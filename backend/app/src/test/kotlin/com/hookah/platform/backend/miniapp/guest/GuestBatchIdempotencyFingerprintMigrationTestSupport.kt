package com.hookah.platform.backend.miniapp.guest

import kotlinx.serialization.json.Json
import org.flywaydb.core.Flyway
import java.sql.Connection
import java.sql.DatabaseMetaData
import java.sql.Statement
import java.sql.Timestamp
import java.sql.Types
import java.time.Instant
import javax.sql.DataSource
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

internal object GuestBatchIdempotencyFingerprintMigrationTestSupport {
    fun assertMigrationSchemaAndExistingRows(
        dataSource: DataSource,
        location: String,
        previousVersion: String,
        expectedVersion: String,
    ) {
        val beforeFlyway = flyway(dataSource, location, previousVersion)
        beforeFlyway.migrate()
        assertEquals(previousVersion, beforeFlyway.info().current().version.version)

        val existingId =
            dataSource.connection.use { connection ->
                val fixture = seedContext(connection)
                insertIdempotency(
                    connection = connection,
                    fixture = fixture,
                    key = "pre-fingerprint-migration",
                    responseSnapshot = PREEXISTING_RESPONSE_SNAPSHOT,
                )
            }
        val beforeRow = dataSource.connection.use { connection -> loadRow(connection, existingId) }
        val beforeColumns = dataSource.connection.use { connection -> loadColumnNames(connection) }

        val migrationFlyway = flyway(dataSource, location, expectedVersion)
        val migrationResult = migrationFlyway.migrate()
        assertEquals(1, migrationResult.migrationsExecuted)
        assertEquals(expectedVersion, migrationFlyway.info().current().version.version)

        dataSource.connection.use { connection ->
            assertFingerprintColumn(connection)
            assertNoFingerprintIndex(connection)
            assertNoFingerprintConstraint(connection)
            assertEquals(beforeColumns + "request_fingerprint", loadColumnNames(connection))
            val afterRow = loadRow(connection, existingId)
            assertEquals(beforeRow, afterRow)
            assertJsonEquals(PREEXISTING_RESPONSE_SNAPSHOT, afterRow.responseSnapshot)
            assertNull(loadFingerprint(connection, existingId))
            assertEquals(1, countRows(connection))
        }
    }

    fun assertLegacyAndFingerprintWriterCompatibility(
        dataSource: DataSource,
        location: String,
        expectedVersion: String,
    ) {
        val migrationFlyway = flyway(dataSource, location, expectedVersion)
        migrationFlyway.migrate()
        assertEquals(expectedVersion, migrationFlyway.info().current().version.version)

        dataSource.connection.use { connection ->
            val fixture = seedContext(connection)
            val legacyId =
                insertIdempotency(
                    connection = connection,
                    fixture = fixture,
                    key = "legacy-writer",
                    responseSnapshot = LEGACY_RESPONSE_SNAPSHOT,
                )
            val firstFingerprintId =
                insertIdempotency(
                    connection = connection,
                    fixture = fixture,
                    key = "fingerprint-writer-1",
                    responseSnapshot = FINGERPRINT_RESPONSE_SNAPSHOT,
                    requestFingerprint = REQUEST_FINGERPRINT,
                )
            val secondFingerprintId =
                insertIdempotency(
                    connection = connection,
                    fixture = fixture,
                    key = "fingerprint-writer-2",
                    responseSnapshot = FINGERPRINT_RESPONSE_SNAPSHOT,
                    requestFingerprint = REQUEST_FINGERPRINT,
                )

            assertNull(loadFingerprint(connection, legacyId))
            assertEquals(REQUEST_FINGERPRINT, loadFingerprint(connection, firstFingerprintId))
            assertEquals(REQUEST_FINGERPRINT, loadFingerprint(connection, secondFingerprintId))
            assertJsonEquals(LEGACY_RESPONSE_SNAPSHOT, loadRow(connection, legacyId).responseSnapshot)
            assertJsonEquals(
                FINGERPRINT_RESPONSE_SNAPSHOT,
                loadRow(connection, firstFingerprintId).responseSnapshot,
            )
            assertEquals(3, countRows(connection))
        }
    }

