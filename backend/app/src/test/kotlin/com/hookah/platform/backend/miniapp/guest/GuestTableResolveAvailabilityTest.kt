package com.hookah.platform.backend.miniapp.guest

import com.hookah.platform.backend.ModuleOverrides
import com.hookah.platform.backend.analytics.KpiRepository
import com.hookah.platform.backend.analytics.KpiRequest
import com.hookah.platform.backend.api.DatabaseUnavailableException
import com.hookah.platform.backend.miniapp.guest.api.GuestTabsResponse
import com.hookah.platform.backend.miniapp.guest.api.TableResolveResponse
import com.hookah.platform.backend.miniapp.guest.api.TableRestoreResponse
import com.hookah.platform.backend.miniapp.guest.api.TableSessionEndRequest
import com.hookah.platform.backend.miniapp.guest.api.TableSessionEndResponse
import com.hookah.platform.backend.miniapp.guest.db.GuestTableActivationResult
import com.hookah.platform.backend.miniapp.guest.db.GuestTableContextLifecycleRepository
import com.hookah.platform.backend.miniapp.guest.db.GuestTabsRepository
import com.hookah.platform.backend.miniapp.guest.db.PlatformGuestTableMutationResult
import com.hookah.platform.backend.miniapp.guest.db.TableSessionRepository
import com.hookah.platform.backend.miniapp.session.SessionTokenConfig
import com.hookah.platform.backend.miniapp.session.SessionTokenService
import com.hookah.platform.backend.miniapp.shift.GuestShiftExtensionOptionsResponse
import com.hookah.platform.backend.miniapp.subscription.db.SubscriptionRepository
import com.hookah.platform.backend.miniapp.venue.orders.VenueOrdersRepository
import com.hookah.platform.backend.miniapp.venue.seating.SeatingCommand
import com.hookah.platform.backend.miniapp.venue.seating.SeatingRepository
import com.hookah.platform.backend.moduleWithOverrides
import com.hookah.platform.backend.telegram.GuestCheckoutNotifications
import com.hookah.platform.backend.telegram.StaffChatNotifier
import com.hookah.platform.backend.telegram.TelegramApiClient
import com.hookah.platform.backend.telegram.TelegramCallResult
import com.hookah.platform.backend.telegram.TelegramOutboxConfig
import com.hookah.platform.backend.telegram.TelegramOutboxEnqueuer
import com.hookah.platform.backend.telegram.TelegramOutboxWorker
import com.hookah.platform.backend.telegram.TelegramRateLimiter
import com.hookah.platform.backend.telegram.TelegramTrafficPolicy
import com.hookah.platform.backend.telegram.db.BotCheckoutScope
import com.hookah.platform.backend.telegram.db.BotPreparedCheckout
import com.hookah.platform.backend.telegram.db.BotPreparedCheckoutRepository
import com.hookah.platform.backend.telegram.db.ChatContextRepository
import com.hookah.platform.backend.telegram.db.DialogStateRepository
import com.hookah.platform.backend.telegram.db.OrderBatchItemInput
import com.hookah.platform.backend.telegram.db.OrdersRepository
import com.hookah.platform.backend.telegram.db.StaffChatNotificationRepository
import com.hookah.platform.backend.telegram.db.TableTokenRepository
import com.hookah.platform.backend.telegram.db.TelegramOutboxRepository
import com.hookah.platform.backend.telegram.db.VenueRepository
import com.hookah.platform.backend.telegram.db.VenueSettingsRepository
import com.hookah.platform.backend.test.PostgresTestEnv
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import io.ktor.client.request.get
import io.ktor.client.request.headers
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.config.MapApplicationConfig
import io.ktor.server.engine.EngineConnectorBuilder
import io.ktor.server.engine.applicationEnvironment
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.ValueSource
import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager
import java.sql.Timestamp
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
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
            assertNull(restore(token, "").context)
            connection.createStatement().use {
                it.executeUpdate("DELETE FROM guest_table_session_exits WHERE user_id = 501")
            }
            val empty = snapshot(connection)
            assertEquals(301L, restore(token, "").context?.tableSessionId)
            assertEquals(empty, snapshot(connection))
            connection.createStatement().use {
                it.executeUpdate(
                    "INSERT INTO orders (venue_id, table_id, table_session_id, status) " +
                        "VALUES (101, 201, 301, 'CLOSED')",
                )
            }
            val before = snapshot(connection)
            assertEquals(301L, restore(token, "").context?.tableSessionId)
            assertEquals(402L, restore(token, "").context?.tabId)
            assertEquals(301L, restore(token, "?scope=active-visits").context?.tableSessionId)
            assertNull(restore(issueToken(502), "?scope=active-visits").context)
            assertEquals(402L, restore(token, exact).context?.tabId)
            assertEquals(HttpStatusCode.OK, resolve(token, "&tableSessionId=301&verifyOnly=true").status)
            val other = resolve(issueToken(503), "&tableSessionId=301&verifyOnly=true")
            assertEquals(HttpStatusCode.Forbidden, other.status)
            assertNull(restore(token, "?tableToken=availability-token&tableSessionId=302").context)
            assertNull(restore(token, "?tableToken=another-token&tableSessionId=301").context)
            assertEquals(before, snapshot(connection))
            connection.createStatement().use {
                it.executeUpdate(
                    "UPDATE table_sessions SET expires_at = CURRENT_TIMESTAMP - INTERVAL '1' MINUTE WHERE id = 301",
                )
            }
            val expired = snapshot(connection)
            assertNull(restore(token, exact).context)
            assertNull(restore(token, "").context)
            assertEquals(expired, snapshot(connection))
            connection.createStatement().use {
                it.executeUpdate(
                    "UPDATE table_sessions SET expires_at = CURRENT_TIMESTAMP + INTERVAL '1' HOUR WHERE id = 301",
                )
                it.executeUpdate("DELETE FROM tab_member WHERE user_id = 501")
            }
            assertNull(restore(token, exact).context)
            assertNull(restore(token, "").context)
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
                val coldStart = restore(token, "").context
                assertEquals(301L, coldStart?.tableSessionId)
                assertNull(coldStart?.tabId)
                val visit = restore(token, guard).context
                assertEquals(301L, visit?.tableSessionId)
                assertNull(visit?.tabId)
                assertNull(restore(issueToken(502), guard).context)
                assertEquals(before, snapshot(connection))
                sql.executeUpdate("UPDATE venues SET status='DRAFT' WHERE id=101")
                assertNull(restore(token, guard).context)
                sql.executeUpdate("INSERT INTO venue_members (venue_id,user_id,role) VALUES (101,501,'STAFF')")
                assertEquals(301L, restore(token, guard).context?.tableSessionId)
                assertNull(restore(token, "").context)
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
            assertNull(restore(token, "").context)
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

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `cold start reads preserve the confirmed visit across venues and closed orders`(platform: Boolean) =
        withFixture("ACTIVE", platformOwner = platform) { connection, token, config ->
            val browserSmoke =
                System.getenv("HT_GUEST_VISIT_BROWSER_TEST") == "1" &&
                    connection.metaData.databaseProductName == "PostgreSQL"
            seedSession(connection, "ACTIVE")
            connection.createStatement().use { sql ->
                sql.executeUpdate("DELETE FROM guest_table_session_exits WHERE user_id = 501")
                sql.executeUpdate("INSERT INTO table_tokens (token, table_id) VALUES ('confirmed-b', 202)")
                sql.executeUpdate(
                    "INSERT INTO venue_subscriptions (venue_id, status, trial_end) " +
                        "SELECT 102, status, trial_end FROM venue_subscriptions WHERE venue_id = 101",
                )
                sql.executeUpdate(
                    "INSERT INTO table_sessions (id,venue_id,table_id,started_at,last_activity_at,expires_at,status) " +
                        "OVERRIDING SYSTEM VALUE SELECT 302,102,202,started_at,last_activity_at,expires_at,status " +
                        "FROM table_sessions WHERE id = 301",
                )
                sql.executeUpdate(
                    "INSERT INTO orders (venue_id, table_id, table_session_id, status) VALUES (101,201,301,'ACTIVE')",
                )
                sql.executeUpdate(
                    "INSERT INTO telegram_chat_context " +
                        "(chat_id,user_id,venue_id,table_id,table_token,table_session_id) " +
                        "VALUES (601,501,102,202,'confirmed-b',302)",
                )
                val beforeBill = snapshot(connection)
                val botOnly = restore(token, "").context
                assertEquals(302L, botOnly?.tableSessionId)
                assertNull(botOnly?.tabId)
                if (browserSmoke) checkBrowserLaunch(config, withBill = false)
                assertEquals(beforeBill, snapshot(connection))
                sql.executeUpdate(
                    "INSERT INTO tab (id,venue_id,table_session_id,type,owner_user_id,status) " +
                        "OVERRIDING SYSTEM VALUE VALUES (403,102,302,'PERSONAL',501,'ACTIVE')",
                )
                sql.executeUpdate("INSERT INTO tab_member (tab_id,user_id,role) VALUES (403,501,'OWNER')")
                sql.executeUpdate(
                    "INSERT INTO orders (venue_id, table_id, table_session_id, status) VALUES (102,202,302,'CLOSED')",
                )
                val before = snapshot(connection)
                val exact = "?tableToken=confirmed-b&tableSessionId=302"
                for (query in listOf("?scope=active-visits", "", exact)) {
                    val restored = restore(token, query).context
                    assertEquals(302L, restored?.tableSessionId)
                    assertEquals(403L, restored?.tabId)
                }
                val reads =
                    listOf(
                        "table/resolve$exact",
                        "staff-call/status$exact",
                        "tabs?table_session_id=302",
                        "order/active$exact&tabId=403",
                    )
                for (path in reads) {
                    val response =
                        client.get("/api/guest/$path") {
                            headers { append(HttpHeaders.Authorization, "Bearer $token") }
                        }
                    assertEquals(HttpStatusCode.OK, response.status, path)
                }
                assertEquals(before, snapshot(connection))
                if (browserSmoke) {
                    checkBrowserLaunch(config, withBill = true)
                    assertEquals(before, snapshot(connection))
                }
                // A second equally recent confirmed chat must not silently break the tie.
                sql.executeUpdate(
                    "INSERT INTO telegram_chat_context " +
                        "(chat_id,user_id,venue_id,table_id,table_token,table_session_id,updated_at) " +
                        "SELECT 602,501,101,201,'availability-token',301,updated_at " +
                        "FROM telegram_chat_context WHERE chat_id=601",
                )
                assertNull(restore(token, "").context)
                assertEquals(302L, restore(token, exact).context?.tableSessionId)
                sql.executeUpdate(
                    "UPDATE telegram_chat_context SET updated_at=TIMESTAMP '2020-01-01 00:00:00' WHERE chat_id=602",
                )
                assertEquals(302L, restore(token, "").context?.tableSessionId)
                val scan =
                    client.get("/api/guest/table/resolve?tableToken=confirmed-b&resolveMode=create") {
                        headers { append(HttpHeaders.Authorization, "Bearer $token") }
                    }
                assertEquals(HttpStatusCode.OK, scan.status)
                sql.executeQuery(
                    "SELECT b.last_activity_at > a.last_activity_at, b.expires_at > a.expires_at " +
                        "FROM table_sessions b, table_sessions a WHERE b.id=302 AND a.id=301",
                ).use {
                    assertTrue(it.next())
                    assertTrue(it.getBoolean(1))
                    assertTrue(it.getBoolean(2))
                }
                val ended =
                    client.post("/api/guest/table/session/end") {
                        headers { append(HttpHeaders.Authorization, "Bearer $token") }
                        contentType(ContentType.Application.Json)
                        setBody("""{"tableToken":"confirmed-b","tableSessionId":302,"expectedVisitGeneration":"0"}""")
                    }
                assertEquals(HttpStatusCode.OK, ended.status)
                assertTrue(Json.decodeFromString<TableSessionEndResponse>(ended.bodyAsText()).ended)
                assertNull(restore(token, exact).context)
                sql.executeQuery(
                    "SELECT COUNT(*) FROM guest_table_session_exits WHERE user_id=501 AND table_session_id=302",
                ).use {
                    assertTrue(it.next())
                    assertEquals(1, it.getInt(1))
                }
            }
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
            assertEquals(first, scan(token).visitGeneration)
            assertEquals(
                first,
                restore(token, "?tableToken=availability-token&tableSessionId=301").context?.visitGeneration,
            )
            assertEquals(HttpStatusCode.OK, endVisit(secondClient, first).status)
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
    fun `duplicate end preserves the outstanding rejoin and never ends the next participation`() =
        withFixture("ACTIVE") { connection, token ->
            seedSession(connection, "ACTIVE")
            val secondClient = issueToken(501)
            val previous = checkNotNull(scan(token).exitEventId)
            assertEquals(HttpStatusCode.OK, endVisit(token, "0").status)
            assertEquals(previous, scan(token).exitEventId)
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
                        endVisit(secondClient, "0").status
                    }
                start.complete(Unit)
                assertTrue(exit.await() in setOf(HttpStatusCode.OK, HttpStatusCode.Conflict))
                assertEquals(HttpStatusCode.OK, join.await())
            }
            assertNull(scan(token).exitEventId)
            assertEquals(previous, scan(token).visitGeneration)
            val before = snapshot(connection)
            assertEquals(HttpStatusCode.Conflict, endVisit(token, "0").status)
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

    @ParameterizedTest
    @ValueSource(strings = ["bot", "miniapp"])
    fun `committed exit and rejoin rotate only this actor's draft generation`(source: String) =
        withFixture("ACTIVE", platformOwner = false) { connection, token, config ->
            seedSession(connection, "ACTIVE")
            connection.createStatement().use {
                it.executeUpdate(
                    "DELETE FROM guest_table_session_exits WHERE user_id = 501",
                )
            }
            val exact = "?tableToken=availability-token&tableSessionId=301"
            assertEquals("0", restore(token, exact).context?.visitGeneration)
            val browserCheck =
                System.getenv("HT_GUEST_VISIT_BROWSER_TEST") == "1" &&
                    connection.metaData.databaseProductName == "PostgreSQL"
            val runCycle: suspend (String?, suspend (String) -> Unit) -> Unit = { engine, browser ->
                val prior = checkNotNull(restore(token, exact).context?.visitGeneration)
                val foreign = snapshot(connection).getValue("guest_table_session_exits").single()
                if (engine == null) {
                    assertTrue(
                        Json.decodeFromString<TableSessionEndResponse>(endVisit(token, prior).bodyAsText()).ended,
                    )
                } else {
                    browser("lose-end")
                }
                val event = checkNotNull(scan(token).exitEventId)
                assertNotEquals(prior, event)
                if (source == "bot") {
                    HikariDataSource().use { ds ->
                        ds.jdbcUrl = config.property("db.jdbcUrl").getString()
                        ds.username = config.property("db.user").getString()
                        ds.password = config.property("db.password").getString()
                        val lifecycle =
                            GuestTableContextLifecycleRepository(
                                ds,
                                TableTokenRepository(ds),
                                SubscriptionRepository(ds),
                                TableSessionRepository(ds),
                                GuestTabsRepository(ds),
                                ChatContextRepository(ds),
                                DialogStateRepository(ds, Json),
                            )
                        assertTrue(
                            lifecycle.activate(
                                501,
                                601,
                                "availability-token",
                                101,
                                201,
                                java.time.Duration.ofHours(4),
                                expectedExitEventId = event.toLong(),
                                ordinaryBot = true,
                            ) is GuestTableActivationResult.Applied,
                        )
                    }
                } else if (engine != null) {
                    browser("lose-rejoin")
                } else {
                    assertEquals(
                        event,
                        Json.decodeFromString<TableResolveResponse>(rejoin(token, event).bodyAsText()).visitGeneration,
                    )
                }
                val after = snapshot(connection)
                assertEquals(listOf(foreign), after.getValue("guest_table_session_exits"))
                assertEquals(listOf(listOf("501", "301", event)), after.getValue("guest_visit_generations"))
                assertEquals(event, restore(token, exact).context?.visitGeneration)
                if (engine != null) browser("reopen")
                assertEquals(after, snapshot(connection))
            }
            if (browserCheck) {
                withBrowserServer(config) { miniapp, port ->
                    for (engine in listOf("chromium", "webkit")) {
                        val state = Files.createTempFile("visit-relaunch-", ".json")
                        try {
                            runCycle(engine) { phase -> runBrowser(miniapp, port, phase, engine, state.toString()) }
                        } finally {
                            Files.deleteIfExists(state)
                        }
                    }
                }
            } else {
                runCycle(null) { error("Browser fixture is disabled") }
            }
        }

    @Test
    fun `only an intentional new checkout adds a second batch after post commit response loss`() =
        withFixture("ACTIVE", platformOwner = false) { connection, _, config ->
            if (System.getenv("HT_GUEST_VISIT_BROWSER_TEST") != "1" ||
                connection.metaData.databaseProductName != "PostgreSQL"
            ) {
                return@withFixture
            }
            seedSession(connection, "ACTIVE")
            connection.createStatement().use { sql ->
                sql.executeUpdate("DELETE FROM guest_table_session_exits WHERE user_id = 501")
                sql.executeUpdate(
                    "INSERT INTO menu_categories(id, venue_id, name, sort_order)" +
                        " OVERRIDING SYSTEM VALUE VALUES (701, 101, 'Кальянное меню', 0)",
                )
                sql.executeUpdate(
                    "INSERT INTO menu_items(id, venue_id, category_id, name, price_minor, currency, is_available)" +
                        " OVERRIDING SYSTEM VALUE VALUES (702, 101, 701, 'Чай', 100, 'RUB', true)",
                )
            }
            withBrowserServer(config) { miniapp, port ->
                for ((index, engine) in listOf("chromium", "webkit").withIndex()) {
                    runBrowser(miniapp, port, "checkout", engine)
                    val saved = snapshot(connection)
                    assertEquals(1, saved.getValue("orders").size)
                    assertEquals((index + 1) * 2, saved.getValue("order_batches").size)
                    assertEquals((index + 1) * 2, saved.getValue("guest_batch_idempotency").size)
                }
            }
        }

    @Test
    fun `generation rolls back with the marker and only one competing rejoin consumes it`() =
        withFixture("ACTIVE") { connection, token ->
            seedSession(connection, "ACTIVE")
            val event = checkNotNull(scan(token).exitEventId)
            val before = snapshot(connection)
            val sessions = TableSessionRepository(null)
            connection.autoCommit = false
            try {
                assertTrue(sessions.consumeExitEvent(connection, 501, 301, event.toLong()))
                assertEquals(event.toLong(), sessions.visitGeneration(connection, 501, 301))
            } finally {
                connection.rollback()
                connection.autoCommit = true
            }
            assertEquals(before, snapshot(connection))
            val start = CompletableDeferred<Unit>()
            coroutineScope {
                val commands =
                    List(2) {
                        async(Dispatchers.IO) {
                            start.await()
                            rejoin(token, event).status
                        }
                    }
                start.complete(Unit)
                assertEquals(setOf(HttpStatusCode.OK, HttpStatusCode.Conflict), commands.map { it.await() }.toSet())
            }
            assertEquals(
                event,
                restore(token, "?tableToken=availability-token&tableSessionId=301").context?.visitGeneration,
            )
            assertEquals(0L, sessions.visitGeneration(connection, 502, 301))
        }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `only a still authorized exact visit reports temporary service suspension`(platform: Boolean) =
        withFixture("CANCELED", platformOwner = platform) { connection, token, _ ->
            seedSession(connection, "ACTIVE")
            connection.createStatement().use {
                it.executeUpdate("DELETE FROM guest_table_session_exits WHERE user_id = 501")
                if (platform) {
                    it.executeUpdate(
                        "INSERT INTO telegram_chat_context " +
                            "(chat_id,user_id,venue_id,table_id,table_token,table_session_id) " +
                            "VALUES (601,501,101,201,'availability-token',301)",
                    )
                }
            }
            val before = snapshot(connection)
            val exact = "?tableToken=availability-token&tableSessionId=301"
            val suspended = restore(token, exact)
            assertNull(suspended.context)
            assertEquals("SUBSCRIPTION_BLOCKED", suspended.unavailableReason)
            assertNull(restore(token, "").unavailableReason)
            assertNull(restore(issueToken(502), exact).unavailableReason)
            assertEquals(before, snapshot(connection))
        }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `paused venue preserves authorized participation and resumes without a passive write`(platform: Boolean) =
        withFixture("ACTIVE", platformOwner = platform) { connection, token, _ ->
            seedSession(connection, "ACTIVE")
            connection.createStatement().use {
                it.executeUpdate("DELETE FROM guest_table_session_exits WHERE user_id = 501")
                if (platform) {
                    it.executeUpdate(
                        "INSERT INTO telegram_chat_context " +
                            "(chat_id,user_id,venue_id,table_id,table_token,table_session_id) " +
                            "VALUES (601,501,101,201,'availability-token',301)",
                    )
                }
                it.executeUpdate("UPDATE venues SET status = 'PAUSED' WHERE id = 101")
            }
            val exact = "?tableToken=availability-token&tableSessionId=301"
            val before = snapshot(connection)
            assertEquals("SERVICE_SUSPENDED", restore(token, exact).unavailableReason)
            val verify = resolve(token, "&tableSessionId=301&verifyOnly=true")
            assertEquals(HttpStatusCode.Locked, verify.status)
            val denied =
                client.post("/api/guest/staff-call") {
                    headers { append(HttpHeaders.Authorization, "Bearer $token") }
                    contentType(ContentType.Application.Json)
                    setBody(
                        """{"tableSessionId":301,"tableToken":"availability-token",
                            "reason":"COME","expectedVisitGeneration":"0"}""",
                    )
                }
            assertTrue(denied.status.value in setOf(403, 404, 423), denied.bodyAsText())
            assertNull(restore(issueToken(502), exact).unavailableReason)
            assertEquals(before, snapshot(connection))
            connection.createStatement().use {
                it.executeUpdate(
                    "UPDATE venues SET status = 'PUBLISHED' WHERE id = 101",
                )
            }
            assertEquals("0", restore(token, exact).context?.visitGeneration)
            assertEquals(before, snapshot(connection))
        }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `incomplete and previous participation commands are rejected before any database effects`(platform: Boolean) =
        withFixture("ACTIVE", platformOwner = platform) { connection, token, _ ->
            seedSession(connection, "ACTIVE")
            val sessions = TableSessionRepository(null)
            assertTrue(
                sessions.consumeExitEvent(
                    connection,
                    501,
                    301,
                    checkNotNull(sessions.findExitEventId(connection, 501, 301)),
                ),
            )
            if (platform) {
                connection.createStatement().use {
                    it.executeUpdate(
                        "INSERT INTO telegram_chat_context " +
                            "(chat_id,user_id,venue_id,table_id,table_token,table_session_id) " +
                            "VALUES (601,501,101,201,'availability-token',301)",
                    )
                }
            }
            val address = """"tableSessionId":301"""
            val table = """$address,"tableToken":"availability-token","tabId":402"""
            val commands =
                mapOf(
                    "tabs/personal" to address,
                    "tabs/shared" to address,
                    "tabs/402/invite" to address,
                    "tabs/join" to """$address,"token":"1234","consent":true,"inviteId":"1","expectedTabId":402""",
                    "staff-call" to """$table,"reason":"COALS" """,
                    "order/bill-request" to """$table,"expectedOrderId":1,"paymentMethod":"CARD" """,
                    "table/extension-requests" to """$table,"expectedOrderId":1,"idempotencyKey":"extension" """,
                )
            val before = snapshot(connection)
            for ((path, fields) in commands) {
                for ((suffix, status) in listOf("" to 428, ""","expectedVisitGeneration":"0" """ to 409)) {
                    val response =
                        client.post("/api/guest/$path") {
                            headers { append(HttpHeaders.Authorization, "Bearer $token") }
                            contentType(ContentType.Application.Json)
                            setBody("{$fields$suffix}")
                        }
                    assertEquals(status, response.status.value, path + response.bodyAsText())
                    assertEquals(before, snapshot(connection), path)
                }
            }
        }

    @ParameterizedTest
    @ValueSource(strings = ["member", "bot", "platform"])
    fun `delayed end cannot finish a later admission to the same session`(actor: String) =
        withFixture("ACTIVE", platformOwner = actor == "platform") { connection, token, _ ->
            seedSession(connection, "ACTIVE")
            if (actor != "member") {
                connection.createStatement().use {
                    it.executeUpdate("DELETE FROM tab_member WHERE user_id = 501")
                    it.executeUpdate(
                        "INSERT INTO telegram_chat_context " +
                            "(chat_id,user_id,venue_id,table_id,table_token,table_session_id) " +
                            "VALUES (601,501,101,201,'availability-token',301)",
                    )
                }
            }
            val sessions = TableSessionRepository(null)
            val event = checkNotNull(sessions.findExitEventId(connection, 501, 301))
            coroutineScope {
                connection.autoCommit = false
                val started = CompletableDeferred<Unit>()
                val (oldEnd, rejoined) =
                    try {
                        checkNotNull(sessions.findSessionForTable(connection, 301, 101, 201, forUpdate = true))
                        assertTrue(sessions.consumeExitEvent(connection, 501, 301, event))
                        val rejoined = snapshot(connection)
                        val pending =
                            async(Dispatchers.IO) {
                                started.complete(Unit)
                                endVisit(token, "0").status
                            }
                        started.await()
                        connection.commit()
                        pending to rejoined
                    } finally {
                        connection.rollback()
                        connection.autoCommit = true
                    }
                assertEquals(HttpStatusCode.Conflict, oldEnd.await())
                assertEquals(rejoined, snapshot(connection))
            }
            val after = snapshot(connection)
            assertEquals(HttpStatusCode.Conflict, endVisit(token, "0").status)
            assertEquals(after, snapshot(connection))
            assertEquals(
                event.toString(),
                restore(token, "?tableToken=availability-token&tableSessionId=301").context?.visitGeneration,
            )
            assertTrue(
                Json.decodeFromString<TableSessionEndResponse>(endVisit(token, event.toString()).bodyAsText()).ended,
            )
        }

    @Test
    fun `delayed preview and batch cannot cross rejoin and exact replay returns the original batch`() =
        withFixture("ACTIVE") { connection, token ->
            seedSession(connection, "ACTIVE")
            connection.createStatement().use {
                it.executeUpdate("DELETE FROM guest_table_session_exits WHERE user_id=501")
                it.executeUpdate(
                    "INSERT INTO menu_categories (id,venue_id,name,sort_order) " +
                        "OVERRIDING SYSTEM VALUE VALUES (701,101,'Menu',0)",
                )
                it.executeUpdate(
                    "INSERT INTO menu_items (id,venue_id,category_id,name,price_minor,currency,is_available) " +
                        "OVERRIDING SYSTEM VALUE VALUES (702,101,701,'Tea',100,'RUB',TRUE)",
                )
            }
            val cart = """{"tableToken":"availability-token","tableSessionId":301,"tabId":402,
                "items":[{"itemId":702,"qty":1}]"""

            suspend fun postOrder(
                path: String,
                suffix: String,
            ) = client.post("/api/guest/order/$path") {
                headers { append(HttpHeaders.Authorization, "Bearer $token") }
                contentType(ContentType.Application.Json)
                setBody(cart + suffix + "}")
            }
            val zero = ""","expectedVisitGeneration":"0" """
            val command = ""","idempotencyKey":"delayed-participation" """
            val before = snapshot(connection)
            assertEquals(428, postOrder("preview", "").status.value)
            assertEquals(428, postOrder("add-batch", command).status.value)
            assertEquals(428, endVisit(token).status.value)
            assertEquals(before, snapshot(connection))

            fun price(body: String) =
                Json.parseToJsonElement(
                    body,
                ).jsonObject.getValue("preview").jsonObject.getValue("pricingFingerprint")
            val firstPreview = postOrder("preview", zero)
            assertEquals(HttpStatusCode.OK, firstPreview.status)
            val firstPrice = price(firstPreview.bodyAsText())
            assertEquals(HttpStatusCode.OK, endVisit(token, "0").status)
            val generation = checkNotNull(scan(token).exitEventId)
            assertEquals(HttpStatusCode.OK, rejoin(token, generation).status)
            val active = snapshot(connection)
            assertEquals(HttpStatusCode.Conflict, postOrder("preview", zero).status)
            assertEquals(
                HttpStatusCode.Conflict,
                postOrder("add-batch", zero + command + ""","previewFingerprint":$firstPrice""").status,
            )
            assertEquals(active, snapshot(connection))
            val current = ""","expectedVisitGeneration":"$generation" """
            assertEquals(
                HttpStatusCode.Conflict,
                postOrder("add-batch", current + command + ""","previewFingerprint":$firstPrice""").status,
            )
            assertEquals(active, snapshot(connection))
            val preview = postOrder("preview", current)
            assertEquals(HttpStatusCode.OK, preview.status)
            assertNotEquals(firstPrice, price(preview.bodyAsText()))
            val success = postOrder("add-batch", current + command)
            assertEquals(HttpStatusCode.OK, success.status)
            val committed = snapshot(connection)
            val replay = postOrder("add-batch", current + command)
            assertEquals(HttpStatusCode.OK, replay.status)
            assertEquals(
                Json.parseToJsonElement(success.bodyAsText()).jsonObject["batchId"],
                Json.parseToJsonElement(replay.bodyAsText()).jsonObject["batchId"],
            )
            assertEquals(committed, snapshot(connection))

            // A delivered request from the pre-generation client already has an immutable batch.
            connection.createStatement().use {
                it.executeUpdate(
                    "UPDATE guest_batch_idempotency SET request_fingerprint = NULL",
                )
            }
            val legacy = postOrder("add-batch", command)
            assertEquals(HttpStatusCode.OK, legacy.status)
            assertEquals(
                Json.parseToJsonElement(success.bodyAsText()).jsonObject["batchId"],
                Json.parseToJsonElement(legacy.bodyAsText()).jsonObject["batchId"],
            )
            val upgraded = snapshot(connection)
            assertEquals(committed - "guest_batch_idempotency", upgraded - "guest_batch_idempotency")
            assertEquals(
                HttpStatusCode.Conflict,
                postOrder("add-batch", command + ""","comment":"different" """).status,
            )
            assertEquals(upgraded, snapshot(connection))
        }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `review09 order key keeps its outcome when menu availability changes`(commitFirst: Boolean) =
        withFixture("ACTIVE") { connection, token ->
            seedSession(connection, "ACTIVE")
            connection.createStatement().use {
                it.executeUpdate("DELETE FROM guest_table_session_exits WHERE user_id = 501")
                it.executeUpdate(
                    "INSERT INTO menu_categories(id,venue_id,name,sort_order) " +
                        "OVERRIDING SYSTEM VALUE VALUES (701,101,'Чай',0)",
                )
                it.executeUpdate(
                    "INSERT INTO menu_items(id,venue_id,category_id,name,price_minor,currency,is_available) " +
                        "OVERRIDING SYSTEM VALUE VALUES (702,101,701,'Чай',100,'RUB',true)",
                )
            }

            fun available(on: Boolean) {
                connection.createStatement().use {
                    it.executeUpdate("UPDATE menu_items SET is_available = $on WHERE id = 702")
                }
            }

            suspend fun submit(
                key: String,
                qty: Int = 1,
            ) = client.post("/api/guest/order/add-batch") {
                headers { append(HttpHeaders.Authorization, "Bearer $token") }
                contentType(ContentType.Application.Json)
                setBody(
                    """{"tableToken":"availability-token","tableSessionId":301,"expectedVisitGeneration":"0",
                    "idempotencyKey":"$key","tabId":402,"items":[{"cartLineRef":"tea","itemId":702,"qty":$qty}]}""",
                )
            }
            val original = "review09-original"
            if (commitFirst) {
                val committed = submit(original)
                assertEquals(HttpStatusCode.OK, committed.status, committed.bodyAsText())
                available(false)
                val before = snapshot(connection)
                val replay = submit(original)
                assertEquals(HttpStatusCode.OK, replay.status, replay.bodyAsText())
                assertEquals(
                    Json.parseToJsonElement(committed.bodyAsText()).jsonObject["batchId"],
                    Json.parseToJsonElement(replay.bodyAsText()).jsonObject["batchId"],
                )
                assertEquals(before, snapshot(connection))
            } else {
                available(false)
                val before = snapshot(connection)
                val results =
                    coroutineScope {
                        val first = async { submit(original) }
                        val peer = async { submit(original) }
                        listOf(first.await(), peer.await())
                    }
                results.forEach {
                    assertEquals(HttpStatusCode.Conflict, it.status, it.bodyAsText())
                    assertTrue(it.bodyAsText().contains("CART_MENU_SELECTION_UNAVAILABLE"))
                }
                val rejected = snapshot(connection)
                assertEquals(before - "guest_batch_idempotency", rejected - "guest_batch_idempotency")
                available(true)
                val delayed = submit(original)
                assertEquals(HttpStatusCode.Conflict, delayed.status, "The closed original key executed")
                assertEquals(1, rejected.getValue("guest_batch_idempotency").size)
                assertEquals(rejected, snapshot(connection))
                val changed = submit(original, qty = 2)
                assertEquals(HttpStatusCode.Conflict, changed.status)
                assertTrue(changed.bodyAsText().contains("ORDER_IDEMPOTENCY_PAYLOAD_MISMATCH"))
                val fresh = submit("review09-new")
                assertEquals(HttpStatusCode.OK, fresh.status, fresh.bodyAsText())
                assertEquals(1, snapshot(connection).getValue("order_batches").size)
            }
        }

    private suspend fun ApplicationTestBuilder.extensionTerms(
        token: String,
        tabId: Long = 402,
        sessionId: Long = 301,
    ) = client.get(
        "/api/guest/table/extension-options?tableToken=availability-token&tableSessionId=$sessionId&tabId=$tabId",
    ) {
        headers { append(HttpHeaders.Authorization, "Bearer $token") }
    }.let {
        assertEquals(HttpStatusCode.OK, it.status, it.bodyAsText())
        Json.parseToJsonElement(it.bodyAsText()).jsonObject.getValue("confirmedTerms")
    }

    @ParameterizedTest
    @ValueSource(ints = [1, 2])
    fun `review11 concurrent approvals provide every paid minute within the physical session limit`(limit: Int) =
        withFixture("ACTIVE", platformOwner = false) { connection, token, config ->
            seedSession(connection, "ACTIVE")
            val otherTab = if (limit == 1) 403 else 402
            connection.createStatement().use { sql ->
                sql.executeUpdate("DELETE FROM guest_table_session_exits WHERE table_session_id=301")
                sql.executeUpdate("INSERT INTO users(telegram_user_id,first_name) VALUES (503,'Staff')")
                sql.executeUpdate("INSERT INTO venue_members(venue_id,user_id,role) VALUES (101,503,'STAFF')")
                sql.executeUpdate(
                    "INSERT INTO tab(id,venue_id,table_session_id,type,owner_user_id,status) " +
                        "OVERRIDING SYSTEM VALUE VALUES (403,101,301,'PERSONAL',501,'ACTIVE')",
                )
                sql.executeUpdate("INSERT INTO tab_member(tab_id,user_id,role) VALUES(403,501,'OWNER')")
                sql.executeUpdate(
                    "INSERT INTO shift_extension_settings(venue_id,enabled,duration_minutes,price_minor,currency) " +
                        "VALUES(101,TRUE,60,5000,'RUB')",
                )
                sql.executeUpdate(
                    "INSERT INTO orders(id,venue_id,table_id,table_session_id,status,display_number,display_date) " +
                        "OVERRIDING SYSTEM VALUE VALUES (601,101,201,301,'ACTIVE',1,CURRENT_DATE)",
                )
                sql.executeUpdate(
                    "INSERT INTO order_batches(order_id,tab_id,author_user_id,source,status) " +
                        "VALUES(601,401,502,'MINIAPP','NEW'),(601,$otherTab,501,'MINIAPP','NEW')",
                )
            }
            val tokens = SessionTokenService(SessionTokenConfig.from(config, "test"))
            val terms = extensionTerms(token, otherTab.toLong())

            suspend fun submit(
                actorToken: String,
                tab: Int,
            ): Long {
                val response =
                    client.post("/api/guest/table/extension-requests") {
                        headers { append(HttpHeaders.Authorization, "Bearer $actorToken") }
                        contentType(ContentType.Application.Json)
                        setBody(
                            """{"tableToken":"availability-token","tableSessionId":301,"tabId":$tab,
                         "expectedVisitGeneration":"0","expectedOrderId":601,"idempotencyKey":"bill-$tab",
                         "confirmedTerms":$terms}""",
                        )
                    }
                assertEquals(200, response.status.value, response.bodyAsText())
                return Json.parseToJsonElement(response.bodyAsText()).jsonObject.getValue("request").jsonObject
                    .getValue("id").toString().toLong()
            }

            val requests = listOf(submit(token, otherTab), submit(tokens.issueToken(502).token, 401))
            connection.createStatement().use {
                it.executeUpdate(
                    "UPDATE shift_extension_settings SET max_extensions_per_session=$limit, " +
                        "duration_minutes=120, price_minor=10000 WHERE venue_id=101",
                )
            }

            fun deadline(): Instant =
                connection.createStatement().use {
                    it.executeQuery("SELECT expires_at FROM table_sessions WHERE id=301").use { rows ->
                        check(rows.next())
                        rows.getTimestamp(1).toInstant()
                    }
                }
            val staff = tokens.issueToken(503).token

            suspend fun approve(id: Long) =
                client.post("/api/venue/101/shift-extension-requests/$id/approve") {
                    headers { append(HttpHeaders.Authorization, "Bearer $staff") }
                    contentType(ContentType.Application.Json)
                    setBody("{}")
                }

            val before = deadline()
            val decisions = coroutineScope { requests.map { async { approve(it) } }.map { it.await() } }
            assertEquals(
                limit,
                decisions.count { it.status == HttpStatusCode.OK },
                decisions.map { it.status }.toString(),
            )
            assertTrue(decisions.all { it.status == HttpStatusCode.OK || it.status == HttpStatusCode.BadRequest })
            assertEquals(before.plusSeconds(3600L * limit), deadline())
            connection.createStatement().use {
                it.executeQuery("SELECT COUNT(*), SUM(total_minor) FROM order_service_charges").use { rows ->
                    assertTrue(rows.next())
                    assertEquals(limit, rows.getInt(1))
                    assertEquals(limit * 5000L, rows.getLong(2))
                }
                it.executeQuery("SELECT COUNT(*) FROM shift_extension_requests WHERE status='APPROVED'").use { rows ->
                    assertTrue(rows.next())
                    assertEquals(limit, rows.getInt(1))
                }
            }
            val decided = snapshot(connection)
            for ((id, response) in requests.zip(decisions)) assertEquals(response.status, approve(id).status)
            assertEquals(decided, snapshot(connection))
        }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `review09 both extension keys replay the same decided request`(approve: Boolean) =
        withFixture("ACTIVE", platformOwner = false) { connection, token, config ->
            seedSession(connection, "ACTIVE")
            connection.createStatement().use {
                it.executeUpdate("DELETE FROM guest_table_session_exits WHERE user_id = 501")
                it.executeUpdate("INSERT INTO venue_members(venue_id,user_id,role) VALUES (101,502,'STAFF')")
                it.executeUpdate(
                    "INSERT INTO shift_extension_settings(venue_id,enabled,duration_minutes,price_minor,currency) " +
                        "VALUES (101,TRUE,60,5000,'RUB')",
                )
                it.executeUpdate(
                    "INSERT INTO orders (id,venue_id,table_id,table_session_id,status,display_number,display_date) " +
                        "OVERRIDING SYSTEM VALUE VALUES (601,101,201,301,'ACTIVE',1,CURRENT_DATE)",
                )
                it.executeUpdate(
                    "INSERT INTO order_batches(order_id,tab_id,author_user_id,source,status) " +
                        "VALUES (601,402,501,'MINIAPP','NEW')",
                )
            }

            val terms = extensionTerms(token)

            suspend fun submit(
                key: String,
                comment: String = "Время",
            ) = client.post("/api/guest/table/extension-requests") {
                headers { append(HttpHeaders.Authorization, "Bearer $token") }
                contentType(ContentType.Application.Json)
                setBody(
                    """{"tableToken":"availability-token","tableSessionId":301,"tabId":402,
                         "expectedVisitGeneration":"0","expectedOrderId":601,
                         "idempotencyKey":"$key","comment":"$comment","confirmedTerms":$terms}""",
                )
            }

            suspend fun requestId(response: io.ktor.client.statement.HttpResponse): String {
                assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
                return Json.parseToJsonElement(response.bodyAsText()).jsonObject.getValue("request")
                    .jsonObject.getValue("id").toString()
            }

            val keys = listOf("review09-a", "review09-b")
            val ids = coroutineScope { keys.map { key -> async { requestId(submit(key)) } }.map { it.await() } }
            val id = ids.distinct().single()
            val jwt = SessionTokenService(SessionTokenConfig.from(config, "test")).issueToken(502).token
            val action = if (approve) "approve" else "reject"
            val staff =
                client.post("/api/venue/101/shift-extension-requests/$id/$action") {
                    headers { append(HttpHeaders.Authorization, "Bearer $jwt") }
                    contentType(ContentType.Application.Json)
                    setBody("{}")
                }
            assertEquals(HttpStatusCode.OK, staff.status, staff.bodyAsText())
            val decided = snapshot(connection)
            assertEquals(2, decided.getValue("shift_extension_request_keys").size)
            for (key in keys) assertEquals(id, requestId(submit(key)), "A retry created a new request")
            assertEquals(decided, snapshot(connection))
            assertEquals(HttpStatusCode.Conflict, submit(keys.last(), "Другой текст").status)
            assertNotEquals(id, requestId(submit("review09-intentional")))
            assertEquals(2, snapshot(connection).getValue("shift_extension_requests").size)
            connection.createStatement().use {
                it.executeQuery("SELECT COUNT(*) FROM order_service_charges").use { rows ->
                    assertTrue(rows.next())
                    assertEquals(if (approve) 1 else 0, rows.getInt(1))
                }
            }
        }

    @ParameterizedTest
    @ValueSource(strings = ["missing-key", "blank-key", "stale-terms"])
    fun `review10 extension refuses incomplete or stale consent without changing the visit`(scenario: String) =
        withFixture("ACTIVE", platformOwner = false) { connection, token, config ->
            seedSession(connection, "ACTIVE")
            connection.createStatement().use {
                it.executeUpdate("DELETE FROM guest_table_session_exits WHERE user_id = 501")
                it.executeUpdate("INSERT INTO venue_members(venue_id,user_id,role) VALUES (101,502,'OWNER')")
                it.executeUpdate(
                    "INSERT INTO shift_extension_settings(venue_id,enabled,duration_minutes,price_minor,currency) " +
                        "VALUES (101,TRUE,60,5000,'RUB')",
                )
                it.executeUpdate(
                    "INSERT INTO orders (id,venue_id,table_id,table_session_id,status,display_number,display_date) " +
                        "OVERRIDING SYSTEM VALUE VALUES (601,101,201,301,'ACTIVE',1,CURRENT_DATE)",
                )
                it.executeUpdate(
                    "INSERT INTO order_batches(order_id,tab_id,author_user_id,source,status) " +
                        "VALUES (601,402,501,'MINIAPP','NEW')",
                )
            }

            suspend fun submit(
                key: String?,
                terms: kotlinx.serialization.json.JsonElement?,
                orderId: Long = 601,
                sessionId: Long = 301,
                tabId: Long = 402,
            ) = client.post("/api/guest/table/extension-requests") {
                headers { append(HttpHeaders.Authorization, "Bearer $token") }
                contentType(ContentType.Application.Json)
                val keyField = key?.let { ""","idempotencyKey":"$it"""" }.orEmpty()
                setBody(
                    """{"tableToken":"availability-token","tableSessionId":$sessionId,"tabId":$tabId,
                       "expectedVisitGeneration":"0","expectedOrderId":$orderId,
                       "confirmedTerms":$terms$keyField}""",
                )
            }
            val operatorToken = SessionTokenService(SessionTokenConfig.from(config, "test")).issueToken(502).token

            suspend fun updateTariff(
                duration: Int,
                minor: Long,
            ) {
                val changed =
                    client.put("/api/venue/101/shift-extension-settings") {
                        headers { append(HttpHeaders.Authorization, "Bearer $operatorToken") }
                        contentType(ContentType.Application.Json)
                        setBody("""{"enabled":true,"durationMinutes":$duration,"priceMinor":$minor,"currency":"RUB"}""")
                    }
                assertEquals(HttpStatusCode.OK, changed.status, changed.bodyAsText())
            }

            val originalTerms = extensionTerms(token)
            if (scenario == "stale-terms") updateTariff(120, 10000)
            val before = snapshot(connection)
            val key =
                when (scenario) {
                    "missing-key" -> null
                    "blank-key" -> " "
                    else -> "review10-original"
                }
            val refused = submit(key, originalTerms)
            val expectedStatus = if (scenario == "stale-terms") 409 else 428
            assertEquals(expectedStatus, refused.status.value, refused.bodyAsText())
            assertEquals(before, snapshot(connection))
            if (scenario != "stale-terms") return@withFixture

            val newTerms = extensionTerms(token)
            assertEquals(428, submit("missing-terms", null).status.value)
            assertEquals(before, snapshot(connection))
            val accepted = submit("review10-confirmed", newTerms)
            assertEquals(HttpStatusCode.OK, accepted.status, accepted.bodyAsText())
            val record = Json.parseToJsonElement(accepted.bodyAsText()).jsonObject.getValue("request").jsonObject
            assertEquals("120", record.getValue("durationMinutes").toString())
            assertEquals("10000", record.getValue("priceMinor").toString())
            val acceptedState = snapshot(connection)
            val changedRevision =
                kotlinx.serialization.json.JsonObject(
                    newTerms.jsonObject + ("revision" to kotlinx.serialization.json.JsonPrimitive("1")),
                )
            assertEquals(HttpStatusCode.Conflict, submit("review10-confirmed", changedRevision).status)
            assertEquals(acceptedState, snapshot(connection))
            val requestId = record.getValue("id").toString()
            updateTariff(180, 15000)
            val replay = submit("review10-confirmed", newTerms)
            assertEquals(HttpStatusCode.OK, replay.status, replay.bodyAsText())
            assertEquals(record, Json.parseToJsonElement(replay.bodyAsText()).jsonObject.getValue("request"))
            assertEquals(HttpStatusCode.Conflict, submit("review10-confirmed", extensionTerms(token)).status)
            val approved =
                client.post("/api/venue/101/shift-extension-requests/$requestId/approve") {
                    headers { append(HttpHeaders.Authorization, "Bearer $operatorToken") }
                    contentType(ContentType.Application.Json)
                    setBody("{}")
                }
            assertEquals(HttpStatusCode.OK, approved.status, approved.bodyAsText())
            connection.createStatement().use { sql ->
                sql.executeQuery("SELECT total_minor, currency FROM order_service_charges").use { rows ->
                    assertTrue(rows.next())
                    assertEquals(10000L, rows.getLong(1))
                    assertEquals("RUB", rows.getString(2))
                    assertFalse(rows.next())
                }
            }
            updateTariff(60, 5000)
            val restoredTariff = snapshot(connection)
            assertEquals(HttpStatusCode.Conflict, submit("review10-original", originalTerms).status)
            assertEquals(restoredTariff, snapshot(connection))
            val ds =
                HikariDataSource(
                    HikariConfig().apply {
                        jdbcUrl = config.property("db.jdbcUrl").getString()
                        username = config.property("db.user").getString()
                        password = config.property("db.password").getString()
                    },
                )
            ds.use { VenueSettingsRepository(it).resetDevOperationalData(101) }
            val afterCleanup = snapshot(connection)
            assertTrue(afterCleanup.getValue("shift_extension_requests").isEmpty())
            assertTrue(afterCleanup.getValue("shift_extension_request_keys").isEmpty())
            val newSession = scan(token).tableSessionId
            assertNotEquals(301L, newSession)
            val newTab =
                connection.createStatement().use {
                    it.executeQuery("SELECT id FROM tab WHERE table_session_id=$newSession AND owner_user_id=501").use {
                            rows ->
                        check(rows.next())
                        rows.getLong(1)
                    }
                }
            connection.createStatement().use {
                it.executeUpdate(
                    "INSERT INTO orders (id,venue_id,table_id,table_session_id,status,display_number,display_date) " +
                        "OVERRIDING SYSTEM VALUE VALUES (602,101,201,$newSession,'ACTIVE',2,CURRENT_DATE)",
                )
                it.executeUpdate(
                    "INSERT INTO order_batches(order_id,tab_id,author_user_id,source,status) " +
                        "VALUES (602,$newTab,501,'MINIAPP','NEW')",
                )
            }
            val replacement = snapshot(connection)
            for (oldOrSubstitutedOrder in listOf(601L, 602L)) {
                val old = submit("review10-confirmed", newTerms, orderId = oldOrSubstitutedOrder)
                assertTrue(old.status.value in setOf(403, 404, 409), old.bodyAsText())
                assertEquals(replacement, snapshot(connection))
            }
            val freshTerms = extensionTerms(token, newTab, newSession)
            val fresh = submit("review10-after-cleanup", freshTerms, 602, newSession, newTab)
            assertEquals(HttpStatusCode.OK, fresh.status, fresh.bodyAsText())
            assertEquals(1, snapshot(connection).getValue("shift_extension_requests").size)
        }

    @Test
    fun `review11 dev reset retires old checkouts under the venue lock and a new scan can order`() =
        withFixture("ACTIVE", platformOwner = false) { connection, token, config ->
            seedCheckoutMenu(connection)
            val scope = BotCheckoutScope(501, 501, 301, 0)
            val other = scope.copy(sessionId = 302)
            val payload =
                BotPreparedCheckout(
                    UUID.randomUUID().toString(),
                    402,
                    null,
                    listOf(OrderBatchItemInput(itemId = 702, qty = 1)),
                    null,
                    null,
                )

            suspend fun order(
                key: String,
                session: Long = 301,
                bill: Long = 402,
            ) = client.post("/api/guest/order/add-batch") {
                headers { append(HttpHeaders.Authorization, "Bearer $token") }
                contentType(ContentType.Application.Json)
                setBody(
                    """{"tableToken":"availability-token","tableSessionId":$session,"expectedVisitGeneration":"0",
                        "tabId":$bill,"idempotencyKey":"$key","items":[{"itemId":702,"qty":1}]}""",
                )
            }

            fixtureDataSource(config).use { ds ->
                connection.createStatement().use {
                    it.executeUpdate(
                        "INSERT INTO table_sessions(id,venue_id,table_id," +
                            "started_at,last_activity_at,expires_at,status) " +
                            "OVERRIDING SYSTEM VALUE SELECT 302,102,202," +
                            "started_at,last_activity_at,expires_at,'ACTIVE' " +
                            "FROM table_sessions WHERE id=301",
                    )
                    it.executeUpdate(
                        "INSERT INTO tab(id,venue_id,table_session_id,type,owner_user_id,status) " +
                            "OVERRIDING SYSTEM VALUE VALUES (403,102,302,'PERSONAL',501,'ACTIVE')",
                    )
                }
                val prepared = BotPreparedCheckoutRepository(ds)
                prepared.prepare(connection, scope, payload)
                prepared.prepare(connection, other, payload.copy(tabId = 403))
                val old = order("lost-ack")
                assertEquals(HttpStatusCode.OK, old.status, old.bodyAsText())
                val racing =
                    coroutineScope {
                        val starting = CompletableDeferred<Unit>()
                        val checkout =
                            async(Dispatchers.IO) {
                                starting.await()
                                order("racing")
                            }
                        val reset =
                            async(Dispatchers.IO) {
                                starting.await()
                                VenueSettingsRepository(ds).resetDevOperationalData(101)
                            }
                        starting.complete(Unit)
                        reset.await()
                        checkout.await()
                    }
                assertTrue(racing.status.value in setOf(200, 403, 404, 409), racing.bodyAsText())
                assertNull(prepared.find(scope))
                assertEquals(payload.copy(tabId = 403), prepared.find(other))
                val resetState = snapshot(connection)
                assertTrue(resetState.getValue("order_batches").isEmpty())
                assertTrue(resetState.getValue("guest_batch_idempotency").isEmpty())
                val stale = order("lost-ack")
                assertTrue(stale.status.value in setOf(403, 404, 409), stale.bodyAsText())
                assertEquals(resetState, snapshot(connection))
                val visit = scan(token)
                assertTrue(visit.tableSessionActive)
                assertNotEquals(scope.sessionId, visit.tableSessionId)
                val bill =
                    connection.createStatement().use {
                        it.executeQuery(
                            "SELECT id FROM tab WHERE table_session_id=${visit.tableSessionId} AND owner_user_id=501",
                        ).use {
                                rows ->
                            check(rows.next())
                            rows.getLong(1)
                        }
                    }
                val fresh = order("new-visit", visit.tableSessionId, bill)
                assertEquals(HttpStatusCode.OK, fresh.status, fresh.bodyAsText())
                val reopened = snapshot(connection)
                assertTrue(order("lost-ack").status.value in setOf(403, 404, 409))
                assertEquals(reopened, snapshot(connection))
                assertEquals(1, reopened.getValue("order_batches").size)
            }
        }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `review11 rebind cancels the original checkout handoff or claimed staff message`(afterPublish: Boolean) =
        withFixture("ACTIVE", platformOwner = false, enableCheckoutOutbox = true) { connection, token, config ->
            seedCheckoutMenu(connection)
            val response =
                client.post("/api/guest/order/add-batch") {
                    headers { append(HttpHeaders.Authorization, "Bearer $token") }
                    contentType(ContentType.Application.Json)
                    setBody(
                        """{"tableToken":"availability-token","tableSessionId":301,"expectedVisitGeneration":"0",
                    "tabId":402,"idempotencyKey":"addressed","items":[{"itemId":702,"qty":1}]}""",
                    )
                }
            assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
            fixtureDataSource(config).use { ds ->
                val venue = VenueRepository(ds)
                val policy = TelegramTrafficPolicy.unrestricted()
                val outbox = TelegramOutboxRepository(ds, policy)
                val rendering = java.util.concurrent.CountDownLatch(1)
                val resume = java.util.concurrent.CountDownLatch(1)
                val notifier =
                    StaffChatNotifier(
                        venue,
                        StaffChatNotificationRepository(ds, policy),
                        isTelegramActive = { true },
                        trafficPolicy = policy,
                        scope = CoroutineScope(Dispatchers.IO),
                        venueMiniAppUrl = {
                            if (!afterPublish) {
                                rendering.countDown()
                                check(resume.await(10, TimeUnit.SECONDS))
                            }
                            null
                        },
                    )
                val notices =
                    GuestCheckoutNotifications(
                        outbox,
                        TelegramOutboxEnqueuer(outbox, Json, policy),
                        VenueOrdersRepository(ds),
                        notifier,
                        null,
                        Json,
                        true,
                    )
                val telegram = mockk<TelegramApiClient>()
                coEvery { telegram.dispatchOutbox(any(), any(), any()) } returns TelegramCallResult.Success(JsonNull)
                val worker =
                    TelegramOutboxWorker(
                        outbox,
                        { telegram },
                        Json,
                        TelegramRateLimiter {
                            if (afterPublish) {
                                rendering.countDown()
                                check(resume.await(10, TimeUnit.SECONDS))
                            }
                        },
                        TelegramOutboxConfig(),
                        CoroutineScope(Dispatchers.IO),
                        checkoutNotifications = notices,
                    )
                if (afterPublish) worker.processOnce()
                coroutineScope {
                    val publishing = async(Dispatchers.IO) { worker.processOnce() }
                    try {
                        assertTrue(rendering.await(10, TimeUnit.SECONDS))
                        for (chat in listOf(-602L, -601L)) {
                            assertTrue(
                                venue.bindStaffChat(
                                    101,
                                    chat,
                                    502,
                                ) is com.hookah.platform.backend.telegram.db.BindResult.Success,
                            )
                        }
                    } finally {
                        resume.countDown()
                    }
                    publishing.await()
                }
                worker.processOnce()
                io.mockk.coVerify(exactly = 0) { telegram.dispatchOutbox(any(), any(), any()) }
                connection.createStatement().use { sql ->
                    sql.executeQuery("SELECT chat_id,method,status FROM telegram_outbox ORDER BY id").use { rows ->
                        assertTrue(rows.next())
                        assertEquals(-601L, rows.getLong(1))
                        assertEquals("guestCheckoutStaff", rows.getString(2))
                        assertEquals(if (afterPublish) "SENT" else "FAILED", rows.getString(3))
                        if (afterPublish) {
                            assertTrue(rows.next())
                            assertEquals(-601L, rows.getLong(1))
                            assertEquals("FAILED", rows.getString(3))
                        }
                        assertFalse(rows.next())
                    }
                }
            }
        }

    @ParameterizedTest
    @CsvSource("false,false", "false,true", "true,false", "true,true")
    fun `review11 quick and direct Bot writers stage one staff obligation for both guest roles`(
        controlled: Boolean,
        quick: Boolean,
    ) = withFixture("ACTIVE", platformOwner = controlled) { connection, _, config ->
        seedCheckoutMenu(connection)
        connection.createStatement().use {
            it.executeUpdate(
                "INSERT INTO telegram_chat_context(chat_id,user_id,venue_id,table_id,table_token,table_session_id) " +
                    "VALUES(501,501,101,201,'availability-token',301)",
            )
            it.executeUpdate(
                "INSERT INTO orders(id,venue_id,table_id,table_session_id,status,display_number,display_date) " +
                    "OVERRIDING SYSTEM VALUE VALUES(601,101,201,301,'ACTIVE',1,CURRENT_DATE)",
            )
        }
        fixtureDataSource(config).use { ds ->
            val policy = TelegramTrafficPolicy.unrestricted()
            val outbox = TelegramOutboxRepository(ds, policy)
            val notices =
                GuestCheckoutNotifications(
                    outbox,
                    TelegramOutboxEnqueuer(outbox, Json, policy),
                    VenueOrdersRepository(ds),
                    mockk(),
                    null,
                    Json,
                    true,
                )
            val stage: (Connection, Long, Long) -> Unit = { tx, order, batch ->
                notices.stageStaff(tx, 101, order, batch, liveCard = false, guestUserId = 501)
            }
            val key = if (quick) "bot-quick:fixture" else "bot-reorder:fixture"
            val lines = if (quick) emptyList() else listOf(OrderBatchItemInput(itemId = 702, qty = 1))
            val target = if (quick) null else 601L
            val note = if (quick) "Текст заказа" else null

            suspend fun submit(): com.hookah.platform.backend.telegram.db.CreatedOrderBatch? {
                val writer = OrdersRepository(ds)
                if (!controlled) {
                    return writer.createGuestOrderBatch(
                        tableId = 201, venueId = 101, tableSessionId = 301, userId = 501,
                        idempotencyKey = key, tabId = 402, comment = note, items = lines, chatOrder = quick,
                        expectedTableToken = "availability-token", expectedVisitGeneration = 0,
                        expectedOrderId = target,
                        stageNewBatch = stage,
                    )
                }
                val lifecycle =
                    GuestTableContextLifecycleRepository(
                        ds,
                        TableTokenRepository(ds),
                        SubscriptionRepository(ds),
                        TableSessionRepository(ds),
                        GuestTabsRepository(ds),
                        ChatContextRepository(ds),
                        DialogStateRepository(ds, Json),
                    )
                return when (
                    val result =
                        lifecycle.withConfirmedPlatformGuestMutation(
                            actorUserId = 501,
                            platformOwnerUserId = 501,
                            chatId = 501,
                            expectedSessionId = 301,
                            ttl = java.time.Duration.ofHours(4),
                            touchSessionBeforeMutation = false,
                        ) { tx, visit ->
                            writer.createGuestOrderBatch(
                                connection = tx, tableId = visit.context.tableId, venueId = visit.context.venueId,
                                tableSessionId = visit.tableSession.id, userId = 501, idempotencyKey = key, tabId = 402,
                                comment = note, items = lines, chatOrder = quick,
                                expectedTableToken = "availability-token",
                                expectedVisitGeneration = 0,
                                expectedOrderId = target, stageNewBatch = stage,
                            )
                        }
                ) {
                    is PlatformGuestTableMutationResult.Applied -> result.value
                    PlatformGuestTableMutationResult.DatabaseUnavailable -> throw DatabaseUnavailableException()
                    PlatformGuestTableMutationResult.Denied -> error("Expected confirmed participation")
                }
            }

            connection.createStatement().use {
                it.executeUpdate(
                    "ALTER TABLE telegram_outbox ADD CONSTRAINT reject_staff CHECK(method <> 'guestCheckoutStaff')",
                )
            }
            val before = snapshot(connection)
            assertFailsWith<DatabaseUnavailableException> { submit() }
            assertEquals(before, snapshot(connection))
            connection.createStatement().use {
                it.executeUpdate(
                    "ALTER TABLE telegram_outbox DROP CONSTRAINT reject_staff",
                )
            }
            val committed = checkNotNull(submit())
            val after = snapshot(connection)
            val replay = checkNotNull(submit())
            assertTrue(replay.idempotencyReplay)
            assertEquals(committed.batchId, replay.batchId)
            assertEquals(1, after.getValue("order_batches").size)
            assertEquals(1, after.getValue("telegram_outbox").size)
            assertEquals(after, snapshot(connection))
        }
    }

    @Test
    fun `review11 a managed seating exposes no technical deadline or paid extension and can be released`() =
        withFixture("ACTIVE", platformOwner = false) { connection, token, config ->
            seedManagedSeatingScenario(connection)
            val terms = extensionTerms(token)

            suspend fun submit(key: String) =
                client.post("/api/guest/table/extension-requests") {
                    headers { append(HttpHeaders.Authorization, "Bearer $token") }
                    contentType(ContentType.Application.Json)
                    setBody(
                        """{"tableToken":"availability-token","tableSessionId":301,"tabId":402,"expectedOrderId":601,
                         "expectedVisitGeneration":"0","idempotencyKey":"$key","confirmedTerms":$terms}""",
                    )
                }
            val legacy = submit("before-seating")
            assertEquals(200, legacy.status.value, legacy.bodyAsText())
            val request =
                Json.parseToJsonElement(
                    legacy.bodyAsText(),
                ).jsonObject.getValue("request").jsonObject.getValue("id")
            fixtureDataSource(config).use { ds ->
                val floor = SeatingRepository(ds)
                val seat =
                    floor.command(
                        101,
                        502,
                        SeatingCommand(
                            UUID.randomUUID().toString(),
                            "SEAT",
                            tableId = 201,
                            partySize = 1,
                            reconcileLegacySession = true,
                        ),
                    )
                val seated = snapshot(connection)
                val options =
                    client.get(
                        "/api/guest/table/extension-options?tableToken=availability-token&tableSessionId=301&tabId=402",
                    ) {
                        headers { append(HttpHeaders.Authorization, "Bearer $token") }
                    }
                assertEquals(HttpStatusCode.OK, options.status)
                val response = Json.decodeFromString<GuestShiftExtensionOptionsResponse>(options.bodyAsText())
                assertFalse(response.available)
                assertEquals("STAFF_MANAGED_SEATING", response.unavailableReason)
                assertNull(response.confirmedTerms)
                assertNull(response.currentOrderableUntil)
                assertNull(response.proposedOrderableUntil)
                assertNull(response.pendingRequest)
                assertFalse(resolve(token, "&tableSessionId=301&verifyOnly=true").bodyAsText().contains("9999"))
                assertEquals(HttpStatusCode.BadRequest, submit("after-seating").status)
                val staff = SessionTokenService(SessionTokenConfig.from(config, "test")).issueToken(502).token
                val decision =
                    client.post("/api/venue/101/shift-extension-requests/$request/approve") {
                        headers { append(HttpHeaders.Authorization, "Bearer $staff") }
                        contentType(ContentType.Application.Json)
                        setBody("{}")
                    }
                assertEquals(HttpStatusCode.BadRequest, decision.status, decision.bodyAsText())
                assertEquals(seated, snapshot(connection))
                assertTrue(seated.getValue("order_service_charges").isEmpty())
                val today = LocalDate.now(ZoneOffset.UTC)
                val kpi =
                    KpiRepository(ds).venue(
                        101,
                        502,
                        KpiRequest(
                            today.minusDays(1).toString(),
                            today.plusDays(2).toString(),
                            section = "seatings",
                            tableId = 201,
                        ),
                    )
                assertEquals("1", kpi.metrics.getValue("seatings.registeredSessions").value)
                assertFalse(seated.getValue("analytics_events").flatten().any { it?.contains("9999-12-31") == true })
                connection.createStatement().use {
                    it.executeQuery("SELECT occurred_at FROM visits WHERE table_session_id=301").use { rows ->
                        assertTrue(rows.next())
                        assertTrue(rows.getTimestamp(1).toInstant().isBefore(Instant.now().plusSeconds(10)))
                    }
                    it.executeUpdate("UPDATE orders SET status='CLOSED' WHERE id=601")
                }
                floor.command(
                    101,
                    502,
                    SeatingCommand(UUID.randomUUID().toString(), "RELEASE", id = seat.id, revision = seat.revision),
                )
                assertTrue(checkNotNull(TableSessionRepository(ds).findSessionForTable(301, 101, 201)).endedAt != null)
            }
        }

    protected fun seedManagedSeatingScenario(connection: Connection) {
        seedCheckoutMenu(connection)
        connection.createStatement().use {
            it.executeUpdate("INSERT INTO venue_members(venue_id,user_id,role) VALUES (101,502,'MANAGER')")
            it.executeUpdate(
                "INSERT INTO shift_extension_settings(venue_id,enabled,duration_minutes,price_minor) " +
                    "VALUES(101,true,60,5000)",
            )
            it.executeUpdate(
                "INSERT INTO orders(id,venue_id,table_id,table_session_id,status,display_number,display_date) " +
                    "OVERRIDING SYSTEM VALUE VALUES(601,101,201,301,'ACTIVE',1,CURRENT_DATE)",
            )
            it.executeUpdate(
                "INSERT INTO order_batches(order_id,tab_id,author_user_id,source,status) " +
                    "VALUES(601,402,501,'MINIAPP','NEW')",
            )
        }
        connection.createStatement().use { statement ->
            statement.executeUpdate("UPDATE venue_tables SET capacity=4 WHERE id=201")
            for (weekday in 1..7) {
                statement.executeUpdate(
                    "INSERT INTO venue_booking_hours(venue_id,weekday,opens_at,closes_at,is_closed) " +
                        "VALUES(101,$weekday,TIME '00:00',TIME '00:00',false)",
                )
            }
        }
    }

    protected fun fixtureDataSource(config: MapApplicationConfig) =
        HikariDataSource(
            HikariConfig().apply {
                jdbcUrl = config.property("db.jdbcUrl").getString()
                username = config.property("db.user").getString()
                password = config.property("db.password").getString()
            },
        )

    private fun seedCheckoutMenu(connection: Connection) {
        seedSession(connection, "ACTIVE")
        connection.createStatement().use {
            it.executeUpdate("UPDATE venues SET staff_chat_id=-601 WHERE id=101")
            it.executeUpdate("DELETE FROM guest_table_session_exits WHERE user_id=501")
            it.executeUpdate(
                "INSERT INTO menu_categories(id,venue_id,name,sort_order) " +
                    "OVERRIDING SYSTEM VALUE VALUES (701,101,'Чай',0)",
            )
            it.executeUpdate(
                "INSERT INTO menu_items(id,venue_id,category_id,name,price_minor,currency,is_available) " +
                    "OVERRIDING SYSTEM VALUE VALUES (702,101,701,'Чай',100,'RUB',true)",
            )
        }
    }

    @Test
    fun `review10 Mini App checkout and staff handoff commit together`() =
        withFixture("ACTIVE", platformOwner = false, enableCheckoutOutbox = true) { connection, token, _ ->
            seedCheckoutMenu(connection)

            suspend fun submit() =
                client.post("/api/guest/order/add-batch") {
                    headers { append(HttpHeaders.Authorization, "Bearer $token") }
                    contentType(ContentType.Application.Json)
                    setBody(
                        """{"tableToken":"availability-token","tableSessionId":301,"expectedVisitGeneration":"0",
                       "idempotencyKey":"review10-atomic","tabId":402,"items":[{"itemId":702,"qty":1}]}""",
                    )
                }
            connection.createStatement().use {
                it.executeUpdate(
                    "ALTER TABLE telegram_outbox ADD CONSTRAINT test_staff_handoff " +
                        "CHECK (method <> 'guestCheckoutStaff')",
                )
            }
            val before = snapshot(connection)
            val failed = submit()
            assertEquals(503, failed.status.value, failed.bodyAsText())
            assertEquals(before, snapshot(connection))
            connection.createStatement().use {
                it.executeUpdate("ALTER TABLE telegram_outbox DROP CONSTRAINT test_staff_handoff")
            }
            val committed = submit()
            assertEquals(HttpStatusCode.OK, committed.status, committed.bodyAsText())
            val after = snapshot(connection)
            assertEquals(1, after.getValue("order_batches").size)
            assertEquals(1, after.getValue("telegram_outbox").size)
            assertEquals(HttpStatusCode.OK, submit().status)
            assertEquals(after, snapshot(connection))
        }

    @Test
    fun `review10 committed Bot checkout survives expiration outbox faults and process restart`() =
        withFixture("ACTIVE", platformOwner = false) { connection, _, config ->
            seedCheckoutMenu(connection)
            val pool =
                HikariConfig().apply {
                    jdbcUrl = config.property("db.jdbcUrl").getString()
                    username = config.property("db.user").getString()
                    password = config.property("db.password").getString()
                }
            HikariDataSource(pool).use { ds ->
                val scope = BotCheckoutScope(501, 501, 301, 0)
                val checkout =
                    BotPreparedCheckout(
                        UUID.randomUUID().toString(),
                        402,
                        "Чай",
                        listOf(OrderBatchItemInput(itemId = 702, qty = 1)),
                        null,
                        null,
                    )
                val prepared = BotPreparedCheckoutRepository(ds)
                prepared.prepare(connection, scope, checkout)
                val committed =
                    ds.connection.use { tx ->
                        tx.autoCommit = false
                        val batch =
                            checkNotNull(
                                OrdersRepository(ds).createGuestOrderBatch(
                                    connection = tx, tableId = 201, venueId = 101, tableSessionId = 301,
                                    userId = 501, idempotencyKey = checkout.idempotencyKey, tabId = checkout.tabId,
                                    comment = checkout.comment, items = checkout.items, expectedVisitGeneration = 0,
                                ),
                            )
                        tx.commit()
                        batch
                    }
                connection.createStatement().use {
                    it.executeUpdate(
                        "UPDATE table_sessions SET status='CLOSED',ended_at=CURRENT_TIMESTAMP WHERE id=301",
                    )
                }
                assertEquals(0, prepared.pruneRetired())
                assertEquals(checkout, prepared.find(scope))
                val retiredSessions = snapshot(connection).getValue("table_sessions")
                val candidates = mutableListOf<BotPreparedCheckoutRepository.CommittedCheckout>()
                prepared.recoverCommitted { _, candidate -> candidates += candidate }
                assertEquals(committed.batchId, candidates.single().batchId)

                val policy = TelegramTrafficPolicy.unrestricted()
                val outbox = TelegramOutboxRepository(ds, policy)
                val enqueuer = TelegramOutboxEnqueuer(outbox, Json, policy)
                val notifier =
                    StaffChatNotifier(
                        VenueRepository(ds),
                        StaffChatNotificationRepository(ds, policy),
                        isTelegramActive = { true },
                        trafficPolicy = policy,
                        scope = CoroutineScope(Dispatchers.IO),
                    )

                fun restart() =
                    GuestCheckoutNotifications(
                        outbox,
                        enqueuer,
                        VenueOrdersRepository(ds),
                        notifier,
                        BotPreparedCheckoutRepository(ds),
                        Json,
                        enabled = true,
                    )

                fun failWrites(predicate: String) {
                    connection.createStatement().use {
                        it.executeUpdate(
                            "ALTER TABLE telegram_outbox ADD CONSTRAINT test_checkout_outbox CHECK ($predicate)",
                        )
                    }
                }

                fun allowWrites() {
                    connection.createStatement().use {
                        it.executeUpdate("ALTER TABLE telegram_outbox DROP CONSTRAINT test_checkout_outbox")
                    }
                }
                for (predicate in listOf("method <> 'guestCheckoutStaff'", "chat_id <> 501")) {
                    failWrites(predicate)
                    assertFailsWith<DatabaseUnavailableException>(predicate) { restart().recoverPrepared() }
                    assertEquals(checkout, prepared.find(scope))
                    assertTrue(snapshot(connection).getValue("telegram_outbox").isEmpty())
                    allowWrites()
                }
                restart().recoverPrepared()
                restart().recoverPrepared()
                assertNull(prepared.find(scope))
                val completedOutbox = snapshot(connection).getValue("telegram_outbox")
                assertEquals(2, completedOutbox.size)
                // A handler already holding the old preparation returns after the original commit.
                prepared.prepare(connection, scope, checkout.copy(id = "later-checkout"))
                restart().completeBot(
                    connection, scope, checkout.id, 101, committed.orderId, committed.batchId,
                    committed.displayNumber, recalculated = true, replay = true,
                )
                assertEquals("later-checkout", prepared.find(scope)?.id)
                assertEquals(completedOutbox, snapshot(connection).getValue("telegram_outbox"))
                prepared.remove(connection, scope, "later-checkout")

                val telegram = mockk<TelegramApiClient>()
                coEvery { telegram.dispatchOutbox(any(), any(), any()) } returns TelegramCallResult.Success(JsonNull)
                var now = Instant.now().plusSeconds(1)

                fun worker() =
                    TelegramOutboxWorker(
                        outbox,
                        { telegram },
                        Json,
                        TelegramRateLimiter { },
                        TelegramOutboxConfig(batchSize = 10, maxConcurrency = 1, maxAttempts = 1),
                        CoroutineScope(Dispatchers.IO),
                        nowProvider = { now },
                        checkoutNotifications = restart(),
                    )
                failWrites("method <> 'sendMessage' OR chat_id > 0")
                worker().processOnce()
                assertEquals(2, snapshot(connection).getValue("telegram_outbox").size)
                allowWrites()

                // The staff outbox commits, then the worker dies before acknowledging its internal job.
                val notice =
                    com.hookah.platform.backend.telegram.GuestCheckoutStaffNotice(
                        101,
                        committed.orderId,
                        committed.batchId,
                        liveCard = false,
                    )
                assertTrue(restart().publishStaff(-601, notice))
                now = now.plusSeconds(3600)
                worker().processOnce()
                worker().processOnce()

                val after = snapshot(connection)
                assertEquals(1, after.getValue("order_batches").size)
                assertEquals(3, after.getValue("telegram_outbox").size)
                assertEquals(retiredSessions, after.getValue("table_sessions"))
                connection.createStatement().use {
                    it.executeQuery(
                        "SELECT chat_id,method,status,payload_json FROM telegram_outbox ORDER BY id",
                    ).use { rows ->
                        assertTrue(rows.next())
                        assertEquals("guestCheckoutStaff", rows.getString("method"))
                        assertEquals("SENT", rows.getString("status"))
                        assertTrue(rows.next())
                        assertEquals(501L, rows.getLong("chat_id"))
                        assertEquals("SENT", rows.getString("status"))
                        assertFalse(rows.getString("payload_json").contains("reply_markup"))
                        assertTrue(rows.next())
                        assertEquals(-601L, rows.getLong("chat_id"))
                        assertEquals("sendMessage", rows.getString("method"))
                        assertFalse(rows.next())
                    }
                }
            }
        }

    @Test
    fun `review09 stale support mutations cannot use a later platform binding`() =
        withFixture("ACTIVE", platformOwner = true) { connection, token, _ ->
            seedSession(connection, "ACTIVE")
            connection.createStatement().use {
                it.executeUpdate("DELETE FROM guest_table_session_exits WHERE user_id = 501")
                it.executeUpdate(
                    "INSERT INTO telegram_chat_context " +
                        "(chat_id,user_id,venue_id,table_id,table_token,table_session_id) " +
                        "VALUES (601,501,101,201,'availability-token',301)",
                )
            }

            suspend fun post(
                path: String,
                body: String = "{}",
            ) = client.post("/api/guest/support/threads$path") {
                headers { append(HttpHeaders.Authorization, "Bearer $token") }
                contentType(ContentType.Application.Json)
                setBody(body)
            }
            val created =
                post(
                    "",
                    """{"category":"OTHER","message":"Нужно уточнение","tableToken":"availability-token",
                  "tableSessionId":301,"expectedVisitGeneration":"0"}""",
                )
            assertEquals(HttpStatusCode.OK, created.status, created.bodyAsText())
            val ticket =
                Json.parseToJsonElement(created.bodyAsText()).jsonObject.getValue("thread")
                    .jsonObject.getValue("threadId")
            connection.createStatement().use {
                it.executeUpdate(
                    "INSERT INTO guest_visit_generations(user_id,table_session_id,consumed_exit_event_id) " +
                        "VALUES (501,301,194)",
                )
            }
            val before = snapshot(connection)
            assertEquals(428, post("/$ticket/messages", """{"message":"Без адреса"}""").status.value)
            assertEquals(428, post("/$ticket/resolve").status.value)

            suspend fun send(
                generation: Int,
                key: String? = null,
            ) = post(
                "/$ticket/messages",
                """{"message":"Ответ","expectedTableSessionId":301,"expectedVisitGeneration":"$generation"
                       ${key?.let { ""","clientMessageId":"$it"""" }.orEmpty()}}""",
            )

            fun query(generation: Int) = "?expectedTableSessionId=301&expectedVisitGeneration=$generation"
            assertEquals(HttpStatusCode.Conflict, send(0).status)
            assertEquals(HttpStatusCode.Conflict, send(0, UUID.randomUUID().toString()).status)
            assertEquals(HttpStatusCode.Conflict, post("/$ticket/resolve" + query(0)).status)
            assertEquals(HttpStatusCode.Conflict, post("/$ticket/reopen" + query(0)).status)
            assertEquals(before, snapshot(connection))
            assertEquals(HttpStatusCode.OK, send(194, UUID.randomUUID().toString()).status)
            assertEquals(HttpStatusCode.OK, post("/$ticket/resolve" + query(194)).status)
            assertEquals(HttpStatusCode.OK, post("/$ticket/reopen" + query(194)).status)
        }

    @Test
    fun `review08 member loss reports definitive denial or the original committed batch`() =
        withFixture("ACTIVE") { connection, token ->
            seedSession(connection, "ACTIVE")
            connection.createStatement().use {
                it.executeUpdate("DELETE FROM guest_table_session_exits WHERE user_id = 501")
                it.executeUpdate(
                    "INSERT INTO menu_categories(id, venue_id, name, sort_order) " +
                        "OVERRIDING SYSTEM VALUE VALUES (701,101,'Чай',0)",
                )
                it.executeUpdate(
                    "INSERT INTO menu_items(id,venue_id,category_id,name,price_minor,currency,is_available) " +
                        "OVERRIDING SYSTEM VALUE VALUES (702,101,701,'Чай',100,'RUB',true)",
                )
            }

            suspend fun submit(key: String) =
                client.post("/api/guest/order/add-batch") {
                    headers { append(HttpHeaders.Authorization, "Bearer $token") }
                    contentType(ContentType.Application.Json)
                    setBody(
                        """{"tableToken":"availability-token","tableSessionId":301,"expectedVisitGeneration":"0",
                       "idempotencyKey":"$key","tabId":402,"items":[{"itemId":702,"qty":1}]}""",
                    )
                }
            val first = submit("committed-in-shared-tab")
            assertEquals(HttpStatusCode.OK, first.status, first.bodyAsText())
            connection.createStatement().use {
                it.executeUpdate("DELETE FROM tab_member WHERE tab_id = 402 AND user_id = 501")
            }
            val revoked = snapshot(connection)
            coroutineScope {
                val left = async { submit("never-accepted-in-shared-tab") }
                val right = async { submit("never-accepted-in-shared-tab") }
                for (denied in listOf(left.await(), right.await())) {
                    assertEquals(HttpStatusCode.Forbidden, denied.status, denied.bodyAsText())
                    assertTrue(denied.bodyAsText().contains("ORDER_BATCH_TAB_ACCESS_DENIED"))
                }
            }
            val afterDenial = snapshot(connection)
            assertEquals(revoked - "guest_batch_idempotency", afterDenial - "guest_batch_idempotency")
            val replay = submit("committed-in-shared-tab")
            assertEquals(HttpStatusCode.OK, replay.status, replay.bodyAsText())
            assertEquals(
                Json.parseToJsonElement(first.bodyAsText()).jsonObject.getValue("batchId"),
                Json.parseToJsonElement(replay.bodyAsText()).jsonObject.getValue("batchId"),
            )
            assertEquals(afterDenial, snapshot(connection))
            assertEquals(2, afterDenial.getValue("guest_batch_idempotency").size)
            connection.createStatement().use {
                it.executeUpdate("INSERT INTO tab_member (tab_id,user_id,role) VALUES (402,501,'MEMBER')")
            }
            val regranted = snapshot(connection)
            val delayed = submit("never-accepted-in-shared-tab")
            assertEquals(HttpStatusCode.Forbidden, delayed.status, delayed.bodyAsText())
            assertEquals(regranted, snapshot(connection))
            assertEquals(HttpStatusCode.OK, submit("new-explicit-operation").status)
            assertEquals(2, snapshot(connection).getValue("order_batches").size)
        }

    @Test
    fun `review08 prepared bot checkout survives a lost commit reply and reload`() =
        withFixture("ACTIVE", platformOwner = false) { connection, _, config ->
            seedSession(connection, "ACTIVE")
            connection.createStatement().use {
                it.executeUpdate("DELETE FROM guest_table_session_exits WHERE user_id = 501")
                it.executeUpdate(
                    "INSERT INTO menu_categories(id,venue_id,name,sort_order) " +
                        "OVERRIDING SYSTEM VALUE VALUES (701,101,'Чай',0)",
                )
                it.executeUpdate(
                    "INSERT INTO menu_items(id,venue_id,category_id,name,price_minor,currency,is_available) " +
                        "OVERRIDING SYSTEM VALUE VALUES (702,101,701,'Чай',100,'RUB',true)",
                )
            }
            val pool =
                HikariConfig().apply {
                    jdbcUrl = config.property("db.jdbcUrl").getString()
                    username = config.property("db.user").getString()
                    password = config.property("db.password").getString()
                }
            HikariDataSource(pool).use { ds ->
                val scope = BotCheckoutScope(601, 501, 301, 0)
                val operation =
                    BotPreparedCheckout(
                        UUID.randomUUID().toString(),
                        402,
                        "Исходный комментарий",
                        listOf(OrderBatchItemInput(itemId = 702, qty = 1)),
                        null,
                        null,
                    )
                val original = BotPreparedCheckoutRepository(ds)
                original.prepare(connection, scope, operation)
                assertEquals(
                    operation,
                    original.prepare(connection, scope, operation.copy(id = UUID.randomUUID().toString())),
                )

                val orders = OrdersRepository(ds)

                fun submit(checkout: BotPreparedCheckout) =
                    ds.connection.use { tx ->
                        tx.autoCommit = false
                        val result =
                            checkNotNull(
                                orders.createGuestOrderBatch(
                                    connection = tx, tableId = 201, venueId = 101, tableSessionId = 301,
                                    userId = 501, idempotencyKey = checkout.idempotencyKey, tabId = checkout.tabId,
                                    comment = checkout.comment, items = checkout.items, expectedVisitGeneration = 0,
                                ),
                            )
                        tx.commit()
                        result
                    }
                assertFailsWith<java.io.IOException> {
                    submit(operation)
                    throw java.io.IOException("Commit succeeded before the caller lost its reply")
                }
                val committed = snapshot(connection)
                assertEquals(1, committed.getValue("order_batches").size)
                assertEquals(1, committed.getValue("guest_batch_idempotency").size)
                val reopened = BotPreparedCheckoutRepository(ds)
                assertNull(reopened.find(scope.copy(userId = 502)))
                assertNull(reopened.find(scope.copy(generation = 1)))
                assertEquals(operation, reopened.find(scope))
                val replay = submit(checkNotNull(reopened.find(scope)))
                assertTrue(replay.idempotencyReplay)
                assertEquals(committed, snapshot(connection))
                assertEquals(1, reopened.remove(scope, operation))
                val intentionalOrder = operation.copy(id = UUID.randomUUID().toString())
                reopened.prepare(connection, scope, intentionalOrder)
                assertEquals(0, reopened.remove(scope, operation))
                assertFalse(submit(intentionalOrder).idempotencyReplay)
                assertEquals(2, snapshot(connection).getValue("order_batches").size)
            }
        }

    @Test
    fun `review08 consent cannot follow a recycled invitation to another bill`() =
        withFixture("ACTIVE") { connection, token ->
            seedSession(connection, "ACTIVE")
            connection.createStatement().use {
                it.executeUpdate("DELETE FROM guest_table_session_exits WHERE user_id = 501")
                it.executeUpdate(
                    "INSERT INTO tab (id, venue_id, table_session_id, type, owner_user_id, status) " +
                        "OVERRIDING SYSTEM VALUE VALUES (403,101,301,'SHARED',502,'ACTIVE')",
                )
                it.executeUpdate("DELETE FROM tab_member WHERE tab_id = 402 AND user_id = 501")
                it.executeUpdate(
                    "INSERT INTO tab (id,venue_id,table_session_id,type,owner_user_id,status) " +
                        "OVERRIDING SYSTEM VALUE VALUES (404,101,301,'PERSONAL',501,'ACTIVE')",
                )
                it.executeUpdate("INSERT INTO tab_member (tab_id,user_id,role) VALUES (404,501,'OWNER')")
            }

            fun invite(tab: Int) {
                connection.prepareStatement(
                    "INSERT INTO tab_invite(tab_id,token,expires_at,created_by) VALUES (?,'0123',?,502)",
                ).use {
                    it.setInt(1, tab)
                    it.setTimestamp(2, Timestamp.from(Instant.now().plusSeconds(600)))
                    it.executeUpdate()
                }
            }

            suspend fun request(
                path: String,
                target: String = "",
            ) = client.post("/api/guest/tabs/$path") {
                headers { append(HttpHeaders.Authorization, "Bearer $token") }
                contentType(ContentType.Application.Json)
                setBody("""{"tableSessionId":301,"expectedVisitGeneration":"0","token":"0123","consent":true$target}""")
            }

            fun address(body: String): String {
                val fields = Json.parseToJsonElement(body).jsonObject
                return ""","inviteId":${fields.getValue("inviteId")},"expectedTabId":${fields.getValue("tabId")}"""
            }

            invite(402)
            val old = request("join-target")
            assertEquals(HttpStatusCode.OK, old.status, old.bodyAsText())
            val oldTarget = address(old.bodyAsText())
            connection.createStatement().use { it.executeUpdate("DELETE FROM tab_invite WHERE token = '0123'") }
            invite(403)
            val before = snapshot(connection)
            assertEquals(428, request("join").status.value)
            assertEquals(HttpStatusCode.NotFound, request("join", oldTarget).status)
            assertEquals(before, snapshot(connection))
            val current = request("join-target")
            assertEquals(HttpStatusCode.OK, current.status)
            assertEquals(HttpStatusCode.OK, request("join", address(current.bodyAsText())).status)
            assertTrue(snapshot(connection).getValue("tab_member").any { it.take(2) == listOf("403", "501") })
        }

    @Test
    fun `review08 extension outcome survives WebView replacement after staff approval`() =
        withFixture("ACTIVE", platformOwner = false) { connection, _, config ->
            if (System.getenv("HT_GUEST_VISIT_BROWSER_TEST") != "1" ||
                connection.metaData.databaseProductName != "PostgreSQL"
            ) {
                return@withFixture
            }
            seedSession(connection, "ACTIVE")
            connection.createStatement().use {
                it.executeUpdate("DELETE FROM guest_table_session_exits WHERE user_id = 501")
                it.executeUpdate("INSERT INTO venue_members(venue_id,user_id,role) VALUES (101,502,'STAFF')")
                it.executeUpdate(
                    "INSERT INTO shift_extension_settings(venue_id,enabled,duration_minutes,price_minor,currency) " +
                        "VALUES (101,TRUE,60,5000,'RUB')",
                )
            }
            for ((index, engine) in listOf("chromium", "webkit").withIndex()) {
                val order = 601 + index
                connection.createStatement().use {
                    it.executeUpdate("UPDATE orders SET status = 'CLOSED' WHERE status = 'ACTIVE'")
                    it.executeUpdate(
                        "INSERT INTO orders " +
                            "(id,venue_id,table_id,table_session_id,status,display_number,display_date) " +
                            "OVERRIDING SYSTEM VALUE VALUES ($order,101,201,301,'ACTIVE',$order,CURRENT_DATE)",
                    )
                    it.executeUpdate(
                        "INSERT INTO order_batches(order_id,tab_id,author_user_id,source,status) " +
                            "VALUES ($order,402,501,'MINIAPP','NEW')",
                    )
                }
                val staff = SessionTokenService(SessionTokenConfig.from(config, "test")).issueToken(502).token
                withBrowserServer(config) { miniapp, port -> runBrowser(miniapp, port, "extension", engine, staff) }
                assertEquals(index + 1, snapshot(connection).getValue("shift_extension_requests").size)
                assertEquals((index + 1) * 2, snapshot(connection).getValue("shift_extension_request_keys").size)
                connection.createStatement().use {
                    it.executeQuery("SELECT COUNT(*) FROM order_service_charges WHERE order_id = $order").use { rows ->
                        assertTrue(rows.next())
                        assertEquals(1, rows.getInt(1))
                    }
                }
            }
        }

    @Test
    fun `review08 stale platform ticket has no write after rejoin`() =
        withFixture("ACTIVE", platformOwner = true) { connection, token, _ ->
            seedSession(connection, "ACTIVE")
            connection.createStatement().use {
                it.executeUpdate("DELETE FROM guest_table_session_exits WHERE user_id = 501")
                it.executeUpdate(
                    "INSERT INTO telegram_chat_context " +
                        "(chat_id,user_id,venue_id,table_id,table_token,table_session_id) " +
                        "VALUES (601,501,101,201,'availability-token',301)",
                )
                it.executeUpdate(
                    "INSERT INTO guest_visit_generations(user_id,table_session_id,consumed_exit_event_id) " +
                        "VALUES (501,301,194)",
                )
            }

            suspend fun ticket(generation: String = "") =
                client.post("/api/guest/support/threads") {
                    headers { append(HttpHeaders.Authorization, "Bearer $token") }
                    contentType(ContentType.Application.Json)
                    setBody(
                        """{"category":"OTHER","message":"Проверка обращения",
                        "tableToken":"availability-token","tableSessionId":301$generation}""",
                    )
                }
            val before = snapshot(connection)
            assertEquals(428, ticket().status.value)
            assertEquals(HttpStatusCode.Conflict, ticket(""","expectedVisitGeneration":"0"""").status)
            assertEquals(before, snapshot(connection))
            val current = ticket(""","expectedVisitGeneration":"194"""")
            assertTrue(current.status.value in 200..299, current.bodyAsText())
        }

    @Test
    fun `expired platform binding can only be cleared by its own participation`() =
        withFixture("ACTIVE", platformOwner = true) { connection, token, _ ->
            seedSession(connection, "ACTIVE")
            connection.createStatement().use {
                it.executeUpdate("DELETE FROM guest_table_session_exits WHERE user_id=501")
                it.executeUpdate(
                    "INSERT INTO telegram_chat_context " +
                        "(chat_id,user_id,venue_id,table_id,table_token,table_session_id) " +
                        "VALUES (601,501,101,201,'availability-token',301)",
                )
            }
            connection.prepareStatement("UPDATE table_sessions SET expires_at=? WHERE id=301").use {
                it.setTimestamp(1, Timestamp.from(Instant.now().minusSeconds(15)))
                it.executeUpdate()
            }
            val before = snapshot(connection)
            assertEquals(HttpStatusCode.Conflict, endVisit(token, "999").status)
            assertEquals(before, snapshot(connection))
            assertEquals(HttpStatusCode.OK, endVisit(token, "0").status)
            assertTrue(snapshot(connection).getValue("telegram_chat_context").isEmpty())
        }

    private fun issueToken(userId: Long): String =
        SessionTokenService(
            SessionTokenConfig.from(MapApplicationConfig("api.session.jwtSecret" to "test-secret"), "test"),
        )
            .issueToken(userId).token

    private suspend fun ApplicationTestBuilder.scan(token: String): TableResolveResponse =
        Json.decodeFromString(resolve(token, "&resolveMode=create").bodyAsText())

    private suspend fun ApplicationTestBuilder.endVisit(
        token: String,
        generation: String? = null,
    ) = client.post("/api/guest/table/session/end") {
        headers { append(HttpHeaders.Authorization, "Bearer $token") }
        contentType(ContentType.Application.Json)
        setBody(Json.encodeToString(TableSessionEndRequest("availability-token", 301, generation)))
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
    ) = withFixture(subscription, platformOwner = false) { connection, token, _ -> test(connection, token) }

    protected fun withFixture(
        subscription: String,
        platformOwner: Boolean,
        enableCheckoutOutbox: Boolean = false,
        test: suspend ApplicationTestBuilder.(Connection, String, MapApplicationConfig) -> Unit,
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
                "guest.rateLimit.addBatch.maxRequests" to "20",
                "telegram.enabled" to "false",
                // The billing startup tick can create venue 102's missing subscription
                // between snapshots, independently of the resolve request under test.
                "billing.subscription.intervalSeconds" to "0",
            )
        if (platformOwner) config.put("telegram.platformOwnerId", "501")
        environment { this.config = config }
        application {
            moduleWithOverrides(
                ModuleOverrides(tableSessionCleanupEnabled = false, enableCheckoutOutboxInTests = enableCheckoutOutbox),
            )
        }
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
            test(connection, token, config)
        }
    }

    private suspend fun checkBrowserLaunch(
        config: MapApplicationConfig,
        withBill: Boolean,
    ) = withBrowserServer(config) { miniapp, port -> runBrowser(miniapp, port, withBill.toString()) }

    private suspend fun withBrowserServer(
        config: MapApplicationConfig,
        test: suspend (Path, String) -> Unit,
    ) {
        val miniapp =
            generateSequence(Path.of("").toAbsolutePath()) { it.parent }
                .map { it.resolve("miniapp") }.first { Files.exists(it.resolve("package.json")) }
        require(Files.exists(miniapp.resolve("dist/index.html"))) { "Build the Mini App before the browser check" }
        config.put("miniapp.staticDir", miniapp.resolve("dist").toString())
        config.put("telegram.token", "123456:local-visit-test")
        val server =
            embeddedServer(
                Netty,
                environment = applicationEnvironment { this.config = config },
                configure = {
                    connectors =
                        mutableListOf(
                            EngineConnectorBuilder().apply {
                                host = "127.0.0.1"
                                port = 0
                            },
                        )
                },
                module = { moduleWithOverrides(ModuleOverrides(tableSessionCleanupEnabled = false)) },
            ).start(false)
        try {
            val port = server.engine.resolvedConnectors().single().port
            test(miniapp, port.toString())
        } finally {
            server.stop(0, 1_000)
        }
    }

    private fun runBrowser(
        miniapp: Path,
        port: String,
        vararg arguments: String,
    ) {
        val process =
            ProcessBuilder("node", "e2e/fixtures/guest-visit-loopback.cjs", port, *arguments)
                .directory(miniapp.toFile()).inheritIO().start()
        try {
            assertTrue(process.waitFor(90, TimeUnit.SECONDS), "Loopback Mini App check timed out")
            assertEquals(0, process.exitValue(), "Browser did not recover the fixture visit")
        } finally {
            if (process.isAlive) process.destroyForcibly()
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
            "tab_invite",
            "staff_calls",
            "support_threads",
            "shift_extension_requests",
            "shift_extension_request_keys",
            "order_service_charges",
            "support_messages",
            "support_thread_reads",
            "guest_table_session_exits",
            "guest_visit_generations",
            "orders",
            "order_batches",
            "order_batch_items",
            "guest_batch_idempotency",
            "venue_accounting_shifts",
            "venue_accounting_sales",
            "venue_accounting_events",
            "audit_log",
            "analytics_events",
            "venue_subscriptions",
            "telegram_chat_context",
            "telegram_outbox",
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
    @Test
    fun `extension options overlapping a staff release cannot offer the retired seating deadline`() =
        withFixture("ACTIVE", platformOwner = false) { connection, token, config ->
            seedManagedSeatingScenario(connection)
            fixtureDataSource(config).use { ds ->
                val floor = SeatingRepository(ds)
                val seat =
                    floor.command(
                        101,
                        502,
                        SeatingCommand(
                            UUID.randomUUID().toString(),
                            "SEAT",
                            tableId = 201,
                            partySize = 1,
                            reconcileLegacySession = true,
                        ),
                    )
                connection.createStatement().use {
                    it.executeUpdate("UPDATE order_batches SET status='DELIVERED' WHERE order_id=601")
                }
                val staff = SessionTokenService(SessionTokenConfig.from(config, "test")).issueToken(502).token
                coroutineScope {
                    connection.autoCommit = false
                    val blocker =
                        connection.createStatement().use { sql ->
                            sql.execute("LOCK TABLE venue_settings IN ACCESS EXCLUSIVE MODE")
                            sql.executeQuery("SELECT pg_backend_pid()").use {
                                it.next()
                                it.getInt(1)
                            }
                        }
                    val reading =
                        async(Dispatchers.IO) {
                            client.get(
                                "/api/guest/table/extension-options?tableToken=availability-token" +
                                    "&tableSessionId=301&tabId=402",
                            ) {
                                headers { append(HttpHeaders.Authorization, "Bearer $token") }
                            }
                        }
                    try {
                        // The settings read is the barrier between the original session lookup and its seating check.
                        ds.connection.use { monitor ->
                            monitor.createStatement().use { sql ->
                                val waitSql =
                                    "SELECT EXISTS(SELECT 1 FROM pg_stat_activity " +
                                        "WHERE $blocker = ANY(pg_blocking_pids(pid)) " +
                                        "AND query LIKE '%FROM venue_settings%')"
                                kotlinx.coroutines.withTimeout(10000) {
                                    while (!sql.executeQuery(waitSql).use {
                                            it.next()
                                            it.getBoolean(1)
                                        }
                                    ) {
                                        kotlinx.coroutines.delay(10)
                                    }
                                }
                            }
                        }
                        val closed =
                            client.post("/api/venue/orders/601/close?venueId=101") {
                                headers { append(HttpHeaders.Authorization, "Bearer $staff") }
                            }
                        assertEquals(HttpStatusCode.OK, closed.status, closed.bodyAsText())
                        floor.command(
                            101,
                            502,
                            SeatingCommand(
                                UUID.randomUUID().toString(),
                                "RELEASE",
                                id = seat.id,
                                revision = seat.revision,
                            ),
                        )
                    } finally {
                        connection.rollback()
                        connection.autoCommit = true
                    }
                    val response = reading.await()
                    assertEquals(HttpStatusCode.NotFound, response.status, response.bodyAsText())
                    assertFalse(response.bodyAsText().contains("9999"))
                }
                connection.createStatement().use { sql ->
                    sql.executeQuery("SELECT count(*) FROM order_service_charges").use {
                        it.next()
                        assertEquals(0, it.getInt(1))
                    }
                }
            }
        }

    override fun database(): Triple<String, String, String> {
        val database = PostgresTestEnv.createDatabase()
        return Triple(database.jdbcUrl, database.user, database.password)
    }
}
