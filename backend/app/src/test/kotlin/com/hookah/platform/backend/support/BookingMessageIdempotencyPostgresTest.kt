package com.hookah.platform.backend.support

import com.hookah.platform.backend.api.BookingMessageIdempotencyPayloadMismatchException
import com.hookah.platform.backend.api.DatabaseUnavailableException
import com.hookah.platform.backend.api.InvalidInputException
import com.hookah.platform.backend.telegram.BookingMessageStaffChatNotifier
import com.hookah.platform.backend.telegram.SendMessagePayload
import com.hookah.platform.backend.telegram.TelegramOutboxEnqueuer
import com.hookah.platform.backend.telegram.bookingMessageStaffAlertDedupeKey
import com.hookah.platform.backend.telegram.buildSendMessagePayload
import com.hookah.platform.backend.telegram.db.TelegramOutboxRepository
import com.hookah.platform.backend.test.PostgresTestEnv
import com.zaxxer.hikari.HikariDataSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.sql.Connection
import java.sql.Date
import java.sql.Statement
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class BookingMessageIdempotencyPostgresTest {
    @Test
    fun `guest same key and text commits one message and outbox`() =
        withFixture { fixture ->
            val key = UUID.randomUUID().toString()
            val first = fixture.writeGuest(key, "Guest reply")
            val replay = fixture.writeGuest(key, "Guest reply")

            assertTrue(first.created)
            assertTrue(!replay.created)
            assertEquals(first.message.id, replay.message.id)
            assertEquals(1, fixture.messageCount())
            assertEquals(1, fixture.outboxCount())
            assertEquals(fixture.guestUserId, fixture.singleOutbox().chatId)
            assertEquals("booking-thread-message:${first.message.id}:guest-ack", fixture.singleOutbox().dedupeKey)
        }

    @Test
    fun `guest miniapp message commits one exact privacy-safe staff alert and replay does not duplicate`() =
        withFixture { fixture ->
            fixture.linkCanonicalStaffChat(chatId = -777L, timezone = "Europe/Moscow")
            val key = UUID.randomUUID().toString()
            val first = fixture.writeGuestWithStaffAlert(key, "Guest secret reply")
            val replay = fixture.writeGuestWithStaffAlert(key, "Guest secret reply")

            assertTrue(first.created)
            assertTrue(!replay.created)
            assertEquals(first.message.id, replay.message.id)
            assertEquals(1, fixture.messageCount())
            assertEquals(2, fixture.outboxCount())
            val alert = fixture.outbox(bookingMessageStaffAlertDedupeKey(first.message.id))
            assertEquals(-777L, alert.chatId)
            assertTrue(alert.payloadJson.contains("Новое сообщение по брони"), alert.payloadJson)
            assertTrue(alert.payloadJson.contains("Бронь №1 · 10.01.2030, 21:30"), alert.payloadJson)
            assertTrue(alert.payloadJson.contains("Гость: @message${fixture.guestUserId}"), alert.payloadJson)
            assertTrue(
                alert.payloadJson.contains(
                    "https://miniapp.example/entry?existing=1&mode=venue&venueId=${fixture.venueId}" +
                        "#/messages?threadId=${first.thread.id}",
                ),
                alert.payloadJson,
            )
            assertTrue(!alert.payloadJson.contains("Guest secret reply"), alert.payloadJson)
            assertTrue(!alert.payloadJson.contains("authorUserId"), alert.payloadJson)
        }

    @Test
    fun `guest staff alert safely skips missing chat disabled telegram and missing exact url`() =
        withFixture { fixture ->
            fixture.writeGuestWithStaffAlert(UUID.randomUUID().toString(), "No linked chat")
            assertEquals(1, fixture.outboxCount())

            fixture.linkCanonicalStaffChat(chatId = -777L, timezone = "Europe/Moscow")
            fixture.writeGuestWithStaffAlert(
                clientMessageId = UUID.randomUUID().toString(),
                text = "Telegram disabled",
                telegramActive = false,
            )
            fixture.writeGuestWithStaffAlert(
                clientMessageId = UUID.randomUUID().toString(),
                text = "No exact URL",
                webAppPublicUrl = null,
            )

            assertEquals(3, fixture.messageCount())
            assertEquals(3, fixture.outboxCount())
            assertEquals(0, fixture.staffAlertCount())
        }

    @Test
    fun `unlink committed first makes waiting production message skip the stale staff chat`() =
        withFixture { fixture ->
            coroutineScope {
                fixture.linkCanonicalStaffChat(chatId = -777L, timezone = "Europe/Moscow")
                fixture.openConnection().use { unlinkConnection ->
                    unlinkConnection.autoCommit = false
                    fixture.unlinkCanonicalStaffChat(unlinkConnection)
                    val unlinkPid = fixture.backendPid(unlinkConnection)
                    val write =
                        async(Dispatchers.IO) {
                            fixture.writeGuestWithStaffAlert(
                                UUID.randomUUID().toString(),
                                "Message waiting for unlink",
                            )
                        }

                    try {
                        val evidence = fixture.observeBlockedCaller(unlinkPid)
                        assertEquals(listOf(unlinkPid), evidence.blockingPids)
                        assertEquals("transactionid", evidence.waitingLockType)
                        assertEquals("ShareLock", evidence.waitingLockMode)
                        assertTrue(evidence.blockerLockGranted)
                        unlinkConnection.commit()
                    } catch (failure: Throwable) {
                        unlinkConnection.rollback()
                        throw failure
                    }

                    val committed = write.await()
                    assertTrue(committed.created)
                }
            }

            assertEquals(1, fixture.messageCount())
            assertEquals(1, fixture.outboxCount())
            assertEquals(0, fixture.staffAlertCount())
            assertEquals(0, fixture.outboxCountForChat(-777L))
        }

    @Test
    fun `guest staff alert uses only canonical booking venue chat`() =
        withFixture { fixture ->
            fixture.linkCanonicalStaffChat(chatId = -777L, timezone = "Europe/Moscow")
            fixture.createForeignVenueWithStaffChat(-888L)

            val write = fixture.writeGuestWithStaffAlert(UUID.randomUUID().toString(), "Canonical venue only")
            val alert = fixture.outbox(bookingMessageStaffAlertDedupeKey(write.message.id))

            assertEquals(-777L, alert.chatId)
            assertEquals(0, fixture.outboxCountForChat(-888L))
        }

    @Test
    fun `strict staff alert failure rolls back booking message and guest ack`() =
        withFixture { fixture ->
            fixture.linkCanonicalStaffChat(chatId = -777L, timezone = "Europe/Moscow")
            fixture.rejectVenueStaffAlertOutbox()

            val failure =
                runCatching {
                    fixture.writeGuestWithStaffAlert(UUID.randomUUID().toString(), "Must roll back together")
                }.exceptionOrNull()

            assertIs<DatabaseUnavailableException>(failure)
            assertEquals(0, fixture.bookingThreadCount())
            assertEquals(0, fixture.messageCount())
            assertEquals(0, fixture.outboxCount())
        }

    @Test
    fun `guest bot replay with changed booking context does not regenerate staff alert`() =
        withFixture { fixture ->
            fixture.linkCanonicalStaffChat(chatId = -777L, timezone = "Europe/Moscow")
            val telegramMessageId = 90_001L
            val committed = fixture.writeGuestBotWithStaffAlert(telegramMessageId, "Bot secret reply")
            val alertBefore = fixture.outbox(bookingMessageStaffAlertDedupeKey(committed.message.id))
            fixture.changeBookingAndGuestDisplayContext()

            val replay = fixture.writeGuestBotWithStaffAlert(telegramMessageId, "Changed replay text is ignored")

            assertTrue(committed.created)
            assertTrue(!replay.created)
            assertEquals(committed.message.id, replay.message.id)
            assertEquals(1, fixture.messageCount())
            assertEquals(1, fixture.outboxCount())
            assertEquals(alertBefore, fixture.outbox(bookingMessageStaffAlertDedupeKey(committed.message.id)))
            assertTrue(!alertBefore.payloadJson.contains("Bot secret reply"), alertBefore.payloadJson)
            assertTrue(!alertBefore.payloadJson.contains("Changed replay text"), alertBefore.payloadJson)
        }

    @Test
    fun `venue same key and text commits one message and outbox`() =
        withFixture { fixture ->
            val key = UUID.randomUUID().toString()
            val first = fixture.writeVenue(key, "Venue reply")
            val replay = fixture.writeVenue(key, "Venue reply")

            assertTrue(first.created)
            assertTrue(!replay.created)
            assertEquals(first.message.id, replay.message.id)
            assertEquals(1, fixture.messageCount())
            assertEquals(1, fixture.outboxCount())
            val outbox = fixture.singleOutbox()
            assertEquals(fixture.guestUserId, outbox.chatId)
            assertEquals("booking-thread-message:${first.message.id}:guest-notification", outbox.dedupeKey)
            assertTrue(outbox.payloadJson.contains("Venue reply"))
            assertEquals(0, fixture.staffAlertCount())
        }

    @Test
    fun `same key with different text is conflict without writes`() =
        withFixture { fixture ->
            val key = UUID.randomUUID().toString()
            val first = fixture.writeGuest(key, "Original reply")
            val stateBefore = fixture.threadState(first.thread.id)
            val failure = runCatching { fixture.writeGuest(key, "Different reply") }.exceptionOrNull()

            assertIs<BookingMessageIdempotencyPayloadMismatchException>(failure)
            assertEquals(1, fixture.messageCount())
            assertEquals(1, fixture.outboxCount())
            assertEquals(stateBefore, fixture.threadState(first.thread.id))
        }

    @Test
    fun `concurrent same key exposes the exact PostgreSQL blocker and commits once`() =
        withFixture { fixture ->
            val key = UUID.randomUUID().toString()
            val callerAHoldsTransaction = CountDownLatch(1)
            val releaseCallerA = CountDownLatch(1)
            val callerBStarted = CountDownLatch(1)
            val callerAPid = AtomicInteger()

            coroutineScope {
                val callerA =
                    async(Dispatchers.IO) {
                        fixture.writeGuest(
                            clientMessageId = key,
                            text = "Concurrent reply",
                            transactionBoundary = { connection, _ ->
                                callerAPid.set(fixture.backendPid(connection))
                                callerAHoldsTransaction.countDown()
                                check(releaseCallerA.await(20, TimeUnit.SECONDS)) {
                                    "Timed out waiting to release caller A"
                                }
                            },
                        )
                    }
                assertTrue(callerAHoldsTransaction.await(20, TimeUnit.SECONDS))
                val callerB =
                    async(Dispatchers.IO) {
                        callerBStarted.countDown()
                        fixture.writeGuest(key, "Concurrent reply")
                    }
                assertTrue(callerBStarted.await(20, TimeUnit.SECONDS))
                val evidence =
                    try {
                        fixture.observeBlockedCaller(callerAPid.get())
                    } finally {
                        releaseCallerA.countDown()
                    }
                val results = awaitAll(callerA, callerB)

                assertNotEquals(evidence.observerPid, callerAPid.get())
                assertNotEquals(evidence.observerPid, evidence.waitingPid)
                assertNotEquals(callerAPid.get(), evidence.waitingPid)
                assertEquals(listOf(callerAPid.get()), evidence.blockingPids)
                assertEquals("transactionid", evidence.waitingLockType)
                assertEquals("ShareLock", evidence.waitingLockMode)
                assertEquals("ExclusiveLock", evidence.blockerLockMode)
                assertTrue(evidence.blockerLockGranted)
                assertEquals(results[0].message.id, results[1].message.id)
                assertEquals(1, fixture.bookingThreadCount())
                assertEquals(1, fixture.messageCount())
                assertEquals(1, fixture.outboxCount())
                assertEquals(0, fixture.readMarkerCount())
                assertEquals(0, fixture.auditCount())
                val state = fixture.threadState(results[0].thread.id)
                assertEquals(fixture.messageCreatedAt(results[0].message.id), state.updatedAt)
                assertEquals(state.updatedAt, state.lastMessageAt)
            }
        }

    @Test
    fun `different keys commit two messages and two outbox rows`() =
        withFixture { fixture ->
            val first = fixture.writeGuest(UUID.randomUUID().toString(), "First reply")
            val second = fixture.writeGuest(UUID.randomUUID().toString(), "Second reply")

            assertNotEquals(first.message.id, second.message.id)
            assertEquals(2, fixture.messageCount())
            assertEquals(2, fixture.outboxCount())
        }

    @Test
    fun `outbox insert failure rolls back new thread message and outbox`() =
        withFixture { fixture ->
            assertEquals(0, fixture.bookingThreadCount())
            fixture.rejectBookingOutbox()

            val failure =
                runCatching {
                    fixture.writeGuest(UUID.randomUUID().toString(), "Must roll back")
                }.exceptionOrNull()

            assertIs<DatabaseUnavailableException>(failure)
            assertEquals(0, fixture.bookingThreadCount())
            assertEquals(0, fixture.messageCount())
            assertEquals(0, fixture.outboxCount())
        }

    @Test
    fun `conflicting preexisting outbox envelope rolls back booking message transaction`() =
        withFixture { fixture ->
            val conflicts =
                listOf<(OutboxRow) -> OutboxRow>(
                    { expected -> expected.copy(chatId = expected.chatId + 1) },
                    { expected -> expected.copy(method = "editMessageText") },
                    { expected -> expected.copy(payloadJson = "{}") },
                )

            conflicts.forEachIndexed { index, conflict ->
                lateinit var seeded: OutboxRow
                lateinit var seededState: OutboxState
                val failure =
                    runCatching {
                        fixture.writeGuest(
                            clientMessageId = UUID.randomUUID().toString(),
                            text = "Collision $index",
                            beforeNotification = { notification ->
                                seeded = conflict(fixture.expectedOutbox(notification))
                                fixture.insertOutbox(seeded)
                                seededState = fixture.outboxState(seeded.dedupeKey)
                            },
                        )
                    }.exceptionOrNull()

                assertIs<DatabaseUnavailableException>(failure)
                assertEquals(0, fixture.bookingThreadCount())
                assertEquals(0, fixture.messageCount())
                assertEquals(index + 1, fixture.outboxCount())
                assertEquals(seeded, fixture.outbox(seeded.dedupeKey))
                assertEquals(seededState, fixture.outboxState(seeded.dedupeKey))
            }
        }

    @Test
    fun `identical preexisting outbox envelope is an idempotent replay`() =
        withFixture { fixture ->
            lateinit var seeded: OutboxRow
            lateinit var seededState: OutboxState
            val result =
                fixture.writeGuest(
                    clientMessageId = UUID.randomUUID().toString(),
                    text = "Exact outbox replay",
                    beforeNotification = { notification ->
                        seeded = fixture.expectedOutbox(notification)
                        fixture.insertOutbox(seeded)
                        seededState = fixture.outboxState(seeded.dedupeKey)
                    },
                )

            assertTrue(result.created)
            assertEquals(1, fixture.bookingThreadCount())
            assertEquals(1, fixture.messageCount())
            assertEquals(1, fixture.outboxCount())
            assertEquals(seeded, fixture.outbox(seeded.dedupeKey))
            assertEquals(seededState, fixture.outboxState(seeded.dedupeKey))
        }

    @Test
    fun `canonical equivalent preexisting outbox payload is an idempotent replay`() =
        withFixture { fixture ->
            lateinit var seeded: OutboxRow
            val result =
                fixture.writeGuest(
                    clientMessageId = UUID.randomUUID().toString(),
                    text = "Canonical outbox replay",
                    beforeNotification = { notification ->
                        val expected = fixture.expectedOutbox(notification)
                        seeded = expected.copy(payloadJson = "\n  ${expected.payloadJson}\n")
                        fixture.insertOutbox(seeded)
                    },
                )

            assertTrue(result.created)
            assertEquals(1, fixture.bookingThreadCount())
            assertEquals(1, fixture.messageCount())
            assertEquals(1, fixture.outboxCount())
            assertEquals(seeded, fixture.outbox(seeded.dedupeKey))
        }

    @Test
    fun `retry after recovered outbox failure commits once`() =
        withFixture { fixture ->
            val thread = assertNotNull(fixture.repository.createOrFindBookingThread(fixture.bookingId))
            val stateBefore = fixture.threadState(thread.id)
            val key = UUID.randomUUID().toString()
            fixture.rejectBookingOutbox()
            assertIs<DatabaseUnavailableException>(
                runCatching { fixture.writeVenue(key, "Retry after recovery") }.exceptionOrNull(),
            )
            assertEquals(0, fixture.messageCount())
            assertEquals(0, fixture.outboxCount())
            assertEquals(stateBefore, fixture.threadState(thread.id))

            fixture.allowBookingOutbox()
            val committed = fixture.writeVenue(key, "Retry after recovery")
            val replay = fixture.writeVenue(key, "Retry after recovery")
            assertEquals(committed.message.id, replay.message.id)
            assertEquals(1, fixture.messageCount())
            assertEquals(1, fixture.outboxCount())
        }

    @Test
    fun `lost response replay leaves thread and outbox unchanged`() =
        withFixture { fixture ->
            val key = UUID.randomUUID().toString()
            val committed = fixture.writeGuest(key, "Committed before response loss")
            fixture.closeThread(committed.thread.id)
            val threadState = fixture.threadState(committed.thread.id)
            val outbox = fixture.singleOutbox()

            val replay = fixture.writeGuest(key, "Committed before response loss")
            val mismatchFailure =
                runCatching {
                    fixture.writeGuest(key, "Changed delivery after close")
                }.exceptionOrNull()
            val newKeyFailure =
                runCatching {
                    fixture.writeGuest(UUID.randomUUID().toString(), "New delivery after close")
                }.exceptionOrNull()

            assertEquals(committed.message.id, replay.message.id)
            assertTrue(!replay.created)
            assertIs<BookingMessageIdempotencyPayloadMismatchException>(mismatchFailure)
            assertIs<InvalidInputException>(newKeyFailure)
            assertEquals(threadState, fixture.threadState(committed.thread.id))
            assertEquals(outbox, fixture.singleOutbox())
            assertEquals(1, fixture.messageCount())
            assertEquals(1, fixture.outboxCount())
        }

    private fun withFixture(block: suspend (Fixture) -> Unit) =
        runBlocking {
            val database = PostgresTestEnv.createDatabase()
            PostgresTestEnv.createDataSource(database).use { dataSource ->
                block(Fixture(dataSource))
            }
        }

    private class Fixture(
        private val dataSource: HikariDataSource,
    ) {
        val repository = SupportThreadRepository(dataSource)
        val guestUserId = 891001L
        private val venueUserId = 891002L
        val venueId: Long
        val bookingId: Long
        private val json = Json { ignoreUnknownKeys = true }
        private val outboxEnqueuer =
            TelegramOutboxEnqueuer(
                TelegramOutboxRepository(dataSource),
                json,
            )

        init {
            dataSource.connection.use { connection ->
                insertUser(connection, guestUserId)
                insertUser(connection, venueUserId)
                venueId = insertVenue(connection)
                bookingId = insertBooking(connection, venueId, guestUserId)
            }
        }

        suspend fun writeGuest(
            clientMessageId: String,
            text: String,
            beforeNotification: ((BookingMessageNotificationContext) -> Unit)? = null,
            transactionBoundary: ((Connection, BookingMessageNotificationContext) -> Unit)? = null,
        ): BookingThreadMessageRecord =
            assertNotNull(
                repository.addBookingMessage(
                    bookingId = bookingId,
                    authorUserId = guestUserId,
                    authorRole = SupportMessageAuthorRole.GUEST,
                    source = SupportMessageSource.GUEST_MINIAPP,
                    text = text,
                    expectedGuestUserId = guestUserId,
                    clientMessageId = clientMessageId,
                    notificationWriter = { connection, notification ->
                        beforeNotification?.invoke(notification)
                        transactionBoundary?.invoke(connection, notification)
                        enqueueNotification(connection, notification)
                    },
                ),
            )

        suspend fun writeGuestWithStaffAlert(
            clientMessageId: String,
            text: String,
            telegramActive: Boolean = true,
            webAppPublicUrl: String? = "https://miniapp.example/entry?existing=1#old",
        ): BookingThreadMessageRecord =
            assertNotNull(
                repository.addBookingMessage(
                    bookingId = bookingId,
                    authorUserId = guestUserId,
                    authorRole = SupportMessageAuthorRole.GUEST,
                    source = SupportMessageSource.GUEST_MINIAPP,
                    text = text,
                    expectedGuestUserId = guestUserId,
                    clientMessageId = clientMessageId,
                    notificationWriter = { connection, notification ->
                        enqueueNotification(connection, notification)
                        bookingStaffNotifier(telegramActive, webAppPublicUrl)
                            .enqueueGuestMessageAlertInTransaction(
                                connection = connection,
                                thread = notification.thread,
                                messageId = notification.message.id,
                            )
                    },
                ),
            )

        suspend fun writeGuestBotWithStaffAlert(
            telegramMessageId: Long,
            text: String,
        ): BookingThreadMessageRecord =
            assertNotNull(
                repository.addBookingMessage(
                    bookingId = bookingId,
                    authorUserId = guestUserId,
                    authorRole = SupportMessageAuthorRole.GUEST,
                    source = SupportMessageSource.GUEST_BOT,
                    text = text,
                    telegramMessageId = telegramMessageId,
                    expectedGuestUserId = guestUserId,
                    guestBotNotificationWriter = { connection, committedWrite ->
                        bookingStaffNotifier(telegramActive = true, webAppPublicUrl = "https://miniapp.example/entry")
                            .enqueueGuestMessageAlertInTransaction(
                                connection = connection,
                                thread = committedWrite.thread,
                                messageId = committedWrite.message.id,
                            )
                    },
                ),
            )

        private fun bookingStaffNotifier(
            telegramActive: Boolean,
            webAppPublicUrl: String?,
        ): BookingMessageStaffChatNotifier =
            BookingMessageStaffChatNotifier(
                outboxEnqueuer = outboxEnqueuer,
                isTelegramActive = { telegramActive },
                webAppPublicUrl = { webAppPublicUrl },
            )

        fun backendPid(connection: Connection): Int =
            connection.createStatement().use { statement ->
                statement.executeQuery("SELECT pg_backend_pid()").use { rows ->
                    check(rows.next())
                    rows.getInt(1)
                }
            }

        fun openConnection(): Connection = dataSource.connection

        fun unlinkCanonicalStaffChat(connection: Connection) {
            connection.prepareStatement("UPDATE venues SET staff_chat_id = NULL WHERE id = ?").use { statement ->
                statement.setLong(1, venueId)
                check(statement.executeUpdate() == 1)
            }
        }

        fun observeBlockedCaller(blockerPid: Int): LockEvidence =
            dataSource.connection.use { observer ->
                val observerPid = backendPid(observer)
                val deadlineNanos = System.nanoTime() + TimeUnit.SECONDS.toNanos(20)
                while (System.nanoTime() < deadlineNanos) {
                    observer.prepareStatement(
                        """
                        SELECT activity.pid,
                               pg_blocking_pids(activity.pid) AS blocking_pids,
                               waiting.locktype,
                               waiting.mode,
                               waiting.transactionid::TEXT AS transaction_id,
                               blocker.mode AS blocker_mode,
                               blocker.granted AS blocker_granted
                        FROM pg_stat_activity activity
                        JOIN pg_locks waiting
                          ON waiting.pid = activity.pid
                         AND NOT waiting.granted
                         AND waiting.locktype = 'transactionid'
                         AND waiting.mode = 'ShareLock'
                        JOIN pg_locks blocker
                          ON blocker.pid = ?
                         AND blocker.granted
                         AND blocker.locktype = waiting.locktype
                         AND blocker.transactionid = waiting.transactionid
                        WHERE activity.datname = current_database()
                          AND activity.pid <> ?
                          AND activity.pid <> ?
                          AND ? = ANY(pg_blocking_pids(activity.pid))
                        """.trimIndent(),
                    ).use { statement ->
                        statement.setInt(1, blockerPid)
                        statement.setInt(2, blockerPid)
                        statement.setInt(3, observerPid)
                        statement.setInt(4, blockerPid)
                        statement.executeQuery().use { rows ->
                            if (rows.next()) {
                                val blockingPids =
                                    (rows.getArray("blocking_pids").array as Array<*>)
                                        .map { (it as Number).toInt() }
                                return LockEvidence(
                                    observerPid = observerPid,
                                    waitingPid = rows.getInt("pid"),
                                    blockingPids = blockingPids,
                                    waitingLockType = rows.getString("locktype"),
                                    waitingLockMode = rows.getString("mode"),
                                    transactionId = rows.getString("transaction_id"),
                                    blockerLockMode = rows.getString("blocker_mode"),
                                    blockerLockGranted = rows.getBoolean("blocker_granted"),
                                ).also { check(!rows.next()) }
                            }
                        }
                    }
                    Thread.onSpinWait()
                }
                error("Caller B never exposed an ungranted PostgreSQL lock blocked by PID $blockerPid")
            }

        suspend fun writeVenue(
            clientMessageId: String,
            text: String,
        ): BookingThreadMessageRecord =
            assertNotNull(
                repository.addBookingMessage(
                    bookingId = bookingId,
                    authorUserId = venueUserId,
                    authorRole = SupportMessageAuthorRole.VENUE,
                    source = SupportMessageSource.VENUE_MINIAPP,
                    text = text,
                    expectedVenueId = venueId,
                    clientMessageId = clientMessageId,
                    notificationWriter = ::enqueueNotification,
                ),
            )

        private fun enqueueNotification(
            connection: Connection,
            notification: BookingMessageNotificationContext,
        ) {
            check(notification.recipientChatId == guestUserId)
            outboxEnqueuer.enqueueBookingSendMessageInTransaction(
                connection = connection,
                chatId = notification.recipientChatId,
                text = notificationText(notification),
                dedupeKey = notification.dedupeKey,
            )
        }

        fun expectedOutbox(notification: BookingMessageNotificationContext): OutboxRow =
            OutboxRow(
                chatId = notification.recipientChatId,
                method = "sendMessage",
                payloadJson =
                    json.encodeToString(
                        SendMessagePayload.serializer(),
                        buildSendMessagePayload(
                            json = json,
                            chatId = notification.recipientChatId,
                            text = notificationText(notification),
                            replyMarkup = null,
                        ),
                    ),
                dedupeKey = notification.dedupeKey,
            )

        fun insertOutbox(row: OutboxRow) {
            dataSource.connection.use { connection ->
                connection.prepareStatement(
                    """
                    INSERT INTO telegram_outbox (chat_id, method, payload_json, dedupe_key)
                    VALUES (?, ?, ?, ?)
                    """.trimIndent(),
                ).use { statement ->
                    statement.setLong(1, row.chatId)
                    statement.setString(2, row.method)
                    statement.setString(3, row.payloadJson)
                    statement.setString(4, row.dedupeKey)
                    statement.executeUpdate()
                }
            }
        }

        fun outbox(dedupeKey: String): OutboxRow =
            dataSource.connection.use { connection ->
                connection.prepareStatement(
                    "SELECT chat_id, method, payload_json, dedupe_key FROM telegram_outbox WHERE dedupe_key = ?",
                ).use { statement ->
                    statement.setString(1, dedupeKey)
                    statement.executeQuery().use { rows ->
                        check(rows.next())
                        OutboxRow(
                            chatId = rows.getLong("chat_id"),
                            method = rows.getString("method"),
                            payloadJson = rows.getString("payload_json"),
                            dedupeKey = rows.getString("dedupe_key"),
                        ).also { check(!rows.next()) }
                    }
                }
            }

        fun outboxState(dedupeKey: String): OutboxState =
            dataSource.connection.use { connection ->
                connection.prepareStatement(
                    """
                    SELECT id,
                           chat_id,
                           method,
                           payload_json,
                           dedupe_key,
                           status,
                           attempts,
                           last_error,
                           created_at,
                           processed_at,
                           next_attempt_at
                    FROM telegram_outbox
                    WHERE dedupe_key = ?
                    """.trimIndent(),
                ).use { statement ->
                    statement.setString(1, dedupeKey)
                    statement.executeQuery().use { rows ->
                        check(rows.next())
                        OutboxState(
                            id = rows.getLong("id"),
                            row =
                                OutboxRow(
                                    chatId = rows.getLong("chat_id"),
                                    method = rows.getString("method"),
                                    payloadJson = rows.getString("payload_json"),
                                    dedupeKey = rows.getString("dedupe_key"),
                                ),
                            status = rows.getString("status"),
                            attempts = rows.getInt("attempts"),
                            lastError = rows.getString("last_error"),
                            createdAt = rows.getTimestamp("created_at").toInstant(),
                            processedAt = rows.getTimestamp("processed_at")?.toInstant(),
                            nextAttemptAt = rows.getTimestamp("next_attempt_at")?.toInstant(),
                        ).also { check(!rows.next()) }
                    }
                }
            }

        private fun notificationText(notification: BookingMessageNotificationContext): String =
            when (notification.kind) {
                BookingMessageNotificationKind.GUEST_ACK -> "✅ Ответ отправлен заведению."
                BookingMessageNotificationKind.GUEST_NOTIFICATION ->
                    "Message from ${notification.thread.venueName}: ${notification.message.text}"
            }

        fun rejectBookingOutbox() {
            dataSource.connection.use { connection ->
                connection.createStatement().use { statement ->
                    statement.execute(
                        """
                        ALTER TABLE telegram_outbox
                        ADD CONSTRAINT reject_booking_message_outbox
                        CHECK (dedupe_key IS NULL OR dedupe_key NOT LIKE 'booking-thread-message:%')
                        """.trimIndent(),
                    )
                }
            }
        }

        fun rejectVenueStaffAlertOutbox() {
            dataSource.connection.use { connection ->
                connection.createStatement().use { statement ->
                    statement.execute(
                        """
                        ALTER TABLE telegram_outbox
                        ADD CONSTRAINT reject_booking_message_staff_alert
                        CHECK (dedupe_key IS NULL OR dedupe_key NOT LIKE '%:venue-staff-alert')
                        """.trimIndent(),
                    )
                }
            }
        }

        fun allowBookingOutbox() {
            dataSource.connection.use { connection ->
                connection.createStatement().use { statement ->
                    statement.execute("ALTER TABLE telegram_outbox DROP CONSTRAINT reject_booking_message_outbox")
                }
            }
        }

        fun messageCount(): Int = count("SELECT COUNT(*) FROM support_messages")

        fun outboxCount(): Int = count("SELECT COUNT(*) FROM telegram_outbox")

        fun staffAlertCount(): Int =
            count("SELECT COUNT(*) FROM telegram_outbox WHERE dedupe_key LIKE '%:venue-staff-alert'")

        fun outboxCountForChat(chatId: Long): Int =
            dataSource.connection.use { connection ->
                connection.prepareStatement("SELECT COUNT(*) FROM telegram_outbox WHERE chat_id = ?").use { statement ->
                    statement.setLong(1, chatId)
                    statement.executeQuery().use { rows ->
                        check(rows.next())
                        rows.getInt(1)
                    }
                }
            }

        fun bookingThreadCount(): Int =
            count("SELECT COUNT(*) FROM support_threads WHERE thread_type = 'BOOKING_THREAD'")

        fun readMarkerCount(): Int = count("SELECT COUNT(*) FROM support_thread_reads")

        fun auditCount(): Int = count("SELECT COUNT(*) FROM audit_log")

        fun messageCreatedAt(messageId: Long): Instant =
            dataSource.connection.use { connection ->
                connection.prepareStatement("SELECT created_at FROM support_messages WHERE id = ?").use { statement ->
                    statement.setLong(1, messageId)
                    statement.executeQuery().use { rows ->
                        check(rows.next())
                        rows.getTimestamp(1).toInstant()
                    }
                }
            }

        fun closeThread(threadId: Long) {
            dataSource.connection.use { connection ->
                connection.prepareStatement(
                    "UPDATE support_threads SET status = 'CLOSED' WHERE id = ?",
                ).use { statement ->
                    statement.setLong(1, threadId)
                    check(statement.executeUpdate() == 1)
                }
            }
        }

        fun threadState(threadId: Long): ThreadState =
            dataSource.connection.use { connection ->
                connection.prepareStatement(
                    "SELECT status, updated_at, last_message_at FROM support_threads WHERE id = ?",
                ).use { statement ->
                    statement.setLong(1, threadId)
                    statement.executeQuery().use { rows ->
                        check(rows.next())
                        ThreadState(
                            status = rows.getString("status"),
                            updatedAt = rows.getTimestamp("updated_at").toInstant(),
                            lastMessageAt = rows.getTimestamp("last_message_at")?.toInstant(),
                        )
                    }
                }
            }

        fun singleOutbox(): OutboxRow =
            dataSource.connection.use { connection ->
                connection.createStatement().use { statement ->
                    statement.executeQuery(
                        "SELECT chat_id, method, payload_json, dedupe_key FROM telegram_outbox ORDER BY id",
                    ).use { rows ->
                        check(rows.next())
                        val result =
                            OutboxRow(
                                chatId = rows.getLong("chat_id"),
                                method = rows.getString("method"),
                                payloadJson = rows.getString("payload_json"),
                                dedupeKey = rows.getString("dedupe_key"),
                            )
                        check(!rows.next())
                        result
                    }
                }
            }

        private fun count(sql: String): Int =
            dataSource.connection.use { connection ->
                connection.createStatement().use { statement ->
                    statement.executeQuery(sql).use { rows ->
                        check(rows.next())
                        rows.getInt(1)
                    }
                }
            }

        private fun insertUser(
            connection: Connection,
            userId: Long,
        ) {
            connection.prepareStatement(
                "INSERT INTO users (telegram_user_id, username) VALUES (?, ?)",
            ).use { statement ->
                statement.setLong(1, userId)
                statement.setString(2, "message$userId")
                statement.executeUpdate()
            }
        }

        private fun insertVenue(connection: Connection): Long =
            connection.prepareStatement(
                "INSERT INTO venues (name, city, address, status) VALUES (?, ?, ?, 'PUBLISHED')",
                Statement.RETURN_GENERATED_KEYS,
            ).use { statement ->
                statement.setString(1, "Message Idempotency Venue")
                statement.setString(2, "Moscow")
                statement.setString(3, "Message street, 1")
                statement.executeUpdate()
                statement.generatedKeys.use { keys ->
                    check(keys.next())
                    keys.getLong(1)
                }
            }

        fun linkCanonicalStaffChat(
            chatId: Long,
            timezone: String,
        ) {
            dataSource.connection.use { connection ->
                connection.prepareStatement("UPDATE venues SET staff_chat_id = ? WHERE id = ?").use { statement ->
                    statement.setLong(1, chatId)
                    statement.setLong(2, venueId)
                    check(statement.executeUpdate() == 1)
                }
                connection.prepareStatement(
                    """
                    INSERT INTO venue_settings (venue_id, timezone)
                    VALUES (?, ?)
                    ON CONFLICT (venue_id) DO UPDATE SET timezone = EXCLUDED.timezone
                    """.trimIndent(),
                ).use { statement ->
                    statement.setLong(1, venueId)
                    statement.setString(2, timezone)
                    statement.executeUpdate()
                }
            }
        }

        fun createForeignVenueWithStaffChat(chatId: Long) {
            dataSource.connection.use { connection ->
                val foreignVenueId = insertVenue(connection)
                connection.prepareStatement("UPDATE venues SET staff_chat_id = ? WHERE id = ?").use { statement ->
                    statement.setLong(1, chatId)
                    statement.setLong(2, foreignVenueId)
                    check(statement.executeUpdate() == 1)
                }
            }
        }

        fun changeBookingAndGuestDisplayContext() {
            dataSource.connection.use { connection ->
                connection.prepareStatement(
                    "UPDATE bookings SET display_number = 2, scheduled_at = ? WHERE id = ?",
                ).use { statement ->
                    statement.setTimestamp(1, Timestamp.from(Instant.parse("2030-01-10T20:30:00Z")))
                    statement.setLong(2, bookingId)
                    check(statement.executeUpdate() == 1)
                }
                connection.prepareStatement(
                    "UPDATE users SET username = 'changed-guest' WHERE telegram_user_id = ?",
                ).use { statement ->
                    statement.setLong(1, guestUserId)
                    check(statement.executeUpdate() == 1)
                }
            }
        }

        private fun insertBooking(
            connection: Connection,
            venueId: Long,
            userId: Long,
        ): Long =
            connection.prepareStatement(
                """
                INSERT INTO bookings (
                    venue_id,
                    user_id,
                    scheduled_at,
                    party_size,
                    status,
                    display_date,
                    display_number
                )
                VALUES (?, ?, ?, 2, 'PENDING', ?, 1)
                """.trimIndent(),
                Statement.RETURN_GENERATED_KEYS,
            ).use { statement ->
                statement.setLong(1, venueId)
                statement.setLong(2, userId)
                statement.setTimestamp(3, Timestamp.from(Instant.parse("2030-01-10T18:30:00Z")))
                statement.setDate(4, Date.valueOf("2030-01-10"))
                statement.executeUpdate()
                statement.generatedKeys.use { keys ->
                    check(keys.next())
                    keys.getLong(1)
                }
            }
    }

    private data class ThreadState(
        val status: String,
        val updatedAt: Instant,
        val lastMessageAt: Instant?,
    )

    private data class LockEvidence(
        val observerPid: Int,
        val waitingPid: Int,
        val blockingPids: List<Int>,
        val waitingLockType: String,
        val waitingLockMode: String,
        val transactionId: String,
        val blockerLockMode: String,
        val blockerLockGranted: Boolean,
    )

    private data class OutboxRow(
        val chatId: Long,
        val method: String,
        val payloadJson: String,
        val dedupeKey: String,
    )

    private data class OutboxState(
        val id: Long,
        val row: OutboxRow,
        val status: String,
        val attempts: Int,
        val lastError: String?,
        val createdAt: Instant,
        val processedAt: Instant?,
        val nextAttemptAt: Instant?,
    )
}
