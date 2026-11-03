package com.hookah.platform.backend.miniapp.venue

import com.hookah.platform.backend.miniapp.session.SessionTokenConfig
import com.hookah.platform.backend.miniapp.session.SessionTokenService
import com.hookah.platform.backend.module
import com.hookah.platform.backend.venue.links.h2
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.config.MapApplicationConfig
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.sql.Connection
import javax.sql.DataSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class VenuePublicationRoutesTest {
    @Test
    fun `selected owner publishes only saved ready draft and subscription still gates guest link`() =
        testApplication {
            h2().use { ds ->
                val config =
                    MapApplicationConfig(
                        "app.env" to "test",
                        "db.jdbcUrl" to ds.jdbcUrl,
                        "db.user" to "sa",
                        "db.password" to "",
                        "api.session.jwtSecret" to "publication-test-secret",
                        "telegram.enabled" to "false",
                        "telegram.webAppPublicUrl" to "https://stage.example.org/miniapp/",
                        "telegram.botUsername" to "ExampleBot",
                        "platform.ownerUserId" to "404",
                    )
                environment { this.config = config }
                application { module() }
                val tokenService = SessionTokenService(SessionTokenConfig.from(config, "test"))

                fun token(actor: Long) = "Bearer ${tokenService.issueToken(actor).token}"

                suspend fun inspect(
                    actor: Long,
                    venue: Long = 1001,
                ) = client.get(
                    "/api/venue/$venue/publication?check=true",
                ) { header(HttpHeaders.Authorization, token(actor)) }

                suspend fun publish(
                    actor: Long,
                    venue: Long = 1001,
                    body: String = """{"confirmed":true}""",
                ) = client.post("/api/venue/$venue/publication") {
                    header(HttpHeaders.Authorization, token(actor))
                    header(HttpHeaders.ContentType, "application/json")
                    setBody(body)
                }
                assertEquals(HttpStatusCode.Unauthorized, client.get("/api/venue/1001/publication").status)
                seedPublicationFixture(ds)
                assertEquals(HttpStatusCode.Forbidden, inspect(303).status)
                assertEquals(HttpStatusCode.Forbidden, inspect(505).status)
                val manager = inspect(202)
                assertEquals(HttpStatusCode.OK, manager.status)
                assertEquals("no-store", manager.headers[HttpHeaders.CacheControl])
                assertEquals("false", body(manager.bodyAsText()).text("ownerCanPublish"))
                for (actor in listOf(202L, 303L, 404L, 505L)) {
                    assertEquals(HttpStatusCode.Forbidden, publish(actor).status, "Actor $actor")
                }
                assertEquals(HttpStatusCode.BadRequest, publish(101, body = """{"confirmed":false}""").status)
                val denied = body(publish(101).bodyAsText())
                assertEquals("NOT_READY", denied.text("outcome"))
                assertEquals(
                    listOf("ADDRESS", "HOURS", "DESCRIPTION", "MENU"),
                    denied.getValue("missing").jsonArray.map { it.jsonPrimitive.content },
                )
                ds.connection.use { connection ->
                    readyPublicationVenue(connection, 1001)
                    connection.exec(
                        "INSERT INTO venue_subscriptions (venue_id, status) VALUES (1001, 'SUSPENDED')",
                    )
                }
                assertEquals(emptyList(), body(inspect(101).bodyAsText()).getValue("missing").jsonArray)
                ds.connection.use { it.exec("UPDATE menu_categories SET is_active = FALSE WHERE venue_id = 1001") }
                val staleCheck = body(publish(101).bodyAsText())
                assertEquals("NOT_READY", staleCheck.text("outcome"))
                assertEquals(listOf("MENU"), staleCheck.getValue("missing").jsonArray.map { it.jsonPrimitive.content })
                ds.connection.use { it.exec("UPDATE menu_categories SET is_active = TRUE WHERE venue_id = 1001") }
                val response = publish(101)
                assertEquals(HttpStatusCode.OK, response.status)
                val published = body(response.bodyAsText())
                assertEquals("PUBLISHED", published.text("outcome"))
                assertEquals("PUBLISHED", published.text("status"))
                assertEquals("UNAVAILABLE", published.text("guestAccess"))
                assertEquals("ALREADY_PUBLISHED", body(publish(101).bodyAsText()).text("outcome"))
                assertEquals("DRAFT", body(inspect(101, 1002).bodyAsText()).text("status"))
                assertEquals(HttpStatusCode.NotFound, client.get("/v/same-name").status)
                ds.connection.use { connection ->
                    connection.exec("UPDATE venue_subscriptions SET status = 'ACTIVE' WHERE venue_id = 1001")
                }
                assertEquals("AVAILABLE", body(inspect(101).bodyAsText()).text("guestAccess"))
                assertEquals(HttpStatusCode.OK, client.get("/v/same-name").status)
                val link = client.get("/api/venue/1001/public-link") { header(HttpHeaders.Authorization, token(101)) }
                val linkBody = body(link.bodyAsText())
                assertEquals("true", linkBody.text("guestAvailable"))
                assertEquals("false", linkBody.text("catalogListed"))
                val catalog = client.get("/api/guest/catalog?q=Same") { header(HttpHeaders.Authorization, token(101)) }
                assertEquals(HttpStatusCode.OK, catalog.status)
                assertFalse(catalog.bodyAsText().contains("Same Name"))
                ds.connection.use { connection ->
                    assertEquals(1, connection.auditCount(1001))
                    assertEquals(0, connection.auditCount(1002))
                    for (status in listOf("HIDDEN", "PAUSED", "SUSPENDED", "ARCHIVED", "DELETED")) {
                        connection.exec("UPDATE venues SET status = '$status' WHERE id = 1001")
                        val stopped = body(publish(101).bodyAsText())
                        assertEquals("INVALID_STATUS", stopped.text("outcome"))
                        assertEquals(status, stopped.text("status"))
                    }
                    assertEquals(1, connection.auditCount(1001))
                }
            }
        }

    private fun body(value: String): JsonObject = Json.parseToJsonElement(value).jsonObject

    private fun JsonObject.text(key: String): String = getValue(key).jsonPrimitive.content
}

