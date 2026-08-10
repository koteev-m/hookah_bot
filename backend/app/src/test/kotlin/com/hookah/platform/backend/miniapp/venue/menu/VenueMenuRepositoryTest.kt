package com.hookah.platform.backend.miniapp.venue.menu

import com.hookah.platform.backend.api.DatabaseUnavailableException
import com.hookah.platform.backend.api.InvalidInputException
import com.hookah.platform.backend.api.MENU_ITEM_DELETE_BLOCKED_BY_FIXED_REWARD_MESSAGE
import com.hookah.platform.backend.api.MenuItemDeleteBlockedByFixedRewardException
import com.hookah.platform.backend.api.MenuShiftCheckStaleException
import com.hookah.platform.backend.api.NotFoundException
import com.hookah.platform.backend.miniapp.venue.AuditLogRepository
import com.hookah.platform.backend.miniapp.venue.TransactionalAuditLogWriter
import com.hookah.platform.backend.miniapp.venue.VenueStatus
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.flywaydb.core.Flyway
import org.h2.jdbcx.JdbcDataSource
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.sql.Connection
import java.sql.DriverManager
import java.sql.PreparedStatement
import java.sql.SQLException
import java.sql.Statement
import java.util.HexFormat
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import javax.sql.DataSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class VenueMenuRepositoryTest {
    @Test
    fun `category and item semantic types can be read and updated`() =
        runBlocking {
            val jdbcUrl = migratedJdbcUrl("venue-menu-semantic-types")
            val venueId = seedVenue(jdbcUrl)
            val repository = VenueMenuRepository(dataSource(jdbcUrl))

            val category = repository.createCategory(venueId, "Кальянное меню")
            assertEquals(MenuSemanticType.OTHER, category.categoryType)

            val typedCategory = repository.updateCategoryType(venueId, category.id, MenuSemanticType.HOOKAH)
            assertEquals(MenuSemanticType.HOOKAH, typedCategory?.categoryType)

            val item =
                repository.createItem(
                    venueId = venueId,
                    categoryId = category.id,
                    name = "Кальян обычный",
                    priceMinor = 110_000,
                    currency = "RUB",
                    isAvailable = true,
                )
            assertNull(item?.itemType)

            val menuWithInheritedType = repository.getMenu(venueId).single()
            val inheritedItem = menuWithInheritedType.items.single()
            assertEquals(MenuSemanticType.HOOKAH, inheritedItem.effectiveType(menuWithInheritedType))

            val typedItem = repository.updateItemType(venueId, inheritedItem.id, MenuSemanticType.DRINK)
            assertEquals(MenuSemanticType.DRINK, typedItem?.itemType)

            val resetItem = repository.updateItemType(venueId, inheritedItem.id, null)
            assertNull(resetItem?.itemType)
        }

    @Test
    fun `shift check applies item and option changes and audits no-op completion`() =
        runBlocking {
            val jdbcUrl = migratedJdbcUrl("venue-menu-shift-check-success")
            val venueId = seedVenue(jdbcUrl)
            val dataSource = dataSource(jdbcUrl)
            val repository = VenueMenuRepository(dataSource)
            val auditRepository = AuditLogRepository(dataSource, Json)
            val fixture = createMenuFixture(repository, venueId)

            val result =
                repository.completeShiftCheck(
                    venueId = venueId,
                    actorUserId = 101,
                    itemChanges =
                        listOf(
                            MenuShiftCheckItemChange(
                                itemId = fixture.firstItem.id,
                                expectedIsAvailable = true,
                                desiredIsAvailable = false,
                            ),
                        ),
                    optionChanges =
                        listOf(
                            MenuShiftCheckOptionChange(
                                optionId = fixture.secondOption.id,
                                itemId = fixture.secondItem.id,
                                expectedIsAvailable = true,
                                desiredIsAvailable = false,
                            ),
                        ),
                    auditLogRepository = auditRepository,
                )

            assertEquals(1, result.changedItemCount)
            assertEquals(1, result.changedOptionCount)
            assertEquals(2, result.reviewedItemCount)
            assertEquals(2, result.reviewedOptionCount)
            assertEquals(1, result.availableItemCount)
            assertEquals(1, result.availableOptionCount)
            val savedItems = result.categories.flatMap { it.items }.associateBy { it.id }
            assertFalse(savedItems.getValue(fixture.firstItem.id).isAvailable)
            assertFalse(
                savedItems
                    .getValue(fixture.secondItem.id)
                    .options
                    .single { it.id == fixture.secondOption.id }
                    .isAvailable,
            )

            val firstAudit = auditPayloads(jdbcUrl).single()
            assertEquals(
                setOf(
                    "actorUserId",
                    "venueId",
                    "changedItemCount",
                    "changedOptionCount",
                    "itemsMadeAvailable",
                    "itemsMadeUnavailable",
                    "optionsMadeAvailable",
                    "optionsMadeUnavailable",
                    "reviewedItemCount",
                    "reviewedOptionCount",
                ),
                firstAudit.keys,
            )
            assertEquals(101, firstAudit.getValue("actorUserId").jsonPrimitive.int)
            assertEquals(1, firstAudit.getValue("changedItemCount").jsonPrimitive.int)
            assertEquals(1, firstAudit.getValue("changedOptionCount").jsonPrimitive.int)
            assertEquals(
                listOf(fixture.firstItem.id),
                firstAudit.getValue("itemsMadeUnavailable").jsonArray.map { it.jsonPrimitive.content.toLong() },
            )
            assertEquals(
                listOf(fixture.secondOption.id),
                firstAudit.getValue("optionsMadeUnavailable").jsonArray.map { it.jsonPrimitive.content.toLong() },
            )
            assertFalse(firstAudit.toString().contains(fixture.firstItem.name))
            assertFalse(firstAudit.toString().contains(fixture.secondOption.name))
            assertFalse(firstAudit.toString().contains("price", ignoreCase = true))
            assertFalse(firstAudit.toString().contains("telegram", ignoreCase = true))

            val noOp =
                repository.completeShiftCheck(
                    venueId = venueId,
                    actorUserId = 101,
                    itemChanges = emptyList(),
                    optionChanges = emptyList(),
                    auditLogRepository = auditRepository,
                )
            assertEquals(0, noOp.changedItemCount)
            assertEquals(0, noOp.changedOptionCount)
            val noOpAudit = auditPayloads(jdbcUrl).last()
            assertEquals(2, auditPayloads(jdbcUrl).size)
            assertEquals(0, noOpAudit.getValue("changedItemCount").jsonPrimitive.int)
            assertEquals(0, noOpAudit.getValue("changedOptionCount").jsonPrimitive.int)
        }

    @Test
    fun `shift check rejects duplicate missing foreign mismatched and oversized ids without writes or audit`() =
        runBlocking {
            val jdbcUrl = migratedJdbcUrl("venue-menu-shift-check-invalid")
            val venueId = seedVenue(jdbcUrl)
            val foreignVenueId = seedVenue(jdbcUrl)
            val dataSource = dataSource(jdbcUrl)
            val repository = VenueMenuRepository(dataSource)
            val auditRepository = AuditLogRepository(dataSource, Json)
            val own = createMenuFixture(repository, venueId)
            val foreign = createMenuFixture(repository, foreignVenueId)

            suspend fun assertMenuUnchanged() {
                val menu = repository.getMenu(venueId)
                assertTrue(menu.flatMap { it.items }.all { it.isAvailable })
                assertTrue(menu.flatMap { it.items }.flatMap { it.options }.all { it.isAvailable })
                assertEquals(0, auditPayloads(jdbcUrl).size)
            }

            assertFailsWith<InvalidInputException> {
                repository.completeShiftCheck(
                    venueId,
                    101,
                    listOf(
                        itemChange(own.firstItem.id),
                        itemChange(own.firstItem.id),
                    ),
                    emptyList(),
                    auditRepository,
                )
            }
            assertMenuUnchanged()

            assertFailsWith<InvalidInputException> {
                repository.completeShiftCheck(
                    venueId,
                    101,
                    emptyList(),
                    listOf(
                        optionChange(own.firstOption),
                        optionChange(own.firstOption),
                    ),
                    auditRepository,
                )
            }
            assertMenuUnchanged()

            assertFailsWith<NotFoundException> {
                repository.completeShiftCheck(
                    venueId,
                    101,
                    listOf(itemChange(own.firstItem.id), itemChange(Long.MAX_VALUE)),
                    emptyList(),
                    auditRepository,
                )
            }
            assertMenuUnchanged()

            assertFailsWith<NotFoundException> {
                repository.completeShiftCheck(
                    venueId,
                    101,
                    listOf(itemChange(own.firstItem.id)),
                    listOf(
                        MenuShiftCheckOptionChange(
                            optionId = Long.MAX_VALUE,
                            itemId = own.secondItem.id,
                            expectedIsAvailable = true,
                            desiredIsAvailable = false,
                        ),
                    ),
                    auditRepository,
                )
            }
            assertMenuUnchanged()

            assertFailsWith<NotFoundException> {
                repository.completeShiftCheck(
                    venueId,
                    101,
                    listOf(itemChange(own.firstItem.id), itemChange(foreign.firstItem.id)),
                    emptyList(),
                    auditRepository,
                )
            }
            assertMenuUnchanged()

            assertFailsWith<NotFoundException> {
                repository.completeShiftCheck(
                    venueId,
                    101,
                    listOf(itemChange(own.firstItem.id)),
                    listOf(
                        MenuShiftCheckOptionChange(
                            optionId = foreign.firstOption.id,
                            itemId = own.secondItem.id,
                            expectedIsAvailable = true,
                            desiredIsAvailable = false,
                        ),
                    ),
                    auditRepository,
                )
            }
            assertMenuUnchanged()

            assertFailsWith<InvalidInputException> {
                repository.completeShiftCheck(
                    venueId,
                    101,
                    emptyList(),
                    listOf(
                        MenuShiftCheckOptionChange(
                            optionId = own.firstOption.id,
                            itemId = own.secondItem.id,
                            expectedIsAvailable = true,
                            desiredIsAvailable = false,
                        ),
                    ),
                    auditRepository,
                )
            }
            assertMenuUnchanged()

            assertFailsWith<InvalidInputException> {
                repository.completeShiftCheck(
                    venueId,
                    101,
                    (1L..501L).map { itemChange(10_000L + it) },
                    emptyList(),
                    auditRepository,
                )
            }
            assertMenuUnchanged()
        }

    @Test
    fun `stale expected availability rejects the whole shift check`() =
        runBlocking {
            val jdbcUrl = migratedJdbcUrl("venue-menu-shift-check-stale")
            val venueId = seedVenue(jdbcUrl)
            val dataSource = dataSource(jdbcUrl)
            val repository = VenueMenuRepository(dataSource)
            val auditRepository = AuditLogRepository(dataSource, Json)
            val fixture = createMenuFixture(repository, venueId)
            repository.setOptionAvailability(venueId, fixture.secondOption.id, false)

            assertFailsWith<MenuShiftCheckStaleException> {
                repository.completeShiftCheck(
                    venueId = venueId,
                    actorUserId = 101,
                    itemChanges = listOf(itemChange(fixture.firstItem.id)),
                    optionChanges =
                        listOf(
                            MenuShiftCheckOptionChange(
                                optionId = fixture.secondOption.id,
                                itemId = fixture.secondItem.id,
                                expectedIsAvailable = true,
                                desiredIsAvailable = false,
                            ),
                        ),
                    auditLogRepository = auditRepository,
                )
            }

            val savedItems = repository.getMenu(venueId).flatMap { it.items }.associateBy { it.id }
            assertTrue(savedItems.getValue(fixture.firstItem.id).isAvailable)
            assertFalse(
                savedItems
                    .getValue(fixture.secondItem.id)
                    .options
                    .single { it.id == fixture.secondOption.id }
                    .isAvailable,
            )
            assertEquals(0, auditPayloads(jdbcUrl).size)
        }

    @Test
    fun `audit failure rolls back shift check availability writes`() =
        runBlocking {
            val jdbcUrl = migratedJdbcUrl("venue-menu-shift-check-audit-rollback")
            val venueId = seedVenue(jdbcUrl)
            val dataSource = dataSource(jdbcUrl)
            val repository = VenueMenuRepository(dataSource)
            val auditRepository = AuditLogRepository(dataSource, Json)
            val fixture = createMenuFixture(repository, venueId)
            DriverManager.getConnection(jdbcUrl, "sa", "").use { connection ->
                connection.createStatement().use { statement ->
                    statement.execute(
                        """
                        ALTER TABLE audit_log
                        ADD CONSTRAINT reject_shift_check_audit
                        CHECK (action <> '$MENU_SHIFT_CHECK_COMPLETED_AUDIT_ACTION')
                        """.trimIndent(),
                    )
                }
            }

            assertFailsWith<DatabaseUnavailableException> {
                repository.completeShiftCheck(
                    venueId = venueId,
                    actorUserId = 101,
                    itemChanges = listOf(itemChange(fixture.firstItem.id)),
                    optionChanges = emptyList(),
                    auditLogRepository = auditRepository,
                )
            }

            assertTrue(repository.getMenu(venueId).flatMap { it.items }.first().isAvailable)
            assertEquals(0, auditPayloads(jdbcUrl).size)
        }

    @Test
    fun `option delete writes one exact safe audit and repeat missing foreign write none`() =
        runBlocking {
            val jdbcUrl = migratedJdbcUrl("venue-menu-option-delete-success")
            val venueId = seedVenue(jdbcUrl)
            val foreignVenueId = seedVenue(jdbcUrl)
            val repository = VenueMenuRepository(dataSource(jdbcUrl))
            val own = createMenuFixture(repository, venueId)
            val foreign = createMenuFixture(repository, foreignVenueId)

            assertTrue(
                repository.deleteOption(
                    venueId = venueId,
                    optionId = own.firstOption.id,
                    actorUserId = AUDIT_ACTOR_ID,
                    source = MenuOptionDeleteSource.VENUE_MINI_APP,
                ),
            )

            assertFalse(repository.optionExists(venueId, own.firstOption.id))
            val audit = menuOptionDeleteAudits(jdbcUrl).single()
            assertMenuOptionDeleteAudit(
                audit = audit,
                venueId = venueId,
                itemId = own.firstItem.id,
                optionId = own.firstOption.id,
                actorUserId = AUDIT_ACTOR_ID,
                source = MenuOptionDeleteSource.VENUE_MINI_APP,
            )
            val serialized = audit.payload.toString()
            assertFalse(serialized.contains(own.firstItem.name))
            assertFalse(serialized.contains(own.firstOption.name))
            listOf(
                "name",
                "price",
                "media",
                "order",
                "cart",
                "request",
                "callback",
                "initData",
                "username",
                "firstName",
                "lastName",
                "phone",
                "secret",
            ).forEach { forbidden ->
                assertFalse(serialized.contains(forbidden, ignoreCase = true), forbidden)
            }

            repeat(2) {
                assertFalse(
                    repository.deleteOption(
                        venueId = venueId,
                        optionId = own.firstOption.id,
                        actorUserId = AUDIT_ACTOR_ID,
                        source = MenuOptionDeleteSource.VENUE_MINI_APP,
                    ),
                )
            }
            assertFalse(
                repository.deleteOption(
                    venueId = venueId,
                    optionId = Long.MAX_VALUE,
                    actorUserId = AUDIT_ACTOR_ID,
                    source = MenuOptionDeleteSource.VENUE_MINI_APP,
                ),
            )
            assertFalse(
                repository.deleteOption(
                    venueId = venueId,
                    optionId = foreign.firstOption.id,
                    actorUserId = AUDIT_ACTOR_ID,
                    source = MenuOptionDeleteSource.VENUE_MINI_APP,
                ),
            )
            assertTrue(repository.optionExists(foreignVenueId, foreign.firstOption.id))
            assertEquals(listOf(audit), menuOptionDeleteAudits(jdbcUrl))
        }

    @Test
    fun `option delete audit failure after physical delete rolls back option and audit`() =
        runBlocking {
            val jdbcUrl = migratedJdbcUrl("venue-menu-option-delete-audit-rollback")
            val venueId = seedVenue(jdbcUrl)
            val dataSource = dataSource(jdbcUrl)
            val fixtureRepository = VenueMenuRepository(dataSource)
            val fixture = createMenuFixture(fixtureRepository, venueId)
            val delegateAuditWriter = AuditLogRepository(dataSource, Json)
            var deleteObserved = false
            var auditInsertCompleted = false
            val failingAuditWriter =
                TransactionalAuditLogWriter { connection, actorUserId, action, entityType, entityId, payload ->
                    if (action == MENU_OPTION_DELETED_AUDIT_ACTION) {
                        deleteObserved = !optionExists(connection, venueId, fixture.firstOption.id)
                        check(deleteObserved) { "Option delete must happen before its audit insert" }
                        delegateAuditWriter.appendJson(
                            connection,
                            actorUserId,
                            action,
                            entityType,
                            entityId,
                            payload,
                        )
                        auditInsertCompleted = true
                        throw SQLException("Synthetic menu option delete audit failure", "XX999")
                    }
                    delegateAuditWriter.appendJson(
                        connection,
                        actorUserId,
                        action,
                        entityType,
                        entityId,
                        payload,
                    )
                }
            val repository = VenueMenuRepository(dataSource, failingAuditWriter)

            assertFailsWith<DatabaseUnavailableException> {
                repository.deleteOption(
                    venueId = venueId,
                    optionId = fixture.firstOption.id,
                    actorUserId = AUDIT_ACTOR_ID,
                    source = MenuOptionDeleteSource.VENUE_MINI_APP,
                )
            }

            assertTrue(deleteObserved)
            assertTrue(auditInsertCompleted)
            assertTrue(fixtureRepository.optionExists(venueId, fixture.firstOption.id))
            assertTrue(menuOptionDeleteAudits(jdbcUrl).isEmpty())
        }

    @Test
    fun `option update audits one real compound rename and no-op non-name missing foreign write none`() =
        runBlocking {
            val jdbcUrl = migratedJdbcUrl("venue-menu-option-rename-success")
            val venueId = seedVenue(jdbcUrl)
            val foreignVenueId = seedVenue(jdbcUrl)
            val repository = VenueMenuRepository(dataSource(jdbcUrl))
            val own = createMenuFixture(repository, venueId)
            val foreign = createMenuFixture(repository, foreignVenueId)

            val renamed =
                requireNotNull(
                    repository.updateOption(
                        venueId = venueId,
                        optionId = own.firstOption.id,
                        name = "Renamed option",
                        priceDeltaMinor = 125,
                        isAvailable = false,
                        actorUserId = AUDIT_ACTOR_ID,
                        source = MenuOptionRenameSource.VENUE_MINI_APP,
                    ),
                )
            assertEquals("Renamed option", renamed.name)
            assertEquals(125L, renamed.priceDeltaMinor)
            assertFalse(renamed.isAvailable)

            val audit = menuOptionRenameAudits(jdbcUrl).single()
            assertMenuOptionRenameAudit(
                audit = audit,
                venueId = venueId,
                itemId = own.firstItem.id,
                optionId = own.firstOption.id,
                oldName = own.firstOption.name,
                newName = renamed.name,
                actorUserId = AUDIT_ACTOR_ID,
                source = MenuOptionRenameSource.VENUE_MINI_APP,
            )
            val serialized = audit.payload.toString()
            listOf(
                "price",
                "availability",
                "canonicalKey",
                "media",
                "order",
                "cart",
                "request",
                "callback",
                "initData",
                "telegramUserId",
                "username",
                "firstName",
                "lastName",
                "phone",
                "secret",
            ).forEach { forbidden ->
                assertFalse(serialized.contains(forbidden, ignoreCase = true), forbidden)
            }

            val repeated =
                requireNotNull(
                    repository.updateOption(
                        venueId = venueId,
                        optionId = own.firstOption.id,
                        name = renamed.name,
                        priceDeltaMinor = 130,
                        isAvailable = null,
                        actorUserId = AUDIT_ACTOR_ID,
                        source = MenuOptionRenameSource.VENUE_MINI_APP,
                    ),
                )
            assertEquals(130L, repeated.priceDeltaMinor)
            requireNotNull(
                repository.updateOption(
                    venueId = venueId,
                    optionId = own.firstOption.id,
                    name = null,
                    priceDeltaMinor = null,
                    isAvailable = true,
                    actorUserId = AUDIT_ACTOR_ID,
                    source = MenuOptionRenameSource.VENUE_MINI_APP,
                ),
            )
            assertNull(
                repository.updateOption(
                    venueId = venueId,
                    optionId = Long.MAX_VALUE,
                    name = "Missing option",
                    priceDeltaMinor = null,
                    isAvailable = null,
                    actorUserId = AUDIT_ACTOR_ID,
                    source = MenuOptionRenameSource.VENUE_MINI_APP,
                ),
            )
            assertNull(
                repository.updateOption(
                    venueId = venueId,
                    optionId = foreign.firstOption.id,
                    name = "Foreign option",
                    priceDeltaMinor = null,
                    isAvailable = null,
                    actorUserId = AUDIT_ACTOR_ID,
                    source = MenuOptionRenameSource.VENUE_MINI_APP,
                ),
            )
            val savedForeign =
                loadItem(repository, foreignVenueId, foreign.firstItem.id).options.single {
                    it.id == foreign.firstOption.id
                }
            assertEquals(foreign.firstOption, savedForeign)
            assertEquals(listOf(audit), menuOptionRenameAudits(jdbcUrl))
        }

    @Test
    fun `option rename collision preserves locked row and writes zero audit`() =
        runBlocking {
            val jdbcUrl = migratedJdbcUrl("venue-menu-option-rename-collision")
            val venueId = seedVenue(jdbcUrl)
            val repository = VenueMenuRepository(dataSource(jdbcUrl))
            val fixture = createNormalizationFixture(repository, venueId)
            val custom = fixture.options.single { it.name == "Авторский микс" }
            val before = optionRows(jdbcUrl, fixture.item.id)

            assertFailsWith<InvalidInputException> {
                repository.updateOption(
                    venueId = venueId,
                    optionId = custom.id,
                    name = "ЯГОДНЫЙ",
                    priceDeltaMinor = 999,
                    isAvailable = false,
                    actorUserId = AUDIT_ACTOR_ID,
                    source = MenuOptionRenameSource.VENUE_MINI_APP,
                )
            }

            assertEquals(before, optionRows(jdbcUrl, fixture.item.id))
            assertTrue(menuOptionRenameAudits(jdbcUrl).isEmpty())
        }

    @Test
    fun `option rename audit failure after compound update restores every field and audit`() =
        runBlocking {
            val jdbcUrl = migratedJdbcUrl("venue-menu-option-rename-audit-rollback")
            val venueId = seedVenue(jdbcUrl)
            val dataSource = dataSource(jdbcUrl)
            val fixtureRepository = VenueMenuRepository(dataSource)
            val fixture = createMenuFixture(fixtureRepository, venueId)
            val original = fixture.firstOption
            val delegateAuditWriter = AuditLogRepository(dataSource, Json)
            var updatedRowObserved = false
            val failingAuditWriter =
                TransactionalAuditLogWriter { connection, actorUserId, action, entityType, entityId, payload ->
                    if (action == MENU_OPTION_RENAMED_AUDIT_ACTION) {
                        val current = loadOptionSnapshot(connection, venueId, original.id)
                        updatedRowObserved =
                            current?.name == "Rollback private name" &&
                            current.priceDeltaMinor == 999L &&
                            !current.isAvailable
                        check(updatedRowObserved) { "Compound option update must happen before rename audit" }
                        delegateAuditWriter.appendJson(
                            connection,
                            actorUserId,
                            action,
                            entityType,
                            entityId,
                            payload,
                        )
                        throw SQLException("Synthetic menu option rename audit failure", "XX999")
                    }
                    delegateAuditWriter.appendJson(connection, actorUserId, action, entityType, entityId, payload)
                }
            val repository = VenueMenuRepository(dataSource, failingAuditWriter)

            assertFailsWith<DatabaseUnavailableException> {
                repository.updateOption(
                    venueId = venueId,
                    optionId = original.id,
                    name = "Rollback private name",
                    priceDeltaMinor = 999,
                    isAvailable = false,
                    actorUserId = AUDIT_ACTOR_ID,
                    source = MenuOptionRenameSource.VENUE_MINI_APP,
                )
            }

            assertTrue(updatedRowObserved)
            val restored = loadItem(fixtureRepository, venueId, original.itemId).options.single { it.id == original.id }
            assertEquals(original, restored)
            assertTrue(menuOptionRenameAudits(jdbcUrl).isEmpty())
        }

    @Test
    fun `normalization preserves current outcome audits every delete and repeat is no-op`() =
        runBlocking {
            val jdbcUrl = migratedJdbcUrl("venue-menu-option-normalization-success")
            val venueId = seedVenue(jdbcUrl)
            val repository = VenueMenuRepository(dataSource(jdbcUrl))
            val fixture = createNormalizationFixture(repository, venueId)
            val obsoleteOptions =
                fixture.options.filter { HookahFlavorProfileService.isObsoleteProfileValue(it.name) }

            val result =
                requireNotNull(
                    repository.normalizeHookahFlavorProfiles(
                        venueId = venueId,
                        itemId = fixture.item.id,
                        actorUserId = AUDIT_ACTOR_ID,
                        source = MenuOptionDeleteSource.TELEGRAM_BOT,
                    ),
                )

            assertEquals(5, result.removedCount)
            assertEquals(6, result.addedCount)
            val normalized = loadItem(repository, venueId, fixture.item.id)
            assertEquals(
                listOf(
                    "Освежающий / мятный",
                    "Авторский микс",
                    "Ягодный",
                    "Фруктовый",
                    "Цитрусовый",
                    "Десертный",
                    "Напиточный",
                    "Пряный",
                    "Цветочный",
                ),
                normalized.options.map { it.name },
            )
            assertEquals((4..12).toList(), normalized.options.map { it.sortOrder })
            val refreshing = normalized.options.single { it.name == "Освежающий / мятный" }
            assertEquals(500L, refreshing.priceDeltaMinor)
            assertFalse(refreshing.isAvailable)
            val custom = normalized.options.single { it.name == "Авторский микс" }
            assertEquals(600L, custom.priceDeltaMinor)
            assertTrue(custom.isAvailable)
            val berry = normalized.options.single { it.name == "Ягодный" }
            assertEquals(700L, berry.priceDeltaMinor)
            assertTrue(berry.isAvailable)
            normalized.options
                .filter { it.id !in fixture.options.map(VenueMenuOption::id) }
                .forEach { created ->
                    assertEquals(0L, created.priceDeltaMinor)
                    assertTrue(created.isAvailable)
                }

            val audits = menuOptionDeleteAudits(jdbcUrl)
            assertEquals(obsoleteOptions.map { it.id }.sorted(), audits.map { it.entityId })
            audits.forEach { audit ->
                assertMenuOptionDeleteAudit(
                    audit = audit,
                    venueId = venueId,
                    itemId = fixture.item.id,
                    optionId = audit.entityId,
                    actorUserId = AUDIT_ACTOR_ID,
                    source = MenuOptionDeleteSource.TELEGRAM_BOT,
                )
            }

            val beforeNoOp = optionRows(jdbcUrl, fixture.item.id)
            val noOp =
                requireNotNull(
                    repository.normalizeHookahFlavorProfiles(
                        venueId = venueId,
                        itemId = fixture.item.id,
                        actorUserId = AUDIT_ACTOR_ID,
                        source = MenuOptionDeleteSource.TELEGRAM_BOT,
                    ),
                )
            assertEquals(HookahFlavorProfileNormalizationResult(removedCount = 0, addedCount = 0), noOp)
            assertEquals(beforeNoOp, optionRows(jdbcUrl, fixture.item.id))
            assertEquals(audits, menuOptionDeleteAudits(jdbcUrl))
        }

    @Test
    fun `normalization rechecks current hookah scope before changing options`() =
        runBlocking {
            val jdbcUrl = migratedJdbcUrl("venue-menu-option-normalization-scope")
            val venueId = seedVenue(jdbcUrl)
            val repository = VenueMenuRepository(dataSource(jdbcUrl))
            val fixture = createMenuFixture(repository, venueId)
            val before = optionRows(jdbcUrl, fixture.firstItem.id)

            assertFailsWith<InvalidInputException> {
                repository.normalizeHookahFlavorProfiles(
                    venueId = venueId,
                    itemId = fixture.firstItem.id,
                    actorUserId = AUDIT_ACTOR_ID,
                    source = MenuOptionDeleteSource.TELEGRAM_BOT,
                )
            }

            assertEquals(before, optionRows(jdbcUrl, fixture.firstItem.id))
            assertTrue(menuOptionDeleteAudits(jdbcUrl).isEmpty())
        }

    @Test
    fun `canonical collision guard is hookah scoped and ignores unchanged legacy duplicates`() =
        runBlocking {
            val jdbcUrl = migratedJdbcUrl("venue-menu-option-canonical-collision-scope")
            val venueId = seedVenue(jdbcUrl)
            val repository = VenueMenuRepository(dataSource(jdbcUrl))
            val category = repository.createCategory(venueId, "Ordinary menu")
            val item =
                requireNotNull(
                    repository.createItem(
                        venueId = venueId,
                        categoryId = category.id,
                        name = "Ordinary item",
                        priceMinor = 100,
                        currency = "RUB",
                        isAvailable = true,
                    ),
                )
            val firstCanonical =
                requireNotNull(
                    repository.createOption(venueId, item.id, "Ягодный", 10, true),
                )
            requireNotNull(repository.createOption(venueId, item.id, "Ягодный", 20, true))
            requireNotNull(repository.updateCategoryType(venueId, category.id, MenuSemanticType.HOOKAH))

            val priceOnlyUpdate =
                requireNotNull(
                    repository.updateOption(
                        venueId = venueId,
                        optionId = firstCanonical.id,
                        name = null,
                        priceDeltaMinor = 30,
                        isAvailable = false,
                        actorUserId = AUDIT_ACTOR_ID,
                        source = MenuOptionRenameSource.VENUE_MINI_APP,
                    ),
                )
            assertEquals("Ягодный", priceOnlyUpdate.name)
            assertEquals(30L, priceOnlyUpdate.priceDeltaMinor)
            assertFalse(priceOnlyUpdate.isAvailable)

            assertFailsWith<InvalidInputException> {
                repository.createOption(venueId, item.id, "Ягодный", 0, true)
            }
            val custom = requireNotNull(repository.createOption(venueId, item.id, "Авторский", 0, true))
            assertFailsWith<InvalidInputException> {
                repository.updateOption(
                    venueId = venueId,
                    optionId = custom.id,
                    name = "Ягодный",
                    priceDeltaMinor = null,
                    isAvailable = null,
                    actorUserId = AUDIT_ACTOR_ID,
                    source = MenuOptionRenameSource.VENUE_MINI_APP,
                )
            }
            assertTrue(menuOptionRenameAudits(jdbcUrl).isEmpty())
            Unit
        }

    @Test
    fun `normalization failure after multiple deletes and creates rolls back all writes`() =
        runBlocking {
            val jdbcUrl = migratedJdbcUrl("venue-menu-option-normalization-write-rollback")
            val venueId = seedVenue(jdbcUrl)
            val delegate = dataSource(jdbcUrl)
            val fixtureRepository = VenueMenuRepository(delegate)
            val fixture = createNormalizationFixture(fixtureRepository, venueId)
            val before = optionRows(jdbcUrl, fixture.item.id)
            val failingDataSource = FailAfterOptionInsertsDataSource(delegate, failOnInsert = 3)
            val repository = VenueMenuRepository(failingDataSource)

            assertFailsWith<DatabaseUnavailableException> {
                repository.normalizeHookahFlavorProfiles(
                    venueId = venueId,
                    itemId = fixture.item.id,
                    actorUserId = AUDIT_ACTOR_ID,
                    source = MenuOptionDeleteSource.TELEGRAM_BOT,
                )
            }

            assertEquals(3, failingDataSource.completedOptionInserts.get())
            assertEquals(before, optionRows(jdbcUrl, fixture.item.id))
            assertTrue(menuOptionDeleteAudits(jdbcUrl).isEmpty())
        }

    @Test
    fun `normalization nth audit failure rolls back prior audits deletes and creates`() =
        runBlocking {
            val jdbcUrl = migratedJdbcUrl("venue-menu-option-normalization-audit-rollback")
            val venueId = seedVenue(jdbcUrl)
            val dataSource = dataSource(jdbcUrl)
            val fixtureRepository = VenueMenuRepository(dataSource)
            val fixture = createNormalizationFixture(fixtureRepository, venueId)
            val before = optionRows(jdbcUrl, fixture.item.id)
            val delegateAuditWriter = AuditLogRepository(dataSource, Json)
            var auditAttempts = 0
            val failingAuditWriter =
                TransactionalAuditLogWriter { connection, actorUserId, action, entityType, entityId, payload ->
                    delegateAuditWriter.appendJson(
                        connection,
                        actorUserId,
                        action,
                        entityType,
                        entityId,
                        payload,
                    )
                    if (action == MENU_OPTION_DELETED_AUDIT_ACTION && ++auditAttempts == 2) {
                        throw SQLException("Synthetic second menu option audit failure", "XX999")
                    }
                }
            val repository = VenueMenuRepository(dataSource, failingAuditWriter)

            assertFailsWith<DatabaseUnavailableException> {
                repository.normalizeHookahFlavorProfiles(
                    venueId = venueId,
                    itemId = fixture.item.id,
                    actorUserId = AUDIT_ACTOR_ID,
                    source = MenuOptionDeleteSource.TELEGRAM_BOT,
                )
            }

            assertEquals(2, auditAttempts)
            assertEquals(before, optionRows(jdbcUrl, fixture.item.id))
            assertTrue(menuOptionDeleteAudits(jdbcUrl).isEmpty())
        }

    @Test
    fun `empty category delete writes one exact safe audit and repeat writes none`() =
        runBlocking {
            val jdbcUrl = migratedJdbcUrl("venue-menu-category-delete-no-references")
            val venueId = seedVenue(jdbcUrl)
            val repository = VenueMenuRepository(dataSource(jdbcUrl))
            val category = repository.createCategory(venueId, "Private category name")

            assertTrue(
                repository.deleteCategory(
                    venueId = venueId,
                    categoryId = category.id,
                    actorUserId = AUDIT_ACTOR_ID,
                    source = MenuCategoryDeleteSource.VENUE_MINI_APP,
                ),
            )

            assertFalse(repository.categoryExists(venueId, category.id))
            val audit = menuCategoryDeleteAudits(jdbcUrl).single()
            assertEquals(AUDIT_ACTOR_ID, audit.actorUserId)
            assertEquals(MENU_CATEGORY_DELETED_AUDIT_ACTION, audit.action)
            assertEquals("menu_category", audit.entityType)
            assertEquals(category.id, audit.entityId)
            assertEquals(
                setOf("venueId", "categoryId", "source", "affectedPromotionRules"),
                audit.payload.keys,
            )
            assertEquals(venueId, audit.payload.longValue("venueId"))
            assertEquals(category.id, audit.payload.longValue("categoryId"))
            assertEquals(
                MenuCategoryDeleteSource.VENUE_MINI_APP.name,
                audit.payload.getValue("source").jsonPrimitive.content,
            )
            assertAffectedPromotionRules(audit.payload, emptyList())

            val serialized = audit.payload.toString()
            assertFalse(serialized.contains(category.name))
            listOf(
                "name",
                "price",
                "media",
                "title",
                "config",
                "schedule",
                "reward",
                "request",
                "callback",
                "initData",
                "telegram",
                "secret",
                "username",
                "firstName",
                "lastName",
                "phone",
            ).forEach { forbidden ->
                assertFalse(serialized.contains(forbidden, ignoreCase = true), forbidden)
            }
            assertTrue(
                serialized.toByteArray(StandardCharsets.UTF_8).size < MENU_DELETE_AUDIT_PAYLOAD_MAX_BYTES,
            )

            repeat(2) {
                assertFalse(
                    repository.deleteCategory(
                        venueId = venueId,
                        categoryId = category.id,
                        actorUserId = AUDIT_ACTOR_ID,
                        source = MenuCategoryDeleteSource.VENUE_MINI_APP,
                    ),
                )
            }
            assertFalse(
                repository.deleteCategory(
                    venueId = venueId,
                    categoryId = Long.MAX_VALUE,
                    actorUserId = AUDIT_ACTOR_ID,
                    source = MenuCategoryDeleteSource.VENUE_MINI_APP,
                ),
            )
            assertEquals(listOf(audit), menuCategoryDeleteAudits(jdbcUrl))
        }

    @Test
    fun `referenced empty category delete cleans targets bumps versions and writes bounded audit`() =
        runBlocking {
            val jdbcUrl = migratedJdbcUrl("venue-menu-category-delete-references")
            val venueId = seedVenue(jdbcUrl)
            val repository = VenueMenuRepository(dataSource(jdbcUrl))
            val category = repository.createCategory(venueId, "Private referenced category")
            val ruleIds = seedPromotionCategoryReferences(jdbcUrl, venueId, category.id, 73)
            val before = readRuleSnapshots(jdbcUrl, ruleIds)

            assertTrue(
                repository.deleteCategory(
                    venueId = venueId,
                    categoryId = category.id,
                    actorUserId = AUDIT_ACTOR_ID,
                    source = MenuCategoryDeleteSource.TELEGRAM_BOT,
                ),
            )

            assertFalse(repository.categoryExists(venueId, category.id))
            assertEquals(0, countRuleCategoryTargets(jdbcUrl, ruleIds))
            val after = readRuleSnapshots(jdbcUrl, ruleIds)
            ruleIds.forEach { ruleId ->
                assertEquals(before.getValue(ruleId).version + 1, after.getValue(ruleId).version)
                assertEquals(before.getValue(ruleId).status, after.getValue(ruleId).status)
            }
            val audit = menuCategoryDeleteAudits(jdbcUrl).single()
            assertEquals(
                MenuCategoryDeleteSource.TELEGRAM_BOT.name,
                audit.payload.getValue("source").jsonPrimitive.content,
            )
            assertAffectedPromotionRules(audit.payload, ruleIds)
            val affected = audit.payload.getValue("affectedPromotionRules").jsonObject
            assertEquals(
                ruleIds.sorted().take(MENU_DELETE_AFFECTED_RULE_SAMPLE_LIMIT),
                affected.getValue("sampleRuleIds").jsonArray.map { it.jsonPrimitive.content.toLong() },
            )
            assertEquals(23, affected.getValue("omittedCount").jsonPrimitive.int)
            assertTrue(
                audit.payload.toString().toByteArray(StandardCharsets.UTF_8).size <
                    MENU_DELETE_AUDIT_PAYLOAD_MAX_BYTES,
            )
            assertFalse(audit.payload.containsKey("affectedPromotionRuleIds"))
            assertFalse(audit.payload.toString().contains(category.name))
            assertFalse(audit.payload.toString().contains("Private promotion", ignoreCase = true))
        }

    @Test
    fun `non-empty category delete changes no category item promotion version or audit`() =
        runBlocking {
            val jdbcUrl = migratedJdbcUrl("venue-menu-category-delete-non-empty")
            val venueId = seedVenue(jdbcUrl)
            val repository = VenueMenuRepository(dataSource(jdbcUrl))
            val fixture = createMenuFixture(repository, venueId)
            val categoryId = fixture.firstItem.categoryId
            val ruleIds = seedPromotionCategoryReferences(jdbcUrl, venueId, categoryId, 2)
            val before = readRuleSnapshots(jdbcUrl, ruleIds)

            assertFalse(
                repository.deleteCategory(
                    venueId = venueId,
                    categoryId = categoryId,
                    actorUserId = AUDIT_ACTOR_ID,
                    source = MenuCategoryDeleteSource.VENUE_MINI_APP,
                ),
            )

            assertTrue(repository.categoryExists(venueId, categoryId))
            assertTrue(repository.itemExists(venueId, fixture.firstItem.id))
            assertEquals(ruleIds.size, countRuleCategoryTargets(jdbcUrl, ruleIds))
            assertEquals(before, readRuleSnapshots(jdbcUrl, ruleIds))
            assertTrue(menuCategoryDeleteAudits(jdbcUrl).isEmpty())
        }

    @Test
    fun `category delete audit failure rolls back category references versions and timestamps`() =
        runBlocking {
            val jdbcUrl = migratedJdbcUrl("venue-menu-category-delete-audit-rollback")
            val venueId = seedVenue(jdbcUrl)
            val fixtureRepository = VenueMenuRepository(dataSource(jdbcUrl))
            val category = fixtureRepository.createCategory(venueId, "Rollback category")
            val ruleIds = seedPromotionCategoryReferences(jdbcUrl, venueId, category.id, 2)
            val before = readRuleSnapshots(jdbcUrl, ruleIds)
            val failingAuditWriter =
                TransactionalAuditLogWriter { _, _, action, _, _, _ ->
                    if (action == MENU_CATEGORY_DELETED_AUDIT_ACTION) {
                        throw SQLException("Synthetic menu category delete audit failure", "XX999")
                    }
                }
            val repository = VenueMenuRepository(dataSource(jdbcUrl), failingAuditWriter)

            assertFailsWith<DatabaseUnavailableException> {
                repository.deleteCategory(
                    venueId = venueId,
                    categoryId = category.id,
                    actorUserId = AUDIT_ACTOR_ID,
                    source = MenuCategoryDeleteSource.VENUE_MINI_APP,
                )
            }

            assertTrue(fixtureRepository.categoryExists(venueId, category.id))
            assertEquals(ruleIds.size, countRuleCategoryTargets(jdbcUrl, ruleIds))
            assertEquals(before, readRuleSnapshots(jdbcUrl, ruleIds))
            assertTrue(menuCategoryDeleteAudits(jdbcUrl).isEmpty())
        }

    @Test
    fun `category affected promotion summary deduplicates before count sample and hash`() {
        val payload =
            buildMenuCategoryDeleteAuditPayload(
                venueId = 2,
                categoryId = 4,
                source = MenuCategoryDeleteSource.VENUE_MINI_APP,
                affectedRuleIds = listOf(12, 4, 9, 4, 12),
            )

        assertAffectedPromotionRules(payload, listOf(4, 9, 12))
    }

    @Test
    fun `item delete without promotion references writes one exact safe audit`() =
        runBlocking {
            val jdbcUrl = migratedJdbcUrl("venue-menu-item-delete-no-references")
            val venueId = seedVenue(jdbcUrl)
            val repository = VenueMenuRepository(dataSource(jdbcUrl))
            val fixture = createMenuFixture(repository, venueId)

            assertTrue(
                repository.deleteItem(
                    venueId = venueId,
                    itemId = fixture.firstItem.id,
                    actorUserId = AUDIT_ACTOR_ID,
                    source = MenuItemDeleteSource.VENUE_MINI_APP,
                ),
            )

            assertFalse(repository.itemExists(venueId, fixture.firstItem.id))
            val audit = menuItemDeleteAudits(jdbcUrl).single()
            assertEquals(AUDIT_ACTOR_ID, audit.actorUserId)
            assertEquals(MENU_ITEM_DELETED_AUDIT_ACTION, audit.action)
            assertEquals("menu_item", audit.entityType)
            assertEquals(fixture.firstItem.id, audit.entityId)
            assertEquals(
                setOf("venueId", "itemId", "categoryId", "source", "affectedPromotionRules"),
                audit.payload.keys,
            )
            assertEquals(venueId, audit.payload.longValue("venueId"))
            assertEquals(fixture.firstItem.id, audit.payload.longValue("itemId"))
            assertEquals(fixture.firstItem.categoryId, audit.payload.longValue("categoryId"))
            assertEquals(
                MenuItemDeleteSource.VENUE_MINI_APP.name,
                audit.payload.getValue("source").jsonPrimitive.content,
            )
            assertAffectedPromotionRules(audit.payload, emptyList())

            val serialized = audit.payload.toString()
            assertFalse(serialized.contains(fixture.firstItem.name))
            assertFalse(serialized.contains(fixture.firstOption.name))
            assertFalse(serialized.contains(fixture.firstItem.priceMinor.toString()))
            listOf(
                "name",
                "price",
                "media",
                "title",
                "config",
                "schedule",
                "reward",
                "request",
                "initData",
                "telegram",
                "secret",
                "username",
                "firstName",
                "lastName",
                "phone",
            ).forEach { forbidden ->
                assertFalse(serialized.contains(forbidden, ignoreCase = true), forbidden)
            }

            assertFalse(
                repository.deleteItem(
                    venueId = venueId,
                    itemId = fixture.firstItem.id,
                    actorUserId = AUDIT_ACTOR_ID,
                    source = MenuItemDeleteSource.VENUE_MINI_APP,
                ),
            )
            assertFalse(
                repository.deleteItem(
                    venueId = venueId,
                    itemId = Long.MAX_VALUE,
                    actorUserId = AUDIT_ACTOR_ID,
                    source = MenuItemDeleteSource.VENUE_MINI_APP,
                ),
            )
            assertEquals(listOf(audit), menuItemDeleteAudits(jdbcUrl))
        }

    @Test
    fun `fixed reward blocks repeated item delete without state changes or audit`() =
        runBlocking {
            val jdbcUrl = migratedJdbcUrl("venue-menu-item-delete-fixed-reward")
            val venueId = seedVenue(jdbcUrl)
            val repository = VenueMenuRepository(dataSource(jdbcUrl))
            val fixture = createMenuFixture(repository, venueId)
            val ruleId =
                seedGiftRewardRule(
                    jdbcUrl = jdbcUrl,
                    venueId = venueId,
                    primaryRewardItemId = fixture.firstItem.id,
                    rewardMode = "FIXED_ITEM",
                    optionItemIds = emptyList(),
                )
            val before = readGiftRuleSnapshot(jdbcUrl, ruleId)

            repeat(2) {
                val error =
                    assertFailsWith<MenuItemDeleteBlockedByFixedRewardException> {
                        repository.deleteItem(
                            venueId = venueId,
                            itemId = fixture.firstItem.id,
                            actorUserId = AUDIT_ACTOR_ID,
                            source = MenuItemDeleteSource.VENUE_MINI_APP,
                        )
                    }
                assertEquals(MENU_ITEM_DELETE_BLOCKED_BY_FIXED_REWARD_MESSAGE, error.message)
            }

            assertTrue(repository.itemExists(venueId, fixture.firstItem.id))
            assertEquals(before, readGiftRuleSnapshot(jdbcUrl, ruleId))
            assertTrue(menuItemDeleteAudits(jdbcUrl).isEmpty())
            val safeMessage = MENU_ITEM_DELETE_BLOCKED_BY_FIXED_REWARD_MESSAGE
            assertFalse(safeMessage.contains("Private promotion title"))
            assertFalse(safeMessage.contains(ruleId.toString()))
            assertFalse(safeMessage.contains("SQL", ignoreCase = true))
            assertFalse(safeMessage.contains("telegram", ignoreCase = true))
        }

    @Test
    fun `primary choice reward item delete rehomes reward and writes exactly one audit`() =
        runBlocking {
            val jdbcUrl = migratedJdbcUrl("venue-menu-item-delete-choice-primary")
            val venueId = seedVenue(jdbcUrl)
            val repository = VenueMenuRepository(dataSource(jdbcUrl))
            val fixture = createMenuFixture(repository, venueId)
            val ruleId =
                seedGiftRewardRule(
                    jdbcUrl = jdbcUrl,
                    venueId = venueId,
                    primaryRewardItemId = fixture.firstItem.id,
                    rewardMode = "CHOICE_ITEMS",
                    optionItemIds = listOf(fixture.firstItem.id, fixture.secondItem.id),
                )
            val before = readGiftRuleSnapshot(jdbcUrl, ruleId)

            assertTrue(
                repository.deleteItem(
                    venueId = venueId,
                    itemId = fixture.firstItem.id,
                    actorUserId = AUDIT_ACTOR_ID,
                    source = MenuItemDeleteSource.VENUE_MINI_APP,
                ),
            )

            assertFalse(repository.itemExists(venueId, fixture.firstItem.id))
            val after = readGiftRuleSnapshot(jdbcUrl, ruleId)
            assertEquals(before.version + 1, after.version)
            assertEquals(before.status, after.status)
            assertEquals(before.rewardId, after.rewardId)
            assertEquals("CHOICE_ITEMS", after.rewardMode)
            assertEquals(fixture.secondItem.id, after.primaryRewardItemId)
            assertEquals(listOf(fixture.secondItem.id), after.optionItemIds)
            val audit = menuItemDeleteAudits(jdbcUrl).single()
            assertAffectedPromotionRules(audit.payload, listOf(ruleId))
        }

    @Test
    fun `item delete audits exact sorted small promotion rule set and bumps versions`() =
        runBlocking {
            val jdbcUrl = migratedJdbcUrl("venue-menu-item-delete-small-references")
            val venueId = seedVenue(jdbcUrl)
            val repository = VenueMenuRepository(dataSource(jdbcUrl))
            val fixture = createMenuFixture(repository, venueId)
            val ruleIds = seedPromotionRuleReferences(jdbcUrl, venueId, fixture.firstItem.id, 3)

            assertTrue(
                repository.deleteItem(
                    venueId = venueId,
                    itemId = fixture.firstItem.id,
                    actorUserId = AUDIT_ACTOR_ID,
                    source = MenuItemDeleteSource.TELEGRAM_BOT,
                ),
            )

            assertEquals(ruleIds.associateWith { 2 }, readRuleVersions(jdbcUrl, ruleIds))
            assertEquals(0, countRuleTargets(jdbcUrl, ruleIds))
            val audit = menuItemDeleteAudits(jdbcUrl).single()
            assertEquals(MenuItemDeleteSource.TELEGRAM_BOT.name, audit.payload.getValue("source").jsonPrimitive.content)
            assertAffectedPromotionRules(audit.payload, ruleIds.sorted())
        }

    @Test
    fun `item delete bounds sample hashes the full rule set and stays below byte budget`() =
        runBlocking {
            val jdbcUrl = migratedJdbcUrl("venue-menu-item-delete-bounded-references")
            val venueId = seedVenue(jdbcUrl)
            val repository = VenueMenuRepository(dataSource(jdbcUrl))
            val fixture = createMenuFixture(repository, venueId)
            val ruleIds = seedPromotionRuleReferences(jdbcUrl, venueId, fixture.firstItem.id, 73)

            assertTrue(
                repository.deleteItem(
                    venueId = venueId,
                    itemId = fixture.firstItem.id,
                    actorUserId = AUDIT_ACTOR_ID,
                    source = MenuItemDeleteSource.VENUE_MINI_APP,
                ),
            )

            val audit = menuItemDeleteAudits(jdbcUrl).single()
            assertAffectedPromotionRules(audit.payload, ruleIds.sorted())
            val affected = audit.payload.getValue("affectedPromotionRules").jsonObject
            assertEquals(
                ruleIds.sorted().take(MENU_ITEM_DELETE_AFFECTED_RULE_SAMPLE_LIMIT),
                affected.getValue("sampleRuleIds").jsonArray.map { it.jsonPrimitive.content.toLong() },
            )
            assertEquals(23, affected.getValue("omittedCount").jsonPrimitive.int)
            assertTrue(
                audit.payload.toString().toByteArray(StandardCharsets.UTF_8).size <
                    MENU_ITEM_DELETE_AUDIT_PAYLOAD_MAX_BYTES,
            )
            assertFalse(audit.payload.containsKey("affectedPromotionRuleIds"))
        }

    @Test
    fun `affected promotion rule summary deduplicates before count sample and hash`() {
        val payload =
            buildMenuItemDeleteAuditPayload(
                venueId = 2,
                itemId = 17,
                categoryId = 4,
                source = MenuItemDeleteSource.VENUE_MINI_APP,
                affectedRuleIds = listOf(12, 4, 9, 4, 12),
            )

        assertAffectedPromotionRules(payload, listOf(4, 9, 12))
    }

    @Test
    fun `item delete audit failure rolls back item references and rule versions`() =
        runBlocking {
            val jdbcUrl = migratedJdbcUrl("venue-menu-item-delete-audit-rollback")
            val venueId = seedVenue(jdbcUrl)
            val fixtureRepository = VenueMenuRepository(dataSource(jdbcUrl))
            val fixture = createMenuFixture(fixtureRepository, venueId)
            val ruleIds = seedPromotionRuleReferences(jdbcUrl, venueId, fixture.firstItem.id, 2)
            val choiceRuleId =
                seedGiftRewardRule(
                    jdbcUrl = jdbcUrl,
                    venueId = venueId,
                    primaryRewardItemId = fixture.firstItem.id,
                    rewardMode = "CHOICE_ITEMS",
                    optionItemIds = listOf(fixture.firstItem.id, fixture.secondItem.id),
                )
            val choiceBefore = readGiftRuleSnapshot(jdbcUrl, choiceRuleId)
            val failingAuditWriter =
                TransactionalAuditLogWriter { _, _, action, _, _, _ ->
                    if (action == MENU_ITEM_DELETED_AUDIT_ACTION) {
                        throw SQLException("Synthetic menu item delete audit failure", "XX999")
                    }
                }
            val repository = VenueMenuRepository(dataSource(jdbcUrl), failingAuditWriter)

            assertFailsWith<DatabaseUnavailableException> {
                repository.deleteItem(
                    venueId = venueId,
                    itemId = fixture.firstItem.id,
                    actorUserId = AUDIT_ACTOR_ID,
                    source = MenuItemDeleteSource.VENUE_MINI_APP,
                )
            }

            assertTrue(fixtureRepository.itemExists(venueId, fixture.firstItem.id))
            assertEquals(ruleIds.associateWith { 1 }, readRuleVersions(jdbcUrl, ruleIds))
            assertEquals(ruleIds.size, countRuleTargets(jdbcUrl, ruleIds))
            assertEquals(choiceBefore, readGiftRuleSnapshot(jdbcUrl, choiceRuleId))
            assertTrue(menuItemDeleteAudits(jdbcUrl).isEmpty())
        }

    @Test
    fun `promotion rule version SQL failure leaves item references versions and audit unchanged`() =
        runBlocking {
            val jdbcUrl = migratedJdbcUrl("venue-menu-item-delete-reference-rollback")
            val venueId = seedVenue(jdbcUrl)
            val repository = VenueMenuRepository(dataSource(jdbcUrl))
            val fixture = createMenuFixture(repository, venueId)
            val ruleIds = seedPromotionRuleReferences(jdbcUrl, venueId, fixture.firstItem.id, 2)
            val choiceRuleId =
                seedGiftRewardRule(
                    jdbcUrl = jdbcUrl,
                    venueId = venueId,
                    primaryRewardItemId = fixture.firstItem.id,
                    rewardMode = "CHOICE_ITEMS",
                    optionItemIds = listOf(fixture.firstItem.id, fixture.secondItem.id),
                )
            val choiceBefore = readGiftRuleSnapshot(jdbcUrl, choiceRuleId)
            DriverManager.getConnection(jdbcUrl, "sa", "").use { connection ->
                connection.createStatement().use { statement ->
                    statement.execute(
                        """
                        ALTER TABLE promotion_rules
                        ADD CONSTRAINT reject_menu_item_delete_version_bump
                        CHECK (version = 1)
                        """.trimIndent(),
                    )
                }
            }

            assertFailsWith<DatabaseUnavailableException> {
                repository.deleteItem(
                    venueId = venueId,
                    itemId = fixture.firstItem.id,
                    actorUserId = AUDIT_ACTOR_ID,
                    source = MenuItemDeleteSource.VENUE_MINI_APP,
                )
            }

            assertTrue(repository.itemExists(venueId, fixture.firstItem.id))
            assertEquals(ruleIds.associateWith { 1 }, readRuleVersions(jdbcUrl, ruleIds))
            assertEquals(ruleIds.size, countRuleTargets(jdbcUrl, ruleIds))
            assertEquals(choiceBefore, readGiftRuleSnapshot(jdbcUrl, choiceRuleId))
            assertTrue(menuItemDeleteAudits(jdbcUrl).isEmpty())
        }

    private fun migratedJdbcUrl(name: String): String {
        val jdbcUrl =
            "jdbc:h2:mem:$name-${UUID.randomUUID()};MODE=PostgreSQL;" +
                "DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE"
        Flyway
            .configure()
            .dataSource(jdbcUrl, "sa", "")
            .locations("classpath:db/migration/h2")
            .load()
            .migrate()
        return jdbcUrl
    }

    private fun dataSource(jdbcUrl: String): JdbcDataSource =
        JdbcDataSource().apply {
            setURL(jdbcUrl)
            user = "sa"
            password = ""
        }

    private suspend fun createMenuFixture(
        repository: VenueMenuRepository,
        venueId: Long,
    ): MenuFixture {
        val category = repository.createCategory(venueId, "Shift check")
        val firstItem =
            requireNotNull(
                repository.createItem(
                    venueId = venueId,
                    categoryId = category.id,
                    name = "First private item name",
                    priceMinor = 100,
                    currency = "RUB",
                    isAvailable = true,
                ),
            )
        val secondItem =
            requireNotNull(
                repository.createItem(
                    venueId = venueId,
                    categoryId = category.id,
                    name = "Second item",
                    priceMinor = 200,
                    currency = "RUB",
                    isAvailable = true,
                ),
            )
        val firstOption =
            requireNotNull(
                repository.createOption(
                    venueId = venueId,
                    itemId = firstItem.id,
                    name = "First option",
                    priceDeltaMinor = 10,
                    isAvailable = true,
                ),
            )
        val secondOption =
            requireNotNull(
                repository.createOption(
                    venueId = venueId,
                    itemId = secondItem.id,
                    name = "Second private option name",
                    priceDeltaMinor = 20,
                    isAvailable = true,
                ),
            )
        return MenuFixture(firstItem, secondItem, firstOption, secondOption)
    }

    private suspend fun createNormalizationFixture(
        repository: VenueMenuRepository,
        venueId: Long,
    ): NormalizationFixture {
        val category = repository.createCategory(venueId, "Кальянное меню")
        repository.updateCategoryType(venueId, category.id, MenuSemanticType.HOOKAH)
        val item =
            requireNotNull(
                repository.createItem(
                    venueId = venueId,
                    categoryId = category.id,
                    name = "Normalization hookah",
                    priceMinor = 100_000,
                    currency = "RUB",
                    isAvailable = true,
                ),
            )
        val specifications =
            listOf(
                Triple("Яблоко", 100L, true),
                Triple("Фруктовые", 200L, true),
                Triple("Освежающий", 300L, false),
                Triple("Мятный", 400L, true),
                Triple("Освежающий / мятный", 500L, false),
                Triple("Авторский микс", 600L, true),
                Triple("Ягодный", 700L, true),
                Triple("Арбуз", 800L, false),
            )
        val options =
            specifications.map { (name, priceDeltaMinor, isAvailable) ->
                requireNotNull(
                    repository.createOption(
                        venueId = venueId,
                        itemId = item.id,
                        name = name,
                        priceDeltaMinor = priceDeltaMinor,
                        isAvailable = isAvailable,
                    ),
                )
            }
        return NormalizationFixture(item = item, options = options)
    }

    private suspend fun loadItem(
        repository: VenueMenuRepository,
        venueId: Long,
        itemId: Long,
    ): VenueMenuItem =
        repository.getMenu(venueId)
            .flatMap { it.items }
            .single { it.id == itemId }

    private fun seedPromotionRuleReferences(
        jdbcUrl: String,
        venueId: Long,
        itemId: Long,
        count: Int,
    ): List<Long> =
        seedPromotionRuleReferences(
            jdbcUrl = jdbcUrl,
            venueId = venueId,
            count = count,
            executableTargetType = "MENU_ITEM",
        ) { connection, ruleId ->
            connection.prepareStatement(
                """
                INSERT INTO promotion_rule_targets (
                    rule_id,
                    target_type,
                    semantic_type,
                    menu_item_id
                )
                VALUES (?, 'MENU_ITEM', NULL, ?)
                """.trimIndent(),
            ).use { statement ->
                statement.setLong(1, ruleId)
                statement.setLong(2, itemId)
                statement.executeUpdate()
            }
        }

    private fun seedPromotionCategoryReferences(
        jdbcUrl: String,
        venueId: Long,
        categoryId: Long,
        count: Int,
    ): List<Long> =
        seedPromotionRuleReferences(
            jdbcUrl = jdbcUrl,
            venueId = venueId,
            count = count,
            executableTargetType = "MENU_CATEGORY",
        ) { connection, ruleId ->
            connection.prepareStatement(
                """
                INSERT INTO promotion_rule_menu_category_targets (rule_id, menu_category_id)
                VALUES (?, ?)
                """.trimIndent(),
            ).use { statement ->
                statement.setLong(1, ruleId)
                statement.setLong(2, categoryId)
                statement.executeUpdate()
            }
        }

    private fun seedPromotionRuleReferences(
        jdbcUrl: String,
        venueId: Long,
        count: Int,
        executableTargetType: String,
        insertTarget: (java.sql.Connection, Long) -> Unit,
    ): List<Long> =
        DriverManager.getConnection(jdbcUrl, "sa", "").use { connection ->
            connection.autoCommit = false
            try {
                val promotionId =
                    connection.prepareStatement(
                        """
                        INSERT INTO venue_promotions (
                            venue_id,
                            title,
                            description,
                            status,
                            template_type,
                            created_by_user_id
                        )
                        VALUES (?, 'Private promotion title', 'Private promotion config', 'DRAFT',
                            'HAPPY_HOURS_PERCENT', ?)
                        """.trimIndent(),
                        Statement.RETURN_GENERATED_KEYS,
                    ).use { statement ->
                        statement.setLong(1, venueId)
                        statement.setLong(2, AUDIT_ACTOR_ID)
                        statement.executeUpdate()
                        statement.generatedKeys.use { keys ->
                            check(keys.next())
                            keys.getLong(1)
                        }
                    }
                val ruleIds =
                    buildList {
                        repeat(count) { index ->
                            val ruleId =
                                connection.prepareStatement(
                                    """
                                    INSERT INTO promotion_rules (
                                        promotion_id,
                                        venue_id,
                                        rule_type,
                                        target_type,
                                        target_value,
                                        executable_target_type,
                                        discount_percent,
                                        status,
                                        priority,
                                        stackable,
                                        max_applications_per_item,
                                        version,
                                        created_by_user_id
                                    )
                                    VALUES (?, ?, 'HAPPY_HOURS_PERCENT', 'CATEGORY_TYPE', 'HOOKAH',
                                        ?, 10, 'DRAFT', ?, FALSE, 1, 1, ?)
                                    """.trimIndent(),
                                    Statement.RETURN_GENERATED_KEYS,
                                ).use { statement ->
                                    statement.setLong(1, promotionId)
                                    statement.setLong(2, venueId)
                                    statement.setString(3, executableTargetType)
                                    statement.setInt(4, 100 + index)
                                    statement.setLong(5, AUDIT_ACTOR_ID)
                                    statement.executeUpdate()
                                    statement.generatedKeys.use { keys ->
                                        check(keys.next())
                                        keys.getLong(1)
                                    }
                                }
                            insertTarget(connection, ruleId)
                            add(ruleId)
                        }
                    }
                connection.commit()
                ruleIds
            } catch (e: Exception) {
                connection.rollback()
                throw e
            }
        }

    private fun seedGiftRewardRule(
        jdbcUrl: String,
        venueId: Long,
        primaryRewardItemId: Long,
        rewardMode: String,
        optionItemIds: List<Long>,
    ): Long =
        DriverManager.getConnection(jdbcUrl, "sa", "").use { connection ->
            connection.autoCommit = false
            try {
                val promotionId =
                    connection.prepareStatement(
                        """
                        INSERT INTO venue_promotions (
                            venue_id,
                            title,
                            description,
                            status,
                            template_type,
                            created_by_user_id
                        )
                        VALUES (?, 'Private promotion title', 'Private promotion config', 'DRAFT',
                            'GIFT_WITH_ITEM', ?)
                        """.trimIndent(),
                        Statement.RETURN_GENERATED_KEYS,
                    ).use { statement ->
                        statement.setLong(1, venueId)
                        statement.setLong(2, AUDIT_ACTOR_ID)
                        statement.executeUpdate()
                        statement.generatedKeys.use { keys ->
                            check(keys.next())
                            keys.getLong(1)
                        }
                    }
                val ruleId =
                    connection.prepareStatement(
                        """
                        INSERT INTO promotion_rules (
                            promotion_id,
                            venue_id,
                            rule_type,
                            target_type,
                            target_value,
                            executable_target_type,
                            discount_percent,
                            status,
                            priority,
                            stackable,
                            max_applications_per_item,
                            version,
                            created_by_user_id
                        )
                        VALUES (?, ?, 'GIFT_WITH_ITEM', 'CATEGORY_TYPE', 'HOOKAH',
                            'MENU_ITEM', NULL, 'DRAFT', 100, FALSE, 1, 1, ?)
                        """.trimIndent(),
                        Statement.RETURN_GENERATED_KEYS,
                    ).use { statement ->
                        statement.setLong(1, promotionId)
                        statement.setLong(2, venueId)
                        statement.setLong(3, AUDIT_ACTOR_ID)
                        statement.executeUpdate()
                        statement.generatedKeys.use { keys ->
                            check(keys.next())
                            keys.getLong(1)
                        }
                    }
                val rewardId =
                    connection.prepareStatement(
                        """
                        INSERT INTO promotion_rule_rewards (
                            rule_id,
                            reward_menu_item_id,
                            reward_mode,
                            reward_qty,
                            max_rewards_per_batch
                        )
                        VALUES (?, ?, ?, 1, 1)
                        """.trimIndent(),
                        Statement.RETURN_GENERATED_KEYS,
                    ).use { statement ->
                        statement.setLong(1, ruleId)
                        statement.setLong(2, primaryRewardItemId)
                        statement.setString(3, rewardMode)
                        statement.executeUpdate()
                        statement.generatedKeys.use { keys ->
                            check(keys.next())
                            keys.getLong(1)
                        }
                    }
                optionItemIds.forEach { optionItemId ->
                    connection.prepareStatement(
                        """
                        INSERT INTO promotion_rule_reward_options (reward_id, menu_item_id)
                        VALUES (?, ?)
                        """.trimIndent(),
                    ).use { statement ->
                        statement.setLong(1, rewardId)
                        statement.setLong(2, optionItemId)
                        statement.executeUpdate()
                    }
                }
                connection.commit()
                ruleId
            } catch (e: Exception) {
                connection.rollback()
                throw e
            }
        }

    private fun readGiftRuleSnapshot(
        jdbcUrl: String,
        ruleId: Long,
    ): GiftRuleSnapshot =
        DriverManager.getConnection(jdbcUrl, "sa", "").use { connection ->
            val base =
                connection.prepareStatement(
                    """
                    SELECT
                        r.version,
                        r.status,
                        r.updated_at AS rule_updated_at,
                        reward.id AS reward_id,
                        reward.reward_menu_item_id,
                        reward.reward_mode,
                        reward.updated_at AS reward_updated_at
                    FROM promotion_rules r
                    JOIN promotion_rule_rewards reward ON reward.rule_id = r.id
                    WHERE r.id = ?
                    """.trimIndent(),
                ).use { statement ->
                    statement.setLong(1, ruleId)
                    statement.executeQuery().use { rs ->
                        check(rs.next())
                        GiftRuleSnapshot(
                            version = rs.getInt("version"),
                            status = rs.getString("status"),
                            ruleUpdatedAt = rs.getObject("rule_updated_at").toString(),
                            rewardId = rs.getLong("reward_id"),
                            primaryRewardItemId = rs.getLong("reward_menu_item_id"),
                            rewardMode = rs.getString("reward_mode"),
                            rewardUpdatedAt = rs.getObject("reward_updated_at").toString(),
                            optionItemIds = emptyList(),
                        )
                    }
                }
            val optionItemIds =
                connection.prepareStatement(
                    """
                    SELECT menu_item_id
                    FROM promotion_rule_reward_options
                    WHERE reward_id = ?
                    ORDER BY id
                    """.trimIndent(),
                ).use { statement ->
                    statement.setLong(1, base.rewardId)
                    statement.executeQuery().use { rs ->
                        buildList {
                            while (rs.next()) add(rs.getLong("menu_item_id"))
                        }
                    }
                }
            base.copy(optionItemIds = optionItemIds)
        }

    private fun readRuleVersions(
        jdbcUrl: String,
        ruleIds: List<Long>,
    ): Map<Long, Int> {
        val placeholders = ruleIds.joinToString(",") { "?" }
        return DriverManager.getConnection(jdbcUrl, "sa", "").use { connection ->
            connection.prepareStatement(
                """
                SELECT id, version
                FROM promotion_rules
                WHERE id IN ($placeholders)
                ORDER BY id
                """.trimIndent(),
            ).use { statement ->
                ruleIds.forEachIndexed { index, ruleId -> statement.setLong(index + 1, ruleId) }
                statement.executeQuery().use { rs ->
                    buildMap {
                        while (rs.next()) {
                            put(rs.getLong("id"), rs.getInt("version"))
                        }
                    }
                }
            }
        }
    }

    private fun countRuleTargets(
        jdbcUrl: String,
        ruleIds: List<Long>,
    ): Int {
        val placeholders = ruleIds.joinToString(",") { "?" }
        return DriverManager.getConnection(jdbcUrl, "sa", "").use { connection ->
            connection.prepareStatement(
                """
                SELECT COUNT(*)
                FROM promotion_rule_targets
                WHERE rule_id IN ($placeholders)
                """.trimIndent(),
            ).use { statement ->
                ruleIds.forEachIndexed { index, ruleId -> statement.setLong(index + 1, ruleId) }
                statement.executeQuery().use { rs ->
                    check(rs.next())
                    rs.getInt(1)
                }
            }
        }
    }

    private fun countRuleCategoryTargets(
        jdbcUrl: String,
        ruleIds: List<Long>,
    ): Int {
        val placeholders = ruleIds.joinToString(",") { "?" }
        return DriverManager.getConnection(jdbcUrl, "sa", "").use { connection ->
            connection.prepareStatement(
                """
                SELECT COUNT(*)
                FROM promotion_rule_menu_category_targets
                WHERE rule_id IN ($placeholders)
                """.trimIndent(),
            ).use { statement ->
                ruleIds.forEachIndexed { index, ruleId -> statement.setLong(index + 1, ruleId) }
                statement.executeQuery().use { rs ->
                    check(rs.next())
                    rs.getInt(1)
                }
            }
        }
    }

    private fun readRuleSnapshots(
        jdbcUrl: String,
        ruleIds: List<Long>,
    ): Map<Long, RuleSnapshot> {
        val placeholders = ruleIds.joinToString(",") { "?" }
        return DriverManager.getConnection(jdbcUrl, "sa", "").use { connection ->
            connection.prepareStatement(
                """
                SELECT id, version, status, updated_at
                FROM promotion_rules
                WHERE id IN ($placeholders)
                ORDER BY id
                """.trimIndent(),
            ).use { statement ->
                ruleIds.forEachIndexed { index, ruleId -> statement.setLong(index + 1, ruleId) }
                statement.executeQuery().use { rs ->
                    buildMap {
                        while (rs.next()) {
                            put(
                                rs.getLong("id"),
                                RuleSnapshot(
                                    version = rs.getInt("version"),
                                    status = rs.getString("status"),
                                    updatedAt = rs.getObject("updated_at").toString(),
                                ),
                            )
                        }
                    }
                }
            }
        }
    }

    private fun menuItemDeleteAudits(jdbcUrl: String): List<MenuDeleteAuditRow> =
        menuDeleteAudits(jdbcUrl, MENU_ITEM_DELETED_AUDIT_ACTION)

    private fun menuCategoryDeleteAudits(jdbcUrl: String): List<MenuDeleteAuditRow> =
        menuDeleteAudits(jdbcUrl, MENU_CATEGORY_DELETED_AUDIT_ACTION)

    private fun menuOptionDeleteAudits(jdbcUrl: String): List<MenuDeleteAuditRow> =
        menuDeleteAudits(jdbcUrl, MENU_OPTION_DELETED_AUDIT_ACTION)

    private fun menuOptionRenameAudits(jdbcUrl: String): List<MenuDeleteAuditRow> =
        menuDeleteAudits(jdbcUrl, MENU_OPTION_RENAMED_AUDIT_ACTION)

    private fun menuDeleteAudits(
        jdbcUrl: String,
        action: String,
    ): List<MenuDeleteAuditRow> =
        DriverManager.getConnection(jdbcUrl, "sa", "").use { connection ->
            connection.prepareStatement(
                """
                SELECT actor_user_id, action, entity_type, entity_id, payload_json
                FROM audit_log
                WHERE action = ?
                ORDER BY id
                """.trimIndent(),
            ).use { statement ->
                statement.setString(1, action)
                statement.executeQuery().use { rs ->
                    buildList {
                        while (rs.next()) {
                            add(
                                MenuDeleteAuditRow(
                                    actorUserId = rs.getLong("actor_user_id"),
                                    action = rs.getString("action"),
                                    entityType = rs.getString("entity_type"),
                                    entityId = rs.getLong("entity_id"),
                                    payload =
                                        Json.parseToJsonElement(rs.getString("payload_json"))
                                            .jsonObject,
                                ),
                            )
                        }
                    }
                }
            }
        }

    private fun assertMenuOptionDeleteAudit(
        audit: MenuDeleteAuditRow,
        venueId: Long,
        itemId: Long,
        optionId: Long,
        actorUserId: Long,
        source: MenuOptionDeleteSource,
    ) {
        assertEquals(actorUserId, audit.actorUserId)
        assertEquals(MENU_OPTION_DELETED_AUDIT_ACTION, audit.action)
        assertEquals("menu_item_option", audit.entityType)
        assertEquals(optionId, audit.entityId)
        assertEquals(setOf("venueId", "itemId", "optionId", "source"), audit.payload.keys)
        assertEquals(venueId, audit.payload.longValue("venueId"))
        assertEquals(itemId, audit.payload.longValue("itemId"))
        assertEquals(optionId, audit.payload.longValue("optionId"))
        assertEquals(source.name, audit.payload.getValue("source").jsonPrimitive.content)
    }

    private fun assertMenuOptionRenameAudit(
        audit: MenuDeleteAuditRow,
        venueId: Long,
        itemId: Long,
        optionId: Long,
        oldName: String,
        newName: String,
        actorUserId: Long,
        source: MenuOptionRenameSource,
    ) {
        assertEquals(actorUserId, audit.actorUserId)
        assertEquals(MENU_OPTION_RENAMED_AUDIT_ACTION, audit.action)
        assertEquals("menu_item_option", audit.entityType)
        assertEquals(optionId, audit.entityId)
        assertEquals(setOf("venueId", "itemId", "optionId", "oldName", "newName", "source"), audit.payload.keys)
        assertEquals(venueId, audit.payload.longValue("venueId"))
        assertEquals(itemId, audit.payload.longValue("itemId"))
        assertEquals(optionId, audit.payload.longValue("optionId"))
        assertEquals(oldName, audit.payload.getValue("oldName").jsonPrimitive.content)
        assertEquals(newName, audit.payload.getValue("newName").jsonPrimitive.content)
        assertEquals(source.name, audit.payload.getValue("source").jsonPrimitive.content)
    }

    private fun loadOptionSnapshot(
        connection: Connection,
        venueId: Long,
        optionId: Long,
    ): VenueMenuOption? =
        connection.prepareStatement(
            """
            SELECT id, venue_id, item_id, name, price_delta_minor, is_available, sort_order
            FROM menu_item_options
            WHERE venue_id = ? AND id = ?
            """.trimIndent(),
        ).use { statement ->
            statement.setLong(1, venueId)
            statement.setLong(2, optionId)
            statement.executeQuery().use { rs ->
                if (!rs.next()) {
                    null
                } else {
                    VenueMenuOption(
                        id = rs.getLong("id"),
                        venueId = rs.getLong("venue_id"),
                        itemId = rs.getLong("item_id"),
                        name = rs.getString("name"),
                        priceDeltaMinor = rs.getLong("price_delta_minor"),
                        isAvailable = rs.getBoolean("is_available"),
                        sortOrder = rs.getInt("sort_order"),
                    )
                }
            }
        }

    private fun optionExists(
        connection: Connection,
        venueId: Long,
        optionId: Long,
    ): Boolean =
        connection.prepareStatement(
            "SELECT 1 FROM menu_item_options WHERE venue_id = ? AND id = ?",
        ).use { statement ->
            statement.setLong(1, venueId)
            statement.setLong(2, optionId)
            statement.executeQuery().use { rs -> rs.next() }
        }

    private fun optionRows(
        jdbcUrl: String,
        itemId: Long,
    ): List<OptionRowSnapshot> =
        DriverManager.getConnection(jdbcUrl, "sa", "").use { connection ->
            connection.prepareStatement(
                """
                SELECT id, venue_id, item_id, name, price_delta_minor, is_available, sort_order, updated_at
                FROM menu_item_options
                WHERE item_id = ?
                ORDER BY id
                """.trimIndent(),
            ).use { statement ->
                statement.setLong(1, itemId)
                statement.executeQuery().use { rs ->
                    buildList {
                        while (rs.next()) {
                            add(
                                OptionRowSnapshot(
                                    id = rs.getLong("id"),
                                    venueId = rs.getLong("venue_id"),
                                    itemId = rs.getLong("item_id"),
                                    name = rs.getString("name"),
                                    priceDeltaMinor = rs.getLong("price_delta_minor"),
                                    isAvailable = rs.getBoolean("is_available"),
                                    sortOrder = rs.getInt("sort_order"),
                                    updatedAt = rs.getObject("updated_at").toString(),
                                ),
                            )
                        }
                    }
                }
            }
        }

    private fun assertAffectedPromotionRules(
        payload: JsonObject,
        expectedRuleIds: List<Long>,
    ) {
        val sortedUniqueRuleIds = expectedRuleIds.distinct().sorted()
        val affected = payload.getValue("affectedPromotionRules").jsonObject
        assertEquals(setOf("totalCount", "sampleRuleIds", "omittedCount", "sha256"), affected.keys)
        assertEquals(sortedUniqueRuleIds.size, affected.getValue("totalCount").jsonPrimitive.int)
        assertEquals(
            sortedUniqueRuleIds.take(MENU_ITEM_DELETE_AFFECTED_RULE_SAMPLE_LIMIT),
            affected.getValue("sampleRuleIds").jsonArray.map { it.jsonPrimitive.content.toLong() },
        )
        assertEquals(
            sortedUniqueRuleIds.size - minOf(sortedUniqueRuleIds.size, MENU_ITEM_DELETE_AFFECTED_RULE_SAMPLE_LIMIT),
            affected.getValue("omittedCount").jsonPrimitive.int,
        )
        assertEquals(
            expectedCanonicalRuleHash(sortedUniqueRuleIds),
            affected.getValue("sha256").jsonPrimitive.content,
        )
    }

    private fun expectedCanonicalRuleHash(sortedUniqueRuleIds: List<Long>): String =
        HexFormat.of().formatHex(
            MessageDigest
                .getInstance("SHA-256")
                .digest(
                    ("v1:" + sortedUniqueRuleIds.joinToString(","))
                        .toByteArray(StandardCharsets.UTF_8),
                ),
        )

    private fun JsonObject.longValue(key: String): Long = getValue(key).jsonPrimitive.content.toLong()

    private fun auditPayloads(jdbcUrl: String): List<JsonObject> =
        DriverManager.getConnection(jdbcUrl, "sa", "").use { connection ->
            connection.prepareStatement(
                """
                SELECT payload_json
                FROM audit_log
                WHERE action = ?
                ORDER BY id
                """.trimIndent(),
            ).use { statement ->
                statement.setString(1, MENU_SHIFT_CHECK_COMPLETED_AUDIT_ACTION)
                statement.executeQuery().use { rs ->
                    buildList {
                        while (rs.next()) {
                            add(Json.parseToJsonElement(rs.getString("payload_json")) as JsonObject)
                        }
                    }
                }
            }
        }

    private fun itemChange(itemId: Long) =
        MenuShiftCheckItemChange(
            itemId = itemId,
            expectedIsAvailable = true,
            desiredIsAvailable = false,
        )

    private fun optionChange(option: VenueMenuOption) =
        MenuShiftCheckOptionChange(
            optionId = option.id,
            itemId = option.itemId,
            expectedIsAvailable = true,
            desiredIsAvailable = false,
        )

    private data class MenuFixture(
        val firstItem: VenueMenuItem,
        val secondItem: VenueMenuItem,
        val firstOption: VenueMenuOption,
        val secondOption: VenueMenuOption,
    )

    private data class NormalizationFixture(
        val item: VenueMenuItem,
        val options: List<VenueMenuOption>,
    )

    private data class OptionRowSnapshot(
        val id: Long,
        val venueId: Long,
        val itemId: Long,
        val name: String,
        val priceDeltaMinor: Long,
        val isAvailable: Boolean,
        val sortOrder: Int,
        val updatedAt: String,
    )

    private data class MenuDeleteAuditRow(
        val actorUserId: Long,
        val action: String,
        val entityType: String,
        val entityId: Long,
        val payload: JsonObject,
    )

    private data class RuleSnapshot(
        val version: Int,
        val status: String,
        val updatedAt: String,
    )

    private data class GiftRuleSnapshot(
        val version: Int,
        val status: String,
        val ruleUpdatedAt: String,
        val rewardId: Long,
        val primaryRewardItemId: Long,
        val rewardMode: String,
        val rewardUpdatedAt: String,
        val optionItemIds: List<Long>,
    )

    private class FailAfterOptionInsertsDataSource(
        private val delegate: DataSource,
        private val failOnInsert: Int,
    ) : DataSource by delegate {
        val completedOptionInserts = AtomicInteger()

        override fun getConnection(): Connection = wrap(delegate.connection)

        override fun getConnection(
            username: String?,
            password: String?,
        ): Connection = wrap(delegate.getConnection(username, password))

        private fun wrap(connection: Connection): Connection =
            object : Connection by connection {
                override fun prepareStatement(
                    sql: String,
                    autoGeneratedKeys: Int,
                ): PreparedStatement {
                    val statement = connection.prepareStatement(sql, autoGeneratedKeys)
                    if (!sql.trimStart().startsWith("INSERT INTO menu_item_options", ignoreCase = true)) {
                        return statement
                    }
                    return object : PreparedStatement by statement {
                        override fun executeUpdate(): Int {
                            val updated = statement.executeUpdate()
                            if (completedOptionInserts.incrementAndGet() == failOnInsert) {
                                throw SQLException("Synthetic option insert failure", "XX999")
                            }
                            return updated
                        }
                    }
                }
            }
    }

    private fun seedVenue(jdbcUrl: String): Long =
        DriverManager.getConnection(jdbcUrl, "sa", "").use { connection ->
            connection.prepareStatement(
                """
                MERGE INTO users (telegram_user_id, username, first_name, last_name)
                KEY (telegram_user_id)
                VALUES (?, 'menu-audit-actor', 'Menu', 'Actor')
                """.trimIndent(),
            ).use { statement ->
                statement.setLong(1, AUDIT_ACTOR_ID)
                statement.executeUpdate()
            }
            connection.prepareStatement(
                """
                INSERT INTO venues (name, city, address, status)
                VALUES ('Mix', 'Москва', 'Тверская, 1', ?)
                """.trimIndent(),
                Statement.RETURN_GENERATED_KEYS,
            ).use { statement ->
                statement.setString(1, VenueStatus.PUBLISHED.dbValue)
                statement.executeUpdate()
                statement.generatedKeys.use { keys ->
                    keys.next()
                    keys.getLong(1)
                }
            }
        }

    private companion object {
        const val AUDIT_ACTOR_ID = 101L
    }
}
