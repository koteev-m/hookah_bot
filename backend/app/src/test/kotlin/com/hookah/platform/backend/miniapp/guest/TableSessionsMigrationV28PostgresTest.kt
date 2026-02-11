package com.hookah.platform.backend.miniapp.guest

import com.hookah.platform.backend.test.PostgresTestEnv
import org.flywaydb.core.Flyway
import java.sql.Connection
import java.sql.DriverManager
import java.sql.SQLException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull

class TableSessionsMigrationV28PostgresTest {
    @Test
    fun `v28 remaps guest batch idempotency table_session_id from venue table to table session`() {
        val database = PostgresTestEnv.createDatabase()

        migrateToVersion27(database.jdbcUrl, database.user, database.password)

        DriverManager.getConnection(database.jdbcUrl, database.user, database.password).use { connection ->
            val venueId = insertVenue(connection)
            val tableId = insertVenueTable(connection, venueId)
            val orderId = insertOrder(connection, venueId, tableId)
            val batchId = insertOrderBatch(connection, orderId)
            val idempotencyId =
                insertGuestBatchIdempotency(
                    connection = connection,
                    venueId = venueId,
                    oldTableId = tableId,
                    orderId = orderId,
                    batchId = batchId,
                )

            migrateToLatest(database.jdbcUrl, database.user, database.password)

            connection.prepareStatement(
                """
                SELECT
                    gbi.table_session_id,
                    ts.id,
                    ts.venue_id,
                    ts.table_id,
                    ts.status,
                    ts.ended_at
                FROM guest_batch_idempotency gbi
                JOIN table_sessions ts ON ts.id = gbi.table_session_id
                WHERE gbi.id = ?
                """.trimIndent(),
            ).use { statement ->
                statement.setLong(1, idempotencyId)
                statement.executeQuery().use { resultSet ->
                    assertEquals(true, resultSet.next())

                    val remappedSessionId = resultSet.getLong("table_session_id")
                    assertEquals(resultSet.getLong("id"), remappedSessionId)
                    assertEquals(venueId, resultSet.getLong("venue_id"))
                    assertEquals(tableId, resultSet.getLong("table_id"))
                    assertEquals("ENDED", resultSet.getString("status"))
                    assertNotNull(resultSet.getTimestamp("ended_at"))
                }
            }

            assertFailsWith<SQLException> {
                connection.prepareStatement(
                    "UPDATE guest_batch_idempotency SET table_session_id = ? WHERE id = ?",
                ).use { statement ->
                    statement.setLong(1, Long.MAX_VALUE)
                    statement.setLong(2, idempotencyId)
                    statement.executeUpdate()
                }
            }
        }
    }

    private fun migrateToVersion27(
        jdbcUrl: String,
        user: String,
        password: String,
    ) {
        Flyway.configure()
            .dataSource(jdbcUrl, user, password)
            .locations("classpath:db/migration")
            .target("27")
            .load()
            .migrate()
    }

    private fun migrateToLatest(
        jdbcUrl: String,
        user: String,
        password: String,
    ) {
        Flyway.configure()
            .dataSource(jdbcUrl, user, password)
            .locations("classpath:db/migration")
            .load()
            .migrate()
    }

    private fun insertVenue(connection: Connection): Long =
        connection.prepareStatement(
            "INSERT INTO venues (name, status) VALUES (?, ?) RETURNING id",
        ).use { statement ->
            statement.setString(1, "Migration Test Venue")
            statement.setString(2, "DRAFT")
            statement.executeQuery().use { resultSet ->
                resultSet.next()
                resultSet.getLong("id")
            }
        }

    private fun insertVenueTable(
        connection: Connection,
        venueId: Long,
    ): Long =
        connection.prepareStatement(
            "INSERT INTO venue_tables (venue_id, table_number) VALUES (?, ?) RETURNING id",
        ).use { statement ->
            statement.setLong(1, venueId)
            statement.setInt(2, 5)
            statement.executeQuery().use { resultSet ->
                resultSet.next()
                resultSet.getLong("id")
            }
        }

    private fun insertOrder(
        connection: Connection,
        venueId: Long,
        tableId: Long,
    ): Long =
        connection.prepareStatement(
            "INSERT INTO orders (venue_id, table_id, status) VALUES (?, ?, ?) RETURNING id",
        ).use { statement ->
            statement.setLong(1, venueId)
            statement.setLong(2, tableId)
            statement.setString(3, "ACTIVE")
            statement.executeQuery().use { resultSet ->
                resultSet.next()
                resultSet.getLong("id")
            }
        }

    private fun insertOrderBatch(
        connection: Connection,
        orderId: Long,
    ): Long =
        connection.prepareStatement(
            """
            INSERT INTO order_batches (order_id, source, status, items_snapshot)
            VALUES (?, ?, ?, ?::jsonb)
            RETURNING id
            """.trimIndent(),
        ).use { statement ->
            statement.setLong(1, orderId)
            statement.setString(2, "MINIAPP")
            statement.setString(3, "NEW")
            statement.setString(4, "[]")
            statement.executeQuery().use { resultSet ->
                resultSet.next()
                resultSet.getLong("id")
            }
        }

    private fun insertGuestBatchIdempotency(
        connection: Connection,
        venueId: Long,
        oldTableId: Long,
        orderId: Long,
        batchId: Long,
    ): Long =
        connection.prepareStatement(
            """
            INSERT INTO guest_batch_idempotency (
                venue_id,
                table_session_id,
                user_id,
                idempotency_key,
                order_id,
                batch_id,
                created_at
            )
            VALUES (?, ?, ?, ?, ?, ?, ?::timestamptz)
            RETURNING id
            """.trimIndent(),
        ).use { statement ->
            statement.setLong(1, venueId)
            statement.setLong(2, oldTableId)
            statement.setLong(3, 501)
            statement.setString(4, "legacy-remap-key")
            statement.setLong(5, orderId)
            statement.setLong(6, batchId)
            statement.setString(7, "2025-01-01T10:00:00Z")
            statement.executeQuery().use { resultSet ->
                resultSet.next()
                resultSet.getLong("id")
            }
        }
}
