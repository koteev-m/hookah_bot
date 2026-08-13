package com.hookah.platform.backend.miniapp.venue.menu

import com.hookah.platform.backend.api.DatabaseUnavailableException
import com.hookah.platform.backend.api.InvalidInputException
import com.hookah.platform.backend.api.MenuShiftCheckStaleException
import com.hookah.platform.backend.miniapp.venue.AuditLogRepository
import com.hookah.platform.backend.miniapp.venue.TransactionalAuditLogWriter
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
import java.util.concurrent.ExecutionException
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
import kotlin.test.assertNull
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
                                    deleteSource = MenuOptionDeleteSource.TELEGRAM_BOT,
                                    createSource = MenuOptionCreateSource.TELEGRAM_BOT,
                                )
                            }
                        },
                        waiterAction = {
                            runBlocking {
                                VenueMenuRepository(waiterDataSource).normalizeHookahFlavorProfiles(
                                    venueId = fixture.venueId,
                                    itemId = fixture.itemId,
                                    actorUserId = SECOND_ACTOR_ID,
                                    deleteSource = MenuOptionDeleteSource.TELEGRAM_BOT,
                                    createSource = MenuOptionCreateSource.TELEGRAM_BOT,
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
                val options = readOptions(dataSource, fixture.itemId)
                assertCreateAudits(
                    dataSource = dataSource,
                    fixture = fixture,
                    expected =
                        profileOptionIds(options, HookahFlavorProfileService.baseProfiles.drop(1)).map { optionId ->
                            ExpectedCreateAudit(
                                optionId = optionId,
                                actorUserId = FIRST_ACTOR_ID,
                                source = MenuOptionCreateSource.TELEGRAM_BOT,
                            )
                        },
                )
                assertCommittedCreateAuditCardinality(dataSource, fixture)
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
                                    deleteSource = MenuOptionDeleteSource.TELEGRAM_BOT,
                                    createSource = MenuOptionCreateSource.TELEGRAM_BOT,
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
                                    actorUserId = FIRST_ACTOR_ID,
                                    source = MenuOptionCreateSource.VENUE_MINI_APP,
                                )
                            }
                        },
                        waiterAction = {
                            runBlocking {
                                VenueMenuRepository(waiterDataSource).normalizeHookahFlavorProfiles(
                                    venueId = fixture.venueId,
                                    itemId = fixture.itemId,
                                    actorUserId = SECOND_ACTOR_ID,
                                    deleteSource = MenuOptionDeleteSource.TELEGRAM_BOT,
                                    createSource = MenuOptionCreateSource.TELEGRAM_BOT,
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
                        actorUserId = SECOND_ACTOR_ID,
                        source = MenuOptionCreateSource.TELEGRAM_BOT,
                    )
                }
                assertDeleteAudits(
                    dataSource = dataSource,
                    fixture = fixture,
                    expectedOptionIds = fixture.obsoleteOptionIds,
                    expectedActorUserId = SECOND_ACTOR_ID,
                )
                val normalizationCreatedIds =
                    profileOptionIds(
                        options = readOptions(dataSource, fixture.itemId),
                        profileNames =
                            HookahFlavorProfileService.baseProfiles.filterNot { profile ->
                                profile == HookahFlavorProfileService.baseProfiles.first() || profile == profileName
                            },
                    )
                assertCreateAudits(
                    dataSource = dataSource,
                    fixture = fixture,
                    expected =
                        listOf(
                            ExpectedCreateAudit(
                                optionId = createdOption.id,
                                actorUserId = FIRST_ACTOR_ID,
                                source = MenuOptionCreateSource.VENUE_MINI_APP,
                            ),
                        ) +
                            normalizationCreatedIds.map { optionId ->
                                ExpectedCreateAudit(
                                    optionId = optionId,
                                    actorUserId = SECOND_ACTOR_ID,
                                    source = MenuOptionCreateSource.TELEGRAM_BOT,
                                )
                            },
                )
                assertCommittedCreateAuditCardinality(dataSource, fixture)
            }
        }

    @Test
    fun `concurrent direct creates of same canonical profile have one audited winner`() =
        runBlocking {
            val database = PostgresTestEnv.createDatabase()
            PostgresTestEnv.createDataSource(database).use { dataSource ->
                val fixture = seedFixture(dataSource)
                val profileName = HookahFlavorProfileService.baseProfiles.last()
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
                                VenueMenuRepository(holderDataSource).createOption(
                                    venueId = fixture.venueId,
                                    itemId = fixture.itemId,
                                    name = profileName,
                                    priceDeltaMinor = 0,
                                    isAvailable = true,
                                    actorUserId = FIRST_ACTOR_ID,
                                    source = MenuOptionCreateSource.VENUE_MINI_APP,
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
                                        actorUserId = SECOND_ACTOR_ID,
                                        source = MenuOptionCreateSource.TELEGRAM_BOT,
                                    )
                                }
                            }
                        },
                    )

                val created = assertNotNull(winner)
                assertEquals(profileName, created.name)
                assertTrue(loser.exceptionOrNull() is InvalidInputException)
                assertEquals(
                    1,
                    readOptions(dataSource, fixture.itemId).count { option ->
                        HookahFlavorProfileService.normalizeFlavorNameKey(option.name) ==
                            HookahFlavorProfileService.normalizeFlavorNameKey(profileName)
                    },
                )
                assertCreateAudits(
                    dataSource = dataSource,
                    fixture = fixture,
                    expected =
                        listOf(
                            ExpectedCreateAudit(
                                optionId = created.id,
                                actorUserId = FIRST_ACTOR_ID,
                                source = MenuOptionCreateSource.VENUE_MINI_APP,
                            ),
                        ),
                )
                assertCommittedCreateAuditCardinality(dataSource, fixture)
                assertEquals(1, countAuditRows(dataSource))
            }
        }

    @Test
    fun `concurrent direct creates of different custom options both commit with matching audits`() =
        runBlocking {
            val database = PostgresTestEnv.createDatabase()
            PostgresTestEnv.createDataSource(database).use { dataSource ->
                val fixture = seedFixture(dataSource)
                val holderDataSource = HeldItemLockDataSource(dataSource)
                val waiterDataSource = TrackedItemLockDataSource(dataSource)

                val (first, second) =
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
                                    name = FIRST_CUSTOM_CREATE_NAME,
                                    priceDeltaMinor = 111,
                                    isAvailable = true,
                                    actorUserId = FIRST_ACTOR_ID,
                                    source = MenuOptionCreateSource.VENUE_MINI_APP,
                                )
                            }
                        },
                        waiterAction = {
                            runBlocking {
                                VenueMenuRepository(waiterDataSource).createOption(
                                    venueId = fixture.venueId,
                                    itemId = fixture.itemId,
                                    name = SECOND_CUSTOM_CREATE_NAME,
                                    priceDeltaMinor = 222,
                                    isAvailable = false,
                                    actorUserId = SECOND_ACTOR_ID,
                                    source = MenuOptionCreateSource.TELEGRAM_BOT,
                                )
                            }
                        },
                    )

                val firstCreated = assertNotNull(first)
                val secondCreated = assertNotNull(second)
                assertFalse(firstCreated.id == secondCreated.id)
                assertEquals(
                    setOf(FIRST_CUSTOM_CREATE_NAME, SECOND_CUSTOM_CREATE_NAME),
                    readOptions(dataSource, fixture.itemId)
                        .filter { option -> option.id in setOf(firstCreated.id, secondCreated.id) }
                        .map { option -> option.name }
                        .toSet(),
                )
                assertCreateAudits(
                    dataSource = dataSource,
                    fixture = fixture,
                    expected =
                        listOf(
                            ExpectedCreateAudit(
                                optionId = firstCreated.id,
                                actorUserId = FIRST_ACTOR_ID,
                                source = MenuOptionCreateSource.VENUE_MINI_APP,
                            ),
                            ExpectedCreateAudit(
                                optionId = secondCreated.id,
                                actorUserId = SECOND_ACTOR_ID,
                                source = MenuOptionCreateSource.TELEGRAM_BOT,
                            ),
                        ),
                )
                assertCommittedCreateAuditCardinality(dataSource, fixture)
                assertEquals(2, countAuditRows(dataSource))
            }
        }

    @Test
    fun `direct canonical create and base profile bulk serialize without duplicate profile`() =
        runBlocking {
            val database = PostgresTestEnv.createDatabase()
            PostgresTestEnv.createDataSource(database).use { dataSource ->
                val fixture = seedFixture(dataSource)
                val profileName = HookahFlavorProfileService.baseProfiles.last()
                val holderDataSource = HeldItemLockDataSource(dataSource)
                val waiterDataSource = TrackedItemLockDataSource(dataSource)

                val (direct, bulk) =
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
                                    actorUserId = FIRST_ACTOR_ID,
                                    source = MenuOptionCreateSource.VENUE_MINI_APP,
                                )
                            }
                        },
                        waiterAction = {
                            runBlocking {
                                VenueMenuRepository(waiterDataSource).applyMissingBaseProfiles(
                                    venueId = fixture.venueId,
                                    itemId = fixture.itemId,
                                    actorUserId = SECOND_ACTOR_ID,
                                    source = MenuOptionCreateSource.TELEGRAM_BOT,
                                )
                            }
                        },
                    )

                val directCreated = assertNotNull(direct)
                val bulkResult = assertNotNull(bulk)
                assertEquals(HookahFlavorProfileService.baseProfiles.size - 2, bulkResult.addedCount)
                assertEquals(2, bulkResult.existingCount)
                val options = readOptions(dataSource, fixture.itemId)
                assertCanonicalProfilesUnique(options)
                assertTrue(fixture.initialOptionIds.all { optionId -> options.any { it.id == optionId } })
                val bulkCreatedIds =
                    profileOptionIds(
                        options = options,
                        profileNames =
                            HookahFlavorProfileService.baseProfiles.drop(1).filterNot { profile ->
                                profile == profileName
                            },
                    )
                assertCreateAudits(
                    dataSource = dataSource,
                    fixture = fixture,
                    expected =
                        listOf(
                            ExpectedCreateAudit(
                                optionId = directCreated.id,
                                actorUserId = FIRST_ACTOR_ID,
                                source = MenuOptionCreateSource.VENUE_MINI_APP,
                            ),
                        ) +
                            bulkCreatedIds.map { optionId ->
                                ExpectedCreateAudit(
                                    optionId = optionId,
                                    actorUserId = SECOND_ACTOR_ID,
                                    source = MenuOptionCreateSource.TELEGRAM_BOT,
                                )
                            },
                )
                assertCommittedCreateAuditCardinality(dataSource, fixture)
                assertTrue(readDeleteAudits(dataSource).isEmpty())
            }
        }

    @Test
    fun `concurrent base profile bulk operations have one audited writer and one no-op`() =
        runBlocking {
            val database = PostgresTestEnv.createDatabase()
            PostgresTestEnv.createDataSource(database).use { dataSource ->
                val fixture = seedFixture(dataSource)
                val holderDataSource = HeldItemLockDataSource(dataSource)
                val waiterDataSource = TrackedItemLockDataSource(dataSource)

                val (winner, noOp) =
                    runWithHeldItemLock(
                        observerDataSource = dataSource,
                        holderDataSource = holderDataSource,
                        waiterDataSource = waiterDataSource,
                        beforeRelease = { assertInitialState(dataSource, fixture) },
                        holderAction = {
                            runBlocking {
                                VenueMenuRepository(holderDataSource).applyMissingBaseProfiles(
                                    venueId = fixture.venueId,
                                    itemId = fixture.itemId,
                                    actorUserId = FIRST_ACTOR_ID,
                                    source = MenuOptionCreateSource.VENUE_MINI_APP,
                                )
                            }
                        },
                        waiterAction = {
                            runBlocking {
                                VenueMenuRepository(waiterDataSource).applyMissingBaseProfiles(
                                    venueId = fixture.venueId,
                                    itemId = fixture.itemId,
                                    actorUserId = SECOND_ACTOR_ID,
                                    source = MenuOptionCreateSource.TELEGRAM_BOT,
                                )
                            }
                        },
                    )

                val winnerResult = assertNotNull(winner)
                val noOpResult = assertNotNull(noOp)
                assertEquals(HookahFlavorProfileService.baseProfiles.size - 1, winnerResult.addedCount)
                assertEquals(1, winnerResult.existingCount)
                assertEquals(0, noOpResult.addedCount)
                assertEquals(HookahFlavorProfileService.baseProfiles.size, noOpResult.existingCount)
                val options = readOptions(dataSource, fixture.itemId)
                assertCanonicalProfilesUnique(options)
                assertTrue(fixture.initialOptionIds.all { optionId -> options.any { it.id == optionId } })
                assertCreateAudits(
                    dataSource = dataSource,
                    fixture = fixture,
                    expected =
                        profileOptionIds(options, HookahFlavorProfileService.baseProfiles.drop(1)).map { optionId ->
                            ExpectedCreateAudit(
                                optionId = optionId,
                                actorUserId = FIRST_ACTOR_ID,
                                source = MenuOptionCreateSource.VENUE_MINI_APP,
                            )
                        },
                )
                assertCommittedCreateAuditCardinality(dataSource, fixture)
                assertTrue(readCreateAudits(dataSource).none { audit -> audit.actorUserId == SECOND_ACTOR_ID })
                assertTrue(readDeleteAudits(dataSource).isEmpty())
            }
        }

    @Test
    fun `base profile bulk and normalization serialize with no duplicate create audits`() =
        runBlocking {
            val database = PostgresTestEnv.createDatabase()
            PostgresTestEnv.createDataSource(database).use { dataSource ->
                val fixture = seedFixture(dataSource)
                val holderDataSource = HeldItemLockDataSource(dataSource)
                val waiterDataSource = TrackedItemLockDataSource(dataSource)

                val (bulk, normalization) =
                    runWithHeldItemLock(
                        observerDataSource = dataSource,
                        holderDataSource = holderDataSource,
                        waiterDataSource = waiterDataSource,
                        beforeRelease = { assertInitialState(dataSource, fixture) },
                        holderAction = {
                            runBlocking {
                                VenueMenuRepository(holderDataSource).applyMissingBaseProfiles(
                                    venueId = fixture.venueId,
                                    itemId = fixture.itemId,
                                    actorUserId = FIRST_ACTOR_ID,
                                    source = MenuOptionCreateSource.VENUE_MINI_APP,
                                )
                            }
                        },
                        waiterAction = {
                            runBlocking {
                                VenueMenuRepository(waiterDataSource).normalizeHookahFlavorProfiles(
                                    venueId = fixture.venueId,
                                    itemId = fixture.itemId,
                                    actorUserId = SECOND_ACTOR_ID,
                                    deleteSource = MenuOptionDeleteSource.TELEGRAM_BOT,
                                    createSource = MenuOptionCreateSource.TELEGRAM_BOT,
                                )
                            }
                        },
                    )

                assertEquals(HookahFlavorProfileService.baseProfiles.size - 1, assertNotNull(bulk).addedCount)
                assertEquals(
                    HookahFlavorProfileNormalizationResult(
                        removedCount = fixture.obsoleteOptionIds.size,
                        addedCount = 0,
                    ),
                    assertNotNull(normalization),
                )
                assertNormalizedState(
                    dataSource = dataSource,
                    fixture = fixture,
                    expectedPreservedIds = setOf(fixture.existingCanonicalOptionId, fixture.customOptionId),
                    expectedNonCanonicalNames = setOf(CUSTOM_OPTION_NAME),
                )
                val options = readOptions(dataSource, fixture.itemId)
                assertCreateAudits(
                    dataSource = dataSource,
                    fixture = fixture,
                    expected =
                        profileOptionIds(options, HookahFlavorProfileService.baseProfiles.drop(1)).map { optionId ->
                            ExpectedCreateAudit(
                                optionId = optionId,
                                actorUserId = FIRST_ACTOR_ID,
                                source = MenuOptionCreateSource.VENUE_MINI_APP,
                            )
                        },
                )
                assertDeleteAudits(
                    dataSource = dataSource,
                    fixture = fixture,
                    expectedOptionIds = fixture.obsoleteOptionIds,
                    expectedActorUserId = SECOND_ACTOR_ID,
                )
                assertCommittedCreateAuditCardinality(dataSource, fixture)
                assertTrue(readCreateAudits(dataSource).none { audit -> audit.actorUserId == SECOND_ACTOR_ID })
            }
        }

    @Test
    fun `direct delete and create of same canonical profile serialize without partial state`() =
        runBlocking {
            val database = PostgresTestEnv.createDatabase()
            PostgresTestEnv.createDataSource(database).use { dataSource ->
                val fixture = seedFixture(dataSource)
                val profileName = HookahFlavorProfileService.baseProfiles.first()
                val holderDataSource = HeldItemLockDataSource(dataSource)
                val waiterDataSource = TrackedItemLockDataSource(dataSource)

                val (deleted, created) =
                    runWithHeldItemLock(
                        observerDataSource = dataSource,
                        holderDataSource = holderDataSource,
                        waiterDataSource = waiterDataSource,
                        beforeRelease = { assertInitialState(dataSource, fixture) },
                        holderAction = {
                            runBlocking {
                                VenueMenuRepository(holderDataSource).deleteOption(
                                    venueId = fixture.venueId,
                                    optionId = fixture.existingCanonicalOptionId,
                                    actorUserId = FIRST_ACTOR_ID,
                                    source = MenuOptionDeleteSource.TELEGRAM_BOT,
                                )
                            }
                        },
                        waiterAction = {
                            runBlocking {
                                VenueMenuRepository(waiterDataSource).createOption(
                                    venueId = fixture.venueId,
                                    itemId = fixture.itemId,
                                    name = profileName,
                                    priceDeltaMinor = 0,
                                    isAvailable = true,
                                    actorUserId = SECOND_ACTOR_ID,
                                    source = MenuOptionCreateSource.VENUE_MINI_APP,
                                )
                            }
                        },
                    )

                assertTrue(deleted)
                val createdOption = assertNotNull(created)
                assertFalse(createdOption.id == fixture.existingCanonicalOptionId)
                val options = readOptions(dataSource, fixture.itemId)
                assertTrue(options.none { option -> option.id == fixture.existingCanonicalOptionId })
                assertEquals(
                    1,
                    options.count { option ->
                        HookahFlavorProfileService.normalizeFlavorNameKey(option.name) ==
                            HookahFlavorProfileService.normalizeFlavorNameKey(profileName)
                    },
                )
                assertDeleteAudits(
                    dataSource = dataSource,
                    fixture = fixture,
                    expectedOptionIds = setOf(fixture.existingCanonicalOptionId),
                    expectedActorUserId = FIRST_ACTOR_ID,
                )
                assertCreateAudits(
                    dataSource = dataSource,
                    fixture = fixture,
                    expected =
                        listOf(
                            ExpectedCreateAudit(
                                optionId = createdOption.id,
                                actorUserId = SECOND_ACTOR_ID,
                                source = MenuOptionCreateSource.VENUE_MINI_APP,
                            ),
                        ),
                )
                assertCommittedCreateAuditCardinality(dataSource, fixture)
                assertEquals(2, countAuditRows(dataSource))
            }
        }

    @Test
    fun `concurrent price changes audit truthful locked database transitions`() =
        runBlocking {
            val database = PostgresTestEnv.createDatabase()
            PostgresTestEnv.createDataSource(database).use { dataSource ->
                val fixture = seedFixture(dataSource)
                val holderDataSource = HeldItemLockDataSource(dataSource)
                val waiterDataSource = TrackedItemLockDataSource(dataSource)

                val (firstUpdate, secondUpdate) =
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
                                    name = null,
                                    priceDeltaMinor = 500,
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
                                    name = null,
                                    priceDeltaMinor = 700,
                                    isAvailable = null,
                                    actorUserId = SECOND_ACTOR_ID,
                                    source = MenuOptionRenameSource.VENUE_MINI_APP,
                                )
                            }
                        },
                    )

                assertEquals(500L, assertNotNull(firstUpdate).priceDeltaMinor)
                assertEquals(700L, assertNotNull(secondUpdate).priceDeltaMinor)
                assertEquals(
                    700L,
                    readOptions(dataSource, fixture.itemId).single { it.id == fixture.customOptionId }.priceDeltaMinor,
                )
                val audits = readPriceAudits(dataSource)
                assertEquals(2, audits.size)
                assertPriceAudit(audits[0], fixture, 300, 500, FIRST_ACTOR_ID)
                assertPriceAudit(audits[1], fixture, 500, 700, SECOND_ACTOR_ID)
                assertTrue(readRenameAudits(dataSource).isEmpty())
                assertTrue(readDeleteAudits(dataSource).isEmpty())
            }
        }

    @Test
    fun `concurrent same price has one audited winner and one database current no-op`() =
        runBlocking {
            val database = PostgresTestEnv.createDatabase()
            PostgresTestEnv.createDataSource(database).use { dataSource ->
                val fixture = seedFixture(dataSource)
                val holderDataSource = HeldItemLockDataSource(dataSource)
                val waiterDataSource = TrackedItemLockDataSource(dataSource)

                val (winner, noOp) =
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
                                    name = null,
                                    priceDeltaMinor = 500,
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
                                    name = null,
                                    priceDeltaMinor = 500,
                                    isAvailable = null,
                                    actorUserId = SECOND_ACTOR_ID,
                                    source = MenuOptionRenameSource.VENUE_MINI_APP,
                                )
                            }
                        },
                    )

                assertEquals(500L, assertNotNull(winner).priceDeltaMinor)
                assertEquals(500L, assertNotNull(noOp).priceDeltaMinor)
                val audits = readPriceAudits(dataSource)
                assertEquals(1, audits.size)
                assertPriceAudit(audits.single(), fixture, 300, 500, FIRST_ACTOR_ID)
                assertTrue(audits.none { it.actorUserId == SECOND_ACTOR_ID })
                assertTrue(readRenameAudits(dataSource).isEmpty())
                assertTrue(readDeleteAudits(dataSource).isEmpty())
            }
        }

    @Test
    fun `compound price update and normalization share item lock and preserve updated option`() =
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
                                    priceDeltaMinor = 650,
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
                                    deleteSource = MenuOptionDeleteSource.TELEGRAM_BOT,
                                    createSource = MenuOptionCreateSource.TELEGRAM_BOT,
                                )
                            }
                        },
                    )

                val updatedOption = assertNotNull(updated)
                val normalizationResult = assertNotNull(normalization)
                assertEquals(fixture.customOptionId, updatedOption.id)
                assertEquals(profileName, updatedOption.name)
                assertEquals(650L, updatedOption.priceDeltaMinor)
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
                assertEquals(650L, finalUpdatedOption.priceDeltaMinor)
                assertTrue(
                    readOptions(dataSource, fixture.itemId)
                        .filter { it.id !in setOf(fixture.customOptionId, fixture.existingCanonicalOptionId) }
                        .all { it.priceDeltaMinor == 0L },
                )
                assertRenameAudit(
                    dataSource = dataSource,
                    fixture = fixture,
                    expectedOldName = CUSTOM_OPTION_NAME,
                    expectedNewName = profileName,
                    expectedActorUserId = SECOND_ACTOR_ID,
                    expectedSource = MenuOptionRenameSource.VENUE_MINI_APP,
                )
                assertPriceAudit(
                    audit = readPriceAudits(dataSource).single(),
                    fixture = fixture,
                    expectedOldPriceDeltaMinor = 300,
                    expectedNewPriceDeltaMinor = 650,
                    expectedActorUserId = SECOND_ACTOR_ID,
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
    fun `compound price rename and rename serialize with independent audits`() =
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
                                    priceDeltaMinor = 500,
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

                val firstRenamedOption = assertNotNull(firstRename)
                val secondRenamedOption = assertNotNull(secondRename)
                assertEquals("Первое имя", firstRenamedOption.name)
                assertEquals("Второе имя", secondRenamedOption.name)
                assertEquals(500L, firstRenamedOption.priceDeltaMinor)
                assertEquals(500L, secondRenamedOption.priceDeltaMinor)
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
                assertPriceAudit(
                    audit = readPriceAudits(dataSource).single(),
                    fixture = fixture,
                    expectedOldPriceDeltaMinor = 300,
                    expectedNewPriceDeltaMinor = 500,
                    expectedActorUserId = FIRST_ACTOR_ID,
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
                                        actorUserId = SECOND_ACTOR_ID,
                                        source = MenuOptionCreateSource.TELEGRAM_BOT,
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
                assertTrue(readCreateAudits(dataSource).isEmpty())
                assertCommittedCreateAuditCardinality(dataSource, fixture)
                assertTrue(readDeleteAudits(dataSource).isEmpty())
            }
        }

    @Test
    fun `direct delete and compound price rename serialize with zero loser audits`() =
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
                                    priceDeltaMinor = 700,
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
                assertTrue(readPriceAudits(dataSource).isEmpty())
            }
        }

    @Test
    fun `direct availability writers serialize with one database current winner audit`() =
        runBlocking {
            val database = PostgresTestEnv.createDatabase()
            PostgresTestEnv.createDataSource(database).use { dataSource ->
                val fixture = seedFixture(dataSource)
                val holderDataSource = HeldItemLockDataSource(dataSource)
                val waiterDataSource = TrackedItemLockDataSource(dataSource)

                val (winner, noOp) =
                    runWithHeldItemLock(
                        observerDataSource = dataSource,
                        holderDataSource = holderDataSource,
                        waiterDataSource = waiterDataSource,
                        beforeRelease = { assertInitialState(dataSource, fixture) },
                        holderAction = {
                            runBlocking {
                                VenueMenuRepository(holderDataSource).setOptionAvailability(
                                    fixture.venueId,
                                    fixture.customOptionId,
                                    true,
                                    FIRST_ACTOR_ID,
                                    MenuOptionAvailabilitySource.VENUE_MINI_APP,
                                )
                            }
                        },
                        waiterAction = {
                            runBlocking {
                                VenueMenuRepository(waiterDataSource).setOptionAvailability(
                                    fixture.venueId,
                                    fixture.customOptionId,
                                    true,
                                    SECOND_ACTOR_ID,
                                    MenuOptionAvailabilitySource.TELEGRAM_BOT,
                                )
                            }
                        },
                    )

                assertTrue(assertNotNull(winner).isAvailable)
                assertTrue(assertNotNull(noOp).isAvailable)
                assertTrue(
                    readOptions(dataSource, fixture.itemId)
                        .single { it.id == fixture.customOptionId }
                        .isAvailable,
                )
                assertAvailabilityAudit(
                    audit = readAvailabilityAudits(dataSource).single(),
                    fixture = fixture,
                    oldIsAvailable = false,
                    newIsAvailable = true,
                    actorUserId = FIRST_ACTOR_ID,
                    source = MenuOptionAvailabilitySource.VENUE_MINI_APP,
                )
            }
        }

    @Test
    fun `direct availability and compound patch serialize truthful independent audits`() =
        runBlocking {
            val database = PostgresTestEnv.createDatabase()
            PostgresTestEnv.createDataSource(database).use { dataSource ->
                val fixture = seedFixture(dataSource)
                val holderDataSource = HeldItemLockDataSource(dataSource)
                val waiterDataSource = TrackedItemLockDataSource(dataSource)

                val (direct, compound) =
                    runWithHeldItemLock(
                        observerDataSource = dataSource,
                        holderDataSource = holderDataSource,
                        waiterDataSource = waiterDataSource,
                        beforeRelease = { assertInitialState(dataSource, fixture) },
                        holderAction = {
                            runBlocking {
                                VenueMenuRepository(holderDataSource).setOptionAvailability(
                                    fixture.venueId,
                                    fixture.customOptionId,
                                    true,
                                    FIRST_ACTOR_ID,
                                    MenuOptionAvailabilitySource.TELEGRAM_BOT,
                                )
                            }
                        },
                        waiterAction = {
                            runBlocking {
                                VenueMenuRepository(waiterDataSource).updateOption(
                                    venueId = fixture.venueId,
                                    optionId = fixture.customOptionId,
                                    name = "Compound availability winner",
                                    priceDeltaMinor = null,
                                    isAvailable = false,
                                    actorUserId = SECOND_ACTOR_ID,
                                    source = MenuOptionRenameSource.VENUE_MINI_APP,
                                )
                            }
                        },
                    )

                assertTrue(assertNotNull(direct).isAvailable)
                assertFalse(assertNotNull(compound).isAvailable)
                val final = readOptions(dataSource, fixture.itemId).single { it.id == fixture.customOptionId }
                assertEquals("Compound availability winner", final.name)
                assertFalse(final.isAvailable)
                val audits = readAvailabilityAudits(dataSource)
                assertEquals(2, audits.size)
                assertAvailabilityAudit(
                    audits[0],
                    fixture,
                    false,
                    true,
                    FIRST_ACTOR_ID,
                    MenuOptionAvailabilitySource.TELEGRAM_BOT,
                )
                assertAvailabilityAudit(
                    audits[1],
                    fixture,
                    true,
                    false,
                    SECOND_ACTOR_ID,
                    MenuOptionAvailabilitySource.VENUE_MINI_APP,
                )
                assertRenameAudit(
                    dataSource,
                    fixture,
                    CUSTOM_OPTION_NAME,
                    "Compound availability winner",
                    SECOND_ACTOR_ID,
                    MenuOptionRenameSource.VENUE_MINI_APP,
                )
                assertTrue(readPriceAudits(dataSource).isEmpty())
            }
        }

    @Test
    fun `direct availability commits before shift check stale conflict without per option batch audit`() =
        runBlocking {
            val database = PostgresTestEnv.createDatabase()
            PostgresTestEnv.createDataSource(database).use { dataSource ->
                val fixture = seedFixture(dataSource)
                val holderDataSource = HeldItemLockDataSource(dataSource)
                val waiterDataSource = TrackedItemLockDataSource(dataSource)

                val (direct, shiftAttempt) =
                    runWithHeldItemLock(
                        observerDataSource = dataSource,
                        holderDataSource = holderDataSource,
                        waiterDataSource = waiterDataSource,
                        beforeRelease = { assertInitialState(dataSource, fixture) },
                        holderAction = {
                            runBlocking {
                                VenueMenuRepository(holderDataSource).setOptionAvailability(
                                    fixture.venueId,
                                    fixture.customOptionId,
                                    true,
                                    FIRST_ACTOR_ID,
                                    MenuOptionAvailabilitySource.VENUE_MINI_APP,
                                )
                            }
                        },
                        waiterAction = {
                            runCatching {
                                runBlocking {
                                    VenueMenuRepository(waiterDataSource).completeShiftCheck(
                                        venueId = fixture.venueId,
                                        actorUserId = SECOND_ACTOR_ID,
                                        itemChanges = emptyList(),
                                        optionChanges =
                                            listOf(
                                                MenuShiftCheckOptionChange(
                                                    optionId = fixture.customOptionId,
                                                    itemId = fixture.itemId,
                                                    expectedIsAvailable = false,
                                                    desiredIsAvailable = true,
                                                ),
                                            ),
                                        auditLogRepository = AuditLogRepository(waiterDataSource, Json),
                                    )
                                }
                            }
                        },
                    )

                assertTrue(assertNotNull(direct).isAvailable)
                assertTrue(shiftAttempt.exceptionOrNull() is MenuShiftCheckStaleException)
                assertAvailabilityAudit(
                    readAvailabilityAudits(dataSource).single(),
                    fixture,
                    false,
                    true,
                    FIRST_ACTOR_ID,
                    MenuOptionAvailabilitySource.VENUE_MINI_APP,
                )
                assertTrue(readShiftCheckAudits(dataSource).isEmpty())
            }
        }

    @Test
    fun `shift check commits before direct availability truthful reread without per option batch audit`() =
        runBlocking {
            val database = PostgresTestEnv.createDatabase()
            PostgresTestEnv.createDataSource(database).use { dataSource ->
                val fixture = seedFixture(dataSource)
                val holderDataSource = HeldItemLockDataSource(dataSource)
                val waiterDataSource = TrackedItemLockDataSource(dataSource)

                val (shift, direct) =
                    runWithHeldItemLock(
                        observerDataSource = dataSource,
                        holderDataSource = holderDataSource,
                        waiterDataSource = waiterDataSource,
                        beforeRelease = { assertInitialState(dataSource, fixture) },
                        holderAction = {
                            runBlocking {
                                VenueMenuRepository(holderDataSource).completeShiftCheck(
                                    venueId = fixture.venueId,
                                    actorUserId = FIRST_ACTOR_ID,
                                    itemChanges = emptyList(),
                                    optionChanges =
                                        listOf(
                                            MenuShiftCheckOptionChange(
                                                optionId = fixture.customOptionId,
                                                itemId = fixture.itemId,
                                                expectedIsAvailable = false,
                                                desiredIsAvailable = true,
                                            ),
                                        ),
                                    auditLogRepository = AuditLogRepository(holderDataSource, Json),
                                )
                            }
                        },
                        waiterAction = {
                            runBlocking {
                                VenueMenuRepository(waiterDataSource).setOptionAvailability(
                                    fixture.venueId,
                                    fixture.customOptionId,
                                    false,
                                    SECOND_ACTOR_ID,
                                    MenuOptionAvailabilitySource.TELEGRAM_BOT,
                                )
                            }
                        },
                    )

                assertEquals(1, shift.changedOptionCount)
                assertFalse(assertNotNull(direct).isAvailable)
                assertFalse(
                    readOptions(dataSource, fixture.itemId)
                        .single { it.id == fixture.customOptionId }
                        .isAvailable,
                )
                assertAvailabilityAudit(
                    readAvailabilityAudits(dataSource).single(),
                    fixture,
                    true,
                    false,
                    SECOND_ACTOR_ID,
                    MenuOptionAvailabilitySource.TELEGRAM_BOT,
                )
                assertEquals(1, readShiftCheckAudits(dataSource).size)
            }
        }

    @Test
    fun `direct availability and direct delete share item lock without partial state`() =
        runBlocking {
            val database = PostgresTestEnv.createDatabase()
            PostgresTestEnv.createDataSource(database).use { dataSource ->
                val fixture = seedFixture(dataSource)
                val holderDataSource = HeldItemLockDataSource(dataSource)
                val waiterDataSource = TrackedItemLockDataSource(dataSource)

                val (direct, deleted) =
                    runWithHeldItemLock(
                        observerDataSource = dataSource,
                        holderDataSource = holderDataSource,
                        waiterDataSource = waiterDataSource,
                        beforeRelease = { assertInitialState(dataSource, fixture) },
                        holderAction = {
                            runBlocking {
                                VenueMenuRepository(holderDataSource).setOptionAvailability(
                                    fixture.venueId,
                                    fixture.customOptionId,
                                    true,
                                    FIRST_ACTOR_ID,
                                    MenuOptionAvailabilitySource.VENUE_MINI_APP,
                                )
                            }
                        },
                        waiterAction = {
                            runBlocking {
                                VenueMenuRepository(waiterDataSource).deleteOption(
                                    fixture.venueId,
                                    fixture.customOptionId,
                                    SECOND_ACTOR_ID,
                                    MenuOptionDeleteSource.TELEGRAM_BOT,
                                )
                            }
                        },
                    )

                assertTrue(assertNotNull(direct).isAvailable)
                assertTrue(deleted)
                assertTrue(readOptions(dataSource, fixture.itemId).none { it.id == fixture.customOptionId })
                assertAvailabilityAudit(
                    readAvailabilityAudits(dataSource).single(),
                    fixture,
                    false,
                    true,
                    FIRST_ACTOR_ID,
                    MenuOptionAvailabilitySource.VENUE_MINI_APP,
                )
                assertDeleteAudits(dataSource, fixture, setOf(fixture.customOptionId), SECOND_ACTOR_ID)
            }
        }

    @Test
    fun `direct availability and normalization share item lock and preserve current option state`() =
        runBlocking {
            val database = PostgresTestEnv.createDatabase()
            PostgresTestEnv.createDataSource(database).use { dataSource ->
                val fixture = seedFixture(dataSource)
                val holderDataSource = HeldItemLockDataSource(dataSource)
                val waiterDataSource = TrackedItemLockDataSource(dataSource)

                val (direct, normalization) =
                    runWithHeldItemLock(
                        observerDataSource = dataSource,
                        holderDataSource = holderDataSource,
                        waiterDataSource = waiterDataSource,
                        beforeRelease = { assertInitialState(dataSource, fixture) },
                        holderAction = {
                            runBlocking {
                                VenueMenuRepository(holderDataSource).setOptionAvailability(
                                    fixture.venueId,
                                    fixture.customOptionId,
                                    true,
                                    FIRST_ACTOR_ID,
                                    MenuOptionAvailabilitySource.TELEGRAM_BOT,
                                )
                            }
                        },
                        waiterAction = {
                            runBlocking {
                                VenueMenuRepository(waiterDataSource).normalizeHookahFlavorProfiles(
                                    fixture.venueId,
                                    fixture.itemId,
                                    SECOND_ACTOR_ID,
                                    MenuOptionDeleteSource.TELEGRAM_BOT,
                                    MenuOptionCreateSource.TELEGRAM_BOT,
                                )
                            }
                        },
                    )

                assertTrue(assertNotNull(direct).isAvailable)
                assertNotNull(normalization)
                assertTrue(
                    readOptions(dataSource, fixture.itemId)
                        .single { it.id == fixture.customOptionId }
                        .isAvailable,
                )
                assertAvailabilityAudit(
                    readAvailabilityAudits(dataSource).single(),
                    fixture,
                    false,
                    true,
                    FIRST_ACTOR_ID,
                    MenuOptionAvailabilitySource.TELEGRAM_BOT,
                )
                assertDeleteAudits(dataSource, fixture, fixture.obsoleteOptionIds, SECOND_ACTOR_ID)
            }
        }

    @Test
    fun `item direct availability writers serialize with one database current audit`() =
        runBlocking {
            val database = PostgresTestEnv.createDatabase()
            PostgresTestEnv.createDataSource(database).use { dataSource ->
                val fixture = seedFixture(dataSource)
                val optionsBefore = readOptions(dataSource, fixture.itemId)
                val holderDataSource = HeldItemLockDataSource(dataSource)
                val waiterDataSource = TrackedItemLockDataSource(dataSource)

                val (winner, noOp) =
                    runWithHeldItemLock(
                        observerDataSource = dataSource,
                        holderDataSource = holderDataSource,
                        waiterDataSource = waiterDataSource,
                        beforeRelease = { assertInitialItemState(dataSource, fixture) },
                        holderAction = {
                            runBlocking {
                                VenueMenuRepository(holderDataSource).setItemAvailability(
                                    venueId = fixture.venueId,
                                    itemId = fixture.itemId,
                                    isAvailable = false,
                                    actorUserId = FIRST_ACTOR_ID,
                                    source = MenuItemAvailabilitySource.VENUE_MINI_APP,
                                )
                            }
                        },
                        waiterAction = {
                            runBlocking {
                                VenueMenuRepository(waiterDataSource).setItemAvailability(
                                    venueId = fixture.venueId,
                                    itemId = fixture.itemId,
                                    isAvailable = false,
                                    actorUserId = SECOND_ACTOR_ID,
                                    source = MenuItemAvailabilitySource.TELEGRAM_BOT,
                                )
                            }
                        },
                    )

                assertFalse(assertNotNull(winner).isAvailable)
                assertFalse(assertNotNull(noOp).isAvailable)
                assertFalse(assertNotNull(readItem(dataSource, fixture.itemId)).isAvailable)
                assertEquals(optionsBefore, readOptions(dataSource, fixture.itemId))
                assertItemAvailabilityAudit(
                    audit = readItemAvailabilityAudits(dataSource).single(),
                    fixture = fixture,
                    oldIsAvailable = true,
                    newIsAvailable = false,
                    actorUserId = FIRST_ACTOR_ID,
                    source = MenuItemAvailabilitySource.VENUE_MINI_APP,
                )
                assertTrue(readShiftCheckAudits(dataSource).isEmpty())
                assertEquals(1, countAuditRows(dataSource))
            }
        }

    @Test
    fun `item direct availability and compound patch serialize truthful audits`() =
        runBlocking {
            val database = PostgresTestEnv.createDatabase()
            PostgresTestEnv.createDataSource(database).use { dataSource ->
                val fixture = seedFixture(dataSource)
                val optionsBefore = readOptions(dataSource, fixture.itemId)
                val holderDataSource = HeldItemLockDataSource(dataSource)
                val waiterDataSource = TrackedItemLockDataSource(dataSource)

                val (direct, compound) =
                    runWithHeldItemLock(
                        observerDataSource = dataSource,
                        holderDataSource = holderDataSource,
                        waiterDataSource = waiterDataSource,
                        beforeRelease = { assertInitialItemState(dataSource, fixture) },
                        holderAction = {
                            runBlocking {
                                VenueMenuRepository(holderDataSource).setItemAvailability(
                                    venueId = fixture.venueId,
                                    itemId = fixture.itemId,
                                    isAvailable = false,
                                    actorUserId = FIRST_ACTOR_ID,
                                    source = MenuItemAvailabilitySource.TELEGRAM_BOT,
                                )
                            }
                        },
                        waiterAction = {
                            runBlocking {
                                VenueMenuRepository(waiterDataSource).updateItem(
                                    venueId = fixture.venueId,
                                    itemId = fixture.itemId,
                                    categoryId = null,
                                    name = COMPOUND_ITEM_NAME,
                                    priceMinor = null,
                                    currency = null,
                                    isAvailable = true,
                                    actorUserId = SECOND_ACTOR_ID,
                                    source = MenuItemAvailabilitySource.VENUE_MINI_APP,
                                )
                            }
                        },
                    )

                assertFalse(assertNotNull(direct).isAvailable)
                val compoundResult = assertNotNull(compound)
                assertTrue(compoundResult.isAvailable)
                assertEquals(COMPOUND_ITEM_NAME, compoundResult.name)
                val finalItem = assertNotNull(readItem(dataSource, fixture.itemId))
                assertTrue(finalItem.isAvailable)
                assertEquals(COMPOUND_ITEM_NAME, finalItem.name)
                assertEquals(optionsBefore, readOptions(dataSource, fixture.itemId))
                val audits = readItemAvailabilityAudits(dataSource)
                assertEquals(2, audits.size)
                assertItemAvailabilityAudit(
                    audit = audits[0],
                    fixture = fixture,
                    oldIsAvailable = true,
                    newIsAvailable = false,
                    actorUserId = FIRST_ACTOR_ID,
                    source = MenuItemAvailabilitySource.TELEGRAM_BOT,
                )
                assertItemAvailabilityAudit(
                    audit = audits[1],
                    fixture = fixture,
                    oldIsAvailable = false,
                    newIsAvailable = true,
                    actorUserId = SECOND_ACTOR_ID,
                    source = MenuItemAvailabilitySource.VENUE_MINI_APP,
                )
                val renameAudit = readAudits(dataSource, MENU_ITEM_RENAMED_AUDIT_ACTION).single()
                assertEquals(SECOND_ACTOR_ID, renameAudit.actorUserId)
                assertEquals("menu_item", renameAudit.entityType)
                assertEquals(fixture.itemId, renameAudit.entityId)
                assertEquals(
                    setOf("venueId", "itemId", "source"),
                    renameAudit.payload.keys,
                )
                assertTrue(readShiftCheckAudits(dataSource).isEmpty())
                assertEquals(3, countAuditRows(dataSource))
            }
        }

    @Test
    fun `item direct availability commits before shift check stale without batch item audit`() =
        runBlocking {
            val database = PostgresTestEnv.createDatabase()
            PostgresTestEnv.createDataSource(database).use { dataSource ->
                val fixture = seedFixture(dataSource)
                val optionsBefore = readOptions(dataSource, fixture.itemId)
                val holderDataSource = HeldItemLockDataSource(dataSource)
                val waiterDataSource = TrackedItemLockDataSource(dataSource)

                val (direct, shiftAttempt) =
                    runWithHeldItemLock(
                        observerDataSource = dataSource,
                        holderDataSource = holderDataSource,
                        waiterDataSource = waiterDataSource,
                        beforeRelease = { assertInitialItemState(dataSource, fixture) },
                        holderAction = {
                            runBlocking {
                                VenueMenuRepository(holderDataSource).setItemAvailability(
                                    venueId = fixture.venueId,
                                    itemId = fixture.itemId,
                                    isAvailable = false,
                                    actorUserId = FIRST_ACTOR_ID,
                                    source = MenuItemAvailabilitySource.VENUE_MINI_APP,
                                )
                            }
                        },
                        waiterAction = {
                            runCatching {
                                runBlocking {
                                    VenueMenuRepository(waiterDataSource).completeShiftCheck(
                                        venueId = fixture.venueId,
                                        actorUserId = SECOND_ACTOR_ID,
                                        itemChanges =
                                            listOf(
                                                MenuShiftCheckItemChange(
                                                    itemId = fixture.itemId,
                                                    expectedIsAvailable = true,
                                                    desiredIsAvailable = false,
                                                ),
                                            ),
                                        optionChanges = emptyList(),
                                        auditLogRepository = AuditLogRepository(waiterDataSource, Json),
                                    )
                                }
                            }
                        },
                    )

                assertFalse(assertNotNull(direct).isAvailable)
                assertTrue(shiftAttempt.exceptionOrNull() is MenuShiftCheckStaleException)
                assertFalse(assertNotNull(readItem(dataSource, fixture.itemId)).isAvailable)
                assertEquals(optionsBefore, readOptions(dataSource, fixture.itemId))
                assertItemAvailabilityAudit(
                    audit = readItemAvailabilityAudits(dataSource).single(),
                    fixture = fixture,
                    oldIsAvailable = true,
                    newIsAvailable = false,
                    actorUserId = FIRST_ACTOR_ID,
                    source = MenuItemAvailabilitySource.VENUE_MINI_APP,
                )
                assertTrue(readShiftCheckAudits(dataSource).isEmpty())
                assertEquals(1, countAuditRows(dataSource))
            }
        }

    @Test
    fun `item shift check commits before direct availability with only direct item audit`() =
        runBlocking {
            val database = PostgresTestEnv.createDatabase()
            PostgresTestEnv.createDataSource(database).use { dataSource ->
                val fixture = seedFixture(dataSource)
                val optionsBefore = readOptions(dataSource, fixture.itemId)
                val holderDataSource = HeldItemLockDataSource(dataSource)
                val waiterDataSource = TrackedItemLockDataSource(dataSource)

                val (shift, direct) =
                    runWithHeldItemLock(
                        observerDataSource = dataSource,
                        holderDataSource = holderDataSource,
                        waiterDataSource = waiterDataSource,
                        beforeRelease = { assertInitialItemState(dataSource, fixture) },
                        holderAction = {
                            runBlocking {
                                VenueMenuRepository(holderDataSource).completeShiftCheck(
                                    venueId = fixture.venueId,
                                    actorUserId = FIRST_ACTOR_ID,
                                    itemChanges =
                                        listOf(
                                            MenuShiftCheckItemChange(
                                                itemId = fixture.itemId,
                                                expectedIsAvailable = true,
                                                desiredIsAvailable = false,
                                            ),
                                        ),
                                    optionChanges = emptyList(),
                                    auditLogRepository = AuditLogRepository(holderDataSource, Json),
                                )
                            }
                        },
                        waiterAction = {
                            runBlocking {
                                VenueMenuRepository(waiterDataSource).setItemAvailability(
                                    venueId = fixture.venueId,
                                    itemId = fixture.itemId,
                                    isAvailable = true,
                                    actorUserId = SECOND_ACTOR_ID,
                                    source = MenuItemAvailabilitySource.TELEGRAM_BOT,
                                )
                            }
                        },
                    )

                assertEquals(1, shift.changedItemCount)
                assertEquals(0, shift.changedOptionCount)
                assertTrue(assertNotNull(direct).isAvailable)
                assertTrue(assertNotNull(readItem(dataSource, fixture.itemId)).isAvailable)
                assertEquals(optionsBefore, readOptions(dataSource, fixture.itemId))
                assertItemAvailabilityAudit(
                    audit = readItemAvailabilityAudits(dataSource).single(),
                    fixture = fixture,
                    oldIsAvailable = false,
                    newIsAvailable = true,
                    actorUserId = SECOND_ACTOR_ID,
                    source = MenuItemAvailabilitySource.TELEGRAM_BOT,
                )
                assertEquals(1, readShiftCheckAudits(dataSource).size)
                assertEquals(2, countAuditRows(dataSource))
            }
        }

    @Test
    fun `item delete commits before direct availability without partial state`() =
        runBlocking {
            val database = PostgresTestEnv.createDatabase()
            PostgresTestEnv.createDataSource(database).use { dataSource ->
                val fixture = seedFixture(dataSource)
                val holderDataSource = HeldItemDeleteLockDataSource(dataSource)
                val waiterDataSource = TrackedItemLockDataSource(dataSource)

                val (deleted, direct) =
                    runWithHeldItemDeleteLock(
                        observerDataSource = dataSource,
                        holderDataSource = holderDataSource,
                        waiterDataSource = waiterDataSource,
                        beforeRelease = { assertInitialItemState(dataSource, fixture) },
                        holderAction = {
                            runBlocking {
                                VenueMenuRepository(holderDataSource).deleteItem(
                                    venueId = fixture.venueId,
                                    itemId = fixture.itemId,
                                    actorUserId = FIRST_ACTOR_ID,
                                    source = MenuItemDeleteSource.VENUE_MINI_APP,
                                )
                            }
                        },
                        waiterAction = {
                            runBlocking {
                                VenueMenuRepository(waiterDataSource).setItemAvailability(
                                    venueId = fixture.venueId,
                                    itemId = fixture.itemId,
                                    isAvailable = false,
                                    actorUserId = SECOND_ACTOR_ID,
                                    source = MenuItemAvailabilitySource.TELEGRAM_BOT,
                                )
                            }
                        },
                    )

                assertTrue(deleted)
                assertNull(direct)
                assertNull(readItem(dataSource, fixture.itemId))
                assertTrue(readOptions(dataSource, fixture.itemId).isEmpty())
                assertTrue(readItemAvailabilityAudits(dataSource).isEmpty())
                val deleteAudit = readItemDeleteAudits(dataSource).single()
                assertEquals(FIRST_ACTOR_ID, deleteAudit.actorUserId)
                assertEquals("menu_item", deleteAudit.entityType)
                assertEquals(fixture.itemId, deleteAudit.entityId)
                assertEquals(1, countAuditRows(dataSource))
            }
        }

    @Test
    fun `category create and default seed serialize on venue order scope with truthful audits`() =
        runBlocking {
            val database = PostgresTestEnv.createDatabase()
            PostgresTestEnv.createDataSource(database).use { dataSource ->
                val fixture = seedFixture(dataSource)
                val holderDataSource =
                    HeldCategoryLockDataSource(dataSource) { sql -> sql.isMenuCategoryOrderScopeLock() }
                val waiterDataSource =
                    TrackedCategoryLockDataSource(dataSource) { sql -> sql.isMenuCategoryOrderScopeLock() }

                val (created, seeded) =
                    runWithHeldCategoryLock(
                        observerDataSource = dataSource,
                        holderDataSource = holderDataSource,
                        waiterDataSource = waiterDataSource,
                        beforeRelease = {
                            assertEquals(
                                listOf("Кальянное меню"),
                                readMenuCategories(dataSource, fixture.venueId).map { it.name },
                            )
                            assertTrue(readAudits(dataSource, MENU_CATEGORY_CREATED_AUDIT_ACTION).isEmpty())
                        },
                        holderAction = {
                            runBlocking {
                                VenueMenuRepository(holderDataSource).createCategory(
                                    venueId = fixture.venueId,
                                    name = "Напитки",
                                    actorUserId = FIRST_ACTOR_ID,
                                    source = MenuItemAvailabilitySource.VENUE_MINI_APP,
                                )
                            }
                        },
                        waiterAction = {
                            runBlocking {
                                VenueMenuRepository(waiterDataSource).createMissingCategories(
                                    venueId = fixture.venueId,
                                    seeds =
                                        listOf(
                                            MenuCategorySeed("Кальянное меню"),
                                            MenuCategorySeed("Напитки"),
                                            MenuCategorySeed("Кухня"),
                                        ),
                                    actorUserId = SECOND_ACTOR_ID,
                                    source = MenuItemAvailabilitySource.TELEGRAM_BOT,
                                )
                            }
                        },
                    )

                val kitchen = seeded.single { it.name == "Кухня" }
                val categories = readMenuCategories(dataSource, fixture.venueId)
                assertEquals(listOf("Кальянное меню", "Напитки", "Кухня"), categories.map { it.name })
                assertEquals(listOf(0, 1, 2), categories.map { it.sortOrder })
                assertEquals(created.id, categories[1].id)
                assertEquals(kitchen.id, categories[2].id)
                val audits = readAudits(dataSource, MENU_CATEGORY_CREATED_AUDIT_ACTION)
                assertEquals(2, audits.size)
                val directAudit = audits.single { it.entityId == created.id }
                assertEquals(FIRST_ACTOR_ID, directAudit.actorUserId)
                assertEquals("VENUE_MINI_APP", directAudit.payload.getValue("source").jsonPrimitive.content)
                val seedAudit = audits.single { it.entityId == kitchen.id }
                assertEquals(SECOND_ACTOR_ID, seedAudit.actorUserId)
                assertEquals("TELEGRAM_BOT", seedAudit.payload.getValue("source").jsonPrimitive.content)
                assertEquals(2, countAuditRows(dataSource))
            }
        }

    @Test
    fun `category compound update and category reorder serialize with committed hashes`() =
        runBlocking {
            val database = PostgresTestEnv.createDatabase()
            PostgresTestEnv.createDataSource(database).use { dataSource ->
                val fixture = seedFixture(dataSource)
                val secondCategoryId = insertCategory(dataSource, fixture.venueId, "Напитки", 1)
                val oldOrder = listOf(fixture.categoryId, secondCategoryId)
                val newOrder = oldOrder.reversed()
                val holderDataSource = HeldCategoryLockDataSource(dataSource)
                val waiterDataSource = TrackedCategoryLockDataSource(dataSource)

                val (updated, reordered) =
                    runWithHeldCategoryLock(
                        observerDataSource = dataSource,
                        holderDataSource = holderDataSource,
                        waiterDataSource = waiterDataSource,
                        beforeRelease = {
                            assertEquals(oldOrder, readMenuCategories(dataSource, fixture.venueId).map { it.id })
                            assertEquals(0, countAuditRows(dataSource))
                        },
                        holderAction = {
                            runBlocking {
                                VenueMenuRepository(holderDataSource).updateCategory(
                                    venueId = fixture.venueId,
                                    categoryId = fixture.categoryId,
                                    name = "Обновлённый раздел",
                                    categoryType = MenuSemanticType.DRINK,
                                    actorUserId = FIRST_ACTOR_ID,
                                    source = MenuItemAvailabilitySource.VENUE_MINI_APP,
                                )
                            }
                        },
                        waiterAction = {
                            runBlocking {
                                VenueMenuRepository(waiterDataSource).reorderCategories(
                                    venueId = fixture.venueId,
                                    categoryIds = newOrder,
                                    actorUserId = SECOND_ACTOR_ID,
                                    source = MenuItemAvailabilitySource.VENUE_MINI_APP,
                                )
                            }
                        },
                    )

                assertEquals("Обновлённый раздел", assertNotNull(updated).name)
                assertEquals(MenuSemanticType.DRINK, updated.categoryType)
                assertTrue(reordered)
                val categories = readMenuCategories(dataSource, fixture.venueId)
                assertEquals(newOrder, categories.map { it.id })
                assertEquals("Обновлённый раздел", categories.single { it.id == fixture.categoryId }.name)
                assertEquals("DRINK", categories.single { it.id == fixture.categoryId }.categoryType)
                assertEquals(1, readAudits(dataSource, MENU_CATEGORY_RENAMED_AUDIT_ACTION).size)
                val typeAudit = readAudits(dataSource, MENU_CATEGORY_TYPE_CHANGED_AUDIT_ACTION).single()
                assertEquals("HOOKAH", typeAudit.payload.getValue("oldCategoryType").jsonPrimitive.content)
                assertEquals("DRINK", typeAudit.payload.getValue("newCategoryType").jsonPrimitive.content)
                val reorderAudit = readAudits(dataSource, MENU_CATEGORIES_REORDERED_AUDIT_ACTION).single()
                assertEquals(SECOND_ACTOR_ID, reorderAudit.actorUserId)
                assertEquals("VENUE_MINI_APP", reorderAudit.payload.getValue("source").jsonPrimitive.content)
                assertEquals(
                    menuOrderSha256(oldOrder),
                    reorderAudit.payload.getValue("oldOrderSha256").jsonPrimitive.content,
                )
                assertEquals(
                    menuOrderSha256(newOrder),
                    reorderAudit.payload.getValue("newOrderSha256").jsonPrimitive.content,
                )
                assertEquals(3, countAuditRows(dataSource))
            }
        }

    @Test
    fun `category reorder and item create serialize with authoritative category and item state`() =
        runBlocking {
            val database = PostgresTestEnv.createDatabase()
            PostgresTestEnv.createDataSource(database).use { dataSource ->
                val fixture = seedFixture(dataSource)
                val secondCategoryId = insertCategory(dataSource, fixture.venueId, "Кухня", 1)
                val oldOrder = listOf(fixture.categoryId, secondCategoryId)
                val newOrder = oldOrder.reversed()
                val initialItems = readMenuItems(dataSource, fixture.venueId, fixture.categoryId)
                val holderDataSource = HeldCategoryLockDataSource(dataSource)
                val waiterDataSource = TrackedCategoryLockDataSource(dataSource)

                val (reordered, created) =
                    runWithHeldCategoryLock(
                        observerDataSource = dataSource,
                        holderDataSource = holderDataSource,
                        waiterDataSource = waiterDataSource,
                        beforeRelease = {
                            assertEquals(oldOrder, readMenuCategories(dataSource, fixture.venueId).map { it.id })
                            assertEquals(initialItems, readMenuItems(dataSource, fixture.venueId, fixture.categoryId))
                            assertEquals(0, countAuditRows(dataSource))
                        },
                        holderAction = {
                            runBlocking {
                                VenueMenuRepository(holderDataSource).reorderCategories(
                                    venueId = fixture.venueId,
                                    categoryIds = newOrder,
                                    actorUserId = FIRST_ACTOR_ID,
                                    source = MenuItemAvailabilitySource.VENUE_MINI_APP,
                                )
                            }
                        },
                        waiterAction = {
                            runBlocking {
                                VenueMenuRepository(waiterDataSource).createItem(
                                    venueId = fixture.venueId,
                                    categoryId = fixture.categoryId,
                                    name = SECOND_ITEM_CREATE_NAME,
                                    priceMinor = 130_000,
                                    currency = "RUB",
                                    isAvailable = true,
                                    actorUserId = SECOND_ACTOR_ID,
                                    source = MenuItemCreateSource.TELEGRAM_BOT,
                                    itemType = MenuSemanticType.HOOKAH,
                                )
                            }
                        },
                    )

                assertTrue(reordered)
                val createdItem = assertNotNull(created)
                assertEquals(newOrder, readMenuCategories(dataSource, fixture.venueId).map { it.id })
                assertEquals(initialItems.maxOf { it.sortOrder } + 1, createdItem.sortOrder)
                assertEquals(
                    initialItems.map { it.id } + createdItem.id,
                    readItemOrder(dataSource, fixture.venueId, fixture.categoryId),
                )
                val reorderAudit = readAudits(dataSource, MENU_CATEGORIES_REORDERED_AUDIT_ACTION).single()
                assertEquals(
                    menuOrderSha256(oldOrder),
                    reorderAudit.payload.getValue("oldOrderSha256").jsonPrimitive.content,
                )
                assertEquals(
                    menuOrderSha256(newOrder),
                    reorderAudit.payload.getValue("newOrderSha256").jsonPrimitive.content,
                )
                val createAudit = readMenuItemCreateAudits(dataSource).single()
                assertEquals(createdItem.id, createAudit.entityId)
                assertEquals(SECOND_ACTOR_ID, createAudit.actorUserId)
                assertEquals("TELEGRAM_BOT", createAudit.payload.getValue("source").jsonPrimitive.content)
                assertEquals(2, countAuditRows(dataSource))
            }
        }

    @Test
    fun `compound item update and telegram item type serialize truthful transitions`() =
        runBlocking {
            val database = PostgresTestEnv.createDatabase()
            PostgresTestEnv.createDataSource(database).use { dataSource ->
                val fixture = seedFixture(dataSource)
                val original = assertNotNull(readFullItem(dataSource, fixture.itemId))
                val holderDataSource = HeldCategoryLockDataSource(dataSource)
                val waiterDataSource = TrackedCategoryLockDataSource(dataSource)

                val (compound, telegramType) =
                    runWithHeldCategoryLock(
                        observerDataSource = dataSource,
                        holderDataSource = holderDataSource,
                        waiterDataSource = waiterDataSource,
                        beforeRelease = {
                            assertEquals(original, readFullItem(dataSource, fixture.itemId))
                            assertEquals(0, countAuditRows(dataSource))
                        },
                        holderAction = {
                            runBlocking {
                                VenueMenuRepository(holderDataSource).updateItem(
                                    venueId = fixture.venueId,
                                    itemId = fixture.itemId,
                                    categoryId = null,
                                    name = COMPOUND_ITEM_NAME,
                                    priceMinor = 120_000,
                                    currency = "RUB",
                                    isAvailable = false,
                                    actorUserId = FIRST_ACTOR_ID,
                                    source = MenuItemAvailabilitySource.VENUE_MINI_APP,
                                    itemType = MenuSemanticType.DRINK,
                                    itemTypeSpecified = true,
                                )
                            }
                        },
                        waiterAction = {
                            runBlocking {
                                VenueMenuRepository(waiterDataSource).updateItemType(
                                    venueId = fixture.venueId,
                                    itemId = fixture.itemId,
                                    itemType = MenuSemanticType.TEA,
                                    actorUserId = SECOND_ACTOR_ID,
                                    source = MenuItemAvailabilitySource.TELEGRAM_BOT,
                                )
                            }
                        },
                    )

                val compoundResult = assertNotNull(compound)
                assertEquals(MenuSemanticType.DRINK, compoundResult.itemType)
                val telegramResult = assertNotNull(telegramType)
                assertEquals(MenuSemanticType.TEA, telegramResult.itemType)
                val committed = assertNotNull(readFullItem(dataSource, fixture.itemId))
                assertEquals(COMPOUND_ITEM_NAME, committed.name)
                assertEquals(120_000L, committed.priceMinor)
                assertEquals("RUB", committed.currency)
                assertFalse(committed.isAvailable)
                assertEquals("TEA", committed.itemType)
                assertEquals(1, readAudits(dataSource, MENU_ITEM_RENAMED_AUDIT_ACTION).size)
                assertEquals(1, readAudits(dataSource, MENU_ITEM_PRICE_CHANGED_AUDIT_ACTION).size)
                assertEquals(1, readItemAvailabilityAudits(dataSource).size)
                val typeAudits = readAudits(dataSource, MENU_ITEM_TYPE_CHANGED_AUDIT_ACTION)
                assertEquals(2, typeAudits.size)
                assertEquals(FIRST_ACTOR_ID, typeAudits[0].actorUserId)
                assertEquals("HOOKAH", typeAudits[0].payload.getValue("oldItemType").jsonPrimitive.content)
                assertEquals("DRINK", typeAudits[0].payload.getValue("newItemType").jsonPrimitive.content)
                assertEquals(SECOND_ACTOR_ID, typeAudits[1].actorUserId)
                assertEquals("DRINK", typeAudits[1].payload.getValue("oldItemType").jsonPrimitive.content)
                assertEquals("TEA", typeAudits[1].payload.getValue("newItemType").jsonPrimitive.content)
                assertEquals("TELEGRAM_BOT", typeAudits[1].payload.getValue("source").jsonPrimitive.content)
                assertEquals(5, countAuditRows(dataSource))
            }
        }

    @Test
    fun `item move waits for source category reorder and preserves both committed audits`() =
        runBlocking {
            val database = PostgresTestEnv.createDatabase()
            PostgresTestEnv.createDataSource(database).use { dataSource ->
                val fixture = seedFixture(dataSource)
                val secondSourceItemId =
                    insertMenuItem(dataSource, fixture.venueId, fixture.categoryId, "Second source item", 1)
                val destinationCategoryId = insertCategory(dataSource, fixture.venueId, "Destination", 1)
                val oldOrder = listOf(fixture.itemId, secondSourceItemId)
                val newOrder = oldOrder.reversed()
                val holderDataSource = HeldCategoryLockDataSource(dataSource)
                val waiterDataSource = TrackedCategoryLockDataSource(dataSource)

                val (reordered, moved) =
                    runWithHeldCategoryLock(
                        observerDataSource = dataSource,
                        holderDataSource = holderDataSource,
                        waiterDataSource = waiterDataSource,
                        beforeRelease = {
                            assertEquals(oldOrder, readItemOrder(dataSource, fixture.venueId, fixture.categoryId))
                            assertEquals(fixture.categoryId, readFullItem(dataSource, fixture.itemId)?.categoryId)
                            assertEquals(0, countAuditRows(dataSource))
                        },
                        holderAction = {
                            runBlocking {
                                VenueMenuRepository(holderDataSource).reorderItems(
                                    venueId = fixture.venueId,
                                    categoryId = fixture.categoryId,
                                    itemIds = newOrder,
                                    actorUserId = FIRST_ACTOR_ID,
                                    source = MenuItemAvailabilitySource.VENUE_MINI_APP,
                                )
                            }
                        },
                        waiterAction = {
                            runBlocking {
                                VenueMenuRepository(waiterDataSource).updateItem(
                                    venueId = fixture.venueId,
                                    itemId = fixture.itemId,
                                    categoryId = destinationCategoryId,
                                    name = null,
                                    priceMinor = null,
                                    currency = null,
                                    isAvailable = null,
                                    actorUserId = SECOND_ACTOR_ID,
                                    source = MenuItemAvailabilitySource.VENUE_MINI_APP,
                                )
                            }
                        },
                    )

                assertTrue(reordered)
                assertEquals(destinationCategoryId, assertNotNull(moved).categoryId)
                assertEquals(listOf(secondSourceItemId), readItemOrder(dataSource, fixture.venueId, fixture.categoryId))
                assertEquals(listOf(fixture.itemId), readItemOrder(dataSource, fixture.venueId, destinationCategoryId))
                val reorderAudit = readAudits(dataSource, MENU_ITEMS_REORDERED_AUDIT_ACTION).single()
                assertEquals(
                    menuOrderSha256(oldOrder),
                    reorderAudit.payload.getValue("oldOrderSha256").jsonPrimitive.content,
                )
                assertEquals(
                    menuOrderSha256(newOrder),
                    reorderAudit.payload.getValue("newOrderSha256").jsonPrimitive.content,
                )
                val moveAudit = readAudits(dataSource, MENU_ITEM_CATEGORY_MOVED_AUDIT_ACTION).single()
                assertEquals(fixture.categoryId, moveAudit.payload.longValue("oldCategoryId"))
                assertEquals(destinationCategoryId, moveAudit.payload.longValue("newCategoryId"))
                assertEquals(SECOND_ACTOR_ID, moveAudit.actorUserId)
                assertEquals("VENUE_MINI_APP", moveAudit.payload.getValue("source").jsonPrimitive.content)
                assertEquals(2, countAuditRows(dataSource))
            }
        }

    @Test
    fun `item move serializes with destination category reorder and preserves relative order`() =
        runBlocking {
            val database = PostgresTestEnv.createDatabase()
            PostgresTestEnv.createDataSource(database).use { dataSource ->
                val fixture = seedFixture(dataSource)
                val destinationCategoryId = insertCategory(dataSource, fixture.venueId, "Destination", 1)
                val firstDestinationItemId =
                    insertMenuItem(dataSource, fixture.venueId, destinationCategoryId, "Destination one", 0)
                val secondDestinationItemId =
                    insertMenuItem(dataSource, fixture.venueId, destinationCategoryId, "Destination two", 1)
                val oldOrder = listOf(firstDestinationItemId, secondDestinationItemId)
                val newOrder = oldOrder.reversed()
                val holderDataSource = HeldCategoryLockDataSource(dataSource)
                val waiterDataSource = TrackedCategoryLockDataSource(dataSource)

                val (reordered, moved) =
                    runWithHeldCategoryLock(
                        observerDataSource = dataSource,
                        holderDataSource = holderDataSource,
                        waiterDataSource = waiterDataSource,
                        beforeRelease = {
                            assertEquals(oldOrder, readItemOrder(dataSource, fixture.venueId, destinationCategoryId))
                            assertEquals(fixture.categoryId, readFullItem(dataSource, fixture.itemId)?.categoryId)
                            assertEquals(0, countAuditRows(dataSource))
                        },
                        holderAction = {
                            runBlocking {
                                VenueMenuRepository(holderDataSource).reorderItems(
                                    venueId = fixture.venueId,
                                    categoryId = destinationCategoryId,
                                    itemIds = newOrder,
                                    actorUserId = FIRST_ACTOR_ID,
                                    source = MenuItemAvailabilitySource.VENUE_MINI_APP,
                                )
                            }
                        },
                        waiterAction = {
                            runBlocking {
                                VenueMenuRepository(waiterDataSource).updateItem(
                                    venueId = fixture.venueId,
                                    itemId = fixture.itemId,
                                    categoryId = destinationCategoryId,
                                    name = null,
                                    priceMinor = null,
                                    currency = null,
                                    isAvailable = null,
                                    actorUserId = SECOND_ACTOR_ID,
                                    source = MenuItemAvailabilitySource.VENUE_MINI_APP,
                                )
                            }
                        },
                    )

                assertTrue(reordered)
                assertEquals(destinationCategoryId, assertNotNull(moved).categoryId)
                val finalDestinationOrder =
                    readItemOrder(dataSource, fixture.venueId, destinationCategoryId)
                assertEquals(
                    setOf(fixture.itemId, firstDestinationItemId, secondDestinationItemId),
                    finalDestinationOrder.toSet(),
                )
                assertEquals(newOrder, finalDestinationOrder.filter { it in oldOrder.toSet() })
                val reorderAudit = readAudits(dataSource, MENU_ITEMS_REORDERED_AUDIT_ACTION).single()
                assertEquals(
                    menuOrderSha256(oldOrder),
                    reorderAudit.payload.getValue("oldOrderSha256").jsonPrimitive.content,
                )
                assertEquals(
                    menuOrderSha256(newOrder),
                    reorderAudit.payload.getValue("newOrderSha256").jsonPrimitive.content,
                )
                val moveAudit = readAudits(dataSource, MENU_ITEM_CATEGORY_MOVED_AUDIT_ACTION).single()
                assertEquals(fixture.categoryId, moveAudit.payload.longValue("oldCategoryId"))
                assertEquals(destinationCategoryId, moveAudit.payload.longValue("newCategoryId"))
                assertEquals("VENUE_MINI_APP", moveAudit.payload.getValue("source").jsonPrimitive.content)
                assertEquals(2, countAuditRows(dataSource))
            }
        }

    @Test
    fun `concurrent mini app item creates serialize with one audit per committed item`() =
        runBlocking {
            val database = PostgresTestEnv.createDatabase()
            PostgresTestEnv.createDataSource(database).use { dataSource ->
                val fixture = seedFixture(dataSource)
                val initialItems = readMenuItems(dataSource, fixture.venueId, fixture.categoryId)
                val holderDataSource = HeldCategoryLockDataSource(dataSource)
                val waiterDataSource = TrackedCategoryLockDataSource(dataSource)

                val (first, second) =
                    runWithHeldCategoryLock(
                        observerDataSource = dataSource,
                        holderDataSource = holderDataSource,
                        waiterDataSource = waiterDataSource,
                        beforeRelease = {
                            assertEquals(initialItems, readMenuItems(dataSource, fixture.venueId, fixture.categoryId))
                            assertTrue(readMenuItemCreateAudits(dataSource).isEmpty())
                        },
                        holderAction = {
                            runBlocking {
                                VenueMenuRepository(holderDataSource).createItem(
                                    venueId = fixture.venueId,
                                    categoryId = fixture.categoryId,
                                    name = FIRST_ITEM_CREATE_NAME,
                                    priceMinor = 120_000,
                                    currency = "RUB",
                                    isAvailable = true,
                                    actorUserId = FIRST_ACTOR_ID,
                                    source = MenuItemCreateSource.VENUE_MINI_APP,
                                    itemType = MenuSemanticType.HOOKAH,
                                )
                            }
                        },
                        waiterAction = {
                            runBlocking {
                                VenueMenuRepository(waiterDataSource).createItem(
                                    venueId = fixture.venueId,
                                    categoryId = fixture.categoryId,
                                    name = SECOND_ITEM_CREATE_NAME,
                                    priceMinor = 130_000,
                                    currency = "RUB",
                                    isAvailable = true,
                                    actorUserId = SECOND_ACTOR_ID,
                                    source = MenuItemCreateSource.VENUE_MINI_APP,
                                    itemType = MenuSemanticType.HOOKAH,
                                )
                            }
                        },
                    )

                val firstItem = assertNotNull(first)
                val secondItem = assertNotNull(second)
                assertFalse(firstItem.id == secondItem.id)
                assertEquals(initialItems.maxOf { it.sortOrder } + 1, firstItem.sortOrder)
                assertEquals(firstItem.sortOrder + 1, secondItem.sortOrder)
                assertMenuItemCreateCardinality(
                    dataSource = dataSource,
                    fixture = fixture,
                    initialItemIds = initialItems.map { it.id }.toSet(),
                    expected =
                        listOf(
                            ExpectedItemCreateAudit(
                                firstItem.id,
                                FIRST_ACTOR_ID,
                                MenuItemCreateSource.VENUE_MINI_APP,
                            ),
                            ExpectedItemCreateAudit(
                                secondItem.id,
                                SECOND_ACTOR_ID,
                                MenuItemCreateSource.VENUE_MINI_APP,
                            ),
                        ),
                )
            }
        }

    @Test
    fun `mini app and telegram item creates serialize with server sources`() =
        runBlocking {
            val database = PostgresTestEnv.createDatabase()
            PostgresTestEnv.createDataSource(database).use { dataSource ->
                val fixture = seedFixture(dataSource)
                val initialItems = readMenuItems(dataSource, fixture.venueId, fixture.categoryId)
                val holderDataSource = HeldCategoryLockDataSource(dataSource)
                val waiterDataSource = TrackedCategoryLockDataSource(dataSource)

                val (miniApp, telegram) =
                    runWithHeldCategoryLock(
                        observerDataSource = dataSource,
                        holderDataSource = holderDataSource,
                        waiterDataSource = waiterDataSource,
                        beforeRelease = {
                            assertEquals(initialItems, readMenuItems(dataSource, fixture.venueId, fixture.categoryId))
                            assertTrue(readMenuItemCreateAudits(dataSource).isEmpty())
                        },
                        holderAction = {
                            runBlocking {
                                VenueMenuRepository(holderDataSource).createItem(
                                    venueId = fixture.venueId,
                                    categoryId = fixture.categoryId,
                                    name = FIRST_ITEM_CREATE_NAME,
                                    priceMinor = 120_000,
                                    currency = "RUB",
                                    isAvailable = true,
                                    actorUserId = FIRST_ACTOR_ID,
                                    source = MenuItemCreateSource.VENUE_MINI_APP,
                                    itemType = MenuSemanticType.HOOKAH,
                                )
                            }
                        },
                        waiterAction = {
                            runBlocking {
                                VenueMenuRepository(waiterDataSource).createItem(
                                    venueId = fixture.venueId,
                                    categoryId = fixture.categoryId,
                                    name = SECOND_ITEM_CREATE_NAME,
                                    priceMinor = 130_000,
                                    currency = "RUB",
                                    isAvailable = true,
                                    actorUserId = SECOND_ACTOR_ID,
                                    source = MenuItemCreateSource.TELEGRAM_BOT,
                                    itemType = MenuSemanticType.HOOKAH,
                                )
                            }
                        },
                    )

                val miniAppItem = assertNotNull(miniApp)
                val telegramItem = assertNotNull(telegram)
                assertMenuItemCreateCardinality(
                    dataSource = dataSource,
                    fixture = fixture,
                    initialItemIds = initialItems.map { it.id }.toSet(),
                    expected =
                        listOf(
                            ExpectedItemCreateAudit(
                                miniAppItem.id,
                                FIRST_ACTOR_ID,
                                MenuItemCreateSource.VENUE_MINI_APP,
                            ),
                            ExpectedItemCreateAudit(
                                telegramItem.id,
                                SECOND_ACTOR_ID,
                                MenuItemCreateSource.TELEGRAM_BOT,
                            ),
                        ),
                )
            }
        }

    @Test
    fun `item create category lock makes concurrent category delete fail without partial state`() =
        runBlocking {
            val database = PostgresTestEnv.createDatabase()
            PostgresTestEnv.createDataSource(database).use { dataSource ->
                val fixture = seedFixture(dataSource)
                val emptyCategoryId = insertEmptyCategory(dataSource, fixture.venueId)
                val holderDataSource = HeldCategoryLockDataSource(dataSource)
                val deleteDataSource = TrackedCategoryDeleteLockDataSource(dataSource)
                val executor = Executors.newFixedThreadPool(2)
                val createFuture =
                    executor.submit(
                        Callable {
                            runBlocking {
                                VenueMenuRepository(holderDataSource).createItem(
                                    venueId = fixture.venueId,
                                    categoryId = emptyCategoryId,
                                    name = FIRST_ITEM_CREATE_NAME,
                                    priceMinor = 120_000,
                                    currency = "RUB",
                                    isAvailable = true,
                                    actorUserId = FIRST_ACTOR_ID,
                                    source = MenuItemCreateSource.VENUE_MINI_APP,
                                    itemType = MenuSemanticType.HOOKAH,
                                )
                            }
                        },
                    )
                try {
                    assertTrue(
                        holderDataSource.categoryLockAcquired.await(WAIT_TIMEOUT_SECONDS, TimeUnit.SECONDS),
                        "Production item create did not acquire the category lock",
                    )
                    val deleteFuture =
                        executor.submit(
                            Callable {
                                runBlocking {
                                    VenueMenuRepository(deleteDataSource).deleteCategory(
                                        venueId = fixture.venueId,
                                        categoryId = emptyCategoryId,
                                        actorUserId = SECOND_ACTOR_ID,
                                        source = MenuCategoryDeleteSource.TELEGRAM_BOT,
                                    )
                                }
                            },
                        )
                    assertTrue(
                        deleteDataSource.categoryDeleteLockAttempted.await(
                            WAIT_TIMEOUT_SECONDS,
                            TimeUnit.SECONDS,
                        ),
                        "Concurrent category delete did not attempt its production NOWAIT lock",
                    )
                    assertTrue(holderDataSource.backendPid.get() > 0)
                    assertTrue(deleteDataSource.backendPid.get() > 0)
                    assertFalse(holderDataSource.backendPid.get() == deleteDataSource.backendPid.get())
                    val deleteFailure =
                        assertFailsWith<ExecutionException> {
                            deleteFuture.get(WAIT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                        }
                    assertTrue(deleteFailure.cause is DatabaseUnavailableException)
                    assertTrue(categoryExists(dataSource, fixture.venueId, emptyCategoryId))
                    assertTrue(readMenuItems(dataSource, fixture.venueId, emptyCategoryId).isEmpty())
                    assertTrue(readMenuItemCreateAudits(dataSource).isEmpty())

                    holderDataSource.allowMutation.countDown()
                    val created = assertNotNull(createFuture.get(WAIT_TIMEOUT_SECONDS, TimeUnit.SECONDS))
                    assertTrue(categoryExists(dataSource, fixture.venueId, emptyCategoryId))
                    assertEquals(
                        listOf(created.id),
                        readMenuItems(dataSource, fixture.venueId, emptyCategoryId).map { it.id },
                    )
                    assertMenuItemCreateAudits(
                        dataSource = dataSource,
                        fixture = fixture,
                        expected =
                            listOf(
                                ExpectedItemCreateAudit(
                                    created.id,
                                    FIRST_ACTOR_ID,
                                    MenuItemCreateSource.VENUE_MINI_APP,
                                ),
                            ),
                    )
                    assertTrue(readAudits(dataSource, MENU_CATEGORY_DELETED_AUDIT_ACTION).isEmpty())
                } finally {
                    holderDataSource.allowMutation.countDown()
                    executor.shutdownNow()
                    executor.awaitTermination(WAIT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                }
            }
        }

    @Test
    fun `item create and reorder serialize on category lock with current sort outcome`() =
        runBlocking {
            val database = PostgresTestEnv.createDatabase()
            PostgresTestEnv.createDataSource(database).use { dataSource ->
                val fixture = seedFixture(dataSource)
                val initialItems = readMenuItems(dataSource, fixture.venueId, fixture.categoryId)
                val holderDataSource = HeldCategoryLockDataSource(dataSource)
                val waiterDataSource = TrackedCategoryLockDataSource(dataSource)

                val (created, reordered) =
                    runWithHeldCategoryLock(
                        observerDataSource = dataSource,
                        holderDataSource = holderDataSource,
                        waiterDataSource = waiterDataSource,
                        beforeRelease = {
                            assertEquals(initialItems, readMenuItems(dataSource, fixture.venueId, fixture.categoryId))
                            assertTrue(readMenuItemCreateAudits(dataSource).isEmpty())
                        },
                        holderAction = {
                            runBlocking {
                                VenueMenuRepository(holderDataSource).createItem(
                                    venueId = fixture.venueId,
                                    categoryId = fixture.categoryId,
                                    name = FIRST_ITEM_CREATE_NAME,
                                    priceMinor = 120_000,
                                    currency = "RUB",
                                    isAvailable = true,
                                    actorUserId = FIRST_ACTOR_ID,
                                    source = MenuItemCreateSource.VENUE_MINI_APP,
                                    itemType = MenuSemanticType.HOOKAH,
                                )
                            }
                        },
                        waiterAction = {
                            runBlocking {
                                VenueMenuRepository(waiterDataSource).reorderItems(
                                    venueId = fixture.venueId,
                                    categoryId = fixture.categoryId,
                                    itemIds = listOf(fixture.itemId),
                                    actorUserId = SECOND_ACTOR_ID,
                                    source = MenuItemAvailabilitySource.VENUE_MINI_APP,
                                )
                            }
                        },
                    )

                val createdItem = assertNotNull(created)
                assertFalse(reordered)
                val committedItems = readMenuItems(dataSource, fixture.venueId, fixture.categoryId)
                assertEquals(0, committedItems.single { it.id == fixture.itemId }.sortOrder)
                assertEquals(initialItems.maxOf { it.sortOrder } + 1, createdItem.sortOrder)
                assertEquals(createdItem.sortOrder, committedItems.single { it.id == createdItem.id }.sortOrder)
                assertMenuItemCreateCardinality(
                    dataSource = dataSource,
                    fixture = fixture,
                    initialItemIds = initialItems.map { it.id }.toSet(),
                    expected =
                        listOf(
                            ExpectedItemCreateAudit(
                                createdItem.id,
                                FIRST_ACTOR_ID,
                                MenuItemCreateSource.VENUE_MINI_APP,
                            ),
                        ),
                )
                assertTrue(readAudits(dataSource, MENU_ITEMS_REORDERED_AUDIT_ACTION).isEmpty())
            }
        }

    @Test
    fun `item delete and item reorder serialize with exact set loser and no reorder audit`() =
        runBlocking {
            val database = PostgresTestEnv.createDatabase()
            PostgresTestEnv.createDataSource(database).use { dataSource ->
                val fixture = seedFixture(dataSource)
                val secondItemId =
                    insertMenuItem(dataSource, fixture.venueId, fixture.categoryId, "Delete race survivor", 1)
                val preDeleteOrder = listOf(fixture.itemId, secondItemId)
                val holderDataSource = HeldItemDeleteLockDataSource(dataSource)
                val waiterDataSource = TrackedItemLockDataSource(dataSource)

                val (deleted, reordered) =
                    runWithHeldItemDeleteLock(
                        observerDataSource = dataSource,
                        holderDataSource = holderDataSource,
                        waiterDataSource = waiterDataSource,
                        beforeRelease = {
                            assertEquals(preDeleteOrder, readItemOrder(dataSource, fixture.venueId, fixture.categoryId))
                            assertEquals(0, countAuditRows(dataSource))
                        },
                        holderAction = {
                            runBlocking {
                                VenueMenuRepository(holderDataSource).deleteItem(
                                    venueId = fixture.venueId,
                                    itemId = fixture.itemId,
                                    actorUserId = FIRST_ACTOR_ID,
                                    source = MenuItemDeleteSource.TELEGRAM_BOT,
                                )
                            }
                        },
                        waiterAction = {
                            runBlocking {
                                VenueMenuRepository(waiterDataSource).reorderItems(
                                    venueId = fixture.venueId,
                                    categoryId = fixture.categoryId,
                                    itemIds = preDeleteOrder.reversed(),
                                    actorUserId = SECOND_ACTOR_ID,
                                    source = MenuItemAvailabilitySource.VENUE_MINI_APP,
                                )
                            }
                        },
                    )

                assertTrue(deleted)
                assertFalse(reordered)
                assertNull(readFullItem(dataSource, fixture.itemId))
                assertEquals(listOf(secondItemId), readItemOrder(dataSource, fixture.venueId, fixture.categoryId))
                val deleteAudit = readItemDeleteAudits(dataSource).single()
                assertEquals(FIRST_ACTOR_ID, deleteAudit.actorUserId)
                assertEquals("TELEGRAM_BOT", deleteAudit.payload.getValue("source").jsonPrimitive.content)
                assertTrue(readAudits(dataSource, MENU_ITEMS_REORDERED_AUDIT_ACTION).isEmpty())
                assertEquals(1, countAuditRows(dataSource))
            }
        }

    @Test
    fun `compound item later audit failure rolls back all deltas and earlier audits`() =
        runBlocking {
            val database = PostgresTestEnv.createDatabase()
            PostgresTestEnv.createDataSource(database).use { dataSource ->
                val fixture = seedFixture(dataSource)
                val destinationCategoryId = insertCategory(dataSource, fixture.venueId, "Rollback destination", 1)
                val original = assertNotNull(readFullItem(dataSource, fixture.itemId))
                val originalOptions = readOptions(dataSource, fixture.itemId)
                val auditReached = CountDownLatch(1)
                val allowAuditFailure = CountDownLatch(1)
                val failingPid = AtomicInteger()
                val realAuditWriter = AuditLogRepository(dataSource, Json)
                val failingAuditWriter =
                    TransactionalAuditLogWriter { connection, actorUserId, action, entityType, entityId, payload ->
                        realAuditWriter.appendJson(
                            connection = connection,
                            actorUserId = actorUserId,
                            action = action,
                            entityType = entityType,
                            entityId = entityId,
                            payload = payload,
                        )
                        if (action == MENU_ITEM_AVAILABILITY_CHANGED_AUDIT_ACTION) {
                            val mutated = readFullItem(connection, fixture.itemId)
                            if (
                                mutated == null ||
                                mutated.categoryId != destinationCategoryId ||
                                mutated.name != "Rolled back compound name" ||
                                mutated.priceMinor != 145_000L ||
                                mutated.currency != "USD" ||
                                mutated.isAvailable ||
                                mutated.itemType != "DRINK"
                            ) {
                                throw SQLException("Compound item mutation was not visible before its audits")
                            }
                            val expectedActions =
                                listOf(
                                    MENU_ITEM_RENAMED_AUDIT_ACTION,
                                    MENU_ITEM_PRICE_CHANGED_AUDIT_ACTION,
                                    MENU_ITEM_TYPE_CHANGED_AUDIT_ACTION,
                                    MENU_ITEM_CATEGORY_MOVED_AUDIT_ACTION,
                                    MENU_ITEM_AVAILABILITY_CHANGED_AUDIT_ACTION,
                                )
                            if (
                                countAuditRows(connection) != expectedActions.size ||
                                readAuditActions(connection, fixture.itemId) != expectedActions
                            ) {
                                throw SQLException("Compound item audits were not visible on the writer connection")
                            }
                            failingPid.set(backendPid(connection))
                            auditReached.countDown()
                            if (!allowAuditFailure.await(WAIT_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                                throw SQLException("Timed out before injected compound item audit failure")
                            }
                            throw SQLException("Injected final compound item audit failure")
                        }
                    }
                val waiterDataSource = TrackedCategoryLockDataSource(dataSource)
                val executor = Executors.newFixedThreadPool(2)
                val failedFuture =
                    executor.submit(
                        Callable {
                            runBlocking {
                                VenueMenuRepository(dataSource, failingAuditWriter).updateItem(
                                    venueId = fixture.venueId,
                                    itemId = fixture.itemId,
                                    categoryId = destinationCategoryId,
                                    name = "Rolled back compound name",
                                    priceMinor = 145_000,
                                    currency = "USD",
                                    isAvailable = false,
                                    actorUserId = FIRST_ACTOR_ID,
                                    source = MenuItemAvailabilitySource.VENUE_MINI_APP,
                                    itemType = MenuSemanticType.DRINK,
                                    itemTypeSpecified = true,
                                )
                            }
                        },
                    )
                try {
                    assertTrue(
                        auditReached.await(WAIT_TIMEOUT_SECONDS, TimeUnit.SECONDS),
                        "Injected writer did not reach the final compound item audit",
                    )
                    val waiterFuture =
                        executor.submit(
                            Callable {
                                runBlocking {
                                    VenueMenuRepository(waiterDataSource).updateItemType(
                                        venueId = fixture.venueId,
                                        itemId = fixture.itemId,
                                        itemType = MenuSemanticType.HOOKAH,
                                        actorUserId = SECOND_ACTOR_ID,
                                        source = MenuItemAvailabilitySource.TELEGRAM_BOT,
                                    )
                                }
                            },
                        )
                    assertTrue(
                        waiterDataSource.categoryLockAttempted.await(WAIT_TIMEOUT_SECONDS, TimeUnit.SECONDS),
                        "Telegram type writer did not attempt the category lock",
                    )
                    val holderPid = failingPid.get()
                    val waiterPid = waiterDataSource.backendPid.get()
                    assertTrue(holderPid > 0)
                    assertTrue(waiterPid > 0)
                    assertFalse(holderPid == waiterPid)
                    dataSource.connection.use { observer ->
                        val observation =
                            awaitPostgresBlock(
                                observer = observer,
                                blockedPid = waiterPid,
                                blockerPid = holderPid,
                                waiterFuture = waiterFuture,
                            )
                        assertTrue(
                            observation.blocked,
                            "PostgreSQL did not report compound audit-failure blocking edge. " +
                                observation.diagnostic,
                        )
                    }
                    assertEquals(original, readFullItem(dataSource, fixture.itemId))
                    assertEquals(originalOptions, readOptions(dataSource, fixture.itemId))
                    assertEquals(0, countAuditRows(dataSource))

                    allowAuditFailure.countDown()
                    val failure =
                        assertFailsWith<ExecutionException> {
                            failedFuture.get(WAIT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                        }
                    assertTrue(failure.cause is DatabaseUnavailableException)
                    assertNotNull(waiterFuture.get(WAIT_TIMEOUT_SECONDS, TimeUnit.SECONDS))
                    assertEquals(original, readFullItem(dataSource, fixture.itemId))
                    assertEquals(originalOptions, readOptions(dataSource, fixture.itemId))
                    assertEquals(0, countAuditRows(dataSource))
                } finally {
                    allowAuditFailure.countDown()
                    executor.shutdownNow()
                    executor.awaitTermination(WAIT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                }
            }
        }

    @Test
    fun `item reorder audit failure rolls back order before concurrent create commits`() =
        runBlocking {
            val database = PostgresTestEnv.createDatabase()
            PostgresTestEnv.createDataSource(database).use { dataSource ->
                val fixture = seedFixture(dataSource)
                val secondItemId =
                    insertMenuItem(dataSource, fixture.venueId, fixture.categoryId, "Reorder rollback item", 1)
                val originalOrder = listOf(fixture.itemId, secondItemId)
                val originalStates = originalOrder.map { itemId -> assertNotNull(readFullItem(dataSource, itemId)) }
                val auditReached = CountDownLatch(1)
                val allowAuditFailure = CountDownLatch(1)
                val failingPid = AtomicInteger()
                val realAuditWriter = AuditLogRepository(dataSource, Json)
                val failingAuditWriter =
                    TransactionalAuditLogWriter { connection, actorUserId, action, entityType, entityId, payload ->
                        realAuditWriter.appendJson(
                            connection = connection,
                            actorUserId = actorUserId,
                            action = action,
                            entityType = entityType,
                            entityId = entityId,
                            payload = payload,
                        )
                        if (action == MENU_ITEMS_REORDERED_AUDIT_ACTION) {
                            if (
                                readItemOrder(connection, fixture.venueId, fixture.categoryId) !=
                                originalOrder.reversed()
                            ) {
                                throw SQLException("Item reorder mutation was not visible before its audit")
                            }
                            if (
                                countAuditRows(connection) != 1 ||
                                readAuditActions(connection, fixture.categoryId) !=
                                listOf(MENU_ITEMS_REORDERED_AUDIT_ACTION)
                            ) {
                                throw SQLException("Item reorder audit was not visible on the writer connection")
                            }
                            failingPid.set(backendPid(connection))
                            auditReached.countDown()
                            if (!allowAuditFailure.await(WAIT_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                                throw SQLException("Timed out before injected item reorder audit failure")
                            }
                            throw SQLException("Injected item reorder audit failure")
                        }
                    }
                val waiterDataSource = TrackedCategoryLockDataSource(dataSource)
                val executor = Executors.newFixedThreadPool(2)
                val failedFuture =
                    executor.submit(
                        Callable {
                            runBlocking {
                                VenueMenuRepository(dataSource, failingAuditWriter).reorderItems(
                                    venueId = fixture.venueId,
                                    categoryId = fixture.categoryId,
                                    itemIds = originalOrder.reversed(),
                                    actorUserId = FIRST_ACTOR_ID,
                                    source = MenuItemAvailabilitySource.VENUE_MINI_APP,
                                )
                            }
                        },
                    )
                try {
                    assertTrue(
                        auditReached.await(WAIT_TIMEOUT_SECONDS, TimeUnit.SECONDS),
                        "Injected writer did not reach the item reorder audit",
                    )
                    val createFuture =
                        executor.submit(
                            Callable {
                                runBlocking {
                                    VenueMenuRepository(waiterDataSource).createItem(
                                        venueId = fixture.venueId,
                                        categoryId = fixture.categoryId,
                                        name = SECOND_ITEM_CREATE_NAME,
                                        priceMinor = 130_000,
                                        currency = "RUB",
                                        isAvailable = true,
                                        actorUserId = SECOND_ACTOR_ID,
                                        source = MenuItemCreateSource.TELEGRAM_BOT,
                                        itemType = MenuSemanticType.HOOKAH,
                                    )
                                }
                            },
                        )
                    assertTrue(
                        waiterDataSource.categoryLockAttempted.await(WAIT_TIMEOUT_SECONDS, TimeUnit.SECONDS),
                        "Concurrent item create did not attempt the category lock",
                    )
                    val holderPid = failingPid.get()
                    val waiterPid = waiterDataSource.backendPid.get()
                    assertTrue(holderPid > 0)
                    assertTrue(waiterPid > 0)
                    assertFalse(holderPid == waiterPid)
                    dataSource.connection.use { observer ->
                        val observation =
                            awaitPostgresBlock(
                                observer = observer,
                                blockedPid = waiterPid,
                                blockerPid = holderPid,
                                waiterFuture = createFuture,
                            )
                        assertTrue(
                            observation.blocked,
                            "PostgreSQL did not report reorder audit-failure blocking create. " +
                                observation.diagnostic,
                        )
                    }
                    assertEquals(originalOrder, readItemOrder(dataSource, fixture.venueId, fixture.categoryId))
                    assertEquals(originalStates, originalOrder.map { assertNotNull(readFullItem(dataSource, it)) })
                    assertEquals(0, countAuditRows(dataSource))

                    allowAuditFailure.countDown()
                    val failure =
                        assertFailsWith<ExecutionException> {
                            failedFuture.get(WAIT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                        }
                    assertTrue(failure.cause is DatabaseUnavailableException)
                    val created = assertNotNull(createFuture.get(WAIT_TIMEOUT_SECONDS, TimeUnit.SECONDS))
                    assertEquals(
                        originalOrder + created.id,
                        readItemOrder(dataSource, fixture.venueId, fixture.categoryId),
                    )
                    assertEquals(originalStates, originalOrder.map { assertNotNull(readFullItem(dataSource, it)) })
                    assertTrue(readAudits(dataSource, MENU_ITEMS_REORDERED_AUDIT_ACTION).isEmpty())
                    val createAudit = readMenuItemCreateAudits(dataSource).single()
                    assertEquals(created.id, createAudit.entityId)
                    assertEquals(SECOND_ACTOR_ID, createAudit.actorUserId)
                    assertEquals(1, countAuditRows(dataSource))
                } finally {
                    allowAuditFailure.countDown()
                    executor.shutdownNow()
                    executor.awaitTermination(WAIT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                }
            }
        }

    @Test
    fun `concurrent audit failure rolls back inserted item before valid create commits`() =
        runBlocking {
            val database = PostgresTestEnv.createDatabase()
            PostgresTestEnv.createDataSource(database).use { dataSource ->
                val fixture = seedFixture(dataSource)
                val initialItems = readMenuItems(dataSource, fixture.venueId, fixture.categoryId)
                val initialOptions = readOptions(dataSource, fixture.itemId)
                val auditReached = CountDownLatch(1)
                val allowAuditFailure = CountDownLatch(1)
                val failedItemId = java.util.concurrent.atomic.AtomicLong()
                val failingPid = AtomicInteger()
                val realAuditWriter = AuditLogRepository(dataSource, Json)
                val failingAuditWriter =
                    TransactionalAuditLogWriter { connection, actorUserId, action, entityType, entityId, payload ->
                        val insertedItemId = entityId ?: throw SQLException("Missing item id in create audit")
                        if (connection.autoCommit || action != MENU_ITEM_CREATED_AUDIT_ACTION) {
                            throw SQLException("Item create audit was not called in the production transaction")
                        }
                        if (!rowExists(connection, "menu_items", insertedItemId)) {
                            throw SQLException("Physical menu item insert was not visible before audit")
                        }
                        realAuditWriter.appendJson(
                            connection = connection,
                            actorUserId = actorUserId,
                            action = action,
                            entityType = entityType,
                            entityId = entityId,
                            payload = payload,
                        )
                        if (!auditExists(connection, action, insertedItemId)) {
                            throw SQLException("Connection-aware audit insert was not visible")
                        }
                        failedItemId.set(insertedItemId)
                        failingPid.set(backendPid(connection))
                        auditReached.countDown()
                        if (!allowAuditFailure.await(WAIT_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                            throw SQLException("Timed out before injected audit failure")
                        }
                        throw SQLException("Injected same-connection item create audit failure")
                    }
                val waiterDataSource = TrackedCategoryLockDataSource(dataSource)
                val executor = Executors.newFixedThreadPool(2)
                val failedFuture =
                    executor.submit(
                        Callable {
                            runBlocking {
                                VenueMenuRepository(dataSource, failingAuditWriter).createItem(
                                    venueId = fixture.venueId,
                                    categoryId = fixture.categoryId,
                                    name = FAILED_ITEM_CREATE_NAME,
                                    priceMinor = 140_000,
                                    currency = "RUB",
                                    isAvailable = true,
                                    actorUserId = FIRST_ACTOR_ID,
                                    source = MenuItemCreateSource.VENUE_MINI_APP,
                                    itemType = MenuSemanticType.HOOKAH,
                                )
                            }
                        },
                    )
                try {
                    assertTrue(
                        auditReached.await(WAIT_TIMEOUT_SECONDS, TimeUnit.SECONDS),
                        "Injected writer did not observe the physical item and audit inserts",
                    )
                    val validFuture =
                        executor.submit(
                            Callable {
                                runBlocking {
                                    VenueMenuRepository(waiterDataSource).createItem(
                                        venueId = fixture.venueId,
                                        categoryId = fixture.categoryId,
                                        name = SECOND_ITEM_CREATE_NAME,
                                        priceMinor = 130_000,
                                        currency = "RUB",
                                        isAvailable = true,
                                        actorUserId = SECOND_ACTOR_ID,
                                        source = MenuItemCreateSource.TELEGRAM_BOT,
                                        itemType = MenuSemanticType.HOOKAH,
                                    )
                                }
                            },
                        )
                    assertTrue(
                        waiterDataSource.categoryLockAttempted.await(WAIT_TIMEOUT_SECONDS, TimeUnit.SECONDS),
                        "Valid concurrent create did not attempt the category lock",
                    )
                    val holderPid = failingPid.get()
                    val waiterPid = waiterDataSource.backendPid.get()
                    assertTrue(holderPid > 0)
                    assertTrue(waiterPid > 0)
                    assertFalse(holderPid == waiterPid)
                    dataSource.connection.use { observer ->
                        val observation =
                            awaitPostgresBlock(
                                observer = observer,
                                blockedPid = waiterPid,
                                blockerPid = holderPid,
                                waiterFuture = validFuture,
                            )
                        assertTrue(
                            observation.blocked,
                            "PostgreSQL did not report audit-failure transaction blocking valid create. " +
                                observation.diagnostic,
                        )
                    }
                    assertEquals(initialItems, readMenuItems(dataSource, fixture.venueId, fixture.categoryId))
                    assertTrue(readMenuItemCreateAudits(dataSource).isEmpty())

                    allowAuditFailure.countDown()
                    val failure =
                        assertFailsWith<ExecutionException> {
                            failedFuture.get(WAIT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                        }
                    assertTrue(failure.cause is DatabaseUnavailableException)
                    val validItem = assertNotNull(validFuture.get(WAIT_TIMEOUT_SECONDS, TimeUnit.SECONDS))
                    assertNull(
                        readMenuItems(dataSource, fixture.venueId, fixture.categoryId)
                            .find { it.id == failedItemId.get() },
                    )
                    assertEquals(initialOptions, readOptions(dataSource, fixture.itemId))
                    assertMenuItemCreateCardinality(
                        dataSource = dataSource,
                        fixture = fixture,
                        initialItemIds = initialItems.map { it.id }.toSet(),
                        expected =
                            listOf(
                                ExpectedItemCreateAudit(
                                    validItem.id,
                                    SECOND_ACTOR_ID,
                                    MenuItemCreateSource.TELEGRAM_BOT,
                                ),
                            ),
                    )
                    assertEquals(1, countAuditRows(dataSource))
                } finally {
                    allowAuditFailure.countDown()
                    executor.shutdownNow()
                    executor.awaitTermination(WAIT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                }
            }
        }

    private fun <T, U> runWithHeldCategoryLock(
        observerDataSource: DataSource,
        holderDataSource: HeldCategoryLockCoordinator,
        waiterDataSource: TrackedCategoryLockDataSource,
        beforeRelease: () -> Unit,
        holderAction: () -> T,
        waiterAction: () -> U,
    ): Pair<T, U> {
        val executor = Executors.newFixedThreadPool(2)
        val holderFuture = executor.submit(Callable<T> { holderAction() })
        try {
            assertTrue(
                holderDataSource.categoryLockAcquired.await(WAIT_TIMEOUT_SECONDS, TimeUnit.SECONDS),
                "Production lock holder did not acquire the menu category lock",
            )
            val waiterFuture = executor.submit(Callable<U> { waiterAction() })
            assertTrue(
                waiterDataSource.categoryLockAttempted.await(WAIT_TIMEOUT_SECONDS, TimeUnit.SECONDS),
                "Competing production mutation did not attempt the menu category lock",
            )

            val holderPid = holderDataSource.backendPid.get()
            val waiterPid = waiterDataSource.backendPid.get()
            assertTrue(holderPid > 0, "Missing PostgreSQL PID for category-lock holder")
            assertTrue(waiterPid > 0, "Missing PostgreSQL PID for category-lock waiter")
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
                    "PostgreSQL did not report the expected category-lock blocking edge. " +
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

    private fun <T, U> runWithHeldItemLock(
        observerDataSource: DataSource,
        holderDataSource: HeldItemLockCoordinator,
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

    private fun <T, U> runWithHeldItemDeleteLock(
        observerDataSource: DataSource,
        holderDataSource: HeldItemDeleteLockDataSource,
        waiterDataSource: TrackedItemLockDataSource,
        beforeRelease: () -> Unit,
        holderAction: () -> T,
        waiterAction: () -> U,
    ): Pair<T, U> =
        runWithHeldItemLock(
            observerDataSource = observerDataSource,
            holderDataSource = holderDataSource,
            waiterDataSource = waiterDataSource,
            beforeRelease = beforeRelease,
            holderAction = holderAction,
            waiterAction = waiterAction,
        )

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
                    categoryId = categoryId,
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

    private fun insertEmptyCategory(
        dataSource: DataSource,
        venueId: Long,
    ): Long = insertCategory(dataSource, venueId, "Empty create concurrency category", 1)

    private fun insertCategory(
        dataSource: DataSource,
        venueId: Long,
        name: String,
        sortOrder: Int,
    ): Long =
        dataSource.connection.use { connection ->
            insertReturningId(
                connection,
                """
                INSERT INTO menu_categories (venue_id, name, sort_order, category_type)
                VALUES (?, ?, ?, 'HOOKAH')
                """.trimIndent(),
            ) { statement ->
                statement.setLong(1, venueId)
                statement.setString(2, name)
                statement.setInt(3, sortOrder)
            }
        }

    private fun insertMenuItem(
        dataSource: DataSource,
        venueId: Long,
        categoryId: Long,
        name: String,
        sortOrder: Int,
    ): Long =
        dataSource.connection.use { connection ->
            insertReturningId(
                connection,
                """
                INSERT INTO menu_items (
                    venue_id, category_id, name, price_minor, currency,
                    is_available, sort_order, item_type
                )
                VALUES (?, ?, ?, 100000, 'RUB', TRUE, ?, 'HOOKAH')
                """.trimIndent(),
            ) { statement ->
                statement.setLong(1, venueId)
                statement.setLong(2, categoryId)
                statement.setString(3, name)
                statement.setInt(4, sortOrder)
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
            fixture.initialOptionIds,
            options.map { option -> option.id }.toSet(),
        )
        assertTrue(readCreateAudits(dataSource).isEmpty())
        assertTrue(readDeleteAudits(dataSource).isEmpty())
        assertTrue(readRenameAudits(dataSource).isEmpty())
        assertTrue(readPriceAudits(dataSource).isEmpty())
        assertTrue(readAvailabilityAudits(dataSource).isEmpty())
        assertTrue(readShiftCheckAudits(dataSource).isEmpty())
    }

    private fun assertInitialItemState(
        dataSource: DataSource,
        fixture: Fixture,
    ) {
        assertInitialState(dataSource, fixture)
        val item = assertNotNull(readItem(dataSource, fixture.itemId))
        assertEquals(INITIAL_ITEM_NAME, item.name)
        assertTrue(item.isAvailable)
        assertTrue(readItemAvailabilityAudits(dataSource).isEmpty())
        assertTrue(readItemDeleteAudits(dataSource).isEmpty())
        assertEquals(0, countAuditRows(dataSource))
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

    private fun assertCanonicalProfilesUnique(options: List<OptionRow>) {
        HookahFlavorProfileService.baseProfiles.forEach { profileName ->
            val profileKey = HookahFlavorProfileService.normalizeFlavorNameKey(profileName)
            assertEquals(
                1,
                options.count { option ->
                    HookahFlavorProfileService.normalizeFlavorNameKey(option.name) == profileKey
                },
                "Canonical profile $profileKey must exist exactly once",
            )
        }
    }

    private fun profileOptionIds(
        options: List<OptionRow>,
        profileNames: List<String>,
    ): List<Long> =
        profileNames.map { profileName ->
            val profileKey = HookahFlavorProfileService.normalizeFlavorNameKey(profileName)
            options.single { option ->
                HookahFlavorProfileService.normalizeFlavorNameKey(option.name) == profileKey
            }.id
        }

    private fun assertCreateAudits(
        dataSource: DataSource,
        fixture: Fixture,
        expected: List<ExpectedCreateAudit>,
    ) {
        val audits = readCreateAudits(dataSource)
        assertEquals(expected.size, audits.size)
        assertEquals(expected.map { it.optionId }, audits.map { it.entityId })
        expected.forEachIndexed { index, expectedAudit ->
            val audit = audits[index]
            assertEquals(expectedAudit.actorUserId, audit.actorUserId)
            assertEquals("menu_item_option", audit.entityType)
            assertEquals(expectedAudit.optionId, audit.entityId)
            assertEquals(AUDIT_PAYLOAD_KEYS, audit.payload.keys)
            assertEquals(fixture.venueId, audit.payload.longValue("venueId"))
            assertEquals(fixture.itemId, audit.payload.longValue("itemId"))
            assertEquals(expectedAudit.optionId, audit.payload.longValue("optionId"))
            assertEquals(
                expectedAudit.source.name,
                audit.payload.getValue("source").jsonPrimitive.content,
            )
        }
    }

    private fun assertCommittedCreateAuditCardinality(
        dataSource: DataSource,
        fixture: Fixture,
    ) {
        val committedCreateIds =
            readOptions(dataSource, fixture.itemId)
                .map { option -> option.id }
                .filterNot { optionId -> optionId in fixture.initialOptionIds }
                .toSet()
        val createAudits = readCreateAudits(dataSource)
        assertEquals(committedCreateIds.size, createAudits.size)
        assertEquals(committedCreateIds, createAudits.map { audit -> audit.entityId }.toSet())
        assertEquals(createAudits.size, createAudits.map { audit -> audit.entityId }.distinct().size)
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

    private fun assertPriceAudit(
        audit: AuditRow,
        fixture: Fixture,
        expectedOldPriceDeltaMinor: Long,
        expectedNewPriceDeltaMinor: Long,
        expectedActorUserId: Long,
    ) {
        assertEquals(expectedActorUserId, audit.actorUserId)
        assertEquals("menu_item_option", audit.entityType)
        assertEquals(fixture.customOptionId, audit.entityId)
        assertEquals(PRICE_AUDIT_PAYLOAD_KEYS, audit.payload.keys)
        assertEquals(fixture.venueId, audit.payload.longValue("venueId"))
        assertEquals(fixture.itemId, audit.payload.longValue("itemId"))
        assertEquals(fixture.customOptionId, audit.payload.longValue("optionId"))
        assertEquals(expectedOldPriceDeltaMinor, audit.payload.longValue("oldPriceDeltaMinor"))
        assertEquals(expectedNewPriceDeltaMinor, audit.payload.longValue("newPriceDeltaMinor"))
        assertEquals("VENUE_MINI_APP", audit.payload.getValue("source").jsonPrimitive.content)
    }

    private fun assertAvailabilityAudit(
        audit: AuditRow,
        fixture: Fixture,
        oldIsAvailable: Boolean,
        newIsAvailable: Boolean,
        actorUserId: Long,
        source: MenuOptionAvailabilitySource,
    ) {
        assertEquals(actorUserId, audit.actorUserId)
        assertEquals("menu_item_option", audit.entityType)
        assertEquals(fixture.customOptionId, audit.entityId)
        assertEquals(AVAILABILITY_AUDIT_PAYLOAD_KEYS, audit.payload.keys)
        assertEquals(fixture.venueId, audit.payload.longValue("venueId"))
        assertEquals(fixture.itemId, audit.payload.longValue("itemId"))
        assertEquals(fixture.customOptionId, audit.payload.longValue("optionId"))
        assertEquals(oldIsAvailable.toString(), audit.payload.getValue("oldIsAvailable").jsonPrimitive.content)
        assertEquals(newIsAvailable.toString(), audit.payload.getValue("newIsAvailable").jsonPrimitive.content)
        assertEquals(source.name, audit.payload.getValue("source").jsonPrimitive.content)
    }

    private fun assertItemAvailabilityAudit(
        audit: AuditRow,
        fixture: Fixture,
        oldIsAvailable: Boolean,
        newIsAvailable: Boolean,
        actorUserId: Long,
        source: MenuItemAvailabilitySource,
    ) {
        assertEquals(actorUserId, audit.actorUserId)
        assertEquals("menu_item", audit.entityType)
        assertEquals(fixture.itemId, audit.entityId)
        assertEquals(ITEM_AVAILABILITY_AUDIT_PAYLOAD_KEYS, audit.payload.keys)
        assertEquals(fixture.venueId, audit.payload.longValue("venueId"))
        assertEquals(fixture.itemId, audit.payload.longValue("itemId"))
        assertEquals(oldIsAvailable.toString(), audit.payload.getValue("oldIsAvailable").jsonPrimitive.content)
        assertEquals(newIsAvailable.toString(), audit.payload.getValue("newIsAvailable").jsonPrimitive.content)
        assertEquals(source.name, audit.payload.getValue("source").jsonPrimitive.content)
    }

    private fun assertMenuItemCreateCardinality(
        dataSource: DataSource,
        fixture: Fixture,
        initialItemIds: Set<Long>,
        expected: List<ExpectedItemCreateAudit>,
    ) {
        val committedItemIds =
            readMenuItems(dataSource, fixture.venueId, fixture.categoryId)
                .map { it.id }
                .filterNot { it in initialItemIds }
                .toSet()
        val audits = readMenuItemCreateAudits(dataSource)
        assertEquals(committedItemIds.size, audits.size)
        assertEquals(committedItemIds, audits.map { it.entityId }.toSet())
        assertEquals(audits.size, audits.map { it.entityId }.distinct().size)
        assertMenuItemCreateAudits(dataSource, fixture, expected)
    }

    private fun assertMenuItemCreateAudits(
        dataSource: DataSource,
        fixture: Fixture,
        expected: List<ExpectedItemCreateAudit>,
    ) {
        val audits = readMenuItemCreateAudits(dataSource)
        assertEquals(expected.size, audits.size)
        expected.forEachIndexed { index, expectedAudit ->
            val audit = audits[index]
            assertEquals(expectedAudit.actorUserId, audit.actorUserId)
            assertEquals("menu_item", audit.entityType)
            assertEquals(expectedAudit.itemId, audit.entityId)
            assertEquals(ITEM_CREATE_AUDIT_PAYLOAD_KEYS, audit.payload.keys)
            assertEquals(fixture.venueId, audit.payload.longValue("venueId"))
            assertEquals(expectedAudit.itemId, audit.payload.longValue("itemId"))
            assertEquals(expectedAudit.source.name, audit.payload.getValue("source").jsonPrimitive.content)
        }
    }

    private fun categoryExists(
        dataSource: DataSource,
        venueId: Long,
        categoryId: Long,
    ): Boolean =
        dataSource.connection.use { connection ->
            connection.prepareStatement(
                "SELECT 1 FROM menu_categories WHERE id = ? AND venue_id = ?",
            ).use { statement ->
                statement.setLong(1, categoryId)
                statement.setLong(2, venueId)
                statement.executeQuery().use { it.next() }
            }
        }

    private fun rowExists(
        connection: Connection,
        table: String,
        entityId: Long,
    ): Boolean =
        connection.prepareStatement("SELECT 1 FROM $table WHERE id = ?").use { statement ->
            statement.setLong(1, entityId)
            statement.executeQuery().use { it.next() }
        }

    private fun auditExists(
        connection: Connection,
        action: String,
        entityId: Long,
    ): Boolean =
        connection.prepareStatement(
            "SELECT 1 FROM audit_log WHERE action = ? AND entity_id = ?",
        ).use { statement ->
            statement.setString(1, action)
            statement.setLong(2, entityId)
            statement.executeQuery().use { it.next() }
        }

    private fun readMenuItems(
        dataSource: DataSource,
        venueId: Long,
        categoryId: Long,
    ): List<MenuItemRow> =
        dataSource.connection.use { connection ->
            connection.prepareStatement(
                """
                SELECT id, name, sort_order
                FROM menu_items
                WHERE venue_id = ? AND category_id = ?
                ORDER BY id
                """.trimIndent(),
            ).use { statement ->
                statement.setLong(1, venueId)
                statement.setLong(2, categoryId)
                statement.executeQuery().use { resultSet ->
                    buildList {
                        while (resultSet.next()) {
                            add(
                                MenuItemRow(
                                    id = resultSet.getLong("id"),
                                    name = resultSet.getString("name"),
                                    sortOrder = resultSet.getInt("sort_order"),
                                ),
                            )
                        }
                    }
                }
            }
        }

    private fun readItemOrder(
        dataSource: DataSource,
        venueId: Long,
        categoryId: Long,
    ): List<Long> =
        dataSource.connection.use { connection ->
            readItemOrder(connection, venueId, categoryId)
        }

    private fun readItemOrder(
        connection: Connection,
        venueId: Long,
        categoryId: Long,
    ): List<Long> =
        connection.prepareStatement(
            """
            SELECT id
            FROM menu_items
            WHERE venue_id = ? AND category_id = ?
            ORDER BY sort_order, id
            """.trimIndent(),
        ).use { statement ->
            statement.setLong(1, venueId)
            statement.setLong(2, categoryId)
            statement.executeQuery().use { resultSet ->
                buildList {
                    while (resultSet.next()) add(resultSet.getLong("id"))
                }
            }
        }

    private fun readMenuCategories(
        dataSource: DataSource,
        venueId: Long,
    ): List<MenuCategoryRow> =
        dataSource.connection.use { connection ->
            connection.prepareStatement(
                """
                SELECT id, name, sort_order, category_type
                FROM menu_categories
                WHERE venue_id = ?
                ORDER BY sort_order, id
                """.trimIndent(),
            ).use { statement ->
                statement.setLong(1, venueId)
                statement.executeQuery().use { resultSet ->
                    buildList {
                        while (resultSet.next()) {
                            add(
                                MenuCategoryRow(
                                    id = resultSet.getLong("id"),
                                    name = resultSet.getString("name"),
                                    sortOrder = resultSet.getInt("sort_order"),
                                    categoryType = resultSet.getString("category_type"),
                                ),
                            )
                        }
                    }
                }
            }
        }

    private fun readFullItem(
        dataSource: DataSource,
        itemId: Long,
    ): FullItemRow? =
        dataSource.connection.use { connection ->
            readFullItem(connection, itemId)
        }

    private fun readFullItem(
        connection: Connection,
        itemId: Long,
    ): FullItemRow? =
        connection.prepareStatement(
            """
            SELECT id, category_id, name, price_minor, currency, is_available,
                   item_type, sort_order, updated_at
            FROM menu_items
            WHERE id = ?
            """.trimIndent(),
        ).use { statement ->
            statement.setLong(1, itemId)
            statement.executeQuery().use { resultSet ->
                if (!resultSet.next()) {
                    null
                } else {
                    FullItemRow(
                        id = resultSet.getLong("id"),
                        categoryId = resultSet.getLong("category_id"),
                        name = resultSet.getString("name"),
                        priceMinor = resultSet.getLong("price_minor"),
                        currency = resultSet.getString("currency"),
                        isAvailable = resultSet.getBoolean("is_available"),
                        itemType = resultSet.getString("item_type"),
                        sortOrder = resultSet.getInt("sort_order"),
                        updatedAtMillis = resultSet.getTimestamp("updated_at").time,
                    )
                }
            }
        }

    private fun readItem(
        dataSource: DataSource,
        itemId: Long,
    ): ItemRow? =
        dataSource.connection.use { connection ->
            connection.prepareStatement(
                "SELECT name, is_available FROM menu_items WHERE id = ?",
            ).use { statement ->
                statement.setLong(1, itemId)
                statement.executeQuery().use { resultSet ->
                    if (resultSet.next()) {
                        ItemRow(
                            name = resultSet.getString("name"),
                            isAvailable = resultSet.getBoolean("is_available"),
                        )
                    } else {
                        null
                    }
                }
            }
        }

    private fun readOptions(
        dataSource: DataSource,
        itemId: Long,
    ): List<OptionRow> =
        dataSource.connection.use { connection ->
            connection.prepareStatement(
                """
                SELECT id, name, price_delta_minor, is_available
                FROM menu_item_options
                WHERE item_id = ?
                ORDER BY id
                """.trimIndent(),
            ).use { statement ->
                statement.setLong(1, itemId)
                statement.executeQuery().use { resultSet ->
                    buildList {
                        while (resultSet.next()) {
                            add(
                                OptionRow(
                                    id = resultSet.getLong("id"),
                                    name = resultSet.getString("name"),
                                    priceDeltaMinor = resultSet.getLong("price_delta_minor"),
                                    isAvailable = resultSet.getBoolean("is_available"),
                                ),
                            )
                        }
                    }
                }
            }
        }

    private fun readDeleteAudits(dataSource: DataSource): List<AuditRow> =
        readAudits(dataSource, MENU_OPTION_DELETED_AUDIT_ACTION)

    private fun readCreateAudits(dataSource: DataSource): List<AuditRow> =
        readAudits(dataSource, MENU_OPTION_CREATED_AUDIT_ACTION)

    private fun readRenameAudits(dataSource: DataSource): List<AuditRow> =
        readAudits(dataSource, MENU_OPTION_RENAMED_AUDIT_ACTION)

    private fun readPriceAudits(dataSource: DataSource): List<AuditRow> =
        readAudits(dataSource, MENU_OPTION_PRICE_CHANGED_AUDIT_ACTION)

    private fun readAvailabilityAudits(dataSource: DataSource): List<AuditRow> =
        readAudits(dataSource, MENU_OPTION_AVAILABILITY_CHANGED_AUDIT_ACTION)

    private fun readItemAvailabilityAudits(dataSource: DataSource): List<AuditRow> =
        readAudits(dataSource, MENU_ITEM_AVAILABILITY_CHANGED_AUDIT_ACTION)

    private fun readItemDeleteAudits(dataSource: DataSource): List<AuditRow> =
        readAudits(dataSource, MENU_ITEM_DELETED_AUDIT_ACTION)

    private fun readMenuItemCreateAudits(dataSource: DataSource): List<AuditRow> =
        readAudits(dataSource, MENU_ITEM_CREATED_AUDIT_ACTION)

    private fun readShiftCheckAudits(dataSource: DataSource): List<AuditRow> =
        readAudits(dataSource, MENU_SHIFT_CHECK_COMPLETED_AUDIT_ACTION)

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

    private fun countAuditRows(dataSource: DataSource): Int =
        dataSource.connection.use { connection ->
            countAuditRows(connection)
        }

    private fun countAuditRows(connection: Connection): Int =
        connection.createStatement().use { statement ->
            statement.executeQuery("SELECT COUNT(*) FROM audit_log").use { resultSet ->
                assertTrue(resultSet.next())
                resultSet.getInt(1)
            }
        }

    private fun readAuditActions(
        connection: Connection,
        entityId: Long,
    ): List<String> =
        connection.prepareStatement(
            "SELECT action FROM audit_log WHERE entity_id = ? ORDER BY id",
        ).use { statement ->
            statement.setLong(1, entityId)
            statement.executeQuery().use { resultSet ->
                buildList {
                    while (resultSet.next()) add(resultSet.getString("action"))
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

    private fun String.isItemDeleteMutationLock(): Boolean {
        val normalized = normalizedSql()
        return normalized.contains("from menu_items") &&
            normalized.contains("for update nowait")
    }

    private fun String.isCategoryOrderMutationLock(): Boolean {
        val normalized = normalizedSql()
        return normalized.contains("from menu_categories") &&
            normalized.contains("for update") &&
            !normalized.contains("nowait")
    }

    private fun String.isMenuCategoryOrderScopeLock(): Boolean =
        normalizedSql().startsWith("select pg_advisory_xact_lock(")

    private fun String.isCategoryDeleteMutationLock(): Boolean {
        val normalized = normalizedSql()
        return normalized.contains("from menu_categories") &&
            normalized.contains("for update nowait")
    }

    private fun String.normalizedSql(): String =
        trim()
            .replace(WHITESPACE, " ")
            .lowercase()

    private interface HeldItemLockCoordinator {
        val itemLockAcquired: CountDownLatch
        val allowMutation: CountDownLatch
        val backendPid: AtomicInteger
    }

    private interface HeldCategoryLockCoordinator {
        val categoryLockAcquired: CountDownLatch
        val allowMutation: CountDownLatch
        val backendPid: AtomicInteger
    }

    private inner class HeldCategoryLockDataSource(
        private val delegate: DataSource,
        private val lockPredicate: (String) -> Boolean = { it.isCategoryOrderMutationLock() },
    ) : DataSource by delegate,
        HeldCategoryLockCoordinator {
        override val categoryLockAcquired = CountDownLatch(1)
        override val allowMutation = CountDownLatch(1)
        override val backendPid = AtomicInteger()
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
                    if (lockPredicate(sql) && held.compareAndSet(false, true)) {
                        return object : PreparedStatement by prepared {
                            override fun executeQuery(): ResultSet {
                                check(!connection.autoCommit) {
                                    "Menu category lock must be held inside the production transaction"
                                }
                                val resultSet = prepared.executeQuery()
                                categoryLockAcquired.countDown()
                                if (!allowMutation.await(WAIT_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                                    resultSet.close()
                                    throw SQLException("Timed out while holding menu category lock")
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

    private inner class TrackedCategoryLockDataSource(
        private val delegate: DataSource,
        private val lockPredicate: (String) -> Boolean = { it.isCategoryOrderMutationLock() },
    ) : DataSource by delegate {
        val categoryLockAttempted = CountDownLatch(1)
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
                    if (lockPredicate(sql)) {
                        return object : PreparedStatement by prepared {
                            override fun executeQuery(): ResultSet {
                                if (signalled.compareAndSet(false, true)) {
                                    categoryLockAttempted.countDown()
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

    private inner class TrackedCategoryDeleteLockDataSource(
        private val delegate: DataSource,
    ) : DataSource by delegate {
        val categoryDeleteLockAttempted = CountDownLatch(1)
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
                    if (sql.isCategoryDeleteMutationLock()) {
                        return object : PreparedStatement by prepared {
                            override fun executeQuery(): ResultSet {
                                if (signalled.compareAndSet(false, true)) {
                                    categoryDeleteLockAttempted.countDown()
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

    private inner class HeldItemLockDataSource(
        private val delegate: DataSource,
    ) : DataSource by delegate,
        HeldItemLockCoordinator {
        override val itemLockAcquired = CountDownLatch(1)
        override val allowMutation = CountDownLatch(1)
        override val backendPid = AtomicInteger()
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

    private inner class HeldItemDeleteLockDataSource(
        private val delegate: DataSource,
    ) : DataSource by delegate,
        HeldItemLockCoordinator {
        override val itemLockAcquired = CountDownLatch(1)
        override val allowMutation = CountDownLatch(1)
        override val backendPid = AtomicInteger()
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
                    if (sql.isItemDeleteMutationLock() && held.compareAndSet(false, true)) {
                        return object : PreparedStatement by prepared {
                            override fun executeQuery(): ResultSet {
                                check(!connection.autoCommit) {
                                    "Menu item delete lock must be held inside the production transaction"
                                }
                                val resultSet = prepared.executeQuery()
                                itemLockAcquired.countDown()
                                if (!allowMutation.await(WAIT_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                                    resultSet.close()
                                    throw SQLException("Timed out while holding menu item delete lock")
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
        val categoryId: Long,
        val itemId: Long,
        val obsoleteOptionIds: Set<Long>,
        val customOptionId: Long,
        val existingCanonicalOptionId: Long,
    )

    private val Fixture.initialOptionIds: Set<Long>
        get() = obsoleteOptionIds + customOptionId + existingCanonicalOptionId

    private data class OptionRow(
        val id: Long,
        val name: String,
        val priceDeltaMinor: Long,
        val isAvailable: Boolean,
    )

    private data class ItemRow(
        val name: String,
        val isAvailable: Boolean,
    )

    private data class MenuItemRow(
        val id: Long,
        val name: String,
        val sortOrder: Int,
    )

    private data class MenuCategoryRow(
        val id: Long,
        val name: String,
        val sortOrder: Int,
        val categoryType: String,
    )

    private data class FullItemRow(
        val id: Long,
        val categoryId: Long,
        val name: String,
        val priceMinor: Long,
        val currency: String,
        val isAvailable: Boolean,
        val itemType: String?,
        val sortOrder: Int,
        val updatedAtMillis: Long,
    )

    private data class AuditRow(
        val actorUserId: Long,
        val entityType: String,
        val entityId: Long,
        val payload: JsonObject,
    )

    private data class ExpectedCreateAudit(
        val optionId: Long,
        val actorUserId: Long,
        val source: MenuOptionCreateSource,
    )

    private data class ExpectedItemCreateAudit(
        val itemId: Long,
        val actorUserId: Long,
        val source: MenuItemCreateSource,
    )

    private data class PostgresBlockObservation(
        val blocked: Boolean,
        val diagnostic: String,
    )

    private companion object {
        const val FIRST_ACTOR_ID = 97_001L
        const val SECOND_ACTOR_ID = 97_002L
        const val INITIAL_ITEM_NAME = "Concurrency hookah"
        const val COMPOUND_ITEM_NAME = "Concurrency compound item"
        const val CUSTOM_OPTION_NAME = "Авторский микс"
        const val FIRST_CUSTOM_CREATE_NAME = "Авторский цитрус"
        const val SECOND_CUSTOM_CREATE_NAME = "Авторская ягода"
        const val FIRST_ITEM_CREATE_NAME = "Concurrency item one"
        const val SECOND_ITEM_CREATE_NAME = "Concurrency item two"
        const val FAILED_ITEM_CREATE_NAME = "Concurrency item rolled back"
        const val WAIT_TIMEOUT_SECONDS = 30L

        val AUDIT_PAYLOAD_KEYS = setOf("venueId", "itemId", "optionId", "source")
        val RENAME_AUDIT_PAYLOAD_KEYS = setOf("venueId", "itemId", "optionId", "oldName", "newName", "source")
        val PRICE_AUDIT_PAYLOAD_KEYS =
            setOf(
                "venueId",
                "itemId",
                "optionId",
                "oldPriceDeltaMinor",
                "newPriceDeltaMinor",
                "source",
            )
        val AVAILABILITY_AUDIT_PAYLOAD_KEYS =
            setOf("venueId", "itemId", "optionId", "oldIsAvailable", "newIsAvailable", "source")
        val ITEM_AVAILABILITY_AUDIT_PAYLOAD_KEYS =
            setOf("venueId", "itemId", "oldIsAvailable", "newIsAvailable", "source")
        val ITEM_CREATE_AUDIT_PAYLOAD_KEYS = setOf("venueId", "itemId", "source")
        val WHITESPACE = Regex("\\s+")
    }
}
