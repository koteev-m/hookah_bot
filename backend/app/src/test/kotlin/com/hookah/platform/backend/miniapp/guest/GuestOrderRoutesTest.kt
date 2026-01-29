package com.hookah.platform.backend.miniapp.guest

import com.hookah.platform.backend.ModuleOverrides
import com.hookah.platform.backend.api.ApiErrorCodes
import com.hookah.platform.backend.miniapp.guest.api.ActiveOrderResponse
import com.hookah.platform.backend.miniapp.guest.api.AddBatchItemDto
import com.hookah.platform.backend.miniapp.guest.api.AddBatchRequest
import com.hookah.platform.backend.miniapp.guest.api.AddBatchResponse
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
import java.sql.DriverManager
import java.sql.Statement
import java.sql.Timestamp
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlinx.serialization.json.Json

class GuestOrderRoutesTest {
    private val json = Json { ignoreUnknownKeys = true }
    private val appEnv = "test"

    @Test
    fun `active returns null when no active order`() = testApplication {
        val jdbcUrl = buildJdbcUrl("guest-order-none")
        val config = buildConfig(jdbcUrl)

        environment { this.config = config }
        application { module() }

        client.get("/health")

        val venueId = seedVenue(jdbcUrl, VenueStatus.PUBLISHED.dbValue)
        val tableId = seedTable(jdbcUrl, venueId, 4)
        seedTableToken(jdbcUrl, tableId, "active-token")
        seedSubscription(jdbcUrl, venueId, "ACTIVE")

        val token = issueToken(config)
        val response = client.get("/api/guest/order/active?tableToken=active-token") {
            headers { append(HttpHeaders.Authorization, "Bearer $token") }
        }

        assertEquals(HttpStatusCode.OK, response.status)
        val payload = json.decodeFromString(ActiveOrderResponse.serializer(), response.bodyAsText())
        assertNull(payload.order)
    }

    @Test
    fun `add-batch twice keeps single order`() = testApplication {
        val jdbcUrl = buildJdbcUrl("guest-order-add")
        val config = buildConfig(jdbcUrl)

        environment { this.config = config }
        application { module() }

        client.get("/health")

        val venueId = seedVenue(jdbcUrl, VenueStatus.PUBLISHED.dbValue)
        val tableId = seedTable(jdbcUrl, venueId, 8)
        seedTableToken(jdbcUrl, tableId, "batch-token")
        seedSubscription(jdbcUrl, venueId, "ACTIVE")
        val categoryId = seedMenuCategory(jdbcUrl, venueId)
        val itemId = seedMenuItem(jdbcUrl, venueId, categoryId, "Tea")

        val token = issueToken(config)
        val request = AddBatchRequest(
            tableToken = "batch-token",
            items = listOf(AddBatchItemDto(itemId = itemId, qty = 2)),
            comment = "First batch"
        )
        val firstResponse = client.post("/api/guest/order/add-batch") {
            contentType(ContentType.Application.Json)
            headers { append(HttpHeaders.Authorization, "Bearer $token") }
            setBody(json.encodeToString(AddBatchRequest.serializer(), request))
        }
        val firstPayload = json.decodeFromString(AddBatchResponse.serializer(), firstResponse.bodyAsText())

        val secondResponse = client.post("/api/guest/order/add-batch") {
            contentType(ContentType.Application.Json)
            headers { append(HttpHeaders.Authorization, "Bearer $token") }
            setBody(json.encodeToString(AddBatchRequest.serializer(), request.copy(comment = "Second batch")))
        }
        val secondPayload = json.decodeFromString(AddBatchResponse.serializer(), secondResponse.bodyAsText())

        assertEquals(HttpStatusCode.OK, firstResponse.status)
        assertEquals(HttpStatusCode.OK, secondResponse.status)
        assertEquals(firstPayload.orderId, secondPayload.orderId)
        assertTrue(firstPayload.batchId != secondPayload.batchId)

        val activeResponse = client.get("/api/guest/order/active?tableToken=batch-token") {
            headers { append(HttpHeaders.Authorization, "Bearer $token") }
        }
        assertEquals(HttpStatusCode.OK, activeResponse.status)
        val activePayload = json.decodeFromString(ActiveOrderResponse.serializer(), activeResponse.bodyAsText())
        val order = activePayload.order
        assertNotNull(order)
        assertEquals(2, order.batches.size)
        assertEquals(itemId, order.batches.first().items.first().itemId)
    }

