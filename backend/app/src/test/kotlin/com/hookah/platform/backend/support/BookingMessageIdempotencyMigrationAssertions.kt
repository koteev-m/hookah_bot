package com.hookah.platform.backend.support

import org.flywaydb.core.Flyway
import java.sql.Connection
import java.sql.DatabaseMetaData
import java.sql.SQLException
import java.sql.Statement
import java.sql.Types
import java.util.UUID
import javax.sql.DataSource
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

internal class BookingMessageIdempotencyMigrationAssertions(
    private val dataSource: DataSource,
    private val location: String,
    private val previousVersion: String,
    private val expectedVersion: String,
    private val close: () -> Unit = {},
) {
    fun assertLegacyRowsRemainNullable() =
        scenario { connection, fixture ->
            insertLegacyMessage(connection, fixture.threadId, fixture.guestUserId, "GUEST_MINIAPP", null)
            insertLegacyMessage(connection, fixture.threadId, fixture.guestUserId, "GUEST_BOT", 81001L)
            migrate(expectedVersion)

            assertEquals(2, count(connection, "SELECT COUNT(*) FROM support_messages WHERE client_message_id IS NULL"))
            insertMessage(
                connection = connection,
                threadId = fixture.threadId,
                authorUserId = fixture.guestUserId,
                authorRole = "GUEST",
                source = "GUEST_MINIAPP",
                clientMessageId = null,
            )
            assertEquals(3, count(connection, "SELECT COUNT(*) FROM support_messages WHERE client_message_id IS NULL"))
        }

    fun assertMiniAppScopeIsUnique() =
        scenario { connection, fixture ->
            migrate(expectedVersion)
            val secondThreadId = insertThread(connection, fixture.venueId, fixture.guestUserId, "Second thread")
            val key = UUID.randomUUID().toString()
            insertMessage(connection, fixture.threadId, fixture.guestUserId, "GUEST", "GUEST_MINIAPP", key)
            assertFailsWith<SQLException> {
                insertMessage(connection, fixture.threadId, fixture.guestUserId, "GUEST", "GUEST_MINIAPP", key)
            }
            insertMessage(connection, fixture.threadId, fixture.venueUserId, "VENUE", "VENUE_MINIAPP", key)
            insertMessage(connection, fixture.threadId, fixture.venueUserId, "GUEST", "GUEST_MINIAPP", key)
            insertMessage(connection, secondThreadId, fixture.guestUserId, "GUEST", "GUEST_MINIAPP", key)
            insertMessage(
                connection,
                fixture.threadId,
                fixture.guestUserId,
                "GUEST",
                "GUEST_MINIAPP",
                UUID.randomUUID().toString(),
            )
            assertEquals(5, count(connection, "SELECT COUNT(*) FROM support_messages"))
        }

    fun assertClientMessageIdScopeIsConstrained() =
        scenario { connection, fixture ->
            migrate(expectedVersion)
            assertFailsWith<SQLException> {
                insertMessage(
                    connection,
                    fixture.threadId,
                    fixture.venueUserId,
                    "VENUE",
                    "STAFF_CHAT",
                    UUID.randomUUID().toString(),
                )
            }
            assertFailsWith<SQLException> {
                insertMessage(
                    connection,
                    fixture.threadId,
                    null,
                    "GUEST",
                    "GUEST_MINIAPP",
                    UUID.randomUUID().toString(),
                )
            }
            insertMessage(connection, fixture.threadId, fixture.guestUserId, "GUEST", "GUEST_BOT", null)
            assertEquals(1, count(connection, "SELECT COUNT(*) FROM support_messages"))
        }

    fun assertClientMessageIdIsBounded() =
        scenario { connection, fixture ->
            migrate(expectedVersion)
            insertMessage(connection, fixture.threadId, fixture.guestUserId, "GUEST", "GUEST_MINIAPP", "a".repeat(64))
            assertFailsWith<SQLException> {
                insertMessage(
                    connection,
                    fixture.threadId,
                    fixture.guestUserId,
                    "GUEST",
                    "GUEST_MINIAPP",
                    "b".repeat(65),
                )
            }
            assertEquals(64, singleString(connection, "SELECT client_message_id FROM support_messages").length)
        }

    fun assertExactMetadataAndMigrationHead() =
        scenario { connection, fixture ->
            insertLegacyMessage(connection, fixture.threadId, fixture.guestUserId, "GUEST_MINIAPP", null)
            insertLegacyMessage(connection, fixture.threadId, fixture.guestUserId, "GUEST_BOT", 81002L)
            val columnsBefore = columnNames(connection)
            val indexesBefore = indexes(connection)
            val constraintsBefore = constraints(connection)

            migrate(expectedVersion)

            assertEquals(columnsBefore + "client_message_id", columnNames(connection))
            val column = clientMessageIdColumn(connection)
            assertEquals(Types.VARCHAR, column.jdbcType)
            assertEquals(64, column.size)
            assertEquals(DatabaseMetaData.columnNullable, column.nullable)
            assertNull(column.defaultValue)
            assertEquals("NO", column.autoIncrement)
            assertEquals("NO", column.generated)
            assertEquals(2, count(connection, "SELECT COUNT(*) FROM support_messages WHERE client_message_id IS NULL"))

            val indexesAfter = indexes(connection)
            assertEquals(
                setOf(MINIAPP_UNIQUE_INDEX),
                indexesAfter.keys.filterTo(mutableSetOf()) { it == MINIAPP_UNIQUE_INDEX },
            )
            assertEquals(
                IndexMetadata(
                    unique = true,
                    columns = listOf("thread_id", "source", "author_user_id", "client_message_id"),
                ),
                indexesAfter.getValue(MINIAPP_UNIQUE_INDEX),
            )
            assertEquals(
                indexesBefore.values.groupingBy { it }.eachCount(),
                (indexesAfter - MINIAPP_UNIQUE_INDEX).values.groupingBy { it }.eachCount(),
                "H2 may regenerate internal index names during ALTER TABLE, " +
                    "but the semantic index set must be unchanged",
            )

            val constraintsAfter = constraints(connection)
            assertEquals("CHECK", constraintsAfter[CLIENT_MESSAGE_SCOPE_CONSTRAINT])
            assertEquals(
                constraintsBefore.values.groupingBy { it }.eachCount().toMutableMap().apply {
                    this["CHECK"] = getOrDefault("CHECK", 0) + 1
                },
                constraintsAfter.values.groupingBy { it }.eachCount(),
                "only the declared client-message scope CHECK constraint may be added",
            )
            assertExactIndexPredicate(connection)

            val flyway = Flyway.configure().dataSource(dataSource).locations(location).load()
            assertEquals(expectedVersion, flyway.info().current().version.toString())
            assertTrue(flyway.info().pending().isEmpty(), "$expectedVersion must be the actual migration head")
        }

    private fun scenario(assertion: (Connection, Fixture) -> Unit) {
        try {
            migrate(previousVersion)
            dataSource.connection.use { connection ->
                assertion(connection, seedFixture(connection))
            }
        } finally {
            close()
        }
    }

    private fun migrate(targetVersion: String) {
        Flyway.configure()
            .dataSource(dataSource)
            .locations(location)
            .target(targetVersion)
            .load()
            .migrate()
    }

    private fun seedFixture(connection: Connection): Fixture {
        val guestUserId = 881001L
        val venueUserId = 881002L
        insertUser(connection, guestUserId)
        insertUser(connection, venueUserId)
        val venueId =
            connection.prepareStatement(
                "INSERT INTO venues (name, city, address, status) VALUES (?, ?, ?, 'PUBLISHED')",
                Statement.RETURN_GENERATED_KEYS,
            ).use { statement ->
                statement.setString(1, "Message Migration Venue")
                statement.setString(2, "Moscow")
                statement.setString(3, "Migration street, 1")
                statement.executeUpdate()
                statement.generatedKeys.use { keys ->
                    assertTrue(keys.next())
                    keys.getLong(1)
                }
            }
        val threadId = insertThread(connection, venueId, guestUserId, "Migration thread")
        return Fixture(guestUserId, venueUserId, venueId, threadId)
    }

    private fun insertUser(
        connection: Connection,
        userId: Long,
    ) {
        connection.prepareStatement("INSERT INTO users (telegram_user_id, username) VALUES (?, ?)").use { statement ->
            statement.setLong(1, userId)
            statement.setString(2, "migration$userId")
            statement.executeUpdate()
        }
    }

    private fun insertThread(
        connection: Connection,
        venueId: Long,
        guestUserId: Long,
        title: String,
    ): Long =
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
            VALUES (?, ?, 'OTHER', 'IN_PROGRESS', 'SUPPORT_TICKET', 'VENUE', 'GUEST_MINIAPP', ?)
            """.trimIndent(),
            Statement.RETURN_GENERATED_KEYS,
        ).use { statement ->
            statement.setLong(1, venueId)
            statement.setLong(2, guestUserId)
            statement.setString(3, title)
            statement.executeUpdate()
            statement.generatedKeys.use { keys ->
                assertTrue(keys.next())
                keys.getLong(1)
            }
        }

    private fun insertLegacyMessage(
        connection: Connection,
        threadId: Long,
        authorUserId: Long,
        source: String,
        telegramMessageId: Long?,
    ) {
        connection.prepareStatement(
            """
            INSERT INTO support_messages (
                thread_id,
                author_user_id,
                author_role,
                source,
                text,
                telegram_message_id
            )
            VALUES (?, ?, 'GUEST', ?, 'Legacy message', ?)
            """.trimIndent(),
        ).use { statement ->
            statement.setLong(1, threadId)
            statement.setLong(2, authorUserId)
            statement.setString(3, source)
            if (telegramMessageId == null) {
                statement.setNull(4, java.sql.Types.BIGINT)
            } else {
                statement.setLong(4, telegramMessageId)
            }
            statement.executeUpdate()
        }
    }

    private fun insertMessage(
        connection: Connection,
        threadId: Long,
        authorUserId: Long?,
        authorRole: String,
        source: String,
        clientMessageId: String?,
    ) {
        connection.prepareStatement(
            """
            INSERT INTO support_messages (
                thread_id,
                author_user_id,
                author_role,
                source,
                text,
                client_message_id
            )
            VALUES (?, ?, ?, ?, 'Message', ?)
            """.trimIndent(),
        ).use { statement ->
            statement.setLong(1, threadId)
            if (authorUserId == null) {
                statement.setNull(2, java.sql.Types.BIGINT)
            } else {
                statement.setLong(2, authorUserId)
            }
            statement.setString(3, authorRole)
            statement.setString(4, source)
            if (clientMessageId == null) {
                statement.setNull(5, java.sql.Types.VARCHAR)
            } else {
                statement.setString(5, clientMessageId)
            }
            statement.executeUpdate()
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

    private fun singleString(
        connection: Connection,
        sql: String,
    ): String =
        connection.createStatement().use { statement ->
            statement.executeQuery(sql).use { rows ->
                assertTrue(rows.next())
                rows.getString(1)
            }
        }

    private fun columnNames(connection: Connection): Set<String> =
        connection.metaData.getColumns(null, connection.schema, "support_messages", null).use { rows ->
            buildSet {
                while (rows.next()) add(rows.getString("COLUMN_NAME").lowercase())
            }
        }

    private fun clientMessageIdColumn(connection: Connection): ColumnMetadata =
        connection.metaData.getColumns(null, connection.schema, "support_messages", "client_message_id").use { rows ->
            assertTrue(rows.next())
            ColumnMetadata(
                jdbcType = rows.getInt("DATA_TYPE"),
                size = rows.getInt("COLUMN_SIZE"),
                nullable = rows.getInt("NULLABLE"),
                defaultValue = rows.getString("COLUMN_DEF"),
                autoIncrement = rows.getString("IS_AUTOINCREMENT"),
                generated = rows.getString("IS_GENERATEDCOLUMN"),
            ).also { assertFalse(rows.next()) }
        }

    private fun indexes(connection: Connection): Map<String, IndexMetadata> {
        data class MutableIndex(val unique: Boolean, val columns: MutableList<Pair<Int, String>>)

        val values = linkedMapOf<String, MutableIndex>()
        connection.metaData.getIndexInfo(null, connection.schema, "support_messages", false, false).use { rows ->
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

    private fun constraints(connection: Connection): Map<String, String> =
        connection.prepareStatement(
            """
            SELECT constraint_name, constraint_type
            FROM information_schema.table_constraints
            WHERE LOWER(table_schema) = LOWER(CURRENT_SCHEMA())
              AND LOWER(table_name) = 'support_messages'
            """.trimIndent(),
        ).use { statement ->
            statement.executeQuery().use { rows ->
                buildMap {
                    while (rows.next()) {
                        put(
                            rows.getString("constraint_name").lowercase(),
                            rows.getString("constraint_type").uppercase(),
                        )
                    }
                }
            }
        }

    private fun assertExactIndexPredicate(connection: Connection) {
        val product = connection.metaData.databaseProductName.lowercase()
        if (product.contains("postgresql")) {
            val predicate =
                connection.prepareStatement(
                    """
                    SELECT pg_get_expr(indexes.indpred, indexes.indrelid)
                    FROM pg_catalog.pg_index indexes
                    JOIN pg_catalog.pg_class index_relation ON index_relation.oid = indexes.indexrelid
                    JOIN pg_catalog.pg_namespace namespace ON namespace.oid = index_relation.relnamespace
                    WHERE index_relation.relname = ?
                      AND namespace.nspname = CURRENT_SCHEMA()
                    """.trimIndent(),
                ).use { statement ->
                    statement.setString(1, MINIAPP_UNIQUE_INDEX)
                    statement.executeQuery().use { rows ->
                        assertTrue(rows.next())
                        rows.getString(1).also { assertFalse(rows.next()) }
                    }
                }
            assertEquals("(client_message_id IS NOT NULL)", predicate)
        } else {
            assertTrue(product.contains("h2"))
            connection.prepareStatement(
                """
                SELECT is_generated
                FROM information_schema.columns
                WHERE LOWER(table_schema) = LOWER(CURRENT_SCHEMA())
                  AND LOWER(table_name) = 'support_messages'
                  AND LOWER(column_name) = 'client_message_id'
                """.trimIndent(),
            ).use { statement ->
                statement.executeQuery().use { rows ->
                    assertTrue(rows.next())
                    assertEquals("NEVER", rows.getString(1))
                    assertFalse(rows.next())
                }
            }
        }
    }

    private data class Fixture(
        val guestUserId: Long,
        val venueUserId: Long,
        val venueId: Long,
        val threadId: Long,
    )

    private data class ColumnMetadata(
        val jdbcType: Int,
        val size: Int,
        val nullable: Int,
        val defaultValue: String?,
        val autoIncrement: String,
        val generated: String,
    )

    private data class IndexMetadata(
        val unique: Boolean,
        val columns: List<String>,
    )

    private companion object {
        const val MINIAPP_UNIQUE_INDEX = "uq_support_messages_miniapp_client_message"
        const val CLIENT_MESSAGE_SCOPE_CONSTRAINT = "chk_support_messages_client_message_id_scope"
    }
}
