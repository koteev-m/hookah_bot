package com.hookah.platform.backend.miniapp.venue

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.flywaydb.core.Flyway
import java.sql.Connection
import java.sql.DatabaseMetaData
import java.sql.Types
import javax.sql.DataSource
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

internal object AuditLogTargetMigrationTestSupport {
    fun assertMigrationSchemaAndExistingRows(
        dataSource: DataSource,
        location: String,
        previousVersion: String,
        expectedVersion: String,
    ) {
        val beforeFlyway = flyway(dataSource, location, previousVersion)
        beforeFlyway.migrate()
        assertEquals(previousVersion, beforeFlyway.info().current().version.version)

        dataSource.connection.use { connection ->
            insertUser(connection, ACTOR_USER_ID)
            connection.prepareStatement(
                """
                INSERT INTO audit_log (
                    actor_user_id,
                    action,
                    entity_type,
                    entity_id,
                    payload_json
                )
                VALUES (?, 'PREEXISTING_AUDIT', 'venue', 42, '{}')
                """.trimIndent(),
            ).use { statement ->
                statement.setLong(1, ACTOR_USER_ID)
                assertEquals(1, statement.executeUpdate())
            }
        }

        val migrationResult = flyway(dataSource, location, expectedVersion).migrate()
        assertEquals(1, migrationResult.migrationsExecuted)
        assertEquals(expectedVersion, flyway(dataSource, location, expectedVersion).info().current().version.version)

        dataSource.connection.use { connection ->
            assertTargetColumn(connection)
            assertTargetForeignKey(connection)
            assertTargetIndex(connection)
            connection.prepareStatement(
                """
                SELECT target_user_id
                FROM audit_log
                WHERE action = 'PREEXISTING_AUDIT'
                """.trimIndent(),
            ).use { statement ->
                statement.executeQuery().use { resultSet ->
                    assertTrue(resultSet.next())
                    assertNull(resultSet.getObject("target_user_id"))
                    assertFalse(resultSet.next())
                }
            }
        }
    }

    fun assertLegacyAndTargetedWriterCompatibility(
        dataSource: DataSource,
        location: String,
        expectedVersion: String,
    ) {
        flyway(dataSource, location, expectedVersion).migrate()

        dataSource.connection.use { connection ->
            insertUser(connection, ACTOR_USER_ID)
            insertUser(connection, TARGET_USER_ID)

            val repository = AuditLogRepository(dataSource, Json)
            val legacyWriter: TransactionalAuditLogWriter = repository
            val targetedWriter: TransactionalTargetedAuditLogWriter = repository
            connection.autoCommit = false
            legacyWriter.appendJson(
                connection = connection,
                actorUserId = ACTOR_USER_ID,
                action = "LEGACY_AUDIT",
                entityType = "venue",
                entityId = 42,
                payload = buildJsonObject { put("source", "TEST") },
            )
            targetedWriter.appendTargetedJson(
                connection = connection,
                actorUserId = ACTOR_USER_ID,
                targetUserId = TARGET_USER_ID,
                action = "TARGETED_AUDIT",
                entityType = "venue",
                entityId = 42,
                payload =
                    buildJsonObject {
                        put("oldRole", "STAFF")
                        put("newRole", "MANAGER")
                        put("source", "VENUE_MINI_APP")
                    },
            )
            connection.commit()

            assertAuditTarget(connection, "LEGACY_AUDIT", expectedTargetUserId = null)
            assertAuditTarget(connection, "TARGETED_AUDIT", expectedTargetUserId = TARGET_USER_ID)

            connection.prepareStatement(
                "DELETE FROM users WHERE telegram_user_id = ?",
            ).use { statement ->
                statement.setLong(1, TARGET_USER_ID)
                assertEquals(1, statement.executeUpdate())
            }
            connection.commit()

            assertAuditTarget(connection, "TARGETED_AUDIT", expectedTargetUserId = null)
        }
    }

    private fun assertTargetColumn(connection: Connection) {
        connection.prepareStatement(
            """
            SELECT data_type, is_nullable
            FROM information_schema.columns
            WHERE LOWER(table_schema) = LOWER(?)
              AND LOWER(table_name) = 'audit_log'
              AND LOWER(column_name) = 'target_user_id'
            """.trimIndent(),
        ).use { statement ->
            statement.setString(1, connection.schema)
            statement.executeQuery().use { resultSet ->
                assertTrue(resultSet.next(), "audit_log.target_user_id must exist")
                assertEquals("bigint", resultSet.getString("data_type").lowercase())
                assertEquals("YES", resultSet.getString("is_nullable").uppercase())
                assertFalse(resultSet.next(), "audit_log.target_user_id must be unique")
            }
        }

        val metadata = connection.metaData
        metadata.getColumns(
            connection.catalog,
            metadataIdentifier(metadata, connection.schema),
            metadataIdentifier(metadata, "audit_log"),
            metadataIdentifier(metadata, "target_user_id"),
        ).use { columns ->
            assertTrue(columns.next())
            assertEquals(Types.BIGINT, columns.getInt("DATA_TYPE"))
            assertEquals(DatabaseMetaData.columnNullable, columns.getInt("NULLABLE"))
            assertFalse(columns.next())
        }
    }

