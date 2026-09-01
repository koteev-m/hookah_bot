package com.hookah.platform.backend.miniapp.venue

import com.hookah.platform.backend.api.ApiErrorCodes
import com.hookah.platform.backend.miniapp.session.SessionTokenConfig
import com.hookah.platform.backend.miniapp.session.SessionTokenService
import com.hookah.platform.backend.module
import com.hookah.platform.backend.test.PostgresTestDatabase
import com.hookah.platform.backend.test.PostgresTestEnv
import io.ktor.client.request.get
import io.ktor.client.request.headers
import io.ktor.client.request.patch
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.config.MapApplicationConfig
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Test
import java.sql.Connection
import java.sql.Statement
import java.sql.Timestamp
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class StaffProfileLinkConcurrencyPostgresTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `concurrent create from member has one winner and one typed conflict`() =
        testApplication {
            val database = PostgresTestEnv.createDatabase()
            val config = buildConfig(database)
            environment { this.config = config }
            application { module() }

            client.get("/health")
            val fixture = seedFixture(database)
            val firstToken = issueToken(config, fixture.firstOwnerId)
            val secondToken = issueToken(config, fixture.secondOwnerId)

            val responses =
                runWhileTargetMembershipIsBlocked(
                    database = database,
                    venueId = fixture.venueId,
                    targetUserId = fixture.staffUserId,
                    beforeRelease = {
                        assertEquals(0, loadProfiles(database, fixture.venueId).size)
                        assertEquals(0, loadAudits(database, STAFF_PROFILE_CREATED).size)
                    },
                    firstRequest = {
                        val response =
                            client.post("/api/venue/${fixture.venueId}/staff/profiles/from-member") {
                                headers { append(HttpHeaders.Authorization, "Bearer $firstToken") }
                                contentType(ContentType.Application.Json)
                                setBody(
                                    """{"userId":${fixture.staffUserId},"subtype":"waiter"}""",
                                )
                            }
                        HttpSnapshot(response.status, response.bodyAsText())
                    },
                    secondRequest = {
                        val response =
                            client.post("/api/venue/${fixture.venueId}/staff/profiles/from-member") {
                                headers { append(HttpHeaders.Authorization, "Bearer $secondToken") }
                                contentType(ContentType.Application.Json)
                                setBody(
                                    """{"userId":${fixture.staffUserId},"subtype":"waiter"}""",
                                )
                            }
                        HttpSnapshot(response.status, response.bodyAsText())
                    },
                )

            val success = responses.single { it.status == HttpStatusCode.OK }
            val conflict = responses.single { it.status == HttpStatusCode.Conflict }
            val winnerProfileId = success.profileId()
            assertLinkConflict(conflict, winnerProfileId)

            val profiles = loadProfiles(database, fixture.venueId)
            val created = profiles.single()
            assertEquals(winnerProfileId, created.id)
            assertEquals(fixture.staffUserId, created.linkedUserId)
            assertFalse(created.isGuestVisible)
            assertNull(created.publishedAt)
            assertNull(created.disabledAt)
            assertEquals(1, profiles.count { it.linkedUserId == fixture.staffUserId && it.disabledAt == null })

            val audits = loadAudits(database, STAFF_PROFILE_CREATED)
            val audit = audits.single()
            assertEquals(winnerProfileId, audit.entityId)
            assertTrue(audit.actorUserId in setOf(fixture.firstOwnerId, fixture.secondOwnerId))
        }

    @Test
    fun `concurrent relink has one winner while loser remains display only`() =
        testApplication {
            val database = PostgresTestEnv.createDatabase()
            val config = buildConfig(database)
            environment { this.config = config }
            application { module() }

            client.get("/health")
            val fixture = seedFixture(database)
            val firstProfileId = seedDisplayOnlyProfile(database, fixture, "Первая карточка")
            val secondProfileId = seedDisplayOnlyProfile(database, fixture, "Вторая карточка")
            val firstToken = issueToken(config, fixture.firstOwnerId)
            val secondToken = issueToken(config, fixture.secondOwnerId)

            val responses =
                runWhileTargetMembershipIsBlocked(
                    database = database,
                    venueId = fixture.venueId,
                    targetUserId = fixture.staffUserId,
                    beforeRelease = {
                        val inFlightProfiles = loadProfiles(database, fixture.venueId)
                        assertEquals(2, inFlightProfiles.size)
                        assertTrue(inFlightProfiles.all { it.linkedUserId == null })
                        assertEquals(0, loadAudits(database, STAFF_PROFILE_UPDATED).size)
                    },
                    firstRequest = {
                        val response =
                            client.patch("/api/venue/${fixture.venueId}/staff/profiles/$firstProfileId") {
                                headers { append(HttpHeaders.Authorization, "Bearer $firstToken") }
                                contentType(ContentType.Application.Json)
                                setBody("""{"linkedUserId":${fixture.staffUserId}}""")
                            }
                        HttpSnapshot(response.status, response.bodyAsText())
                    },
                    secondRequest = {
                        val response =
                            client.patch("/api/venue/${fixture.venueId}/staff/profiles/$secondProfileId") {
                                headers { append(HttpHeaders.Authorization, "Bearer $secondToken") }
                                contentType(ContentType.Application.Json)
                                setBody("""{"linkedUserId":${fixture.staffUserId}}""")
                            }
                        HttpSnapshot(response.status, response.bodyAsText())
                    },
                )

            val success = responses.single { it.status == HttpStatusCode.OK }
            val conflict = responses.single { it.status == HttpStatusCode.Conflict }
            val winnerProfileId = success.profileId()
            assertTrue(winnerProfileId in setOf(firstProfileId, secondProfileId))
            assertLinkConflict(conflict, winnerProfileId)

            val profiles = loadProfiles(database, fixture.venueId).associateBy { it.id }
            assertEquals(fixture.staffUserId, profiles.getValue(winnerProfileId).linkedUserId)
            val loserProfileId = (setOf(firstProfileId, secondProfileId) - winnerProfileId).single()
            assertNull(profiles.getValue(loserProfileId).linkedUserId)
            assertEquals(
                1,
                profiles.values.count {
                    it.linkedUserId == fixture.staffUserId && it.disabledAt == null
                },
            )

            val audits = loadAudits(database, STAFF_PROFILE_UPDATED)
            val audit = audits.single()
            assertEquals(winnerProfileId, audit.entityId)
            assertTrue(audit.actorUserId in setOf(fixture.firstOwnerId, fixture.secondOwnerId))
        }

    private suspend fun runWhileTargetMembershipIsBlocked(
        database: PostgresTestDatabase,
        venueId: Long,
        targetUserId: Long,
        beforeRelease: () -> Unit,
        firstRequest: suspend () -> HttpSnapshot,
        secondRequest: suspend () -> HttpSnapshot,
    ): List<HttpSnapshot> =
        coroutineScope {
            database.connection().use { blocker ->
                blocker.autoCommit = false
                lockTargetMembership(blocker, venueId, targetUserId)
                val blockerPid = backendPid(blocker)

                database.connection().use { observer ->
                    val start = CyclicBarrier(3)
                    val first = async(Dispatchers.IO) { start.awaitThen(firstRequest) }
                    val second = async(Dispatchers.IO) { start.awaitThen(secondRequest) }
                    try {
                        awaitBarrier(start)
                        val observation =
                            awaitTargetMembershipWaiters(
                                observer = observer,
                                blockerPid = blockerPid,
                                requests = listOf(first, second),
                            )
                        assertEquals(
                            2,
                            observation.waiterPids.size,
                            "Both production requests must wait on the target membership lock. " +
                                observation.diagnostic,
                        )
                        assertTrue(
                            observation.lockEdges.isNotEmpty(),
                            "Expected a PostgreSQL pg_locks edge for the competing transactions. " +
                                observation.diagnostic,
                        )
                        beforeRelease()
                    } finally {
                        blocker.commit()
                    }
                    listOf(first.await(), second.await())
                }
            }
        }

    private fun awaitTargetMembershipWaiters(
        observer: Connection,
        blockerPid: Int,
        requests: List<Deferred<*>>,
    ): LockObservation {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(WAIT_TIMEOUT_SECONDS)
        var last = LockObservation(emptySet(), emptySet(), "No target membership waiters observed")
        while (System.nanoTime() < deadline) {
            val waiters = readTargetWaitersReachingBlocker(observer, blockerPid)
            val relevantPids = waiters + blockerPid
            val edges = readPgLockEdges(observer, relevantPids)
            val activity = describeActivity(observer, relevantPids)
            last =
                LockObservation(
                    waiterPids = waiters,
                    lockEdges = edges,
                    diagnostic = "blockerPid=$blockerPid; waiters=$waiters; edges=$edges; activity=$activity",
                )
            if (waiters.size == 2 && edges.isNotEmpty()) {
                return last
            }
            if (requests.any { it.isCompleted }) {
                return last.copy(
                    diagnostic = "A production request completed before both reached the lock. ${last.diagnostic}",
                )
            }
            Thread.yield()
        }
        return last
    }

    private fun readTargetWaitersReachingBlocker(
        observer: Connection,
        blockerPid: Int,
    ): Set<Int> =
        observer.prepareStatement(
            """
            WITH RECURSIVE production_request_waiters(pid) AS (
                SELECT pid
                FROM pg_stat_activity
                WHERE datname = current_database()
                  AND wait_event_type = 'Lock'
                  AND lower(query) LIKE '%for update%'
                  AND (
                      lower(query) LIKE '%from venue_members%'
                      OR lower(query) LIKE '%from venues%'
                  )
            ),
            lock_chain(root_pid, pid, path) AS (
                SELECT pid, pid, ARRAY[pid]
                FROM production_request_waiters
                UNION ALL
                SELECT lock_chain.root_pid, blocker.pid, lock_chain.path || blocker.pid
                FROM lock_chain
                CROSS JOIN LATERAL unnest(pg_blocking_pids(lock_chain.pid)) AS blocker(pid)
                WHERE NOT blocker.pid = ANY(lock_chain.path)
            )
            SELECT DISTINCT root_pid
            FROM lock_chain
            WHERE pid = ?
            ORDER BY root_pid
            """.trimIndent(),
        ).use { statement ->
            statement.setInt(1, blockerPid)
            statement.executeQuery().use { rs ->
                buildSet {
                    while (rs.next()) add(rs.getInt("root_pid"))
                }
            }
        }

    private fun readPgLockEdges(
        observer: Connection,
        relevantPids: Set<Int>,
    ): Set<PgLockEdge> {
        if (relevantPids.size < 2) return emptySet()
        val pids = relevantPids.sorted()
        val placeholders = pids.joinToString(",") { "?" }
        return observer.prepareStatement(
            """
            SELECT DISTINCT blocked.pid AS blocked_pid, blocking.pid AS blocking_pid
            FROM pg_locks blocked
            JOIN pg_locks blocking
              ON blocking.locktype = blocked.locktype
             AND blocking.database IS NOT DISTINCT FROM blocked.database
             AND blocking.relation IS NOT DISTINCT FROM blocked.relation
             AND blocking.page IS NOT DISTINCT FROM blocked.page
             AND blocking.tuple IS NOT DISTINCT FROM blocked.tuple
             AND blocking.virtualxid IS NOT DISTINCT FROM blocked.virtualxid
             AND blocking.transactionid IS NOT DISTINCT FROM blocked.transactionid
             AND blocking.classid IS NOT DISTINCT FROM blocked.classid
             AND blocking.objid IS NOT DISTINCT FROM blocked.objid
             AND blocking.objsubid IS NOT DISTINCT FROM blocked.objsubid
            WHERE blocked.pid IN ($placeholders)
              AND NOT blocked.granted
              AND blocking.pid IN ($placeholders)
              AND blocking.granted
            """.trimIndent(),
        ).use { statement ->
            var index = 1
            pids.forEach { statement.setInt(index++, it) }
            pids.forEach { statement.setInt(index++, it) }
            statement.executeQuery().use { rs ->
                buildSet {
                    while (rs.next()) {
                        add(PgLockEdge(rs.getInt("blocked_pid"), rs.getInt("blocking_pid")))
                    }
                }
            }
        }
    }

    private fun describeActivity(
        observer: Connection,
        relevantPids: Set<Int>,
    ): String {
        if (relevantPids.isEmpty()) return "none"
        val pids = relevantPids.sorted()
        val placeholders = pids.joinToString(",") { "?" }
        return observer.prepareStatement(
            """
            SELECT pid, state, wait_event_type, wait_event, pg_blocking_pids(pid), query
            FROM pg_stat_activity
            WHERE pid IN ($placeholders)
            ORDER BY pid
            """.trimIndent(),
        ).use { statement ->
            pids.forEachIndexed { index, pid -> statement.setInt(index + 1, pid) }
            statement.executeQuery().use { rs ->
                buildList {
                    while (rs.next()) {
                        add(
                            "pid=${rs.getInt("pid")}, state=${rs.getString("state")}, " +
                                "wait=${rs.getString("wait_event_type")}/${rs.getString("wait_event")}, " +
                                "blockers=${rs.getString("pg_blocking_pids")}, " +
                                "query=${rs.getString("query").normalizedSql()}",
                        )
                    }
                }.joinToString(" | ")
            }
        }
    }

    private suspend fun CyclicBarrier.awaitThen(request: suspend () -> HttpSnapshot): HttpSnapshot {
        awaitBarrier(this)
        return request()
    }

    private suspend fun awaitBarrier(barrier: CyclicBarrier) {
        withContext(Dispatchers.IO) {
            barrier.await(WAIT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        }
    }

    private fun lockTargetMembership(
        connection: Connection,
        venueId: Long,
        targetUserId: Long,
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
            statement.setLong(2, targetUserId)
            statement.executeQuery().use { rs ->
                assertTrue(rs.next(), "Target membership fixture must exist")
            }
        }
    }

    private fun backendPid(connection: Connection): Int =
        connection.createStatement().use { statement ->
            statement.executeQuery("SELECT pg_backend_pid()").use { rs ->
                assertTrue(rs.next())
                rs.getInt(1)
            }
        }

    private fun seedFixture(database: PostgresTestDatabase): Fixture =
        database.connection().use { connection ->
            connection.autoCommit = false
            try {
                val firstOwnerId = 9_101L
                val secondOwnerId = 9_102L
                val staffUserId = 9_103L
                insertUser(connection, firstOwnerId, "owner_one", "Первый", "Владелец")
                insertUser(connection, secondOwnerId, "owner_two", "Второй", "Владелец")
                insertUser(connection, staffUserId, "staff_member", "Свежий", "Сотрудник")
                val venueId = insertVenue(connection)
                insertMembership(connection, venueId, firstOwnerId, VenueRole.OWNER.name)
                insertMembership(connection, venueId, secondOwnerId, VenueRole.OWNER.name)
                insertMembership(connection, venueId, staffUserId, VenueRole.STAFF.name)
                connection.commit()
                Fixture(venueId, firstOwnerId, secondOwnerId, staffUserId)
            } catch (e: Exception) {
                connection.rollback()
                throw e
            }
        }

    private fun insertUser(
        connection: Connection,
        userId: Long,
        username: String,
        firstName: String,
        lastName: String,
    ) {
        connection.prepareStatement(
            """
            INSERT INTO users (telegram_user_id, username, first_name, last_name)
            VALUES (?, ?, ?, ?)
            """.trimIndent(),
        ).use { statement ->
            statement.setLong(1, userId)
            statement.setString(2, username)
            statement.setString(3, firstName)
            statement.setString(4, lastName)
            statement.executeUpdate()
        }
    }

    private fun insertVenue(connection: Connection): Long =
        connection.prepareStatement(
            """
            INSERT INTO venues (name, city, address, status)
            VALUES ('Concurrency venue', 'Moscow', 'Test address', ?)
            """.trimIndent(),
            Statement.RETURN_GENERATED_KEYS,
        ).use { statement ->
            statement.setString(1, VenueStatus.PUBLISHED.dbValue)
            statement.executeUpdate()
            statement.generatedKeys.use { keys ->
                assertTrue(keys.next())
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
            """
            INSERT INTO venue_members (venue_id, user_id, role)
            VALUES (?, ?, ?)
            """.trimIndent(),
        ).use { statement ->
            statement.setLong(1, venueId)
            statement.setLong(2, userId)
            statement.setString(3, role)
            statement.executeUpdate()
        }
    }

    private fun seedDisplayOnlyProfile(
        database: PostgresTestDatabase,
        fixture: Fixture,
        displayName: String,
    ): Long =
        database.connection().use { connection ->
            connection.prepareStatement(
                """
                INSERT INTO staff_profiles (
                    venue_id,
                    linked_user_id,
                    display_name,
                    role_label,
                    subtype,
                    photo_ref,
                    bio,
                    tags,
                    is_guest_visible,
                    created_by_user_id,
                    updated_by_user_id,
                    published_at,
                    disabled_at
                )
                VALUES (?, NULL, ?, NULL, 'waiter', NULL, NULL, NULL, FALSE, ?, ?, NULL, NULL)
                """.trimIndent(),
                Statement.RETURN_GENERATED_KEYS,
            ).use { statement ->
                statement.setLong(1, fixture.venueId)
                statement.setString(2, displayName)
                statement.setLong(3, fixture.firstOwnerId)
                statement.setLong(4, fixture.firstOwnerId)
                statement.executeUpdate()
                statement.generatedKeys.use { keys ->
                    assertTrue(keys.next())
                    keys.getLong(1)
                }
            }
        }

    private fun loadProfiles(
        database: PostgresTestDatabase,
        venueId: Long,
    ): List<StoredProfile> =
        database.connection().use { connection ->
            connection.prepareStatement(
                """
                SELECT id, linked_user_id, is_guest_visible, published_at, disabled_at
                FROM staff_profiles
                WHERE venue_id = ?
                ORDER BY id
                """.trimIndent(),
            ).use { statement ->
                statement.setLong(1, venueId)
                statement.executeQuery().use { rs ->
                    buildList {
                        while (rs.next()) {
                            add(
                                StoredProfile(
                                    id = rs.getLong("id"),
                                    linkedUserId = rs.getLong("linked_user_id").takeIf { !rs.wasNull() },
                                    isGuestVisible = rs.getBoolean("is_guest_visible"),
                                    publishedAt = rs.getTimestamp("published_at"),
                                    disabledAt = rs.getTimestamp("disabled_at"),
                                ),
                            )
                        }
                    }
                }
            }
        }

    private fun loadAudits(
        database: PostgresTestDatabase,
        action: String,
    ): List<StoredAudit> =
        database.connection().use { connection ->
            connection.prepareStatement(
                """
                SELECT actor_user_id, entity_id
                FROM audit_log
                WHERE entity_type = 'staff_profile' AND action = ?
                ORDER BY id
                """.trimIndent(),
            ).use { statement ->
                statement.setString(1, action)
                statement.executeQuery().use { rs ->
                    buildList {
                        while (rs.next()) {
                            add(
                                StoredAudit(
                                    actorUserId = rs.getLong("actor_user_id"),
                                    entityId = rs.getLong("entity_id").takeIf { !rs.wasNull() },
                                ),
                            )
                        }
                    }
                }
            }
        }

    private fun assertLinkConflict(
        snapshot: HttpSnapshot,
        expectedProfileId: Long,
    ) {
        assertEquals(HttpStatusCode.Conflict, snapshot.status)
        val error = json.parseToJsonElement(snapshot.body).jsonObject.getValue("error").jsonObject
        assertEquals(ApiErrorCodes.STAFF_PROFILE_LINK_CONFLICT, error.getValue("code").jsonPrimitive.content)
        val details = error.getValue("details").jsonObject
        assertEquals("LINKED", details.getValue("profileLinkState").jsonPrimitive.content)
        assertEquals(expectedProfileId, details.getValue("linkedStaffProfileId").jsonPrimitive.content.toLong())
    }

    private fun HttpSnapshot.profileId(): Long {
        assertEquals(HttpStatusCode.OK, status)
        return json.parseToJsonElement(body).jsonObject.getValue("id").jsonPrimitive.content.toLong()
    }

    private fun buildConfig(database: PostgresTestDatabase): MapApplicationConfig =
        MapApplicationConfig(
            "app.env" to APP_ENV,
            "api.session.jwtSecret" to "staff-profile-concurrency-secret",
            "db.jdbcUrl" to database.jdbcUrl,
            "db.user" to database.user,
            "db.password" to database.password,
            "db.maxPoolSize" to "4",
            "telegram.enabled" to "false",
            "venue.staffInviteSecretPepper" to "staff-profile-concurrency-pepper",
        )

    private fun issueToken(
        config: MapApplicationConfig,
        userId: Long,
    ): String = SessionTokenService(SessionTokenConfig.from(config, APP_ENV)).issueToken(userId).token

    private fun PostgresTestDatabase.connection(): Connection =
        java.sql.DriverManager.getConnection(jdbcUrl, user, password)

    private fun String.normalizedSql(): String =
        trim()
            .replace(WHITESPACE, " ")
            .lowercase()

    private data class Fixture(
        val venueId: Long,
        val firstOwnerId: Long,
        val secondOwnerId: Long,
        val staffUserId: Long,
    )

    private data class HttpSnapshot(
        val status: HttpStatusCode,
        val body: String,
    )

    private data class StoredProfile(
        val id: Long,
        val linkedUserId: Long?,
        val isGuestVisible: Boolean,
        val publishedAt: Timestamp?,
        val disabledAt: Timestamp?,
    )

    private data class StoredAudit(
        val actorUserId: Long,
        val entityId: Long?,
    )

    private data class PgLockEdge(
        val blockedPid: Int,
        val blockingPid: Int,
    )

    private data class LockObservation(
        val waiterPids: Set<Int>,
        val lockEdges: Set<PgLockEdge>,
        val diagnostic: String,
    )

    private companion object {
        const val APP_ENV = "test"
        const val WAIT_TIMEOUT_SECONDS = 20L
        const val STAFF_PROFILE_CREATED = "STAFF_PROFILE_CREATED"
        const val STAFF_PROFILE_UPDATED = "STAFF_PROFILE_UPDATED"
        val WHITESPACE = Regex("\\s+")
    }
}
