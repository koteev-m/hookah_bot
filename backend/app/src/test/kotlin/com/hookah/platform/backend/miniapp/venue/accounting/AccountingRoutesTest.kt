package com.hookah.platform.backend.miniapp.venue.accounting
import com.hookah.platform.backend.miniapp.session.SessionTokenConfig
import com.hookah.platform.backend.miniapp.session.SessionTokenService
import com.hookah.platform.backend.module
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.config.MapApplicationConfig
import io.ktor.server.testing.testApplication
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AccountingRoutesTest {
    @Test
    fun `JWT actor staff operations manager close and foreign history denial`() =
        testApplication {
            val url =
                "jdbc:h2:mem:accounting_routes_${UUID.randomUUID()}" +
                    ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE"
            val config =
                MapApplicationConfig(
                    "app.env" to "test",
                    "api.session.jwtSecret" to "test-secret",
                    "db.jdbcUrl" to url,
                    "db.user" to "sa",
                    "db.password" to "",
                    "telegram.enabled" to "false",
                    "billing.subscriptionJob.enabled" to "false",
                )
            environment { this.config = config }
            application { module() }
            client.get("/health")
            val tokens = SessionTokenService(SessionTokenConfig.from(config, "test"))
            val json = Json { ignoreUnknownKeys = true }
            HikariDataSource(
                HikariConfig().apply {
                    jdbcUrl = url
                    username = "sa"
                    password = ""
                },
            ).use { ds ->
                seedAccounting(ds)

                suspend fun send(
                    actor: Long,
                    command: AccountingCommand,
                ) = client.post("/api/venue/1/accounting/commands") {
                    header(HttpHeaders.Authorization, "Bearer ${tokens.issueToken(actor).token}")
                    contentType(ContentType.Application.Json)
                    setBody(Json.encodeToString(command))
                }
                val anonymous = client.get("/api/venue/1/accounting")
                assertEquals(HttpStatusCode.Unauthorized, anonymous.status)
                val foreign =
                    client.get("/api/venue/1/accounting") {
                        header(
                            HttpHeaders.Authorization,
                            "Bearer ${tokens.issueToken(201).token}",
                        )
                    }
                assertEquals(HttpStatusCode.Forbidden, foreign.status)
                val opened = send(103, command("OPEN"))
                assertEquals(HttpStatusCode.OK, opened.status)
                val shift = json.decodeFromString<AccountingReport>(opened.bodyAsText())
                val created = send(103, manual(shift.shiftId))
                assertEquals(HttpStatusCode.OK, created.status)
                val report = json.decodeFromString<AccountingReport>(created.bodyAsText())
                val close =
                    command(
                        "CLOSE",
                        shift.shiftId,
                    ).copy(
                        expectedReportHash = report.reportHash,
                        acknowledgeUnpaid = true,
                    )
                assertEquals(HttpStatusCode.Forbidden, send(103, close).status)
                assertEquals(HttpStatusCode.OK, send(102, close).status)
                val staffHistory =
                    client.get("/api/venue/1/accounting/${shift.shiftId}") {
                        header(
                            HttpHeaders.Authorization,
                            "Bearer ${tokens.issueToken(103).token}",
                        )
                    }
                assertEquals(HttpStatusCode.Forbidden, staffHistory.status)
                val ownerHistory =
                    client.get("/api/venue/1/accounting/${shift.shiftId}") {
                        header(
                            HttpHeaders.Authorization,
                            "Bearer ${tokens.issueToken(101).token}",
                        )
                    }
                assertEquals(HttpStatusCode.OK, ownerHistory.status)
                assertTrue(ownerHistory.bodyAsText().contains("CLOSED"))
                ds.connection.use { c ->
                    assertEquals(2, c.count("venue_accounting_events", "actor_user_id = 103"))
                    assertEquals(1, c.count("venue_accounting_events", "actor_user_id = 102"))
                }
            }
        }
}
