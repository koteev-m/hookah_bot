package com.hookah.platform.backend.miniapp.venue.dashboard

import com.hookah.platform.backend.miniapp.session.SessionTokenConfig
import com.hookah.platform.backend.miniapp.session.SessionTokenService
import com.hookah.platform.backend.miniapp.venue.seating.OWNER
import com.hookah.platform.backend.miniapp.venue.seating.STAFF
import com.hookah.platform.backend.miniapp.venue.seating.h2Seating
import com.hookah.platform.backend.miniapp.venue.seating.insert
import com.hookah.platform.backend.miniapp.venue.seating.rows
import com.hookah.platform.backend.miniapp.venue.seating.seedSeating
import com.hookah.platform.backend.miniapp.venue.seating.write
import com.hookah.platform.backend.module
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.config.MapApplicationConfig
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

internal fun Connection.dashboardOrder(
    venue: Long,
    table: Long,
): Long {
    val session =
        insert(
            "INSERT INTO table_sessions(venue_id,table_id,started_at,last_activity_at,expires_at,status) " +
                "VALUES (?,?,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,'ACTIVE')",
            venue,
            table,
        )
    return insert(
        "INSERT INTO orders(venue_id,table_id,table_session_id,status) VALUES (?,?,?,'ACTIVE')",
        venue,
        table,
        session,
    )
}

internal fun Connection.dashboardBatch(
    order: Long,
    status: String = "NEW",
): Long = insert("INSERT INTO order_batches(order_id,source,status) VALUES (?,'MINIAPP',?)", order, status)

internal fun Connection.dashboardCall(
    venue: Long,
    table: Long,
    status: String = "NEW",
): Long = insert("INSERT INTO staff_calls(venue_id,table_id,reason,status) VALUES (?,?,'COME',?)", venue, table, status)

class VenueDashboardRoutesTest {
    @Test
    fun `protocol three scopes booking tasks and per tab initial sends without breaking protocol two`() =
        runBlocking {
            h2Seating().use { ds ->
                val (venue, tables) = seedSeating(ds)
                val repo = VenueDashboardRepository(ds)
                ds.connection.use { c ->
                    val order = c.dashboardOrder(venue, tables[0])
                    val session =
                        c.rows(
                            "SELECT table_session_id FROM orders WHERE id=?",
                            order,
                        ) { it.getLong(1) }.single()
                    val tab1 =
                        c.insert(
                            "INSERT INTO tab(venue_id,table_session_id,type) VALUES (?,?,'SHARED')",
                            venue,
                            session,
                        )
                    val tab2 =
                        c.insert(
                            "INSERT INTO tab(venue_id,table_session_id,type,owner_user_id) VALUES (?,?,'PERSONAL',?)",
                            venue,
                            session,
                            OWNER,
                        )
                    val first = c.dashboardBatch(order)
                    val extra = c.dashboardBatch(order)
                    val personal = c.dashboardBatch(order)
                    c.write("UPDATE order_batches SET tab_id=? WHERE id=?", tab1, first)
                    c.write("UPDATE order_batches SET tab_id=? WHERE id=?", tab1, extra)
                    c.write("UPDATE order_batches SET tab_id=? WHERE id=?", tab2, personal)
                    val booking =
                        c.insert(
                            "INSERT INTO bookings(venue_id,user_id,scheduled_at,status,display_date," +
                                "display_number) VALUES (?,?,CURRENT_TIMESTAMP,'PENDING',CURRENT_DATE,1)",
                            venue,
                            STAFF,
                        )
                    c.insert(
                        "INSERT INTO bookings(venue_id,user_id,scheduled_at,status,display_date," +
                            "display_number) VALUES (?,?,CURRENT_TIMESTAMP,'CHANGED',CURRENT_DATE,2)",
                        venue,
                        OWNER,
                    )
                    val current = repo.snapshot(venue, OWNER, 3)
                    assertEquals(3, current.monitoringVersion)
                    assertEquals("ORDER_CREATED", current.attention.single { it.id == "batch:$personal" }.kind)
                    assertEquals("BATCH_ADDED", current.attention.single { it.id == "batch:$extra" }.kind)
                    assertEquals(listOf(booking), current.attention.mapNotNull { it.bookingId })
                    assertTrue(repo.snapshot(venue, STAFF, 3).attention.none { it.bookingId != null })
                    assertTrue(
                        repo.snapshot(venue, OWNER).attention.none { it.bookingId != null || it.batchId != null },
                    )
                    c.write("UPDATE bookings SET status='CONFIRMED' WHERE id=?", booking)
                    assertTrue(repo.snapshot(venue, OWNER, 3).attention.none { it.bookingId == booking })
                    val thread =
                        c.insert(
                            "INSERT INTO support_threads(venue_id,guest_user_id,booking_id,category,status," +
                                "thread_type,assignee_scope,created_source,title) " +
                                "VALUES (?,?,?,'BOOKING','NEW','BOOKING_THREAD','VENUE','GUEST_MINIAPP','Booking')",
                            venue,
                            STAFF,
                            booking,
                        )
                    c.insert(
                        "INSERT INTO support_messages(thread_id,author_role,source,text) " +
                            "VALUES (?,'SYSTEM','SYSTEM','Internal')",
                        thread,
                    )
                    assertTrue(repo.snapshot(venue, OWNER, 3).attention.none { it.kind == "BOOKING_MESSAGE" })
                    val message =
                        c.insert(
                            "INSERT INTO support_messages(thread_id,author_user_id,author_role,source,text) " +
                                "VALUES (?,?,'GUEST','GUEST_MINIAPP','Private guest message')",
                            thread,
                            STAFF,
                        )
                    assertEquals(
                        "bookingMessage:$message",
                        repo.snapshot(venue, OWNER, 3).attention.single {
                            it.kind == "BOOKING_MESSAGE"
                        }.id,
                    )
                    c.write(
                        "INSERT INTO support_thread_reads(thread_id,user_id,last_read_message_id) VALUES (?,?,?)",
                        thread,
                        OWNER,
                        message,
                    )
                    assertTrue(repo.snapshot(venue, OWNER, 3).attention.none { it.kind == "BOOKING_MESSAGE" })
                    val bill = c.dashboardCall(venue, tables[0])
                    c.write("UPDATE staff_calls SET reason='BILL' WHERE id=?", bill)
                    assertEquals(
                        true,
                        repo.snapshot(venue, OWNER, 3).attention.single { it.callId == bill }.billRequest,
                    )
                }
            }
        }

