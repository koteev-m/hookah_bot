package com.hookah.platform.backend.support

import com.hookah.platform.backend.api.ConfigException
import com.hookah.platform.backend.api.InvalidInputException
import com.hookah.platform.backend.test.migrateH2OnboardingFixture
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.h2.jdbcx.JdbcDataSource
import java.sql.Connection
import java.sql.Date
import java.sql.Statement
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CyclicBarrier
import javax.sql.DataSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BookingConversationRepositoryTest {
    private data class StoredThreadState(
        val status: String,
        val updatedAt: Instant,
        val lastMessageAt: Instant?,
    )

    @Test
    fun `repeated and concurrent open resolve one physical booking thread`() =
        withFixture { fixture ->
            runBlocking {
                val first = fixture.repository.createOrFindBookingThread(fixture.bookingId, "Первая")
                val repeated = fixture.repository.createOrFindBookingThread(fixture.bookingId, "Повторная")

                assertNotNull(first)
                assertNotNull(repeated)
                assertEquals(first.id, repeated.id)
                assertEquals("Первая", repeated.title)
                assertEquals(1, fixture.countBookingThreads())

                val secondBookingId = fixture.seedBooking()
                val barrier = CyclicBarrier(2)
                val results =
                    listOf("Конкурент 1", "Конкурент 2").map { title ->
                        async(Dispatchers.IO) {
                            barrier.await()
                            fixture.repository.createOrFindBookingThread(secondBookingId, title)
                        }
                    }.awaitAll()

                assertNotNull(results[0])
                assertNotNull(results[1])
                assertEquals(results[0]?.id, results[1]?.id)
                assertEquals(1, fixture.countBookingThreads(secondBookingId))
            }
        }

    @Test
    fun `booking identity and participants are always derived from the locked booking row`() =
        withFixture { fixture ->
            runBlocking {
                val otherBookingId = fixture.seedBooking()
                val first = fixture.repository.createOrFindBookingThread(fixture.bookingId)
                val second = fixture.repository.createOrFindBookingThread(otherBookingId)

                assertNotNull(first)
                assertNotNull(second)
                assertNotEquals(first.id, second.id)
                assertEquals(fixture.venueId, first.venueId)
                assertEquals(fixture.guestUserId, first.guestUserId)
                assertEquals(fixture.bookingId, first.bookingId)
                assertEquals(otherBookingId, second.bookingId)
            }
        }

    @Test
    fun `exact batch lookup is canonical complete fail closed and read only`() =
        withFixture { fixture ->
            runBlocking {
                val targetThread = assertNotNull(fixture.repository.createOrFindBookingThread(fixture.bookingId))
                val noThreadBookingId = fixture.seedBooking()
                val mutationCountsBefore = fixture.lookupMutationCounts()
                val targetStateBefore = fixture.threadState(targetThread.id)

                val guestLookup =
                    assertNotNull(
                        fixture.repository.lookupGuestBookingThreads(
                            userId = fixture.guestUserId,
                            bookingIds = listOf(fixture.bookingId, noThreadBookingId),
                        ),
                    )
                val venueLookup =
                    assertNotNull(
                        fixture.repository.lookupVenueBookingThreads(
                            venueId = fixture.venueId,
                            viewerUserId = 82002L,
                            bookingIds = listOf(fixture.bookingId, noThreadBookingId),
                        ),
                    )

                listOf(guestLookup, venueLookup).forEach { lookup ->
                    assertEquals(listOf(fixture.bookingId, noThreadBookingId), lookup.map { it.bookingId })
                    assertEquals(targetThread.id, lookup[0].thread?.id)
                    assertNull(lookup[1].thread)
                }
                assertNotNull(
                    fixture.repository.lookupVenueBookingThreads(
                        venueId = fixture.venueId,
                        viewerUserId = 82002L,
                        bookingIds = listOf(fixture.bookingId, noThreadBookingId),
                    ),
                )
                assertEquals(mutationCountsBefore, fixture.lookupMutationCounts())
                assertEquals(targetStateBefore, fixture.threadState(targetThread.id))
                assertEquals(0, fixture.countBookingThreads(noThreadBookingId))

                val foreignGuestId = fixture.guestUserId + 1
                fixture.seedUser(foreignGuestId)
                val foreignVenueId = fixture.seedVenue()
                val foreignBookingId = fixture.seedBooking(foreignVenueId, foreignGuestId)
                assertNull(
                    fixture.repository.lookupGuestBookingThreads(
                        userId = fixture.guestUserId,
                        bookingIds = listOf(fixture.bookingId, foreignBookingId),
                    ),
                )
                assertNull(
                    fixture.repository.lookupVenueBookingThreads(
                        venueId = fixture.venueId,
                        viewerUserId = 82002L,
                        bookingIds = listOf(fixture.bookingId, foreignBookingId),
                    ),
                )

                val corruptBookingId = fixture.seedBooking()
                val corruptThread = assertNotNull(fixture.repository.createOrFindBookingThread(corruptBookingId))
                fixture.updateThreadMetadata(corruptThread.id, venueId = foreignVenueId)
                assertNull(
                    fixture.repository.lookupVenueBookingThreads(
                        venueId = fixture.venueId,
                        viewerUserId = 82002L,
                        bookingIds = listOf(corruptBookingId),
                    ),
                )

                val duplicateBookingId = fixture.seedBooking()
                val duplicateThread = assertNotNull(fixture.repository.createOrFindBookingThread(duplicateBookingId))
                fixture.seedDuplicateBookingThread(duplicateThread.id)
                assertNull(
                    fixture.repository.lookupGuestBookingThreads(
                        userId = fixture.guestUserId,
                        bookingIds = listOf(duplicateBookingId),
                    ),
                )
            }
        }

    @Test
    fun `Telegram delivery retry returns the persisted message without a duplicate`() =
        withFixture { fixture ->
            runBlocking {
                val first =
                    fixture.repository.addBookingMessage(
                        bookingId = fixture.bookingId,
                        authorUserId = fixture.guestUserId,
                        authorRole = SupportMessageAuthorRole.GUEST,
                        source = SupportMessageSource.GUEST_BOT,
                        text = "Первый текст",
                        telegramMessageId = 7001L,
                        expectedGuestUserId = fixture.guestUserId,
                    )
                val retry =
                    fixture.repository.addBookingMessage(
                        bookingId = fixture.bookingId,
                        authorUserId = fixture.guestUserId,
                        authorRole = SupportMessageAuthorRole.GUEST,
                        source = SupportMessageSource.GUEST_BOT,
                        text = "Повтор после потерянного ответа",
                        telegramMessageId = 7001L,
                        expectedGuestUserId = fixture.guestUserId,
                    )
                val distinctDelivery =
                    fixture.repository.addBookingMessage(
                        bookingId = fixture.bookingId,
                        authorUserId = fixture.guestUserId,
                        authorRole = SupportMessageAuthorRole.GUEST,
                        source = SupportMessageSource.GUEST_BOT,
                        text = "Новое сообщение",
                        telegramMessageId = 7002L,
                        expectedGuestUserId = fixture.guestUserId,
                    )

                assertNotNull(first)
                assertNotNull(retry)
                assertNotNull(distinctDelivery)
                assertEquals(first.message.id, retry.message.id)
                assertEquals("Первый текст", retry.message.text)
                assertNotEquals(first.message.id, distinctDelivery.message.id)
                assertEquals(2, fixture.countMessages())
                assertEquals(1, fixture.countBookingThreads())
            }
        }

    @Test
    fun `Guest Bot replay does not rerun transactional notification after booking context changes`() =
        withFixture { fixture ->
            runBlocking {
                var notificationWrites = 0
                val first =
                    assertNotNull(
                        fixture.repository.addBookingMessage(
                            bookingId = fixture.bookingId,
                            authorUserId = fixture.guestUserId,
                            authorRole = SupportMessageAuthorRole.GUEST,
                            source = SupportMessageSource.GUEST_BOT,
                            text = "Первый текст",
                            telegramMessageId = 7_101L,
                            expectedGuestUserId = fixture.guestUserId,
                            guestBotNotificationWriter = { connection, committedWrite ->
                                assertEquals(false, connection.autoCommit)
                                assertEquals(1, committedWrite.thread.booking?.displayNumber)
                                notificationWrites += 1
                            },
                        ),
                    )
                fixture.changeBookingContext()

                val replay =
                    assertNotNull(
                        fixture.repository.addBookingMessage(
                            bookingId = fixture.bookingId,
                            authorUserId = fixture.guestUserId,
                            authorRole = SupportMessageAuthorRole.GUEST,
                            source = SupportMessageSource.GUEST_BOT,
                            text = "Изменённый текст повтора",
                            telegramMessageId = 7_101L,
                            expectedGuestUserId = fixture.guestUserId,
                            guestBotNotificationWriter = { _, _ -> error("Replay must not enqueue a notification") },
                        ),
                    )

                assertTrue(first.created)
                assertEquals(false, replay.created)
                assertEquals(first.message.id, replay.message.id)
                assertEquals("Первый текст", replay.message.text)
                assertEquals(1, notificationWrites)
                assertEquals(1, fixture.countMessages())
            }
        }

    @Test
    fun `Mini App and Telegram sources append to the same authoritative thread`() =
        withFixture { fixture ->
            runBlocking {
                val venueMiniApp =
                    fixture.repository.addBookingMessage(
                        bookingId = fixture.bookingId,
                        authorUserId = 82002L,
                        authorRole = SupportMessageAuthorRole.VENUE,
                        source = SupportMessageSource.VENUE_MINIAPP,
                        text = "Сообщение из кабинета",
                        expectedVenueId = fixture.venueId,
                        clientMessageId = UUID.randomUUID().toString(),
                        notificationWriter = { _, _ -> BookingMessageNotificationWriteResult.WRITTEN },
                    )
                val venueTelegram =
                    fixture.repository.addBookingMessage(
                        bookingId = fixture.bookingId,
                        authorUserId = 82002L,
                        authorRole = SupportMessageAuthorRole.VENUE,
                        source = SupportMessageSource.STAFF_CHAT,
                        text = "Ответ через Telegram",
                        telegramMessageId = 7101L,
                        expectedVenueId = fixture.venueId,
                    )
                val guestTelegram =
                    fixture.repository.addBookingMessage(
                        bookingId = fixture.bookingId,
                        authorUserId = fixture.guestUserId,
                        authorRole = SupportMessageAuthorRole.GUEST,
                        source = SupportMessageSource.GUEST_BOT,
                        text = "Ответ гостя через Telegram",
                        telegramMessageId = 7102L,
                        expectedGuestUserId = fixture.guestUserId,
                    )

                assertNotNull(venueMiniApp)
                assertNotNull(venueTelegram)
                assertNotNull(guestTelegram)
                assertEquals(venueMiniApp.thread.id, venueTelegram.thread.id)
                assertEquals(venueMiniApp.thread.id, guestTelegram.thread.id)
                assertEquals(1, fixture.countBookingThreads())
                assertEquals(3, fixture.countMessages())
            }
        }

    @Test
    fun `rejected Mini App notification rolls back and leaves the same client key reusable`() =
        withFixture { fixture ->
            runBlocking {
                val clientMessageId = UUID.randomUUID().toString()
                val stateBefore = fixture.lookupMutationCounts()
                val failure =
                    runCatching {
                        fixture.repository.addBookingMessage(
                            bookingId = fixture.bookingId,
                            authorUserId = fixture.guestUserId,
                            authorRole = SupportMessageAuthorRole.GUEST,
                            source = SupportMessageSource.GUEST_MINIAPP,
                            text = "Retry after policy change",
                            expectedGuestUserId = fixture.guestUserId,
                            clientMessageId = clientMessageId,
                            notificationWriter = { _, _ -> BookingMessageNotificationWriteResult.REJECTED },
                        )
                    }.exceptionOrNull()

                assertIs<ConfigException>(failure)
                assertEquals(stateBefore, fixture.lookupMutationCounts())
                assertEquals(0, fixture.countBookingThreads())
                assertEquals(0, fixture.countMessages())
                assertEquals(0, fixture.countReads())

                var notificationWrites = 0
                val committed =
                    assertNotNull(
                        fixture.repository.addBookingMessage(
                            bookingId = fixture.bookingId,
                            authorUserId = fixture.guestUserId,
                            authorRole = SupportMessageAuthorRole.GUEST,
                            source = SupportMessageSource.GUEST_MINIAPP,
                            text = "Retry after policy change",
                            expectedGuestUserId = fixture.guestUserId,
                            clientMessageId = clientMessageId,
                            notificationWriter = { _, _ ->
                                notificationWrites += 1
                                BookingMessageNotificationWriteResult.WRITTEN
                            },
                        ),
                    )
                val replay =
                    assertNotNull(
                        fixture.repository.addBookingMessage(
                            bookingId = fixture.bookingId,
                            authorUserId = fixture.guestUserId,
                            authorRole = SupportMessageAuthorRole.GUEST,
                            source = SupportMessageSource.GUEST_MINIAPP,
                            text = "Retry after policy change",
                            expectedGuestUserId = fixture.guestUserId,
                            clientMessageId = clientMessageId,
                            notificationWriter = { _, _ ->
                                notificationWrites += 1
                                BookingMessageNotificationWriteResult.WRITTEN
                            },
                        ),
                    )

                assertTrue(committed.created)
                assertTrue(!replay.created)
                assertEquals(committed.message.id, replay.message.id)
                assertEquals(1, notificationWrites)
                assertEquals(1, fixture.countBookingThreads())
                assertEquals(1, fixture.countMessages())
                assertEquals(0, fixture.countReads())
            }
        }

    @Test
    fun `authoritative writer denies foreign guest and venue expectations before message facts`() =
        withFixture { fixture ->
            runBlocking {
                val thread = assertNotNull(fixture.repository.createOrFindBookingThread(fixture.bookingId))
                val stateBefore = fixture.threadState(thread.id)

                val foreignGuest =
                    fixture.repository.addBookingMessage(
                        bookingId = fixture.bookingId,
                        authorUserId = fixture.guestUserId + 1,
                        authorRole = SupportMessageAuthorRole.GUEST,
                        source = SupportMessageSource.GUEST_MINIAPP,
                        text = "foreign guest",
                        expectedThreadId = thread.id,
                        expectedGuestUserId = fixture.guestUserId + 1,
                        clientMessageId = UUID.randomUUID().toString(),
                        notificationWriter = { _, _ -> BookingMessageNotificationWriteResult.WRITTEN },
                    )
                val foreignVenue =
                    fixture.repository.addBookingMessage(
                        bookingId = fixture.bookingId,
                        authorUserId = 82002L,
                        authorRole = SupportMessageAuthorRole.VENUE,
                        source = SupportMessageSource.VENUE_MINIAPP,
                        text = "foreign venue",
                        expectedThreadId = thread.id,
                        expectedVenueId = fixture.venueId + 1,
                        clientMessageId = UUID.randomUUID().toString(),
                        notificationWriter = { _, _ -> BookingMessageNotificationWriteResult.WRITTEN },
                    )

                assertNull(foreignGuest)
                assertNull(foreignVenue)
                assertEquals(0, fixture.countMessages())
                assertEquals(stateBefore, fixture.threadState(thread.id))
            }
        }

    @Test
    fun `authoritative writer denies stored guest or venue metadata mismatch`() =
        withFixture { fixture ->
            runBlocking {
                val guestThread = assertNotNull(fixture.repository.createOrFindBookingThread(fixture.bookingId))
                val foreignGuestUserId = fixture.guestUserId + 1
                fixture.seedUser(foreignGuestUserId)
                fixture.updateThreadMetadata(guestThread.id, guestUserId = foreignGuestUserId)
                val guestStateBefore = fixture.threadState(guestThread.id)

                val guestMismatch =
                    fixture.repository.addBookingMessage(
                        bookingId = fixture.bookingId,
                        authorUserId = fixture.guestUserId,
                        authorRole = SupportMessageAuthorRole.GUEST,
                        source = SupportMessageSource.GUEST_MINIAPP,
                        text = "guest metadata mismatch",
                        expectedThreadId = guestThread.id,
                        expectedGuestUserId = fixture.guestUserId,
                        clientMessageId = UUID.randomUUID().toString(),
                        notificationWriter = { _, _ -> BookingMessageNotificationWriteResult.WRITTEN },
                    )
                assertNull(guestMismatch)
                assertEquals(guestStateBefore, fixture.threadState(guestThread.id))

                val venueBookingId = fixture.seedBooking()
                val venueThread = assertNotNull(fixture.repository.createOrFindBookingThread(venueBookingId))
                val foreignVenueId = fixture.seedVenue()
                fixture.updateThreadMetadata(venueThread.id, venueId = foreignVenueId)
                val venueStateBefore = fixture.threadState(venueThread.id)

                val venueMismatch =
                    fixture.repository.addBookingMessage(
                        bookingId = venueBookingId,
                        authorUserId = 82002L,
                        authorRole = SupportMessageAuthorRole.VENUE,
                        source = SupportMessageSource.VENUE_MINIAPP,
                        text = "venue metadata mismatch",
                        expectedThreadId = venueThread.id,
                        expectedVenueId = fixture.venueId,
                        clientMessageId = UUID.randomUUID().toString(),
                        notificationWriter = { _, _ -> BookingMessageNotificationWriteResult.WRITTEN },
                    )
                assertNull(venueMismatch)
                assertEquals(venueStateBefore, fixture.threadState(venueThread.id))
                assertEquals(0, fixture.countMessages())
            }
        }

    @Test
    fun `close committed after thread resolve wins before booking message insert`() {
        val dataSource = dataSource()
        migrateH2OnboardingFixture(dataSource)
        val fixture = Fixture(dataSource)
        val bookingId = fixture.seedBooking()
        val thread = runBlocking { assertNotNull(fixture.repository.createOrFindBookingThread(bookingId)) }
        var closeInjected = false
        val repository =
            SupportThreadRepository(dataSource) { checkpoint ->
                if (checkpoint == BookingConversationCheckpoint.AFTER_THREAD_RESOLVE) {
                    fixture.updateThreadStatus(thread.id, SupportThreadStatus.CLOSED)
                    closeInjected = true
                }
            }

        assertFailsWith<InvalidInputException> {
            runBlocking {
                repository.addBookingMessage(
                    bookingId = bookingId,
                    authorUserId = fixture.guestUserId,
                    authorRole = SupportMessageAuthorRole.GUEST,
                    source = SupportMessageSource.GUEST_MINIAPP,
                    text = "must stay closed",
                    expectedThreadId = thread.id,
                    expectedGuestUserId = fixture.guestUserId,
                    clientMessageId = UUID.randomUUID().toString(),
                    notificationWriter = { _, _ -> BookingMessageNotificationWriteResult.WRITTEN },
                )
            }
        }

        assertTrue(closeInjected)
        assertEquals(0, fixture.countMessages())
        assertEquals(SupportThreadStatus.CLOSED.name, fixture.threadState(thread.id).status)
    }

    @Test
    fun `generic addMessage rejects booking threads before insert`() =
        withFixture { fixture ->
            runBlocking {
                val thread = assertNotNull(fixture.repository.createOrFindBookingThread(fixture.bookingId))
                val stateBefore = fixture.threadState(thread.id)

                val failure =
                    assertFailsWith<IllegalStateException> {
                        runBlocking {
                            fixture.repository.addMessage(
                                threadId = thread.id,
                                authorUserId = fixture.guestUserId,
                                authorRole = SupportMessageAuthorRole.GUEST,
                                source = SupportMessageSource.GUEST_MINIAPP,
                                text = "must be rejected",
                            )
                        }
                    }

                assertTrue(failure.message.orEmpty().contains("addBookingMessage"))
                assertEquals(0, fixture.countMessages())
                assertEquals(stateBefore, fixture.threadState(thread.id))
            }
        }

    @Test
    fun `generic addMessage rejects an unsupported future thread type before insert`() =
        withFixture { fixture ->
            runBlocking {
                val threadId = fixture.seedGenericThread(SupportThreadType.SUPPORT_TICKET)
                fixture.setUnsupportedThreadType(threadId, "FUTURE_THREAD")
                val stateBefore = fixture.threadState(threadId)

                val failure =
                    assertFailsWith<IllegalStateException> {
                        runBlocking {
                            fixture.repository.addMessage(
                                threadId = threadId,
                                authorUserId = fixture.guestUserId,
                                authorRole = SupportMessageAuthorRole.GUEST,
                                source = SupportMessageSource.GUEST_MINIAPP,
                                text = "future type must be rejected",
                            )
                        }
                    }

                assertTrue(failure.message.orEmpty().contains("unsupported thread type"))
                assertEquals(0, fixture.countMessages())
                assertEquals(stateBefore, fixture.threadState(threadId))
            }
        }

    @Test
    fun `generic addMessage preserves venue chat and support ticket writes`() =
        withFixture { fixture ->
            runBlocking {
                val venueChatId = fixture.seedGenericThread(SupportThreadType.VENUE_CHAT)
                val supportTicketId = fixture.seedGenericThread(SupportThreadType.SUPPORT_TICKET)

                val venueChatMessage =
                    fixture.repository.addMessage(
                        threadId = venueChatId,
                        authorUserId = fixture.guestUserId,
                        authorRole = SupportMessageAuthorRole.GUEST,
                        source = SupportMessageSource.GUEST_MINIAPP,
                        text = "ordinary venue chat",
                    )
                val supportTicketMessage =
                    fixture.repository.addMessage(
                        threadId = supportTicketId,
                        authorUserId = 82002L,
                        authorRole = SupportMessageAuthorRole.VENUE,
                        source = SupportMessageSource.VENUE_MINIAPP,
                        text = "ordinary support reply",
                    )

                assertEquals(venueChatId, venueChatMessage.threadId)
                assertEquals(supportTicketId, supportTicketMessage.threadId)
                assertEquals(2, fixture.countMessages())
                assertEquals("IN_PROGRESS", fixture.threadState(venueChatId).status)
                assertEquals("WAITING_USER", fixture.threadState(supportTicketId).status)
            }
        }

    @Test
    fun `failure after the first message write rolls back thread message and state`() {
        val dataSource = dataSource()
        migrateH2OnboardingFixture(dataSource)
        val fixture = Fixture(dataSource)
        val bookingId = fixture.seedBooking()
        val thread = runBlocking { assertNotNull(fixture.repository.createOrFindBookingThread(bookingId)) }
        val stateBefore = fixture.threadState(thread.id)
        val repository =
            SupportThreadRepository(dataSource) { checkpoint ->
                if (checkpoint == BookingConversationCheckpoint.AFTER_MESSAGE_WRITE) {
                    error("injected post-message failure")
                }
            }

        val failure =
            runCatching {
                runBlocking {
                    repository.addBookingMessage(
                        bookingId = bookingId,
                        authorUserId = fixture.guestUserId,
                        authorRole = SupportMessageAuthorRole.GUEST,
                        source = SupportMessageSource.GUEST_BOT,
                        text = "Не должно сохраниться",
                        telegramMessageId = 9001L,
                        expectedThreadId = thread.id,
                        expectedGuestUserId = fixture.guestUserId,
                    )
                }
            }.exceptionOrNull()

        assertIs<IllegalStateException>(failure)
        assertEquals(1, fixture.countBookingThreads(bookingId))
        assertEquals(0, fixture.countMessages())
        assertEquals(0, fixture.countReads())
        assertEquals(stateBefore, fixture.threadState(thread.id))
    }

    private fun withFixture(block: (Fixture) -> Unit) {
        val dataSource = dataSource()
        migrateH2OnboardingFixture(dataSource)
        block(Fixture(dataSource).also { it.seedBooking() })
    }

    private fun dataSource(): JdbcDataSource =
        JdbcDataSource().apply {
            setURL(
                "jdbc:h2:mem:booking-conversation-repository-${UUID.randomUUID()};MODE=PostgreSQL;" +
                    "DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1",
            )
            user = "sa"
            password = ""
        }

    private class Fixture(
        private val dataSource: DataSource,
    ) {
        val repository = SupportThreadRepository(dataSource)
        val guestUserId = 81001L
        val venueId: Long
        lateinit var bookingIds: MutableList<Long>
        val bookingId: Long
            get() = bookingIds.first()

        init {
            dataSource.connection.use { connection ->
                insertUser(connection, guestUserId)
                venueId = insertVenue(connection)
            }
        }

        fun seedBooking(
            venueId: Long = this.venueId,
            userId: Long = guestUserId,
        ): Long =
            dataSource.connection.use { connection ->
                val id = insertBooking(connection, venueId, userId)
                if (!::bookingIds.isInitialized) bookingIds = mutableListOf()
                bookingIds += id
                id
            }

        fun countBookingThreads(bookingId: Long = this.bookingId): Int =
            count(
                "SELECT COUNT(*) FROM support_threads " +
                    "WHERE booking_id = $bookingId AND thread_type = 'BOOKING_THREAD'",
            )

        fun countMessages(): Int = count("SELECT COUNT(*) FROM support_messages")

        fun countReads(): Int = count("SELECT COUNT(*) FROM support_thread_reads")

        fun changeBookingContext() {
            dataSource.connection.use { connection ->
                connection.prepareStatement(
                    "UPDATE bookings SET scheduled_at = ?, display_number = ? WHERE id = ?",
                ).use { statement ->
                    statement.setTimestamp(1, Timestamp.from(Instant.parse("2030-01-10T20:45:00Z")))
                    statement.setInt(2, 91)
                    statement.setLong(3, bookingId)
                    assertEquals(1, statement.executeUpdate())
                }
                connection.prepareStatement(
                    "UPDATE users SET username = ? WHERE telegram_user_id = ?",
                ).use { statement ->
                    statement.setString(1, "changed-guest")
                    statement.setLong(2, guestUserId)
                    assertEquals(1, statement.executeUpdate())
                }
            }
        }

        fun lookupMutationCounts(): List<Int> =
            listOf(
                count("SELECT COUNT(*) FROM support_threads"),
                count("SELECT COUNT(*) FROM support_messages"),
                count("SELECT COUNT(*) FROM support_thread_reads"),
                count("SELECT COUNT(*) FROM audit_log"),
                count("SELECT COUNT(*) FROM telegram_outbox"),
            )

        fun seedUser(userId: Long) {
            dataSource.connection.use { connection -> insertUser(connection, userId) }
        }

        fun seedVenue(): Long = dataSource.connection.use(::insertVenue)

        fun seedGenericThread(threadType: SupportThreadType): Long =
            dataSource.connection.use { connection ->
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
                    VALUES (?, ?, 'OTHER', 'IN_PROGRESS', ?, 'VENUE', 'GUEST_MINIAPP', ?)
                    """.trimIndent(),
                    Statement.RETURN_GENERATED_KEYS,
                ).use { statement ->
                    statement.setLong(1, venueId)
                    statement.setLong(2, guestUserId)
                    statement.setString(3, threadType.name)
                    statement.setString(4, "Generic ${threadType.name}")
                    statement.executeUpdate()
                    statement.generatedKeys.use { keys ->
                        assertTrue(keys.next())
                        keys.getLong(1)
                    }
                }
            }

        fun updateThreadMetadata(
            threadId: Long,
            venueId: Long? = null,
            guestUserId: Long? = null,
        ) {
            require(venueId != null || guestUserId != null)
            dataSource.connection.use { connection ->
                venueId?.let { value ->
                    connection
                        .prepareStatement("UPDATE support_threads SET venue_id = ? WHERE id = ?")
                        .use { statement ->
                            statement.setLong(1, value)
                            statement.setLong(2, threadId)
                            assertEquals(1, statement.executeUpdate())
                        }
                }
                guestUserId?.let { value ->
                    connection
                        .prepareStatement("UPDATE support_threads SET guest_user_id = ? WHERE id = ?")
                        .use { statement ->
                            statement.setLong(1, value)
                            statement.setLong(2, threadId)
                            assertEquals(1, statement.executeUpdate())
                        }
                }
            }
        }

        fun seedDuplicateBookingThread(threadId: Long) {
            dataSource.connection.use { connection ->
                connection.createStatement().use { statement ->
                    statement.execute("DROP INDEX uq_support_threads_booking_thread_booking_id")
                }
                connection.prepareStatement(
                    """
                    INSERT INTO support_threads (
                        venue_id,
                        guest_user_id,
                        category,
                        status,
                        booking_id,
                        thread_type,
                        assignee_scope,
                        created_source,
                        title
                    )
                    SELECT venue_id,
                           guest_user_id,
                           category,
                           status,
                           booking_id,
                           thread_type,
                           assignee_scope,
                           created_source,
                           title || ' duplicate'
                    FROM support_threads
                    WHERE id = ?
                    """.trimIndent(),
                ).use { statement ->
                    statement.setLong(1, threadId)
                    assertEquals(1, statement.executeUpdate())
                }
            }
        }

        fun updateThreadStatus(
            threadId: Long,
            status: SupportThreadStatus,
        ) {
            dataSource.connection.use { connection ->
                connection.prepareStatement("UPDATE support_threads SET status = ? WHERE id = ?").use { statement ->
                    statement.setString(1, status.name)
                    statement.setLong(2, threadId)
                    assertEquals(1, statement.executeUpdate())
                }
            }
        }

        fun setUnsupportedThreadType(
            threadId: Long,
            threadType: String,
        ) {
            dataSource.connection.use { connection ->
                connection.createStatement().use { statement ->
                    statement.execute("ALTER TABLE support_threads DROP CONSTRAINT chk_support_threads_thread_type")
                }
                connection
                    .prepareStatement("UPDATE support_threads SET thread_type = ? WHERE id = ?")
                    .use { statement ->
                        statement.setString(1, threadType)
                        statement.setLong(2, threadId)
                        assertEquals(1, statement.executeUpdate())
                    }
            }
        }

        fun threadState(threadId: Long): StoredThreadState =
            dataSource.connection.use { connection ->
                connection.prepareStatement(
                    "SELECT status, updated_at, last_message_at FROM support_threads WHERE id = ?",
                ).use { statement ->
                    statement.setLong(1, threadId)
                    statement.executeQuery().use { rows ->
                        assertTrue(rows.next())
                        StoredThreadState(
                            status = rows.getString("status"),
                            updatedAt = rows.getTimestamp("updated_at").toInstant(),
                            lastMessageAt = rows.getTimestamp("last_message_at")?.toInstant(),
                        )
                    }
                }
            }

        private fun count(sql: String): Int =
            dataSource.connection.use { connection ->
                connection.createStatement().use { statement ->
                    statement.executeQuery(sql).use { resultSet ->
                        resultSet.next()
                        resultSet.getInt(1)
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
                statement.setString(2, "guest$userId")
                statement.executeUpdate()
            }
        }

        private fun insertVenue(connection: Connection): Long =
            connection.prepareStatement(
                "INSERT INTO venues (name, city, address, status) VALUES (?, ?, ?, 'PUBLISHED')",
                Statement.RETURN_GENERATED_KEYS,
            ).use { statement ->
                statement.setString(1, "Booking Integrity Venue")
                statement.setString(2, "Moscow")
                statement.setString(3, "Integrity street, 1")
                statement.executeUpdate()
                statement.generatedKeys.use { keys ->
                    keys.next()
                    keys.getLong(1)
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
                VALUES (?, ?, ?, 2, 'PENDING', ?, ?)
                """.trimIndent(),
                Statement.RETURN_GENERATED_KEYS,
            ).use { statement ->
                statement.setLong(1, venueId)
                statement.setLong(2, userId)
                statement.setTimestamp(3, Timestamp.from(Instant.now().plusSeconds(86_400)))
                statement.setDate(4, Date.valueOf("2030-01-10"))
                statement.setInt(5, if (::bookingIds.isInitialized) bookingIds.size + 1 else 1)
                statement.executeUpdate()
                statement.generatedKeys.use { keys ->
                    keys.next()
                    keys.getLong(1)
                }
            }
    }
}