    @Test
    fun `add-batch stores miniapp source`() = testApplication {
        val jdbcUrl = buildJdbcUrl("guest-order-source")
        val config = buildConfig(jdbcUrl)

        environment { this.config = config }
        application { module() }

        client.get("/health")

        val venueId = seedVenue(jdbcUrl, VenueStatus.PUBLISHED.dbValue)
        val tableId = seedTable(jdbcUrl, venueId, 7)
        seedTableToken(jdbcUrl, tableId, "source-token")
        seedSubscription(jdbcUrl, venueId, "ACTIVE")
        val categoryId = seedMenuCategory(jdbcUrl, venueId)
        val itemId = seedMenuItem(jdbcUrl, venueId, categoryId, "Latte")

        val token = issueToken(config)
        val request = AddBatchRequest(
            tableToken = "source-token",
            items = listOf(AddBatchItemDto(itemId = itemId, qty = 1)),
            comment = "Source check"
        )
        val response = client.post("/api/guest/order/add-batch") {
            contentType(ContentType.Application.Json)
            headers { append(HttpHeaders.Authorization, "Bearer $token") }
            setBody(json.encodeToString(AddBatchRequest.serializer(), request))
        }

        assertEquals(HttpStatusCode.OK, response.status)
        val payload = json.decodeFromString(AddBatchResponse.serializer(), response.bodyAsText())
        val source = fetchOrderBatchSource(jdbcUrl, payload.batchId)
        assertEquals("MINIAPP", source)
    }

    @Test
    fun `add-batch accepts missing comment`() = testApplication {
        val jdbcUrl = buildJdbcUrl("guest-order-missing-comment")
        val config = buildConfig(jdbcUrl)

        environment { this.config = config }
        application { module() }

        client.get("/health")

        val venueId = seedVenue(jdbcUrl, VenueStatus.PUBLISHED.dbValue)
        val tableId = seedTable(jdbcUrl, venueId, 5)
        seedTableToken(jdbcUrl, tableId, "missing-comment-token")
        seedSubscription(jdbcUrl, venueId, "ACTIVE")
        val categoryId = seedMenuCategory(jdbcUrl, venueId)
        val itemId = seedMenuItem(jdbcUrl, venueId, categoryId, "Matcha")

        val token = issueToken(config)
        val requestBody = """
            {
              "tableToken": "missing-comment-token",
              "items": [
                {
                  "itemId": $itemId,
                  "qty": 1
                }
              ]
            }
        """.trimIndent()
        val response = client.post("/api/guest/order/add-batch") {
            contentType(ContentType.Application.Json)
            headers { append(HttpHeaders.Authorization, "Bearer $token") }
            setBody(requestBody)
        }

        assertEquals(HttpStatusCode.OK, response.status)
    }

    @Test
    fun `add-batch for paused venue returns not found`() = testApplication {
        val jdbcUrl = buildJdbcUrl("guest-order-paused")
        val config = buildConfig(jdbcUrl)

        environment { this.config = config }
        application { module() }

        client.get("/health")

        val venueId = seedVenue(jdbcUrl, VenueStatus.PAUSED.dbValue)
        val tableId = seedTable(jdbcUrl, venueId, 9)
        seedTableToken(jdbcUrl, tableId, "paused-token")
        seedSubscription(jdbcUrl, venueId, "ACTIVE")
        val categoryId = seedMenuCategory(jdbcUrl, venueId)
        val itemId = seedMenuItem(jdbcUrl, venueId, categoryId, "Herbal Tea")

        val token = issueToken(config)
        val request = AddBatchRequest(
            tableToken = "paused-token",
            items = listOf(AddBatchItemDto(itemId = itemId, qty = 1)),
            comment = null
        )
        val response = client.post("/api/guest/order/add-batch") {
            contentType(ContentType.Application.Json)
            headers { append(HttpHeaders.Authorization, "Bearer $token") }
            setBody(json.encodeToString(AddBatchRequest.serializer(), request))
        }

        assertEquals(HttpStatusCode.NotFound, response.status)
        assertApiErrorEnvelope(response, ApiErrorCodes.NOT_FOUND)
    }

