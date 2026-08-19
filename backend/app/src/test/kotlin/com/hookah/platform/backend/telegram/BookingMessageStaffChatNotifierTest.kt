package com.hookah.platform.backend.telegram

import com.hookah.platform.backend.support.SupportAssigneeScope
import com.hookah.platform.backend.support.SupportBookingContextRecord
import com.hookah.platform.backend.support.SupportThreadCategory
import com.hookah.platform.backend.support.SupportThreadCreatedSource
import com.hookah.platform.backend.support.SupportThreadRecord
import com.hookah.platform.backend.support.SupportThreadStatus
import com.hookah.platform.backend.support.SupportThreadType
import com.hookah.platform.backend.telegram.db.TelegramOutboxRepository
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.h2.jdbcx.JdbcDataSource
import java.sql.Connection
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import javax.sql.DataSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BookingMessageStaffChatNotifierTest {
    private val json = Json { ignoreUnknownKeys = false }

    @Test
    fun `linked staff chat receives one fact-only alert with canonical label and exact thread URL`() =
        withFixture { fixture ->
            fixture.seedVenue(venueId = 10L, staffChatId = -777L, timezone = "Europe/Moscow")
            fixture.seedVenue(venueId = 11L, staffChatId = -888L, timezone = "Europe/Moscow")
            val notifier = fixture.notifier()
            val thread = bookingThread(guestDisplayName = "Алексей")

            fixture.commit { connection ->
                notifier.enqueueGuestMessageAlertInTransaction(connection, thread, messageId = 7_001L)
                notifier.enqueueGuestMessageAlertInTransaction(connection, thread, messageId = 7_001L)
            }

            val alert = fixture.singleOutbox()
            assertEquals(-777L, alert.chatId)
            assertEquals("sendMessage", alert.method)
            assertEquals(bookingMessageStaffAlertDedupeKey(7_001L), alert.dedupeKey)
            val payload = json.decodeFromString(SendMessagePayload.serializer(), alert.payloadJson)
            assertEquals(
                """
                💬 Новое сообщение по брони
                Бронь №42 · 03.04.2026, 21:00
                Гость: Алексей
                Откройте переписку в Venue Mode.
                """.trimIndent(),
                payload.text,
            )
            val button =
                payload.replyMarkup
                    ?.jsonObject
                    ?.getValue("inline_keyboard")
                    ?.jsonArray
                    ?.single()
                    ?.jsonArray
                    ?.single()
                    ?.jsonObject
            assertEquals("Открыть переписку", button?.getValue("text")?.jsonPrimitive?.content)
            assertEquals(
                "https://miniapp.example/entry?existing=1&mode=venue&venueId=10#/messages?threadId=501",
                button?.getValue("url")?.jsonPrimitive?.content,
            )
            assertFalse(alert.payloadJson.contains("Сверхсекретный текст сообщения"))
            assertFalse(alert.payloadJson.contains("918273"))
            assertFalse(alert.payloadJson.contains("authorUserId"))
            assertEquals(0, fixture.outboxCountForChat(-888L))
        }

    @Test
    fun `staff alert participates in the caller transaction`() =
        withFixture { fixture ->
            fixture.seedVenue(venueId = 10L, staffChatId = -777L, timezone = "Europe/Moscow")
            fixture.dataSource.connection.use { connection ->
                connection.autoCommit = false
                fixture.notifier().enqueueGuestMessageAlertInTransaction(
                    connection,
                    bookingThread(),
                    messageId = 7_002L,
                )
                assertEquals(1, fixture.outboxCount(connection))
                connection.rollback()
            }

            assertEquals(0, fixture.outboxCount())
        }

    @Test
    fun `staff alert target is serialized with unlink and later messages skip the old chat`() =
        withFixture { fixture ->
            fixture.seedVenue(venueId = 10L, staffChatId = -777L, timezone = "Europe/Moscow")
            val unlinkStarted = CountDownLatch(1)
            val executor = Executors.newSingleThreadExecutor()
            try {
                fixture.dataSource.connection.use { alertConnection ->
                    alertConnection.autoCommit = false
                    fixture.notifier().enqueueGuestMessageAlertInTransaction(
                        alertConnection,
                        bookingThread(),
                        messageId = 7_008L,
                    )
                    val unlinkFuture =
                        executor.submit<Int> {
                            unlinkStarted.countDown()
                            fixture.dataSource.connection.use { unlinkConnection ->
                                unlinkConnection.autoCommit = false
                                fixture.unlinkStaffChat(10L, unlinkConnection)
                                unlinkConnection.commit()
                                1
                            }
                        }
                    assertTrue(unlinkStarted.await(5, TimeUnit.SECONDS))
                    assertFailsWith<TimeoutException> { unlinkFuture.get(250, TimeUnit.MILLISECONDS) }
                    alertConnection.commit()
                    assertEquals(1, unlinkFuture.get(5, TimeUnit.SECONDS))
                }

                fixture.commit { connection ->
                    fixture.notifier().enqueueGuestMessageAlertInTransaction(
                        connection,
                        bookingThread(),
                        messageId = 7_009L,
                    )
                }
                assertEquals(1, fixture.outboxCount())
                assertEquals(-777L, fixture.singleOutbox().chatId)
            } finally {
                executor.shutdownNow()
                assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS))
            }
        }

    @Test
    fun `disabled missing URL wrong type and unlinked staff chat skip without false queued alert`() =
        withFixture { fixture ->
            fixture.seedVenue(venueId = 10L, staffChatId = -777L, timezone = "Europe/Moscow")
            fixture.commit { connection ->
                fixture.notifier(telegramActive = false).enqueueGuestMessageAlertInTransaction(
                    connection,
                    bookingThread(),
                    messageId = 7_003L,
                )
                fixture.notifier(webAppPublicUrl = null).enqueueGuestMessageAlertInTransaction(
                    connection,
                    bookingThread(),
                    messageId = 7_004L,
                )
                fixture.notifier().enqueueGuestMessageAlertInTransaction(
                    connection,
                    bookingThread().copy(threadType = SupportThreadType.SUPPORT_TICKET),
                    messageId = 7_005L,
                )
                fixture.unlinkStaffChat(10L, connection)
                fixture.notifier().enqueueGuestMessageAlertInTransaction(
                    connection,
                    bookingThread(),
                    messageId = 7_006L,
                )
            }

            assertEquals(0, fixture.outboxCount())
        }

    @Test
    fun `fallback booking identity and guest label are stable in the product timezone`() =
        withFixture { fixture ->
            fixture.seedVenue(venueId = 10L, staffChatId = -777L, timezone = null)
            val thread = bookingThread(displayNumber = null, guestDisplayName = "  ")

            fixture.commit { connection ->
                fixture.notifier().enqueueGuestMessageAlertInTransaction(connection, thread, messageId = 7_007L)
            }

            val payload = json.decodeFromString(SendMessagePayload.serializer(), fixture.singleOutbox().payloadJson)
            assertEquals(
                """
                💬 Новое сообщение по брони
                Бронь #77 · 03.04.2026, 21:00
                Гость: Гость
                Откройте переписку в Venue Mode.
                """.trimIndent(),
                payload.text,
            )
        }

    private fun withFixture(block: (Fixture) -> Unit) {
        val dataSource =
            JdbcDataSource().apply {
                setURL(
                    "jdbc:h2:mem:booking-message-staff-chat-${UUID.randomUUID()};MODE=PostgreSQL;" +
                        "DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
                )
                user = "sa"
                password = ""
            }
        createSchema(dataSource)
        block(Fixture(dataSource, json))
    }

    private fun createSchema(dataSource: DataSource) {
        dataSource.connection.use { connection ->
            connection.createStatement().use { statement ->
                statement.execute(
                    """
                    CREATE TABLE venues (
                        id BIGINT PRIMARY KEY,
                        staff_chat_id BIGINT
                    )
                    """.trimIndent(),
                )
                statement.execute(
                    """
                    CREATE TABLE venue_settings (
                        venue_id BIGINT PRIMARY KEY,
                        timezone VARCHAR(64)
                    )
                    """.trimIndent(),
                )
                statement.execute(
                    """
                    CREATE TABLE telegram_outbox (
                        id BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
                        chat_id BIGINT NOT NULL,
                        method VARCHAR(64) NOT NULL,
                        payload_json CLOB NOT NULL,
                        dedupe_key VARCHAR(180),
                        CONSTRAINT uq_booking_message_staff_alert_dedupe UNIQUE (dedupe_key)
                    )
                    """.trimIndent(),
                )
            }
        }
    }

    private fun bookingThread(
        displayNumber: Int? = 42,
        guestDisplayName: String? = "Алексей",
    ): SupportThreadRecord =
        SupportThreadRecord(
            id = 501L,
            venueId = 10L,
            venueName = "Тестовая кальянная",
            venueTimezone = "Europe/Moscow",
            guestDisplayName = guestDisplayName,
            guestUserId = 918_273L,
            threadType = SupportThreadType.BOOKING_THREAD,
            assigneeScope = SupportAssigneeScope.VENUE,
            createdSource = SupportThreadCreatedSource.BOOKING_FLOW,
            category = SupportThreadCategory.BOOKING,
            status = SupportThreadStatus.IN_PROGRESS,
            bookingId = 77L,
            orderId = null,
            tableSessionId = null,
            title = "legacy title",
            lastMessageAt = Instant.parse("2026-04-03T18:05:00Z"),
            createdAt = Instant.parse("2026-04-03T18:00:00Z"),
            updatedAt = Instant.parse("2026-04-03T18:05:00Z"),
            booking =
                SupportBookingContextRecord(
                    bookingId = 77L,
                    displayNumber = displayNumber,
                    scheduledAt = Instant.parse("2026-04-03T18:00:00Z"),
                    partySize = 3,
                    status = "PENDING",
                ),
        )

    private class Fixture(
        val dataSource: DataSource,
        json: Json,
    ) {
        private val outboxEnqueuer = TelegramOutboxEnqueuer(TelegramOutboxRepository(dataSource), json)

        fun notifier(
            telegramActive: Boolean = true,
            webAppPublicUrl: String? = "https://miniapp.example/entry?existing=1#old-fragment",
        ): BookingMessageStaffChatNotifier =
            BookingMessageStaffChatNotifier(
                outboxEnqueuer = outboxEnqueuer,
                isTelegramActive = { telegramActive },
                webAppPublicUrl = { webAppPublicUrl },
            )

        fun seedVenue(
            venueId: Long,
            staffChatId: Long?,
            timezone: String?,
        ) {
            dataSource.connection.use { connection ->
                connection.prepareStatement(
                    "INSERT INTO venues (id, staff_chat_id) VALUES (?, ?)",
                ).use { statement ->
                    statement.setLong(1, venueId)
                    if (staffChatId == null) {
                        statement.setNull(2, java.sql.Types.BIGINT)
                    } else {
                        statement.setLong(2, staffChatId)
                    }
                    statement.executeUpdate()
                }
                if (timezone != null) {
                    connection.prepareStatement(
                        "INSERT INTO venue_settings (venue_id, timezone) VALUES (?, ?)",
                    ).use { statement ->
                        statement.setLong(1, venueId)
                        statement.setString(2, timezone)
                        statement.executeUpdate()
                    }
                }
            }
        }

        fun unlinkStaffChat(
            venueId: Long,
            connection: Connection,
        ) {
            connection.prepareStatement("UPDATE venues SET staff_chat_id = NULL WHERE id = ?").use { statement ->
                statement.setLong(1, venueId)
                assertEquals(1, statement.executeUpdate())
            }
        }

        fun commit(block: (Connection) -> Unit) {
            dataSource.connection.use { connection ->
                connection.autoCommit = false
                try {
                    block(connection)
                    connection.commit()
                } catch (failure: Throwable) {
                    connection.rollback()
                    throw failure
                }
            }
        }

        fun singleOutbox(): StoredOutbox =
            dataSource.connection.use { connection ->
                connection.prepareStatement(
                    "SELECT chat_id, method, payload_json, dedupe_key FROM telegram_outbox",
                ).use { statement ->
                    statement.executeQuery().use { rows ->
                        assertEquals(true, rows.next())
                        val result =
                            StoredOutbox(
                                chatId = rows.getLong("chat_id"),
                                method = rows.getString("method"),
                                payloadJson = rows.getString("payload_json"),
                                dedupeKey = rows.getString("dedupe_key"),
                            )
                        assertEquals(false, rows.next())
                        result
                    }
                }
            }

        fun outboxCount(): Int = dataSource.connection.use(::outboxCount)

        fun outboxCount(connection: Connection): Int =
            connection.createStatement().use { statement ->
                statement.executeQuery("SELECT COUNT(*) FROM telegram_outbox").use { rows ->
                    rows.next()
                    rows.getInt(1)
                }
            }

        fun outboxCountForChat(chatId: Long): Int =
            dataSource.connection.use { connection ->
                connection.prepareStatement("SELECT COUNT(*) FROM telegram_outbox WHERE chat_id = ?").use { statement ->
                    statement.setLong(1, chatId)
                    statement.executeQuery().use { rows ->
                        rows.next()
                        rows.getInt(1)
                    }
                }
            }
    }

    private data class StoredOutbox(
        val chatId: Long,
        val method: String,
        val payloadJson: String,
        val dedupeKey: String,
    )
}