    @Test
    fun `bounded current snapshot preserves first and append without history or read mutations`() =
        runBlocking<Unit> {
            h2Seating().use { ds ->
                val (venue, tables) = seedSeating(ds)
                val (foreign, foreignTables) = seedSeating(ds)
                val repo = VenueDashboardRepository(ds)
                ds.connection.use { c ->
                    val order = c.dashboardOrder(venue, tables[0])
                    val first = c.dashboardBatch(order)
                    val append = c.dashboardBatch(order)
                    repeat(135) { c.dashboardBatch(order, "DELIVERED") }
                    val call = c.dashboardCall(venue, tables[0])
                    c.dashboardBatch(c.dashboardOrder(foreign, foreignTables[0]))
                    c.dashboardCall(foreign, foreignTables[0])
                    val protectedTables =
                        listOf("venue_accounting_shifts", "venue_accounting_events", "audit_log", "telegram_outbox")

                    fun counts() =
                        protectedTables.map { table ->
                            c.rows(
                                "SELECT COUNT(*) FROM $table",
                            ) { it.getLong(1) }.single()
                        }
                    val before = counts()
                    val initial = repo.snapshot(venue, OWNER)
                    assertTrue(initial.attentionComplete)
                    assertEquals(2, initial.monitoringVersion)
                    assertEquals(3, initial.attentionTotalAtLeast)
                    assertEquals(3, initial.attention.size)
                    assertEquals("ORDER_CREATED", initial.attention.single { it.id == "batch:$first" }.kind)
                    assertEquals("BATCH_ADDED", initial.attention.single { it.id == "batch:$append" }.kind)
                    assertEquals(setOf(order), initial.attention.mapNotNull { it.orderId }.toSet())
                    assertEquals(1, initial.activeOrders)
                    assertEquals(0, initial.occupiedTables)
                    c.write("UPDATE order_batches SET status='ACCEPTED' WHERE id = ?", first)
                    c.write("UPDATE staff_calls SET status='DONE' WHERE id = ?", call)
                    val handled = repo.snapshot(venue, STAFF)
                    assertTrue(handled.attentionComplete)
                    assertEquals(listOf("batch:$append"), handled.attention.map { it.id })
                    assertEquals(null, handled.staffChatLinked)
                    assertEquals(null, handled.subscriptionStatus)
                    c.write("UPDATE orders SET status='CLOSED' WHERE id = ?", order)
                    assertTrue(repo.snapshot(venue, OWNER).attention.isEmpty())
                    assertEquals(0, repo.snapshot(venue, OWNER).attentionTotalAtLeast)
                    assertEquals(before, counts(), "read model must not write financial, audit or Telegram records")
                }
            }
        }