    private fun assertFingerprintColumn(connection: Connection) {
        connection.prepareStatement(
            """
            SELECT data_type, character_maximum_length, is_nullable, column_default
            FROM information_schema.columns
            WHERE LOWER(table_schema) = LOWER(?)
              AND LOWER(table_name) = 'guest_batch_idempotency'
              AND LOWER(column_name) = 'request_fingerprint'
            """.trimIndent(),
        ).use { statement ->
            statement.setString(1, connection.schema)
            statement.executeQuery().use { resultSet ->
                assertTrue(resultSet.next(), "guest_batch_idempotency.request_fingerprint must exist")
                assertEquals("character varying", resultSet.getString("data_type").lowercase())
                assertEquals(80, resultSet.getInt("character_maximum_length"))
                assertEquals("YES", resultSet.getString("is_nullable").uppercase())
                assertNull(resultSet.getString("column_default"))
                assertFalse(resultSet.next(), "request_fingerprint column must be defined exactly once")
            }
        }

        val metadata = connection.metaData
        metadata.getColumns(
            connection.catalog,
            metadataIdentifier(metadata, connection.schema),
            metadataIdentifier(metadata, "guest_batch_idempotency"),
            metadataIdentifier(metadata, "request_fingerprint"),
        ).use { columns ->
            assertTrue(columns.next())
            assertEquals(Types.VARCHAR, columns.getInt("DATA_TYPE"))
            assertEquals(80, columns.getInt("COLUMN_SIZE"))
            assertEquals(DatabaseMetaData.columnNullable, columns.getInt("NULLABLE"))
            assertNull(columns.getString("COLUMN_DEF"))
            assertFalse(columns.next())
        }
    }

    private fun assertNoFingerprintIndex(connection: Connection) {
        val metadata = connection.metaData
        metadata.getIndexInfo(
            connection.catalog,
            metadataIdentifier(metadata, connection.schema),
            metadataIdentifier(metadata, "guest_batch_idempotency"),
            false,
            false,
        ).use { indexes ->
            while (indexes.next()) {
                assertFalse(
                    indexes.getString("COLUMN_NAME").equals("request_fingerprint", ignoreCase = true),
                    "request_fingerprint must not have an index",
                )
            }
        }
    }

    private fun assertNoFingerprintConstraint(connection: Connection) {
        connection.prepareStatement(
            """
            SELECT constraint_name
            FROM information_schema.constraint_column_usage
            WHERE LOWER(table_schema) = LOWER(?)
              AND LOWER(table_name) = 'guest_batch_idempotency'
              AND LOWER(column_name) = 'request_fingerprint'
            """.trimIndent(),
        ).use { statement ->
            statement.setString(1, connection.schema)
            statement.executeQuery().use { resultSet ->
                assertFalse(resultSet.next(), "request_fingerprint must not have a constraint")
            }
        }
    }

    private fun loadColumnNames(connection: Connection): Set<String> =
        connection.prepareStatement(
            """
            SELECT column_name
            FROM information_schema.columns
            WHERE LOWER(table_schema) = LOWER(?)
              AND LOWER(table_name) = 'guest_batch_idempotency'
            """.trimIndent(),
        ).use { statement ->
            statement.setString(1, connection.schema)
            statement.executeQuery().use { resultSet ->
                buildSet {
                    while (resultSet.next()) {
                        add(resultSet.getString("column_name").lowercase())
                    }
                }
            }
        }

