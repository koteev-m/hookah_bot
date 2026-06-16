package com.hookah.platform.backend.miniapp.venue.stats

import com.hookah.platform.backend.api.ApiErrorCodes
import com.hookah.platform.backend.miniapp.session.SessionTokenConfig
import com.hookah.platform.backend.miniapp.session.SessionTokenService
import com.hookah.platform.backend.module
import com.hookah.platform.backend.test.assertApiErrorEnvelope
import io.ktor.client.request.get
import io.ktor.client.request.headers
import io.ktor.client.statement.bodyAsText
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
import kotlin.test.assertTrue

class VenueStatsRoutesTest {
    private val json = Json { ignoreUnknownKeys = true }
    private val appEnv = "test"

    @Test
    fun `owner can read venue stats`() =
        testApplication {
            val jdbcUrl = buildJdbcUrl("venue-stats-owner")
            val config = buildConfig(jdbcUrl)

            environment { this.config = config }
            application { module() }

            client.get("/health")

            val venueId = seedVenueWithRole(jdbcUrl, TELEGRAM_USER_ID, "OWNER")
            seedStatsFixture(jdbcUrl, venueId)
            val token = issueToken(config)

            val response =
                client.get("/api/venue/$venueId/stats?period=30d") {
                    headers { append(HttpHeaders.Authorization, "Bearer $token") }
                }

            assertEquals(HttpStatusCode.OK, response.status)
            val payload = json.decodeFromString(VenueStatsResponse.serializer(), response.bodyAsText())
            assertEquals(venueId, payload.venueId)
            assertEquals("30d", payload.period)
            assertEquals("30 дней", payload.periodTitle)
            assertTrue(payload.periodStart.isNotBlank())
            assertEquals(1L, payload.ordersCount)
            assertEquals(270_000L, payload.revenueMinor)
            assertEquals(270_000L, payload.averageCheckMinor)
            assertEquals(20_000L, payload.discountMinor)
            assertEquals(4L, payload.cancelledItemsCount)
            assertEquals("RUB", payload.currency)
            assertEquals(listOf("Чай" to 3L, "Кальян" to 2L), payload.topItems.map { it.itemName to it.qty })
        }

    @Test
    fun `manager can read empty venue stats`() =
        testApplication {
            val jdbcUrl = buildJdbcUrl("venue-stats-manager-empty")
            val config = buildConfig(jdbcUrl)

            environment { this.config = config }
            application { module() }

            client.get("/health")

            val venueId = seedVenueWithRole(jdbcUrl, TELEGRAM_USER_ID, "MANAGER")
            val token = issueToken(config)

            val response =
                client.get("/api/venue/$venueId/stats?period=today") {
                    headers { append(HttpHeaders.Authorization, "Bearer $token") }
                }

            assertEquals(HttpStatusCode.OK, response.status)
            val payload = json.decodeFromString(VenueStatsResponse.serializer(), response.bodyAsText())
            assertEquals("today", payload.period)
            assertEquals(0L, payload.ordersCount)
            assertEquals(0L, payload.revenueMinor)
            assertEquals(0L, payload.averageCheckMinor)
            assertEquals(0L, payload.discountMinor)
            assertEquals(0L, payload.cancelledItemsCount)
            assertEquals(emptyList(), payload.topItems)
        }

    @Test
    fun `staff cannot read venue stats`() =
        testApplication {
            val jdbcUrl = buildJdbcUrl("venue-stats-staff-denied")
            val config = buildConfig(jdbcUrl)

            environment { this.config = config }
            application { module() }

            client.get("/health")

            val venueId = seedVenueWithRole(jdbcUrl, TELEGRAM_USER_ID, "STAFF")
            val token = issueToken(config)

            val response =
                client.get("/api/venue/$venueId/stats?period=today") {
                    headers { append(HttpHeaders.Authorization, "Bearer $token") }
                }

            assertEquals(HttpStatusCode.Forbidden, response.status)
            assertApiErrorEnvelope(response, ApiErrorCodes.FORBIDDEN)
        }