    @Test
    fun `real HTTP scopes every role and exact call deep link and fails closed after revocation`() =
        testApplication {
            val url =
                "jdbc:h2:mem:dashboard_${UUID.randomUUID()};MODE=PostgreSQL;" +
                    "DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE"
            val config =
                MapApplicationConfig(
                    "app.env" to "test",
                    "api.session.jwtSecret" to "test-secret",
                    "db.jdbcUrl" to url,
                    "db.user" to "sa",
                    "db.password" to "",
                    "telegram.enabled" to "false",
                )
            environment { this.config = config }
            application { module() }
            client.get("/health")
            HikariDataSource(
                HikariConfig().apply {
                    jdbcUrl = url
                    username = "sa"
                    password = ""
                },
            ).use { ds ->
                val (venue, tables) = seedSeating(ds)
                val (foreign, foreignTables) = seedSeating(ds)
                ds.connection.use { c ->
                    c.write("INSERT INTO venue_members(venue_id,user_id,role) VALUES (?,1003,'MANAGER')", venue)
                    c.write("DELETE FROM venue_members WHERE venue_id = ?", foreign)
                    val order = c.dashboardOrder(venue, tables[0])
                    c.dashboardBatch(order)
                    c.dashboardBatch(order)
                    c.dashboardCall(venue, tables[0])
                    c.dashboardCall(foreign, foreignTables[0])
                }
                val tokens = SessionTokenService(SessionTokenConfig.from(config, "test"))
                val endpoint = "/api/venue/$venue/dashboard"
                assertEquals(HttpStatusCode.Unauthorized, client.get(endpoint).status)
                for (actor in listOf(OWNER, STAFF, 1003L)) {
                    val response = client.get(endpoint) { bearerAuth(tokens.issueToken(actor).token) }
                    assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
                    assertEquals("no-store", response.headers[HttpHeaders.CacheControl])
                    val body = Json.decodeFromString<VenueDashboardSnapshot>(response.bodyAsText())
                    assertEquals(actor, body.userId)
                    assertEquals(3, body.attention.size)
                    val upgradedResponse =
                        client.get("$endpoint?monitoringVersion=3") {
                            bearerAuth(tokens.issueToken(actor).token)
                        }
                    assertEquals(HttpStatusCode.OK, upgradedResponse.status)
                    val upgraded = Json.decodeFromString<VenueDashboardSnapshot>(upgradedResponse.bodyAsText())
                    assertEquals(3, upgraded.monitoringVersion)
                    assertEquals(2, upgraded.attention.count { it.batchId != null })
                    assertTrue(upgraded.attention.filter { it.batchId != null }.all { it.kind == "ORDER_SEND_UNKNOWN" })
                    assertEquals(
                        HttpStatusCode.BadRequest,
                        client.get("$endpoint?monitoringVersion=4") {
                            bearerAuth(tokens.issueToken(actor).token)
                        }.status,
                    )
                    assertTrue(body.attentionComplete)
                    assertEquals(if (actor == STAFF) null else false, body.staffChatLinked)
                    assertEquals(
                        HttpStatusCode.Forbidden,
                        client.get("/api/venue/$foreign/dashboard") {
                            bearerAuth(tokens.issueToken(actor).token)
                        }.status,
                    )
                    if (actor == OWNER) {
                        val out = Path.of("build", "venue-dashboard-http.json")
                        Files.createDirectories(out.parent)
                        Files.writeString(out, response.bodyAsText())
                    }
                }
                val call = ds.connection.use { c -> c.dashboardCall(venue, tables[0], "DONE") }
                ds.connection.use { c ->
                    c.write("UPDATE staff_calls SET created_at = TIMESTAMP '2020-01-01 00:00:00' WHERE id = ?", call)
                }
                val detail =
                    client.get(
                        "/api/venue/$venue/staff-calls?callId=$call",
                    ) { bearerAuth(tokens.issueToken(STAFF).token) }
                assertEquals(HttpStatusCode.OK, detail.status)
                val items = Json.parseToJsonElement(detail.bodyAsText()).jsonObject.getValue("items").jsonArray
                assertEquals(1, items.size)
                assertEquals(call.toString(), items.single().jsonObject.getValue("id").jsonPrimitive.content)
                assertFalse(items.single().jsonObject.getValue("status").jsonPrimitive.content == "NEW")
                ds.connection.use {
                        c ->
                    c.write("DELETE FROM venue_members WHERE venue_id = ? AND user_id = ?", venue, STAFF)
                }
                assertEquals(
                    HttpStatusCode.Forbidden,
                    client.get(endpoint) { bearerAuth(tokens.issueToken(STAFF).token) }.status,
                )
                ds.connection.use { c -> c.write("UPDATE venues SET status='DELETED' WHERE id = ?", venue) }
                assertEquals(
                    HttpStatusCode.Forbidden,
                    client.get(endpoint) { bearerAuth(tokens.issueToken(OWNER).token) }.status,
                )
            }
        }
}
