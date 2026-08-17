package com.hookah.platform.backend.support

import com.hookah.platform.backend.test.PostgresTestDatabase
import com.hookah.platform.backend.test.PostgresTestEnv
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.flywaydb.core.api.FlywayException
import java.sql.Connection
import java.sql.DriverManager
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class BookingThreadIntegrityMigrationConcurrencyPostgresTest {
    @Test
    fun `writer-first production read commits before V124 guards and migration fails unchanged`() =
        runBlocking {
            val database = PostgresTestEnv.createDatabase()
            PostgresTestEnv.createDataSource(database, migrate = false).use { dataSource ->
                val fixture =
                    BookingThreadIntegrityMigrationTestSupport.prepareConcurrencyFixture(
                        dataSource = dataSource,
                        location = POSTGRES_MIGRATIONS,
                        previousVersion = PREVIOUS_VERSION,
                    )
                val beforeWriterSnapshot =
                    dataSource.connection.use { connection ->
                        BookingThreadIntegrityMigrationTestSupport
                            .loadDomainSnapshotIgnoringWriterReadForConcurrency(connection, fixture)
                    }
                val applicationName = writerApplicationName("writer_first", database)
                writerDataSource(database, applicationName).use { writerDataSource ->
                    directConnection(database).use { observer ->
                        val markerWritten = CountDownLatch(1)
                        val allowWriterCommit = CountDownLatch(1)
                        val repository =
                            SupportThreadRepository(
                                dataSource = writerDataSource,
                                supportThreadReadCheckpoint = { checkpoint ->
                                    if (checkpoint == SupportThreadReadCheckpoint.AFTER_MARKER_WRITE) {
                                        markerWritten.countDown()
                                        check(
                                            allowWriterCommit.await(
                                                WRITER_CHECKPOINT_TIMEOUT_SECONDS,
                                                TimeUnit.SECONDS,
                                            ),
                                        ) { "Timed out waiting to release the production read transaction" }
                                    }
                                },
                            )
                        val writerPid = captureOnlyPoolBackendPid(writerDataSource, applicationName)
                        val writer =
                            async(Dispatchers.IO) {
                                runCatching {
                                    repository.markThreadRead(
                                        threadId = fixture.duplicateThreadId,
                                        access = SupportThreadReadAccess.Guest(fixture.readerUserId),
                                    )
                                }
                            }
                        try {
                            assertTrue(
                                markerWritten.await(WAIT_TIMEOUT_SECONDS, TimeUnit.SECONDS),
                                "Production read did not reach AFTER_MARKER_WRITE. " + describeActivity(observer),
                            )
                            val migration =
                                async(Dispatchers.IO) {
                                    runCatching {
                                        BookingThreadIntegrityMigrationTestSupport.flywayForConcurrency(
                                            dataSource = dataSource,
                                            location = POSTGRES_MIGRATIONS,
                                            expectedVersion = EXPECTED_VERSION,
                                        ).migrate()
                                    }.exceptionOrNull()
                                }

                            val lockWait = awaitWriterFirstMigrationLockWait(observer, writerPid, migration)
                            assertTrue(lockWait.blockedByWriter, lockWait.diagnostic)
                            assertTrue(lockWait.waitingForBookingsExclusive, lockWait.diagnostic)
                            assertTrue(lockWait.writerHoldsBookingsRowShare, lockWait.diagnostic)
                            assertTrue(lockWait.writerHoldsThreadsRowShare, lockWait.diagnostic)
                            assertTrue(lockWait.writerHoldsReadsRowExclusive, lockWait.diagnostic)
                            assertFalse(assertNotNull(lockWait.migrationPid) == writerPid, lockWait.diagnostic)

                            allowWriterCommit.countDown()
                            assertEquals(
                                SupportThreadReadResult.MARKED,
                                withTimeout(TimeUnit.SECONDS.toMillis(WAIT_TIMEOUT_SECONDS)) {
                                    writer.await()
                                }.getOrThrow(),
                            )
                            val failure =
                                withTimeout(TimeUnit.SECONDS.toMillis(WAIT_TIMEOUT_SECONDS)) {
                                    migration.await()
                                }
                            val flywayFailure = assertIs<FlywayException>(assertNotNull(failure))
                            assertTrue(
                                throwableMessages(flywayFailure).any {
                                    it.contains("partial user coverage", ignoreCase = true)
                                },
                                flywayFailure.stackTraceToString(),
                            )

                            assertEquals(
                                beforeWriterSnapshot,
                                BookingThreadIntegrityMigrationTestSupport
                                    .loadDomainSnapshotIgnoringWriterReadForConcurrency(observer, fixture),
                            )
                            assertEquals(
                                listOf(fixture.duplicateThreadId),
                                readMarkerThreadIds(observer, fixture.readerUserId),
                            )
                            assertEquals(
                                PREVIOUS_VERSION,
                                BookingThreadIntegrityMigrationTestSupport.currentVersion(
                                    dataSource,
                                    POSTGRES_MIGRATIONS,
                                ),
                            )
                            assertFalse(uniqueIndexExists(observer))
                        } finally {
                            allowWriterCommit.countDown()
                        }
                    }
                }
            }
        }

    @Test
    fun `migration-first V124 blocks production read before child then safely serializes`() =
        runBlocking {
            val database = PostgresTestEnv.createDatabase()
            PostgresTestEnv.createDataSource(database, migrate = false).use { dataSource ->
                val fixture =
                    BookingThreadIntegrityMigrationTestSupport.prepareConcurrencyFixture(
                        dataSource = dataSource,
                        location = POSTGRES_MIGRATIONS,
                        previousVersion = PREVIOUS_VERSION,
                    )
                val survivorApplicationName = writerApplicationName("migration_first_survivor", database)
                val deletedApplicationName = writerApplicationName("migration_first_deleted", database)
                writerDataSource(database, survivorApplicationName).use { survivorWriterDataSource ->
                    writerDataSource(database, deletedApplicationName).use { deletedWriterDataSource ->
                        directConnection(database).use { blocker ->
                            directConnection(database).use { observer ->
                                val survivorCheckpoints = CopyOnWriteArrayList<SupportThreadReadCheckpoint>()
                                val deletedCheckpoints = CopyOnWriteArrayList<SupportThreadReadCheckpoint>()
                                val survivorRepository =
                                    SupportThreadRepository(
                                        dataSource = survivorWriterDataSource,
                                        supportThreadReadCheckpoint = { checkpoint ->
                                            survivorCheckpoints += checkpoint
                                        },
                                    )
                                val deletedRepository =
                                    SupportThreadRepository(
                                        dataSource = deletedWriterDataSource,
                                        supportThreadReadCheckpoint = { checkpoint ->
                                            deletedCheckpoints += checkpoint
                                        },
                                    )
                                val survivorWriterPid =
                                    captureOnlyPoolBackendPid(survivorWriterDataSource, survivorApplicationName)
                                val deletedWriterPid =
                                    captureOnlyPoolBackendPid(deletedWriterDataSource, deletedApplicationName)
                                assertFalse(survivorWriterPid == deletedWriterPid)
                                blocker.autoCommit = false
                                var blockerReleased = false
                                try {
                                    lockSupportMessages(blocker)
                                    val blockerPid = backendPid(blocker)
                                    val migration =
                                        async(Dispatchers.IO) {
                                            runCatching {
                                                BookingThreadIntegrityMigrationTestSupport.flywayForConcurrency(
                                                    dataSource = dataSource,
                                                    location = POSTGRES_MIGRATIONS,
                                                    expectedVersion = EXPECTED_VERSION,
                                                ).migrate()
                                            }
                                        }

                                    val parentLocks =
                                        awaitMigrationParentLocks(
                                            observer = observer,
                                            blockerPid = blockerPid,
                                            migration = migration,
                                            deadline = migrationFirstObservationDeadline(),
                                        )
                                    assertTrue(parentLocks.blockedBySupportMessagesBlocker, parentLocks.diagnostic)
                                    assertTrue(parentLocks.holdsBookingsExclusive, parentLocks.diagnostic)
                                    assertTrue(parentLocks.holdsThreadsExclusive, parentLocks.diagnostic)
                                    assertTrue(parentLocks.waitingForMessagesExclusive, parentLocks.diagnostic)
                                    assertTrue(parentLocks.blockerHoldsMessagesRowExclusive, parentLocks.diagnostic)
                                    assertFalse(parentLocks.holdsReadsExclusive, parentLocks.diagnostic)
                                    val migrationPid = assertNotNull(parentLocks.migrationPid)
                                    assertEquals(
                                        4,
                                        setOf(
                                            migrationPid,
                                            blockerPid,
                                            survivorWriterPid,
                                            deletedWriterPid,
                                        ).size,
                                        parentLocks.diagnostic,
                                    )

                                    val survivorWriter =
                                        async(Dispatchers.IO) {
                                            runCatching {
                                                survivorRepository.markThreadRead(
                                                    threadId = fixture.survivorThreadId,
                                                    access = SupportThreadReadAccess.Guest(fixture.readerUserId),
                                                )
                                            }
                                        }
                                    val survivorWait =
                                        awaitMigrationFirstWriterWait(
                                            observer = observer,
                                            migrationPid = migrationPid,
                                            writerPid = survivorWriterPid,
                                            writer = survivorWriter,
                                            deadline = migrationFirstObservationDeadline(),
                                        )
                                    assertMigrationFirstWriterWait(survivorWait)

                                    val deletedWriter =
                                        async(Dispatchers.IO) {
                                            runCatching {
                                                deletedRepository.markThreadRead(
                                                    threadId = fixture.duplicateThreadId,
                                                    access = SupportThreadReadAccess.Guest(fixture.readerUserId),
                                                )
                                            }
                                        }
                                    val deletedWait =
                                        awaitMigrationFirstWriterWait(
                                            observer = observer,
                                            migrationPid = migrationPid,
                                            writerPid = deletedWriterPid,
                                            writer = deletedWriter,
                                            deadline = migrationFirstObservationDeadline(),
                                        )
                                    assertMigrationFirstWriterWait(deletedWait)

                                    blocker.commit()
                                    blockerReleased = true
                                    val migrationResult =
                                        withTimeout(TimeUnit.SECONDS.toMillis(WAIT_TIMEOUT_SECONDS)) {
                                            migration.await()
                                        }.getOrThrow()
                                    assertEquals(1, migrationResult.migrationsExecuted)
                                    assertEquals(
                                        SupportThreadReadResult.MARKED,
                                        withTimeout(TimeUnit.SECONDS.toMillis(WAIT_TIMEOUT_SECONDS)) {
                                            survivorWriter.await()
                                        }.getOrThrow(),
                                    )
                                    assertEquals(
                                        SupportThreadReadResult.NOT_FOUND,
                                        withTimeout(TimeUnit.SECONDS.toMillis(WAIT_TIMEOUT_SECONDS)) {
                                            deletedWriter.await()
                                        }.getOrThrow(),
                                    )
                                    assertEquals(
                                        listOf(
                                            SupportThreadReadCheckpoint.AFTER_THREAD_LOCK,
                                            SupportThreadReadCheckpoint.AFTER_MARKER_WRITE,
                                        ),
                                        survivorCheckpoints,
                                    )
                                    assertTrue(deletedCheckpoints.isEmpty())

                                    assertSuccessfulSerializedState(observer, fixture)
                                    assertEquals(
                                        EXPECTED_VERSION,
                                        BookingThreadIntegrityMigrationTestSupport.currentVersion(
                                            dataSource,
                                            POSTGRES_MIGRATIONS,
                                        ),
                                    )
                                    assertTrue(uniqueIndexExists(observer))
                                } finally {
                                    if (!blockerReleased) runCatching { blocker.rollback() }
                                }
                            }
                        }
                    }
                }
            }
        }

    private fun awaitWriterFirstMigrationLockWait(
        observer: Connection,
        writerPid: Int,
        migration: Deferred<Throwable?>,
    ): WriterFirstLockObservation {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(WAIT_TIMEOUT_SECONDS)
        var last = WriterFirstLockObservation.missing(describeActivity(observer))
        while (System.nanoTime() < deadline) {
            last =
                readWriterFirstMigrationLockWait(observer, writerPid)
                    ?: WriterFirstLockObservation.missing(describeActivity(observer))
            if (
                last.blockedByWriter &&
                last.waitingForBookingsExclusive &&
                last.writerHoldsBookingsRowShare &&
                last.writerHoldsThreadsRowShare &&
                last.writerHoldsReadsRowExclusive
            ) {
                return last
            }
            if (migration.isCompleted) {
                return last.copy(
                    diagnostic = "Migration completed before the table-lock wait was observed. ${last.diagnostic}",
                )
            }
            Thread.yield()
        }
        return last
    }

    private fun readWriterFirstMigrationLockWait(
        observer: Connection,
        writerPid: Int,
    ): WriterFirstLockObservation? =
        observer.prepareStatement(
            """
            SELECT
                activity.pid,
                ? = ANY(pg_blocking_pids(activity.pid)) AS blocked_by_writer,
                EXISTS (
                    SELECT 1
                    FROM pg_locks lock
                    JOIN pg_class relation ON relation.oid = lock.relation
                    JOIN pg_namespace namespace ON namespace.oid = relation.relnamespace
                    WHERE lock.pid = activity.pid
                      AND namespace.nspname = current_schema()
                      AND relation.relname = 'bookings'
                      AND lock.mode = 'ExclusiveLock'
                      AND NOT lock.granted
                ) AS waiting_for_bookings_exclusive,
                EXISTS (
                    SELECT 1
                    FROM pg_locks lock
                    JOIN pg_class relation ON relation.oid = lock.relation
                    JOIN pg_namespace namespace ON namespace.oid = relation.relnamespace
                    WHERE lock.pid = ?
                      AND namespace.nspname = current_schema()
                      AND relation.relname = 'bookings'
                      AND lock.mode = 'RowShareLock'
                      AND lock.granted
                ) AS writer_holds_bookings_row_share,
                EXISTS (
                    SELECT 1
                    FROM pg_locks lock
                    JOIN pg_class relation ON relation.oid = lock.relation
                    JOIN pg_namespace namespace ON namespace.oid = relation.relnamespace
                    WHERE lock.pid = ?
                      AND namespace.nspname = current_schema()
                      AND relation.relname = 'support_threads'
                      AND lock.mode = 'RowShareLock'
                      AND lock.granted
                ) AS writer_holds_threads_row_share,
                EXISTS (
                    SELECT 1
                    FROM pg_locks lock
                    JOIN pg_class relation ON relation.oid = lock.relation
                    JOIN pg_namespace namespace ON namespace.oid = relation.relnamespace
                    WHERE lock.pid = ?
                      AND namespace.nspname = current_schema()
                      AND relation.relname = 'support_thread_reads'
                      AND lock.mode = 'RowExclusiveLock'
                      AND lock.granted
                ) AS writer_holds_reads_row_exclusive,
                pg_blocking_pids(activity.pid) AS blocker_pids
            FROM pg_stat_activity activity
            WHERE activity.datname = current_database()
              AND activity.pid <> pg_backend_pid()
              AND activity.pid <> ?
              AND LOWER(activity.query) LIKE '%lock table%'
              AND LOWER(activity.query) LIKE '%bookings%'
              AND LOWER(activity.query) LIKE '%support_threads%'
              AND EXISTS (
                  SELECT 1
                  FROM pg_locks lock
                  JOIN pg_class relation ON relation.oid = lock.relation
                  JOIN pg_namespace namespace ON namespace.oid = relation.relnamespace
                  WHERE lock.pid = activity.pid
                    AND namespace.nspname = current_schema()
                    AND relation.relname = 'bookings'
                    AND lock.mode = 'ExclusiveLock'
                    AND NOT lock.granted
              )
            ORDER BY activity.pid
            FETCH FIRST 1 ROW ONLY
            """.trimIndent(),
        ).use { statement ->
            statement.setInt(1, writerPid)
            statement.setInt(2, writerPid)
            statement.setInt(3, writerPid)
            statement.setInt(4, writerPid)
            statement.setInt(5, writerPid)
            statement.executeQuery().use { resultSet ->
                if (!resultSet.next()) return@use null
                WriterFirstLockObservation(
                    migrationPid = resultSet.getInt("pid"),
                    blockedByWriter = resultSet.getBoolean("blocked_by_writer"),
                    waitingForBookingsExclusive = resultSet.getBoolean("waiting_for_bookings_exclusive"),
                    writerHoldsBookingsRowShare = resultSet.getBoolean("writer_holds_bookings_row_share"),
                    writerHoldsThreadsRowShare = resultSet.getBoolean("writer_holds_threads_row_share"),
                    writerHoldsReadsRowExclusive = resultSet.getBoolean("writer_holds_reads_row_exclusive"),
                    diagnostic =
                        "migrationPid=${resultSet.getInt("pid")}; writerPid=$writerPid; " +
                            "blockers=${resultSet.getString("blocker_pids")}; activity=${describeActivity(observer)}",
                )
            }
        }

    private fun awaitMigrationParentLocks(
        observer: Connection,
        blockerPid: Int,
        migration: Deferred<*>,
        deadline: Long,
    ): MigrationParentLocksObservation {
        var last = MigrationParentLocksObservation.missing(describeActivity(observer))
        while (System.nanoTime() < deadline) {
            last =
                readMigrationParentLocks(observer, blockerPid)
                    ?: MigrationParentLocksObservation.missing(describeActivity(observer))
            if (
                last.blockedBySupportMessagesBlocker &&
                last.holdsBookingsExclusive &&
                last.holdsThreadsExclusive &&
                last.waitingForMessagesExclusive &&
                last.blockerHoldsMessagesRowExclusive &&
                !last.holdsReadsExclusive
            ) {
                return last
            }
            if (migration.isCompleted) {
                return last.copy(
                    diagnostic = "Migration completed before its parent locks were observed. ${last.diagnostic}",
                )
            }
            Thread.yield()
        }
        return last
    }

    private fun readMigrationParentLocks(
        observer: Connection,
        blockerPid: Int,
    ): MigrationParentLocksObservation? =
        observer.prepareStatement(
            """
            SELECT
                activity.pid,
                ? = ANY(pg_blocking_pids(activity.pid)) AS blocked_by_messages_blocker,
                EXISTS (
                    SELECT 1 FROM pg_locks lock
                    JOIN pg_class relation ON relation.oid = lock.relation
                    JOIN pg_namespace namespace ON namespace.oid = relation.relnamespace
                    WHERE lock.pid = activity.pid
                      AND namespace.nspname = current_schema()
                      AND relation.relname = 'bookings'
                      AND lock.mode = 'ExclusiveLock'
                      AND lock.granted
                ) AS holds_bookings_exclusive,
                EXISTS (
                    SELECT 1 FROM pg_locks lock
                    JOIN pg_class relation ON relation.oid = lock.relation
                    JOIN pg_namespace namespace ON namespace.oid = relation.relnamespace
                    WHERE lock.pid = activity.pid
                      AND namespace.nspname = current_schema()
                      AND relation.relname = 'support_threads'
                      AND lock.mode = 'ExclusiveLock'
                      AND lock.granted
                ) AS holds_threads_exclusive,
                EXISTS (
                    SELECT 1 FROM pg_locks lock
                    JOIN pg_class relation ON relation.oid = lock.relation
                    JOIN pg_namespace namespace ON namespace.oid = relation.relnamespace
                    WHERE lock.pid = activity.pid
                      AND namespace.nspname = current_schema()
                      AND relation.relname = 'support_messages'
                      AND lock.mode = 'ExclusiveLock'
                      AND NOT lock.granted
                ) AS waiting_for_messages_exclusive,
                EXISTS (
                    SELECT 1 FROM pg_locks lock
                    JOIN pg_class relation ON relation.oid = lock.relation
                    JOIN pg_namespace namespace ON namespace.oid = relation.relnamespace
                    WHERE lock.pid = ?
                      AND namespace.nspname = current_schema()
                      AND relation.relname = 'support_messages'
                      AND lock.mode = 'RowExclusiveLock'
                      AND lock.granted
                ) AS blocker_holds_messages_row_exclusive,
                EXISTS (
                    SELECT 1 FROM pg_locks lock
                    JOIN pg_class relation ON relation.oid = lock.relation
                    JOIN pg_namespace namespace ON namespace.oid = relation.relnamespace
                    WHERE lock.pid = activity.pid
                      AND namespace.nspname = current_schema()
                      AND relation.relname = 'support_thread_reads'
                      AND lock.mode = 'ExclusiveLock'
                      AND lock.granted
                ) AS holds_reads_exclusive,
                pg_blocking_pids(activity.pid) AS blocker_pids
            FROM pg_stat_activity activity
            WHERE activity.datname = current_database()
              AND activity.pid <> pg_backend_pid()
              AND activity.pid <> ?
              AND LOWER(activity.query) LIKE '%lock table%'
              AND LOWER(activity.query) LIKE '%support_messages%'
              AND EXISTS (
                  SELECT 1
                  FROM pg_locks lock
                  JOIN pg_class relation ON relation.oid = lock.relation
                  JOIN pg_namespace namespace ON namespace.oid = relation.relnamespace
                  WHERE lock.pid = activity.pid
                    AND namespace.nspname = current_schema()
                    AND relation.relname = 'support_messages'
                    AND lock.mode = 'ExclusiveLock'
                    AND NOT lock.granted
              )
            ORDER BY activity.pid
            FETCH FIRST 1 ROW ONLY
            """.trimIndent(),
        ).use { statement ->
            statement.setInt(1, blockerPid)
            statement.setInt(2, blockerPid)
            statement.setInt(3, blockerPid)
            statement.executeQuery().use { resultSet ->
                if (!resultSet.next()) return@use null
                MigrationParentLocksObservation(
                    migrationPid = resultSet.getInt("pid"),
                    blockedBySupportMessagesBlocker = resultSet.getBoolean("blocked_by_messages_blocker"),
                    holdsBookingsExclusive = resultSet.getBoolean("holds_bookings_exclusive"),
                    holdsThreadsExclusive = resultSet.getBoolean("holds_threads_exclusive"),
                    waitingForMessagesExclusive = resultSet.getBoolean("waiting_for_messages_exclusive"),
                    blockerHoldsMessagesRowExclusive = resultSet.getBoolean("blocker_holds_messages_row_exclusive"),
                    holdsReadsExclusive = resultSet.getBoolean("holds_reads_exclusive"),
                    diagnostic =
                        "migrationPid=${resultSet.getInt("pid")}; blockerPid=$blockerPid; " +
                            "blockers=${resultSet.getString("blocker_pids")}; activity=${describeActivity(observer)}",
                )
            }
        }

    private fun awaitMigrationFirstWriterWait(
        observer: Connection,
        migrationPid: Int,
        writerPid: Int,
        writer: Deferred<*>,
        deadline: Long,
    ): MigrationFirstWriterWaitObservation {
        var last = readMigrationFirstWriterWait(observer, migrationPid, writerPid)
        while (System.nanoTime() < deadline) {
            last = readMigrationFirstWriterWait(observer, migrationPid, writerPid)
            if (
                last.blockedByMigration &&
                last.waitingForBookingsRowShare &&
                !last.holdsThreadsRowShare &&
                !last.holdsReadsRowExclusive
            ) {
                return last
            }
            if (writer.isCompleted) {
                return last.copy(
                    diagnostic = "Writer completed before its parent-lock wait was observed. ${last.diagnostic}",
                )
            }
            Thread.yield()
        }
        return last
    }

    private fun assertMigrationFirstWriterWait(observation: MigrationFirstWriterWaitObservation) {
        assertTrue(observation.blockedByMigration, observation.diagnostic)
        assertTrue(observation.waitingForBookingsRowShare, observation.diagnostic)
        assertFalse(observation.holdsThreadsRowShare, observation.diagnostic)
        assertFalse(observation.holdsReadsRowExclusive, observation.diagnostic)
    }

    private fun readMigrationFirstWriterWait(
        observer: Connection,
        migrationPid: Int,
        writerPid: Int,
    ): MigrationFirstWriterWaitObservation =
        observer.prepareStatement(
            """
            SELECT
                ? = ANY(pg_blocking_pids(?)) AS blocked_by_migration,
                EXISTS (
                    SELECT 1 FROM pg_locks lock
                    JOIN pg_class relation ON relation.oid = lock.relation
                    JOIN pg_namespace namespace ON namespace.oid = relation.relnamespace
                    WHERE lock.pid = ?
                      AND namespace.nspname = current_schema()
                      AND relation.relname = 'bookings'
                      AND lock.mode = 'RowShareLock'
                      AND NOT lock.granted
                ) AS waiting_for_bookings_row_share,
                EXISTS (
                    SELECT 1 FROM pg_locks lock
                    JOIN pg_class relation ON relation.oid = lock.relation
                    JOIN pg_namespace namespace ON namespace.oid = relation.relnamespace
                    WHERE lock.pid = ?
                      AND namespace.nspname = current_schema()
                      AND relation.relname = 'support_threads'
                      AND lock.mode = 'RowShareLock'
                      AND lock.granted
                ) AS holds_threads_row_share,
                EXISTS (
                    SELECT 1 FROM pg_locks lock
                    JOIN pg_class relation ON relation.oid = lock.relation
                    JOIN pg_namespace namespace ON namespace.oid = relation.relnamespace
                    WHERE lock.pid = ?
                      AND namespace.nspname = current_schema()
                      AND relation.relname = 'support_thread_reads'
                      AND lock.mode = 'RowExclusiveLock'
                      AND lock.granted
                ) AS holds_reads_row_exclusive,
                pg_blocking_pids(?) AS blocker_pids
            """.trimIndent(),
        ).use { statement ->
            statement.setInt(1, migrationPid)
            statement.setInt(2, writerPid)
            statement.setInt(3, writerPid)
            statement.setInt(4, writerPid)
            statement.setInt(5, writerPid)
            statement.setInt(6, writerPid)
            statement.executeQuery().use { resultSet ->
                assertTrue(resultSet.next())
                MigrationFirstWriterWaitObservation(
                    blockedByMigration = resultSet.getBoolean("blocked_by_migration"),
                    waitingForBookingsRowShare = resultSet.getBoolean("waiting_for_bookings_row_share"),
                    holdsThreadsRowShare = resultSet.getBoolean("holds_threads_row_share"),
                    holdsReadsRowExclusive = resultSet.getBoolean("holds_reads_row_exclusive"),
                    diagnostic =
                        "migrationPid=$migrationPid; writerPid=$writerPid; " +
                            "blockers=${resultSet.getString("blocker_pids")}; activity=${describeActivity(observer)}",
                )
            }
        }

    private fun assertSuccessfulSerializedState(
        connection: Connection,
        fixture: BookingThreadIntegrityMigrationTestSupport.MigrationConcurrencyFixture,
    ) {
        assertEquals(
            listOf(fixture.survivorThreadId),
            bookingThreadIds(connection, fixture.bookingId),
        )
        assertEquals(
            listOf(fixture.survivorThreadId),
            readMarkerThreadIds(connection, fixture.readerUserId),
        )
        assertEquals(3, messageCount(connection, fixture.survivorThreadId))
        assertEquals(3, bookingAuditCount(connection, fixture.survivorThreadId))
        assertFalse(threadExists(connection, fixture.duplicateThreadId))
        assertEquals(0, messageCount(connection, fixture.duplicateThreadId))
    }

    private fun bookingThreadIds(
        connection: Connection,
        bookingId: Long,
    ): List<Long> =
        connection.prepareStatement(
            """
            SELECT id
            FROM support_threads
            WHERE thread_type = 'BOOKING_THREAD'
              AND booking_id = ?
            ORDER BY id
            """.trimIndent(),
        ).use { statement ->
            statement.setLong(1, bookingId)
            statement.executeQuery().use { resultSet ->
                buildList {
                    while (resultSet.next()) add(resultSet.getLong("id"))
                }
            }
        }

    private fun readMarkerThreadIds(
        connection: Connection,
        userId: Long,
    ): List<Long> =
        connection.prepareStatement(
            """
            SELECT thread_id
            FROM support_thread_reads
            WHERE user_id = ?
            ORDER BY thread_id
            """.trimIndent(),
        ).use { statement ->
            statement.setLong(1, userId)
            statement.executeQuery().use { resultSet ->
                buildList {
                    while (resultSet.next()) add(resultSet.getLong("thread_id"))
                }
            }
        }

    private fun messageCount(
        connection: Connection,
        threadId: Long,
    ): Int =
        connection.prepareStatement("SELECT COUNT(*) FROM support_messages WHERE thread_id = ?").use { statement ->
            statement.setLong(1, threadId)
            statement.executeQuery().use { resultSet ->
                assertTrue(resultSet.next())
                resultSet.getInt(1)
            }
        }

    private fun bookingAuditCount(
        connection: Connection,
        threadId: Long,
    ): Int =
        connection.prepareStatement(
            """
            SELECT COUNT(*)
            FROM audit_log
            WHERE entity_type = 'support_ticket'
              AND action = 'SUPPORT_TICKET_STATUS_CHANGED'
              AND entity_id = ?
              AND payload_json::JSONB ->> 'ticketId' = ?
            """.trimIndent(),
        ).use { statement ->
            statement.setLong(1, threadId)
            statement.setString(2, threadId.toString())
            statement.executeQuery().use { resultSet ->
                assertTrue(resultSet.next())
                resultSet.getInt(1)
            }
        }

    private fun threadExists(
        connection: Connection,
        threadId: Long,
    ): Boolean =
        connection.prepareStatement("SELECT EXISTS (SELECT 1 FROM support_threads WHERE id = ?)").use { statement ->
            statement.setLong(1, threadId)
            statement.executeQuery().use { resultSet ->
                assertTrue(resultSet.next())
                resultSet.getBoolean(1)
            }
        }

    private fun lockSupportMessages(connection: Connection) {
        connection.createStatement().use { statement ->
            statement.execute("LOCK TABLE support_messages IN ROW EXCLUSIVE MODE")
        }
    }

    private fun captureOnlyPoolBackendPid(
        dataSource: HikariDataSource,
        expectedApplicationName: String,
    ): Int =
        dataSource.connection.use { connection ->
            assertEquals(expectedApplicationName, backendApplicationName(connection))
            backendPid(connection)
        }

    private fun backendPid(connection: Connection): Int =
        connection.createStatement().use { statement ->
            statement.executeQuery("SELECT pg_backend_pid()").use { resultSet ->
                assertTrue(resultSet.next())
                resultSet.getInt(1)
            }
        }

    private fun backendApplicationName(connection: Connection): String =
        connection.createStatement().use { statement ->
            statement.executeQuery("SELECT current_setting('application_name')").use { resultSet ->
                assertTrue(resultSet.next())
                resultSet.getString(1)
            }
        }

    private fun describeActivity(observer: Connection): String =
        observer.createStatement().use { statement ->
            statement.executeQuery(
                """
                SELECT pid, application_name, state, wait_event_type, wait_event, pg_blocking_pids(pid), query
                FROM pg_stat_activity
                WHERE datname = current_database()
                  AND pid <> pg_backend_pid()
                ORDER BY pid
                """.trimIndent(),
            ).use { resultSet ->
                buildList {
                    while (resultSet.next()) {
                        add(
                            "pid=${resultSet.getInt("pid")}, app=${resultSet.getString("application_name")}, " +
                                "state=${resultSet.getString("state")}, " +
                                "wait=${resultSet.getString("wait_event_type")}/" +
                                "${resultSet.getString("wait_event")}, " +
                                "blockers=${resultSet.getString("pg_blocking_pids")}, " +
                                "query=${resultSet.getString("query").replace(Regex("\\s+"), " ").trim()}",
                        )
                    }
                }.joinToString(" | ")
            }
        }

    private fun uniqueIndexExists(connection: Connection): Boolean =
        connection.createStatement().use { statement ->
            statement.executeQuery(
                "SELECT to_regclass(current_schema() || '.uq_support_threads_booking_thread_booking_id') IS NOT NULL",
            ).use { resultSet ->
                assertTrue(resultSet.next())
                resultSet.getBoolean(1)
            }
        }

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
                poolName = applicationName
                addDataSourceProperty("ApplicationName", applicationName)
            },
        )

    private fun writerApplicationName(
        scenario: String,
        database: PostgresTestDatabase,
    ): String = "booking_integrity_${scenario}_${database.schema.takeLast(12)}"

    private fun migrationFirstObservationDeadline(): Long =
        System.nanoTime() + TimeUnit.SECONDS.toNanos(MIGRATION_FIRST_OBSERVATION_TIMEOUT_SECONDS)

    private fun directConnection(database: PostgresTestDatabase): Connection =
        DriverManager.getConnection(database.jdbcUrl, database.user, database.password)

    private fun throwableMessages(throwable: Throwable): List<String> =
        generateSequence(throwable) { it.cause }
            .mapNotNull(Throwable::message)
            .toList()

    private data class WriterFirstLockObservation(
        val migrationPid: Int?,
        val blockedByWriter: Boolean,
        val waitingForBookingsExclusive: Boolean,
        val writerHoldsBookingsRowShare: Boolean,
        val writerHoldsThreadsRowShare: Boolean,
        val writerHoldsReadsRowExclusive: Boolean,
        val diagnostic: String,
    ) {
        companion object {
            fun missing(diagnostic: String) =
                WriterFirstLockObservation(
                    migrationPid = null,
                    blockedByWriter = false,
                    waitingForBookingsExclusive = false,
                    writerHoldsBookingsRowShare = false,
                    writerHoldsThreadsRowShare = false,
                    writerHoldsReadsRowExclusive = false,
                    diagnostic = "No V124 bookings-lock waiter observed. activity=$diagnostic",
                )
        }
    }

    private data class MigrationParentLocksObservation(
        val migrationPid: Int?,
        val blockedBySupportMessagesBlocker: Boolean,
        val holdsBookingsExclusive: Boolean,
        val holdsThreadsExclusive: Boolean,
        val waitingForMessagesExclusive: Boolean,
        val blockerHoldsMessagesRowExclusive: Boolean,
        val holdsReadsExclusive: Boolean,
        val diagnostic: String,
    ) {
        companion object {
            fun missing(diagnostic: String) =
                MigrationParentLocksObservation(
                    migrationPid = null,
                    blockedBySupportMessagesBlocker = false,
                    holdsBookingsExclusive = false,
                    holdsThreadsExclusive = false,
                    waitingForMessagesExclusive = false,
                    blockerHoldsMessagesRowExclusive = false,
                    holdsReadsExclusive = false,
                    diagnostic = "No V124 support_messages-lock waiter observed. activity=$diagnostic",
                )
        }
    }

    private data class MigrationFirstWriterWaitObservation(
        val blockedByMigration: Boolean,
        val waitingForBookingsRowShare: Boolean,
        val holdsThreadsRowShare: Boolean,
        val holdsReadsRowExclusive: Boolean,
        val diagnostic: String,
    )

    private companion object {
        const val POSTGRES_MIGRATIONS = "classpath:db/migration/postgresql"
        const val PREVIOUS_VERSION = "123"
        const val EXPECTED_VERSION = "124"
        const val WAIT_TIMEOUT_SECONDS = 15L
        const val MIGRATION_FIRST_OBSERVATION_TIMEOUT_SECONDS = 8L
        const val WRITER_CHECKPOINT_TIMEOUT_SECONDS = 30L
    }
}
