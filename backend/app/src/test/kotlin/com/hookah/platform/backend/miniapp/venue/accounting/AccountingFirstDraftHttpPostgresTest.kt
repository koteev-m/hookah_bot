package com.hookah.platform.backend.miniapp.venue.accounting

import com.hookah.platform.backend.api.DatabaseUnavailableException
import com.hookah.platform.backend.miniapp.session.SessionTokenConfig
import com.hookah.platform.backend.miniapp.session.SessionTokenService
import com.hookah.platform.backend.miniapp.venue.TransactionalAuditLogWriter
import com.hookah.platform.backend.module
import com.hookah.platform.backend.test.PostgresTestEnv
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path
import java.sql.SQLException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals

class AccountingFirstDraftHttpPostgresTest {
    @Test
    fun `browser quota recovery commands and replays create only intended sales and audits`() = verify(bound = true)

    @Test
    fun `first unbound browser sale opens once and preserves closed debt in the next period`() = verify(bound = false)

    private fun verify(bound: Boolean) =
        testApplication {
            val database = PostgresTestEnv.createDatabase()
            val config = PostgresTestEnv.buildConfig(database)
            environment { this.config = config }
            application { module() }
            client.get("/health")
            val tokens = SessionTokenService(SessionTokenConfig.from(config, "test"))

            suspend fun send(
                command: AccountingCommand,
                actor: Long = 103,
            ): AccountingReport {
                val response =
                    client.post("/api/venue/1/accounting/commands") {
                        header(HttpHeaders.Authorization, "Bearer ${tokens.issueToken(actor).token}")
                        contentType(ContentType.Application.Json)
                        setBody(Json.encodeToString(command))
                    }
                assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
                return Json.decodeFromString(response.bodyAsText())
            }
            val fixture =
                generateSequence(Path.of("").toAbsolutePath()) { it.parent }
                    .map { it.resolve("miniapp/e2e/fixtures/accounting-first-draft-commands.json") }
                    .first(Files::isRegularFile)
            // Chromium asserts every field against this fixture, normalizing only random request IDs.
            val commands = Json.decodeFromString<List<AccountingCommand>>(Files.readString(fixture))
            assertEquals(2, commands.size)
            assertNotEquals(commands[0].requestId, commands[1].requestId)
            PostgresTestEnv.createDataSource(database, migrate = false).use { ds ->
                seedAccounting(ds)
                val repo = AccountingRepository(ds)
                if (bound) assertEquals(commands[0].shiftId, send(command("OPEN")).shiftId)
                val first = commands[0].let { if (bound) it else it.copy(shiftId = null) }
                val failing =
                    AccountingRepository(
                        ds,
                        audit =
                            TransactionalAuditLogWriter { _, _, action, _, _, _ ->
                                if (action == "VENUE_ACCOUNTING_CREATE") throw SQLException("rollback before commit")
                            },
                    )
                assertFailsWith<DatabaseUnavailableException> { failing.execute(1, 103, first) }
                ds.connection.use { c ->
                    assertEquals(0, c.count("venue_accounting_sales"))
                    assertEquals(0, c.count("audit_log", "action = 'VENUE_ACCOUNTING_CREATE'"))
                    assertEquals(if (bound) 1 else 0, c.count("venue_accounting_shifts"))
                }
                val created = send(first)
                assertEquals(1, created.sales.size)
                assertEquals(12345, created.totals.accruedMinor)
                // Model both an HTTP 408 and a lost successful response: retry the unchanged command.
                repeat(2) { assertEquals(created, send(first)) }
                ds.connection.use { c ->
                    assertEquals(1, c.count("venue_accounting_sales", "shift_id = ${created.shiftId}"))
                    assertEquals(1, c.count("venue_accounting_commands", "request_id = '${first.requestId}'"))
                    assertEquals(1, c.count("audit_log", "action = 'VENUE_ACCOUNTING_CREATE'"))
                    assertEquals(1, c.count("venue_accounting_shifts", "status = 'OPEN'"))
                    assertEquals(0, c.count("orders"))
                }
                val nextCommand = commands[1].copy(shiftId = created.shiftId)
                val next = send(nextCommand)
                assertEquals(created.shiftId, next.shiftId)
                assertEquals(2, next.sales.size)
                assertEquals(19134, next.totals.accruedMinor)
                assertEquals(0, next.totals.paidMinor)
                assertEquals(19134, next.totals.unpaidMinor)
                assertEquals(next, send(nextCommand))
                ds.connection.use { c ->
                    assertEquals(2, c.count("venue_accounting_sales"))
                    assertEquals(2, c.count("audit_log", "action = 'VENUE_ACCOUNTING_CREATE'"))
                }
                val closed =
                    send(
                        command("CLOSE", next.shiftId).copy(
                            expectedReportHash = next.reportHash,
                            acknowledgeUnpaid = true,
                        ),
                        actor = 102,
                    )

                fun frozen(): String =
                    ds.connection.use { c ->
                        c.prepareStatement("SELECT final_report FROM venue_accounting_shifts WHERE id = ?").use { s ->
                            s.setLong(1, next.shiftId)
                            s.executeQuery().use {
                                it.next()
                                it.getString(1)
                            }
                        }
                    }
                val snapshot = frozen()
                val period = send(commands[1].copy(shiftId = null, requestId = "next-period-operation"))
                assertNotEquals(next.shiftId, period.shiftId)
                assertEquals(6789, period.totals.unpaidMinor)
                assertEquals(snapshot, frozen())
                assertEquals(closed, repo.detail(1, 101, next.shiftId))
                assertEquals(listOf(next.shiftId), repo.state(1, 101).history.map { it.id })
                ds.connection.use { c ->
                    assertEquals(3, c.count("venue_accounting_sales"))
                    assertEquals(3, c.count("audit_log", "action = 'VENUE_ACCOUNTING_CREATE'"))
                    assertEquals(1, c.count("venue_accounting_shifts", "status = 'OPEN'"))
                    assertEquals(0, c.count("telegram_outbox"))
                }
            }
        }
}