internal fun seedPublicationFixture(ds: DataSource) {
    ds.connection.use { connection ->
        connection.exec("INSERT INTO users (telegram_user_id) VALUES (101), (202), (303), (404), (505)")
        connection.exec(
            """
            INSERT INTO venues (id, name, status, catalog_listed, public_slug) OVERRIDING SYSTEM VALUE VALUES
              (1001, 'Same Name', 'DRAFT', FALSE, 'same-name'),
              (1002, 'Same Name', 'DRAFT', TRUE, 'same-name-2'),
              (1003, 'Same Name', 'DRAFT', TRUE, 'same-name-3')
            """.trimIndent(),
        )
        connection.exec("INSERT INTO venue_public_addresses (venue_id, slug) VALUES (1001, 'same-name')")
        connection.exec(
            """
            INSERT INTO venue_members (venue_id, user_id, role) VALUES
              (1001, 101, 'OWNER'), (1001, 202, 'MANAGER'), (1001, 303, 'STAFF'),
              (1002, 101, 'OWNER'), (1003, 505, 'OWNER')
            """.trimIndent(),
        )
    }
}

internal fun readyPublicationVenue(
    connection: Connection,
    id: Long,
) {
    connection.exec("UPDATE venues SET address = 'Test address', card_description = 'Description' WHERE id = $id")
    for (day in 1..7) {
        connection.exec(
            "INSERT INTO venue_booking_hours (venue_id, weekday, opens_at, closes_at, is_closed) " +
                "VALUES ($id, $day, TIME '12:00:00', TIME '23:00:00', FALSE)",
        )
    }
    connection.exec(
        "INSERT INTO menu_categories (id, venue_id, name, sort_order, is_active) " +
            "OVERRIDING SYSTEM VALUE VALUES ($id, $id, 'Menu', 0, TRUE)",
    )
    connection.exec(
        "INSERT INTO menu_items (id, venue_id, category_id, name, price_minor, currency, is_available, sort_order) " +
            "OVERRIDING SYSTEM VALUE VALUES ($id, $id, $id, 'Item', 10000, 'RUB', TRUE, 0)",
    )
}

internal fun Connection.exec(sql: String) {
    createStatement().use { it.executeUpdate(sql) }
}

internal fun Connection.auditCount(venueId: Long): Int =
    createStatement().use {
        it.executeQuery(
            "SELECT COUNT(*) FROM audit_log WHERE entity_id = $venueId AND action = 'VENUE_STATUS_CHANGE'",
        ).use {
                rs ->
            assertTrue(rs.next())
            rs.getInt(1)
        }
    }
