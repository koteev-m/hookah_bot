package com.hookah.platform.backend.support

import com.hookah.platform.backend.test.PostgresTestDatabase
import com.hookah.platform.backend.test.PostgresTestEnv
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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class BookingConversationConcurrencyPostgresTest {
    @Test
    fun `two production opens wait on the booking lock and create one physical thread`() =
        runBlocking {
            val database = PostgresTestEnv.createDatabase()
            PostgresTestEnv.createDataSource(database).use { dataSource ->
                val fixture = seedFixture(dataSource.connection)
                val repository = SupportThreadRepository(dataSource)
                directConnection(database).use { blocker ->
                    directConnection(database).use { observer ->
                        blocker.autoCommit = false
                        lockBooking(blocker, fixture.bookingId)
                        val blockerPid = backendPid(blocker)
                        val observerPid = backendPid(observer)
                        val ready = CountDownLatch(2)
                        val start = CountDownLatch(1)
                        val requests =
                            listOf("Open A", "Open B").map { title ->
                                async(Dispatchers.IO) {
                                    ready.countDown()
                                    assertTrue(start.await(WAIT_TIMEOUT_SECONDS, TimeUnit.SECONDS))
                                    repository.createOrFindBookingThread(fixture.bookingId, title)
                                }
                            }

                        assertTrue(ready.await(WAIT_TIMEOUT_SECONDS, TimeUnit.SECONDS))
                        start.countDown()
                        val observation = awaitBookingWaiters(observer, blockerPid, requests)
                        assertEquals(
                            2,
                            observation.waiterPids.size,
                            "Both independent production transactions must wait on the canonical booking row. " +
                                observation.diagnostic,
                        )
                        assertFalse(blockerPid in observation.waiterPids)
                        assertFalse(observerPid in observation.waiterPids)

                        blocker.commit()
                        val results =
                            withTimeout(TimeUnit.SECONDS.toMillis(WAIT_TIMEOUT_SECONDS)) {
                                requests.awaitAll()
                            }
                        val firstResult = assertNotNull(results[0])
                        val secondResult = assertNotNull(results[1])
                        assertEquals(firstResult.id, secondResult.id)
                        assertEquals(1, countBookingThreads(observer, fixture.bookingId))

                        val authoritativeThread = loadBookingThread(observer, fixture.bookingId)
                        assertEquals(firstResult.id, authoritativeThread.id)
                        assertEquals(fixture.bookingId, authoritativeThread.bookingId)
                        assertEquals(fixture.venueId, authoritativeThread.venueId)
                        assertEquals(fixture.guestUserId, authoritativeThread.guestUserId)
                        assertEquals("BOOKING_THREAD", authoritativeThread.threadType)
                        assertEquals("IN_PROGRESS", authoritativeThread.status)
                        assertEquals(0, countMessages(observer, authoritativeThread.id))
                        assertEquals(0, countRelevantAudits(observer, authoritativeThread.id))
                    }
                }
            }
        }

    private fun awaitBookingWaiters(
        observer: Connection,
        blockerPid: Int,
        requests: List<kotlinx.coroutines.Deferred<*>>,
    ): LockObservation {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(WAIT_TIMEOUT_SECONDS)
        var last = LockObservation(emptySet(), "No booking row-lock waiters observed")
        while (System.nanoTime() < deadline) {
            val waiterPids = readBookingWaiters(observer, blockerPid)
            last =
                LockObservation(
                    waiterPids = waiterPids,
                    diagnostic =
                        "blockerPid=$blockerPid; waiters=$waiterPids; " +
                            "activity=${describeBookingActivity(observer)}",
                )
            if (waiterPids.size == 2) return last
            if (requests.any { it.isCompleted }) {
                return last.copy(
                    diagnostic = "A booking open completed before both reached the lock. ${last.diagnostic}",
                )
            }
            Thread.yield()
        }
        return last
    }

    private fun readBookingWaiters(
        observer: Connection,
        blockerPid: Int,
    ): Set<Int> =
        observer.prepareStatement(
            """
            WITH RECURSIVE booking_waiters(pid) AS (
                SELECT pid
                FROM pg_stat_activity
                WHERE datname = current_database()
                  AND pid <> pg_backend_pid()
                  AND wait_event_type = 'Lock'
                  AND lower(query) LIKE '%from bookings%'
                  AND lower(query) LIKE '%for update%'
            ),
            lock_chain(root_pid, pid, path) AS (
                SELECT pid, pid, ARRAY[pid]
                FROM booking_waiters
                UNION ALL
                SELECT lock_chain.root_pid, blocker.pid, lock_chain.path || blocker.pid
                FROM lock_chain
                CROSS JOIN LATERAL unnest(pg_blocking_pids(lock_chain.pid)) AS blocker(pid)
                WHERE NOT blocker.pid = ANY(lock_chain.path)
            )
            SELECT DISTINCT root_pid AS pid
            FROM lock_chain
            WHERE pid = ?
            ORDER BY root_pid
            """.trimIndent(),
        ).use { statement ->
            statement.setInt(1, blockerPid)
            statement.executeQuery().use { resultSet ->
                buildSet {
                    while (resultSet.next()) add(resultSet.getInt("pid"))
                }
            }
        }

    private fun describeBookingActivity(observer: Connection): String =
        observer.createStatement().use { statement ->
            statement.executeQuery(
                """
                SELECT pid, state, wait_event_type, wait_event, pg_blocking_pids(pid), query
                FROM pg_stat_activity
                WHERE datname = current_database()
                  AND pid <> pg_backend_pid()
                  AND lower(query) LIKE '%bookings%'
                ORDER BY pid
                """.trimIndent(),
            ).use { resultSet ->
                buildList {
                    while (resultSet.next()) {
                        add(
                            "pid=${resultSet.getInt("pid")}, state=${resultSet.getString("state")}, " +
                                "wait=${resultSet.getString("wait_event_type")}/" +
                                "${resultSet.getString("wait_event")}, " +
                                "blockers=${resultSet.getString("pg_blocking_pids")}, " +
                                "query=${resultSet.getString("query").replace(Regex("\\s+"), " ").trim()}",
                        )
                    }
                }.joinToString(" | ")
            }
        }

    private fun seedFixture(connection: Connection): Fixture =
        connection.use {
            val guestUserId = 82001L
            it.prepareStatement(
                "INSERT INTO users (telegram_user_id, username) VALUES (?, ?)",
            ).use { statement ->
                statement.setLong(1, guestUserId)
                statement.setString(2, "booking_concurrency_guest")
                statement.executeUpdate()
            }
            val venueId =
                it.prepareStatement(
                    "INSERT INTO venues (name, city, address, status) VALUES (?, ?, ?, 'PUBLISHED')",
                    Statement.RETURN_GENERATED_KEYS,
                ).use { statement ->
                    statement.setString(1, "Booking Concurrency Venue")
                    statement.setString(2, "Moscow")
                    statement.setString(3, "Concurrency street, 1")
                    statement.executeUpdate()
                    statement.generatedKeys.use { keys ->
                        keys.next()
                        keys.getLong(1)
                    }
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
                    statement.setTimestamp(3, Timestamp.from(Instant.now().plusSeconds(86_400)))
                    statement.setDate(4, Date.valueOf("2030-01-10"))
                    statement.executeUpdate()
                    statement.generatedKeys.use { keys ->
                        keys.next()
                        keys.getLong(1)
                    }
                }
            Fixture(
                bookingId = bookingId,
                venueId = venueId,
                guestUserId = guestUserId,
            )
        }

    private fun directConnection(database: PostgresTestDatabase): Connection =
        DriverManager.getConnection(database.jdbcUrl, database.user, database.password)

    private fun lockBooking(
        connection: Connection,
        bookingId: Long,
    ) {
        connection.prepareStatement("SELECT id FROM bookings WHERE id = ? FOR UPDATE").use { statement ->
            statement.setLong(1, bookingId)
            statement.executeQuery().use { resultSet -> assertTrue(resultSet.next()) }
        }
    }

    private fun backendPid(connection: Connection): Int =
        connection.createStatement().use { statement ->
            statement.executeQuery("SELECT pg_backend_pid()").use { resultSet ->
                resultSet.next()
                resultSet.getInt(1)
            }
        }

    private fun countBookingThreads(
        connection: Connection,
        bookingId: Long,
    ): Int =
        connection.prepareStatement(
            "SELECT COUNT(*) FROM support_threads WHERE booking_id = ? AND thread_type = 'BOOKING_THREAD'",
        ).use { statement ->
            statement.setLong(1, bookingId)
            statement.executeQuery().use { resultSet ->
                resultSet.next()
                resultSet.getInt(1)
            }
        }

    private fun loadBookingThread(
        connection: Connection,
        bookingId: Long,
    ): BookingThreadRow =
        connection.prepareStatement(
            """
            SELECT id, booking_id, venue_id, guest_user_id, thread_type, status
            FROM support_threads
            WHERE booking_id = ?
              AND thread_type = 'BOOKING_THREAD'
            """.trimIndent(),
        ).use { statement ->
            statement.setLong(1, bookingId)
            statement.executeQuery().use { resultSet ->
                assertTrue(resultSet.next())
                val row =
                    BookingThreadRow(
                        id = resultSet.getLong("id"),
                        bookingId = resultSet.getLong("booking_id"),
                        venueId = resultSet.getLong("venue_id"),
                        guestUserId = resultSet.getLong("guest_user_id"),
                        threadType = resultSet.getString("thread_type"),
                        status = resultSet.getString("status"),
                    )
                assertFalse(resultSet.next())
                row
            }
        }

    private fun countMessages(
        connection: Connection,
        threadId: Long,
    ): Int =
        countByThreadId(
            connection = connection,
            sql = "SELECT COUNT(*) FROM support_messages WHERE thread_id = ?",
            threadId = threadId,
        )

    private fun countRelevantAudits(
        connection: Connection,
        threadId: Long,
    ): Int =
        countByThreadId(
            connection = connection,
            sql = "SELECT COUNT(*) FROM audit_log WHERE entity_type = 'support_ticket' AND entity_id = ?",
            threadId = threadId,
        )

    private fun countByThreadId(
        connection: Connection,
        sql: String,
        threadId: Long,
    ): Int =
        connection.prepareStatement(sql).use { statement ->
            statement.setLong(1, threadId)
            statement.executeQuery().use { resultSet ->
                assertTrue(resultSet.next())
                resultSet.getInt(1)
            }
        }

    private data class Fixture(
        val bookingId: Long,
        val venueId: Long,
        val guestUserId: Long,
    )

    private data class BookingThreadRow(
        val id: Long,
        val bookingId: Long,
        val venueId: Long,
        val guestUserId: Long,
        val threadType: String,
        val status: String,
    )

    private data class LockObservation(
        val waiterPids: Set<Int>,
        val diagnostic: String,
    )

    private companion object {
        const val WAIT_TIMEOUT_SECONDS = 15L
    }
}