    @Test
    fun `blocked subscription rejects add-batch`() = testApplication {
        val jdbcUrl = buildJdbcUrl("guest-order-blocked")
        val config = buildConfig(jdbcUrl)

        environment { this.config = config }
        application { module() }

        client.get("/health")

        val venueId = seedVenue(jdbcUrl, VenueStatus.PUBLISHED.dbValue)
        val tableId = seedTable(jdbcUrl, venueId, 2)
        seedTableToken(jdbcUrl, tableId, "blocked-token")
        seedSubscription(jdbcUrl, venueId, "PAST_DUE")
        val categoryId = seedMenuCategory(jdbcUrl, venueId)
        val itemId = seedMenuItem(jdbcUrl, venueId, categoryId, "Coffee")

        val token = issueToken(config)
        val request = AddBatchRequest(
            tableToken = "blocked-token",
            items = listOf(AddBatchItemDto(itemId = itemId, qty = 1)),
            comment = null
        )
        val response = client.post("/api/guest/order/add-batch") {
            contentType(ContentType.Application.Json)
            headers { append(HttpHeaders.Authorization, "Bearer $token") }
            setBody(json.encodeToString(AddBatchRequest.serializer(), request))
        }

        assertEquals(HttpStatusCode.Locked, response.status)
        assertApiErrorEnvelope(response, ApiErrorCodes.SUBSCRIPTION_BLOCKED)
    }

