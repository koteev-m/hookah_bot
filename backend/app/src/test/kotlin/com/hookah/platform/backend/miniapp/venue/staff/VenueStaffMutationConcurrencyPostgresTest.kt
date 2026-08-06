package com.hookah.platform.backend.miniapp.venue.staff

import com.hookah.platform.backend.miniapp.venue.AuditLogRepository
import com.hookah.platform.backend.miniapp.venue.VenueRole
import com.hookah.platform.backend.test.PostgresTestDatabase
import com.hookah.platform.backend.test.PostgresTestEnv
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.sql.Connection
import java.sql.Statement
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.TimeUnit
import javax.sql.DataSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class VenueStaffMutationConcurrencyPostgresTest {
    @Test
    fun `concurrent owner demotions keep one owner and one winner audit`() =
        runBlocking {
            val database = PostgresTestEnv.createDatabase()
            PostgresTestEnv.createDataSource(database).use { dataSource ->
                val venueId = seedTwoOwners(dataSource)
                val repository = VenueStaffRepository(dataSource, AuditLogRepository(dataSource))

                val attempts =
                    runWhileOwnerMembershipIsBlocked(
                        database = database,
                        venueId = venueId,
                        beforeRelease = {
                            assertEquals(mapOf("OWNER" to 2), membershipRoleCounts(dataSource, venueId))
                            assertEquals(0, auditCount(dataSource, VENUE_STAFF_ROLE_CHANGED_ACTION))
                        },
                        first = {
                            MutationAttempt(
                                actorUserId = FIRST_OWNER_ID,
                                targetUserId = SECOND_OWNER_ID,
                                result =
                                    repository.updateRoleWithOwnerGuard(
                                        venueId = venueId,
                                        actorUserId = FIRST_OWNER_ID,
                                        targetUserId = SECOND_OWNER_ID,
                                        newRole = VenueRole.STAFF,
                                        source = VenueStaffMutationSource.VENUE_MINI_APP,
                                    ),
                            )
                        },
                        second = {
                            MutationAttempt(
                                actorUserId = SECOND_OWNER_ID,
                                targetUserId = FIRST_OWNER_ID,
                                result =
                                    repository.updateRoleWithOwnerGuard(
                                        venueId = venueId,
                                        actorUserId = SECOND_OWNER_ID,
                                        targetUserId = FIRST_OWNER_ID,
                                        newRole = VenueRole.STAFF,
                                        source = VenueStaffMutationSource.VENUE_MINI_APP,
                                    ),
                            )
                        },
                    )

                val winner = attempts.single { it.result is VenueStaffUpdateResult.Success }
                assertEquals(
                    VenueStaffMutationOutcome.APPLIED,
                    (winner.result as VenueStaffUpdateResult.Success).outcome,
                )
                assertEquals(1, attempts.count { it.result == VenueStaffUpdateResult.Forbidden })
                assertEquals(mapOf("OWNER" to 1, "STAFF" to 1), membershipRoleCounts(dataSource, venueId))
                assertSingleWinnerAudit(
                    dataSource = dataSource,
                    venueId = venueId,
                    action = VENUE_STAFF_ROLE_CHANGED_ACTION,
                    expectedActorUserId = winner.actorUserId,
                    expectedTargetUserId = winner.targetUserId,
                    expectedPayload =
                        mapOf(
                            "oldRole" to "OWNER",
                            "newRole" to "STAFF",
                            "source" to "VENUE_MINI_APP",
                        ),
                )
            }
        }

    @Test
    fun `concurrent owner removals keep one owner and one winner audit`() =
        runBlocking {
            val database = PostgresTestEnv.createDatabase()
            PostgresTestEnv.createDataSource(database).use { dataSource ->
                val venueId = seedTwoOwners(dataSource)
                val repository = VenueStaffRepository(dataSource, AuditLogRepository(dataSource))

                val attempts =
                    runWhileOwnerMembershipIsBlocked(
                        database = database,
                        venueId = venueId,
                        beforeRelease = {
                            assertEquals(mapOf("OWNER" to 2), membershipRoleCounts(dataSource, venueId))
                            assertEquals(0, auditCount(dataSource, VENUE_STAFF_MEMBER_REMOVED_ACTION))
                        },
                        first = {
                            MutationAttempt(
                                actorUserId = FIRST_OWNER_ID,
                                targetUserId = SECOND_OWNER_ID,
                                result =
                                    repository.removeMemberWithOwnerGuard(
                                        venueId = venueId,
                                        actorUserId = FIRST_OWNER_ID,
                                        targetUserId = SECOND_OWNER_ID,
                                        source = VenueStaffMutationSource.TELEGRAM_BOT,
                                    ),
                            )
                        },
                        second = {
                            MutationAttempt(
                                actorUserId = SECOND_OWNER_ID,
                                targetUserId = FIRST_OWNER_ID,
                                result =
                                    repository.removeMemberWithOwnerGuard(
                                        venueId = venueId,
                                        actorUserId = SECOND_OWNER_ID,
                                        targetUserId = FIRST_OWNER_ID,
                                        source = VenueStaffMutationSource.TELEGRAM_BOT,
                                    ),
                            )
                        },
                    )

                val winner = attempts.single { it.result == VenueStaffRemoveResult.Success }
                assertEquals(1, attempts.count { it.result == VenueStaffRemoveResult.Forbidden })
                assertEquals(mapOf("OWNER" to 1), membershipRoleCounts(dataSource, venueId))
                assertSingleWinnerAudit(
                    dataSource = dataSource,
                    venueId = venueId,
                    action = VENUE_STAFF_MEMBER_REMOVED_ACTION,
                    expectedActorUserId = winner.actorUserId,
                    expectedTargetUserId = winner.targetUserId,
                    expectedPayload =
                        mapOf(
                            "oldRole" to "OWNER",
                            "source" to "TELEGRAM_BOT",
                        ),
                )
            }
        }

    private suspend fun <T> runWhileOwnerMembershipIsBlocked(
        database: PostgresTestDatabase,
        venueId: Long,
        beforeRelease: () -> Unit,
        first: suspend () -> T,
        second: suspend () -> T,
    ): List<T> =
        coroutineScope {
            database.connection().use { blocker ->
                blocker.autoCommit = false
                lockOwnerMembership(blocker, venueId, FIRST_OWNER_ID)
                val blockerPid = backendPid(blocker)

                database.connection().use { observer ->
                    val start = CyclicBarrier(3)
                    val firstResult = async(Dispatchers.IO) { start.awaitThen(first) }
                    val secondResult = async(Dispatchers.IO) { start.awaitThen(second) }
                    try {
                        awaitBarrier(start)
                        val observation =
                            awaitOwnerMutationWaiters(
                                observer = observer,
                                blockerPid = blockerPid,
                                requests = listOf(firstResult, secondResult),
                            )
                        assertEquals(
                            2,
                            observation.waiterPids.size,
                            "Both production mutations must wait on the deterministic owner lock. " +
                                observation.diagnostic,
                        )
                        beforeRelease()
                    } finally {
                        blocker.commit()
                    }
                    listOf(firstResult.await(), secondResult.await())
                }
            }
        }

    private fun awaitOwnerMutationWaiters(
        observer: Connection,
        blockerPid: Int,
        requests: List<Deferred<*>>,
    ): LockObservation {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(WAIT_TIMEOUT_SECONDS)
        var last = LockObservation(emptySet(), "No owner-mutation waiters observed")
        while (System.nanoTime() < deadline) {
            val waiters = readOwnerMutationWaiters(observer, blockerPid)
            last =
                LockObservation(
                    waiterPids = waiters,
                    diagnostic =
                        "blockerPid=$blockerPid; waiters=$waiters; " +
                            "activity=${describeOwnerMutationActivity(observer)}",
                )
            if (waiters.size == 2) return last
            if (requests.any { it.isCompleted }) {
                return last.copy(
                    diagnostic = "A mutation completed before both reached the lock. ${last.diagnostic}",
                )
            }
            Thread.yield()
        }
        return last
    }

    private fun readOwnerMutationWaiters(
        observer: Connection,
        blockerPid: Int,
    ): Set<Int> =
        observer.prepareStatement(
            """
            WITH RECURSIVE owner_waiters(pid) AS (
                SELECT pid
                FROM pg_stat_activity
                WHERE datname = current_database()
                  AND pid <> pg_backend_pid()
                  AND wait_event_type = 'Lock'
                  AND lower(query) LIKE '%from venue_members%'
                  AND lower(query) LIKE '%for update%'
            ),
            lock_chain(root_pid, pid, path) AS (
                SELECT pid, pid, ARRAY[pid]
                FROM owner_waiters
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

    private fun describeOwnerMutationActivity(observer: Connection): String =
        observer.createStatement().use { statement ->
            statement.executeQuery(
                """
                SELECT pid, state, wait_event_type, wait_event, pg_blocking_pids(pid), query
                FROM pg_stat_activity
                WHERE datname = current_database()
                  AND pid <> pg_backend_pid()
                  AND lower(query) LIKE '%venue_members%'
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
                                "query=${resultSet.getString("query").normalizedSql()}",
                        )
                    }
                }.joinToString(" | ")
            }
        }

    private suspend fun <T> CyclicBarrier.awaitThen(operation: suspend () -> T): T {
        awaitBarrier(this)
        return operation()
    }

    private suspend fun awaitBarrier(barrier: CyclicBarrier) {
        withContext(Dispatchers.IO) {
            barrier.await(WAIT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        }
    }

    private fun lockOwnerMembership(
        connection: Connection,
        venueId: Long,
        userId: Long,
    ) {
        connection.prepareStatement(
            """
            SELECT role
            FROM venue_members
            WHERE venue_id = ? AND user_id = ?
            FOR UPDATE
            """.trimIndent(),
        ).use { statement ->
            statement.setLong(1, venueId)
            statement.setLong(2, userId)
            statement.executeQuery().use { resultSet ->
                assertTrue(resultSet.next(), "Owner membership fixture must exist")
            }
        }
    }

    private fun backendPid(connection: Connection): Int =
        connection.createStatement().use { statement ->
            statement.executeQuery("SELECT pg_backend_pid()").use { resultSet ->
                assertTrue(resultSet.next())
                resultSet.getInt(1)
            }
        }

    private fun seedTwoOwners(dataSource: DataSource): Long =
        dataSource.connection.use { connection ->
            insertUser(connection, FIRST_OWNER_ID)
            insertUser(connection, SECOND_OWNER_ID)
            val venueId =
                connection.prepareStatement(
                    "INSERT INTO venues (name, status) VALUES ('Concurrent staff audit venue', 'PUBLISHED')",
                    Statement.RETURN_GENERATED_KEYS,
                ).use { statement ->
                    statement.executeUpdate()
                    statement.generatedKeys.use { keys ->
                        assertTrue(keys.next())
                        keys.getLong(1)
                    }
                }
            connection.prepareStatement(
                "INSERT INTO venue_members (venue_id, user_id, role) VALUES (?, ?, 'OWNER')",
            ).use { statement ->
                listOf(FIRST_OWNER_ID, SECOND_OWNER_ID).forEach { userId ->
                    statement.setLong(1, venueId)
                    statement.setLong(2, userId)
                    assertEquals(1, statement.executeUpdate())
                }
            }
            venueId
        }

    private fun insertUser(
        connection: Connection,
        userId: Long,
    ) {
        connection.prepareStatement(
            "INSERT INTO users (telegram_user_id, first_name) VALUES (?, 'Owner')",
        ).use { statement ->
            statement.setLong(1, userId)
            assertEquals(1, statement.executeUpdate())
        }
    }

    private fun membershipRoleCounts(
        dataSource: DataSource,
        venueId: Long,
    ): Map<String, Int> =
        dataSource.connection.use { connection ->
            connection.prepareStatement(
                "SELECT role, COUNT(*) AS role_count FROM venue_members WHERE venue_id = ? GROUP BY role",
            ).use { statement ->
                statement.setLong(1, venueId)
                statement.executeQuery().use { resultSet ->
                    buildMap {
                        while (resultSet.next()) {
                            put(resultSet.getString("role"), resultSet.getInt("role_count"))
                        }
                    }
                }
            }
        }

    private fun auditCount(
        dataSource: DataSource,
        action: String,
    ): Int =
        dataSource.connection.use { connection ->
            connection.prepareStatement("SELECT COUNT(*) FROM audit_log WHERE action = ?").use { statement ->
                statement.setString(1, action)
                statement.executeQuery().use { resultSet ->
                    assertTrue(resultSet.next())
                    resultSet.getInt(1)
                }
            }
        }

    private fun assertSingleWinnerAudit(
        dataSource: DataSource,
        venueId: Long,
        action: String,
        expectedActorUserId: Long,
        expectedTargetUserId: Long,
        expectedPayload: Map<String, String>,
    ) {
        dataSource.connection.use { connection ->
            connection.prepareStatement(
                """
                SELECT actor_user_id, target_user_id, entity_type, entity_id, payload_json
                FROM audit_log
                WHERE action = ?
                """.trimIndent(),
            ).use { statement ->
                statement.setString(1, action)
                statement.executeQuery().use { resultSet ->
                    assertTrue(resultSet.next())
                    assertEquals(expectedActorUserId, resultSet.getLong("actor_user_id"))
                    assertEquals(expectedTargetUserId, resultSet.getLong("target_user_id"))
                    assertFalse(expectedActorUserId == expectedTargetUserId)
                    assertEquals("venue", resultSet.getString("entity_type"))
                    assertEquals(venueId, resultSet.getLong("entity_id"))
                    val payload = Json.parseToJsonElement(resultSet.getString("payload_json")).jsonObject
                    assertEquals(expectedPayload.keys, payload.keys)
                    expectedPayload.forEach { (key, value) ->
                        assertEquals(value, payload.getValue(key).jsonPrimitive.content)
                    }
                    assertFalse(resultSet.next())
                }
            }
        }
    }

    private fun PostgresTestDatabase.connection(): Connection =
        java.sql.DriverManager.getConnection(jdbcUrl, user, password)

    private fun String.normalizedSql(): String =
        trim()
            .replace(WHITESPACE, " ")
            .lowercase()

    private data class MutationAttempt<T>(
        val actorUserId: Long,
        val targetUserId: Long,
        val result: T,
    )

    private data class LockObservation(
        val waiterPids: Set<Int>,
        val diagnostic: String,
    )

    private companion object {
        const val FIRST_OWNER_ID = 9_200_001L
        const val SECOND_OWNER_ID = 9_200_002L
        const val WAIT_TIMEOUT_SECONDS = 10L
        val WHITESPACE = Regex("\\s+")
    }
}
