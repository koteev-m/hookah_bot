package com.hookah.platform.backend.miniapp.venue

import com.hookah.platform.backend.api.ApiErrorCodes
import com.hookah.platform.backend.miniapp.session.SessionTokenConfig
import com.hookah.platform.backend.miniapp.session.SessionTokenService
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
import io.ktor.server.config.MapApplicationConfig
import io.ktor.server.testing.testApplication
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.sql.DriverManager
import java.sql.Statement
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class VenueOrderRoutesTest {
    private val json = Json { ignoreUnknownKeys = true }
    private val appEnv = "test"

    @Test
    fun `manager can update status and audit is recorded`() =
        testApplication {
            val jdbcUrl = buildJdbcUrl("orders-status")
            val config = buildConfig(jdbcUrl)

            environment { this.config = config }
            application { module() }

            client.get("/health")

            val venueId = seedVenueWithRole(jdbcUrl, TELEGRAM_USER_ID, "MANAGER")
            val tableId = seedTable(jdbcUrl, venueId, 10)
            seedMenu(jdbcUrl, venueId)
            val orderId = seedOrder(jdbcUrl, venueId, tableId, "ACTIVE")
            val batchId = seedBatch(jdbcUrl, orderId, "NEW", Instant.now().minusSeconds(120))
            seedBatchItem(jdbcUrl, batchId)
            val token = issueToken(config)

            val response =
                client.post("/api/venue/orders/$orderId/status?venueId=$venueId") {
                    headers {
                        append(HttpHeaders.Authorization, "Bearer $token")
                        append(HttpHeaders.ContentType, ContentType.Application.Json.toString())
                    }
                    setBody("""{"nextStatus":"accepted"}""")
                }

            assertEquals(HttpStatusCode.OK, response.status)

            val auditResponse =
                client.get("/api/venue/orders/$orderId/audit?venueId=$venueId") {
                    headers { append(HttpHeaders.Authorization, "Bearer $token") }
                }

            assertEquals(HttpStatusCode.OK, auditResponse.status)
            val audit = json.decodeFromString(OrderAuditResponse.serializer(), auditResponse.bodyAsText())
            assertTrue(audit.items.isNotEmpty())
            val entry = audit.items.first()
            assertEquals("STATUS_CHANGE", entry.action)
            assertEquals("new", entry.fromStatus)
            assertEquals("accepted", entry.toStatus)
            assertEquals(1, countAnalyticsEvents(jdbcUrl, "batch_status_changed", venueId))
        }

    @Test
    fun `invalid status transition returns error`() =
        testApplication {
            val jdbcUrl = buildJdbcUrl("orders-invalid")
            val config = buildConfig(jdbcUrl)

            environment { this.config = config }
            application { module() }

            client.get("/health")

            val venueId = seedVenueWithRole(jdbcUrl, TELEGRAM_USER_ID, "MANAGER")
            val tableId = seedTable(jdbcUrl, venueId, 1)
            seedMenu(jdbcUrl, venueId)
            val orderId = seedOrder(jdbcUrl, venueId, tableId, "ACTIVE")
            val batchId = seedBatch(jdbcUrl, orderId, "NEW", Instant.now())
            seedBatchItem(jdbcUrl, batchId)
            val token = issueToken(config)

            val response =
                client.post("/api/venue/orders/$orderId/status?venueId=$venueId") {
                    headers {
                        append(HttpHeaders.Authorization, "Bearer $token")
                        append(HttpHeaders.ContentType, ContentType.Application.Json.toString())
                    }
                    setBody("""{"nextStatus":"delivering"}""")
                }

            assertEquals(HttpStatusCode.BadRequest, response.status)
            assertApiErrorEnvelope(response, ApiErrorCodes.INVALID_INPUT)
        }

    @Test
    fun `staff cannot reject orders`() =
        testApplication {
            val jdbcUrl = buildJdbcUrl("orders-reject")
            val config = buildConfig(jdbcUrl)

            environment { this.config = config }
            application { module() }

            client.get("/health")

            val venueId = seedVenueWithRole(jdbcUrl, TELEGRAM_USER_ID, "STAFF")
            val tableId = seedTable(jdbcUrl, venueId, 2)
            seedMenu(jdbcUrl, venueId)
            val orderId = seedOrder(jdbcUrl, venueId, tableId, "ACTIVE")
            val batchId = seedBatch(jdbcUrl, orderId, "NEW", Instant.now())
            seedBatchItem(jdbcUrl, batchId)
            val token = issueToken(config)

            val response =
                client.post("/api/venue/orders/$orderId/reject?venueId=$venueId") {
                    headers {
                        append(HttpHeaders.Authorization, "Bearer $token")
                        append(HttpHeaders.ContentType, ContentType.Application.Json.toString())
                    }
                    setBody("""{"reasonCode":"guest_request"}""")
                }

            assertEquals(HttpStatusCode.Forbidden, response.status)
            assertApiErrorEnvelope(response, ApiErrorCodes.FORBIDDEN)
        }

    @Test
    fun `queue pagination returns newest first`() =
        testApplication {
            val jdbcUrl = buildJdbcUrl("orders-queue")
            val config = buildConfig(jdbcUrl)

            environment { this.config = config }
            application { module() }

            client.get("/health")

            val venueId = seedVenueWithRole(jdbcUrl, TELEGRAM_USER_ID, "MANAGER")
            val tableId = seedTable(jdbcUrl, venueId, 5)
            seedMenu(jdbcUrl, venueId)
            val orderId = seedOrder(jdbcUrl, venueId, tableId, "ACTIVE")
            val olderBatchId = seedBatch(jdbcUrl, orderId, "NEW", Instant.now().minusSeconds(300))
            val newerBatchId = seedBatch(jdbcUrl, orderId, "NEW", Instant.now().minusSeconds(10))
            seedBatchItem(jdbcUrl, olderBatchId)
            seedBatchItem(jdbcUrl, newerBatchId)
            val token = issueToken(config)

            val firstPage =
                client.get("/api/venue/orders/queue?venueId=$venueId&status=new&limit=1") {
                    headers { append(HttpHeaders.Authorization, "Bearer $token") }
                }

            assertEquals(HttpStatusCode.OK, firstPage.status)
            val payload = json.decodeFromString(OrdersQueueResponse.serializer(), firstPage.bodyAsText())
            assertEquals(1, payload.items.size)
            assertEquals(newerBatchId, payload.items.first().batchId)
            val cursor = payload.nextCursor
            assertNotNull(cursor)

            val secondPage =
                client.get(
                    "/api/venue/orders/queue?venueId=$venueId&status=new&limit=1&cursor=$cursor",
                ) {
                    headers { append(HttpHeaders.Authorization, "Bearer $token") }
                }

            assertEquals(HttpStatusCode.OK, secondPage.status)
            val payload2 = json.decodeFromString(OrdersQueueResponse.serializer(), secondPage.bodyAsText())
            assertEquals(1, payload2.items.size)
            assertEquals(olderBatchId, payload2.items.first().batchId)
        }

    @Test
    fun `order detail returns batches and items`() =
        testApplication {
            val jdbcUrl = buildJdbcUrl("orders-detail")
            val config = buildConfig(jdbcUrl)

            environment { this.config = config }
            application { module() }

            client.get("/health")

            val venueId = seedVenueWithRole(jdbcUrl, TELEGRAM_USER_ID, "MANAGER")
            val tableId = seedTable(jdbcUrl, venueId, 3)
            seedMenu(jdbcUrl, venueId)
            val orderId = seedOrder(jdbcUrl, venueId, tableId, "ACTIVE")
            val batchId = seedBatch(jdbcUrl, orderId, "NEW", Instant.now())
            seedBatchItem(jdbcUrl, batchId)
            val token = issueToken(config)

            val response =
                client.get("/api/venue/orders/$orderId?venueId=$venueId") {
                    headers { append(HttpHeaders.Authorization, "Bearer $token") }
                }

            assertEquals(HttpStatusCode.OK, response.status)
            val payload = json.decodeFromString(OrderDetailResponse.serializer(), response.bodyAsText())
            assertEquals("new", payload.order.status)
            assertTrue(payload.order.batches.isNotEmpty())
            val firstBatch = payload.order.batches.first()
            assertEquals(batchId, firstBatch.batchId)
            assertTrue(firstBatch.items.isNotEmpty())
        }

    @Test
    fun `order audit returns empty list when no entries`() =
        testApplication {
            val jdbcUrl = buildJdbcUrl("orders-audit-empty")
            val config = buildConfig(jdbcUrl)

            environment { this.config = config }
            application { module() }

            client.get("/health")

            val venueId = seedVenueWithRole(jdbcUrl, TELEGRAM_USER_ID, "MANAGER")
            val tableId = seedTable(jdbcUrl, venueId, 6)
            seedMenu(jdbcUrl, venueId)
            val orderId = seedOrder(jdbcUrl, venueId, tableId, "ACTIVE")
            val batchId = seedBatch(jdbcUrl, orderId, "NEW", Instant.now())
            seedBatchItem(jdbcUrl, batchId)
            val token = issueToken(config)

            val response =
                client.get("/api/venue/orders/$orderId/audit?venueId=$venueId") {
                    headers { append(HttpHeaders.Authorization, "Bearer $token") }
                }

            assertEquals(HttpStatusCode.OK, response.status)
            val payload = json.decodeFromString(OrderAuditResponse.serializer(), response.bodyAsText())
            assertTrue(payload.items.isEmpty())
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
                statement.executeQuery().use { rs ->
                    if (rs.next()) {
                        return rs.getInt(1)
                    }
                }
            }
        }
        return 0
    }

    private fun buildJdbcUrl(prefix: String): String {
        val dbName = "$prefix-${UUID.randomUUID()}"
        return "jdbc:h2:mem:$dbName;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1"
    }

    private fun buildConfig(jdbcUrl: String): MapApplicationConfig {
        return MapApplicationConfig(
            "app.env" to appEnv,
            "api.session.jwtSecret" to "test-secret",
            "db.jdbcUrl" to jdbcUrl,
            "db.user" to "sa",
            "db.password" to "",
        )
    }

    private fun issueToken(config: MapApplicationConfig): String {
        val service = SessionTokenService(SessionTokenConfig.from(config, appEnv))
        return service.issueToken(TELEGRAM_USER_ID).token
    }

    private fun seedVenueWithRole(
        jdbcUrl: String,
        userId: Long,
        role: String,
    ): Long {
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
            val venueId =
                connection.prepareStatement(
                    """
                    INSERT INTO venues (name, city, address, status)
                    VALUES ('Venue', 'City', 'Address', ?)
                    """.trimIndent(),
                    Statement.RETURN_GENERATED_KEYS,
                ).use { statement ->
                    statement.setString(1, VenueStatus.PUBLISHED.dbValue)
                    statement.executeUpdate()
                    statement.generatedKeys.use { rs ->
                        if (rs.next()) rs.getLong(1) else error("Failed to insert venue")
                    }
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
            return venueId
        }
    }

    private fun seedTable(
        jdbcUrl: String,
        venueId: Long,
        tableNumber: Int,
    ): Long {
        DriverManager.getConnection(jdbcUrl, "sa", "").use { connection ->
            return connection.prepareStatement(
                """
                INSERT INTO venue_tables (venue_id, table_number, is_active)
                VALUES (?, ?, true)
                """.trimIndent(),
                Statement.RETURN_GENERATED_KEYS,
            ).use { statement ->
                statement.setLong(1, venueId)
                statement.setInt(2, tableNumber)
                statement.executeUpdate()
                statement.generatedKeys.use { rs ->
                    if (rs.next()) rs.getLong(1) else error("Failed to insert table")
                }
            }
        }
    }

    private fun seedMenu(
        jdbcUrl: String,
        venueId: Long,
    ) {
        DriverManager.getConnection(jdbcUrl, "sa", "").use { connection ->
            val categoryId =
                connection.prepareStatement(
                    """
                    INSERT INTO menu_categories (venue_id, name, sort_order)
                    VALUES (?, 'Category', 0)
                    """.trimIndent(),
                    Statement.RETURN_GENERATED_KEYS,
                ).use { statement ->
                    statement.setLong(1, venueId)
                    statement.executeUpdate()
                    statement.generatedKeys.use { rs ->
                        if (rs.next()) rs.getLong(1) else error("Failed to insert category")
                    }
                }
            connection.prepareStatement(
                """
                INSERT INTO menu_items (venue_id, category_id, name, price_minor, currency, is_available)
                VALUES (?, ?, 'Item', 100, 'RUB', true)
                """.trimIndent(),
                Statement.RETURN_GENERATED_KEYS,
            ).use { statement ->
                statement.setLong(1, venueId)
                statement.setLong(2, categoryId)
                statement.executeUpdate()
            }
        }
    }

    private fun seedOrder(
        jdbcUrl: String,
        venueId: Long,
        tableId: Long,
        status: String,
    ): Long {
        DriverManager.getConnection(jdbcUrl, "sa", "").use { connection ->
            return connection.prepareStatement(
                """
                INSERT INTO orders (venue_id, table_id, status)
                VALUES (?, ?, ?)
                """.trimIndent(),
                Statement.RETURN_GENERATED_KEYS,
            ).use { statement ->
                statement.setLong(1, venueId)
                statement.setLong(2, tableId)
                statement.setString(3, status)
                statement.executeUpdate()
                statement.generatedKeys.use { rs ->
                    if (rs.next()) rs.getLong(1) else error("Failed to insert order")
                }
            }
        }
    }

    private fun seedBatch(
        jdbcUrl: String,
        orderId: Long,
        status: String,
        createdAt: Instant,
    ): Long {
        DriverManager.getConnection(jdbcUrl, "sa", "").use { connection ->
            return connection.prepareStatement(
                """
                INSERT INTO order_batches (
                    order_id, created_at, updated_at, author_user_id, source, status, guest_comment
                ) VALUES (?, ?, ?, NULL, 'MINIAPP', ?, NULL)
                """.trimIndent(),
                Statement.RETURN_GENERATED_KEYS,
            ).use { statement ->
                val ts = Timestamp.from(createdAt)
                statement.setLong(1, orderId)
                statement.setTimestamp(2, ts)
                statement.setTimestamp(3, ts)
                statement.setString(4, status)
                statement.executeUpdate()
                statement.generatedKeys.use { rs ->
                    if (rs.next()) rs.getLong(1) else error("Failed to insert batch")
                }
            }
        }
    }

    private fun seedBatchItem(
        jdbcUrl: String,
        batchId: Long,
    ) {
        DriverManager.getConnection(jdbcUrl, "sa", "").use { connection ->
            val menuItemId =
                connection.prepareStatement(
                    "SELECT id FROM menu_items LIMIT 1",
                ).use { statement ->
                    statement.executeQuery().use { rs ->
                        if (rs.next()) rs.getLong(1) else error("Missing menu item")
                    }
                }
            connection.prepareStatement(
                """
                INSERT INTO order_batch_items (order_batch_id, menu_item_id, qty)
                VALUES (?, ?, 1)
                """.trimIndent(),
            ).use { statement ->
                statement.setLong(1, batchId)
                statement.setLong(2, menuItemId)
                statement.executeUpdate()
            }
        }
    }

    @Serializable
    private data class OrdersQueueResponse(
        val items: List<OrderQueueItemDto>,
        val nextCursor: String?,
    )

    @Serializable
    private data class OrderQueueItemDto(
        val batchId: Long,
    )

    @Serializable
    private data class OrderDetailResponse(
        val order: OrderDetailDto,
    )

    @Serializable
    private data class OrderDetailDto(
        val status: String,
        val batches: List<OrderBatchDto>,
    )

    @Serializable
    private data class OrderBatchDto(
        val batchId: Long,
        val items: List<OrderBatchItemDto>,
    )

    @Serializable
    private data class OrderBatchItemDto(
        val itemId: Long,
    )

    @Serializable
    private data class OrderAuditResponse(
        val items: List<OrderAuditEntryDto>,
    )

    @Serializable
    private data class OrderAuditEntryDto(
        val action: String,
        val fromStatus: String,
        val toStatus: String,
    )

    private companion object {
        const val TELEGRAM_USER_ID: Long = 909L
    }
}
