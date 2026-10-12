package com.hookah.platform.backend.miniapp.guest

import com.hookah.platform.backend.miniapp.guest.api.TableResolveResponse
import com.hookah.platform.backend.miniapp.session.SessionTokenConfig
import com.hookah.platform.backend.miniapp.session.SessionTokenService
import com.hookah.platform.backend.module
import com.hookah.platform.backend.test.PostgresTestEnv
import io.ktor.client.request.get
import io.ktor.client.request.headers
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.config.MapApplicationConfig
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.ValueSource
import java.sql.Connection
import java.sql.DriverManager
import java.sql.Timestamp
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

open class GuestTableResolveAvailabilityTest {
    protected open fun database(): Triple<String, String, String> =
        Triple(
            "jdbc:h2:mem:resolve-${UUID.randomUUID()};MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
            "sa",
            "",
        )

    @ParameterizedTest
    @ValueSource(strings = ["CANCELED", "SUSPENDED", "SUSPENDED_BY_PLATFORM"])
    fun `blocked subscription never creates session tab membership or analytics`(subscription: String) =
        withFixture(subscription) { connection, token ->
            val before = snapshot(connection)
            for (query in listOf("", "&resolveMode=create", "&tableSessionId=987654&resolveMode=create")) {
                val response = resolve(token, query)
                assertEquals(HttpStatusCode.OK, response.status)
                val payload = Json.decodeFromString<TableResolveResponse>(response.bodyAsText())
                assertFalse(payload.available)
                assertEquals("SUBSCRIPTION_BLOCKED", payload.unavailableReason)
                assertEquals(before, snapshot(connection), query)
                assertFalse(payload.tableSessionActive)
                if (!query.contains("tableSessionId")) {
                    assertEquals(0L, payload.tableSessionId)
                    assertEquals("UNKNOWN", payload.tableSessionStatus)
                    assertEquals("TABLE_SESSION_MISSING", payload.tableSessionInactiveReason)
                }
            }
        }

    @ParameterizedTest
    @CsvSource(
        "CANCELED,create,ACTIVE",
        "CANCELED,explicit,ACTIVE",
        "SUSPENDED,create,ACTIVE",
        "SUSPENDED,explicit,ACTIVE",
        "SUSPENDED_BY_PLATFORM,create,ACTIVE",
        "SUSPENDED_BY_PLATFORM,explicit,ACTIVE",
        "SUSPENDED_BY_PLATFORM,create,EXPIRED",
        "SUSPENDED_BY_PLATFORM,explicit,EXPIRED",
        "SUSPENDED_BY_PLATFORM,create,ENDED",
        "SUSPENDED_BY_PLATFORM,explicit,ENDED",
    )
    fun `blocked resolve preserves existing sessions tabs memberships exits and events`(
        subscription: String,
        mode: String,
        state: String,
    ) = withFixture(subscription) { connection, token ->
        seedSession(connection, state)
        val before = snapshot(connection)
        val query = if (mode == "create") "&resolveMode=create" else "&tableSessionId=301&resolveMode=create"
        repeat(2) {
            val response = resolve(token, query)
            assertEquals(HttpStatusCode.OK, response.status)
            val payload = Json.decodeFromString<TableResolveResponse>(response.bodyAsText())
            assertFalse(payload.available)
            assertEquals("SUBSCRIPTION_BLOCKED", payload.unavailableReason)
            assertEquals(before, snapshot(connection), "$mode/$state")
            if (mode == "explicit") {
                assertEquals(301L, payload.tableSessionId)
                assertEquals(state == "ACTIVE", payload.tableSessionActive)
                assertEquals(state, payload.tableSessionStatus)
                assertEquals(
                    when (state) {
                        "EXPIRED" -> "TABLE_SESSION_EXPIRED"
                        "ENDED" -> "TABLE_SESSION_ENDED"
                        else -> null
                    },
                    payload.tableSessionInactiveReason,
                )
            }
        }
    }

