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
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class SupportThreadReadConcurrencyPostgresTest {
    @Test
    fun `concurrent production reads serialize on parents and create one marker`() =
        runBlocking {
            val database = PostgresTestEnv.createDatabase()
            PostgresTestEnv.createDataSource(database).use { dataSource ->
                val fixture = seedFixture(dataSource.connection)
                val thread =
                    assertNotNull(
                        SupportThreadRepository(dataSource).createOrFindBookingThread(fixture.bookingId),
                    )
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
                val access = SupportThreadReadAccess.Guest(fixture.guestUserId)
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
                }
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