    private fun seedContext(connection: Connection): Fixture {
        val venueId =
            insertReturningId(
                connection,
                """
                INSERT INTO venues (name, city, address, status)
                VALUES (?, ?, ?, ?)
                """.trimIndent(),
            ) { statement ->
                statement.setString(1, "Fingerprint migration venue")
                statement.setString(2, "Moscow")
                statement.setString(3, "Migration street, 1")
                statement.setString(4, "PUBLISHED")
            }
        val tableId =
            insertReturningId(
                connection,
                """
                INSERT INTO venue_tables (venue_id, table_number, is_active)
                VALUES (?, ?, TRUE)
                """.trimIndent(),
            ) { statement ->
                statement.setLong(1, venueId)
                statement.setInt(2, 1)
            }
        val tableSessionId =
            insertReturningId(
                connection,
                """
                INSERT INTO table_sessions (
                    venue_id,
                    table_id,
                    started_at,
                    last_activity_at,
                    expires_at,
                    status
                )
                VALUES (?, ?, ?, ?, ?, 'ACTIVE')
                """.trimIndent(),
            ) { statement ->
                statement.setLong(1, venueId)
                statement.setLong(2, tableId)
                statement.setTimestamp(3, Timestamp.from(SESSION_STARTED_AT))
                statement.setTimestamp(4, Timestamp.from(SESSION_STARTED_AT))
                statement.setTimestamp(5, Timestamp.from(SESSION_STARTED_AT.plusSeconds(7_200)))
            }
        val orderId =
            insertReturningId(
                connection,
                """
                INSERT INTO orders (venue_id, table_id, table_session_id, status)
                VALUES (?, ?, ?, 'ACTIVE')
                """.trimIndent(),
            ) { statement ->
                statement.setLong(1, venueId)
                statement.setLong(2, tableId)
                statement.setLong(3, tableSessionId)
            }
        val batchId =
            insertReturningId(
                connection,
                """
                INSERT INTO order_batches (order_id, source, status)
                VALUES (?, 'MINIAPP', 'NEW')
                """.trimIndent(),
            ) { statement ->
                statement.setLong(1, orderId)
            }
        return Fixture(
            venueId = venueId,
            tableSessionId = tableSessionId,
            orderId = orderId,
            batchId = batchId,
        )
    }

    private fun insertIdempotency(
        connection: Connection,
        fixture: Fixture,
        key: String,
        responseSnapshot: String,
        requestFingerprint: String? = null,
    ): Long {
        val isPostgres = connection.metaData.databaseProductName.contains("PostgreSQL", ignoreCase = true)
        val responsePlaceholder = if (isPostgres) "CAST(? AS JSONB)" else "?"
        val includeFingerprint = requestFingerprint != null
        val fingerprintColumn = if (includeFingerprint) ", request_fingerprint" else ""
        val fingerprintPlaceholder = if (includeFingerprint) ", ?" else ""
        return insertReturningId(
            connection,
            """
            INSERT INTO guest_batch_idempotency (
                venue_id,
                table_session_id,
                user_id,
                idempotency_key,
                order_id,
                batch_id,
                response_snapshot,
                created_at$fingerprintColumn
            )
            VALUES (?, ?, ?, ?, ?, ?, $responsePlaceholder, ?$fingerprintPlaceholder)
            """.trimIndent(),
        ) { statement ->
            statement.setLong(1, fixture.venueId)
            statement.setLong(2, fixture.tableSessionId)
            statement.setLong(3, USER_ID)
            statement.setString(4, key)
            statement.setLong(5, fixture.orderId)
            statement.setLong(6, fixture.batchId)
            statement.setString(7, responseSnapshot)
            statement.setTimestamp(8, Timestamp.from(IDEMPOTENCY_CREATED_AT))
            if (includeFingerprint) {
                statement.setString(9, requestFingerprint)
            }
        }
    }

