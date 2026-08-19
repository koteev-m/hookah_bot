package com.hookah.platform.backend.support

import com.hookah.platform.backend.test.migrateH2OnboardingFixture
import kotlinx.coroutines.runBlocking
import org.h2.jdbcx.JdbcDataSource
import java.sql.Connection
import java.sql.Date
import java.sql.Statement
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.sql.DataSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SupportThreadReadRepositoryTest {
    @Test
    fun `Guest marks owned thread types with both support assignee scopes`() =
        withFixture { fixture ->
            val bookingThread = fixture.seedBookingThread()
            val venueChat = fixture.seedThread(SupportThreadType.VENUE_CHAT)
            val venueAssignedSupport = fixture.seedThread(SupportThreadType.SUPPORT_TICKET)
            val platformAssignedSupport =
                fixture.seedThread(
                    threadType = SupportThreadType.SUPPORT_TICKET,
                    assigneeScope = SupportAssigneeScope.PLATFORM,
                )

            runBlocking {
                listOf(
                    bookingThread,
                    venueChat,
                    venueAssignedSupport,
                    platformAssignedSupport,
                ).forEach { threadId ->
                    assertEquals(
                        SupportThreadReadResult.MARKED,
                        fixture.repository.markThreadRead(
                            threadId,
                            SupportThreadReadAccess.Guest(fixture.guestUserId),
                        ),
                    )
                }
            }

            assertEquals(4, fixture.countReads())
        }

    @Test
    fun `Venue Owner and Manager mark all own venue thread types including platform assigned support`() =
        withFixture { fixture ->
            val bookingThread = fixture.seedBookingThread()
            val venueChat = fixture.seedThread(SupportThreadType.VENUE_CHAT)
            val venueAssignedSupport = fixture.seedThread(SupportThreadType.SUPPORT_TICKET)
            val platformAssignedSupport =
                fixture.seedThread(
                    threadType = SupportThreadType.SUPPORT_TICKET,
                    assigneeScope = SupportAssigneeScope.PLATFORM,
                )

            runBlocking {
                listOf(fixture.ownerUserId, fixture.managerUserId).forEach { userId ->
                    listOf(
                        bookingThread,
                        venueChat,
                        venueAssignedSupport,
                        platformAssignedSupport,
                    ).forEach { threadId ->
                        assertEquals(
                            SupportThreadReadResult.MARKED,
                            fixture.repository.markThreadRead(
                                threadId,
                                SupportThreadReadAccess.Venue(userId = userId, venueId = fixture.venueId),
                            ),
                        )
                    }
                }
            }

            assertEquals(8, fixture.countReads())
        }

    @Test
    fun `Platform owner marks only support tickets regardless assignee scope`() =
        withFixture { fixture ->
            val venueAssignedSupport = fixture.seedThread(SupportThreadType.SUPPORT_TICKET)
            val platformAssignedSupport =
                fixture.seedThread(
                    threadType = SupportThreadType.SUPPORT_TICKET,
                    assigneeScope = SupportAssigneeScope.PLATFORM,
                )
            val bookingThread = fixture.seedBookingThread()
            val venueChat = fixture.seedThread(SupportThreadType.VENUE_CHAT)
            val platformAccess =
                SupportThreadReadAccess.Platform(
                    userId = fixture.platformOwnerUserId,
                    platformOwnerUserId = fixture.platformOwnerUserId,
                )

            runBlocking {
                assertEquals(
                    SupportThreadReadResult.MARKED,
                    fixture.repository.markThreadRead(venueAssignedSupport, platformAccess),
                )
                assertEquals(
                    SupportThreadReadResult.MARKED,
                    fixture.repository.markThreadRead(platformAssignedSupport, platformAccess),
                )
                assertEquals(
                    SupportThreadReadResult.NOT_FOUND,
                    fixture.repository.markThreadRead(bookingThread, platformAccess),
                )
                assertEquals(
                    SupportThreadReadResult.NOT_FOUND,
                    fixture.repository.markThreadRead(venueChat, platformAccess),
                )
                assertEquals(
                    SupportThreadReadResult.FORBIDDEN,
                    fixture.repository.markThreadRead(
                        venueAssignedSupport,
                        SupportThreadReadAccess.Platform(
                            userId = fixture.platformOwnerUserId,
                            platformOwnerUserId = fixture.platformOwnerUserId + 1,
                        ),
                    ),
                )
            }

            assertEquals(2, fixture.countReads())
        }

    @Test
    fun `unknown and null assignee scopes fail closed under the thread lock`() =
        withFixture { fixture ->
            val unknownScopeThread = fixture.seedThread(SupportThreadType.SUPPORT_TICKET)
            val nullScopeThread = fixture.seedThread(SupportThreadType.SUPPORT_TICKET)
            fixture.allowInvalidAssigneeScopes()
            fixture.updateThreadAssigneeScope(unknownScopeThread, "UNKNOWN")
            fixture.updateThreadAssigneeScope(nullScopeThread, null)

            runBlocking {
                listOf(unknownScopeThread, nullScopeThread).forEach { threadId ->
                    assertEquals(
                        SupportThreadReadResult.NOT_FOUND,
                        fixture.repository.markThreadRead(
                            threadId,
                            SupportThreadReadAccess.Guest(fixture.guestUserId),
                        ),
                    )
                }
            }

            assertEquals(0, fixture.countReads())
        }

    @Test
    fun `foreign Guest foreign venue and Staff cannot create read markers`() =
        withFixture { fixture ->
            val threadId = fixture.seedThread(SupportThreadType.VENUE_CHAT)

            runBlocking {
                assertEquals(
                    SupportThreadReadResult.NOT_FOUND,
                    fixture.repository.markThreadRead(
                        threadId,
                        SupportThreadReadAccess.Guest(fixture.foreignGuestUserId),
                    ),
                )
                assertEquals(
                    SupportThreadReadResult.NOT_FOUND,
                    fixture.repository.markThreadRead(
                        threadId,
                        SupportThreadReadAccess.Venue(
                            userId = fixture.foreignVenueManagerUserId,
                            venueId = fixture.foreignVenueId,
                        ),
                    ),
                )
                assertEquals(
                    SupportThreadReadResult.FORBIDDEN,
                    fixture.repository.markThreadRead(
                        threadId,
                        SupportThreadReadAccess.Venue(
                            userId = fixture.staffUserId,
                            venueId = fixture.venueId,
                        ),
                    ),
                )
            }

            assertEquals(0, fixture.countReads())
        }

    @Test
    fun `booking read fails closed when stored thread identity is not canonical`() =
        withFixture { fixture ->
            val threadId = fixture.seedBookingThread()
            fixture.updateThreadVenue(threadId, fixture.foreignVenueId)

            val result =
                runBlocking {
                    fixture.repository.markThreadRead(
                        threadId,
                        SupportThreadReadAccess.Guest(fixture.guestUserId),
                    )
                }

            assertEquals(SupportThreadReadResult.NOT_FOUND, result)
            assertEquals(0, fixture.countReads())
        }

    @Test
    fun `repeat read is one physical marker and last read timestamp never moves backwards`() =
        withFixture { fixture ->
            val threadId = fixture.seedThread(SupportThreadType.SUPPORT_TICKET)
            val futureReadAt = Instant.parse("2099-01-01T00:00:00Z")
            fixture.seedRead(threadId, fixture.guestUserId, futureReadAt)

            runBlocking {
                repeat(2) {
                    assertEquals(
                        SupportThreadReadResult.MARKED,
                        fixture.repository.markThreadRead(
                            threadId,
                            SupportThreadReadAccess.Guest(fixture.guestUserId),
                        ),
                    )
                }
            }

            assertEquals(1, fixture.countReads())
            assertEquals(futureReadAt, fixture.readAt(threadId, fixture.guestUserId))
        }

    @Test
    fun `null cursor treats every foreign message as unread regardless of marker timestamp`() =
        withFixture { fixture ->
            val threadId = fixture.seedBookingThread()
            val sharedCreatedAt = Instant.parse("2031-02-03T10:15:30Z")
            fixture.seedMessage(threadId, fixture.guestUserId, sharedCreatedAt, "Первое")
            fixture.seedMessage(threadId, fixture.ownerUserId, sharedCreatedAt, "Своё")
            fixture.seedMessage(threadId, fixture.guestUserId, sharedCreatedAt, "Второе")
            fixture.seedRead(
                threadId = threadId,
                userId = fixture.ownerUserId,
                readAt = Instant.parse("2099-01-01T00:00:00Z"),
                lastReadMessageId = null,
            )

            assertEquals(
                2,
                runBlocking {
                    fixture.repository.countVenueConversationUnread(fixture.venueId, fixture.ownerUserId)
                },
            )
            assertNull(fixture.readMarker(threadId, fixture.ownerUserId)?.lastReadMessageId)
        }

    @Test
    fun `message id cursor orders equal timestamps and ignores own messages above cursor`() =
        withFixture { fixture ->
            val threadId = fixture.seedBookingThread()
            val sharedCreatedAt = Instant.parse("2031-02-03T10:15:30Z")
            val firstForeign = fixture.seedMessage(threadId, fixture.guestUserId, sharedCreatedAt, "Первое")
            fixture.seedRead(
                threadId = threadId,
                userId = fixture.ownerUserId,
                readAt = sharedCreatedAt.plusSeconds(60),
                lastReadMessageId = firstForeign,
            )
            val secondForeign = fixture.seedMessage(threadId, fixture.guestUserId, sharedCreatedAt, "Второе")
            val ownMessage = fixture.seedMessage(threadId, fixture.ownerUserId, sharedCreatedAt, "Своё")

            assertTrue(secondForeign > firstForeign)
            assertTrue(ownMessage > secondForeign)
            assertEquals(
                1,
                runBlocking {
                    fixture.repository.countVenueConversationUnread(fixture.venueId, fixture.ownerUserId)
                },
            )

            val detail =
                assertNotNull(
                    runBlocking {
                        fixture.repository.getVenueThreadAndMarkRead(
                            venueId = fixture.venueId,
                            threadId = threadId,
                            viewerUserId = fixture.ownerUserId,
                            allowedThreadTypes = setOf(SupportThreadType.BOOKING_THREAD),
                        )
                    },
                )
            assertEquals(listOf(firstForeign, secondForeign, ownMessage), detail.messages.map { it.id })
            assertEquals(ownMessage, fixture.readMarker(threadId, fixture.ownerUserId)?.lastReadMessageId)
            assertEquals(
                0,
                runBlocking {
                    fixture.repository.countVenueConversationUnread(fixture.venueId, fixture.ownerUserId)
                },
            )
        }

    @Test
    fun `repeat open is idempotent cursor never moves backwards and another thread is unchanged`() =
        withFixture { fixture ->
            val threadId = fixture.seedBookingThread()
            val otherThreadId = fixture.seedThread(SupportThreadType.VENUE_CHAT)
            fixture.seedMessage(
                threadId,
                fixture.guestUserId,
                Instant.parse("2031-02-03T10:15:30Z"),
                "Сообщение",
            )
            val futureCursor = 9_999_999L
            val otherCursor = 8_888_888L
            fixture.seedRead(
                threadId,
                fixture.ownerUserId,
                Instant.parse("2031-02-03T10:16:30Z"),
                futureCursor,
            )
            fixture.seedRead(
                otherThreadId,
                fixture.ownerUserId,
                Instant.parse("2031-02-03T10:16:30Z"),
                otherCursor,
            )

            repeat(2) {
                assertNotNull(
                    runBlocking {
                        fixture.repository.getVenueThreadAndMarkRead(
                            venueId = fixture.venueId,
                            threadId = threadId,
                            viewerUserId = fixture.ownerUserId,
                        )
                    },
                )
            }

            assertEquals(2, fixture.countReads())
            assertEquals(futureCursor, fixture.readMarker(threadId, fixture.ownerUserId)?.lastReadMessageId)
            assertEquals(otherCursor, fixture.readMarker(otherThreadId, fixture.ownerUserId)?.lastReadMessageId)
        }

    @Test
    fun `empty thread open records metadata but keeps cursor null`() =
        withFixture { fixture ->
            val threadId = fixture.seedThread(SupportThreadType.VENUE_CHAT)

            assertNotNull(
                runBlocking {
                    fixture.repository.getVenueThreadAndMarkRead(
                        venueId = fixture.venueId,
                        threadId = threadId,
                        viewerUserId = fixture.ownerUserId,
                    )
                },
            )

            val marker = assertNotNull(fixture.readMarker(threadId, fixture.ownerUserId))
            assertEquals(threadId, marker.threadId)
            assertEquals(fixture.ownerUserId, marker.userId)
            assertNull(marker.lastReadMessageId)
            assertNotNull(marker.lastReadAt)
            assertEquals(
                0,
                runBlocking {
                    fixture.repository.countVenueConversationUnread(fixture.venueId, fixture.ownerUserId)
                },
            )
        }

    @Test
    fun `wrong expected type and foreign actor leave no marker or message facts`() =
        withFixture { fixture ->
            val supportThread = fixture.seedThread(SupportThreadType.SUPPORT_TICKET)
            val messageId =
                fixture.seedMessage(
                    supportThread,
                    fixture.guestUserId,
                    Instant.parse("2031-02-03T10:15:30Z"),
                    "Скрытое сообщение",
                )

            assertNull(
                runBlocking {
                    fixture.repository.getVenueThreadAndMarkRead(
                        venueId = fixture.venueId,
                        threadId = supportThread,
                        viewerUserId = fixture.ownerUserId,
                        allowedThreadTypes = setOf(SupportThreadType.BOOKING_THREAD),
                    )
                },
            )
            assertNull(
                runBlocking {
                    fixture.repository.getGuestThreadAndMarkRead(
                        fixture.foreignGuestUserId,
                        supportThread,
                        GuestThreadSurface.SUPPORT,
                    )
                },
            )
            assertEquals(0, fixture.countReads())
            assertEquals(listOf(messageId), fixture.messageIds(supportThread))
        }

    @Test
    fun `detail failure rolls cursor update back to its previous raw marker`() =
        withFixture { fixture ->
            val threadId = fixture.seedBookingThread()
            val firstMessage =
                fixture.seedMessage(
                    threadId,
                    fixture.guestUserId,
                    Instant.parse("2031-02-03T10:15:30Z"),
                    "Первое",
                )
            fixture.seedRead(
                threadId,
                fixture.ownerUserId,
                Instant.parse("2031-02-03T10:16:30Z"),
                firstMessage,
            )
            fixture.seedMessage(
                threadId,
                fixture.guestUserId,
                Instant.parse("2031-02-03T10:17:30Z"),
                "Второе",
            )
            val before = assertNotNull(fixture.readMarker(threadId, fixture.ownerUserId))
            val failingRepository =
                SupportThreadRepository(
                    dataSource = fixture.dataSource,
                    supportThreadReadCheckpoint = { checkpoint ->
                        if (checkpoint == SupportThreadReadCheckpoint.AFTER_MARKER_WRITE) {
                            error("injected detail read failure")
                        }
                    },
                )

            assertFailsWith<IllegalStateException> {
                runBlocking {
                    failingRepository.getVenueThreadAndMarkRead(
                        venueId = fixture.venueId,
                        threadId = threadId,
                        viewerUserId = fixture.ownerUserId,
                    )
                }
            }
            assertEquals(before, fixture.readMarker(threadId, fixture.ownerUserId))
            assertEquals(
                1,
                runBlocking {
                    fixture.repository.countVenueConversationUnread(fixture.venueId, fixture.ownerUserId)
                },
            )
        }

    @Test
    fun `venue aggregate excludes support tickets`() =
        withFixture { fixture ->
            val bookingThread = fixture.seedBookingThread()
            val venueChat = fixture.seedThread(SupportThreadType.VENUE_CHAT)
            val supportTicket = fixture.seedThread(SupportThreadType.SUPPORT_TICKET)
            listOf(bookingThread, venueChat, supportTicket).forEachIndexed { index, threadId ->
                fixture.seedMessage(
                    threadId,
                    fixture.guestUserId,
                    Instant.parse("2031-02-03T10:15:30Z").plusSeconds(index.toLong()),
                    "Сообщение $index",
                )
            }

            assertEquals(
                2,
                runBlocking {
                    fixture.repository.countVenueConversationUnread(fixture.venueId, fixture.ownerUserId)
                },
            )
        }

    @Test
    fun `NULL author venue chat participates in every unread projection and clears on exact open`() =
        withFixture { fixture ->
            val threadId = fixture.seedThread(SupportThreadType.VENUE_CHAT)
            val ownMessageId =
                fixture.seedMessage(
                    threadId,
                    fixture.ownerUserId,
                    Instant.parse("2031-02-03T10:14:30Z"),
                    "Своё до системного",
                    authorRole = SupportMessageAuthorRole.VENUE,
                    source = SupportMessageSource.VENUE_MINIAPP,
                )
            val firstSystemMessageId =
                fixture.seedMessage(
                    threadId,
                    null,
                    Instant.parse("2031-02-03T10:15:30Z"),
                    "Отзыв после визита",
                    authorRole = SupportMessageAuthorRole.SYSTEM,
                    source = SupportMessageSource.SYSTEM,
                )

            assertEquals(
                1,
                runBlocking {
                    fixture.repository.countVenueConversationUnread(fixture.venueId, fixture.ownerUserId)
                },
            )
            assertEquals(
                1,
                runBlocking {
                    fixture.repository.listVenueThreads(
                        venueId = fixture.venueId,
                        viewerUserId = fixture.ownerUserId,
                        threadTypes = setOf(SupportThreadType.BOOKING_THREAD, SupportThreadType.VENUE_CHAT),
                    ).single { it.id == threadId }.unreadCount
                },
            )

            fixture.seedRead(
                threadId = threadId,
                userId = fixture.guestUserId,
                readAt = Instant.parse("2031-02-03T10:14:45Z"),
                lastReadMessageId = ownMessageId,
            )
            assertEquals(
                1,
                runBlocking {
                    fixture.repository.listGuestThreads(
                        userId = fixture.guestUserId,
                        filter = null,
                        threadTypes = GuestThreadSurface.CONVERSATIONS.expectedThreadTypes,
                    ).single { it.id == threadId }.unreadCount
                },
            )
            assertNotNull(
                runBlocking {
                    fixture.repository.getGuestThreadAndMarkRead(
                        userId = fixture.guestUserId,
                        threadId = threadId,
                        surface = GuestThreadSurface.CONVERSATIONS,
                    )
                },
            )
            assertEquals(
                firstSystemMessageId,
                fixture.readMarker(threadId, fixture.guestUserId)?.lastReadMessageId,
            )
            assertEquals(
                0,
                runBlocking {
                    fixture.repository.listGuestThreads(
                        userId = fixture.guestUserId,
                        filter = null,
                        threadTypes = GuestThreadSurface.CONVERSATIONS.expectedThreadTypes,
                    ).single { it.id == threadId }.unreadCount
                },
            )
            assertEquals(
                1,
                runBlocking {
                    fixture.repository.countVenueConversationUnread(fixture.venueId, fixture.ownerUserId)
                },
            )

            fixture.seedRead(
                threadId = threadId,
                userId = fixture.ownerUserId,
                readAt = Instant.parse("2031-02-03T10:14:45Z"),
                lastReadMessageId = ownMessageId,
            )
            assertEquals(
                1,
                runBlocking {
                    fixture.repository.countVenueConversationUnread(fixture.venueId, fixture.ownerUserId)
                },
            )

            val opened =
                assertNotNull(
                    runBlocking {
                        fixture.repository.getVenueThreadAndMarkRead(
                            venueId = fixture.venueId,
                            threadId = threadId,
                            viewerUserId = fixture.ownerUserId,
                            allowedThreadTypes = setOf(SupportThreadType.BOOKING_THREAD, SupportThreadType.VENUE_CHAT),
                        )
                    },
                )
            assertEquals(listOf(ownMessageId, firstSystemMessageId), opened.messages.map { it.id })
            val openedMarker = assertNotNull(fixture.readMarker(threadId, fixture.ownerUserId))
            assertEquals(threadId, openedMarker.threadId)
            assertEquals(fixture.ownerUserId, openedMarker.userId)
            assertEquals(firstSystemMessageId, openedMarker.lastReadMessageId)
            assertNotNull(openedMarker.lastReadAt)
            assertEquals(
                0,
                runBlocking {
                    fixture.repository.countVenueConversationUnread(fixture.venueId, fixture.ownerUserId)
                },
            )

            val secondSystemMessageId =
                fixture.seedMessage(
                    threadId,
                    null,
                    Instant.parse("2031-02-03T10:16:30Z"),
                    "Новый системный контекст",
                    authorRole = SupportMessageAuthorRole.SYSTEM,
                    source = SupportMessageSource.SYSTEM,
                )
            assertTrue(secondSystemMessageId > firstSystemMessageId)
            assertEquals(
                1,
                runBlocking {
                    fixture.repository.countVenueConversationUnread(fixture.venueId, fixture.ownerUserId)
                },
            )

            assertNotNull(
                runBlocking {
                    fixture.repository.getVenueThreadAndMarkRead(
                        venueId = fixture.venueId,
                        threadId = threadId,
                        viewerUserId = fixture.ownerUserId,
                        allowedThreadTypes = setOf(SupportThreadType.BOOKING_THREAD, SupportThreadType.VENUE_CHAT),
                    )
                },
            )
            fixture.seedMessage(
                threadId,
                fixture.ownerUserId,
                Instant.parse("2031-02-03T10:17:30Z"),
                "Своё после открытия",
                authorRole = SupportMessageAuthorRole.VENUE,
                source = SupportMessageSource.VENUE_MINIAPP,
            )
            assertEquals(
                0,
                runBlocking {
                    fixture.repository.countVenueConversationUnread(fixture.venueId, fixture.ownerUserId)
                },
            )
        }

    @Test
    fun `NULL author support and unrelated threads stay excluded and unauthorized actors get no count facts`() =
        withFixture { fixture ->
            val venueChat = fixture.seedThread(SupportThreadType.VENUE_CHAT)
            val unrelatedVenueChat = fixture.seedThread(SupportThreadType.VENUE_CHAT)
            val supportTicket = fixture.seedThread(SupportThreadType.SUPPORT_TICKET)
            fixture.seedMessage(
                venueChat,
                null,
                Instant.parse("2031-02-03T10:15:30Z"),
                "Visible venue system message",
                authorRole = SupportMessageAuthorRole.SYSTEM,
                source = SupportMessageSource.SYSTEM,
            )
            val unrelatedMessage =
                fixture.seedMessage(
                    unrelatedVenueChat,
                    fixture.guestUserId,
                    Instant.parse("2031-02-03T10:16:30Z"),
                    "Other thread",
                )
            fixture.seedRead(
                unrelatedVenueChat,
                fixture.ownerUserId,
                Instant.parse("2031-02-03T10:16:45Z"),
                unrelatedMessage,
            )
            fixture.seedMessage(
                supportTicket,
                null,
                Instant.parse("2031-02-03T10:17:30Z"),
                "System support context",
                authorRole = SupportMessageAuthorRole.SYSTEM,
                source = SupportMessageSource.SYSTEM,
            )
            val unrelatedBefore = assertNotNull(fixture.readMarker(unrelatedVenueChat, fixture.ownerUserId))

            assertEquals(
                1,
                runBlocking {
                    fixture.repository.countVenueConversationUnread(fixture.venueId, fixture.ownerUserId)
                },
            )
            assertEquals(
                1,
                runBlocking {
                    fixture.repository.listGuestThreads(
                        userId = fixture.guestUserId,
                        filter = null,
                        threadTypes = GuestThreadSurface.SUPPORT.expectedThreadTypes,
                    ).single { it.id == supportTicket }.unreadCount
                },
            )
            listOf(
                fixture.foreignVenueManagerUserId,
                fixture.staffUserId,
                fixture.platformOwnerUserId,
            ).forEach { unauthorizedUserId ->
                assertEquals(
                    0,
                    runBlocking {
                        fixture.repository.countVenueConversationUnread(fixture.venueId, unauthorizedUserId)
                    },
                )
            }

            assertNotNull(
                runBlocking {
                    fixture.repository.getVenueThreadAndMarkRead(
                        venueId = fixture.venueId,
                        threadId = venueChat,
                        viewerUserId = fixture.ownerUserId,
                        allowedThreadTypes = setOf(SupportThreadType.BOOKING_THREAD, SupportThreadType.VENUE_CHAT),
                    )
                },
            )
            assertEquals(unrelatedBefore, fixture.readMarker(unrelatedVenueChat, fixture.ownerUserId))
            assertEquals(
                0,
                runBlocking {
                    fixture.repository.countVenueConversationUnread(fixture.venueId, fixture.ownerUserId)
                },
            )
        }

    @Test
    fun `atomic venue detail read leaves a message committed after its snapshot unread`() =
        withFixture { fixture ->
            val threadId = fixture.seedBookingThread()
            val bookingId =
                requireNotNull(
                    runBlocking { fixture.repository.getVenueThread(fixture.venueId, threadId) }
                        ?.thread
                        ?.bookingId,
                )
            val afterThreadLock = CountDownLatch(1)
            val releaseRead = CountDownLatch(1)
            val writerStarted = CountDownLatch(1)
            val readRepository =
                SupportThreadRepository(
                    dataSource = fixture.dataSource,
                    supportThreadReadCheckpoint = { checkpoint ->
                        if (checkpoint == SupportThreadReadCheckpoint.AFTER_THREAD_LOCK) {
                            afterThreadLock.countDown()
                            check(releaseRead.await(5, TimeUnit.SECONDS)) { "read release was not signalled" }
                        }
                    },
                )
            val executor = Executors.newFixedThreadPool(2)
            try {
                val readFuture =
                    executor.submit<SupportThreadDetailRecord?> {
                        runBlocking {
                            readRepository.getVenueThreadAndMarkRead(
                                venueId = fixture.venueId,
                                threadId = threadId,
                                viewerUserId = fixture.ownerUserId,
                                allowedThreadTypes = setOf(SupportThreadType.BOOKING_THREAD),
                            )
                        }
                    }
                assertTrue(afterThreadLock.await(5, TimeUnit.SECONDS))

                val writeFuture =
                    executor.submit<BookingThreadMessageRecord?> {
                        writerStarted.countDown()
                        runBlocking {
                            fixture.repository.addBookingMessage(
                                bookingId = bookingId,
                                authorUserId = fixture.guestUserId,
                                authorRole = SupportMessageAuthorRole.GUEST,
                                source = SupportMessageSource.GUEST_BOT,
                                text = "Сообщение после снимка",
                                telegramMessageId = 91_001L,
                                expectedThreadId = threadId,
                                expectedGuestUserId = fixture.guestUserId,
                            )
                        }
                    }
                assertTrue(writerStarted.await(5, TimeUnit.SECONDS))
                releaseRead.countDown()

                val firstDetail = assertNotNull(readFuture.get(5, TimeUnit.SECONDS))
                assertEquals(0, firstDetail.messages.size)
                assertNotNull(writeFuture.get(5, TimeUnit.SECONDS))
                assertEquals(
                    1,
                    runBlocking {
                        fixture.repository.countVenueConversationUnread(fixture.venueId, fixture.ownerUserId)
                    },
                )

                val refreshed =
                    assertNotNull(
                        runBlocking {
                            fixture.repository.getVenueThreadAndMarkRead(
                                venueId = fixture.venueId,
                                threadId = threadId,
                                viewerUserId = fixture.ownerUserId,
                                allowedThreadTypes = setOf(SupportThreadType.BOOKING_THREAD),
                            )
                        },
                    )
                assertEquals(1, refreshed.messages.size)
                assertEquals(
                    0,
                    runBlocking {
                        fixture.repository.countVenueConversationUnread(fixture.venueId, fixture.ownerUserId)
                    },
                )
            } finally {
                releaseRead.countDown()
                executor.shutdownNow()
                assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS))
            }
        }

    @Test
    fun `standalone read rolls back when failure is injected after marker write`() =
        withFixture { fixture ->
            val threadId = fixture.seedThread(SupportThreadType.SUPPORT_TICKET)
            val repository =
                SupportThreadRepository(
                    dataSource = fixture.dataSource,
                    supportThreadReadCheckpoint = { checkpoint ->
                        if (checkpoint == SupportThreadReadCheckpoint.AFTER_MARKER_WRITE) {
                            error("injected read marker failure")
                        }
                    },
                )

            assertFailsWith<IllegalStateException> {
                runBlocking {
                    repository.markThreadRead(
                        threadId,
                        SupportThreadReadAccess.Guest(fixture.guestUserId),
                    )
                }
            }
            assertEquals(0, fixture.countReads())
        }

    @Test
    fun `connection aware read participates in caller rollback`() =
        withFixture { fixture ->
            val threadId = fixture.seedThread(SupportThreadType.SUPPORT_TICKET)

            fixture.dataSource.connection.use { connection ->
                connection.autoCommit = false
                fixture.repository.lockGuestThread(connection, fixture.guestUserId, threadId)
                assertEquals(
                    SupportThreadReadResult.MARKED,
                    fixture.repository.markNonBookingThreadReadAfterThreadLock(
                        connection,
                        threadId,
                        SupportThreadReadAccess.Guest(fixture.guestUserId),
                    ),
                )
                assertEquals(1, fixture.countReads(connection))
                connection.rollback()
            }

            assertEquals(0, fixture.countReads())
        }

    @Test
    fun `connection aware after-thread-lock API rejects booking threads without taking booking after thread`() =
        withFixture { fixture ->
            val threadId = fixture.seedBookingThread()

            fixture.dataSource.connection.use { connection ->
                connection.autoCommit = false
                fixture.repository.lockGuestThread(connection, fixture.guestUserId, threadId)
                assertEquals(
                    SupportThreadReadResult.NOT_FOUND,
                    fixture.repository.markNonBookingThreadReadAfterThreadLock(
                        connection,
                        threadId,
                        SupportThreadReadAccess.Guest(fixture.guestUserId),
                    ),
                )
                connection.rollback()
            }

            assertEquals(0, fixture.countReads())
        }

    @Test
    fun `missing or deleted thread returns not found without a marker`() =
        withFixture { fixture ->
            val threadId = fixture.seedThread(SupportThreadType.SUPPORT_TICKET)
            fixture.deleteThread(threadId)

            val result =
                runBlocking {
                    fixture.repository.markThreadRead(
                        threadId,
                        SupportThreadReadAccess.Guest(fixture.guestUserId),
                    )
                }

            assertEquals(SupportThreadReadResult.NOT_FOUND, result)
            assertEquals(0, fixture.countReads())
        }

    private fun withFixture(block: (Fixture) -> Unit) {
        val dataSource =
            JdbcDataSource().apply {
                setURL(
                    "jdbc:h2:mem:support-thread-read-${UUID.randomUUID()};MODE=PostgreSQL;" +
                        "DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1",
                )
                user = "sa"
                password = ""
            }
        migrateH2OnboardingFixture(dataSource)
        block(Fixture(dataSource))
    }

    private class Fixture(
        val dataSource: DataSource,
    ) {
        val repository = SupportThreadRepository(dataSource)
        val guestUserId = 8_840_001L
        val foreignGuestUserId = 8_840_002L
        val ownerUserId = 8_840_003L
        val managerUserId = 8_840_004L
        val staffUserId = 8_840_005L
        val platformOwnerUserId = 8_840_006L
        val foreignVenueManagerUserId = 8_840_007L
        val venueId: Long
        val foreignVenueId: Long

        init {
            dataSource.connection.use { connection ->
                listOf(
                    guestUserId,
                    foreignGuestUserId,
                    ownerUserId,
                    managerUserId,
                    staffUserId,
                    platformOwnerUserId,
                    foreignVenueManagerUserId,
                ).forEach { insertUser(connection, it) }
                venueId = insertVenue(connection, "Read Policy Venue")
                foreignVenueId = insertVenue(connection, "Foreign Read Policy Venue")
                insertMembership(connection, venueId, ownerUserId, "OWNER")
                insertMembership(connection, venueId, managerUserId, "MANAGER")
                insertMembership(connection, venueId, staffUserId, "STAFF")
                insertMembership(connection, foreignVenueId, foreignVenueManagerUserId, "MANAGER")
            }
        }

        fun seedBookingThread(): Long {
            val bookingId = seedBooking()
            return seedThread(SupportThreadType.BOOKING_THREAD, bookingId = bookingId)
        }

        fun seedThread(
            threadType: SupportThreadType,
            assigneeScope: SupportAssigneeScope = SupportAssigneeScope.VENUE,
            bookingId: Long? = null,
        ): Long =
            dataSource.connection.use { connection ->
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
                    VALUES (?, ?, ?, 'IN_PROGRESS', ?, ?, ?, ?, ?)
                    """.trimIndent(),
                    Statement.RETURN_GENERATED_KEYS,
                ).use { statement ->
                    statement.setLong(1, venueId)
                    statement.setLong(2, guestUserId)
                    statement.setString(
                        3,
                        if (threadType == SupportThreadType.BOOKING_THREAD) {
                            SupportThreadCategory.BOOKING.name
                        } else {
                            SupportThreadCategory.OTHER.name
                        },
                    )
                    if (bookingId == null) {
                        statement.setNull(4, java.sql.Types.BIGINT)
                    } else {
                        statement.setLong(4, bookingId)
                    }
                    statement.setString(5, threadType.name)
                    statement.setString(6, assigneeScope.name)
                    statement.setString(
                        7,
                        if (threadType == SupportThreadType.BOOKING_THREAD) {
                            SupportThreadCreatedSource.BOOKING_FLOW.name
                        } else {
                            SupportThreadCreatedSource.GUEST_MINIAPP.name
                        },
                    )
                    statement.setString(8, "Read policy ${threadType.name}")
                    statement.executeUpdate()
                    statement.generatedKeys.use { keys ->
                        check(keys.next())
                        keys.getLong(1)
                    }
                }
            }

        fun seedRead(
            threadId: Long,
            userId: Long,
            readAt: Instant,
            lastReadMessageId: Long? = null,
        ) {
            dataSource.connection.use { connection ->
                connection.prepareStatement(
                    """
                    INSERT INTO support_thread_reads (thread_id, user_id, last_read_at, last_read_message_id)
                    VALUES (?, ?, ?, ?)
                    """.trimIndent(),
                ).use { statement ->
                    statement.setLong(1, threadId)
                    statement.setLong(2, userId)
                    statement.setTimestamp(3, Timestamp.from(readAt))
                    if (lastReadMessageId == null) {
                        statement.setNull(4, java.sql.Types.BIGINT)
                    } else {
                        statement.setLong(4, lastReadMessageId)
                    }
                    statement.executeUpdate()
                }
            }
        }

        fun seedMessage(
            threadId: Long,
            authorUserId: Long?,
            createdAt: Instant,
            text: String,
            authorRole: SupportMessageAuthorRole = SupportMessageAuthorRole.GUEST,
            source: SupportMessageSource = SupportMessageSource.GUEST_MINIAPP,
        ): Long =
            dataSource.connection.use { connection ->
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
                    VALUES (?, ?, ?, ?, ?, ?)
                    """.trimIndent(),
                    Statement.RETURN_GENERATED_KEYS,
                ).use { statement ->
                    statement.setLong(1, threadId)
                    if (authorUserId == null) {
                        statement.setNull(2, java.sql.Types.BIGINT)
                    } else {
                        statement.setLong(2, authorUserId)
                    }
                    statement.setString(3, authorRole.name)
                    statement.setString(4, source.name)
                    statement.setString(5, text)
                    statement.setTimestamp(6, Timestamp.from(createdAt))
                    statement.executeUpdate()
                    statement.generatedKeys.use { keys ->
                        check(keys.next())
                        keys.getLong(1)
                    }
                }
            }

        fun readAt(
            threadId: Long,
            userId: Long,
        ): Instant? =
            dataSource.connection.use { connection ->
                connection.prepareStatement(
                    "SELECT last_read_at FROM support_thread_reads WHERE thread_id = ? AND user_id = ?",
                ).use { statement ->
                    statement.setLong(1, threadId)
                    statement.setLong(2, userId)
                    statement.executeQuery().use { rows ->
                        if (rows.next()) rows.getTimestamp(1).toInstant() else null
                    }
                }
            }

        fun readMarker(
            threadId: Long,
            userId: Long,
        ): ReadMarker? =
            dataSource.connection.use { connection ->
                connection.prepareStatement(
                    """
                    SELECT thread_id, user_id, last_read_message_id, last_read_at
                    FROM support_thread_reads
                    WHERE thread_id = ?
                      AND user_id = ?
                    """.trimIndent(),
                ).use { statement ->
                    statement.setLong(1, threadId)
                    statement.setLong(2, userId)
                    statement.executeQuery().use { rows ->
                        if (!rows.next()) {
                            null
                        } else {
                            ReadMarker(
                                threadId = rows.getLong("thread_id"),
                                userId = rows.getLong("user_id"),
                                lastReadMessageId =
                                    rows.getLong("last_read_message_id").takeUnless { rows.wasNull() },
                                lastReadAt = rows.getTimestamp("last_read_at")?.toInstant(),
                            )
                        }
                    }
                }
            }

        fun messageIds(threadId: Long): List<Long> =
            dataSource.connection.use { connection ->
                connection.prepareStatement(
                    "SELECT id FROM support_messages WHERE thread_id = ? ORDER BY id",
                ).use { statement ->
                    statement.setLong(1, threadId)
                    statement.executeQuery().use { rows ->
                        buildList {
                            while (rows.next()) add(rows.getLong(1))
                        }
                    }
                }
            }

        fun countReads(): Int = dataSource.connection.use(::countReads)

        fun countReads(connection: Connection): Int =
            connection.createStatement().use { statement ->
                statement.executeQuery("SELECT COUNT(*) FROM support_thread_reads").use { rows ->
                    rows.next()
                    rows.getInt(1)
                }
            }

        fun updateThreadVenue(
            threadId: Long,
            newVenueId: Long,
        ) {
            dataSource.connection.use { connection ->
                connection.prepareStatement("UPDATE support_threads SET venue_id = ? WHERE id = ?").use { statement ->
                    statement.setLong(1, newVenueId)
                    statement.setLong(2, threadId)
                    assertEquals(1, statement.executeUpdate())
                }
            }
        }

        fun allowInvalidAssigneeScopes() {
            dataSource.connection.use { connection ->
                connection.createStatement().use { statement ->
                    statement.execute(
                        "ALTER TABLE support_threads DROP CONSTRAINT chk_support_threads_assignee_scope",
                    )
                    statement.execute("ALTER TABLE support_threads ALTER COLUMN assignee_scope DROP NOT NULL")
                }
            }
        }

        fun updateThreadAssigneeScope(
            threadId: Long,
            assigneeScope: String?,
        ) {
            dataSource.connection.use { connection ->
                connection.prepareStatement(
                    "UPDATE support_threads SET assignee_scope = ? WHERE id = ?",
                ).use { statement ->
                    when (assigneeScope) {
                        null -> statement.setNull(1, java.sql.Types.VARCHAR)
                        else -> statement.setString(1, assigneeScope)
                    }
                    statement.setLong(2, threadId)
                    assertEquals(1, statement.executeUpdate())
                }
            }
        }

        fun deleteThread(threadId: Long) {
            dataSource.connection.use { connection ->
                connection.prepareStatement("DELETE FROM support_threads WHERE id = ?").use { statement ->
                    statement.setLong(1, threadId)
                    assertEquals(1, statement.executeUpdate())
                }
            }
        }

        private fun seedBooking(): Long =
            dataSource.connection.use { connection ->
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
                    statement.setLong(2, guestUserId)
                    statement.setTimestamp(3, Timestamp.from(Instant.parse("2030-01-10T18:00:00Z")))
                    statement.setDate(4, Date.valueOf("2030-01-10"))
                    statement.executeUpdate()
                    statement.generatedKeys.use { keys ->
                        check(keys.next())
                        keys.getLong(1)
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
                statement.setString(2, "read_policy_$userId")
                statement.executeUpdate()
            }
        }

        private fun insertVenue(
            connection: Connection,
            name: String,
        ): Long =
            connection.prepareStatement(
                "INSERT INTO venues (name, city, address, status) VALUES (?, 'Moscow', 'Read street, 1', 'PUBLISHED')",
                Statement.RETURN_GENERATED_KEYS,
            ).use { statement ->
                statement.setString(1, name)
                statement.executeUpdate()
                statement.generatedKeys.use { keys ->
                    check(keys.next())
                    keys.getLong(1)
                }
            }

        private fun insertMembership(
            connection: Connection,
            venueId: Long,
            userId: Long,
            role: String,
        ) {
            connection.prepareStatement(
                "INSERT INTO venue_members (venue_id, user_id, role) VALUES (?, ?, ?)",
            ).use { statement ->
                statement.setLong(1, venueId)
                statement.setLong(2, userId)
                statement.setString(3, role)
                statement.executeUpdate()
            }
        }
    }

    private data class ReadMarker(
        val threadId: Long,
        val userId: Long,
        val lastReadMessageId: Long?,
        val lastReadAt: Instant?,
    )
}
