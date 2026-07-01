package com.hookah.platform.backend.telegram.db

import kotlinx.coroutines.runBlocking
import org.flywaydb.core.Flyway
import org.h2.jdbcx.JdbcDataSource
import java.sql.Date
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
import kotlin.test.assertNull

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

    @Test
    fun `new active order display number increments within venue display date`() =
        runBlocking {
            val jdbcUrl = migratedJdbcUrl("order-display-increment")
            val fixture = seedVenueTableSession(jdbcUrl)
            val secondFixture = seedAdditionalTableSession(jdbcUrl, fixture.venueId, tableNumber = 11)
            val repository = OrdersRepository(dataSource(jdbcUrl))
            val venueZone = ZoneId.of("Europe/Moscow")

            val firstOrderId =
                repository.getOrCreateActiveOrderId(
                    tableId = fixture.tableId,
                    venueId = fixture.venueId,
                    tableSessionId = fixture.tableSessionId,
                    venueZoneId = venueZone,
                )
            val secondOrderId =
                repository.getOrCreateActiveOrderId(
                    tableId = secondFixture.tableId,
                    venueId = secondFixture.venueId,
                    tableSessionId = secondFixture.tableSessionId,
                    venueZoneId = venueZone,
                )

            assertNotNull(firstOrderId)
            assertNotNull(secondOrderId)
            assertEquals(1, fetchOrderDisplay(jdbcUrl, firstOrderId).displayNumber)
            assertEquals(2, fetchOrderDisplay(jdbcUrl, secondOrderId).displayNumber)
            assertEquals(LocalDate.now(venueZone), fetchOrderDisplay(jdbcUrl, secondOrderId).displayDate)
        }

    @Test
    fun `new active order display number ignores previous venue display date`() =
        runBlocking {
            val jdbcUrl = migratedJdbcUrl("order-display-date-reset")
            val fixture = seedVenueTableSession(jdbcUrl)
            val repository = OrdersRepository(dataSource(jdbcUrl))
            val venueZone = ZoneId.of("Europe/Moscow")
            seedClosedOrderDisplay(
                jdbcUrl = jdbcUrl,
                fixture = fixture,
                displayDate = LocalDate.now(venueZone).minusDays(1),
                displayNumber = 9,
            )

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

    @Test
    fun `safe staff call resolver returns active order for authored batch participant`() =
        runBlocking {
            val jdbcUrl = migratedJdbcUrl("staff-call-order-author")
            val fixture = seedVenueTableSession(jdbcUrl)
            val repository = OrdersRepository(dataSource(jdbcUrl))
            val userId = 1001L
            seedUser(jdbcUrl, userId)
            val orderId =
                repository.getOrCreateActiveOrderId(
                    tableId = fixture.tableId,
                    venueId = fixture.venueId,
                    tableSessionId = fixture.tableSessionId,
                    venueZoneId = ZoneId.of("UTC"),
                )
            assertNotNull(orderId)
            seedOrderBatch(jdbcUrl, orderId = orderId, authorUserId = userId)

            val resolved =
                repository.findSafelyLinkableStaffCallOrderId(
                    venueId = fixture.venueId,
                    tableSessionId = fixture.tableSessionId,
                    userId = userId,
                )

            assertEquals(orderId, resolved)
        }

    @Test
    fun `safe staff call resolver returns active order for tab member participant`() =
        runBlocking {
            val jdbcUrl = migratedJdbcUrl("staff-call-order-tab-member")
            val fixture = seedVenueTableSession(jdbcUrl)
            val repository = OrdersRepository(dataSource(jdbcUrl))
            val ownerUserId = 1001L
            val memberUserId = 2002L
            seedUser(jdbcUrl, ownerUserId)
            seedUser(jdbcUrl, memberUserId)
            val orderId =
                repository.getOrCreateActiveOrderId(
                    tableId = fixture.tableId,
                    venueId = fixture.venueId,
                    tableSessionId = fixture.tableSessionId,
                    venueZoneId = ZoneId.of("UTC"),
                )
            assertNotNull(orderId)
            val tabId =
                seedTab(
                    jdbcUrl = jdbcUrl,
                    venueId = fixture.venueId,
                    tableSessionId = fixture.tableSessionId,
                    ownerUserId = ownerUserId,
                )
            seedTabMember(jdbcUrl, tabId = tabId, userId = memberUserId)
            seedOrderBatch(jdbcUrl, orderId = orderId, authorUserId = ownerUserId, tabId = tabId)

            val resolved =
                repository.findSafelyLinkableStaffCallOrderId(
                    venueId = fixture.venueId,
                    tableSessionId = fixture.tableSessionId,
                    userId = memberUserId,
                )

            assertEquals(orderId, resolved)
        }

    @Test
    fun `safe staff call resolver returns null without active order`() =
        runBlocking {
            val jdbcUrl = migratedJdbcUrl("staff-call-order-none")
            val fixture = seedVenueTableSession(jdbcUrl)
            val repository = OrdersRepository(dataSource(jdbcUrl))

            val resolved =
                repository.findSafelyLinkableStaffCallOrderId(
                    venueId = fixture.venueId,
                    tableSessionId = fixture.tableSessionId,
                    userId = 1001L,
                )

            assertNull(resolved)
        }

    @Test
    fun `safe staff call resolver does not attach another user to order`() =
        runBlocking {
            val jdbcUrl = migratedJdbcUrl("staff-call-order-wrong-user")
            val fixture = seedVenueTableSession(jdbcUrl)
            val repository = OrdersRepository(dataSource(jdbcUrl))
            val orderUserId = 1001L
            val otherUserId = 2002L
            seedUser(jdbcUrl, orderUserId)
            seedUser(jdbcUrl, otherUserId)
            val orderId =
                repository.getOrCreateActiveOrderId(
                    tableId = fixture.tableId,
                    venueId = fixture.venueId,
                    tableSessionId = fixture.tableSessionId,
                    venueZoneId = ZoneId.of("UTC"),
                )
            assertNotNull(orderId)
            seedOrderBatch(jdbcUrl, orderId = orderId, authorUserId = orderUserId)

            val resolved =
                repository.findSafelyLinkableStaffCallOrderId(
                    venueId = fixture.venueId,
                    tableSessionId = fixture.tableSessionId,
                    userId = otherUserId,
                )

            assertNull(resolved)
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

    private fun seedAdditionalTableSession(
        jdbcUrl: String,
        venueId: Long,
        tableNumber: Int,
    ): OrderFixture {
        DriverManager.getConnection(jdbcUrl, "sa", "").use { connection ->
            val tableId =
                connection.prepareStatement(
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

    private fun seedUser(
        jdbcUrl: String,
        userId: Long,
    ) {
        DriverManager.getConnection(jdbcUrl, "sa", "").use { connection ->
            connection.prepareStatement(
                """
                INSERT INTO users (telegram_user_id, first_name)
                VALUES (?, 'Guest')
                """.trimIndent(),
            ).use { statement ->
                statement.setLong(1, userId)
                statement.executeUpdate()
            }
        }
    }

    private fun seedTab(
        jdbcUrl: String,
        venueId: Long,
        tableSessionId: Long,
        ownerUserId: Long,
    ): Long =
        DriverManager.getConnection(jdbcUrl, "sa", "").use { connection ->
            connection.prepareStatement(
                """
                INSERT INTO tab (venue_id, table_session_id, type, owner_user_id)
                VALUES (?, ?, 'PERSONAL', ?)
                """.trimIndent(),
                Statement.RETURN_GENERATED_KEYS,
            ).use { statement ->
                statement.setLong(1, venueId)
                statement.setLong(2, tableSessionId)
                statement.setLong(3, ownerUserId)
                statement.executeUpdate()
                statement.generatedKeys.use { rs ->
                    if (rs.next()) rs.getLong(1) else error("Failed to insert tab")
                }
            }
        }

    private fun seedTabMember(
        jdbcUrl: String,
        tabId: Long,
        userId: Long,
    ) {
        DriverManager.getConnection(jdbcUrl, "sa", "").use { connection ->
            connection.prepareStatement(
                """
                INSERT INTO tab_member (tab_id, user_id, role)
                VALUES (?, ?, 'MEMBER')
                """.trimIndent(),
            ).use { statement ->
                statement.setLong(1, tabId)
                statement.setLong(2, userId)
                statement.executeUpdate()
            }
        }
    }

    private fun seedOrderBatch(
        jdbcUrl: String,
        orderId: Long,
        authorUserId: Long?,
        tabId: Long? = null,
    ): Long =
        DriverManager.getConnection(jdbcUrl, "sa", "").use { connection ->
            connection.prepareStatement(
                """
                INSERT INTO order_batches (order_id, author_user_id, tab_id, source, status, items_snapshot)
                VALUES (?, ?, ?, 'MINIAPP', 'NEW', '[]')
                """.trimIndent(),
                Statement.RETURN_GENERATED_KEYS,
            ).use { statement ->
                statement.setLong(1, orderId)
                if (authorUserId != null) {
                    statement.setLong(2, authorUserId)
                } else {
                    statement.setNull(2, java.sql.Types.BIGINT)
                }
                if (tabId != null) {
                    statement.setLong(3, tabId)
                } else {
                    statement.setNull(3, java.sql.Types.BIGINT)
                }
                statement.executeUpdate()
                statement.generatedKeys.use { rs ->
                    if (rs.next()) rs.getLong(1) else error("Failed to insert order batch")
                }
            }
        }

    private fun seedClosedOrderDisplay(
        jdbcUrl: String,
        fixture: OrderFixture,
        displayDate: LocalDate,
        displayNumber: Int,
    ) {
        DriverManager.getConnection(jdbcUrl, "sa", "").use { connection ->
            connection.prepareStatement(
                """
                INSERT INTO orders (
                    venue_id, table_id, table_session_id, status, display_number, display_date
                )
                VALUES (?, ?, ?, 'CLOSED', ?, ?)
                """.trimIndent(),
            ).use { statement ->
                statement.setLong(1, fixture.venueId)
                statement.setLong(2, fixture.tableId)
                statement.setLong(3, fixture.tableSessionId)
                statement.setInt(4, displayNumber)
                statement.setDate(5, Date.valueOf(displayDate))
                statement.executeUpdate()
            }
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
