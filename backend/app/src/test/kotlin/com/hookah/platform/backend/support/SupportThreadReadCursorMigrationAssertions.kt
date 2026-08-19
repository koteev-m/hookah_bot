package com.hookah.platform.backend.support

import org.flywaydb.core.Flyway
import org.flywaydb.core.api.FlywayException
import java.sql.Connection
import java.sql.DatabaseMetaData
import java.sql.Statement
import java.sql.Timestamp
import java.sql.Types
import java.time.Instant
import javax.sql.DataSource
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

internal enum class MigrationDdlFailureContract {
    TRANSACTIONAL,
    NON_TRANSACTIONAL,
}

internal class SupportThreadReadCursorMigrationAssertions(
    private val dataSource: DataSource,
    private val location: String,
    private val previousVersion: String,
    private val expectedVersion: String,
    private val ddlFailureContract: MigrationDdlFailureContract,
    private val close: () -> Unit = {},
) {
    fun assertLegacyMarkerRemainsUnbackfilled() =
        scenario { connection, fixture ->
            val lastReadAtBefore =
                readMarkerWithoutCursor(connection, fixture.threadId, fixture.readerUserId).lastReadAt

            migrate(expectedVersion)

            val marker = readMarker(connection, fixture.threadId, fixture.readerUserId)
            assertNull(marker.lastReadMessageId)
            assertEquals(lastReadAtBefore, marker.lastReadAt)
            assertEquals(fixture.lastReadAt, marker.lastReadAt)
            assertEquals(
                1,
                count(
                    connection,
                    "SELECT COUNT(*) FROM support_messages WHERE thread_id = ${fixture.threadId}",
                ),
            )
        }

    fun assertExactMetadataIndexAndHead() =
        scenario { connection, fixture ->
            val readColumnsBefore = columnNames(connection, SUPPORT_THREAD_READS)
            val lastReadAtBefore = column(connection, SUPPORT_THREAD_READS, LAST_READ_AT)
            val primaryKeyBefore = primaryKeyColumns(connection, SUPPORT_THREAD_READS)
            val foreignKeysBefore = importedKeys(connection, SUPPORT_THREAD_READS)
            val messageIndexesBefore = indexes(connection, SUPPORT_MESSAGES)

            val migrationsExecuted = migrate(expectedVersion)

            assertEquals(1, migrationsExecuted)
            assertEquals(readColumnsBefore + LAST_READ_MESSAGE_ID, columnNames(connection, SUPPORT_THREAD_READS))
            assertEquals(lastReadAtBefore, column(connection, SUPPORT_THREAD_READS, LAST_READ_AT))
            assertEquals(listOf("thread_id", "user_id"), primaryKeyBefore)
            assertEquals(primaryKeyBefore, primaryKeyColumns(connection, SUPPORT_THREAD_READS))
            assertEquals(foreignKeysBefore, importedKeys(connection, SUPPORT_THREAD_READS))
            assertTrue(
                importedKeys(connection, SUPPORT_THREAD_READS).none {
                    it.foreignKeyColumn == LAST_READ_MESSAGE_ID
                },
            )

            assertExactCursorColumn(connection)
            assertNull(readMarker(connection, fixture.threadId, fixture.readerUserId).lastReadMessageId)

            val messageIndexesAfter = indexes(connection, SUPPORT_MESSAGES)
            assertEquals(
                IndexMetadata(unique = false, columns = listOf("thread_id", "id")),
                messageIndexesAfter[UNREAD_INDEX],
            )
            assertEquals(
                messageIndexesBefore,
                messageIndexesAfter - UNREAD_INDEX,
                "the cursor migration may add only the declared unread index to support_messages",
            )

            val flyway = Flyway.configure().dataSource(dataSource).locations(location).load()
            assertEquals(expectedVersion, flyway.info().current().version.version)
            assertTrue(flyway.info().pending().isEmpty(), "$expectedVersion must be the actual migration head")
            assertEquals(listOf(true), targetHistory(connection))
        }

    fun assertIncompatibleCursorColumnFailsClosed() =
        scenario { connection, fixture ->
            connection.createStatement().use { statement ->
                statement.execute(
                    "ALTER TABLE support_thread_reads " +
                        "ADD COLUMN last_read_message_id VARCHAR(32) NOT NULL DEFAULT 'legacy-conflict'",
                )
            }
            val domainBefore = domainSnapshot(connection, fixture)

            assertFailsWith<FlywayException> { migrate(expectedVersion) }

            assertEquals(domainBefore, domainSnapshot(connection, fixture))
            assertFalse(indexes(connection, SUPPORT_MESSAGES).containsKey(UNREAD_INDEX))
            assertFailedHistoryContract(connection)
        }

    fun assertConflictingUnreadIndexFollowsFailureContract() =
        scenario { connection, fixture ->
            connection.createStatement().use { statement ->
                statement.execute(
                    "CREATE INDEX $UNREAD_INDEX ON support_messages (thread_id, created_at)",
                )
            }
            val domainBefore = domainSnapshot(connection, fixture)
            assertFalse(columnNames(connection, SUPPORT_THREAD_READS).contains(LAST_READ_MESSAGE_ID))

            assertFailsWith<FlywayException> { migrate(expectedVersion) }

            assertEquals(
                IndexMetadata(unique = false, columns = listOf("thread_id", "created_at")),
                indexes(connection, SUPPORT_MESSAGES)[UNREAD_INDEX],
            )
            assertEquals(
                domainBefore.marker,
                readMarkerWithoutCursor(connection, fixture.threadId, fixture.readerUserId),
            )
            assertEquals(domainBefore.messageCount, count(connection, "SELECT COUNT(*) FROM support_messages"))
            when (ddlFailureContract) {
                MigrationDdlFailureContract.TRANSACTIONAL ->
                    assertFalse(columnNames(connection, SUPPORT_THREAD_READS).contains(LAST_READ_MESSAGE_ID))
                MigrationDdlFailureContract.NON_TRANSACTIONAL -> {
                    assertExactCursorColumn(connection)
                    assertNull(readMarker(connection, fixture.threadId, fixture.readerUserId).lastReadMessageId)
                }
            }
            assertFailedHistoryContract(connection)
        }

    private fun scenario(assertion: (Connection, Fixture) -> Unit) {
        try {
            migrate(previousVersion)
            dataSource.connection.use { connection -> assertion(connection, seedFixture(connection)) }
        } finally {
            close()
        }
    }

    private fun migrate(targetVersion: String): Int =
        Flyway.configure()
            .dataSource(dataSource)
            .locations(location)
            .target(targetVersion)
            .load()
            .migrate()
            .migrationsExecuted

    private fun seedFixture(connection: Connection): Fixture {
        val readerUserId = 9_910_001L
        val authorUserId = 9_910_002L
        insertUser(connection, readerUserId, "cursor_migration_reader")
        insertUser(connection, authorUserId, "cursor_migration_author")
        val venueId =
            connection.prepareStatement(
                "INSERT INTO venues (name, city, address, status) VALUES (?, ?, ?, 'PUBLISHED')",
                Statement.RETURN_GENERATED_KEYS,
            ).use { statement ->
                statement.setString(1, "Cursor Migration Venue")
                statement.setString(2, "Moscow")
                statement.setString(3, "Cursor street, 1")
                statement.executeUpdate()
                statement.generatedKeys.use { keys ->
                    assertTrue(keys.next())
                    keys.getLong(1)
                }
            }
        val threadId =
            connection.prepareStatement(
                """
                INSERT INTO support_threads (
                    venue_id,
                    guest_user_id,
                    category,
                    status,
                    thread_type,
                    assignee_scope,
                    created_source,
                    title
                )
                VALUES (?, ?, 'OTHER', 'IN_PROGRESS', 'VENUE_CHAT', 'VENUE', 'GUEST_MINIAPP', ?)
                """.trimIndent(),
                Statement.RETURN_GENERATED_KEYS,
            ).use { statement ->
                statement.setLong(1, venueId)
                statement.setLong(2, readerUserId)
                statement.setString(3, "Cursor migration thread")
                statement.executeUpdate()
                statement.generatedKeys.use { keys ->
                    assertTrue(keys.next())
                    keys.getLong(1)
                }
            }
        val messageCreatedAt = Instant.parse("2030-01-10T17:55:00Z")
        connection.prepareStatement(
            """
            INSERT INTO support_messages (
                thread_id,
                author_user_id,
                author_role,
                source,
                text,
                created_at
            )
            VALUES (?, ?, 'VENUE', 'VENUE_MINIAPP', 'Legacy unread message', ?)
            """.trimIndent(),
        ).use { statement ->
            statement.setLong(1, threadId)
            statement.setLong(2, authorUserId)
            statement.setTimestamp(3, Timestamp.from(messageCreatedAt))
            statement.executeUpdate()
        }
        val lastReadAt = Instant.parse("2030-01-10T18:00:00Z")
        connection.prepareStatement(
            "INSERT INTO support_thread_reads (thread_id, user_id, last_read_at) VALUES (?, ?, ?)",
        ).use { statement ->
            statement.setLong(1, threadId)
            statement.setLong(2, readerUserId)
            statement.setTimestamp(3, Timestamp.from(lastReadAt))
            statement.executeUpdate()
        }
        return Fixture(
            threadId = threadId,
            readerUserId = readerUserId,
            lastReadAt = lastReadAt,
        )
    }

    private fun insertUser(
        connection: Connection,
        userId: Long,
        username: String,
    ) {
        connection.prepareStatement(
            "INSERT INTO users (telegram_user_id, username) VALUES (?, ?)",
        ).use { statement ->
            statement.setLong(1, userId)
            statement.setString(2, username)
            statement.executeUpdate()
        }
    }

    private fun assertExactCursorColumn(connection: Connection) {
        val cursor = column(connection, SUPPORT_THREAD_READS, LAST_READ_MESSAGE_ID)
        assertEquals(Types.BIGINT, cursor.jdbcType)
        assertEquals(DatabaseMetaData.columnNullable, cursor.nullable)
        assertNull(cursor.defaultValue)
        assertEquals("NO", cursor.autoIncrement)
        assertEquals("NO", cursor.generated)
    }

    private fun assertFailedHistoryContract(connection: Connection) {
        assertEquals(previousVersion, lastSuccessfulVersion(connection))
        val targetHistory = targetHistory(connection)
        assertTrue(targetHistory.none { it }, "failed migration must never be recorded as successful")
        when (ddlFailureContract) {
            MigrationDdlFailureContract.TRANSACTIONAL -> assertTrue(targetHistory.isEmpty())
            MigrationDdlFailureContract.NON_TRANSACTIONAL -> assertEquals(listOf(false), targetHistory)
        }
    }

    private fun domainSnapshot(
        connection: Connection,
        fixture: Fixture,
    ): DomainSnapshot =
        DomainSnapshot(
            readColumns = columnNames(connection, SUPPORT_THREAD_READS),
            marker = readMarkerWithoutCursor(connection, fixture.threadId, fixture.readerUserId),
            messageIndexes = indexes(connection, SUPPORT_MESSAGES),
            messageCount = count(connection, "SELECT COUNT(*) FROM support_messages"),
        )

    private fun readMarker(
        connection: Connection,
        threadId: Long,
        userId: Long,
    ): ReadMarker =
        connection.prepareStatement(
            """
            SELECT last_read_message_id, last_read_at
            FROM support_thread_reads
            WHERE thread_id = ? AND user_id = ?
            """.trimIndent(),
        ).use { statement ->
            statement.setLong(1, threadId)
            statement.setLong(2, userId)
            statement.executeQuery().use { rows ->
                assertTrue(rows.next())
                ReadMarker(
                    lastReadMessageId = rows.getLong("last_read_message_id").takeUnless { rows.wasNull() },
                    lastReadAt = rows.getTimestamp("last_read_at").toInstant(),
                ).also { assertFalse(rows.next()) }
            }
        }

    private fun readMarkerWithoutCursor(
        connection: Connection,
        threadId: Long,
        userId: Long,
    ): LegacyReadMarker =
        connection.prepareStatement(
            """
            SELECT thread_id, user_id, last_read_at
            FROM support_thread_reads
            WHERE thread_id = ? AND user_id = ?
            """.trimIndent(),
        ).use { statement ->
            statement.setLong(1, threadId)
            statement.setLong(2, userId)
            statement.executeQuery().use { rows ->
                assertTrue(rows.next())
                LegacyReadMarker(
                    threadId = rows.getLong("thread_id"),
                    userId = rows.getLong("user_id"),
                    lastReadAt = rows.getTimestamp("last_read_at").toInstant(),
                ).also { assertFalse(rows.next()) }
            }
        }

    private fun columnNames(
        connection: Connection,
        table: String,
    ): Set<String> =
        connection.metaData.getColumns(null, connection.schema, table, null).use { rows ->
            buildSet {
                while (rows.next()) add(rows.getString("COLUMN_NAME").lowercase())
            }
        }

    private fun column(
        connection: Connection,
        table: String,
        column: String,
    ): ColumnMetadata =
        connection.metaData.getColumns(null, connection.schema, table, column).use { rows ->
            assertTrue(rows.next(), "$table.$column must exist")
            ColumnMetadata(
                jdbcType = rows.getInt("DATA_TYPE"),
                nullable = rows.getInt("NULLABLE"),
                defaultValue = rows.getString("COLUMN_DEF"),
                autoIncrement = rows.getString("IS_AUTOINCREMENT"),
                generated = rows.getString("IS_GENERATEDCOLUMN"),
            ).also { assertFalse(rows.next(), "$table.$column must be unique") }
        }

    private fun primaryKeyColumns(
        connection: Connection,
        table: String,
    ): List<String> =
        connection.metaData.getPrimaryKeys(null, connection.schema, table).use { rows ->
            buildList {
                while (rows.next()) {
                    add(rows.getInt("KEY_SEQ") to rows.getString("COLUMN_NAME").lowercase())
                }
            }.sortedBy { it.first }.map { it.second }
        }

    private fun importedKeys(
        connection: Connection,
        table: String,
    ): List<ForeignKeyMetadata> =
        connection.metaData.getImportedKeys(null, connection.schema, table).use { rows ->
            buildList {
                while (rows.next()) {
                    add(
                        ForeignKeyMetadata(
                            foreignKeyColumn = rows.getString("FKCOLUMN_NAME").lowercase(),
                            primaryKeyTable = rows.getString("PKTABLE_NAME").lowercase(),
                            primaryKeyColumn = rows.getString("PKCOLUMN_NAME").lowercase(),
                            deleteRule = rows.getShort("DELETE_RULE"),
                        ),
                    )
                }
            }.sortedWith(compareBy(ForeignKeyMetadata::foreignKeyColumn, ForeignKeyMetadata::primaryKeyTable))
        }

    private fun indexes(
        connection: Connection,
        table: String,
    ): Map<String, IndexMetadata> {
        data class MutableIndex(val unique: Boolean, val columns: MutableList<Pair<Int, String>>)

        val values = linkedMapOf<String, MutableIndex>()
        connection.metaData.getIndexInfo(null, connection.schema, table, false, false).use { rows ->
            while (rows.next()) {
                val name = rows.getString("INDEX_NAME")?.lowercase() ?: continue
                if (rows.getShort("TYPE") == DatabaseMetaData.tableIndexStatistic) continue
                val column = rows.getString("COLUMN_NAME")?.lowercase() ?: continue
                val mutable = values.getOrPut(name) { MutableIndex(!rows.getBoolean("NON_UNIQUE"), mutableListOf()) }
                mutable.columns += rows.getInt("ORDINAL_POSITION") to column
            }
        }
        return values.mapValues { (_, value) ->
            IndexMetadata(
                unique = value.unique,
                columns = value.columns.sortedBy { it.first }.map { it.second },
            )
        }
    }

    private fun targetHistory(connection: Connection): List<Boolean> =
        connection.prepareStatement(
            "SELECT success FROM flyway_schema_history WHERE version = ? ORDER BY installed_rank",
        ).use { statement ->
            statement.setString(1, expectedVersion)
            statement.executeQuery().use { rows ->
                buildList {
                    while (rows.next()) add(rows.getBoolean("success"))
                }
            }
        }

    private fun lastSuccessfulVersion(connection: Connection): String? =
        connection.createStatement().use { statement ->
            statement.executeQuery(
                """
                SELECT version
                FROM flyway_schema_history
                WHERE success = TRUE AND version IS NOT NULL
                ORDER BY installed_rank DESC
                LIMIT 1
                """.trimIndent(),
            ).use { rows ->
                if (rows.next()) rows.getString("version") else null
            }
        }

    private fun count(
        connection: Connection,
        sql: String,
    ): Int =
        connection.createStatement().use { statement ->
            statement.executeQuery(sql).use { rows ->
                assertTrue(rows.next())
                rows.getInt(1)
            }
        }

    private data class Fixture(
        val threadId: Long,
        val readerUserId: Long,
        val lastReadAt: Instant,
    )

    private data class ReadMarker(
        val lastReadMessageId: Long?,
        val lastReadAt: Instant,
    )

    private data class LegacyReadMarker(
        val threadId: Long,
        val userId: Long,
        val lastReadAt: Instant,
    )

    private data class ColumnMetadata(
        val jdbcType: Int,
        val nullable: Int,
        val defaultValue: String?,
        val autoIncrement: String,
        val generated: String,
    )

    private data class ForeignKeyMetadata(
        val foreignKeyColumn: String,
        val primaryKeyTable: String,
        val primaryKeyColumn: String,
        val deleteRule: Short,
    )

    private data class IndexMetadata(
        val unique: Boolean,
        val columns: List<String>,
    )

    private data class DomainSnapshot(
        val readColumns: Set<String>,
        val marker: LegacyReadMarker,
        val messageIndexes: Map<String, IndexMetadata>,
        val messageCount: Int,
    )

    private companion object {
        const val SUPPORT_THREAD_READS = "support_thread_reads"
        const val SUPPORT_MESSAGES = "support_messages"
        const val LAST_READ_AT = "last_read_at"
        const val LAST_READ_MESSAGE_ID = "last_read_message_id"
        const val UNREAD_INDEX = "idx_support_messages_thread_id"
    }
}
