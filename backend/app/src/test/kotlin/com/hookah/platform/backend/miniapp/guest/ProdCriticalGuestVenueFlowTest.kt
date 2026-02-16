package com.hookah.platform.backend.miniapp.guest

import com.hookah.platform.backend.api.ApiErrorCodes
import com.hookah.platform.backend.miniapp.guest.api.AddBatchItemDto
import com.hookah.platform.backend.miniapp.guest.api.AddBatchRequest
import com.hookah.platform.backend.miniapp.guest.api.AddBatchResponse
import com.hookah.platform.backend.miniapp.guest.api.GuestTabsResponse
import com.hookah.platform.backend.miniapp.guest.api.TableResolveResponse
import com.hookah.platform.backend.miniapp.session.SessionTokenConfig
import com.hookah.platform.backend.miniapp.session.SessionTokenService
import com.hookah.platform.backend.miniapp.venue.VenueStatus
import com.hookah.platform.backend.module
import com.hookah.platform.backend.test.assertApiErrorEnvelope
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
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.sql.DriverManager
import java.sql.Statement
import java.sql.Timestamp
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ProdCriticalGuestVenueFlowTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `e2e qr resolve add-batch and venue accept changes status`() =
        testApplication {
            val jdbcUrl = buildJdbcUrl("prod-critical-e2e")
            val config = buildConfig(jdbcUrl)

            environment { this.config = config }
            application { module() }
            client.get("/health")

            val venueId = seedVenue(jdbcUrl, VenueStatus.PUBLISHED.dbValue)
            seedVenueMembership(jdbcUrl, venueId, TELEGRAM_USER_ID, role = "MANAGER")
            val tableId = seedTable(jdbcUrl, venueId, 44)
            seedTableToken(jdbcUrl, tableId, "e2e-token")
            seedSubscription(jdbcUrl, venueId, "ACTIVE")
            val categoryId = seedMenuCategory(jdbcUrl, venueId)
            val itemId = seedMenuItem(jdbcUrl, venueId, categoryId, "Cola")
            val token = issueToken(config)

            val resolveResponse =
                client.get("/api/guest/table/resolve?tableToken=e2e-token") {
                    headers { append(HttpHeaders.Authorization, "Bearer $token") }
                }
            assertEquals(HttpStatusCode.OK, resolveResponse.status)
            val resolvePayload = json.decodeFromString(TableResolveResponse.serializer(), resolveResponse.bodyAsText())

            val tabsResponse =
                client.get("/api/guest/tabs?table_session_id=${resolvePayload.tableSessionId}") {
                    headers { append(HttpHeaders.Authorization, "Bearer $token") }
                }
            assertEquals(HttpStatusCode.OK, tabsResponse.status)
            val tabsPayload = json.decodeFromString(GuestTabsResponse.serializer(), tabsResponse.bodyAsText())
            val personalTabId = tabsPayload.tabs.single { it.type == "PERSONAL" }.id

            val addBatchResponse =
                client.post("/api/guest/order/add-batch") {
                    contentType(ContentType.Application.Json)
                    headers { append(HttpHeaders.Authorization, "Bearer $token") }
                    setBody(
                        json.encodeToString(
                            AddBatchRequest.serializer(),
                            AddBatchRequest(
                                tableToken = "e2e-token",
                                tableSessionId = resolvePayload.tableSessionId,
                                tabId = personalTabId,
                                idempotencyKey = "e2e-idem-1",
                                items = listOf(AddBatchItemDto(itemId = itemId, qty = 1)),
                                comment = "from e2e",
                            ),
                        ),
                    )
                }
            assertEquals(HttpStatusCode.OK, addBatchResponse.status)
            val addBatchPayload = json.decodeFromString(AddBatchResponse.serializer(), addBatchResponse.bodyAsText())

            val statusResponse =
                client.post("/api/venue/orders/${addBatchPayload.orderId}/status?venueId=$venueId") {
                    contentType(ContentType.Application.Json)
                    headers { append(HttpHeaders.Authorization, "Bearer $token") }
                    setBody("""{"nextStatus":"accepted"}""")
                }
            assertEquals(HttpStatusCode.OK, statusResponse.status)

            val detailResponse =
                client.get("/api/venue/orders/${addBatchPayload.orderId}?venueId=$venueId") {
                    headers { append(HttpHeaders.Authorization, "Bearer $token") }
                }
            assertEquals(HttpStatusCode.OK, detailResponse.status)
            val orderStatus =
                json.parseToJsonElement(detailResponse.bodyAsText())
                    .jsonObject["order"]
                    ?.jsonObject
                    ?.get("status")
                    ?.jsonPrimitive
                    ?.content
            assertEquals("accepted", orderStatus)
            assertEquals(1, countAnalyticsEvents(jdbcUrl, "table_session_started", venueId))
            assertTrue(countAnalyticsEvents(jdbcUrl, "batch_created", venueId) >= 1)
            assertEquals(1, countAnalyticsEvents(jdbcUrl, "batch_status_changed", venueId))
        }

    @Test
    fun `tenant isolation blocks foreign venue menu orders and tables access`() =
        testApplication {
            val jdbcUrl = buildJdbcUrl("prod-critical-tenant")
            val config = buildConfig(jdbcUrl)

            environment { this.config = config }
            application { module() }
            client.get("/health")

            val ownVenueId = seedVenue(jdbcUrl, VenueStatus.PUBLISHED.dbValue)
            seedVenueMembership(jdbcUrl, ownVenueId, TELEGRAM_USER_ID, role = "MANAGER")
            val foreignVenueId = seedVenue(jdbcUrl, VenueStatus.PUBLISHED.dbValue)
            val foreignTableId = seedTable(jdbcUrl, foreignVenueId, 7)
            seedTableToken(jdbcUrl, foreignTableId, "tenant-foreign-token")
            val token = issueToken(config)

            val menuResponse =
                client.post("/api/venue/menu/categories?venueId=$foreignVenueId") {
                    contentType(ContentType.Application.Json)
                    headers { append(HttpHeaders.Authorization, "Bearer $token") }
                    setBody("""{"name":"Blocked"}""")
                }
            assertEquals(HttpStatusCode.Forbidden, menuResponse.status)
            assertApiErrorEnvelope(menuResponse, ApiErrorCodes.FORBIDDEN)

            val orderResponse =
                client.get("/api/venue/orders/999999?venueId=$foreignVenueId") {
                    headers { append(HttpHeaders.Authorization, "Bearer $token") }
                }
            assertEquals(HttpStatusCode.Forbidden, orderResponse.status)
            assertApiErrorEnvelope(orderResponse, ApiErrorCodes.FORBIDDEN)

            val tableResponse =
                client.post("/api/venue/tables/$foreignTableId/rotate-token?venueId=$foreignVenueId") {
                    headers { append(HttpHeaders.Authorization, "Bearer $token") }
                }
            assertEquals(HttpStatusCode.Forbidden, tableResponse.status)
            assertApiErrorEnvelope(tableResponse, ApiErrorCodes.FORBIDDEN)
        }

    private fun buildJdbcUrl(prefix: String): String {
        val dbName = "$prefix-${UUID.randomUUID()}"
        return "jdbc:h2:mem:$dbName;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1"
    }

    private fun buildConfig(jdbcUrl: String): MapApplicationConfig =
        MapApplicationConfig(
            "app.env" to "test",
            "api.session.jwtSecret" to "test-secret",
            "db.jdbcUrl" to jdbcUrl,
            "db.user" to "sa",
            "db.password" to "",
            "telegram.enabled" to "false",
        )

    private fun issueToken(config: MapApplicationConfig): String {
        val service = SessionTokenService(SessionTokenConfig.from(config, "test"))
        return service.issueToken(TELEGRAM_USER_ID).token
    }

    private fun seedVenue(
        jdbcUrl: String,
        status: String,
    ): Long =
        DriverManager.getConnection(jdbcUrl, "sa", "").use { connection ->
            connection.prepareStatement(
                """
                INSERT INTO venues (name, city, address, status)
                VALUES ('Venue', 'City', 'Address', ?)
                """.trimIndent(),
                Statement.RETURN_GENERATED_KEYS,
            ).use { statement ->
                statement.setString(1, status)
                statement.executeUpdate()
                statement.generatedKeys.use { rs ->
                    if (rs.next()) {
                        return rs.getLong(1)
                    }
                }
            }
            error("Failed to insert venue")
        }

    private fun seedVenueMembership(
        jdbcUrl: String,
        venueId: Long,
        userId: Long,
        role: String,
    ) {
        DriverManager.getConnection(jdbcUrl, "sa", "").use { connection ->
            connection.prepareStatement(
                """
                MERGE INTO users (telegram_user_id, username, first_name, last_name)
                KEY (telegram_user_id)
                VALUES (?, 'user', 'Test', 'User')
                """.trimIndent(),
            ).use { statement ->
                statement.setLong(1, userId)
                statement.executeUpdate()
            }
            connection.prepareStatement(
                """
                INSERT INTO venue_members (venue_id, user_id, role)
                VALUES (?, ?, ?)
                """.trimIndent(),
            ).use { statement ->
                statement.setLong(1, venueId)
                statement.setLong(2, userId)
                statement.setString(3, role)
                statement.executeUpdate()
            }
        }
    }

    private fun seedTable(
        jdbcUrl: String,
        venueId: Long,
        tableNumber: Int,
    ): Long =
        DriverManager.getConnection(jdbcUrl, "sa", "").use { connection ->
            connection.prepareStatement(
                """
                INSERT INTO venue_tables (venue_id, table_number)
                VALUES (?, ?)
                """.trimIndent(),
                Statement.RETURN_GENERATED_KEYS,
            ).use { statement ->
                statement.setLong(1, venueId)
                statement.setInt(2, tableNumber)
                statement.executeUpdate()
                statement.generatedKeys.use { rs -> if (rs.next()) return rs.getLong(1) }
            }
            error("Failed to insert table")
        }

    private fun seedTableToken(
        jdbcUrl: String,
        tableId: Long,
        token: String,
    ) {
        DriverManager.getConnection(jdbcUrl, "sa", "").use { connection ->
            connection.prepareStatement(
                """
                INSERT INTO table_tokens (table_id, token, is_active)
                VALUES (?, ?, TRUE)
                """.trimIndent(),
            ).use { statement ->
                statement.setLong(1, tableId)
                statement.setString(2, token)
                statement.executeUpdate()
            }
        }
    }

    private fun seedSubscription(
        jdbcUrl: String,
        venueId: Long,
        status: String,
    ) {
        val now = Instant.now()
        DriverManager.getConnection(jdbcUrl, "sa", "").use { connection ->
            connection.prepareStatement(
                """
                INSERT INTO venue_subscriptions (venue_id, status, trial_end, paid_start, updated_at)
                VALUES (?, ?, ?, ?, ?)
                """.trimIndent(),
            ).use { statement ->
                statement.setLong(1, venueId)
                statement.setString(2, status.uppercase())
                statement.setTimestamp(3, Timestamp.from(now.plus(14, ChronoUnit.DAYS)))
                statement.setTimestamp(4, Timestamp.from(now))
                statement.setTimestamp(5, Timestamp.from(now))
                statement.executeUpdate()
            }
        }
    }

    private fun seedMenuCategory(
        jdbcUrl: String,
        venueId: Long,
    ): Long =
        DriverManager.getConnection(jdbcUrl, "sa", "").use { connection ->
            connection.prepareStatement(
                """
                INSERT INTO menu_categories (venue_id, name, sort_order)
                VALUES (?, 'Drinks', 0)
                """.trimIndent(),
                Statement.RETURN_GENERATED_KEYS,
            ).use { statement ->
                statement.setLong(1, venueId)
                statement.executeUpdate()
                statement.generatedKeys.use { rs -> if (rs.next()) return rs.getLong(1) }
            }
            error("Failed to insert menu category")
        }

    private fun seedMenuItem(
        jdbcUrl: String,
        venueId: Long,
        categoryId: Long,
        name: String,
    ): Long =
        DriverManager.getConnection(jdbcUrl, "sa", "").use { connection ->
            connection.prepareStatement(
                """
                INSERT INTO menu_items (venue_id, category_id, name, price_minor, currency, is_available, sort_order)
                VALUES (?, ?, ?, 200, 'RUB', TRUE, 0)
                """.trimIndent(),
                Statement.RETURN_GENERATED_KEYS,
            ).use { statement ->
                statement.setLong(1, venueId)
                statement.setLong(2, categoryId)
                statement.setString(3, name)
                statement.executeUpdate()
                statement.generatedKeys.use { rs -> if (rs.next()) return rs.getLong(1) }
            }
            error("Failed to insert menu item")
        }

    private fun countAnalyticsEvents(
        jdbcUrl: String,
        eventType: String,
        venueId: Long,
    ): Int {
        DriverManager.getConnection(jdbcUrl, "sa", "").use { connection ->
            connection.prepareStatement(
                "SELECT COUNT(*) FROM analytics_events WHERE event_type = ? AND venue_id = ?",
            ).use { statement ->
                statement.setString(1, eventType)
                statement.setLong(2, venueId)
                statement.executeQuery().use { rs -> if (rs.next()) return rs.getInt(1) }
            }
        }
        return 0
    }

    private companion object {
        const val TELEGRAM_USER_ID = 4_242L
    }
}