    @ParameterizedTest
    @ValueSource(strings = ["table", "token", "venue", "foreign-session"])
    fun `unavailable or foreign context has no related writes`(scenario: String) =
        withFixture("ACTIVE") { connection, token ->
            seedSession(connection, "ACTIVE")
            connection.createStatement().use { statement ->
                statement.executeUpdate(
                    when (scenario) {
                        "table" -> "UPDATE venue_tables SET is_active = FALSE WHERE id = 201"
                        "token" -> "UPDATE table_tokens SET is_active = FALSE WHERE table_id = 201"
                        "venue" -> "UPDATE venues SET status = 'SUSPENDED' WHERE id = 101"
                        else -> "UPDATE table_sessions SET venue_id = 102, table_id = 202 WHERE id = 301"
                    },
                )
            }
            val before = snapshot(connection)
            val response = resolve(token, "&tableSessionId=301&resolveMode=create")
            assertEquals(
                if (scenario == "foreign-session") HttpStatusCode.OK else HttpStatusCode.NotFound,
                response.status,
            )
            if (scenario == "foreign-session") {
                val payload = Json.decodeFromString<TableResolveResponse>(response.bodyAsText())
                assertFalse(payload.tableSessionActive)
                assertEquals("UNKNOWN", payload.tableSessionStatus)
            }
            assertEquals(before, snapshot(connection))
        }

    @ParameterizedTest
    @ValueSource(strings = ["TRIAL", "ACTIVE", "PAST_DUE"])
    fun `available reopen touches same session and clears only own exit`(subscription: String) =
        withFixture(subscription) { connection, token ->
            seedSession(connection, "ACTIVE")
            val before = snapshot(connection)
            for (query in listOf("&tableSessionId=301", "&resolveMode=create", "&tableSessionId=301")) {
                val response = resolve(token, query)
                assertEquals(HttpStatusCode.OK, response.status)
                val payload = Json.decodeFromString<TableResolveResponse>(response.bodyAsText())
                assertTrue(payload.available)
                assertTrue(payload.tableSessionActive)
                assertEquals(301L, payload.tableSessionId)
                assertNull(payload.unavailableReason)
            }
            val after = snapshot(connection)
            assertNotEquals(before.getValue("table_sessions"), after.getValue("table_sessions"))
            assertEquals(1, after.getValue("table_sessions").size)
            assertEquals(3, after.getValue("tab").size)
            assertEquals(4, after.getValue("tab_member").size)
            assertEquals(1, after.getValue("guest_table_session_exits").size)
            connection.createStatement().use { statement ->
                statement.executeQuery("SELECT user_id FROM guest_table_session_exits").use { rows ->
                    assertTrue(rows.next())
                    assertEquals(502L, rows.getLong(1))
                }
                statement.executeQuery(
                    "SELECT COUNT(*) FROM tab WHERE type = 'PERSONAL' AND owner_user_id = 501",
                ).use { rows ->
                    assertTrue(rows.next())
                    assertEquals(1, rows.getInt(1))
                }
            }
            assertEquals(before.getValue("analytics_events"), after.getValue("analytics_events"))
        }

    private fun withFixture(
        subscription: String,
        test: suspend ApplicationTestBuilder.(Connection, String) -> Unit,
    ) = testApplication {
        val (url, user, password) = database()
        val config =
            MapApplicationConfig(
                "app.env" to "test",
                "api.session.jwtSecret" to "test-secret",
                "db.jdbcUrl" to url,
                "db.user" to user,
                "db.password" to password,
                "db.maxPoolSize" to "3",
                "telegram.enabled" to "false",
            )
        environment { this.config = config }
        application { module() }
        client.get("/health")
        DriverManager.getConnection(url, user, password).use { connection ->
            connection.createStatement().use { statement ->
                statement.executeUpdate(
                    "INSERT INTO users (telegram_user_id, first_name) VALUES (501, 'Guest'), (502, 'Other')",
                )
                statement.executeUpdate(
                    "INSERT INTO venues (id, name, city, address, status) " +
                        "OVERRIDING SYSTEM VALUE VALUES " +
                        "(101, 'Venue', 'City', 'Address', 'PUBLISHED'), " +
                        "(102, 'Other', 'City', 'Address', 'PUBLISHED')",
                )
                statement.executeUpdate(
                    "INSERT INTO venue_tables (id, venue_id, table_number) " +
                        "OVERRIDING SYSTEM VALUE VALUES (201, 101, 1), (202, 102, 2)",
                )
                statement.executeUpdate(
                    "INSERT INTO table_tokens (token, table_id, is_active) VALUES ('availability-token', 201, TRUE)",
                )
            }
            connection.prepareStatement(
                "INSERT INTO venue_subscriptions (venue_id, status, trial_end) VALUES (101, ?, ?)",
            ).use {
                it.setString(1, subscription)
                it.setTimestamp(2, Timestamp.from(Instant.now().plus(7, ChronoUnit.DAYS)))
                it.executeUpdate()
            }
            val token = SessionTokenService(SessionTokenConfig.from(config, "test")).issueToken(501).token
            test(connection, token)
        }
    }

