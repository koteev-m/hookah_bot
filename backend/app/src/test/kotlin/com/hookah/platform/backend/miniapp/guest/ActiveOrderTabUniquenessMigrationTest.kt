package com.hookah.platform.backend.miniapp.guest

import com.hookah.platform.backend.miniapp.guest.db.GuestTabsRepository
import com.hookah.platform.backend.telegram.db.OrdersRepository
import com.hookah.platform.backend.test.PostgresTestEnv
import kotlinx.coroutines.runBlocking
import org.flywaydb.core.Flyway
import org.h2.jdbcx.JdbcDataSource
import java.sql.Connection
import java.sql.DriverManager
import java.sql.PreparedStatement
import java.sql.SQLException
import java.sql.Timestamp
import java.sql.Types
import java.time.Instant
import java.time.ZoneId
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ActiveOrderTabUniquenessMigrationTest {
    @Test
    fun `h2 migrated schema mirrors postgres active order and personal tab uniqueness`() {
        val jdbcUrl = migratedH2JdbcUrl("active-order-tab-fidelity")

        DriverManager.getConnection(jdbcUrl, "sa", "").use { connection ->
            assertUniquenessPredicates(connection)
        }
    }

    @Test
    fun `postgres migrated schema enforces active order and personal tab uniqueness`() {
        val database = PostgresTestEnv.createDatabase()

        PostgresTestEnv.createDataSource(database).use { dataSource ->
            dataSource.connection.use { connection ->
                assertUniquenessPredicates(connection)
            }
        }
    }

    @Test
    fun `repositories reuse existing active order and personal tab on repeated creation`() =
        runBlocking {
            val jdbcUrl = migratedH2JdbcUrl("active-order-tab-repository-repeat")
            val fixture =
                DriverManager.getConnection(jdbcUrl, "sa", "").use { connection ->
                    seedBase(connection)
                }
            val dataSource = h2DataSource(jdbcUrl)
            val ordersRepository = OrdersRepository(dataSource)
            val tabsRepository = GuestTabsRepository(dataSource)

            val firstOrderId =
                ordersRepository.getOrCreateActiveOrderId(
                    tableId = fixture.tableId,
                    venueId = fixture.venueId,
                    tableSessionId = fixture.firstTableSessionId,
                    venueZoneId = ZoneId.of("UTC"),
                )
            val secondOrderId =
                ordersRepository.getOrCreateActiveOrderId(
                    tableId = fixture.tableId,
                    venueId = fixture.venueId,
                    tableSessionId = fixture.firstTableSessionId,
                    venueZoneId = ZoneId.of("UTC"),
                )

            assertEquals(firstOrderId, secondOrderId)
            assertEquals(1, countActiveOrders(jdbcUrl, fixture.firstTableSessionId))

            val firstTab =
                tabsRepository.ensurePersonalTab(
                    venueId = fixture.venueId,
                    tableSessionId = fixture.firstTableSessionId,
                    userId = REPOSITORY_USER_ID,
                )
            val secondTab =
                tabsRepository.ensurePersonalTab(
                    venueId = fixture.venueId,
                    tableSessionId = fixture.firstTableSessionId,
                    userId = REPOSITORY_USER_ID,
                )

            assertEquals(firstTab.id, secondTab.id)
            assertEquals(1, countActivePersonalTabs(jdbcUrl, fixture.firstTableSessionId, REPOSITORY_USER_ID))
        }

    private fun assertUniquenessPredicates(connection: Connection) {
        val fixture = seedBase(connection)

        assertDatabaseRejects {
            insertOrder(
                connection = connection,
                fixture = fixture,
                tableSessionId = null,
                status = "ACTIVE",
            )
        }
        assertDatabaseRejects {
            insertTab(
                connection = connection,
                fixture = fixture,
                tableSessionId = null,
                type = "PERSONAL",
                ownerUserId = OWNER_USER_ID,
                status = "ACTIVE",
            )
        }

        insertOrder(connection, fixture, fixture.firstTableSessionId, "ACTIVE")
        assertUniqueViolation("uq_orders_one_active_per_table_session") {
            insertOrder(connection, fixture, fixture.firstTableSessionId, "ACTIVE")
        }
        insertOrder(connection, fixture, fixture.secondTableSessionId, "ACTIVE")
        insertOrder(connection, fixture, fixture.firstTableSessionId, "CLOSED")
        insertOrder(connection, fixture, fixture.firstTableSessionId, "CANCELLED")

        insertTab(connection, fixture, fixture.firstTableSessionId, "PERSONAL", OWNER_USER_ID, "ACTIVE")
        assertUniqueViolation("uq_tab_personal_owner") {
            insertTab(connection, fixture, fixture.firstTableSessionId, "PERSONAL", OWNER_USER_ID, "ACTIVE")
        }
        insertTab(connection, fixture, fixture.secondTableSessionId, "PERSONAL", OWNER_USER_ID, "ACTIVE")
        insertTab(connection, fixture, fixture.firstTableSessionId, "PERSONAL", OWNER_USER_ID, "CLOSED")
        insertTab(connection, fixture, fixture.firstTableSessionId, "PERSONAL", OWNER_USER_ID, "CLOSED")
        insertTab(connection, fixture, fixture.firstTableSessionId, "SHARED", OWNER_USER_ID, "ACTIVE")
        insertTab(connection, fixture, fixture.firstTableSessionId, "SHARED", OWNER_USER_ID, "ACTIVE")
        insertTab(connection, fixture, fixture.firstTableSessionId, "PERSONAL", null, "ACTIVE")
        insertTab(connection, fixture, fixture.firstTableSessionId, "PERSONAL", null, "ACTIVE")
    }

    private fun migratedH2JdbcUrl(prefix: String): String {
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

    private fun h2DataSource(jdbcUrl: String): JdbcDataSource =
        JdbcDataSource().apply {
            setURL(jdbcUrl)
            user = "sa"
            password = ""
        }

    private fun seedBase(connection: Connection): Fixture {
        val venueId =
            insertReturningId(
                connection,
                """
                INSERT INTO venues (name, city, address, status)
                VALUES (?, ?, ?, ?)
                """.trimIndent(),
            ) { statement ->
                statement.setString(1, "Uniqueness Venue")
                statement.setString(2, "City")
                statement.setString(3, "Address")
                statement.setString(4, "PUBLISHED")
            }
        val tableId =
            insertReturningId(
                connection,
                """
                INSERT INTO venue_tables (venue_id, table_number, is_active)
                VALUES (?, ?, true)
                """.trimIndent(),
            ) { statement ->
                statement.setLong(1, venueId)
                statement.setInt(2, 1)
            }
        val firstTableSessionId = insertTableSession(connection, venueId, tableId)
        val secondTableSessionId = insertTableSession(connection, venueId, tableId)
        insertUser(connection, OWNER_USER_ID)

        return Fixture(
            venueId = venueId,
            tableId = tableId,
            firstTableSessionId = firstTableSessionId,
            secondTableSessionId = secondTableSessionId,
        )
    }

    private fun insertTableSession(
        connection: Connection,
        venueId: Long,
        tableId: Long,
    ): Long {
        val now = Instant.now()
        return insertReturningId(
            connection,
            """
            INSERT INTO table_sessions (venue_id, table_id, started_at, last_activity_at, expires_at, status)
            VALUES (?, ?, ?, ?, ?, 'ACTIVE')
            """.trimIndent(),
        ) { statement ->
            statement.setLong(1, venueId)
            statement.setLong(2, tableId)
            statement.setTimestamp(3, Timestamp.from(now))
            statement.setTimestamp(4, Timestamp.from(now))
            statement.setTimestamp(5, Timestamp.from(now.plusSeconds(7200)))
        }
    }

    private fun insertUser(
        connection: Connection,
        userId: Long,
    ) {
        connection.prepareStatement(
            """
            INSERT INTO users (telegram_user_id, username)
            VALUES (?, ?)
            """.trimIndent(),
        ).use { statement ->
            statement.setLong(1, userId)
            statement.setString(2, "user_$userId")
            statement.executeUpdate()
        }
    }

    private fun insertOrder(
        connection: Connection,
        fixture: Fixture,
        tableSessionId: Long?,
        status: String,
    ): Long =
        insertReturningId(
            connection,
            """
            INSERT INTO orders (venue_id, table_id, table_session_id, status)
            VALUES (?, ?, ?, ?)
            """.trimIndent(),
        ) { statement ->
            statement.setLong(1, fixture.venueId)
            statement.setLong(2, fixture.tableId)
            if (tableSessionId != null) {
                statement.setLong(3, tableSessionId)
            } else {
                statement.setNull(3, Types.BIGINT)
            }
            statement.setString(4, status)
        }

    private fun insertTab(
        connection: Connection,
        fixture: Fixture,
        tableSessionId: Long?,
        type: String,
        ownerUserId: Long?,
        status: String,
    ): Long =
        insertReturningId(
            connection,
            """
            INSERT INTO tab (venue_id, table_session_id, type, owner_user_id, status)
            VALUES (?, ?, ?, ?, ?)
            """.trimIndent(),
        ) { statement ->
            statement.setLong(1, fixture.venueId)
            if (tableSessionId != null) {
                statement.setLong(2, tableSessionId)
            } else {
                statement.setNull(2, Types.BIGINT)
            }
            statement.setString(3, type)
            if (ownerUserId != null) {
                statement.setLong(4, ownerUserId)
            } else {
                statement.setNull(4, Types.BIGINT)
            }
            statement.setString(5, status)
        }

    private fun insertReturningId(
        connection: Connection,
        sql: String,
        bind: (PreparedStatement) -> Unit,
    ): Long =
        connection.prepareStatement(sql, arrayOf("id")).use { statement ->
            bind(statement)
            statement.executeUpdate()
            statement.generatedKeys.use { resultSet ->
                assertTrue(resultSet.next(), "Expected generated id for insert")
                resultSet.getLong(1)
            }
        }

    private fun countActiveOrders(
        jdbcUrl: String,
        tableSessionId: Long,
    ): Int =
        countRows(
            jdbcUrl = jdbcUrl,
            sql = "SELECT COUNT(*) FROM orders WHERE table_session_id = ? AND status = 'ACTIVE'",
            bind = { it.setLong(1, tableSessionId) },
        )

    private fun countActivePersonalTabs(
        jdbcUrl: String,
        tableSessionId: Long,
        userId: Long,
    ): Int =
        countRows(
            jdbcUrl = jdbcUrl,
            sql =
                """
                SELECT COUNT(*)
                FROM tab
                WHERE table_session_id = ?
                  AND owner_user_id = ?
                  AND type = 'PERSONAL'
                  AND status = 'ACTIVE'
                """.trimIndent(),
            bind = {
                it.setLong(1, tableSessionId)
                it.setLong(2, userId)
            },
        )

    private fun countRows(
        jdbcUrl: String,
        sql: String,
        bind: (PreparedStatement) -> Unit,
    ): Int =
        DriverManager.getConnection(jdbcUrl, "sa", "").use { connection ->
            connection.prepareStatement(sql).use { statement ->
                bind(statement)
                statement.executeQuery().use { resultSet ->
                    resultSet.next()
                    resultSet.getInt(1)
                }
            }
        }

    private fun assertUniqueViolation(
        expectedConstraintName: String,
        block: () -> Unit,
    ) {
        val error = assertFailsWith<SQLException> { block() }
        assertTrue(
            error.containsSqlStateOrMessage("23505", expectedConstraintName),
            "Expected unique violation for $expectedConstraintName, got: ${error.describeChain()}",
        )
    }

    private fun assertDatabaseRejects(block: () -> Unit) {
        assertFailsWith<SQLException> { block() }
    }

    private fun Throwable.containsSqlStateOrMessage(
        expectedSqlState: String,
        expectedMessagePart: String,
    ): Boolean {
        var current: Throwable? = this
        while (current != null) {
            if (current.message?.contains(expectedMessagePart, ignoreCase = true) == true) {
                return true
            }
            if (current is SQLException) {
                var sqlException: SQLException? = current
                while (sqlException != null) {
                    if (sqlException.sqlState == expectedSqlState ||
                        sqlException.message?.contains(expectedMessagePart, ignoreCase = true) == true
                    ) {
                        return true
                    }
                    sqlException = sqlException.nextException
                }
            }
            current = current.cause
        }
        return false
    }

    private fun Throwable.describeChain(): String =
        generateSequence(this as Throwable?) { it.cause }
            .joinToString(" -> ") { error ->
                val sqlState = (error as? SQLException)?.sqlState
                "${error::class.simpleName}(sqlState=$sqlState,message=${error.message})"
            }

    private data class Fixture(
        val venueId: Long,
        val tableId: Long,
        val firstTableSessionId: Long,
        val secondTableSessionId: Long,
    )

    private companion object {
        const val OWNER_USER_ID = 990_001L
        const val REPOSITORY_USER_ID = 990_002L
    }
}
