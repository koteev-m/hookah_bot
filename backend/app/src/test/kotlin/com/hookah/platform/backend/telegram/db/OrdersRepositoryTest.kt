package com.hookah.platform.backend.telegram.db

import kotlinx.coroutines.runBlocking
import org.flywaydb.core.Flyway
import org.h2.jdbcx.JdbcDataSource
import java.sql.DriverManager
import java.sql.Statement
import java.sql.Timestamp
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class OrdersRepositoryTest {
    @Test
    fun `new active order display date uses venue timezone`() =
        runBlocking {
            val jdbcUrl = migratedJdbcUrl("order-display-zone")
            val fixture = seedVenueTableSession(jdbcUrl)
            val repository = OrdersRepository(dataSource(jdbcUrl))
            val venueZone = ZoneId.of("Pacific/Honolulu")

            val orderId =
                repository.getOrCreateActiveOrderId(
                    tableId = fixture.tableId,
                    venueId = fixture.venueId,
                    tableSessionId = fixture.tableSessionId,
                    venueZoneId = venueZone,
                )

            assertNotNull(orderId)
            val display = fetchOrderDisplay(jdbcUrl, orderId)
            assertEquals(LocalDate.now(venueZone), display.displayDate)
            assertEquals(1, display.displayNumber)
        }

    private fun migratedJdbcUrl(prefix: String): String {
        val jdbcUrl =
            "jdbc:h2:mem:$prefix-${UUID.randomUUID()};MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;" +
                "DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1"
        Flyway.configure()
            .dataSource(jdbcUrl, "sa", "")
            .locations("classpath:db/migration/h2")
            .load()
            .migrate()
        return jdbcUrl
    }

    private fun dataSource(jdbcUrl: String): JdbcDataSource =
        JdbcDataSource().apply {
            setURL(jdbcUrl)
            user = "sa"
            password = ""
        }

    private fun seedVenueTableSession(jdbcUrl: String): OrderFixture {
        DriverManager.getConnection(jdbcUrl, "sa", "").use { connection ->
            val venueId =
                connection.prepareStatement(
                    """
                    INSERT INTO venues (name, city, address, status)
                    VALUES ('Venue', 'City', 'Address', 'PUBLISHED')
                    """.trimIndent(),
                    Statement.RETURN_GENERATED_KEYS,
                ).use { statement ->
                    statement.executeUpdate()
                    statement.generatedKeys.use { rs ->
                        if (rs.next()) rs.getLong(1) else error("Failed to insert venue")
                    }
                }
            val tableId =
                connection.prepareStatement(
                    """
                    INSERT INTO venue_tables (venue_id, table_number, is_active)
                    VALUES (?, 10, true)
                    """.trimIndent(),
                    Statement.RETURN_GENERATED_KEYS,
                ).use { statement ->
                    statement.setLong(1, venueId)
                    statement.executeUpdate()
                    statement.generatedKeys.use { rs ->
                        if (rs.next()) rs.getLong(1) else error("Failed to insert table")
                    }
                }
            val tableSessionId =
                connection.prepareStatement(
                    """
                    INSERT INTO table_sessions (venue_id, table_id, started_at, last_activity_at, expires_at, status)
                    VALUES (?, ?, ?, ?, ?, 'ACTIVE')
                    """.trimIndent(),
                    Statement.RETURN_GENERATED_KEYS,
                ).use { statement ->
                    val now = Instant.now()
                    statement.setLong(1, venueId)
                    statement.setLong(2, tableId)
                    statement.setTimestamp(3, Timestamp.from(now))
                    statement.setTimestamp(4, Timestamp.from(now))
                    statement.setTimestamp(5, Timestamp.from(now.plusSeconds(7200)))
                    statement.executeUpdate()
                    statement.generatedKeys.use { rs ->
                        if (rs.next()) rs.getLong(1) else error("Failed to insert table session")
                    }
                }
            return OrderFixture(venueId = venueId, tableId = tableId, tableSessionId = tableSessionId)
        }
    }

    private fun fetchOrderDisplay(
        jdbcUrl: String,
        orderId: Long,
    ): OrderDisplayFixture =
        DriverManager.getConnection(jdbcUrl, "sa", "").use { connection ->
            connection.prepareStatement(
                """
                SELECT display_number, display_date
                FROM orders
                WHERE id = ?
                """.trimIndent(),
            ).use { statement ->
                statement.setLong(1, orderId)
                statement.executeQuery().use { rs ->
                    if (!rs.next()) error("Order not found")
                    OrderDisplayFixture(
                        displayNumber = rs.getInt("display_number"),
                        displayDate = rs.getDate("display_date").toLocalDate(),
                    )
                }
            }
        }

    private data class OrderFixture(
        val venueId: Long,
        val tableId: Long,
        val tableSessionId: Long,
    )

    private data class OrderDisplayFixture(
        val displayNumber: Int,
        val displayDate: LocalDate,
    )
}