    private fun loadRow(
        connection: Connection,
        id: Long,
    ): IdempotencyRow =
        connection.prepareStatement(
            """
            SELECT
                id,
                venue_id,
                table_session_id,
                user_id,
                idempotency_key,
                order_id,
                batch_id,
                response_snapshot,
                created_at
            FROM guest_batch_idempotency
            WHERE id = ?
            """.trimIndent(),
        ).use { statement ->
            statement.setLong(1, id)
            statement.executeQuery().use { resultSet ->
                assertTrue(resultSet.next(), "Expected guest_batch_idempotency row $id")
                val row =
                    IdempotencyRow(
                        id = resultSet.getLong("id"),
                        venueId = resultSet.getLong("venue_id"),
                        tableSessionId = resultSet.getLong("table_session_id"),
                        userId = resultSet.getLong("user_id"),
                        idempotencyKey = resultSet.getString("idempotency_key"),
                        orderId = resultSet.getLong("order_id"),
                        batchId = resultSet.getLong("batch_id"),
                        responseSnapshot = resultSet.getString("response_snapshot"),
                        createdAt = resultSet.getTimestamp("created_at").toInstant(),
                    )
                assertFalse(resultSet.next(), "Expected exactly one guest_batch_idempotency row $id")
                row
            }
        }

    private fun loadFingerprint(
        connection: Connection,
        id: Long,
    ): String? =
        connection.prepareStatement(
            "SELECT request_fingerprint FROM guest_batch_idempotency WHERE id = ?",
        ).use { statement ->
            statement.setLong(1, id)
            statement.executeQuery().use { resultSet ->
                assertTrue(resultSet.next())
                resultSet.getString("request_fingerprint")
            }
        }

    private fun countRows(connection: Connection): Int =
        connection.createStatement().use { statement ->
            statement.executeQuery("SELECT COUNT(*) FROM guest_batch_idempotency").use { resultSet ->
                assertTrue(resultSet.next())
                resultSet.getInt(1)
            }
        }

    private fun insertReturningId(
        connection: Connection,
        sql: String,
        bind: (java.sql.PreparedStatement) -> Unit,
    ): Long =
        connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS).use { statement ->
            bind(statement)
            assertEquals(1, statement.executeUpdate())
            statement.generatedKeys.use { resultSet ->
                assertTrue(resultSet.next())
                resultSet.getLong(1)
            }
        }

    private fun assertJsonEquals(
        expected: String,
        actual: String,
    ) {
        assertEquals(Json.parseToJsonElement(expected), Json.parseToJsonElement(actual))
    }

    private fun flyway(
        dataSource: DataSource,
        location: String,
        targetVersion: String,
    ): Flyway =
        Flyway.configure()
            .dataSource(dataSource)
            .locations(location)
            .target(targetVersion)
            .load()

    private fun metadataIdentifier(
        metadata: DatabaseMetaData,
        identifier: String,
    ): String =
        when {
            metadata.storesUpperCaseIdentifiers() -> identifier.uppercase()
            metadata.storesLowerCaseIdentifiers() -> identifier.lowercase()
            else -> identifier
        }

    private data class Fixture(
        val venueId: Long,
        val tableSessionId: Long,
        val orderId: Long,
        val batchId: Long,
    )

    private data class IdempotencyRow(
        val id: Long,
        val venueId: Long,
        val tableSessionId: Long,
        val userId: Long,
        val idempotencyKey: String,
        val orderId: Long,
        val batchId: Long,
        val responseSnapshot: String,
        val createdAt: Instant,
    )

    private const val USER_ID = 9_230_001L
    private const val REQUEST_FINGERPRINT =
        "v1:0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
    private const val PREEXISTING_RESPONSE_SNAPSHOT =
        "{\"orderId\":701,\"batchId\":801,\"status\":\"NEW\"}"
    private const val LEGACY_RESPONSE_SNAPSHOT =
        "{\"orderId\":702,\"batchId\":802,\"status\":\"NEW\"}"
    private const val FINGERPRINT_RESPONSE_SNAPSHOT =
        "{\"orderId\":703,\"batchId\":803,\"status\":\"NEW\"}"
    private val SESSION_STARTED_AT = Instant.parse("2026-08-01T12:00:00Z")
    private val IDEMPOTENCY_CREATED_AT = Instant.parse("2026-08-01T12:05:00Z")
}