    private suspend fun ApplicationTestBuilder.resolve(
        token: String,
        query: String,
    ) = client.get("/api/guest/table/resolve?tableToken=availability-token$query") {
        headers { append(HttpHeaders.Authorization, "Bearer $token") }
    }

    private fun seedSession(
        connection: Connection,
        state: String,
    ) {
        val now = Instant.now().truncatedTo(ChronoUnit.SECONDS)
        connection.prepareStatement(
            "INSERT INTO table_sessions " +
                "(id, venue_id, table_id, started_at, last_activity_at, expires_at, ended_at, status) " +
                "OVERRIDING SYSTEM VALUE VALUES (301, 101, 201, ?, ?, ?, ?, ?)",
        ).use {
            it.setTimestamp(1, Timestamp.from(now.minus(2, ChronoUnit.HOURS)))
            it.setTimestamp(2, Timestamp.from(now.minus(1, ChronoUnit.HOURS)))
            it.setTimestamp(3, Timestamp.from(now.plus(if (state == "EXPIRED") -1 else 1, ChronoUnit.HOURS)))
            it.setTimestamp(4, if (state == "ENDED") Timestamp.from(now.minusSeconds(60)) else null)
            it.setString(5, if (state == "ENDED") "ENDED" else "ACTIVE")
            it.executeUpdate()
        }
        connection.createStatement().use {
            it.executeUpdate(
                "INSERT INTO tab (id, venue_id, table_session_id, type, owner_user_id, status) " +
                    "OVERRIDING SYSTEM VALUE VALUES " +
                    "(401, 101, 301, 'PERSONAL', 502, 'ACTIVE'), (402, 101, 301, 'SHARED', 502, 'ACTIVE')",
            )
            it.executeUpdate(
                "INSERT INTO tab_member (tab_id, user_id, role) " +
                    "VALUES (401, 502, 'OWNER'), (402, 502, 'OWNER'), (402, 501, 'MEMBER')",
            )
            it.executeUpdate(
                "INSERT INTO guest_table_session_exits (user_id, table_session_id, exited_at) " +
                    "VALUES (501, 301, CURRENT_TIMESTAMP), (502, 301, CURRENT_TIMESTAMP)",
            )
        }
    }

    private fun snapshot(connection: Connection): Map<String, List<List<String?>>> =
        listOf(
            "table_sessions",
            "tab",
            "tab_member",
            "guest_table_session_exits",
            "analytics_events",
            "venue_subscriptions",
        ).associateWith { table ->
            connection.createStatement().use { statement ->
                statement.executeQuery("SELECT * FROM $table ORDER BY 1, 2").use { rows ->
                    buildList {
                        while (rows.next()) {
                            add((1..rows.metaData.columnCount).map { rows.getString(it) })
                        }
                    }
                }
            }
        }
}

class GuestTableResolveAvailabilityPostgresTest : GuestTableResolveAvailabilityTest() {
    override fun database(): Triple<String, String, String> {
        val database = PostgresTestEnv.createDatabase()
        return Triple(database.jdbcUrl, database.user, database.password)
    }
}
