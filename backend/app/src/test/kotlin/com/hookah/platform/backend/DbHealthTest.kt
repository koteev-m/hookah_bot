package com.hookah.platform.backend

import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.config.MapApplicationConfig
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DbHealthTest {
    @Test
    fun `db health endpoint returns disabled when db is not configured`() = testApplication {
        environment {
            config = MapApplicationConfig("db.jdbcUrl" to "")
        }
        application { module() }

        val response = client.get("/db/health")
        assertEquals(HttpStatusCode.ServiceUnavailable, response.status)
        assertTrue(response.bodyAsText().contains("disabled"))
    }
}
