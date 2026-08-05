package com.hookah.platform.backend.miniapp.guest.db

import kotlinx.coroutines.runBlocking
import java.time.Duration
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class GuestTableContextTeardownRepositoryTest {
    @Test
    fun `teardown clears stored context independently of token table venue and subscription availability`() =
        runBlocking {
            val scenarios =
                listOf(
                    TeardownScenario("valid", expectExit = true) {},
                    TeardownScenario("rotated-token", expectExit = true) { database ->
                        database.update("UPDATE table_tokens SET is_active = FALSE WHERE token = ?") {
                            it.setString(1, database.tableToken)
                        }
                        database.update(
                            "INSERT INTO table_tokens (token, table_id, is_active) VALUES ('ROTATED_TOKEN', ?, TRUE)",
                        ) { it.setLong(1, database.tableId) }
                    },
                    TeardownScenario("disabled-token", expectExit = true) { database ->
                        database.update("UPDATE table_tokens SET is_active = FALSE WHERE token = ?") {
                            it.setString(1, database.tableToken)
                        }
                    },
                    TeardownScenario("disabled-table", expectExit = true) { database ->
                        database.update("UPDATE venue_tables SET is_active = FALSE WHERE id = ?") {
                            it.setLong(1, database.tableId)
                        }
                    },
                    TeardownScenario("paused-venue", expectExit = true) { database ->
                        database.update("UPDATE venues SET status = 'PAUSED' WHERE id = ?") {
                            it.setLong(1, database.venueId)
                        }
                    },
                    TeardownScenario("hidden-venue", expectExit = true) { database ->
                        database.update("UPDATE venues SET status = 'HIDDEN' WHERE id = ?") {
                            it.setLong(1, database.venueId)
                        }
                    },
                    TeardownScenario("blocked-subscription", expectExit = true) { database ->
                        database.update(
                            "UPDATE venue_subscriptions SET status = 'SUSPENDED_BY_PLATFORM' WHERE venue_id = ?",
                        ) { it.setLong(1, database.venueId) }
                    },
                    TeardownScenario("deleted-table", expectExit = false) { database ->
                        database.update("DELETE FROM venue_tables WHERE id = ?") {
                            it.setLong(1, database.tableId)
                        }
                    },
                )

            scenarios.forEach { scenario ->
                val database = GuestTableContextLifecycleTestDatabase.create("guest-teardown-${scenario.name}")
                val now = Instant.parse("2026-08-05T10:00:00Z")
                val tableSessionId =
                    database.createActiveSession(
                        startedAt = now.minus(Duration.ofHours(2)),
                        lastActivityAt = now.minus(Duration.ofMinutes(5)),
                        expiresAt = now.plus(Duration.ofHours(2)),
                    )
                database.createPersonalTab(tableSessionId)
                database.insertContext(updatedAt = now.minus(Duration.ofMinutes(1)))
                database.insertDialog()
                scenario.mutate(database)

                val result =
                    database.lifecycleRepository().teardownByChat(
                        actorUserId = database.actorUserId,
                        chatId = database.chatId,
                        now = now,
                    )

                val cleared = assertIs<GuestTableTeardownResult.Cleared>(result, scenario.name)
                assertEquals(scenario.expectExit, cleared.exitRecorded, scenario.name)
                assertNull(database.contextSnapshot(), scenario.name)
                assertNull(database.dialogSnapshot(), scenario.name)
                if (scenario.expectExit) {
                    assertNotNull(database.exitSnapshot(tableSessionId), scenario.name)
                } else {
                    assertNull(database.exitSnapshot(tableSessionId), scenario.name)
                }
            }
        }

    @Test
    fun `ordinary guest context saved before first session still records exit`() =
        runBlocking {
            val database = GuestTableContextLifecycleTestDatabase.create("guest-teardown-context-before-session")
            val now = Instant.parse("2026-08-05T10:00:00Z")
            database.insertContext(updatedAt = now.minus(Duration.ofHours(2)))
            database.insertDialog()
            val tableSessionId =
                database.createActiveSession(
                    startedAt = now.minus(Duration.ofHours(1)),
                    lastActivityAt = now.minus(Duration.ofMinutes(5)),
                    expiresAt = now.plus(Duration.ofHours(2)),
                )
            database.createPersonalTab(tableSessionId)

            val result =
                database.lifecycleRepository().teardownByChat(
                    actorUserId = database.actorUserId,
                    chatId = database.chatId,
                    now = now,
                )

            val cleared = assertIs<GuestTableTeardownResult.Cleared>(result)
            assertEquals(tableSessionId, cleared.tableSessionId)
            assertEquals(true, cleared.exitRecorded)
            assertNotNull(database.exitSnapshot(tableSessionId))
            assertNull(database.contextSnapshot())
            assertNull(database.dialogSnapshot())
        }

    @Test
    fun `teardown preserves ordinary guest obligation blocking without availability lookup`() =
        runBlocking {
            val database = GuestTableContextLifecycleTestDatabase.create("guest-teardown-blocked")
            val now = Instant.parse("2026-08-05T10:00:00Z")
            val tableSessionId =
                database.createActiveSession(
                    startedAt = now.minus(Duration.ofHours(2)),
                    lastActivityAt = now.minus(Duration.ofMinutes(5)),
                    expiresAt = now.plus(Duration.ofHours(2)),
                )
            database.createPersonalTab(tableSessionId)
            database.createActiveStaffCall(tableSessionId)
            database.insertContext(updatedAt = now.minus(Duration.ofMinutes(1)))
            database.insertDialog()
            database.update("UPDATE venues SET status = 'PAUSED' WHERE id = ?") {
                it.setLong(1, database.venueId)
            }

            val result =
                database.lifecycleRepository().teardownByChat(
                    actorUserId = database.actorUserId,
                    chatId = database.chatId,
                    now = now,
                )

            val blocked = assertIs<GuestTableTeardownResult.Blocked>(result)
            assertEquals(TableSessionEndBlockedReason.ACTIVE_STAFF_CALL, blocked.reason)
            assertEquals(database.tableToken, blocked.identity.tableToken)
            assertNotNull(database.contextSnapshot())
            assertNotNull(database.dialogSnapshot())
            assertNull(database.exitSnapshot(tableSessionId))
        }

    @Test
    fun `actor token teardown denies mismatched explicit session without clearing state`() =
        runBlocking {
            val database = GuestTableContextLifecycleTestDatabase.create("guest-teardown-session-mismatch")
            val now = Instant.parse("2026-08-05T10:00:00Z")
            val tableSessionId =
                database.createActiveSession(
                    startedAt = now.minus(Duration.ofHours(2)),
                    lastActivityAt = now.minus(Duration.ofMinutes(5)),
                    expiresAt = now.plus(Duration.ofHours(2)),
                )
            database.createPersonalTab(tableSessionId)
            database.insertContext(updatedAt = now.minus(Duration.ofMinutes(1)))
            database.insertDialog()

            val result =
                database.lifecycleRepository().teardownByActorAndToken(
                    actorUserId = database.actorUserId,
                    tableToken = database.tableToken,
                    expectedTableSessionId = tableSessionId + 1,
                    now = now,
                )

            assertEquals(GuestTableTeardownResult.Denied, result)
            assertNotNull(database.contextSnapshot())
            assertNotNull(database.dialogSnapshot())
            assertNull(database.exitSnapshot(tableSessionId))
        }

    @Test
    fun `actor token teardown clears exact context after table deletion`() =
        runBlocking {
            val database = GuestTableContextLifecycleTestDatabase.create("guest-teardown-token-table-deleted")
            val now = Instant.parse("2026-08-05T10:00:00Z")
            val tableSessionId =
                database.createActiveSession(
                    startedAt = now.minus(Duration.ofHours(2)),
                    lastActivityAt = now.minus(Duration.ofMinutes(5)),
                    expiresAt = now.plus(Duration.ofHours(2)),
                )
            database.createPersonalTab(tableSessionId)
            database.insertContext(updatedAt = now.minus(Duration.ofMinutes(1)))
            database.insertDialog()
            database.update("DELETE FROM venue_tables WHERE id = ?") {
                it.setLong(1, database.tableId)
            }

            val result =
                database.lifecycleRepository().teardownByActorAndToken(
                    actorUserId = database.actorUserId,
                    tableToken = database.tableToken,
                    expectedTableSessionId = tableSessionId,
                    now = now,
                )

            val cleared = assertIs<GuestTableTeardownResult.Cleared>(result)
            assertNull(cleared.tableSessionId)
            assertEquals(false, cleared.exitRecorded)
            assertNull(database.contextSnapshot())
            assertNull(database.dialogSnapshot())
        }

    @Test
    fun `actor token teardown clears stale context when expected old session ended before a newer session`() =
        runBlocking {
            val database = GuestTableContextLifecycleTestDatabase.create("guest-teardown-ended-explicit-session")
            val now = Instant.parse("2026-08-05T10:00:00Z")
            val oldTableSessionId =
                database.createActiveSession(
                    startedAt = now.minus(Duration.ofHours(3)),
                    lastActivityAt = now.minus(Duration.ofHours(2)),
                    expiresAt = now.plus(Duration.ofHours(1)),
                )
            database.update(
                "UPDATE table_sessions SET status = 'ENDED', ended_at = ? WHERE id = ?",
            ) {
                it.setObject(1, now.minus(Duration.ofHours(1)))
                it.setLong(2, oldTableSessionId)
            }
            database.insertContext(updatedAt = now.minus(Duration.ofMinutes(30)))
            database.insertDialog()
            val newTableSessionId =
                database.createActiveSession(
                    startedAt = now.minus(Duration.ofMinutes(10)),
                    lastActivityAt = now.minus(Duration.ofMinutes(5)),
                    expiresAt = now.plus(Duration.ofHours(2)),
                )
            database.createPersonalTab(newTableSessionId)

            val result =
                database.lifecycleRepository().teardownByActorAndToken(
                    actorUserId = database.actorUserId,
                    tableToken = database.tableToken,
                    expectedTableSessionId = oldTableSessionId,
                    now = now,
                )

            val cleared = assertIs<GuestTableTeardownResult.Cleared>(result)
            assertNull(cleared.tableSessionId)
            assertEquals(false, cleared.exitRecorded)
            assertNull(database.exitSnapshot(newTableSessionId))
            assertNull(database.contextSnapshot())
            assertNull(database.dialogSnapshot())
        }

    @Test
    fun `stale context without an active session still clears context and dialog`() =
        runBlocking {
            val database = GuestTableContextLifecycleTestDatabase.create("guest-teardown-stale")
            val now = Instant.parse("2026-08-05T10:00:00Z")
            database.insertContext(updatedAt = now.minus(Duration.ofDays(1)))
            database.insertDialog()

            val result =
                database.lifecycleRepository().teardownByActorAndToken(
                    actorUserId = database.actorUserId,
                    tableToken = database.tableToken,
                    expectedTableSessionId = 404L,
                    now = now,
                )

            val cleared = assertIs<GuestTableTeardownResult.Cleared>(result)
            assertNull(cleared.tableSessionId)
            assertEquals(false, cleared.exitRecorded)
            assertNull(database.contextSnapshot())
            assertNull(database.dialogSnapshot())
        }

    @Test
    fun `chat teardown without context still clears persisted dialog`() =
        runBlocking {
            val database = GuestTableContextLifecycleTestDatabase.create("guest-teardown-prompt-only")
            database.insertDialog()

            val result =
                database.lifecycleRepository().teardownByChat(
                    actorUserId = database.actorUserId,
                    chatId = database.chatId,
                )

            assertEquals(GuestTableTeardownResult.Missing, result)
            assertNull(database.dialogSnapshot())
        }

    private data class TeardownScenario(
        val name: String,
        val expectExit: Boolean,
        val mutate: (GuestTableContextLifecycleTestDatabase) -> Unit,
    )
}