    @Test
    fun `invalid stats period is rejected`() =
        testApplication {
            val jdbcUrl = buildJdbcUrl("venue-stats-invalid-period")
            val config = buildConfig(jdbcUrl)

            environment { this.config = config }
            application { module() }

            client.get("/health")

            val venueId = seedVenueWithRole(jdbcUrl, TELEGRAM_USER_ID, "OWNER")
            val token = issueToken(config)

            val response =
                client.get("/api/venue/$venueId/stats?period=yesterday") {
                    headers { append(HttpHeaders.Authorization, "Bearer $token") }
                }

            assertEquals(HttpStatusCode.BadRequest, response.status)
            assertApiErrorEnvelope(response, ApiErrorCodes.INVALID_INPUT)
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

    private fun issueToken(
        config: MapApplicationConfig,
        userId: Long = TELEGRAM_USER_ID,
    ): String {
        val service = SessionTokenService(SessionTokenConfig.from(config, appEnv))
        return service.issueToken(userId).token
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
                    VALUES ('Venue', 'Москва', 'Address', 'PUBLISHED')
                    """.trimIndent(),
                    Statement.RETURN_GENERATED_KEYS,
                ).use { statement ->
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
            connection.prepareStatement(
                """
                MERGE INTO venue_settings (venue_id, timezone)
                KEY (venue_id)
                VALUES (?, 'Europe/Moscow')
                """.trimIndent(),
            ).use { statement ->
                statement.setLong(1, venueId)
                statement.executeUpdate()
            }
            return venueId
        }
    }

    private fun seedStatsFixture(
        jdbcUrl: String,
        venueId: Long,
    ) {
        val tableId = seedTable(jdbcUrl, venueId)
        val orderId = seedOrder(jdbcUrl, venueId, tableId)
        val batchId = seedBatch(jdbcUrl, orderId, Instant.now().minusSeconds(3600))
        val hookahItemId = seedMenuItem(jdbcUrl, venueId, "Кальян", 100_000L)
        val teaItemId = seedMenuItem(jdbcUrl, venueId, "Чай", 30_000L)
        val waterItemId = seedMenuItem(jdbcUrl, venueId, "Вода", 20_000L)
        seedBatchItem(jdbcUrl, batchId, hookahItemId, qty = 2, discountPercent = 10)
        seedBatchItem(jdbcUrl, batchId, teaItemId, qty = 3)
        seedBatchItem(jdbcUrl, batchId, waterItemId, qty = 4, isExcluded = true)
    }

    private fun seedTable(
        jdbcUrl: String,
        venueId: Long,
    ): Long =
        DriverManager.getConnection(jdbcUrl, "sa", "").use { connection ->
            connection.prepareStatement(
                """
                INSERT INTO venue_tables (venue_id, table_number, is_active)
                VALUES (?, 1, true)
                """.trimIndent(),
                Statement.RETURN_GENERATED_KEYS,
            ).use { statement ->
                statement.setLong(1, venueId)
                statement.executeUpdate()
                statement.generatedKeys.use { rs ->
                    if (rs.next()) rs.getLong(1) else error("Failed to insert table")
                }
            }
        }

    private fun seedOrder(
        jdbcUrl: String,
        venueId: Long,
        tableId: Long,
    ): Long =
        DriverManager.getConnection(jdbcUrl, "sa", "").use { connection ->
            val now = Instant.now()
            val tableSessionId =
                connection.prepareStatement(
                    """
                    INSERT INTO table_sessions (
                        venue_id, table_id, started_at, last_activity_at, expires_at, ended_at, status
                    )
                    VALUES (?, ?, ?, ?, ?, ?, 'ENDED')
                    """.trimIndent(),
                    Statement.RETURN_GENERATED_KEYS,
                ).use { statement ->
                    statement.setLong(1, venueId)
                    statement.setLong(2, tableId)
                    statement.setTimestamp(3, Timestamp.from(now.minusSeconds(7200)))
                    statement.setTimestamp(4, Timestamp.from(now.minusSeconds(3600)))
                    statement.setTimestamp(5, Timestamp.from(now.minusSeconds(3600)))
                    statement.setTimestamp(6, Timestamp.from(now.minusSeconds(3600)))
                    statement.executeUpdate()
                    statement.generatedKeys.use { rs ->
                        if (rs.next()) rs.getLong(1) else error("Failed to insert table session")
                    }
                }
            connection.prepareStatement(
                """
                INSERT INTO orders (venue_id, table_id, table_session_id, status)
                VALUES (?, ?, ?, 'CLOSED')
                """.trimIndent(),
                Statement.RETURN_GENERATED_KEYS,
            ).use { statement ->
                statement.setLong(1, venueId)
                statement.setLong(2, tableId)
                statement.setLong(3, tableSessionId)
                statement.executeUpdate()
                statement.generatedKeys.use { rs ->
                    if (rs.next()) rs.getLong(1) else error("Failed to insert order")
                }
            }
        }

    private fun seedBatch(
        jdbcUrl: String,
        orderId: Long,
        createdAt: Instant,
    ): Long =
        DriverManager.getConnection(jdbcUrl, "sa", "").use { connection ->
            connection.prepareStatement(
                """
                INSERT INTO order_batches (order_id, created_at, updated_at, source, status, items_snapshot)
                VALUES (?, ?, ?, 'MINIAPP', 'DELIVERED', '[]')
                """.trimIndent(),
                Statement.RETURN_GENERATED_KEYS,
            ).use { statement ->
                val timestamp = Timestamp.from(createdAt)
                statement.setLong(1, orderId)
                statement.setTimestamp(2, timestamp)
                statement.setTimestamp(3, timestamp)
                statement.executeUpdate()
                statement.generatedKeys.use { rs ->
                    if (rs.next()) rs.getLong(1) else error("Failed to insert batch")
                }
            }
        }

    private fun seedMenuItem(
        jdbcUrl: String,
        venueId: Long,
        name: String,
        priceMinor: Long,
    ): Long =
        DriverManager.getConnection(jdbcUrl, "sa", "").use { connection ->
            val categoryId =
                connection.prepareStatement(
                    """
                    INSERT INTO menu_categories (venue_id, name, sort_order, is_active)
                    VALUES (?, ?, 0, true)
                    """.trimIndent(),
                    Statement.RETURN_GENERATED_KEYS,
                ).use { statement ->
                    statement.setLong(1, venueId)
                    statement.setString(2, "Меню $name")
                    statement.executeUpdate()
                    statement.generatedKeys.use { rs ->
                        if (rs.next()) rs.getLong(1) else error("Failed to insert category")
                    }
                }
            connection.prepareStatement(
                """
                INSERT INTO menu_items (venue_id, category_id, name, price_minor, currency, is_available)
                VALUES (?, ?, ?, ?, 'RUB', true)
                """.trimIndent(),
                Statement.RETURN_GENERATED_KEYS,
            ).use { statement ->
                statement.setLong(1, venueId)
                statement.setLong(2, categoryId)
                statement.setString(3, name)
                statement.setLong(4, priceMinor)
                statement.executeUpdate()
                statement.generatedKeys.use { rs ->
                    if (rs.next()) rs.getLong(1) else error("Failed to insert menu item")
                }
            }
        }

    private fun seedBatchItem(
        jdbcUrl: String,
        batchId: Long,
        menuItemId: Long,
        qty: Int,
        discountPercent: Int? = null,
        isExcluded: Boolean = false,
    ): Long =
        DriverManager.getConnection(jdbcUrl, "sa", "").use { connection ->
            connection.prepareStatement(
                """
                INSERT INTO order_batch_items (
                    order_batch_id, menu_item_id, qty, discount_percent, is_excluded
                )
                VALUES (?, ?, ?, ?, ?)
                """.trimIndent(),
                Statement.RETURN_GENERATED_KEYS,
            ).use { statement ->
                statement.setLong(1, batchId)
                statement.setLong(2, menuItemId)
                statement.setInt(3, qty)
                if (discountPercent == null) {
                    statement.setNull(4, java.sql.Types.INTEGER)
                } else {
                    statement.setInt(4, discountPercent)
                }
                statement.setBoolean(5, isExcluded)
                statement.executeUpdate()
                statement.generatedKeys.use { rs ->
                    if (rs.next()) rs.getLong(1) else error("Failed to insert batch item")
                }
            }
        }

    @Serializable
    private data class VenueStatsResponse(
        val venueId: Long,
        val period: String,
        val periodTitle: String,
        val periodStart: String,
        val ordersCount: Long,
        val revenueMinor: Long,
        val averageCheckMinor: Long,
        val discountMinor: Long,
        val cancelledItemsCount: Long,
        val currency: String,
        val topItems: List<VenueStatsTopItemDto>,
    )

    @Serializable
    private data class VenueStatsTopItemDto(
        val itemName: String,
        val qty: Long,
    )

    companion object {
        private const val TELEGRAM_USER_ID = 100500L
    }
}
