package com.hookah.platform.backend.support

import com.hookah.platform.backend.test.PostgresTestDatabase
import com.hookah.platform.backend.test.PostgresTestEnv
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.sql.Connection
import java.sql.Date
import java.sql.DriverManager
import java.sql.Statement
import java.sql.Timestamp
import java.time.Instant
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class SupportThreadReadConcurrencyPostgresTest {
    @Test
    fun `concurrent production reads serialize on parents and create one marker`() =
        runBlocking {
            val database = PostgresTestEnv.createDatabase()
            PostgresTestEnv.createDataSource(database).use { dataSource ->
                val fixture = seedFixture(dataSource.connection)
                val setupRepository = SupportThreadRepository(dataSource)
                val thread =
                    assertNotNull(
                        setupRepository.createOrFindBookingThread(fixture.bookingId),
                    )
                val firstMessage =
                    assertNotNull(
                        setupRepository.addBookingMessage(
                            bookingId = fixture.bookingId,
                            authorUserId = fixture.guestUserId,
                            authorRole = SupportMessageAuthorRole.GUEST,
                            source = SupportMessageSource.GUEST_BOT,
                            text = "First cursor message",
                            telegramMessageId = 92_001L,
                            expectedThreadId = thread.id,
                            expectedGuestUserId = fixture.guestUserId,
                        ),
                    ).message
                val access = SupportThreadReadAccess.Guest(fixture.guestUserId)
                assertEquals(SupportThreadReadResult.MARKED, setupRepository.markThreadRead(thread.id, access))
                val markerBefore =
                    dataSource.connection.use { connection ->
                        assertNotNull(readMarker(connection, thread.id, fixture.guestUserId))
                    }
                assertEquals(firstMessage.id, markerBefore.lastReadMessageId)
                val secondMessage =
                    assertNotNull(
                        setupRepository.addBookingMessage(
                            bookingId = fixture.bookingId,
                            authorUserId = fixture.guestUserId,
                            authorRole = SupportMessageAuthorRole.GUEST,
                            source = SupportMessageSource.GUEST_BOT,
                            text = "Second cursor message",
                            telegramMessageId = 92_002L,
                            expectedThreadId = thread.id,
                            expectedGuestUserId = fixture.guestUserId,
                        ),
                    ).message
                val firstMarkerWritten = CountDownLatch(1)
                val releaseFirst = CountDownLatch(1)
                val markerWrites = AtomicInteger()
                val repository =
                    SupportThreadRepository(
                        dataSource = dataSource,
                        supportThreadReadCheckpoint = { checkpoint ->
                            if (
                                checkpoint == SupportThreadReadCheckpoint.AFTER_MARKER_WRITE &&
                                markerWrites.incrementAndGet() == 1
                            ) {
                                firstMarkerWritten.countDown()
                                assertTrue(releaseFirst.await(WAIT_TIMEOUT_SECONDS, TimeUnit.SECONDS))
                            }
                        },
                    )
                directConnection(database).use { observer ->
                    val first = async(Dispatchers.IO) { repository.markThreadRead(thread.id, access) }
                    assertTrue(firstMarkerWritten.await(WAIT_TIMEOUT_SECONDS, TimeUnit.SECONDS))
                    val second = async(Dispatchers.IO) { repository.markThreadRead(thread.id, access) }

                    try {
                        awaitLockWaiter(
                            observer = observer,
                            queryPattern = "%from bookings%for update%",
                            operation = second,
                        )
                    } finally {
                        releaseFirst.countDown()
                    }

                    val results =
                        withTimeout(TimeUnit.SECONDS.toMillis(WAIT_TIMEOUT_SECONDS)) {
                            awaitAll(first, second)
                        }
                    assertEquals(
                        listOf(SupportThreadReadResult.MARKED, SupportThreadReadResult.MARKED),
                        results,
                    )
                    assertEquals(1, countReads(observer, thread.id, fixture.guestUserId))
                    assertEquals(2, markerWrites.get())
                    val markerAfter = assertNotNull(readMarker(observer, thread.id, fixture.guestUserId))
                    assertEquals(secondMessage.id, markerAfter.lastReadMessageId)
                    assertTrue(
                        requireNotNull(markerAfter.lastReadMessageId) >=
                            requireNotNull(markerBefore.lastReadMessageId),
                    )
                    assertTrue(!markerAfter.lastReadAt.isBefore(markerBefore.lastReadAt))
                }
            }
        }

    @Test
    fun `writer with earlier transaction timestamp commits after reader and remains unread by message id`() =
        runBlocking {
            val database = PostgresTestEnv.createDatabase()
            PostgresTestEnv.createDataSource(database).use { dataSource ->
                val fixture = seedFixture(dataSource.connection)
                val setupRepository = SupportThreadRepository(dataSource)
                val thread =
                    assertNotNull(
                        setupRepository.createOrFindBookingThread(fixture.bookingId),
                    )
                val baselineMessage =
                    assertNotNull(
                        setupRepository.addBookingMessage(
                            bookingId = fixture.bookingId,
                            authorUserId = fixture.guestUserId,
                            authorRole = SupportMessageAuthorRole.GUEST,
                            source = SupportMessageSource.GUEST_BOT,
                            text = "Committed before the read snapshot",
                            telegramMessageId = 93_001L,
                            expectedThreadId = thread.id,
                            expectedGuestUserId = fixture.guestUserId,
                        ),
                    ).message
                val unaffectedThreadId =
                    seedSupportThread(
                        dataSource.connection,
                        fixture,
                        threadType = SupportThreadType.VENUE_CHAT,
                    )
                val unaffectedMessage =
                    setupRepository.addMessage(
                        threadId = unaffectedThreadId,
                        authorUserId = fixture.guestUserId,
                        authorRole = SupportMessageAuthorRole.GUEST,
                        source = SupportMessageSource.GUEST_MINIAPP,
                        text = "Another thread message",
                    )
                val venueAccess =
                    SupportThreadReadAccess.Venue(
                        userId = fixture.venueOwnerUserId,
                        venueId = fixture.venueId,
                    )
                assertEquals(
                    SupportThreadReadResult.MARKED,
                    setupRepository.markThreadRead(unaffectedThreadId, venueAccess),
                )
                val unaffectedMarkerBefore =
                    dataSource.connection.use { connection ->
                        assertNotNull(readMarker(connection, unaffectedThreadId, fixture.venueOwnerUserId))
                    }
                assertEquals(unaffectedMessage.id, unaffectedMarkerBefore.lastReadMessageId)

                val writerBeforeBookingLock = CountDownLatch(1)
                val releaseWriterToBookingLock = CountDownLatch(1)
                val readerMarkerWritten = CountDownLatch(1)
                val releaseReaderCommit = CountDownLatch(1)
                val writerCheckpointCount = AtomicInteger()
                val writerApplicationName = writerApplicationName("cursor_writer", database)
                val readerApplicationName = writerApplicationName("cursor_reader", database)

                writerDataSource(database, writerApplicationName).use { writerPool ->
                    writerDataSource(database, readerApplicationName).use { readerPool ->
                        val writerRepository =
                            SupportThreadRepository(
                                dataSource = writerPool,
                                bookingConversationCheckpoint = { checkpoint ->
                                    if (
                                        checkpoint == BookingConversationCheckpoint.AFTER_INITIAL_THREAD_LOOKUP &&
                                        writerCheckpointCount.incrementAndGet() == 1
                                    ) {
                                        writerBeforeBookingLock.countDown()
                                        check(
                                            releaseWriterToBookingLock.await(
                                                WAIT_TIMEOUT_SECONDS,
                                                TimeUnit.SECONDS,
                                            ),
                                        )
                                    }
                                },
                            )
                        val readerRepository =
                            SupportThreadRepository(
                                dataSource = readerPool,
                                supportThreadReadCheckpoint = { checkpoint ->
                                    if (checkpoint == SupportThreadReadCheckpoint.AFTER_MARKER_WRITE) {
                                        readerMarkerWritten.countDown()
                                        check(
                                            releaseReaderCommit.await(
                                                WAIT_TIMEOUT_SECONDS,
                                                TimeUnit.SECONDS,
                                            ),
                                        )
                                    }
                                },
                            )

                        directConnection(database).use { observer ->
                            val writer =
                                async(Dispatchers.IO) {
                                    writerRepository.addBookingMessage(
                                        bookingId = fixture.bookingId,
                                        authorUserId = fixture.guestUserId,
                                        authorRole = SupportMessageAuthorRole.GUEST,
                                        source = SupportMessageSource.GUEST_BOT,
                                        text = "Started before reader, committed after reader",
                                        telegramMessageId = 93_002L,
                                        expectedThreadId = thread.id,
                                        expectedGuestUserId = fixture.guestUserId,
                                    )
                                }
                            assertTrue(
                                writerBeforeBookingLock.await(WAIT_TIMEOUT_SECONDS, TimeUnit.SECONDS),
                                "Writer did not pause after establishing its transaction timestamp",
                            )
                            val writerPid = onlyApplicationBackendPid(observer, writerApplicationName)
                            val writerTransactionStartedAt = transactionStartedAt(observer, writerPid)
                            awaitDatabaseClockAfter(observer, writerTransactionStartedAt)

                            val reader =
                                async(Dispatchers.IO) {
                                    readerRepository.getVenueThreadAndMarkRead(
                                        venueId = fixture.venueId,
                                        threadId = thread.id,
                                        viewerUserId = fixture.venueOwnerUserId,
                                        allowedThreadTypes = setOf(SupportThreadType.BOOKING_THREAD),
                                    )
                                }
                            assertTrue(
                                readerMarkerWritten.await(WAIT_TIMEOUT_SECONDS, TimeUnit.SECONDS),
                                "Reader did not write its locked-snapshot cursor",
                            )
                            val readerPid = onlyApplicationBackendPid(observer, readerApplicationName)
                            val readerTransactionStartedAt = transactionStartedAt(observer, readerPid)
                            assertTrue(
                                writerTransactionStartedAt.isBefore(readerTransactionStartedAt),
                                "Writer transaction must start first: writer=$writerTransactionStartedAt, " +
                                    "reader=$readerTransactionStartedAt",
                            )

                            val lockEvidence =
                                try {
                                    releaseWriterToBookingLock.countDown()
                                    awaitExactLockEdge(
                                        observer = observer,
                                        writerPid = writerPid,
                                        readerPid = readerPid,
                                        operation = writer,
                                    )
                                } finally {
                                    releaseWriterToBookingLock.countDown()
                                    releaseReaderCommit.countDown()
                                }
                            assertEquals(writerPid, lockEvidence.waitingPid)
                            assertEquals(listOf(readerPid), lockEvidence.blockingPids)
                            assertEquals("transactionid", lockEvidence.waitingLockType)
                            assertEquals("ShareLock", lockEvidence.waitingLockMode)
                            assertFalse(lockEvidence.waitingLockGranted)
                            assertNotNull(lockEvidence.transactionId)
                            assertEquals("ExclusiveLock", lockEvidence.blockerLockMode)
                            assertTrue(lockEvidence.blockerLockGranted)

                            val readerDetail =
                                assertNotNull(
                                    withTimeout(TimeUnit.SECONDS.toMillis(WAIT_TIMEOUT_SECONDS)) {
                                        reader.await()
                                    },
                                )
                            val committedWrite =
                                assertNotNull(
                                    withTimeout(TimeUnit.SECONDS.toMillis(WAIT_TIMEOUT_SECONDS)) {
                                        writer.await()
                                    },
                                )
                            assertEquals(listOf(baselineMessage.id), readerDetail.messages.map { it.id })
                            assertTrue(committedWrite.message.id > baselineMessage.id)

                            val marker =
                                assertNotNull(
                                    readMarker(observer, thread.id, fixture.venueOwnerUserId),
                                )
                            assertEquals(baselineMessage.id, marker.lastReadMessageId)
                            val committedCreatedAt = messageCreatedAt(observer, committedWrite.message.id)
                            assertTrue(
                                committedCreatedAt.isBefore(marker.lastReadAt),
                                "The late commit must reproduce the timestamp loss window: " +
                                    "createdAt=$committedCreatedAt, lastReadAt=${marker.lastReadAt}",
                            )
                            assertEquals(
                                1,
                                setupRepository.countVenueConversationUnread(
                                    fixture.venueId,
                                    fixture.venueOwnerUserId,
                                ),
                            )

                            val ownMessage =
                                assertNotNull(
                                    setupRepository.addBookingMessage(
                                        bookingId = fixture.bookingId,
                                        authorUserId = fixture.venueOwnerUserId,
                                        authorRole = SupportMessageAuthorRole.VENUE,
                                        source = SupportMessageSource.STAFF_CHAT,
                                        text = "Own venue message above the cursor",
                                        telegramMessageId = 93_003L,
                                        expectedThreadId = thread.id,
                                        expectedVenueId = fixture.venueId,
                                    ),
                                ).message
                            assertTrue(ownMessage.id > committedWrite.message.id)
                            assertEquals(
                                1,
                                setupRepository.countVenueConversationUnread(
                                    fixture.venueId,
                                    fixture.venueOwnerUserId,
                                ),
                                "Own messages above the cursor must not increment unread",
                            )
                            assertEquals(
                                unaffectedMarkerBefore,
                                assertNotNull(
                                    readMarker(observer, unaffectedThreadId, fixture.venueOwnerUserId),
                                ),
                                "Reading and writing the booking thread must not mutate another marker",
                            )

                            assertNotNull(
                                setupRepository.getVenueThreadAndMarkRead(
                                    venueId = fixture.venueId,
                                    threadId = thread.id,
                                    viewerUserId = fixture.venueOwnerUserId,
                                    allowedThreadTypes = setOf(SupportThreadType.BOOKING_THREAD),
                                ),
                            )
                            val cursorBeforeRollback =
                                dataSource.connection.use { connection ->
                                    assertNotNull(
                                        readMarker(connection, thread.id, fixture.venueOwnerUserId),
                                    )
                                }
                            assertEquals(ownMessage.id, cursorBeforeRollback.lastReadMessageId)
                            val messageAfterCursor =
                                assertNotNull(
                                    setupRepository.addBookingMessage(
                                        bookingId = fixture.bookingId,
                                        authorUserId = fixture.guestUserId,
                                        authorRole = SupportMessageAuthorRole.GUEST,
                                        source = SupportMessageSource.GUEST_BOT,
                                        text = "Must stay unread after marker rollback",
                                        telegramMessageId = 93_004L,
                                        expectedThreadId = thread.id,
                                        expectedGuestUserId = fixture.guestUserId,
                                    ),
                                ).message
                            assertTrue(
                                messageAfterCursor.id > requireNotNull(cursorBeforeRollback.lastReadMessageId),
                            )
                            val failingReader =
                                SupportThreadRepository(
                                    dataSource = dataSource,
                                    supportThreadReadCheckpoint = { checkpoint ->
                                        if (checkpoint == SupportThreadReadCheckpoint.AFTER_MARKER_WRITE) {
                                            error("injected cursor rollback")
                                        }
                                    },
                                )
                            val rollbackFailure =
                                runCatching {
                                    failingReader.getVenueThreadAndMarkRead(
                                        venueId = fixture.venueId,
                                        threadId = thread.id,
                                        viewerUserId = fixture.venueOwnerUserId,
                                        allowedThreadTypes = setOf(SupportThreadType.BOOKING_THREAD),
                                    )
                                }.exceptionOrNull()
                            assertIs<IllegalStateException>(rollbackFailure)
                            assertEquals(
                                cursorBeforeRollback,
                                assertNotNull(
                                    readMarker(observer, thread.id, fixture.venueOwnerUserId),
                                ),
                                "A failed detail read must restore the prior cursor and metadata timestamp",
                            )
                            assertEquals(
                                1,
                                setupRepository.countVenueConversationUnread(
                                    fixture.venueId,
                                    fixture.venueOwnerUserId,
                                ),
                            )
                        }
                    }
                }
            }
        }

    @Test
    fun `equal message timestamps are ordered and cleared by message id cursor`() =
        runBlocking {
            val database = PostgresTestEnv.createDatabase()
            PostgresTestEnv.createDataSource(database).use { dataSource ->
                val fixture = seedFixture(dataSource.connection)
                val repository = SupportThreadRepository(dataSource)
                val thread = assertNotNull(repository.createOrFindBookingThread(fixture.bookingId))
                val equalCreatedAt = Instant.parse("2031-02-03T04:05:06Z")
                val firstMessage =
                    assertNotNull(
                        repository.addBookingMessage(
                            bookingId = fixture.bookingId,
                            authorUserId = fixture.guestUserId,
                            authorRole = SupportMessageAuthorRole.GUEST,
                            source = SupportMessageSource.GUEST_BOT,
                            text = "First equal-timestamp message",
                            telegramMessageId = 94_001L,
                            expectedThreadId = thread.id,
                            expectedGuestUserId = fixture.guestUserId,
                        ),
                    ).message
                dataSource.connection.use { connection ->
                    updateMessageCreatedAt(connection, firstMessage.id, equalCreatedAt)
                }
                val venueAccess =
                    SupportThreadReadAccess.Venue(
                        userId = fixture.venueOwnerUserId,
                        venueId = fixture.venueId,
                    )
                assertNotNull(
                    repository.getVenueThreadAndMarkRead(
                        venueId = fixture.venueId,
                        threadId = thread.id,
                        viewerUserId = fixture.venueOwnerUserId,
                        allowedThreadTypes = setOf(SupportThreadType.BOOKING_THREAD),
                    ),
                )
                dataSource.connection.use { connection ->
                    assertEquals(
                        firstMessage.id,
                        assertNotNull(
                            readMarker(connection, thread.id, venueAccess.userId),
                        ).lastReadMessageId,
                    )
                }

                val secondMessage =
                    assertNotNull(
                        repository.addBookingMessage(
                            bookingId = fixture.bookingId,
                            authorUserId = fixture.guestUserId,
                            authorRole = SupportMessageAuthorRole.GUEST,
                            source = SupportMessageSource.GUEST_BOT,
                            text = "Second equal-timestamp message",
                            telegramMessageId = 94_002L,
                            expectedThreadId = thread.id,
                            expectedGuestUserId = fixture.guestUserId,
                        ),
                    ).message
                dataSource.connection.use { connection ->
                    updateMessageCreatedAt(connection, secondMessage.id, equalCreatedAt)
                    assertEquals(equalCreatedAt, messageCreatedAt(connection, firstMessage.id))
                    assertEquals(equalCreatedAt, messageCreatedAt(connection, secondMessage.id))
                }
                assertTrue(secondMessage.id > firstMessage.id)
                assertEquals(
                    1,
                    repository.countVenueConversationUnread(fixture.venueId, fixture.venueOwnerUserId),
                )

                val refreshed =
                    assertNotNull(
                        repository.getVenueThreadAndMarkRead(
                            venueId = fixture.venueId,
                            threadId = thread.id,
                            viewerUserId = fixture.venueOwnerUserId,
                            allowedThreadTypes = setOf(SupportThreadType.BOOKING_THREAD),
                        ),
                    )
                assertEquals(listOf(firstMessage.id, secondMessage.id), refreshed.messages.map { it.id })
                dataSource.connection.use { connection ->
                    assertEquals(
                        secondMessage.id,
                        assertNotNull(
                            readMarker(connection, thread.id, venueAccess.userId),
                        ).lastReadMessageId,
                    )
                }
                assertEquals(
                    0,
                    repository.countVenueConversationUnread(fixture.venueId, fixture.venueOwnerUserId),
                )
            }
        }

    @Test
    fun `production read locks every authoritative parent before child marker DML for all actors and scopes`() =
        runBlocking {
            val database = PostgresTestEnv.createDatabase()
            PostgresTestEnv.createDataSource(database).use { dataSource ->
                val fixture = seedFixture(dataSource.connection)
                val bookingThread =
                    assertNotNull(
                        SupportThreadRepository(dataSource).createOrFindBookingThread(fixture.bookingId),
                    )
                val scenarios =
                    listOf(
                        ReadLockScenario(
                            name = "guest_booking",
                            threadId = bookingThread.id,
                            access = SupportThreadReadAccess.Guest(fixture.guestUserId),
                            expectsBookingParent = true,
                        ),
                        ReadLockScenario(
                            name = "venue_booking",
                            threadId = bookingThread.id,
                            access =
                                SupportThreadReadAccess.Venue(
                                    userId = fixture.venueOwnerUserId,
                                    venueId = fixture.venueId,
                                ),
                            expectsBookingParent = true,
                        ),
                        ReadLockScenario(
                            name = "guest_venue_chat",
                            threadId =
                                seedSupportThread(
                                    dataSource.connection,
                                    fixture,
                                    threadType = SupportThreadType.VENUE_CHAT,
                                ),
                            access = SupportThreadReadAccess.Guest(fixture.guestUserId),
                        ),
                        ReadLockScenario(
                            name = "guest_support_venue",
                            threadId = seedSupportThread(dataSource.connection, fixture),
                            access = SupportThreadReadAccess.Guest(fixture.guestUserId),
                        ),
                        ReadLockScenario(
                            name = "guest_support_platform",
                            threadId =
                                seedSupportThread(
                                    dataSource.connection,
                                    fixture,
                                    assigneeScope = SupportAssigneeScope.PLATFORM,
                                    venueId = null,
                                ),
                            access = SupportThreadReadAccess.Guest(fixture.guestUserId),
                        ),
                        ReadLockScenario(
                            name = "venue_venue_chat",
                            threadId =
                                seedSupportThread(
                                    dataSource.connection,
                                    fixture,
                                    threadType = SupportThreadType.VENUE_CHAT,
                                ),
                            access =
                                SupportThreadReadAccess.Venue(
                                    userId = fixture.venueOwnerUserId,
                                    venueId = fixture.venueId,
                                ),
                        ),
                        ReadLockScenario(
                            name = "venue_support_venue",
                            threadId = seedSupportThread(dataSource.connection, fixture),
                            access =
                                SupportThreadReadAccess.Venue(
                                    userId = fixture.venueOwnerUserId,
                                    venueId = fixture.venueId,
                                ),
                        ),
                        ReadLockScenario(
                            name = "venue_support_platform",
                            threadId =
                                seedSupportThread(
                                    dataSource.connection,
                                    fixture,
                                    assigneeScope = SupportAssigneeScope.PLATFORM,
                                ),
                            access =
                                SupportThreadReadAccess.Venue(
                                    userId = fixture.venueOwnerUserId,
                                    venueId = fixture.venueId,
                                ),
                        ),
                        ReadLockScenario(
                            name = "platform_support_venue",
                            threadId = seedSupportThread(dataSource.connection, fixture),
                            access =
                                SupportThreadReadAccess.Platform(
                                    userId = fixture.platformOwnerUserId,
                                    platformOwnerUserId = fixture.platformOwnerUserId,
                                ),
                        ),
                        ReadLockScenario(
                            name = "platform_support_platform",
                            threadId =
                                seedSupportThread(
                                    dataSource.connection,
                                    fixture,
                                    assigneeScope = SupportAssigneeScope.PLATFORM,
                                    venueId = null,
                                ),
                            access =
                                SupportThreadReadAccess.Platform(
                                    userId = fixture.platformOwnerUserId,
                                    platformOwnerUserId = fixture.platformOwnerUserId,
                                ),
                        ),
                    )

                directConnection(database).use { observer ->
                    scenarios.forEach { scenario ->
                        val applicationName = writerApplicationName(scenario.name, database)
                        writerDataSource(database, applicationName).use { writerDataSource ->
                            val parentLocked = CountDownLatch(1)
                            val releaseMarkerWrite = CountDownLatch(1)
                            val markerWritten = CountDownLatch(1)
                            val releaseCommit = CountDownLatch(1)
                            val checkpoints = CopyOnWriteArrayList<SupportThreadReadCheckpoint>()
                            val repository =
                                SupportThreadRepository(
                                    dataSource = writerDataSource,
                                    supportThreadReadCheckpoint = { checkpoint ->
                                        checkpoints += checkpoint
                                        if (checkpoint == SupportThreadReadCheckpoint.AFTER_THREAD_LOCK) {
                                            parentLocked.countDown()
                                            check(
                                                releaseMarkerWrite.await(
                                                    WAIT_TIMEOUT_SECONDS,
                                                    TimeUnit.SECONDS,
                                                ),
                                            )
                                        } else if (checkpoint == SupportThreadReadCheckpoint.AFTER_MARKER_WRITE) {
                                            markerWritten.countDown()
                                            check(releaseCommit.await(WAIT_TIMEOUT_SECONDS, TimeUnit.SECONDS))
                                        }
                                    },
                                )
                            val operation =
                                async(Dispatchers.IO) {
                                    repository.markThreadRead(scenario.threadId, scenario.access)
                                }
                            assertTrue(
                                parentLocked.await(WAIT_TIMEOUT_SECONDS, TimeUnit.SECONDS),
                                "${scenario.name}: production helper did not reach the before-marker checkpoint",
                            )

                            try {
                                val writerPid = onlyApplicationBackendPid(observer, applicationName)
                                val beforeMarkerLocks = readReadTransactionLocks(observer, writerPid)
                                assertEquals(
                                    scenario.expectsBookingParent,
                                    beforeMarkerLocks.bookingsRowShareGranted,
                                    "${scenario.name}: booking parent lock mismatch; ${beforeMarkerLocks.diagnostic}",
                                )
                                assertTrue(
                                    beforeMarkerLocks.threadsRowShareGranted,
                                    "${scenario.name}: thread parent was not locked; ${beforeMarkerLocks.diagnostic}",
                                )
                                assertFalse(
                                    beforeMarkerLocks.readsRowExclusiveGranted,
                                    "${scenario.name}: child marker DML ran before release; " +
                                        beforeMarkerLocks.diagnostic,
                                )
                                assertEquals(
                                    0,
                                    countReads(observer, scenario.threadId, scenario.access.userId),
                                    "${scenario.name}: marker exists before the before-marker checkpoint is released",
                                )
                                releaseMarkerWrite.countDown()
                                assertTrue(
                                    markerWritten.await(WAIT_TIMEOUT_SECONDS, TimeUnit.SECONDS),
                                    "${scenario.name}: production helper did not reach the after-marker checkpoint",
                                )
                                val afterMarkerLocks = readReadTransactionLocks(observer, writerPid)
                                assertEquals(
                                    scenario.expectsBookingParent,
                                    afterMarkerLocks.bookingsRowShareGranted,
                                    "${scenario.name}: booking lock was not retained; ${afterMarkerLocks.diagnostic}",
                                )
                                assertTrue(
                                    afterMarkerLocks.threadsRowShareGranted,
                                    "${scenario.name}: thread lock was not retained; ${afterMarkerLocks.diagnostic}",
                                )
                                assertTrue(
                                    afterMarkerLocks.readsRowExclusiveGranted,
                                    "${scenario.name}: marker DML did not acquire the child lock; " +
                                        afterMarkerLocks.diagnostic,
                                )
                            } finally {
                                releaseMarkerWrite.countDown()
                                releaseCommit.countDown()
                            }

                            assertEquals(
                                SupportThreadReadResult.MARKED,
                                withTimeout(TimeUnit.SECONDS.toMillis(WAIT_TIMEOUT_SECONDS)) {
                                    operation.await()
                                },
                                scenario.name,
                            )
                            assertEquals(
                                listOf(
                                    SupportThreadReadCheckpoint.AFTER_THREAD_LOCK,
                                    SupportThreadReadCheckpoint.AFTER_MARKER_WRITE,
                                ),
                                checkpoints.toList(),
                                scenario.name,
                            )
                            assertEquals(
                                1,
                                countReads(observer, scenario.threadId, scenario.access.userId),
                                "${scenario.name}: expected exactly one committed marker",
                            )
                        }
                    }
                }
            }
        }

    @Test
    fun `PostgreSQL NULL author venue chat is unread until locked exact open and preserves another actor marker`() =
        runBlocking {
            val database = PostgresTestEnv.createDatabase()
            PostgresTestEnv.createDataSource(database).use { dataSource ->
                val fixture = seedFixture(dataSource.connection)
                val repository = SupportThreadRepository(dataSource)
                val threadId =
                    seedSupportThread(
                        dataSource.connection,
                        fixture,
                        threadType = SupportThreadType.VENUE_CHAT,
                    )
                val baseline =
                    repository.addMessage(
                        threadId = threadId,
                        authorUserId = fixture.venueOwnerUserId,
                        authorRole = SupportMessageAuthorRole.VENUE,
                        source = SupportMessageSource.VENUE_MINIAPP,
                        text = "Venue baseline",
                    )
                assertEquals(
                    SupportThreadReadResult.MARKED,
                    repository.markThreadRead(
                        threadId,
                        SupportThreadReadAccess.Venue(
                            userId = fixture.venueOwnerUserId,
                            venueId = fixture.venueId,
                        ),
                    ),
                )
                assertEquals(
                    SupportThreadReadResult.MARKED,
                    repository.markThreadRead(
                        threadId,
                        SupportThreadReadAccess.Guest(fixture.guestUserId),
                    ),
                )
                val otherActorBefore =
                    dataSource.connection.use { connection ->
                        assertNotNull(readMarker(connection, threadId, fixture.guestUserId))
                    }
                assertEquals(baseline.id, otherActorBefore.lastReadMessageId)

                val systemMessage =
                    repository.addMessage(
                        threadId = threadId,
                        authorUserId = null,
                        authorRole = SupportMessageAuthorRole.SYSTEM,
                        source = SupportMessageSource.SYSTEM,
                        text = "Отзыв после визита",
                        statusAfterInsert = null,
                    )
                assertEquals(
                    1,
                    repository.countVenueConversationUnread(fixture.venueId, fixture.venueOwnerUserId),
                )

                val detail =
                    assertNotNull(
                        repository.getVenueThreadAndMarkRead(
                            venueId = fixture.venueId,
                            threadId = threadId,
                            viewerUserId = fixture.venueOwnerUserId,
                            allowedThreadTypes = setOf(SupportThreadType.BOOKING_THREAD, SupportThreadType.VENUE_CHAT),
                        ),
                    )
                assertEquals(listOf(baseline.id, systemMessage.id), detail.messages.map { it.id })
                assertEquals(
                    0,
                    repository.countVenueConversationUnread(fixture.venueId, fixture.venueOwnerUserId),
                )
                dataSource.connection.use { connection ->
                    assertEquals(
                        systemMessage.id,
                        assertNotNull(readMarker(connection, threadId, fixture.venueOwnerUserId)).lastReadMessageId,
                    )
                    assertEquals(otherActorBefore, readMarker(connection, threadId, fixture.guestUserId))
                }
            }
        }

    @Test
    fun `close and read serialize in both orders with closed status and one marker`() =
        runBlocking {
            val database = PostgresTestEnv.createDatabase()
            PostgresTestEnv.createDataSource(database).use { dataSource ->
                val fixture = seedFixture(dataSource.connection)
                val threadId = seedSupportThread(dataSource.connection, fixture)
                val readLocked = CountDownLatch(1)
                val releaseRead = CountDownLatch(1)
                val repository =
                    SupportThreadRepository(
                        dataSource = dataSource,
                        supportThreadReadCheckpoint = { checkpoint ->
                            if (checkpoint == SupportThreadReadCheckpoint.AFTER_MARKER_WRITE) {
                                readLocked.countDown()
                                assertTrue(releaseRead.await(WAIT_TIMEOUT_SECONDS, TimeUnit.SECONDS))
                            }
                        },
                    )
                val ordinaryRepository = SupportThreadRepository(dataSource)
                val access = SupportThreadReadAccess.Guest(fixture.guestUserId)

                directConnection(database).use { observer ->
                    val readFirst = async(Dispatchers.IO) { repository.markThreadRead(threadId, access) }
                    assertTrue(readLocked.await(WAIT_TIMEOUT_SECONDS, TimeUnit.SECONDS))
                    val closeSecond =
                        async(Dispatchers.IO) {
                            ordinaryRepository.updateThreadStatus(threadId, SupportThreadStatus.CLOSED)
                        }
                    try {
                        awaitLockWaiter(
                            observer = observer,
                            queryPattern = "%update support_threads%set status%",
                            operation = closeSecond,
                        )
                    } finally {
                        releaseRead.countDown()
                    }
                    withTimeout(TimeUnit.SECONDS.toMillis(WAIT_TIMEOUT_SECONDS)) {
                        assertEquals(SupportThreadReadResult.MARKED, readFirst.await())
                        closeSecond.await()
                    }
                    assertEquals(SupportThreadStatus.CLOSED.name, threadStatus(observer, threadId))
                    assertEquals(1, countReads(observer, threadId, fixture.guestUserId))

                    resetThreadReadState(observer, threadId, fixture.guestUserId)
                    directConnection(database).use { closeBlocker ->
                        closeBlocker.autoCommit = false
                        ordinaryRepository.updateThreadStatus(
                            closeBlocker,
                            threadId,
                            SupportThreadStatus.CLOSED,
                        )
                        val readSecond =
                            async(Dispatchers.IO) {
                                ordinaryRepository.markThreadRead(threadId, access)
                            }
                        try {
                            awaitLockWaiter(
                                observer = observer,
                                queryPattern = "%from support_threads%for update%",
                                operation = readSecond,
                            )
                            assertEquals(0, countReads(observer, threadId, fixture.guestUserId))
                            closeBlocker.commit()
                        } catch (failure: Throwable) {
                            closeBlocker.rollback()
                            throw failure
                        }
                        assertEquals(
                            SupportThreadReadResult.MARKED,
                            withTimeout(TimeUnit.SECONDS.toMillis(WAIT_TIMEOUT_SECONDS)) { readSecond.await() },
                        )
                    }
                    assertEquals(SupportThreadStatus.CLOSED.name, threadStatus(observer, threadId))
                    assertEquals(1, countReads(observer, threadId, fixture.guestUserId))
                }
            }
        }

    private fun awaitLockWaiter(
        observer: Connection,
        queryPattern: String,
        operation: kotlinx.coroutines.Deferred<*>,
    ) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(WAIT_TIMEOUT_SECONDS)
        var diagnostic = "No matching PostgreSQL lock waiter observed"
        while (System.nanoTime() < deadline) {
            val waiters =
                observer.prepareStatement(
                    """
                    SELECT pid, pg_blocking_pids(pid) AS blockers, query
                    FROM pg_stat_activity
                    WHERE datname = current_database()
                      AND pid <> pg_backend_pid()
                      AND wait_event_type = 'Lock'
                      AND lower(query) LIKE ?
                    ORDER BY pid
                    """.trimIndent(),
                ).use { statement ->
                    statement.setString(1, queryPattern)
                    statement.executeQuery().use { rows ->
                        buildList {
                            while (rows.next()) {
                                add(
                                    "pid=${rows.getInt("pid")}, blockers=${rows.getString("blockers")}, " +
                                        "query=${rows.getString("query").replace(Regex("\\s+"), " ").trim()}",
                                )
                            }
                        }
                    }
                }
            diagnostic = "pattern=$queryPattern; waiters=${waiters.joinToString(" | ")}"
            if (waiters.isNotEmpty()) return
            check(!operation.isCompleted) { "Operation completed before reaching the expected lock: $diagnostic" }
            Thread.yield()
        }
        error("Timed out waiting for PostgreSQL lock: $diagnostic")
    }

    private fun awaitExactLockEdge(
        observer: Connection,
        writerPid: Int,
        readerPid: Int,
        operation: kotlinx.coroutines.Deferred<*>,
    ): ExactLockEvidence {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(WAIT_TIMEOUT_SECONDS)
        var diagnostic = "No matching transaction-id lock edge observed"
        while (System.nanoTime() < deadline) {
            val evidence =
                observer.prepareStatement(
                    """
                    SELECT
                        waiting.pid AS waiting_pid,
                        pg_blocking_pids(waiting.pid) AS blocking_pids,
                        waiting.locktype AS waiting_lock_type,
                        waiting.mode AS waiting_lock_mode,
                        waiting.granted AS waiting_lock_granted,
                        waiting.transactionid::TEXT AS transaction_id,
                        blocker.mode AS blocker_lock_mode,
                        blocker.granted AS blocker_lock_granted
                    FROM pg_locks waiting
                    JOIN pg_locks blocker
                      ON blocker.pid = ?
                     AND blocker.locktype = waiting.locktype
                     AND blocker.transactionid = waiting.transactionid
                     AND blocker.granted
                    WHERE waiting.pid = ?
                      AND NOT waiting.granted
                      AND waiting.locktype = 'transactionid'
                      AND waiting.mode = 'ShareLock'
                      AND ? = ANY(pg_blocking_pids(waiting.pid))
                    ORDER BY waiting.transactionid::TEXT
                    """.trimIndent(),
                ).use { statement ->
                    statement.setInt(1, readerPid)
                    statement.setInt(2, writerPid)
                    statement.setInt(3, readerPid)
                    statement.executeQuery().use { rows ->
                        buildList {
                            while (rows.next()) {
                                val sqlBlockingPids = rows.getArray("blocking_pids")
                                val blockingPids =
                                    try {
                                        (sqlBlockingPids.array as Array<*>)
                                            .map { (it as Number).toInt() }
                                    } finally {
                                        sqlBlockingPids.free()
                                    }
                                add(
                                    ExactLockEvidence(
                                        waitingPid = rows.getInt("waiting_pid"),
                                        blockingPids = blockingPids,
                                        waitingLockType = rows.getString("waiting_lock_type"),
                                        waitingLockMode = rows.getString("waiting_lock_mode"),
                                        waitingLockGranted = rows.getBoolean("waiting_lock_granted"),
                                        transactionId = rows.getString("transaction_id"),
                                        blockerLockMode = rows.getString("blocker_lock_mode"),
                                        blockerLockGranted = rows.getBoolean("blocker_lock_granted"),
                                    ),
                                )
                            }
                        }
                    }
                }
            diagnostic =
                "writerPid=$writerPid; readerPid=$readerPid; " +
                "edges=${evidence.joinToString()}"
            evidence.singleOrNull { it.blockingPids == listOf(readerPid) }?.let { return it }
            check(!operation.isCompleted) {
                "Writer completed before the exact PostgreSQL lock edge was observed: $diagnostic"
            }
            Thread.yield()
        }
        error("Timed out waiting for exact PostgreSQL lock edge: $diagnostic")
    }

    private fun transactionStartedAt(
        observer: Connection,
        pid: Int,
    ): Instant =
        observer.prepareStatement(
            "SELECT xact_start FROM pg_stat_activity WHERE pid = ? AND datname = current_database()",
        ).use { statement ->
            statement.setInt(1, pid)
            statement.executeQuery().use { rows ->
                check(rows.next()) { "PostgreSQL backend pid=$pid disappeared before xact_start inspection" }
                val startedAt = rows.getTimestamp("xact_start")
                check(startedAt != null) { "PostgreSQL backend pid=$pid has no active transaction" }
                check(!rows.next()) { "PostgreSQL backend pid=$pid was not unique" }
                startedAt.toInstant()
            }
        }

    private fun awaitDatabaseClockAfter(
        observer: Connection,
        earlier: Instant,
    ) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(WAIT_TIMEOUT_SECONDS)
        var observed = earlier
        while (System.nanoTime() < deadline) {
            observed =
                observer.createStatement().use { statement ->
                    statement.executeQuery("SELECT clock_timestamp()").use { rows ->
                        check(rows.next())
                        rows.getTimestamp(1).toInstant()
                    }
                }
            if (observed.isAfter(earlier)) return
            Thread.yield()
        }
        error("PostgreSQL clock did not advance after $earlier; last observed=$observed")
    }

    private fun readMarker(
        connection: Connection,
        threadId: Long,
        userId: Long,
    ): ReadMarker? =
        connection.prepareStatement(
            """
            SELECT last_read_at, last_read_message_id
            FROM support_thread_reads
            WHERE thread_id = ? AND user_id = ?
            """.trimIndent(),
        ).use { statement ->
            statement.setLong(1, threadId)
            statement.setLong(2, userId)
            statement.executeQuery().use { rows ->
                if (rows.next()) {
                    val lastReadMessageId = rows.getLong("last_read_message_id")
                    val cursorWasNull = rows.wasNull()
                    ReadMarker(
                        lastReadAt = rows.getTimestamp("last_read_at").toInstant(),
                        lastReadMessageId = if (cursorWasNull) null else lastReadMessageId,
                    )
                } else {
                    null
                }
            }
        }

    private fun messageCreatedAt(
        connection: Connection,
        messageId: Long,
    ): Instant =
        connection.prepareStatement("SELECT created_at FROM support_messages WHERE id = ?").use { statement ->
            statement.setLong(1, messageId)
            statement.executeQuery().use { rows ->
                check(rows.next()) { "Missing support message id=$messageId" }
                rows.getTimestamp("created_at").toInstant()
            }
        }

    private fun updateMessageCreatedAt(
        connection: Connection,
        messageId: Long,
        createdAt: Instant,
    ) {
        connection.prepareStatement("UPDATE support_messages SET created_at = ? WHERE id = ?").use { statement ->
            statement.setTimestamp(1, Timestamp.from(createdAt))
            statement.setLong(2, messageId)
            assertEquals(1, statement.executeUpdate())
        }
    }

    private fun seedFixture(connection: Connection): Fixture =
        connection.use {
            val guestUserId = 8_850_001L
            val venueOwnerUserId = 8_850_002L
            val platformOwnerUserId = 8_850_003L
            listOf(
                guestUserId to "support_read_concurrency_guest",
                venueOwnerUserId to "support_read_concurrency_venue_owner",
                platformOwnerUserId to "support_read_concurrency_platform_owner",
            ).forEach { (userId, username) ->
                it.prepareStatement(
                    "INSERT INTO users (telegram_user_id, username) VALUES (?, ?)",
                ).use { statement ->
                    statement.setLong(1, userId)
                    statement.setString(2, username)
                    statement.executeUpdate()
                }
            }
            val venueId =
                it.prepareStatement(
                    "INSERT INTO venues (name, city, address, status) VALUES (?, ?, ?, 'PUBLISHED')",
                    Statement.RETURN_GENERATED_KEYS,
                ).use { statement ->
                    statement.setString(1, "Support Read Concurrency Venue")
                    statement.setString(2, "Moscow")
                    statement.setString(3, "Read concurrency street, 1")
                    statement.executeUpdate()
                    statement.generatedKeys.use { keys ->
                        check(keys.next())
                        keys.getLong(1)
                    }
                }
            it.prepareStatement(
                "INSERT INTO venue_members (venue_id, user_id, role) VALUES (?, ?, 'OWNER')",
            ).use { statement ->
                statement.setLong(1, venueId)
                statement.setLong(2, venueOwnerUserId)
                statement.executeUpdate()
            }
            val bookingId =
                it.prepareStatement(
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
                    statement.setLong(2, guestUserId)
                    statement.setTimestamp(3, Timestamp.from(Instant.parse("2030-01-10T18:00:00Z")))
                    statement.setDate(4, Date.valueOf("2030-01-10"))
                    statement.executeUpdate()
                    statement.generatedKeys.use { keys ->
                        check(keys.next())
                        keys.getLong(1)
                    }
                }
            Fixture(
                bookingId = bookingId,
                venueId = venueId,
                guestUserId = guestUserId,
                venueOwnerUserId = venueOwnerUserId,
                platformOwnerUserId = platformOwnerUserId,
            )
        }

    private fun seedSupportThread(
        connection: Connection,
        fixture: Fixture,
        threadType: SupportThreadType = SupportThreadType.SUPPORT_TICKET,
        assigneeScope: SupportAssigneeScope = SupportAssigneeScope.VENUE,
        venueId: Long? = fixture.venueId,
    ): Long =
        connection.use {
            it.prepareStatement(
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
                VALUES (?, ?, 'OTHER', 'IN_PROGRESS', ?, ?, 'GUEST_MINIAPP', ?)
                """.trimIndent(),
                Statement.RETURN_GENERATED_KEYS,
            ).use { statement ->
                if (venueId == null) {
                    statement.setNull(1, java.sql.Types.BIGINT)
                } else {
                    statement.setLong(1, venueId)
                }
                statement.setLong(2, fixture.guestUserId)
                statement.setString(3, threadType.name)
                statement.setString(4, assigneeScope.name)
                statement.setString(5, "Concurrent ${threadType.name} ${assigneeScope.name} read")
                statement.executeUpdate()
                statement.generatedKeys.use { keys ->
                    check(keys.next())
                    keys.getLong(1)
                }
            }
        }

    private fun onlyApplicationBackendPid(
        observer: Connection,
        applicationName: String,
    ): Int =
        observer.prepareStatement(
            """
            SELECT pid
            FROM pg_stat_activity
            WHERE datname = current_database()
              AND application_name = ?
            ORDER BY pid
            """.trimIndent(),
        ).use { statement ->
            statement.setString(1, applicationName)
            statement.executeQuery().use { rows ->
                val pids =
                    buildList {
                        while (rows.next()) add(rows.getInt("pid"))
                    }
                assertEquals(
                    1,
                    pids.size,
                    "Expected one independent backend for application_name=$applicationName, got $pids",
                )
                pids.single()
            }
        }

    private fun readReadTransactionLocks(
        observer: Connection,
        writerPid: Int,
    ): ParentBeforeChildLocks =
        observer.prepareStatement(
            """
            WITH target_locks AS (
                SELECT relation.relname, lock.mode, lock.granted
                FROM pg_locks lock
                JOIN pg_class relation ON relation.oid = lock.relation
                JOIN pg_namespace namespace ON namespace.oid = relation.relnamespace
                WHERE lock.pid = ?
                  AND namespace.nspname = current_schema()
            )
            SELECT
                COALESCE(
                    BOOL_OR(relname = 'bookings' AND mode = 'RowShareLock' AND granted),
                    FALSE
                ) AS bookings_row_share_granted,
                COALESCE(
                    BOOL_OR(relname = 'support_threads' AND mode = 'RowShareLock' AND granted),
                    FALSE
                ) AS threads_row_share_granted,
                COALESCE(
                    BOOL_OR(relname = 'support_thread_reads' AND mode = 'RowExclusiveLock' AND granted),
                    FALSE
                ) AS reads_row_exclusive_granted,
                COALESCE(STRING_AGG(relname || ':' || mode || ':' || granted::text, ', ' ORDER BY relname, mode), '')
                    AS diagnostic
            FROM target_locks
            """.trimIndent(),
        ).use { statement ->
            statement.setInt(1, writerPid)
            statement.executeQuery().use { rows ->
                check(rows.next())
                ParentBeforeChildLocks(
                    bookingsRowShareGranted = rows.getBoolean("bookings_row_share_granted"),
                    threadsRowShareGranted = rows.getBoolean("threads_row_share_granted"),
                    readsRowExclusiveGranted = rows.getBoolean("reads_row_exclusive_granted"),
                    diagnostic = "writerPid=$writerPid; locks=${rows.getString("diagnostic")}",
                )
            }
        }

    private fun resetThreadReadState(
        connection: Connection,
        threadId: Long,
        userId: Long,
    ) {
        connection.prepareStatement("DELETE FROM support_thread_reads WHERE thread_id = ? AND user_id = ?").use {
            it.setLong(1, threadId)
            it.setLong(2, userId)
            assertEquals(1, it.executeUpdate())
        }
        connection.prepareStatement("UPDATE support_threads SET status = 'IN_PROGRESS' WHERE id = ?").use {
            it.setLong(1, threadId)
            assertEquals(1, it.executeUpdate())
        }
    }

    private fun countReads(
        connection: Connection,
        threadId: Long,
        userId: Long,
    ): Int =
        connection.prepareStatement(
            "SELECT COUNT(*) FROM support_thread_reads WHERE thread_id = ? AND user_id = ?",
        ).use { statement ->
            statement.setLong(1, threadId)
            statement.setLong(2, userId)
            statement.executeQuery().use { rows ->
                rows.next()
                rows.getInt(1)
            }
        }

    private fun threadStatus(
        connection: Connection,
        threadId: Long,
    ): String =
        connection.prepareStatement("SELECT status FROM support_threads WHERE id = ?").use { statement ->
            statement.setLong(1, threadId)
            statement.executeQuery().use { rows ->
                check(rows.next())
                rows.getString(1)
            }
        }

    private fun directConnection(database: PostgresTestDatabase): Connection =
        DriverManager.getConnection(database.jdbcUrl, database.user, database.password)

    private fun writerDataSource(
        database: PostgresTestDatabase,
        applicationName: String,
    ): HikariDataSource =
        HikariDataSource(
            HikariConfig().apply {
                jdbcUrl = database.jdbcUrl
                username = database.user
                password = database.password
                maximumPoolSize = 1
                minimumIdle = 1
                poolName = applicationName
                addDataSourceProperty("ApplicationName", applicationName)
            },
        )

    private fun writerApplicationName(
        scenario: String,
        database: PostgresTestDatabase,
    ): String = "support_read_${scenario.take(28)}_${database.schema.takeLast(8)}"

    private data class ReadLockScenario(
        val name: String,
        val threadId: Long,
        val access: SupportThreadReadAccess,
        val expectsBookingParent: Boolean = false,
    )

    private data class ParentBeforeChildLocks(
        val bookingsRowShareGranted: Boolean,
        val threadsRowShareGranted: Boolean,
        val readsRowExclusiveGranted: Boolean,
        val diagnostic: String,
    )

    private data class ReadMarker(
        val lastReadAt: Instant,
        val lastReadMessageId: Long?,
    )

    private data class ExactLockEvidence(
        val waitingPid: Int,
        val blockingPids: List<Int>,
        val waitingLockType: String,
        val waitingLockMode: String,
        val waitingLockGranted: Boolean,
        val transactionId: String?,
        val blockerLockMode: String,
        val blockerLockGranted: Boolean,
    )

    private data class Fixture(
        val bookingId: Long,
        val venueId: Long,
        val guestUserId: Long,
        val venueOwnerUserId: Long,
        val platformOwnerUserId: Long,
    )

    private companion object {
        const val WAIT_TIMEOUT_SECONDS = 20L
    }
}
