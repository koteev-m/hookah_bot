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
import javax.sql.DataSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

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
        ) {
            dataSource.connection.use { connection ->
                connection.prepareStatement(
                    "INSERT INTO support_thread_reads (thread_id, user_id, last_read_at) VALUES (?, ?, ?)",
                ).use { statement ->
                    statement.setLong(1, threadId)
                    statement.setLong(2, userId)
                    statement.setTimestamp(3, Timestamp.from(readAt))
                    statement.executeUpdate()
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
}
