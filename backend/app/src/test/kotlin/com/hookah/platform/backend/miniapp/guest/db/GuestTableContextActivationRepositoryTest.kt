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
            database.insertContext(updatedAt = now.minus(Duration.ofMinutes(1)))

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
            database.insertContext(updatedAt = now.minus(Duration.ofMinutes(1)))
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
