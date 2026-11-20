package com.hookah.platform.backend.miniapp.guest

import com.hookah.platform.backend.ModuleOverrides
import com.hookah.platform.backend.miniapp.guest.api.GuestTabsResponse
import com.hookah.platform.backend.miniapp.guest.api.TableResolveResponse
import com.hookah.platform.backend.miniapp.guest.api.TableRestoreResponse
import com.hookah.platform.backend.miniapp.session.SessionTokenConfig
import com.hookah.platform.backend.miniapp.session.SessionTokenService
import com.hookah.platform.backend.moduleWithOverrides
import com.hookah.platform.backend.test.PostgresTestEnv
import io.ktor.client.request.get
import io.ktor.client.request.headers
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.config.MapApplicationConfig
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Test
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
    @ParameterizedTest
    @ValueSource(strings = ["test", "staging", "prod", "production", "dev", "", "unknown"])
    fun `cleanup override can only disable cleanup in test environment`(appEnv: String) {
        assertTrue(ModuleOverrides().isTableSessionCleanupEnabled(appEnv))
        assertTrue(ModuleOverrides(tableSessionCleanupEnabled = true).isTableSessionCleanupEnabled(appEnv))
        assertEquals(
            appEnv != "test",
            ModuleOverrides(tableSessionCleanupEnabled = false).isTableSessionCleanupEnabled(appEnv),
        )
    }

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
            for (query in listOf(
                "&tableSessionId=301",
                "&resolveMode=create",
                "&tableSessionId=301&resolveMode=create",
            )) {
                val response = resolve(token, query)
                assertEquals(HttpStatusCode.OK, response.status)
                val payload = Json.decodeFromString<TableResolveResponse>(response.bodyAsText())
                assertTrue(payload.available)
                assertFalse(payload.tableSessionActive)
                assertEquals(301L, payload.tableSessionId)
                assertEquals("TABLE_SESSION_EXITED", payload.tableSessionInactiveReason)
                assertNull(payload.unavailableReason)
                assertEquals(before, snapshot(connection))
            }
            val event =
                Json.decodeFromString<TableResolveResponse>(
                    resolve(token, "&resolveMode=create").bodyAsText(),
                ).exitEventId
            val accepted = rejoin(token, checkNotNull(event))
            assertEquals(HttpStatusCode.OK, accepted.status)
            assertTrue(Json.decodeFromString<TableResolveResponse>(accepted.bodyAsText()).tableSessionActive)
            val after = snapshot(connection)
            assertNotEquals(before.getValue("table_sessions"), after.getValue("table_sessions"))
            assertEquals(1, after.getValue("table_sessions").size)
            assertEquals(before.getValue("tab"), after.getValue("tab"))
            assertEquals(before.getValue("tab_member"), after.getValue("tab_member"))
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
                    assertEquals(0, rows.getInt(1))
                }
            }
            assertEquals(before.getValue("analytics_events"), after.getValue("analytics_events"))
        }

    @Test
    fun `exact restore verifies membership session and exit without changing rows after order closure`() =
        withFixture("ACTIVE") { connection, token ->
            seedSession(connection, "ACTIVE")

            val exact = "?tableToken=availability-token&tableSessionId=301"
            assertNull(restore(token, exact).context)
            connection.createStatement().use {
                it.executeUpdate("DELETE FROM guest_table_session_exits WHERE user_id = 501")
                it.executeUpdate(
                    "INSERT INTO orders (venue_id, table_id, table_session_id, status) " +
                        "VALUES (101, 201, 301, 'CLOSED')",
                )
            }
            val before = snapshot(connection)
            assertNull(restore(token, "").context)
            assertEquals(301L, restore(token, "?scope=active-visits").context?.tableSessionId)
            assertNull(restore(issueToken(502), "?scope=active-visits").context)
            assertEquals(402L, restore(token, exact).context?.tabId)
            assertEquals(HttpStatusCode.OK, resolve(token, "&tableSessionId=301&verifyOnly=true").status)
            val other = resolve(issueToken(503), "&tableSessionId=301&verifyOnly=true")
            assertEquals(HttpStatusCode.Forbidden, other.status)
            assertNull(restore(token, "?tableToken=availability-token&tableSessionId=302").context)
            assertNull(restore(token, "?tableToken=another-token&tableSessionId=301").context)
            assertEquals(before, snapshot(connection))
            connection.createStatement().use { it.executeUpdate("DELETE FROM tab_member WHERE user_id = 501") }
            assertNull(restore(token, exact).context)
        }

    @Test
    fun `mode selection includes the current bot visit before a tab exists`() =
        withFixture("ACTIVE") { connection, token ->
            seedSession(connection, "ACTIVE")
            val guard = "?scope=active-visits"
            connection.createStatement().use { sql ->
                sql.executeUpdate("DELETE FROM guest_table_session_exits")
                sql.executeUpdate("DELETE FROM tab_member")
                sql.executeUpdate("DELETE FROM tab")
                sql.executeUpdate(
                    "INSERT INTO telegram_chat_context " +
                        "(chat_id,user_id,venue_id,table_id,table_token,table_session_id) " +
                        "VALUES (601,501,101,201,'availability-token',301)",
                )
                val before = snapshot(connection)
                assertNull(restore(token, "").context)
                val visit = restore(token, guard).context
                assertEquals(301L, visit?.tableSessionId)
                assertNull(visit?.tabId)
                assertNull(restore(issueToken(502), guard).context)
                assertEquals(before, snapshot(connection))
                sql.executeUpdate("UPDATE venues SET status='DRAFT' WHERE id=101")
                assertNull(restore(token, guard).context)
                sql.executeUpdate("INSERT INTO venue_members (venue_id,user_id,role) VALUES (101,501,'STAFF')")
                assertEquals(301L, restore(token, guard).context?.tableSessionId)
                sql.executeUpdate("INSERT INTO guest_table_session_exits (user_id,table_session_id) VALUES (501,301)")
                assertNull(restore(token, guard).context)
                sql.executeUpdate("DELETE FROM guest_table_session_exits")
                sql.executeUpdate("UPDATE telegram_chat_context SET updated_at=TIMESTAMP '2020-01-01 00:00:00'")
                assertEquals(301L, restore(token, guard).context?.tableSessionId)
                sql.executeUpdate("UPDATE telegram_chat_context SET table_session_id = NULL")
                assertNull(restore(token, guard).context)
            }
        }

    @Test
    fun `verification does not provision a missing subscription`() =
        withFixture("ACTIVE") { connection, token ->
            seedSession(connection, "ACTIVE")
            connection.createStatement().use {
                it.executeUpdate("DELETE FROM guest_table_session_exits WHERE user_id = 501")
                it.executeUpdate(
                    "INSERT INTO orders (venue_id, table_id, table_session_id, status) " +
                        "VALUES (101, 201, 301, 'CLOSED')",
                )
                it.executeUpdate("DELETE FROM venue_subscriptions WHERE venue_id = 101")
            }
            val before = snapshot(connection)
            assertNull(restore(token, "?tableToken=availability-token&tableSessionId=301").context)
            assertNull(restore(token, "?scope=active-visits").context)
            val response = resolve(token, "&tableSessionId=301")
            assertEquals(HttpStatusCode.OK, response.status)
            assertFalse(Json.decodeFromString<TableResolveResponse>(response.bodyAsText()).available)
            assertEquals(before, snapshot(connection))
        }

    @Test
    fun `newer order at A does not replace verified B or authorize an unavailable shared tab`() =
        withFixture("ACTIVE") { connection, token ->
            seedSession(connection, "ACTIVE")
            connection.createStatement().use {
                it.executeUpdate("DELETE FROM guest_table_session_exits WHERE user_id = 501")
                it.executeUpdate("INSERT INTO table_tokens (token, table_id) VALUES ('scanned-b', 202)")
                it.executeUpdate(
                    "INSERT INTO venue_subscriptions (venue_id, status, trial_end) " +
                        "SELECT 102, status, trial_end FROM venue_subscriptions WHERE venue_id = 101",
                )
                it.executeUpdate(
                    "INSERT INTO table_sessions " +
                        "(id, venue_id, table_id, started_at, last_activity_at, expires_at, status) " +
                        "OVERRIDING SYSTEM VALUE SELECT 302, 102, 202, " +
                        "started_at, last_activity_at, expires_at, status " +
                        "FROM table_sessions WHERE id = 301",
                )
                it.executeUpdate(
                    "INSERT INTO tab (id, venue_id, table_session_id, type, owner_user_id, status) " +
                        "OVERRIDING SYSTEM VALUE VALUES " +
                        "(121, 102, 302, 'PERSONAL', 501, 'ACTIVE'), (122, 102, 302, 'SHARED', 502, 'ACTIVE')",
                )
                it.executeUpdate(
                    "INSERT INTO tab_member (tab_id, user_id, role) VALUES (121, 501, 'OWNER'), (122, 501, 'MEMBER')",
                )
                it.executeUpdate(
                    "INSERT INTO orders (venue_id, table_id, table_session_id, status, updated_at) " +
                        "SELECT venue_id, table_id, id, 'ACTIVE', " +
                        "CASE WHEN id = 301 THEN expires_at ELSE started_at END " +
                        "FROM table_sessions",
                )
            }
            assertEquals(301L, restore(token, "").context?.tableSessionId)
            val exact = "?tableToken=scanned-b&tableSessionId=302"
            val before = snapshot(connection)
            val restored = restore(token, exact).context
            assertEquals(302L, restored?.tableSessionId)
            assertEquals(121L, restored?.tabId)
            assertEquals(before, snapshot(connection))

            suspend fun availableTabs(): Set<Long> {
                val response =
                    client.get("/api/guest/tabs?table_session_id=302") {
                        headers { append(HttpHeaders.Authorization, "Bearer $token") }
                    }
                assertEquals(HttpStatusCode.OK, response.status)
                return Json.decodeFromString<GuestTabsResponse>(response.bodyAsText()).tabs.map { it.id }.toSet()
            }
            assertEquals(setOf(121L, 122L), availableTabs())
            connection.createStatement().use {
                it.executeUpdate("DELETE FROM tab_member WHERE user_id = 501 AND tab_id = 122")
            }
            assertEquals(setOf(121L), availableTabs())

            val guard = "?scope=active-visits"
            connection.createStatement().use {
                it.executeUpdate("UPDATE venue_subscriptions SET status = 'SUSPENDED' WHERE venue_id = 101")
            }
            val withBlockedVenue = snapshot(connection)
            assertEquals(302L, restore(token, guard).context?.tableSessionId)
            assertEquals(withBlockedVenue, snapshot(connection))
            connection.createStatement().use {
                it.executeUpdate("UPDATE venue_subscriptions SET status = 'ACTIVE' WHERE venue_id = 101")
                it.executeUpdate("UPDATE orders SET status = 'CLOSED'")
                it.executeUpdate("INSERT INTO guest_table_session_exits (user_id, table_session_id) VALUES (501, 302)")
            }
            val afterExit = snapshot(connection)
            assertNull(restore(token, exact).context)
            assertEquals(301L, restore(token, guard).context?.tableSessionId)
            assertEquals(afterExit, snapshot(connection))
        }

    @Test
    fun `only the current exit of this actor can be consumed even when exit times match`() =
        withFixture("ACTIVE") { connection, token ->
            seedSession(connection, "ACTIVE")
            val other = issueToken(502)
            val secondClient = issueToken(501)
            val first = checkNotNull(scan(token).exitEventId)
            val otherEvent = checkNotNull(scan(other).exitEventId)
            val before = snapshot(connection)
            assertNotEquals(first, otherEvent)
            assertEquals(HttpStatusCode.Conflict, rejoin(other, first).status)
            assertEquals(HttpStatusCode.Conflict, rejoin(token, first, sessionId = 999).status)
            assertEquals(HttpStatusCode.BadRequest, rejoin(token, "0").status)
            assertEquals(before, snapshot(connection))
            assertEquals(HttpStatusCode.OK, rejoin(token, first).status)
            assertTrue(scan(token).tableSessionActive)
            assertEquals(HttpStatusCode.OK, endVisit(secondClient).status)
            connection.createStatement().use {
                it.executeUpdate(
                    "UPDATE guest_table_session_exits SET exited_at = " +
                        "(SELECT exited_at FROM guest_table_session_exits WHERE user_id = 502) WHERE user_id = 501",
                )
            }
            val second = checkNotNull(scan(token).exitEventId)
            assertNotEquals(first, second)
            val afterExit = snapshot(connection)
            assertEquals(HttpStatusCode.Conflict, rejoin(token, first).status)
            assertFalse(
                Json.decodeFromString<TableResolveResponse>(
                    resolve(token, "&tableSessionId=301").bodyAsText(),
                ).tableSessionActive,
            )
            assertNull(restore(token, "?tableToken=availability-token&tableSessionId=301").context)
            assertEquals(afterExit, snapshot(connection))
            assertEquals(otherEvent, scan(other).exitEventId)
        }

    @Test
    fun `concurrent exit wins against a rejoin command for the preceding exit`() =
        withFixture("ACTIVE") { connection, token ->
            seedSession(connection, "ACTIVE")
            val secondClient = issueToken(501)
            val previous = checkNotNull(scan(token).exitEventId)
            val start = CompletableDeferred<Unit>()
            coroutineScope {
                val join =
                    async(Dispatchers.Default) {
                        start.await()
                        rejoin(token, previous).status
                    }
                val exit =
                    async(Dispatchers.Default) {
                        start.await()
                        endVisit(secondClient).status
                    }
                start.complete(Unit)
                assertEquals(HttpStatusCode.OK, exit.await())
                assertTrue(join.await() in setOf(HttpStatusCode.OK, HttpStatusCode.Conflict))
            }
            val current = checkNotNull(scan(token).exitEventId)
            assertNotEquals(previous, current)
            val before = snapshot(connection)
            assertEquals(HttpStatusCode.Conflict, rejoin(token, previous).status)
            assertEquals(before, snapshot(connection))
        }

    @Test
    fun `rejoin fails closed without current membership or a usable subscription`() =
        withFixture("ACTIVE") { connection, token ->
            seedSession(connection, "ACTIVE")
            val event = checkNotNull(scan(token).exitEventId)
            connection.createStatement().use { it.executeUpdate("DELETE FROM tab_member WHERE user_id = 501") }
            val withoutMember = snapshot(connection)
            assertEquals(HttpStatusCode.Forbidden, rejoin(token, event).status)
            assertEquals(withoutMember, snapshot(connection))
            connection.createStatement().use {
                it.executeUpdate(
                    "DELETE FROM venue_subscriptions WHERE venue_id = 101",
                )
            }
            val withoutSubscription = snapshot(connection)
            assertEquals(HttpStatusCode.Locked, rejoin(token, event).status)
            assertEquals(withoutSubscription, snapshot(connection))
        }

    private fun issueToken(userId: Long): String =
        SessionTokenService(
            SessionTokenConfig.from(MapApplicationConfig("api.session.jwtSecret" to "test-secret"), "test"),
        )
            .issueToken(userId).token

    private suspend fun ApplicationTestBuilder.scan(token: String): TableResolveResponse =
        Json.decodeFromString(resolve(token, "&resolveMode=create").bodyAsText())

    private suspend fun ApplicationTestBuilder.endVisit(token: String) =
        client.post("/api/guest/table/session/end") {
            headers { append(HttpHeaders.Authorization, "Bearer $token") }
            contentType(ContentType.Application.Json)
            setBody("""{"tableToken":"availability-token","tableSessionId":301}""")
        }

    private suspend fun ApplicationTestBuilder.rejoin(
        token: String,
        event: String,
        sessionId: Long = 301,
    ) = client.post("/api/guest/table/session/rejoin") {
        headers { append(HttpHeaders.Authorization, "Bearer $token") }
        contentType(ContentType.Application.Json)
        setBody("""{"tableToken":"availability-token","tableSessionId":$sessionId,"exitEventId":"$event"}""")
    }

    private suspend fun ApplicationTestBuilder.restore(
        token: String,
        query: String,
    ): TableRestoreResponse {
        val response =
            client.get("/api/guest/table/restore$query") {
                headers { append(HttpHeaders.Authorization, "Bearer $token") }
            }
        assertEquals(HttpStatusCode.OK, response.status)
        return Json.decodeFromString(response.bodyAsText())
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
                // The billing startup tick can create venue 102's missing subscription
                // between snapshots, independently of the resolve request under test.
                "billing.subscription.intervalSeconds" to "0",
            )
        environment { this.config = config }
        application { moduleWithOverrides(ModuleOverrides(tableSessionCleanupEnabled = false)) }
        client.get("/health")
        DriverManager.getConnection(url, user, password).use { connection ->
            connection.createStatement().use { statement ->
                if (connection.metaData.databaseProductName == "H2") {
                    // Telegram's PostgreSQL baseline is provided explicitly by H2 route fixtures.
                    statement.executeUpdate(
                        "CREATE TABLE telegram_chat_context (table_session_id BIGINT REFERENCES table_sessions(id), " +
                            "chat_id BIGINT PRIMARY KEY, user_id BIGINT NOT NULL, " +
                            "venue_id BIGINT, table_id BIGINT, table_token VARCHAR(64), " +
                            "updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP)",
                    )
                }
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
            "orders",
            "order_batches",
            "audit_log",
            "analytics_events",
            "venue_subscriptions",
            "telegram_chat_context",
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
