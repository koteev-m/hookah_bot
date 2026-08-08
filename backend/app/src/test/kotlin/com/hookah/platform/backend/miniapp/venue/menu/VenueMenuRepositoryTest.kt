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
import java.sql.DriverManager
import java.sql.SQLException
import java.sql.Statement
import java.util.HexFormat
import java.util.UUID
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