    @Test
    fun `invalid table token rejects add-batch without resolver`() = testApplication {
        var resolveCalls = 0
        val config = MapApplicationConfig(
            "app.env" to appEnv,
            "api.session.jwtSecret" to "test-secret",
            "db.jdbcUrl" to ""
        )

        environment { this.config = config }
        application {
            module(
                ModuleOverrides(
                    tableTokenResolver = {
                        resolveCalls += 1
                        fail("tableTokenResolver must not be called for invalid tokens")
                    }
                )
            )
        }

        val token = issueToken(config)
        val request = AddBatchRequest(
            tableToken = "bad token",
            items = listOf(AddBatchItemDto(itemId = 1, qty = 1)),
            comment = null
        )
        val response = client.post("/api/guest/order/add-batch") {
            contentType(ContentType.Application.Json)
            headers { append(HttpHeaders.Authorization, "Bearer $token") }
            setBody(json.encodeToString(AddBatchRequest.serializer(), request))
        }

        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertApiErrorEnvelope(response, ApiErrorCodes.INVALID_INPUT)
        assertEquals(0, resolveCalls)
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
            "db.password" to ""
        )
    }

    private fun issueToken(config: MapApplicationConfig): String {
        val service = SessionTokenService(SessionTokenConfig.from(config, appEnv))
        return service.issueToken(TELEGRAM_USER_ID).token
    }

    private fun seedVenue(jdbcUrl: String, status: String): Long {
        DriverManager.getConnection(jdbcUrl, "sa", "").use { connection ->
            connection.prepareStatement(
                """
                    INSERT INTO venues (name, city, address, status)
                    VALUES (?, ?, ?, ?)
                """.trimIndent(),
                Statement.RETURN_GENERATED_KEYS
            ).use { statement ->
                statement.setString(1, "Venue")
                statement.setString(2, "City")
                statement.setString(3, "Address")
                statement.setString(4, status)
                statement.executeUpdate()
                statement.generatedKeys.use { rs ->
                    if (rs.next()) {
                        return rs.getLong(1)
                    }
                }
            }
        }
        error("Failed to insert venue")
    }

    private fun seedTable(jdbcUrl: String, venueId: Long, tableNumber: Int): Long {
        DriverManager.getConnection(jdbcUrl, "sa", "").use { connection ->
            connection.prepareStatement(
                """
                    INSERT INTO venue_tables (venue_id, table_number)
                    VALUES (?, ?)
                """.trimIndent(),
                Statement.RETURN_GENERATED_KEYS
            ).use { statement ->
                statement.setLong(1, venueId)
                statement.setInt(2, tableNumber)
                statement.executeUpdate()
                statement.generatedKeys.use { rs ->
                    if (rs.next()) {
                        return rs.getLong(1)
                    }
                }
            }
        }
        error("Failed to insert table")
    }

    private fun seedTableToken(jdbcUrl: String, tableId: Long, token: String) {
        DriverManager.getConnection(jdbcUrl, "sa", "").use { connection ->
            connection.prepareStatement(
                """
                    INSERT INTO table_tokens (token, table_id, is_active)
                    VALUES (?, ?, true)
                """.trimIndent()
            ).use { statement ->
                statement.setString(1, token)
                statement.setLong(2, tableId)
                statement.executeUpdate()
            }
        }
    }

    private fun seedSubscription(jdbcUrl: String, venueId: Long, status: String) {
        val now = Instant.now()
        val trialEnd = now.plus(14, ChronoUnit.DAYS)
        DriverManager.getConnection(jdbcUrl, "sa", "").use { connection ->
            connection.prepareStatement(
                """
                    INSERT INTO venue_subscriptions (venue_id, status, trial_end, paid_start, updated_at)
                    VALUES (?, ?, ?, ?, ?)
                """.trimIndent()
            ).use { statement ->
                statement.setLong(1, venueId)
                statement.setString(2, status.uppercase())
                statement.setTimestamp(3, Timestamp.from(trialEnd))
                statement.setTimestamp(4, Timestamp.from(now))
                statement.setTimestamp(5, Timestamp.from(now))
                statement.executeUpdate()
            }
        }
    }

    private fun seedMenuCategory(jdbcUrl: String, venueId: Long): Long {
        DriverManager.getConnection(jdbcUrl, "sa", "").use { connection ->
            connection.prepareStatement(
                """
                    INSERT INTO menu_categories (venue_id, name, sort_order)
                    VALUES (?, ?, 0)
                """.trimIndent(),
                Statement.RETURN_GENERATED_KEYS
            ).use { statement ->
                statement.setLong(1, venueId)
                statement.setString(2, "Category")
                statement.executeUpdate()
                statement.generatedKeys.use { rs ->
                    if (rs.next()) {
                        return rs.getLong(1)
                    }
                }
            }
        }
        error("Failed to insert category")
    }

    private fun seedMenuItem(jdbcUrl: String, venueId: Long, categoryId: Long, name: String): Long {
        DriverManager.getConnection(jdbcUrl, "sa", "").use { connection ->
            connection.prepareStatement(
                """
                    INSERT INTO menu_items (venue_id, category_id, name, price_minor, currency, is_available)
                    VALUES (?, ?, ?, ?, 'RUB', true)
                """.trimIndent(),
                Statement.RETURN_GENERATED_KEYS
            ).use { statement ->
                statement.setLong(1, venueId)
                statement.setLong(2, categoryId)
                statement.setString(3, name)
                statement.setLong(4, 100)
                statement.executeUpdate()
                statement.generatedKeys.use { rs ->
                    if (rs.next()) {
                        return rs.getLong(1)
                    }
                }
            }
        }
        error("Failed to insert item")
    }

    private fun fetchOrderBatchSource(jdbcUrl: String, batchId: Long): String? {
        DriverManager.getConnection(jdbcUrl, "sa", "").use { connection ->
            connection.prepareStatement(
                """
                    SELECT source
                    FROM order_batches
                    WHERE id = ?
                """.trimIndent()
            ).use { statement ->
                statement.setLong(1, batchId)
                statement.executeQuery().use { rs ->
                    if (rs.next()) {
                        return rs.getString("source")
                    }
                }
            }
        }
        return null
    }

    private companion object {
        const val TELEGRAM_USER_ID: Long = 456L
    }
}
