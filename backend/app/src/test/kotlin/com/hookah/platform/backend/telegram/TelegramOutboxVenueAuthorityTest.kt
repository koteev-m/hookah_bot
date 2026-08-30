package com.hookah.platform.backend.telegram

import com.hookah.platform.backend.telegram.db.BindResult
import com.hookah.platform.backend.telegram.db.TelegramOutboxRepository
import com.hookah.platform.backend.telegram.db.UnlinkResult
import com.hookah.platform.backend.telegram.db.VenueRepository
import com.hookah.platform.backend.test.PostgresTestEnv
import io.ktor.server.config.MapApplicationConfig
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Test
import java.sql.Connection
import java.sql.DriverManager
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TelegramOutboxVenueAuthorityTest {
    @Test
    fun `product venue enqueue rejects stale staff chat after relink`() =
        runBlocking {
            val database = PostgresTestEnv.createDatabase()
            val dataSource = PostgresTestEnv.createDataSource(database)
            val policy = productPolicy()
            val repository = TelegramOutboxRepository(dataSource, policy)
            val enqueuer = TelegramOutboxEnqueuer(repository, Json { ignoreUnknownKeys = true }, policy)
            val venueRepository = VenueRepository(dataSource)
            val actorUserId = 920_001L
            val staffChatId = -100_920_001L

            try {
                val (firstVenueId, secondVenueId) =
                    DriverManager.getConnection(database.jdbcUrl, database.user, database.password).use { connection ->
                        connection.prepareStatement(
                            "INSERT INTO users (telegram_user_id, first_name) VALUES (?, 'Owner')",
                        ).use { statement ->
                            statement.setLong(1, actorUserId)
                            statement.executeUpdate()
                        }
                        insertVenue(connection, "First", staffChatId) to insertVenue(connection, "Second", null)
                    }

                assertEquals(
                    TelegramOutboxEnqueueOutcome.SKIPPED_TRAFFIC_POLICY,
                    enqueuer.enqueueSendMessage(staffChatId, "generic group payload"),
                )
                DriverManager.getConnection(database.jdbcUrl, database.user, database.password).use { connection ->
                    connection.prepareStatement(
                        "SELECT COUNT(*) FROM telegram_outbox WHERE chat_id = ?",
                    ).use { statement ->
                        statement.setLong(1, staffChatId)
                        statement.executeQuery().use { resultSet ->
                            assertTrue(resultSet.next())
                            assertEquals(0L, resultSet.getLong(1))
                        }
                    }
                }

                assertEquals(
                    TelegramOutboxEnqueueOutcome.ENQUEUED,
                    enqueuer.enqueueVenueSendMessage(firstVenueId, staffChatId, "queued before relink"),
                )
                assertTrue(
                    venueRepository.unlinkStaffChatByChatId(
                        chatId = staffChatId,
                        userId = actorUserId,
                        expectedVenueId = firstVenueId,
                    ) is UnlinkResult.Success,
                )
                assertTrue(venueRepository.bindStaffChat(secondVenueId, staffChatId, actorUserId) is BindResult.Success)

                assertEquals(
                    TelegramOutboxEnqueueOutcome.SKIPPED_TRAFFIC_POLICY,
                    enqueuer.enqueueVenueSendMessage(firstVenueId, staffChatId, "stale venue payload"),
                )
                assertEquals(
                    TelegramOutboxEnqueueOutcome.ENQUEUED,
                    enqueuer.enqueueVenueSendMessage(secondVenueId, staffChatId, "fresh venue payload"),
                )
                assertEquals(
                    TelegramOutboxEnqueueOutcome.ENQUEUED,
                    enqueuer.enqueueVenueEditMessageText(
                        venueId = secondVenueId,
                        chatId = staffChatId,
                        messageId = 77L,
                        text = "fresh venue edit",
                    ),
                )

                DriverManager.getConnection(database.jdbcUrl, database.user, database.password).use { connection ->
                    connection.prepareStatement(
                        "SELECT status, payload_json FROM telegram_outbox WHERE chat_id = ? ORDER BY id",
                    ).use { statement ->
                        statement.setLong(1, staffChatId)
                        statement.executeQuery().use { resultSet ->
                            assertTrue(resultSet.next())
                            assertEquals("FAILED", resultSet.getString("status"))
                            assertTrue(resultSet.getString("payload_json").contains("queued before relink"))
                            assertTrue(resultSet.next())
                            assertEquals("NEW", resultSet.getString("status"))
                            assertTrue(resultSet.getString("payload_json").contains("fresh venue payload"))
                            assertTrue(resultSet.next())
                            assertEquals("NEW", resultSet.getString("status"))
                            assertTrue(resultSet.getString("payload_json").contains("fresh venue edit"))
                            assertFalse(resultSet.next())
                        }
                    }
                    connection.prepareStatement(
                        "SELECT COUNT(*) FROM telegram_outbox WHERE payload_json LIKE '%stale venue payload%'",
                    ).use { statement ->
                        statement.executeQuery().use { resultSet ->
                            assertTrue(resultSet.next())
                            assertEquals(0L, resultSet.getLong(1))
                        }
                    }
                }
            } finally {
                dataSource.close()
            }
        }

    @Test
    fun `allowlist venue enqueue preserves static chat policy without database venue authority`() =
        runBlocking {
            val database = PostgresTestEnv.createDatabase()
            val dataSource = PostgresTestEnv.createDataSource(database)
            val allowedPrivateChatId = 930_001L
            val allowedStaffChatId = -100_930_001L
            val deniedStaffChatId = -100_930_002L
            val policy =
                TelegramTrafficPolicy.from(
                    MapApplicationConfig(
                        "telegram.trafficPolicy" to "ALLOWLIST",
                        "telegram.allowedUserIds" to allowedPrivateChatId.toString(),
                        "telegram.allowedChatIds" to "$allowedPrivateChatId,$allowedStaffChatId",
                    ),
                    appEnv = "staging",
                )
            val repository = TelegramOutboxRepository(dataSource, policy)
            val enqueuer = TelegramOutboxEnqueuer(repository, Json { ignoreUnknownKeys = true }, policy)

            try {
                assertEquals(
                    TelegramOutboxEnqueueOutcome.ENQUEUED,
                    enqueuer.enqueueVenueSendMessage(venueId = 999L, chatId = allowedStaffChatId, text = "allowed"),
                )
                assertEquals(
                    TelegramOutboxEnqueueOutcome.ENQUEUED,
                    enqueuer.enqueueVenueEditMessageText(
                        venueId = 999L,
                        chatId = allowedStaffChatId,
                        messageId = 12L,
                        text = "allowed edit",
                    ),
                )
                assertEquals(
                    TelegramOutboxEnqueueOutcome.SKIPPED_TRAFFIC_POLICY,
                    enqueuer.enqueueVenueSendMessage(venueId = 999L, chatId = deniedStaffChatId, text = "denied"),
                )

                DriverManager.getConnection(database.jdbcUrl, database.user, database.password).use { connection ->
                    connection.prepareStatement(
                        "SELECT COUNT(*) FROM telegram_outbox WHERE chat_id = ?",
                    ).use { statement ->
                        statement.setLong(1, allowedStaffChatId)
                        statement.executeQuery().use { resultSet ->
                            assertTrue(resultSet.next())
                            assertEquals(2L, resultSet.getLong(1))
                        }
                    }
                    connection.prepareStatement(
                        "SELECT COUNT(*) FROM telegram_outbox WHERE chat_id = ?",
                    ).use { statement ->
                        statement.setLong(1, deniedStaffChatId)
                        statement.executeQuery().use { resultSet ->
                            assertTrue(resultSet.next())
                            assertEquals(0L, resultSet.getLong(1))
                        }
                    }
                }
            } finally {
                dataSource.close()
            }
        }

    private fun productPolicy(): TelegramTrafficPolicy =
        TelegramTrafficPolicy.from(
            MapApplicationConfig("telegram.trafficPolicy" to "PRODUCT"),
            appEnv = "staging",
        )

    private fun insertVenue(
        connection: Connection,
        name: String,
        staffChatId: Long?,
    ): Long =
        connection.prepareStatement(
            """
            INSERT INTO venues (name, status, staff_chat_id)
            VALUES (?, 'PUBLISHED', ?)
            RETURNING id
            """.trimIndent(),
        ).use { statement ->
            statement.setString(1, name)
            if (staffChatId == null) {
                statement.setNull(2, java.sql.Types.BIGINT)
            } else {
                statement.setLong(2, staffChatId)
            }
            statement.executeQuery().use { resultSet ->
                assertTrue(resultSet.next())
                resultSet.getLong("id")
            }
        }
}
