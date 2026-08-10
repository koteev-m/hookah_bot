package com.hookah.platform.backend.miniapp.venue.menu

import com.hookah.platform.backend.api.InvalidInputException
import com.hookah.platform.backend.test.PostgresTestEnv
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.SQLException
import java.sql.Statement
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import javax.sql.DataSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class VenueMenuOptionNormalizationConcurrencyPostgresTest {
    @Test
    fun `concurrent normalizations serialize with one audited winner`() =
        runBlocking {
            val database = PostgresTestEnv.createDatabase()
            PostgresTestEnv.createDataSource(database).use { dataSource ->
                val fixture = seedFixture(dataSource)
                val holderDataSource = HeldItemLockDataSource(dataSource)
                val waiterDataSource = TrackedItemLockDataSource(dataSource)

                val (winner, loser) =
                    runWithHeldItemLock(
                        observerDataSource = dataSource,
                        holderDataSource = holderDataSource,
                        waiterDataSource = waiterDataSource,
                        beforeRelease = { assertInitialState(dataSource, fixture) },
                        holderAction = {
                            runBlocking {
                                VenueMenuRepository(holderDataSource).normalizeHookahFlavorProfiles(
                                    venueId = fixture.venueId,
                                    itemId = fixture.itemId,
                                    actorUserId = FIRST_ACTOR_ID,
                                    source = MenuOptionDeleteSource.TELEGRAM_BOT,
                                )
                            }
                        },
                        waiterAction = {
                            runBlocking {
                                VenueMenuRepository(waiterDataSource).normalizeHookahFlavorProfiles(
                                    venueId = fixture.venueId,
                                    itemId = fixture.itemId,
                                    actorUserId = SECOND_ACTOR_ID,
                                    source = MenuOptionDeleteSource.TELEGRAM_BOT,
                                )
                            }
                        },
                    )

                assertEquals(
                    HookahFlavorProfileNormalizationResult(
                        removedCount = fixture.obsoleteOptionIds.size,
                        addedCount = HookahFlavorProfileService.baseProfiles.size - 1,
                    ),
                    winner,
                )
                assertEquals(
                    HookahFlavorProfileNormalizationResult(removedCount = 0, addedCount = 0),
                    loser,
                )
                assertNormalizedState(
                    dataSource = dataSource,
                    fixture = fixture,
                    expectedPreservedIds = setOf(fixture.existingCanonicalOptionId, fixture.customOptionId),
                    expectedNonCanonicalNames = setOf(CUSTOM_OPTION_NAME),
                )
                assertDeleteAudits(
                    dataSource = dataSource,
                    fixture = fixture,
                    expectedOptionIds = fixture.obsoleteOptionIds,
                    expectedActorUserId = FIRST_ACTOR_ID,
                )
            }
        }

    @Test
    fun `normalization and direct delete serialize without duplicate audit`() =
        runBlocking {
            val database = PostgresTestEnv.createDatabase()
            PostgresTestEnv.createDataSource(database).use { dataSource ->
                val fixture = seedFixture(dataSource)
                val targetOptionId = fixture.obsoleteOptionIds.first()
                val holderDataSource = HeldItemLockDataSource(dataSource)
                val waiterDataSource = TrackedItemLockDataSource(dataSource)

                val (normalization, directDelete) =
                    runWithHeldItemLock(
                        observerDataSource = dataSource,
                        holderDataSource = holderDataSource,
                        waiterDataSource = waiterDataSource,
                        beforeRelease = { assertInitialState(dataSource, fixture) },
                        holderAction = {
                            runBlocking {
                                VenueMenuRepository(holderDataSource).normalizeHookahFlavorProfiles(
                                    venueId = fixture.venueId,
                                    itemId = fixture.itemId,
                                    actorUserId = FIRST_ACTOR_ID,
                                    source = MenuOptionDeleteSource.TELEGRAM_BOT,
                                )
                            }
                        },
                        waiterAction = {
                            runBlocking {
                                VenueMenuRepository(waiterDataSource).deleteOption(
                                    venueId = fixture.venueId,
                                    optionId = targetOptionId,
                                    actorUserId = SECOND_ACTOR_ID,
                                    source = MenuOptionDeleteSource.VENUE_MINI_APP,
                                )
                            }
                        },
                    )

                assertEquals(fixture.obsoleteOptionIds.size, assertNotNull(normalization).removedCount)
                assertFalse(directDelete)
                assertNormalizedState(
                    dataSource = dataSource,
                    fixture = fixture,
                    expectedPreservedIds = setOf(fixture.existingCanonicalOptionId, fixture.customOptionId),
                    expectedNonCanonicalNames = setOf(CUSTOM_OPTION_NAME),
                )
                assertDeleteAudits(
                    dataSource = dataSource,
                    fixture = fixture,
                    expectedOptionIds = fixture.obsoleteOptionIds,
                    expectedActorUserId = FIRST_ACTOR_ID,
                )
                assertEquals(
                    0,
                    readDeleteAudits(dataSource).count { audit -> audit.actorUserId == SECOND_ACTOR_ID },
                )
            }
        }

    @Test
    fun `canonical create and normalization share item lock without duplicate profile`() =
        runBlocking {
            val database = PostgresTestEnv.createDatabase()
            PostgresTestEnv.createDataSource(database).use { dataSource ->
                val fixture = seedFixture(dataSource)
                val profileName = HookahFlavorProfileService.baseProfiles.last()
                val holderDataSource = HeldItemLockDataSource(dataSource)
                val waiterDataSource = TrackedItemLockDataSource(dataSource)

                val (created, normalization) =
                    runWithHeldItemLock(
                        observerDataSource = dataSource,
                        holderDataSource = holderDataSource,
                        waiterDataSource = waiterDataSource,
                        beforeRelease = { assertInitialState(dataSource, fixture) },
                        holderAction = {
                            runBlocking {
                                VenueMenuRepository(holderDataSource).createOption(
                                    venueId = fixture.venueId,
                                    itemId = fixture.itemId,
                                    name = profileName,
                                    priceDeltaMinor = 0,
                                    isAvailable = true,
                                )
                            }
                        },
                        waiterAction = {
                            runBlocking {
                                VenueMenuRepository(waiterDataSource).normalizeHookahFlavorProfiles(
                                    venueId = fixture.venueId,
                                    itemId = fixture.itemId,
                                    actorUserId = FIRST_ACTOR_ID,
                                    source = MenuOptionDeleteSource.TELEGRAM_BOT,
                                )
                            }
                        },
                    )

                val createdOption = assertNotNull(created)
                val normalizationResult = assertNotNull(normalization)
                assertEquals(profileName, createdOption.name)
                assertEquals(fixture.obsoleteOptionIds.size, normalizationResult.removedCount)
                assertEquals(HookahFlavorProfileService.baseProfiles.size - 2, normalizationResult.addedCount)
                assertNormalizedState(
                    dataSource = dataSource,
                    fixture = fixture,
                    expectedPreservedIds =
                        setOf(
                            fixture.existingCanonicalOptionId,
                            fixture.customOptionId,
                            createdOption.id,
                        ),
                    expectedNonCanonicalNames = setOf(CUSTOM_OPTION_NAME),
                )
                assertEquals(
                    1,
                    readOptions(dataSource, fixture.itemId).count { option ->
                        HookahFlavorProfileService.normalizeFlavorNameKey(option.name) ==
                            HookahFlavorProfileService.normalizeFlavorNameKey(profileName)
                    },
                )
                assertFailsWith<InvalidInputException> {
                    VenueMenuRepository(dataSource).createOption(
                        venueId = fixture.venueId,
                        itemId = fixture.itemId,
                        name = profileName,
                        priceDeltaMinor = 0,
                        isAvailable = true,
                    )
                }
                assertDeleteAudits(
                    dataSource = dataSource,
                    fixture = fixture,
                    expectedOptionIds = fixture.obsoleteOptionIds,
                    expectedActorUserId = FIRST_ACTOR_ID,
                )
            }
        }

    @Test
    fun `canonical update and normalization share item lock and preserve updated option`() =
        runBlocking {
            val database = PostgresTestEnv.createDatabase()
            PostgresTestEnv.createDataSource(database).use { dataSource ->
                val fixture = seedFixture(dataSource)
                val profileName = HookahFlavorProfileService.baseProfiles.last()
                val holderDataSource = HeldItemLockDataSource(dataSource)
                val waiterDataSource = TrackedItemLockDataSource(dataSource)

                val (updated, normalization) =
                    runWithHeldItemLock(
                        observerDataSource = dataSource,
                        holderDataSource = holderDataSource,
                        waiterDataSource = waiterDataSource,
                        beforeRelease = { assertInitialState(dataSource, fixture) },
                        holderAction = {
                            runBlocking {
                                VenueMenuRepository(holderDataSource).updateOption(
                                    venueId = fixture.venueId,
                                    optionId = fixture.customOptionId,
                                    name = profileName,
                                    priceDeltaMinor = null,
                                    isAvailable = null,
                                    actorUserId = SECOND_ACTOR_ID,
                                    source = MenuOptionRenameSource.VENUE_MINI_APP,
                                )
                            }
                        },
                        waiterAction = {
                            runBlocking {
                                VenueMenuRepository(waiterDataSource).normalizeHookahFlavorProfiles(
                                    venueId = fixture.venueId,
                                    itemId = fixture.itemId,
                                    actorUserId = FIRST_ACTOR_ID,
                                    source = MenuOptionDeleteSource.TELEGRAM_BOT,
                                )
                            }
                        },
                    )

                val updatedOption = assertNotNull(updated)
                val normalizationResult = assertNotNull(normalization)
                assertEquals(fixture.customOptionId, updatedOption.id)
                assertEquals(profileName, updatedOption.name)
                assertEquals(fixture.obsoleteOptionIds.size, normalizationResult.removedCount)
                assertEquals(HookahFlavorProfileService.baseProfiles.size - 2, normalizationResult.addedCount)
                assertNormalizedState(
                    dataSource = dataSource,
                    fixture = fixture,
                    expectedPreservedIds =
                        setOf(
                            fixture.existingCanonicalOptionId,
                            fixture.customOptionId,
                        ),
                    expectedNonCanonicalNames = emptySet(),
                )
                val finalUpdatedOption =
                    readOptions(dataSource, fixture.itemId).single { option ->
                        option.id == fixture.customOptionId
                    }
                assertEquals(profileName, finalUpdatedOption.name)
                assertRenameAudit(
                    dataSource = dataSource,
                    fixture = fixture,
                    expectedOldName = CUSTOM_OPTION_NAME,
                    expectedNewName = profileName,
                    expectedActorUserId = SECOND_ACTOR_ID,
                    expectedSource = MenuOptionRenameSource.VENUE_MINI_APP,
                )
                assertFailsWith<InvalidInputException> {
                    VenueMenuRepository(dataSource).updateOption(
                        venueId = fixture.venueId,
                        optionId = fixture.existingCanonicalOptionId,
                        name = profileName,
                        priceDeltaMinor = null,
                        isAvailable = null,
                        actorUserId = SECOND_ACTOR_ID,
                        source = MenuOptionRenameSource.VENUE_MINI_APP,
                    )
                }
                assertDeleteAudits(
                    dataSource = dataSource,
                    fixture = fixture,
                    expectedOptionIds = fixture.obsoleteOptionIds,
                    expectedActorUserId = FIRST_ACTOR_ID,
                )
            }
        }

    @Test
    fun `concurrent renames serialize and audit locked database transitions`() =
        runBlocking {
            val database = PostgresTestEnv.createDatabase()
            PostgresTestEnv.createDataSource(database).use { dataSource ->
                val fixture = seedFixture(dataSource)
                val holderDataSource = HeldItemLockDataSource(dataSource)
                val waiterDataSource = TrackedItemLockDataSource(dataSource)

                val (firstRename, secondRename) =
                    runWithHeldItemLock(
                        observerDataSource = dataSource,
                        holderDataSource = holderDataSource,
                        waiterDataSource = waiterDataSource,
                        beforeRelease = { assertInitialState(dataSource, fixture) },
                        holderAction = {
                            runBlocking {
                                VenueMenuRepository(holderDataSource).updateOption(
                                    venueId = fixture.venueId,
                                    optionId = fixture.customOptionId,
                                    name = "Первое имя",
                                    priceDeltaMinor = null,
                                    isAvailable = null,
                                    actorUserId = FIRST_ACTOR_ID,
                                    source = MenuOptionRenameSource.VENUE_MINI_APP,
                                )
                            }
                        },
                        waiterAction = {
                            runBlocking {
                                VenueMenuRepository(waiterDataSource).updateOption(
                                    venueId = fixture.venueId,
                                    optionId = fixture.customOptionId,
                                    name = "Второе имя",
                                    priceDeltaMinor = null,
                                    isAvailable = null,
                                    actorUserId = SECOND_ACTOR_ID,
                                    source = MenuOptionRenameSource.TELEGRAM_BOT,
                                )
                            }
                        },
                    )

                assertEquals("Первое имя", assertNotNull(firstRename).name)
                assertEquals("Второе имя", assertNotNull(secondRename).name)
                assertEquals(
                    "Второе имя",
                    readOptions(dataSource, fixture.itemId).single { it.id == fixture.customOptionId }.name,
                )
                val audits = readRenameAudits(dataSource)
                assertEquals(2, audits.size)
                assertRenameAudit(
                    audit = audits[0],
                    fixture = fixture,
                    expectedOldName = CUSTOM_OPTION_NAME,
                    expectedNewName = "Первое имя",
                    expectedActorUserId = FIRST_ACTOR_ID,
                    expectedSource = MenuOptionRenameSource.VENUE_MINI_APP,
                )
                assertRenameAudit(
                    audit = audits[1],
                    fixture = fixture,
                    expectedOldName = "Первое имя",
                    expectedNewName = "Второе имя",
                    expectedActorUserId = SECOND_ACTOR_ID,
                    expectedSource = MenuOptionRenameSource.TELEGRAM_BOT,
                )
                assertTrue(readDeleteAudits(dataSource).isEmpty())
            }
        }

    @Test
    fun `canonical rename and create serialize with collision loser and one rename audit`() =
        runBlocking {
            val database = PostgresTestEnv.createDatabase()
            PostgresTestEnv.createDataSource(database).use { dataSource ->
                val fixture = seedFixture(dataSource)
                val profileName = HookahFlavorProfileService.baseProfiles.last()
                val holderDataSource = HeldItemLockDataSource(dataSource)
                val waiterDataSource = TrackedItemLockDataSource(dataSource)

                val (renamed, createAttempt) =
                    runWithHeldItemLock(
                        observerDataSource = dataSource,
                        holderDataSource = holderDataSource,
                        waiterDataSource = waiterDataSource,
                        beforeRelease = { assertInitialState(dataSource, fixture) },
                        holderAction = {
                            runBlocking {
                                VenueMenuRepository(holderDataSource).updateOption(
                                    venueId = fixture.venueId,
                                    optionId = fixture.customOptionId,
                                    name = profileName,
                                    priceDeltaMinor = null,
                                    isAvailable = null,
                                    actorUserId = FIRST_ACTOR_ID,
                                    source = MenuOptionRenameSource.VENUE_MINI_APP,
                                )
                            }
                        },
                        waiterAction = {
                            runCatching {
                                runBlocking {
                                    VenueMenuRepository(waiterDataSource).createOption(
                                        venueId = fixture.venueId,
                                        itemId = fixture.itemId,
                                        name = profileName,
                                        priceDeltaMinor = 0,
                                        isAvailable = true,
                                    )
                                }
                            }
                        },
                    )

                assertEquals(profileName, assertNotNull(renamed).name)
                assertTrue(createAttempt.exceptionOrNull() is InvalidInputException)
                assertEquals(
                    1,
                    readOptions(dataSource, fixture.itemId).count { option ->
                        HookahFlavorProfileService.normalizeFlavorNameKey(option.name) ==
                            HookahFlavorProfileService.normalizeFlavorNameKey(profileName)
                    },
                )
                assertRenameAudit(
                    dataSource = dataSource,
                    fixture = fixture,
                    expectedOldName = CUSTOM_OPTION_NAME,
                    expectedNewName = profileName,
                    expectedActorUserId = FIRST_ACTOR_ID,
                    expectedSource = MenuOptionRenameSource.VENUE_MINI_APP,
                )
                assertTrue(readDeleteAudits(dataSource).isEmpty())
            }
        }

    @Test
    fun `direct delete and rename serialize with not found rename and zero rename audit`() =
        runBlocking {
            val database = PostgresTestEnv.createDatabase()
            PostgresTestEnv.createDataSource(database).use { dataSource ->
                val fixture = seedFixture(dataSource)
                val holderDataSource = HeldItemLockDataSource(dataSource)
                val waiterDataSource = TrackedItemLockDataSource(dataSource)

                val (deleted, renamed) =
                    runWithHeldItemLock(
                        observerDataSource = dataSource,
                        holderDataSource = holderDataSource,
                        waiterDataSource = waiterDataSource,
                        beforeRelease = { assertInitialState(dataSource, fixture) },
                        holderAction = {
                            runBlocking {
                                VenueMenuRepository(holderDataSource).deleteOption(
                                    venueId = fixture.venueId,
                                    optionId = fixture.customOptionId,
                                    actorUserId = FIRST_ACTOR_ID,
                                    source = MenuOptionDeleteSource.TELEGRAM_BOT,
                                )
                            }
                        },
                        waiterAction = {
                            runBlocking {
                                VenueMenuRepository(waiterDataSource).updateOption(
                                    venueId = fixture.venueId,
                                    optionId = fixture.customOptionId,
                                    name = "Удалённый вариант",
                                    priceDeltaMinor = null,
                                    isAvailable = null,
                                    actorUserId = SECOND_ACTOR_ID,
                                    source = MenuOptionRenameSource.VENUE_MINI_APP,
                                )
                            }
                        },
                    )

                assertTrue(deleted)
                assertEquals(null, renamed)
                assertTrue(readOptions(dataSource, fixture.itemId).none { it.id == fixture.customOptionId })
                assertDeleteAudits(
                    dataSource = dataSource,
                    fixture = fixture,
                    expectedOptionIds = setOf(fixture.customOptionId),
                    expectedActorUserId = FIRST_ACTOR_ID,
                )
                assertTrue(readRenameAudits(dataSource).isEmpty())
            }
        }

    private fun <T, U> runWithHeldItemLock(
        observerDataSource: DataSource,
        holderDataSource: HeldItemLockDataSource,
        waiterDataSource: TrackedItemLockDataSource,
        beforeRelease: () -> Unit,
        holderAction: () -> T,
        waiterAction: () -> U,
    ): Pair<T, U> {
        val executor = Executors.newFixedThreadPool(2)
        val holderFuture = executor.submit(Callable<T> { holderAction() })
        try {
            assertTrue(
                holderDataSource.itemLockAcquired.await(WAIT_TIMEOUT_SECONDS, TimeUnit.SECONDS),
                "Production lock holder did not acquire the menu item lock",
            )
            val waiterFuture = executor.submit(Callable<U> { waiterAction() })
            assertTrue(
                waiterDataSource.itemLockAttempted.await(WAIT_TIMEOUT_SECONDS, TimeUnit.SECONDS),
                "Competing production mutation did not attempt the menu item lock",
            )

            val holderPid = holderDataSource.backendPid.get()
            val waiterPid = waiterDataSource.backendPid.get()
            assertTrue(holderPid > 0, "Missing PostgreSQL PID for item-lock holder")
            assertTrue(waiterPid > 0, "Missing PostgreSQL PID for item-lock waiter")
            assertFalse(holderPid == waiterPid, "Production mutations must use independent connections")

            observerDataSource.connection.use { observer ->
                val observation =
                    awaitPostgresBlock(
                        observer = observer,
                        blockedPid = waiterPid,
                        blockerPid = holderPid,
                        waiterFuture = waiterFuture,
                    )
                assertTrue(
                    observation.blocked,
                    "PostgreSQL did not report the expected item-lock blocking edge. " +
                        observation.diagnostic,
                )
            }
            beforeRelease()
            holderDataSource.allowMutation.countDown()
            return holderFuture.get(WAIT_TIMEOUT_SECONDS, TimeUnit.SECONDS) to
                waiterFuture.get(WAIT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        } finally {
            holderDataSource.allowMutation.countDown()
            executor.shutdownNow()
            executor.awaitTermination(WAIT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        }
    }

    private fun awaitPostgresBlock(
        observer: Connection,
        blockedPid: Int,
        blockerPid: Int,
        waiterFuture: Future<*>,
    ): PostgresBlockObservation {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(WAIT_TIMEOUT_SECONDS)
        var lastDiagnostic = "No PostgreSQL blocking edge observed"
        observer.prepareStatement(
            """
            SELECT
                ? = ANY(pg_blocking_pids(?)) AS blocking_pid_reported,
                EXISTS (
                    SELECT 1
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
                    WHERE blocked.pid = ?
                      AND NOT blocked.granted
                      AND blocking.pid = ?
                      AND blocking.granted
                ) AS pg_locks_edge
            """.trimIndent(),
        ).use { statement ->
            statement.setInt(1, blockerPid)
            statement.setInt(2, blockedPid)
            statement.setInt(3, blockedPid)
            statement.setInt(4, blockerPid)
            while (System.nanoTime() < deadline) {
                val (blockingPidReported, pgLocksEdge) =
                    statement.executeQuery().use { resultSet ->
                        assertTrue(resultSet.next())
                        resultSet.getBoolean("blocking_pid_reported") to
                            resultSet.getBoolean("pg_locks_edge")
                    }
                lastDiagnostic =
                    "blockedPid=$blockedPid; blockerPid=$blockerPid; " +
                    "pgBlockingPids=$blockingPidReported; pgLocksEdge=$pgLocksEdge; " +
                    "activity=${describeActivity(observer, blockerPid, blockedPid)}"
                if (blockingPidReported && pgLocksEdge) {
                    return PostgresBlockObservation(blocked = true, diagnostic = lastDiagnostic)
                }
                if (waiterFuture.isDone) {
                    return PostgresBlockObservation(blocked = false, diagnostic = lastDiagnostic)
                }
                Thread.yield()
            }
        }
        return PostgresBlockObservation(blocked = false, diagnostic = lastDiagnostic)
    }

    private fun describeActivity(
        observer: Connection,
        blockerPid: Int,
        blockedPid: Int,
    ): String =
        observer.prepareStatement(
            """
            SELECT pid, state, wait_event_type, wait_event, pg_blocking_pids(pid), query
            FROM pg_stat_activity
            WHERE pid IN (?, ?)
            ORDER BY pid
            """.trimIndent(),
        ).use { statement ->
            statement.setInt(1, blockerPid)
            statement.setInt(2, blockedPid)
            statement.executeQuery().use { resultSet ->
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

    private fun seedFixture(dataSource: DataSource): Fixture =
        dataSource.connection.use { connection ->
            val originalAutoCommit = connection.autoCommit
            connection.autoCommit = false
            try {
                insertUser(connection, FIRST_ACTOR_ID, "First option actor")
                insertUser(connection, SECOND_ACTOR_ID, "Second option actor")
                val venueId =
                    insertReturningId(
                        connection,
                        "INSERT INTO venues (name, status) VALUES ('Option concurrency venue', 'PUBLISHED')",
                    )
                val categoryId =
                    insertReturningId(
                        connection,
                        """
                        INSERT INTO menu_categories (venue_id, name, category_type)
                        VALUES (?, 'Кальянное меню', 'HOOKAH')
                        """.trimIndent(),
                    ) { statement -> statement.setLong(1, venueId) }
                val itemId =
                    insertReturningId(
                        connection,
                        """
                        INSERT INTO menu_items (
                            venue_id, category_id, name, price_minor, currency, is_available, item_type
                        )
                        VALUES (?, ?, 'Concurrency hookah', 100000, 'RUB', TRUE, 'HOOKAH')
                        """.trimIndent(),
                    ) { statement ->
                        statement.setLong(1, venueId)
                        statement.setLong(2, categoryId)
                    }
                val appleId = insertOption(connection, venueId, itemId, "Яблоко", 100, true, 0)
                val grapeId = insertOption(connection, venueId, itemId, "Виноград", 200, false, 1)
                val customId = insertOption(connection, venueId, itemId, CUSTOM_OPTION_NAME, 300, false, 2)
                val canonicalId =
                    insertOption(
                        connection = connection,
                        venueId = venueId,
                        itemId = itemId,
                        name = HookahFlavorProfileService.baseProfiles.first(),
                        priceDeltaMinor = 400,
                        isAvailable = false,
                        sortOrder = 3,
                    )
                connection.commit()
                Fixture(
                    venueId = venueId,
                    itemId = itemId,
                    obsoleteOptionIds = setOf(appleId, grapeId),
                    customOptionId = customId,
                    existingCanonicalOptionId = canonicalId,
                )
            } catch (e: Exception) {
                connection.rollback()
                throw e
            } finally {
                connection.autoCommit = originalAutoCommit
            }
        }

    private fun insertUser(
        connection: Connection,
        userId: Long,
        name: String,
    ) {
        connection.prepareStatement(
            "INSERT INTO users (telegram_user_id, first_name) VALUES (?, ?)",
        ).use { statement ->
            statement.setLong(1, userId)
            statement.setString(2, name)
            assertEquals(1, statement.executeUpdate())
        }
    }

    private fun insertOption(
        connection: Connection,
        venueId: Long,
        itemId: Long,
        name: String,
        priceDeltaMinor: Long,
        isAvailable: Boolean,
        sortOrder: Int,
    ): Long =
        insertReturningId(
            connection,
            """
            INSERT INTO menu_item_options (
                venue_id, item_id, name, price_delta_minor, is_available, sort_order
            )
            VALUES (?, ?, ?, ?, ?, ?)
            """.trimIndent(),
        ) { statement ->
            statement.setLong(1, venueId)
            statement.setLong(2, itemId)
            statement.setString(3, name)
            statement.setLong(4, priceDeltaMinor)
            statement.setBoolean(5, isAvailable)
            statement.setInt(6, sortOrder)
        }

    private fun insertReturningId(
        connection: Connection,
        sql: String,
        bind: (PreparedStatement) -> Unit = {},
    ): Long =
        connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS).use { statement ->
            bind(statement)
            assertEquals(1, statement.executeUpdate())
            statement.generatedKeys.use { keys ->
                assertTrue(keys.next())
                keys.getLong(1)
            }
        }

    private fun assertInitialState(
        dataSource: DataSource,
        fixture: Fixture,
    ) {
        val options = readOptions(dataSource, fixture.itemId)
        assertEquals(
            fixture.obsoleteOptionIds + fixture.customOptionId + fixture.existingCanonicalOptionId,
            options.map { option -> option.id }.toSet(),
        )
        assertTrue(readDeleteAudits(dataSource).isEmpty())
    }

    private fun assertNormalizedState(
        dataSource: DataSource,
        fixture: Fixture,
        expectedPreservedIds: Set<Long>,
        expectedNonCanonicalNames: Set<String>,
    ) {
        val options = readOptions(dataSource, fixture.itemId)
        assertTrue(fixture.obsoleteOptionIds.none { obsoleteId -> options.any { it.id == obsoleteId } })
        assertTrue(expectedPreservedIds.all { preservedId -> options.any { it.id == preservedId } })
        assertTrue(options.none { option -> HookahFlavorProfileService.isObsoleteProfileValue(option.name) })

        val canonicalKeys =
            HookahFlavorProfileService.baseProfiles.map {
                HookahFlavorProfileService.normalizeFlavorNameKey(it)
            }
        canonicalKeys.forEach { canonicalKey ->
            assertEquals(
                1,
                options.count { option ->
                    HookahFlavorProfileService.normalizeFlavorNameKey(option.name) == canonicalKey
                },
                "Canonical profile $canonicalKey must exist exactly once",
            )
        }
        assertEquals(
            expectedNonCanonicalNames,
            options.filterNot { option -> HookahFlavorProfileService.isCanonicalProfileValue(option.name) }
                .map { option -> option.name }
                .toSet(),
        )
    }

    private fun assertDeleteAudits(
        dataSource: DataSource,
        fixture: Fixture,
        expectedOptionIds: Set<Long>,
        expectedActorUserId: Long,
    ) {
        val audits = readDeleteAudits(dataSource)
        assertEquals(expectedOptionIds.size, audits.size)
        assertEquals(expectedOptionIds, audits.map { audit -> audit.entityId }.toSet())
        expectedOptionIds.forEach { optionId ->
            assertEquals(1, audits.count { audit -> audit.entityId == optionId })
        }
        audits.forEach { audit ->
            assertEquals(expectedActorUserId, audit.actorUserId)
            assertEquals("menu_item_option", audit.entityType)
            assertEquals(AUDIT_PAYLOAD_KEYS, audit.payload.keys)
            assertEquals(fixture.venueId, audit.payload.longValue("venueId"))
            assertEquals(fixture.itemId, audit.payload.longValue("itemId"))
            assertEquals(audit.entityId, audit.payload.longValue("optionId"))
            assertEquals(
                MenuOptionDeleteSource.TELEGRAM_BOT.name,
                audit.payload.getValue("source").jsonPrimitive.content,
            )
        }
    }

    private fun assertRenameAudit(
        dataSource: DataSource,
        fixture: Fixture,
        expectedOldName: String,
        expectedNewName: String,
        expectedActorUserId: Long,
        expectedSource: MenuOptionRenameSource,
    ) {
        val audit = readRenameAudits(dataSource).single()
        assertRenameAudit(
            audit = audit,
            fixture = fixture,
            expectedOldName = expectedOldName,
            expectedNewName = expectedNewName,
            expectedActorUserId = expectedActorUserId,
            expectedSource = expectedSource,
        )
    }

    private fun assertRenameAudit(
        audit: AuditRow,
        fixture: Fixture,
        expectedOldName: String,
        expectedNewName: String,
        expectedActorUserId: Long,
        expectedSource: MenuOptionRenameSource,
    ) {
        assertEquals(expectedActorUserId, audit.actorUserId)
        assertEquals("menu_item_option", audit.entityType)
        assertEquals(fixture.customOptionId, audit.entityId)
        assertEquals(RENAME_AUDIT_PAYLOAD_KEYS, audit.payload.keys)
        assertEquals(fixture.venueId, audit.payload.longValue("venueId"))
        assertEquals(fixture.itemId, audit.payload.longValue("itemId"))
        assertEquals(fixture.customOptionId, audit.payload.longValue("optionId"))
        assertEquals(expectedOldName, audit.payload.getValue("oldName").jsonPrimitive.content)
        assertEquals(expectedNewName, audit.payload.getValue("newName").jsonPrimitive.content)
        assertEquals(expectedSource.name, audit.payload.getValue("source").jsonPrimitive.content)
    }

    private fun readOptions(
        dataSource: DataSource,
        itemId: Long,
    ): List<OptionRow> =
        dataSource.connection.use { connection ->
            connection.prepareStatement(
                """
                SELECT id, name
                FROM menu_item_options
                WHERE item_id = ?
                ORDER BY id
                """.trimIndent(),
            ).use { statement ->
                statement.setLong(1, itemId)
                statement.executeQuery().use { resultSet ->
                    buildList {
                        while (resultSet.next()) {
                            add(OptionRow(id = resultSet.getLong("id"), name = resultSet.getString("name")))
                        }
                    }
                }
            }
        }

    private fun readDeleteAudits(dataSource: DataSource): List<AuditRow> =
        readAudits(dataSource, MENU_OPTION_DELETED_AUDIT_ACTION)

    private fun readRenameAudits(dataSource: DataSource): List<AuditRow> =
        readAudits(dataSource, MENU_OPTION_RENAMED_AUDIT_ACTION)

    private fun readAudits(
        dataSource: DataSource,
        action: String,
    ): List<AuditRow> =
        dataSource.connection.use { connection ->
            connection.prepareStatement(
                """
                SELECT actor_user_id, entity_type, entity_id, payload_json
                FROM audit_log
                WHERE action = ?
                ORDER BY id
                """.trimIndent(),
            ).use { statement ->
                statement.setString(1, action)
                statement.executeQuery().use { resultSet ->
                    buildList {
                        while (resultSet.next()) {
                            add(
                                AuditRow(
                                    actorUserId = resultSet.getLong("actor_user_id"),
                                    entityType = resultSet.getString("entity_type"),
                                    entityId = resultSet.getLong("entity_id"),
                                    payload = Json.parseToJsonElement(resultSet.getString("payload_json")).jsonObject,
                                ),
                            )
                        }
                    }
                }
            }
        }

    private fun JsonObject.longValue(key: String): Long = getValue(key).jsonPrimitive.content.toLong()

    private fun backendPid(connection: Connection): Int =
        connection.createStatement().use { statement ->
            statement.executeQuery("SELECT pg_backend_pid()").use { resultSet ->
                assertTrue(resultSet.next())
                resultSet.getInt(1)
            }
        }

    private fun String.isItemOptionMutationLock(): Boolean {
        val normalized = normalizedSql()
        return normalized.contains("from menu_items") &&
            normalized.contains("for update") &&
            !normalized.contains("nowait")
    }

    private fun String.normalizedSql(): String =
        trim()
            .replace(WHITESPACE, " ")
            .lowercase()

    private inner class HeldItemLockDataSource(
        private val delegate: DataSource,
    ) : DataSource by delegate {
        val itemLockAcquired = CountDownLatch(1)
        val allowMutation = CountDownLatch(1)
        val backendPid = AtomicInteger()
        private val held = AtomicBoolean()

        override fun getConnection(): Connection = wrap(delegate.connection)

        override fun getConnection(
            username: String?,
            password: String?,
        ): Connection = wrap(delegate.getConnection(username, password))

        private fun wrap(connection: Connection): Connection {
            backendPid.set(this@VenueMenuOptionNormalizationConcurrencyPostgresTest.backendPid(connection))
            return object : Connection by connection {
                override fun prepareStatement(sql: String): PreparedStatement {
                    val prepared = connection.prepareStatement(sql)
                    if (sql.isItemOptionMutationLock() && held.compareAndSet(false, true)) {
                        return object : PreparedStatement by prepared {
                            override fun executeQuery(): ResultSet {
                                check(!connection.autoCommit) {
                                    "Menu option item lock must be held inside the production transaction"
                                }
                                val resultSet = prepared.executeQuery()
                                itemLockAcquired.countDown()
                                if (!allowMutation.await(WAIT_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                                    resultSet.close()
                                    throw SQLException("Timed out while holding menu option item lock")
                                }
                                return resultSet
                            }
                        }
                    }
                    return prepared
                }
            }
        }
    }

    private inner class TrackedItemLockDataSource(
        private val delegate: DataSource,
    ) : DataSource by delegate {
        val itemLockAttempted = CountDownLatch(1)
        val backendPid = AtomicInteger()
        private val signalled = AtomicBoolean()

        override fun getConnection(): Connection = wrap(delegate.connection)

        override fun getConnection(
            username: String?,
            password: String?,
        ): Connection = wrap(delegate.getConnection(username, password))

        private fun wrap(connection: Connection): Connection {
            backendPid.set(this@VenueMenuOptionNormalizationConcurrencyPostgresTest.backendPid(connection))
            return object : Connection by connection {
                override fun prepareStatement(sql: String): PreparedStatement {
                    val prepared = connection.prepareStatement(sql)
                    if (sql.isItemOptionMutationLock()) {
                        return object : PreparedStatement by prepared {
                            override fun executeQuery(): ResultSet {
                                if (signalled.compareAndSet(false, true)) {
                                    itemLockAttempted.countDown()
                                }
                                return prepared.executeQuery()
                            }
                        }
                    }
                    return prepared
                }
            }
        }
    }

    private data class Fixture(
        val venueId: Long,
        val itemId: Long,
        val obsoleteOptionIds: Set<Long>,
        val customOptionId: Long,
        val existingCanonicalOptionId: Long,
    )

    private data class OptionRow(
        val id: Long,
        val name: String,
    )

    private data class AuditRow(
        val actorUserId: Long,
        val entityType: String,
        val entityId: Long,
        val payload: JsonObject,
    )

    private data class PostgresBlockObservation(
        val blocked: Boolean,
        val diagnostic: String,
    )

    private companion object {
        const val FIRST_ACTOR_ID = 97_001L
        const val SECOND_ACTOR_ID = 97_002L
        const val CUSTOM_OPTION_NAME = "Авторский микс"
        const val WAIT_TIMEOUT_SECONDS = 30L

        val AUDIT_PAYLOAD_KEYS = setOf("venueId", "itemId", "optionId", "source")
        val RENAME_AUDIT_PAYLOAD_KEYS = setOf("venueId", "itemId", "optionId", "oldName", "newName", "source")
        val WHITESPACE = Regex("\\s+")
    }
}
