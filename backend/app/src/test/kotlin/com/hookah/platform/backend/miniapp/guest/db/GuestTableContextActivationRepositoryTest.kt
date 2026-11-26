package com.hookah.platform.backend.miniapp.guest.db

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import java.sql.SQLException
import java.time.Duration
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GuestTableContextActivationRepositoryTest {
    @Test
    fun `only a current venue member may resume a tabless ordinary Bot visit in DRAFT`() =
        runBlocking {
            for (role in listOf("OWNER", "STAFF")) {
                val db = GuestTableContextLifecycleTestDatabase.create("tabless-rejoin-" + role.lowercase())
                val now = Instant.now()
                val session = db.createActiveSession(now.minusSeconds(60), now, now.plusSeconds(3600))
                db.insertContext(tableSessionId = session, updatedAt = now)
                val lifecycle = db.lifecycleRepository()
                val exited =
                    assertIs<GuestTableTeardownResult.Cleared>(
                        lifecycle.teardownByChat(db.actorUserId, db.chatId, now, ordinaryBotVisit = true),
                    )
                assertTrue(exited.exitRecorded)
                val sessions = TableSessionRepository(db.dataSource)
                val event = checkNotNull(sessions.currentQrExit(db.actorUserId, db.venueId, db.tableId))
                db.update("UPDATE venues SET status='DRAFT' WHERE id=?") { it.setLong(1, db.venueId) }

                suspend fun confirm(ordinary: Boolean) =
                    lifecycle.activate(
                        db.actorUserId, db.chatId, db.tableToken, db.venueId, db.tableId,
                        Duration.ofHours(4), now, event, ordinaryBot = ordinary,
                    )
                assertIs<GuestTableActivationResult.Denied>(confirm(true))
                db.update("INSERT INTO venue_members(venue_id,user_id,role) VALUES (?,?,?)") {
                    it.setLong(1, db.venueId)
                    it.setLong(2, db.actorUserId)
                    it.setString(3, role)
                }
                assertIs<GuestTableActivationResult.Denied>(confirm(false))
                val joined = assertIs<GuestTableActivationResult.Applied>(confirm(true))
                assertEquals(session, joined.tableSession.id)
                assertNull(db.exitSnapshot(session))
                assertEquals(0, db.countRows("tab"))
                assertEquals(0, db.countRows("tab_member"))
                assertIs<GuestTableActivationResult.Denied>(confirm(true))
            }
        }

    @Test
    fun `activation atomically touches session clears exit and dialog then saves exact context`() =
        runBlocking {
            val database = GuestTableContextLifecycleTestDatabase.create("guest-activation-success")
            val now = Instant.parse("2026-08-05T10:00:00Z")
            val tableSessionId =
                database.createActiveSession(
                    startedAt = now.minus(Duration.ofHours(2)),
                    lastActivityAt = now.minus(Duration.ofHours(1)),
                    expiresAt = now.plus(Duration.ofMinutes(30)),
                )
            database.insertExit(tableSessionId, now.minus(Duration.ofMinutes(20)))
            database.insertDialog()
            database.insertContext(tableToken = "PREVIOUS_CONTEXT", updatedAt = now.minus(Duration.ofDays(1)))

            val result =
                database.lifecycleRepository().activate(
                    actorUserId = database.actorUserId,
                    chatId = database.chatId,
                    tableToken = database.tableToken,
                    expectedVenueId = database.venueId,
                    expectedTableId = database.tableId,
                    expectedExitEventId =
                        TableSessionRepository(
                            database.dataSource,
                        ).currentQrExit(database.actorUserId, database.venueId, database.tableId, now),
                    ttl = Duration.ofHours(4),
                    now = now,
                )

            val applied = assertIs<GuestTableActivationResult.Applied>(result)
            assertEquals(tableSessionId, applied.tableSession.id)
            assertEquals(database.tableToken, applied.context.tableToken)
            assertEquals(now, database.sessionSnapshot(tableSessionId)?.lastActivityAt)
            assertEquals(now.plus(Duration.ofHours(4)), database.sessionSnapshot(tableSessionId)?.expiresAt)
            assertNull(database.exitSnapshot(tableSessionId))
            assertNull(database.dialogSnapshot())
            assertEquals(
                TestContextSnapshot(
                    chatId = database.chatId,
                    userId = database.actorUserId,
                    venueId = database.venueId,
                    tableId = database.tableId,
                    tableToken = database.tableToken,
                    updatedAt = now,
                ),
                database.contextSnapshot(),
            )
        }

    @Test
    fun `platform prompt and bot choice cannot clear a more recent exit`() =
        runBlocking {
            val database = GuestTableContextLifecycleTestDatabase.create("stale-qr-confirmation")
            val now = Instant.now()
            val session = database.createActiveSession(now.minusSeconds(60), now, now.plusSeconds(3600))
            val sessions = TableSessionRepository(database.dataSource)
            database.insertExit(session, now)
            val previous =
                checkNotNull(sessions.currentQrExit(database.actorUserId, database.venueId, database.tableId))
            database.dataSource.connection.use {
                it.autoCommit = false
                sessions.findSessionForTable(it, session, database.venueId, database.tableId, forUpdate = true)
                assertTrue(sessions.consumeExitEvent(it, database.actorUserId, session, previous))
                sessions.recordUserExit(it, database.actorUserId, session, now)
                it.commit()
            }
            var current = checkNotNull(sessions.currentQrExit(database.actorUserId, database.venueId, database.tableId))
            assertTrue(current != previous)
            val before = database.activationAuthoritativeSnapshot()
            for (expected in listOf(null, previous)) {
                val activation =
                    database.lifecycleRepository().activate(
                        database.actorUserId,
                        database.chatId,
                        database.tableToken,
                        database.venueId,
                        database.tableId,
                        Duration.ofHours(4),
                        now,
                        expected,
                    )
                assertIs<GuestTableActivationResult.Denied>(activation)
                assertIs<GuestTableActivationResult.Denied>(
                    database.lifecycleRepository().activate(
                        database.actorUserId, database.chatId, database.tableToken, database.venueId,
                        database.tableId, Duration.ofHours(4), now, expected, ordinaryBot = true,
                    ),
                )
                assertEquals(before, database.activationAuthoritativeSnapshot())
                assertEquals(current, sessions.currentQrExit(database.actorUserId, database.venueId, database.tableId))
            }

            suspend fun confirmBot() =
                database.lifecycleRepository().activate(
                    database.actorUserId,
                    database.chatId,
                    database.tableToken,
                    database.venueId,
                    database.tableId,
                    Duration.ofHours(4),
                    now,
                    current,
                    ordinaryBot = true,
                )
            assertIs<GuestTableActivationResult.Denied>(confirmBot())
            assertEquals(current, sessions.currentQrExit(database.actorUserId, database.venueId, database.tableId))
            database.createPersonalTab(session)
            assertEquals(session, assertIs<GuestTableActivationResult.Applied>(confirmBot()).tableSession.id)
            assertNull(sessions.currentQrExit(database.actorUserId, database.venueId, database.tableId))
            database.insertExit(session, now)
            current = checkNotNull(sessions.currentQrExit(database.actorUserId, database.venueId, database.tableId))
            database.dataSource.connection.use { connection ->
                connection.createStatement().use { sql ->
                    sql.executeUpdate("UPDATE venues SET status='DRAFT' WHERE id=" + database.venueId)
                    assertIs<GuestTableActivationResult.Denied>(confirmBot())
                    sql.executeUpdate(
                        "INSERT INTO venue_members(venue_id,user_id,role) VALUES (" +
                            "${database.venueId},${database.actorUserId},'STAFF')",
                    )
                }
            }
            val platformConfirmation =
                database.lifecycleRepository().activate(
                    database.actorUserId,
                    database.chatId,
                    database.tableToken,
                    database.venueId,
                    database.tableId,
                    Duration.ofHours(4),
                    now,
                    current,
                )
            assertIs<GuestTableActivationResult.Denied>(platformConfirmation)
            assertEquals(session, assertIs<GuestTableActivationResult.Applied>(confirmBot()).tableSession.id)
        }

    @ParameterizedTest(name = "{0}")
    @MethodSource("rollbackScenarios")
    fun `every reachable activation checkpoint rolls back the full authoritative snapshot`(
        scenario: TestActivationRollbackScenario,
    ) = runBlocking {
        val database =
            GuestTableContextLifecycleTestDatabase.create(
                "guest-activation-rollback-${scenario.toString().lowercase().replace('/', '-')}",
            )
        val now = Instant.parse("2026-08-05T10:00:00Z")
        database.seedUnrelatedActivationState(now)
        val tableSessionId =
            when (scenario.sessionState) {
                TestActivationSessionState.NEW -> null
                TestActivationSessionState.EXISTING ->
                    database.createActiveSession(
                        startedAt = now.minus(Duration.ofHours(2)),
                        lastActivityAt = now.minus(Duration.ofHours(1)),
                        expiresAt = now.plus(Duration.ofMinutes(30)),
                    )
            }
        tableSessionId?.let { database.insertExit(it, now.minus(Duration.ofMinutes(20))) }
        database.insertDialog()
        if (scenario.contextState == TestActivationContextState.UPDATE) {
            database.insertContext(
                tableToken = "PREVIOUS_CONTEXT",
                updatedAt = now.minus(Duration.ofDays(1)),
            )
        }
        val before = database.activationAuthoritativeSnapshot()
        var checkpointReached = false
        val repository =
            database.lifecycleRepository { reached ->
                if (reached == scenario.checkpoint) {
                    checkpointReached = true
                    throw SQLException("injected activation failure")
                }
            }

        val result =
            repository.activate(
                actorUserId = database.actorUserId,
                chatId = database.chatId,
                tableToken = database.tableToken,
                expectedVenueId = database.venueId,
                expectedTableId = database.tableId,
                expectedExitEventId =
                    TableSessionRepository(
                        database.dataSource,
                    ).currentQrExit(database.actorUserId, database.venueId, database.tableId, now),
                ttl = Duration.ofHours(4),
                now = now,
            )

        assertTrue(checkpointReached, scenario.toString())
        assertEquals(GuestTableActivationResult.DatabaseUnavailable, result, scenario.toString())
        assertEquals(before, database.activationAuthoritativeSnapshot(), scenario.toString())
    }

    @Test
    fun `activation final identity and availability checks deny without mutations`() =
        runBlocking {
            val database = GuestTableContextLifecycleTestDatabase.create("guest-activation-denied")
            val now = Instant.parse("2026-08-05T10:00:00Z")
            database.insertDialog()
            val result =
                database.lifecycleRepository().activate(
                    actorUserId = database.actorUserId,
                    chatId = database.chatId,
                    tableToken = database.tableToken,
                    expectedVenueId = database.venueId,
                    expectedTableId = database.tableId + 10_000,
                    ttl = Duration.ofHours(4),
                    now = now,
                )

            assertIs<GuestTableActivationResult.Denied>(result)
            assertEquals(0, database.countRows("table_sessions"))
            assertEquals("QUICK_ORDER_WAIT_TEXT", database.dialogSnapshot()?.first)
            assertNull(database.contextSnapshot())
        }

    @Test
    fun `platform Mini App resolve requires confirmed context and never creates a session on denial`() =
        runBlocking {
            val database = GuestTableContextLifecycleTestDatabase.create("platform-resolve-no-context")
            val now = Instant.parse("2026-08-05T10:00:00Z")

            val result =
                database.lifecycleRepository().resolvePlatformMiniApp(
                    actorUserId = database.actorUserId,
                    tableToken = database.tableToken,
                    expectedVenueId = database.venueId,
                    expectedTableId = database.tableId,
                    requestedTableSessionId = null,
                    ttl = Duration.ofHours(4),
                    now = now,
                )

            assertEquals(PlatformGuestTableResolveResult.Denied, result)
            assertEquals(0, database.countRows("table_sessions"))
            assertEquals(0, database.countRows("tab"))
            assertEquals(0, database.countRows("guest_table_session_exits"))
        }

    @Test
    fun `platform Mini App resolve touches only context-bound session and creates personal tab in same transaction`() =
        runBlocking {
            val database = GuestTableContextLifecycleTestDatabase.create("platform-resolve-confirmed")
            val now = Instant.parse("2026-08-05T10:00:00Z")
            val tableSessionId =
                database.createActiveSession(
                    startedAt = now.minus(Duration.ofHours(2)),
                    lastActivityAt = now.minus(Duration.ofHours(1)),
                    expiresAt = now.plus(Duration.ofMinutes(30)),
                )
            database.insertContext(tableSessionId = tableSessionId, updatedAt = now.minus(Duration.ofMinutes(1)))

            val result =
                database.lifecycleRepository().resolvePlatformMiniApp(
                    actorUserId = database.actorUserId,
                    tableToken = database.tableToken,
                    expectedVenueId = database.venueId,
                    expectedTableId = database.tableId,
                    requestedTableSessionId = tableSessionId,
                    ttl = Duration.ofHours(4),
                    now = now,
                    createPersonalTab = true,
                )

            val allowed = assertIs<PlatformGuestTableResolveResult.Allowed>(result)
            assertEquals(tableSessionId, allowed.tableSession.id)
            assertEquals(now, database.sessionSnapshot(tableSessionId)?.lastActivityAt)
            assertEquals(1, database.countRows("tab"))
            assertEquals(1, database.countRows("tab_member"))
            assertEquals(0, database.countRows("guest_table_session_exits"))
        }

    @Test
    fun `platform Mini App resolve with exit marker denies without touch tab or exit clear`() =
        runBlocking {
            val database = GuestTableContextLifecycleTestDatabase.create("platform-resolve-after-exit")
            val now = Instant.parse("2026-08-05T10:00:00Z")
            val tableSessionId =
                database.createActiveSession(
                    startedAt = now.minus(Duration.ofHours(2)),
                    lastActivityAt = now.minus(Duration.ofHours(1)),
                    expiresAt = now.plus(Duration.ofMinutes(30)),
                )
            database.insertContext(tableSessionId = tableSessionId, updatedAt = now.minus(Duration.ofMinutes(1)))
            database.insertExit(tableSessionId, now.minus(Duration.ofSeconds(30)))
            val sessionBefore = database.sessionSnapshot(tableSessionId)
            val exitBefore = database.exitSnapshot(tableSessionId)

            val result =
                database.lifecycleRepository().resolvePlatformMiniApp(
                    actorUserId = database.actorUserId,
                    tableToken = database.tableToken,
                    expectedVenueId = database.venueId,
                    expectedTableId = database.tableId,
                    requestedTableSessionId = tableSessionId,
                    ttl = Duration.ofHours(4),
                    now = now,
                )

            assertEquals(PlatformGuestTableResolveResult.Denied, result)
            assertEquals(sessionBefore, database.sessionSnapshot(tableSessionId))
            assertEquals(exitBefore, database.exitSnapshot(tableSessionId))
            assertEquals(0, database.countRows("tab"))
        }

    companion object {
        @JvmStatic
        fun rollbackScenarios(): List<TestActivationRollbackScenario> =
            TestActivationSessionState.entries.flatMap { sessionState ->
                TestActivationContextState.entries.flatMap { contextState ->
                    GuestTableActivationCheckpoint.entries.map { checkpoint ->
                        TestActivationRollbackScenario(
                            sessionState = sessionState,
                            contextState = contextState,
                            checkpoint = checkpoint,
                        )
                    }
                }
            }
    }
}