    private fun assertTargetForeignKey(connection: Connection) {
        val metadata = connection.metaData
        val matchingRows = mutableListOf<ForeignKeyMetadata>()
        metadata.getImportedKeys(
            connection.catalog,
            metadataIdentifier(metadata, connection.schema),
            metadataIdentifier(metadata, "audit_log"),
        ).use { foreignKeys ->
            while (foreignKeys.next()) {
                if (foreignKeys.getString("FK_NAME").equals(TARGET_FOREIGN_KEY, ignoreCase = true)) {
                    matchingRows +=
                        ForeignKeyMetadata(
                            foreignTable = foreignKeys.getString("FKTABLE_NAME"),
                            foreignColumn = foreignKeys.getString("FKCOLUMN_NAME"),
                            primaryTable = foreignKeys.getString("PKTABLE_NAME"),
                            primaryColumn = foreignKeys.getString("PKCOLUMN_NAME"),
                            deleteRule = foreignKeys.getShort("DELETE_RULE").toInt(),
                        )
                }
            }
        }

        assertEquals(
            listOf(
                ForeignKeyMetadata(
                    foreignTable = "audit_log",
                    foreignColumn = "target_user_id",
                    primaryTable = "users",
                    primaryColumn = "telegram_user_id",
                    deleteRule = DatabaseMetaData.importedKeySetNull,
                ),
            ),
            matchingRows.map {
                it.copy(
                    foreignTable = it.foreignTable.lowercase(),
                    foreignColumn = it.foreignColumn.lowercase(),
                    primaryTable = it.primaryTable.lowercase(),
                    primaryColumn = it.primaryColumn.lowercase(),
                )
            },
        )
    }

    private fun assertTargetIndex(connection: Connection) {
        val metadata = connection.metaData
        val columns = mutableListOf<IndexColumnMetadata>()
        metadata.getIndexInfo(
            connection.catalog,
            metadataIdentifier(metadata, connection.schema),
            metadataIdentifier(metadata, "audit_log"),
            false,
            false,
        ).use { indexes ->
            while (indexes.next()) {
                if (indexes.getString("INDEX_NAME").equals(TARGET_INDEX, ignoreCase = true)) {
                    columns +=
                        IndexColumnMetadata(
                            ordinal = indexes.getShort("ORDINAL_POSITION").toInt(),
                            column = indexes.getString("COLUMN_NAME").lowercase(),
                            nonUnique = indexes.getBoolean("NON_UNIQUE"),
                        )
                }
            }
        }

        assertEquals(
            listOf(
                IndexColumnMetadata(ordinal = 1, column = "target_user_id", nonUnique = true),
                IndexColumnMetadata(ordinal = 2, column = "created_at", nonUnique = true),
            ),
            columns.sortedBy(IndexColumnMetadata::ordinal),
        )
    }

    private fun assertAuditTarget(
        connection: Connection,
        action: String,
        expectedTargetUserId: Long?,
    ) {
        connection.prepareStatement(
            """
            SELECT target_user_id
            FROM audit_log
            WHERE action = ?
            """.trimIndent(),
        ).use { statement ->
            statement.setString(1, action)
            statement.executeQuery().use { resultSet ->
                assertTrue(resultSet.next(), "Expected one $action audit row")
                val targetUserId = resultSet.getLong("target_user_id").takeUnless { resultSet.wasNull() }
                assertEquals(expectedTargetUserId, targetUserId)
                assertFalse(resultSet.next(), "Expected exactly one $action audit row")
            }
        }
    }

    private fun insertUser(
        connection: Connection,
        userId: Long,
    ) {
        connection.prepareStatement(
            "INSERT INTO users (telegram_user_id) VALUES (?)",
        ).use { statement ->
            statement.setLong(1, userId)
            assertEquals(1, statement.executeUpdate())
        }
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

    private data class ForeignKeyMetadata(
        val foreignTable: String,
        val foreignColumn: String,
        val primaryTable: String,
        val primaryColumn: String,
        val deleteRule: Int,
    )

    private data class IndexColumnMetadata(
        val ordinal: Int,
        val column: String,
        val nonUnique: Boolean,
    )

    private const val ACTOR_USER_ID = 9_100_001L
    private const val TARGET_USER_ID = 9_100_002L
    private const val TARGET_FOREIGN_KEY = "fk_audit_log_target_user"
    private const val TARGET_INDEX = "idx_audit_log_target_user_created_at"
}
