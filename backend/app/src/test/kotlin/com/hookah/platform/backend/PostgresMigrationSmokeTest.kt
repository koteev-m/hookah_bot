package com.hookah.platform.backend

import com.hookah.platform.backend.test.PostgresTestEnv
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import java.sql.DriverManager
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PostgresMigrationSmokeTest {
    @Test
    fun `health endpoint works and billing tables exist on postgres`() =
        testApplication {
            val database = PostgresTestEnv.createDatabase()
            environment {
                config = PostgresTestEnv.buildConfig(database)
            }
            application { module() }

            val response = client.get("/health")
            assertEquals(HttpStatusCode.OK, response.status)
            assertTrue(response.bodyAsText().contains("ok"))

            DriverManager.getConnection(database.jdbcUrl, database.user, database.password).use { connection ->
                connection.prepareStatement("SELECT 1 FROM billing_invoices LIMIT 1").use { statement ->
                    statement.executeQuery().use { resultSet ->
                        resultSet.next()
                    }
                }
                connection.prepareStatement("SELECT 1 FROM billing_payments LIMIT 1").use { statement ->
                    statement.executeQuery().use { resultSet ->
                        resultSet.next()
                    }
                }
            }
        }
}
