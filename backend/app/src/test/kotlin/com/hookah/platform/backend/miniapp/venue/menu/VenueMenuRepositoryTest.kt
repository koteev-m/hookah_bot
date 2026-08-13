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
                    actorUserId = AUDIT_ACTOR_ID,
                    source = MenuItemCreateSource.VENUE_MINI_APP,
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
    fun `category create and compound update write exact safe audits and preserve no-op timestamp`() =
        runBlocking {
            val jdbcUrl = migratedJdbcUrl("venue-menu-category-management-audit")
            val venueId = seedVenue(jdbcUrl)
            val repository = VenueMenuRepository(dataSource(jdbcUrl))
            val privateName = "Private category callback @username initData"

            val category =
                repository.createCategory(
                    venueId = venueId,
                    name = privateName,
                    actorUserId = AUDIT_ACTOR_ID,
                    source = MenuItemAvailabilitySource.VENUE_MINI_APP,
                )
            val duplicateNameCategory =
                repository.createCategory(
                    venueId = venueId,
                    name = privateName,
                    actorUserId = AUDIT_ACTOR_ID,
                    source = MenuItemAvailabilitySource.VENUE_MINI_APP,
                )
            assertTrue(duplicateNameCategory.id != category.id)
            assertEquals(listOf(privateName, privateName), repository.getMenu(venueId).map { it.name })
            val createAudits = menuDeleteAudits(jdbcUrl, MENU_CATEGORY_CREATED_AUDIT_ACTION)
            assertEquals(2, createAudits.size)
            assertEquals(setOf(category.id, duplicateNameCategory.id), createAudits.map { it.entityId }.toSet())
            createAudits.forEach { audit ->
                assertEquals(AUDIT_ACTOR_ID, audit.actorUserId)
                assertEquals("menu_category", audit.entityType)
                assertEquals(setOf("venueId", "categoryId", "source"), audit.payload.keys)
                assertEquals(venueId, audit.payload.longValue("venueId"))
                assertEquals(audit.entityId, audit.payload.longValue("categoryId"))
                assertEquals("VENUE_MINI_APP", audit.payload.getValue("source").jsonPrimitive.content)
                assertFalse(audit.payload.toString().contains(privateName))
            }
            val createAudit = createAudits.single { it.entityId == category.id }
            assertEquals(AUDIT_ACTOR_ID, createAudit.actorUserId)
            assertEquals("menu_category", createAudit.entityType)
            assertEquals(category.id, createAudit.entityId)
            assertEquals(
                setOf("venueId", "categoryId", "source"),
                createAudit.payload.keys,
            )
            assertEquals(venueId, createAudit.payload.longValue("venueId"))
            assertEquals(category.id, createAudit.payload.longValue("categoryId"))
            assertEquals("VENUE_MINI_APP", createAudit.payload.getValue("source").jsonPrimitive.content)
            assertFalse(createAudit.payload.toString().contains(privateName))

            val updated =
                requireNotNull(
                    repository.updateCategory(
                        venueId = venueId,
                        categoryId = category.id,
                        name = "Renamed private section",
                        categoryType = MenuSemanticType.HOOKAH,
                        actorUserId = AUDIT_ACTOR_ID,
                        source = MenuItemAvailabilitySource.VENUE_MINI_APP,
                    ),
                )
            assertEquals("Renamed private section", updated.name)
            assertEquals(MenuSemanticType.HOOKAH, updated.categoryType)

            val renameAudit = menuDeleteAudits(jdbcUrl, MENU_CATEGORY_RENAMED_AUDIT_ACTION).single()
            assertEquals(AUDIT_ACTOR_ID, renameAudit.actorUserId)
            assertEquals("menu_category", renameAudit.entityType)
            assertEquals(category.id, renameAudit.entityId)
            assertEquals(setOf("venueId", "categoryId", "source"), renameAudit.payload.keys)
            assertEquals(venueId, renameAudit.payload.longValue("venueId"))
            assertEquals(category.id, renameAudit.payload.longValue("categoryId"))
            assertEquals("VENUE_MINI_APP", renameAudit.payload.getValue("source").jsonPrimitive.content)
            assertFalse(renameAudit.payload.toString().contains("Renamed private section"))
            val typeAudit = menuDeleteAudits(jdbcUrl, MENU_CATEGORY_TYPE_CHANGED_AUDIT_ACTION).single()
            assertEquals(AUDIT_ACTOR_ID, typeAudit.actorUserId)
            assertEquals("menu_category", typeAudit.entityType)
            assertEquals(category.id, typeAudit.entityId)
            assertEquals(
                setOf("venueId", "categoryId", "oldCategoryType", "newCategoryType", "source"),
                typeAudit.payload.keys,
            )
            assertEquals(venueId, typeAudit.payload.longValue("venueId"))
            assertEquals(category.id, typeAudit.payload.longValue("categoryId"))
            assertEquals("OTHER", typeAudit.payload.getValue("oldCategoryType").jsonPrimitive.content)
            assertEquals("HOOKAH", typeAudit.payload.getValue("newCategoryType").jsonPrimitive.content)
            assertEquals("VENUE_MINI_APP", typeAudit.payload.getValue("source").jsonPrimitive.content)

            val beforeNoOp = categoryRow(jdbcUrl, category.id)
            val noOp =
                requireNotNull(
                    repository.updateCategory(
                        venueId = venueId,
                        categoryId = category.id,
                        name = updated.name,
                        categoryType = updated.categoryType,
                        actorUserId = AUDIT_ACTOR_ID,
                        source = MenuItemAvailabilitySource.VENUE_MINI_APP,
                    ),
                )
            assertEquals(updated, noOp)
            assertEquals(beforeNoOp, categoryRow(jdbcUrl, category.id))
            assertEquals(1, menuDeleteAudits(jdbcUrl, MENU_CATEGORY_RENAMED_AUDIT_ACTION).size)
            assertEquals(1, menuDeleteAudits(jdbcUrl, MENU_CATEGORY_TYPE_CHANGED_AUDIT_ACTION).size)
        }

    @Test
    fun `category create audit failure rolls back physical category and audit`() =
        runBlocking {
            val jdbcUrl = migratedJdbcUrl("venue-menu-category-create-rollback")
            val venueId = seedVenue(jdbcUrl)
            val ds = dataSource(jdbcUrl)
            val delegate = AuditLogRepository(ds, Json)
            val failingWriter =
                TransactionalAuditLogWriter { connection, actorUserId, action, entityType, entityId, payload ->
                    delegate.appendJson(connection, actorUserId, action, entityType, entityId, payload)
                    if (action == MENU_CATEGORY_CREATED_AUDIT_ACTION) {
                        throw SQLException("Synthetic category create audit failure", "XX999")
                    }
                }

            assertFailsWith<DatabaseUnavailableException> {
                VenueMenuRepository(ds, failingWriter).createCategory(
                    venueId = venueId,
                    name = "Must roll back private category",
                    actorUserId = AUDIT_ACTOR_ID,
                    source = MenuItemAvailabilitySource.VENUE_MINI_APP,
                )
            }

            assertTrue(VenueMenuRepository(ds).getMenu(venueId).isEmpty())
            assertTrue(menuDeleteAudits(jdbcUrl, MENU_CATEGORY_CREATED_AUDIT_ACTION).isEmpty())
        }

    @Test
    fun `category compound update rolls back mutation and first audit when second audit fails`() =
        runBlocking {
            val jdbcUrl = migratedJdbcUrl("venue-menu-category-management-rollback")
            val venueId = seedVenue(jdbcUrl)
            val ds = dataSource(jdbcUrl)
            val fixtureRepository = VenueMenuRepository(ds)
            val category = fixtureRepository.createCategory(venueId, "Rollback category")
            val before = categoryRow(jdbcUrl, category.id)
            val delegate = AuditLogRepository(ds, Json)
            val failingWriter =
                TransactionalAuditLogWriter { connection, actorUserId, action, entityType, entityId, payload ->
                    delegate.appendJson(connection, actorUserId, action, entityType, entityId, payload)
                    if (action == MENU_CATEGORY_TYPE_CHANGED_AUDIT_ACTION) {
                        throw SQLException("Synthetic category type audit failure", "XX999")
                    }
                }

            assertFailsWith<DatabaseUnavailableException> {
                VenueMenuRepository(ds, failingWriter).updateCategory(
                    venueId = venueId,
                    categoryId = category.id,
                    name = "Must roll back",
                    categoryType = MenuSemanticType.DRINK,
                    actorUserId = AUDIT_ACTOR_ID,
                    source = MenuItemAvailabilitySource.VENUE_MINI_APP,
                )
            }

            assertEquals(before, categoryRow(jdbcUrl, category.id))
            assertTrue(menuDeleteAudits(jdbcUrl, MENU_CATEGORY_RENAMED_AUDIT_ACTION).isEmpty())
            assertTrue(menuDeleteAudits(jdbcUrl, MENU_CATEGORY_TYPE_CHANGED_AUDIT_ACTION).isEmpty())
        }

    @Test
    fun `price only currency only and combined updates each write one price audit`() =
        runBlocking {
            val jdbcUrl = migratedJdbcUrl("venue-menu-price-delta-audits")
            val venueId = seedVenue(jdbcUrl)
            val repository = VenueMenuRepository(dataSource(jdbcUrl))
            val category = repository.createCategory(venueId, "Prices")
            val item =
                requireNotNull(
                    repository.createItem(
                        venueId = venueId,
                        categoryId = category.id,
                        name = "Item",
                        priceMinor = 100,
                        currency = "RUB",
                        isAvailable = true,
                        actorUserId = AUDIT_ACTOR_ID,
                        source = MenuItemCreateSource.VENUE_MINI_APP,
                    ),
                )

            requireNotNull(
                repository.updateItem(
                    venueId = venueId,
                    itemId = item.id,
                    categoryId = null,
                    name = null,
                    priceMinor = 150,
                    currency = null,
                    isAvailable = null,
                    actorUserId = AUDIT_ACTOR_ID,
                    source = MenuItemAvailabilitySource.VENUE_MINI_APP,
                ),
            )
            requireNotNull(
                repository.updateItem(
                    venueId = venueId,
                    itemId = item.id,
                    categoryId = null,
                    name = null,
                    priceMinor = null,
                    currency = "USD",
                    isAvailable = null,
                    actorUserId = AUDIT_ACTOR_ID,
                    source = MenuItemAvailabilitySource.VENUE_MINI_APP,
                ),
            )
            requireNotNull(
                repository.updateItem(
                    venueId = venueId,
                    itemId = item.id,
                    categoryId = null,
                    name = null,
                    priceMinor = 225,
                    currency = "EUR",
                    isAvailable = null,
                    actorUserId = AUDIT_ACTOR_ID,
                    source = MenuItemAvailabilitySource.VENUE_MINI_APP,
                ),
            )

            val audits = menuDeleteAudits(jdbcUrl, MENU_ITEM_PRICE_CHANGED_AUDIT_ACTION)
            assertEquals(3, audits.size)
            assertEquals(
                listOf("100", "150", "150"),
                audits.map { it.payload.getValue("oldPriceMinor").jsonPrimitive.content },
            )
            assertEquals(
                listOf("150", "150", "225"),
                audits.map { it.payload.getValue("newPriceMinor").jsonPrimitive.content },
            )
            assertEquals(
                listOf("RUB", "RUB", "USD"),
                audits.map { it.payload.getValue("oldCurrency").jsonPrimitive.content },
            )
            assertEquals(
                listOf("RUB", "USD", "EUR"),
                audits.map { it.payload.getValue("newCurrency").jsonPrimitive.content },
            )
            assertTrue(audits.all { it.actorUserId == AUDIT_ACTOR_ID })
            assertTrue(audits.all { it.entityType == "menu_item" && it.entityId == item.id })
            assertTrue(audits.all { it.payload.longValue("venueId") == venueId })
            assertTrue(audits.all { it.payload.longValue("itemId") == item.id })
            assertTrue(audits.all { it.payload.getValue("source").jsonPrimitive.content == "VENUE_MINI_APP" })
        }

    @Test
    fun `default category seed is atomic audited and idempotent`() =
        runBlocking {
            val jdbcUrl = migratedJdbcUrl("venue-menu-default-seed")
            val venueId = seedVenue(jdbcUrl)
            val ds = dataSource(jdbcUrl)
            val delegate = AuditLogRepository(ds, Json)
            var createAuditCount = 0
            val failingWriter =
                TransactionalAuditLogWriter { connection, actorUserId, action, entityType, entityId, payload ->
                    delegate.appendJson(connection, actorUserId, action, entityType, entityId, payload)
                    if (action == MENU_CATEGORY_CREATED_AUDIT_ACTION && ++createAuditCount == 2) {
                        throw SQLException("Synthetic mid-seed audit failure", "XX999")
                    }
                }
            val seeds = INITIAL_VENUE_MENU_CATEGORY_SEEDS

            assertFailsWith<DatabaseUnavailableException> {
                VenueMenuRepository(ds, failingWriter).createMissingCategories(
                    venueId = venueId,
                    seeds = seeds,
                    actorUserId = AUDIT_ACTOR_ID,
                    source = MenuItemAvailabilitySource.TELEGRAM_BOT,
                )
            }
            assertTrue(VenueMenuRepository(ds).getMenu(venueId).isEmpty())
            assertTrue(menuDeleteAudits(jdbcUrl, MENU_CATEGORY_CREATED_AUDIT_ACTION).isEmpty())

            val repository = VenueMenuRepository(ds)
            val seeded =
                repository.createMissingCategories(
                    venueId = venueId,
                    seeds = seeds,
                    actorUserId = AUDIT_ACTOR_ID,
                    source = MenuItemAvailabilitySource.TELEGRAM_BOT,
                )
            assertEquals(seeds.map { it.name }, seeded.map { it.name })
            assertEquals(listOf(0, 1, 2), seeded.map { it.sortOrder })
            assertTrue(seeded.all { it.categoryType == MenuSemanticType.OTHER })
            assertTrue(seeded.all { it.items.isEmpty() })
            val audits = menuDeleteAudits(jdbcUrl, MENU_CATEGORY_CREATED_AUDIT_ACTION)
            assertEquals(3, audits.size)
            assertTrue(audits.all { it.actorUserId == AUDIT_ACTOR_ID })
            assertTrue(audits.all { it.payload.getValue("source").jsonPrimitive.content == "TELEGRAM_BOT" })
            assertEquals(seeded.map { it.id }, audits.map { it.entityId })
            audits.forEach { audit ->
                assertEquals("menu_category", audit.entityType)
                assertEquals(setOf("venueId", "categoryId", "source"), audit.payload.keys)
                assertEquals(venueId, audit.payload.longValue("venueId"))
                assertEquals(audit.entityId, audit.payload.longValue("categoryId"))
            }

            val repeated =
                repository.createMissingCategories(
                    venueId = venueId,
                    seeds = seeds,
                    actorUserId = AUDIT_ACTOR_ID,
                    source = MenuItemAvailabilitySource.TELEGRAM_BOT,
                )
            assertEquals(seeded, repeated)
            assertEquals(3, menuDeleteAudits(jdbcUrl, MENU_CATEGORY_CREATED_AUDIT_ACTION).size)
        }

    @Test
    fun `default category seed appends only missing defaults preserving custom normalized and complete rows`() =
        runBlocking {
            val jdbcUrl = migratedJdbcUrl("venue-menu-default-seed-preservation")
            val venueId = seedVenue(jdbcUrl)
            val repository = VenueMenuRepository(dataSource(jdbcUrl))
            val custom =
                repository.createCategory(
                    venueId = venueId,
                    name = "Авторское",
                    actorUserId = AUDIT_ACTOR_ID,
                    source = MenuItemAvailabilitySource.VENUE_MINI_APP,
                    categoryType = MenuSemanticType.FOOD,
                )
            val existingDrink =
                repository.createCategory(
                    venueId = venueId,
                    name = "  напитки  ",
                    actorUserId = AUDIT_ACTOR_ID,
                    source = MenuItemAvailabilitySource.VENUE_MINI_APP,
                    categoryType = MenuSemanticType.DRINK,
                )
            val existingHookah =
                repository.createCategory(
                    venueId = venueId,
                    name = "Кальянное меню",
                    actorUserId = AUDIT_ACTOR_ID,
                    source = MenuItemAvailabilitySource.VENUE_MINI_APP,
                    categoryType = MenuSemanticType.HOOKAH,
                )
            val item =
                requireNotNull(
                    repository.createItem(
                        venueId = venueId,
                        categoryId = existingHookah.id,
                        name = "Существующий кальян",
                        priceMinor = 1_000,
                        currency = "RUB",
                        isAvailable = true,
                        actorUserId = AUDIT_ACTOR_ID,
                        source = MenuItemCreateSource.VENUE_MINI_APP,
                    ),
                )
            val option =
                requireNotNull(
                    repository.createOption(
                        venueId = venueId,
                        itemId = item.id,
                        name = "Авторский вкус",
                        priceDeltaMinor = 100,
                        isAvailable = false,
                        actorUserId = AUDIT_ACTOR_ID,
                        source = MenuOptionCreateSource.VENUE_MINI_APP,
                    ),
                )
            val rowsBefore = categoryRows(jdbcUrl, venueId)
            val menuBefore = repository.getMenu(venueId)
            val setupAuditCount = menuDeleteAudits(jdbcUrl, MENU_CATEGORY_CREATED_AUDIT_ACTION).size

            val bootstrapped =
                repository.createMissingCategories(
                    venueId = venueId,
                    seeds = INITIAL_VENUE_MENU_CATEGORY_SEEDS,
                    actorUserId = AUDIT_ACTOR_ID,
                    source = MenuItemAvailabilitySource.VENUE_MINI_APP,
                )

            assertEquals(
                listOf("Авторское", "  напитки  ", "Кальянное меню", "Кухня"),
                bootstrapped.map { it.name },
            )
            assertEquals(listOf(0, 1, 2, 3), bootstrapped.map { it.sortOrder })
            assertEquals(rowsBefore, categoryRows(jdbcUrl, venueId).take(rowsBefore.size))
            assertEquals(MenuSemanticType.FOOD, bootstrapped.single { it.id == custom.id }.categoryType)
            assertEquals(MenuSemanticType.DRINK, bootstrapped.single { it.id == existingDrink.id }.categoryType)
            assertEquals(MenuSemanticType.HOOKAH, bootstrapped.single { it.id == existingHookah.id }.categoryType)
            assertEquals(MenuSemanticType.OTHER, bootstrapped.last().categoryType)
            assertEquals(
                listOf(item.copy(options = listOf(option))),
                bootstrapped.single { it.id == existingHookah.id }.items,
            )
            assertTrue(bootstrapped.last().items.isEmpty())
            val bootstrapAudits =
                menuDeleteAudits(jdbcUrl, MENU_CATEGORY_CREATED_AUDIT_ACTION).drop(setupAuditCount)
            assertEquals(1, bootstrapAudits.size)
            assertEquals(AUDIT_ACTOR_ID, bootstrapAudits.single().actorUserId)
            assertEquals("VENUE_MINI_APP", bootstrapAudits.single().payload.getValue("source").jsonPrimitive.content)
            assertEquals(setOf("venueId", "categoryId", "source"), bootstrapAudits.single().payload.keys)

            val rowsAfterBootstrap = categoryRows(jdbcUrl, venueId)
            val repeated =
                repository.createMissingCategories(
                    venueId = venueId,
                    seeds = INITIAL_VENUE_MENU_CATEGORY_SEEDS,
                    actorUserId = AUDIT_ACTOR_ID,
                    source = MenuItemAvailabilitySource.TELEGRAM_BOT,
                )
            assertEquals(bootstrapped, repeated)
            assertEquals(rowsAfterBootstrap, categoryRows(jdbcUrl, venueId))
            assertEquals(1, menuDeleteAudits(jdbcUrl, MENU_CATEGORY_CREATED_AUDIT_ACTION).size - setupAuditCount)
            assertEquals(menuBefore.flatMap { it.items }, repeated.flatMap { it.items })
        }

    @Test
    fun `partial default category seed preserves custom and drinks then appends two missing defaults`() =
        runBlocking {
            val jdbcUrl = migratedJdbcUrl("venue-menu-default-seed-partial")
            val venueId = seedVenue(jdbcUrl)
            val repository = VenueMenuRepository(dataSource(jdbcUrl))
            val custom =
                repository.createCategory(
                    venueId = venueId,
                    name = "Авторские миксы",
                    actorUserId = AUDIT_ACTOR_ID,
                    source = MenuItemAvailabilitySource.VENUE_MINI_APP,
                    categoryType = MenuSemanticType.HOOKAH,
                )
            val drinks =
                repository.createCategory(
                    venueId = venueId,
                    name = "Напитки",
                    actorUserId = AUDIT_ACTOR_ID,
                    source = MenuItemAvailabilitySource.VENUE_MINI_APP,
                    categoryType = MenuSemanticType.DRINK,
                )
            val rowsBefore = categoryRows(jdbcUrl, venueId)
            val setupAuditCount = menuDeleteAudits(jdbcUrl, MENU_CATEGORY_CREATED_AUDIT_ACTION).size

            val result =
                repository.createMissingCategories(
                    venueId = venueId,
                    seeds = INITIAL_VENUE_MENU_CATEGORY_SEEDS,
                    actorUserId = AUDIT_ACTOR_ID,
                    source = MenuItemAvailabilitySource.VENUE_MINI_APP,
                )

            assertEquals(
                listOf("Авторские миксы", "Напитки", "Кальянное меню", "Кухня"),
                result.map { it.name },
            )
            assertEquals(listOf(0, 1, 2, 3), result.map { it.sortOrder })
            assertEquals(rowsBefore, categoryRows(jdbcUrl, venueId).take(rowsBefore.size))
            assertEquals(MenuSemanticType.HOOKAH, result.single { it.id == custom.id }.categoryType)
            assertEquals(MenuSemanticType.DRINK, result.single { it.id == drinks.id }.categoryType)
            assertEquals(
                listOf(MenuSemanticType.OTHER, MenuSemanticType.OTHER),
                result.drop(2).map { it.categoryType },
            )
            val bootstrapAudits =
                menuDeleteAudits(jdbcUrl, MENU_CATEGORY_CREATED_AUDIT_ACTION).drop(setupAuditCount)
            assertEquals(2, bootstrapAudits.size)
            assertEquals(result.drop(2).map { it.id }, bootstrapAudits.map { it.entityId })
            assertTrue(bootstrapAudits.all { it.actorUserId == AUDIT_ACTOR_ID })
            assertTrue(
                bootstrapAudits.all {
                    it.payload.keys == setOf("venueId", "categoryId", "source") &&
                        it.payload.getValue("source").jsonPrimitive.content == "VENUE_MINI_APP"
                },
            )
            assertTrue(result.flatMap { it.items }.isEmpty())
        }

    @Test
    fun `complete default category seed is a row timestamp and audit no-op preserving existing types`() =
        runBlocking {
            val jdbcUrl = migratedJdbcUrl("venue-menu-default-seed-complete")
            val venueId = seedVenue(jdbcUrl)
            val repository = VenueMenuRepository(dataSource(jdbcUrl))
            val existing =
                listOf(
                    repository.createCategory(
                        venueId,
                        "Кальянное меню",
                        AUDIT_ACTOR_ID,
                        MenuItemAvailabilitySource.VENUE_MINI_APP,
                        MenuSemanticType.HOOKAH,
                    ),
                    repository.createCategory(
                        venueId,
                        "Напитки",
                        AUDIT_ACTOR_ID,
                        MenuItemAvailabilitySource.VENUE_MINI_APP,
                        MenuSemanticType.DRINK,
                    ),
                    repository.createCategory(
                        venueId,
                        "Кухня",
                        AUDIT_ACTOR_ID,
                        MenuItemAvailabilitySource.VENUE_MINI_APP,
                        MenuSemanticType.FOOD,
                    ),
                )
            val rowsBefore = categoryRows(jdbcUrl, venueId)
            val auditsBefore = menuDeleteAudits(jdbcUrl, MENU_CATEGORY_CREATED_AUDIT_ACTION)

            val result =
                repository.createMissingCategories(
                    venueId = venueId,
                    seeds = INITIAL_VENUE_MENU_CATEGORY_SEEDS,
                    actorUserId = AUDIT_ACTOR_ID,
                    source = MenuItemAvailabilitySource.TELEGRAM_BOT,
                )

            assertEquals(existing, result)
            assertEquals(
                listOf(MenuSemanticType.HOOKAH, MenuSemanticType.DRINK, MenuSemanticType.FOOD),
                result.map { it.categoryType },
            )
            assertEquals(rowsBefore, categoryRows(jdbcUrl, venueId))
            assertEquals(auditsBefore, menuDeleteAudits(jdbcUrl, MENU_CATEGORY_CREATED_AUDIT_ACTION))
            assertTrue(result.flatMap { it.items }.isEmpty())
        }

    @Test
    fun `compound item update writes five exact audits and final audit failure rolls everything back`() =
        runBlocking {
            val jdbcUrl = migratedJdbcUrl("venue-menu-item-management-audit")
            val venueId = seedVenue(jdbcUrl)
            val ds = dataSource(jdbcUrl)
            val repository = VenueMenuRepository(ds)
            val sourceCategory = repository.createCategory(venueId, "Source")
            val destinationCategory = repository.createCategory(venueId, "Destination")
            val item =
                requireNotNull(
                    repository.createItem(
                        venueId = venueId,
                        categoryId = sourceCategory.id,
                        name = "Private old item",
                        priceMinor = 100,
                        currency = "RUB",
                        isAvailable = true,
                        actorUserId = AUDIT_ACTOR_ID,
                        source = MenuItemCreateSource.VENUE_MINI_APP,
                    ),
                )

            val updated =
                requireNotNull(
                    repository.updateItem(
                        venueId = venueId,
                        itemId = item.id,
                        categoryId = destinationCategory.id,
                        name = "Private new callback @username",
                        priceMinor = 250,
                        currency = "USD",
                        isAvailable = false,
                        actorUserId = AUDIT_ACTOR_ID,
                        source = MenuItemAvailabilitySource.VENUE_MINI_APP,
                        itemType = MenuSemanticType.DRINK,
                        itemTypeSpecified = true,
                    ),
                )
            assertEquals(destinationCategory.id, updated.categoryId)
            assertEquals("Private new callback @username", updated.name)
            assertEquals(250, updated.priceMinor)
            assertEquals("USD", updated.currency)
            assertEquals(MenuSemanticType.DRINK, updated.itemType)
            assertFalse(updated.isAvailable)

            val expectedActions =
                listOf(
                    MENU_ITEM_RENAMED_AUDIT_ACTION,
                    MENU_ITEM_PRICE_CHANGED_AUDIT_ACTION,
                    MENU_ITEM_TYPE_CHANGED_AUDIT_ACTION,
                    MENU_ITEM_CATEGORY_MOVED_AUDIT_ACTION,
                    MENU_ITEM_AVAILABILITY_CHANGED_AUDIT_ACTION,
                )
            val audits = expectedActions.map { action -> menuDeleteAudits(jdbcUrl, action).single() }
            assertTrue(audits.all { it.actorUserId == AUDIT_ACTOR_ID && it.entityId == item.id })
            assertEquals(
                setOf("venueId", "itemId", "source"),
                audits[0].payload.keys,
            )
            assertEquals(
                setOf(
                    "venueId",
                    "itemId",
                    "oldPriceMinor",
                    "newPriceMinor",
                    "oldCurrency",
                    "newCurrency",
                    "source",
                ),
                audits[1].payload.keys,
            )
            assertEquals("100", audits[1].payload.getValue("oldPriceMinor").jsonPrimitive.content)
            assertEquals("250", audits[1].payload.getValue("newPriceMinor").jsonPrimitive.content)
            assertEquals("RUB", audits[1].payload.getValue("oldCurrency").jsonPrimitive.content)
            assertEquals("USD", audits[1].payload.getValue("newCurrency").jsonPrimitive.content)
            assertEquals(
                setOf("venueId", "itemId", "oldItemType", "newItemType", "source"),
                audits[2].payload.keys,
            )
            assertEquals("null", audits[2].payload.getValue("oldItemType").toString())
            assertEquals("DRINK", audits[2].payload.getValue("newItemType").jsonPrimitive.content)
            assertEquals(
                setOf("venueId", "itemId", "oldCategoryId", "newCategoryId", "source"),
                audits[3].payload.keys,
            )
            assertEquals(sourceCategory.id, audits[3].payload.longValue("oldCategoryId"))
            assertEquals(destinationCategory.id, audits[3].payload.longValue("newCategoryId"))
            audits.forEach { audit ->
                assertEquals("menu_item", audit.entityType)
                assertEquals(venueId, audit.payload.longValue("venueId"))
                assertEquals(item.id, audit.payload.longValue("itemId"))
                val serialized = audit.payload.toString()
                assertFalse(serialized.contains("Private old item"))
                assertFalse(serialized.contains("Private new callback"))
                assertFalse(serialized.contains("@username"))
                assertEquals("VENUE_MINI_APP", audit.payload.getValue("source").jsonPrimitive.content)
            }

            val beforeNoOp = itemRow(jdbcUrl, item.id)
            requireNotNull(
                repository.updateItem(
                    venueId = venueId,
                    itemId = item.id,
                    categoryId = updated.categoryId,
                    name = updated.name,
                    priceMinor = updated.priceMinor,
                    currency = updated.currency,
                    isAvailable = updated.isAvailable,
                    actorUserId = AUDIT_ACTOR_ID,
                    source = MenuItemAvailabilitySource.VENUE_MINI_APP,
                    itemType = updated.itemType,
                    itemTypeSpecified = true,
                ),
            )
            assertEquals(beforeNoOp, itemRow(jdbcUrl, item.id))
            expectedActions.forEach { action -> assertEquals(1, menuDeleteAudits(jdbcUrl, action).size) }

            val delegate = AuditLogRepository(ds, Json)
            val failingWriter =
                TransactionalAuditLogWriter { connection, actorUserId, action, entityType, entityId, payload ->
                    delegate.appendJson(connection, actorUserId, action, entityType, entityId, payload)
                    if (action == MENU_ITEM_AVAILABILITY_CHANGED_AUDIT_ACTION) {
                        throw SQLException("Synthetic final item audit failure", "XX999")
                    }
                }
            assertFailsWith<DatabaseUnavailableException> {
                VenueMenuRepository(ds, failingWriter).updateItem(
                    venueId = venueId,
                    itemId = item.id,
                    categoryId = sourceCategory.id,
                    name = "Must roll back",
                    priceMinor = 999,
                    currency = "RUB",
                    isAvailable = true,
                    actorUserId = AUDIT_ACTOR_ID,
                    source = MenuItemAvailabilitySource.TELEGRAM_BOT,
                    itemType = MenuSemanticType.TEA,
                    itemTypeSpecified = true,
                )
            }
            assertEquals(beforeNoOp, itemRow(jdbcUrl, item.id))
            expectedActions.forEach { action -> assertEquals(1, menuDeleteAudits(jdbcUrl, action).size) }
        }

    @Test
    fun `category and item reorder require exact sets hash committed order and roll back failed audits`() =
        runBlocking {
            assertEquals(
                "0f70d16ec6e0bb7972f364c9173644e2520b3ed203e51f0c8053981d15c29d69",
                menuOrderSha256(listOf(11, 7, 42)),
            )
            assertEquals(
                "7cbbc4052a1d37f0573dbe6f6fc45d391969c6ec8be01671b756f5e20f861c78",
                menuOrderSha256(listOf(7, 11, 42)),
            )
            assertEquals(
                "f7c3668944a7d72cbe71e9398d7d570b6d573456b6524b9e0e0633aa794f4061",
                menuOrderSha256(emptyList()),
            )

            val jdbcUrl = migratedJdbcUrl("venue-menu-reorder-management")
            val venueId = seedVenue(jdbcUrl)
            val ds = dataSource(jdbcUrl)
            val repository = VenueMenuRepository(ds)
            val categories =
                listOf("One", "Two", "Three").map { name -> repository.createCategory(venueId, name) }
            val oldCategoryOrder = categories.map { it.id }
            val newCategoryOrder = oldCategoryOrder.reversed()
            assertTrue(
                repository.reorderCategories(
                    venueId,
                    newCategoryOrder,
                    AUDIT_ACTOR_ID,
                    MenuItemAvailabilitySource.VENUE_MINI_APP,
                ),
            )
            val categoryAudit = menuDeleteAudits(jdbcUrl, MENU_CATEGORIES_REORDERED_AUDIT_ACTION).single()
            assertEquals(AUDIT_ACTOR_ID, categoryAudit.actorUserId)
            assertEquals("venue", categoryAudit.entityType)
            assertEquals(venueId, categoryAudit.entityId)
            assertEquals(
                setOf("venueId", "categoryCount", "oldOrderSha256", "newOrderSha256", "source"),
                categoryAudit.payload.keys,
            )
            assertEquals(venueId, categoryAudit.payload.longValue("venueId"))
            assertEquals(oldCategoryOrder.size, categoryAudit.payload.getValue("categoryCount").jsonPrimitive.int)
            assertEquals(
                menuOrderSha256(oldCategoryOrder),
                categoryAudit.payload.getValue("oldOrderSha256").jsonPrimitive.content,
            )
            assertEquals(
                menuOrderSha256(newCategoryOrder),
                categoryAudit.payload.getValue("newOrderSha256").jsonPrimitive.content,
            )
            assertEquals("VENUE_MINI_APP", categoryAudit.payload.getValue("source").jsonPrimitive.content)
            assertFalse(categoryAudit.payload.toString().contains(newCategoryOrder.joinToString(",")))

            val categoryRowsBeforeNoOp = categoryRows(jdbcUrl, venueId)
            assertTrue(
                repository.reorderCategories(
                    venueId,
                    newCategoryOrder,
                    AUDIT_ACTOR_ID,
                    MenuItemAvailabilitySource.VENUE_MINI_APP,
                ),
            )
            assertEquals(categoryRowsBeforeNoOp, categoryRows(jdbcUrl, venueId))
            assertFalse(
                repository.reorderCategories(
                    venueId,
                    newCategoryOrder.dropLast(1),
                    AUDIT_ACTOR_ID,
                    MenuItemAvailabilitySource.VENUE_MINI_APP,
                ),
            )
            assertFalse(
                repository.reorderCategories(
                    venueId,
                    newCategoryOrder + Long.MAX_VALUE,
                    AUDIT_ACTOR_ID,
                    MenuItemAvailabilitySource.VENUE_MINI_APP,
                ),
            )
            assertFalse(
                repository.reorderCategories(
                    venueId,
                    listOf(newCategoryOrder[0], newCategoryOrder[0], newCategoryOrder[2]),
                    AUDIT_ACTOR_ID,
                    MenuItemAvailabilitySource.VENUE_MINI_APP,
                ),
            )
            assertEquals(1, menuDeleteAudits(jdbcUrl, MENU_CATEGORIES_REORDERED_AUDIT_ACTION).size)

            val delegate = AuditLogRepository(ds, Json)
            val failingCategoryWriter =
                TransactionalAuditLogWriter { connection, actorUserId, action, entityType, entityId, payload ->
                    delegate.appendJson(connection, actorUserId, action, entityType, entityId, payload)
                    if (action == MENU_CATEGORIES_REORDERED_AUDIT_ACTION) {
                        throw SQLException("Synthetic category reorder audit failure", "XX999")
                    }
                }
            assertFailsWith<DatabaseUnavailableException> {
                VenueMenuRepository(ds, failingCategoryWriter).reorderCategories(
                    venueId,
                    oldCategoryOrder,
                    AUDIT_ACTOR_ID,
                    MenuItemAvailabilitySource.VENUE_MINI_APP,
                )
            }
            assertEquals(newCategoryOrder, repository.getMenu(venueId).map { it.id })
            assertEquals(1, menuDeleteAudits(jdbcUrl, MENU_CATEGORIES_REORDERED_AUDIT_ACTION).size)

            val targetCategory = categories.first()
            val items =
                listOf("First", "Second", "Third").mapIndexed { index, name ->
                    requireNotNull(
                        repository.createItem(
                            venueId = venueId,
                            categoryId = targetCategory.id,
                            name = name,
                            priceMinor = 100L + index,
                            currency = "RUB",
                            isAvailable = true,
                            actorUserId = AUDIT_ACTOR_ID,
                            source = MenuItemCreateSource.VENUE_MINI_APP,
                        ),
                    )
                }
            val oldItemOrder = items.map { it.id }
            val newItemOrder = oldItemOrder.reversed()
            assertTrue(
                repository.reorderItems(
                    venueId,
                    targetCategory.id,
                    newItemOrder,
                    AUDIT_ACTOR_ID,
                    MenuItemAvailabilitySource.VENUE_MINI_APP,
                ),
            )
            val itemAudit = menuDeleteAudits(jdbcUrl, MENU_ITEMS_REORDERED_AUDIT_ACTION).single()
            assertEquals(AUDIT_ACTOR_ID, itemAudit.actorUserId)
            assertEquals("menu_category", itemAudit.entityType)
            assertEquals(targetCategory.id, itemAudit.entityId)
            assertEquals(
                setOf(
                    "venueId",
                    "categoryId",
                    "itemCount",
                    "oldOrderSha256",
                    "newOrderSha256",
                    "source",
                ),
                itemAudit.payload.keys,
            )
            assertEquals(venueId, itemAudit.payload.longValue("venueId"))
            assertEquals(targetCategory.id, itemAudit.payload.longValue("categoryId"))
            assertEquals(oldItemOrder.size, itemAudit.payload.getValue("itemCount").jsonPrimitive.int)
            assertEquals(
                menuOrderSha256(oldItemOrder),
                itemAudit.payload.getValue("oldOrderSha256").jsonPrimitive.content,
            )
            assertEquals(
                menuOrderSha256(newItemOrder),
                itemAudit.payload.getValue("newOrderSha256").jsonPrimitive.content,
            )
            assertEquals("VENUE_MINI_APP", itemAudit.payload.getValue("source").jsonPrimitive.content)
            val itemRowsBeforeNoOp = items.map { itemRow(jdbcUrl, it.id) }
            assertTrue(
                repository.reorderItems(
                    venueId,
                    targetCategory.id,
                    newItemOrder,
                    AUDIT_ACTOR_ID,
                    MenuItemAvailabilitySource.VENUE_MINI_APP,
                ),
            )
            assertEquals(itemRowsBeforeNoOp, items.map { itemRow(jdbcUrl, it.id) })
            assertFalse(
                repository.reorderItems(
                    venueId,
                    targetCategory.id,
                    newItemOrder.dropLast(1),
                    AUDIT_ACTOR_ID,
                    MenuItemAvailabilitySource.VENUE_MINI_APP,
                ),
            )
            assertFalse(
                repository.reorderItems(
                    venueId,
                    targetCategory.id,
                    listOf(newItemOrder[0], newItemOrder[0], newItemOrder[2]),
                    AUDIT_ACTOR_ID,
                    MenuItemAvailabilitySource.VENUE_MINI_APP,
                ),
            )
            val foreignItem =
                requireNotNull(
                    repository.createItem(
                        venueId = venueId,
                        categoryId = categories[1].id,
                        name = "Foreign category item",
                        priceMinor = 500,
                        currency = "RUB",
                        isAvailable = true,
                        actorUserId = AUDIT_ACTOR_ID,
                        source = MenuItemCreateSource.VENUE_MINI_APP,
                    ),
                )
            assertFalse(
                repository.reorderItems(
                    venueId,
                    targetCategory.id,
                    newItemOrder.dropLast(1) + foreignItem.id,
                    AUDIT_ACTOR_ID,
                    MenuItemAvailabilitySource.VENUE_MINI_APP,
                ),
            )

            val failingWriter =
                TransactionalAuditLogWriter { connection, actorUserId, action, entityType, entityId, payload ->
                    delegate.appendJson(connection, actorUserId, action, entityType, entityId, payload)
                    if (action == MENU_ITEMS_REORDERED_AUDIT_ACTION) {
                        throw SQLException("Synthetic item reorder audit failure", "XX999")
                    }
                }
            assertFailsWith<DatabaseUnavailableException> {
                VenueMenuRepository(ds, failingWriter).reorderItems(
                    venueId,
                    targetCategory.id,
                    oldItemOrder,
                    AUDIT_ACTOR_ID,
                    MenuItemAvailabilitySource.VENUE_MINI_APP,
                )
            }
            assertEquals(
                newItemOrder,
                repository.getMenu(venueId).single { it.id == targetCategory.id }.items.map { it.id },
            )
            assertEquals(1, menuDeleteAudits(jdbcUrl, MENU_ITEMS_REORDERED_AUDIT_ACTION).size)
        }

    @Test
    fun `item create preserves semantics and writes one exact safe audit per physical duplicate row`() =
        runBlocking {
            val jdbcUrl = migratedJdbcUrl("venue-menu-item-create-success")
            val venueId = seedVenue(jdbcUrl)
            val repository = VenueMenuRepository(dataSource(jdbcUrl))
            val category = repository.createCategory(venueId, "Private category name")
            val duplicateName = "Private callback initData @username secret item"

            val first =
                requireNotNull(
                    repository.createItem(
                        venueId = venueId,
                        categoryId = category.id,
                        name = duplicateName,
                        priceMinor = 123_456,
                        currency = "RUB",
                        isAvailable = false,
                        actorUserId = AUDIT_ACTOR_ID,
                        source = MenuItemCreateSource.VENUE_MINI_APP,
                    ),
                )
            val second =
                requireNotNull(
                    repository.createItem(
                        venueId = venueId,
                        categoryId = category.id,
                        name = duplicateName,
                        priceMinor = 654_321,
                        currency = "USD",
                        isAvailable = true,
                        actorUserId = AUDIT_ACTOR_ID,
                        source = MenuItemCreateSource.TELEGRAM_BOT,
                        itemType = MenuSemanticType.DRINK,
                    ),
                )

            val persistedItems = repository.getMenu(venueId).single().items
            assertEquals(listOf(first, second), persistedItems)
            assertEquals(listOf(0, 1), persistedItems.map(VenueMenuItem::sortOrder))
            assertEquals(listOf(123_456L, 654_321L), persistedItems.map(VenueMenuItem::priceMinor))
            assertEquals(listOf("RUB", "USD"), persistedItems.map(VenueMenuItem::currency))
            assertEquals(listOf(false, true), persistedItems.map(VenueMenuItem::isAvailable))
            assertEquals(listOf(category.id, category.id), persistedItems.map(VenueMenuItem::categoryId))
            assertEquals(listOf(null, MenuSemanticType.DRINK), persistedItems.map(VenueMenuItem::itemType))
            assertTrue(persistedItems.all { it.options.isEmpty() })

            val audits = menuItemCreateAudits(jdbcUrl)
            assertEquals(2, audits.size)
            assertMenuItemCreateAudit(
                audit = audits[0],
                venueId = venueId,
                itemId = first.id,
                actorUserId = AUDIT_ACTOR_ID,
                source = MenuItemCreateSource.VENUE_MINI_APP,
            )
            assertMenuItemCreateAudit(
                audit = audits[1],
                venueId = venueId,
                itemId = second.id,
                actorUserId = AUDIT_ACTOR_ID,
                source = MenuItemCreateSource.TELEGRAM_BOT,
            )
            audits.forEach { audit ->
                assertFalse(audit.payload.toString().contains(duplicateName))
            }
        }

    @Test
    fun `item create missing or foreign category writes neither item nor audit`() =
        runBlocking {
            val jdbcUrl = migratedJdbcUrl("venue-menu-item-create-invalid-scope")
            val venueId = seedVenue(jdbcUrl)
            val foreignVenueId = seedVenue(jdbcUrl)
            val repository = VenueMenuRepository(dataSource(jdbcUrl))
            val category = repository.createCategory(venueId, "Own category")

            assertNull(
                repository.createItem(
                    venueId = venueId,
                    categoryId = Long.MAX_VALUE,
                    name = "Missing category item",
                    priceMinor = 100,
                    currency = "RUB",
                    isAvailable = true,
                    actorUserId = AUDIT_ACTOR_ID,
                    source = MenuItemCreateSource.VENUE_MINI_APP,
                ),
            )
            assertNull(
                repository.createItem(
                    venueId = foreignVenueId,
                    categoryId = category.id,
                    name = "Foreign category item",
                    priceMinor = 200,
                    currency = "RUB",
                    isAvailable = true,
                    actorUserId = AUDIT_ACTOR_ID,
                    source = MenuItemCreateSource.TELEGRAM_BOT,
                ),
            )

            assertTrue(repository.getMenu(venueId).single().items.isEmpty())
            assertTrue(repository.getMenu(foreignVenueId).isEmpty())
            assertTrue(menuItemCreateAudits(jdbcUrl).isEmpty())
        }

    @Test
    fun `item create audit failure after insert rolls back item audit and category snapshot`() =
        runBlocking {
            val jdbcUrl = migratedJdbcUrl("venue-menu-item-create-audit-rollback")
            val venueId = seedVenue(jdbcUrl)
            val dataSource = dataSource(jdbcUrl)
            val fixtureRepository = VenueMenuRepository(dataSource)
            val category = fixtureRepository.createCategory(venueId, "Rollback category")
            val existingItem =
                requireNotNull(
                    fixtureRepository.createItem(
                        venueId = venueId,
                        categoryId = category.id,
                        name = "Existing unrelated item",
                        priceMinor = 100,
                        currency = "RUB",
                        isAvailable = true,
                        actorUserId = AUDIT_ACTOR_ID,
                        source = MenuItemCreateSource.VENUE_MINI_APP,
                    ),
                )
            val beforeMenu = fixtureRepository.getMenu(venueId)
            val beforeAudits = menuItemCreateAudits(jdbcUrl)
            assertEquals(existingItem.id, beforeAudits.single().entityId)
            val delegateAuditWriter = AuditLogRepository(dataSource, Json)
            var insertedRowObserved = false
            var transactionObserved = false
            var auditInsertObserved = false
            val failingAuditWriter =
                TransactionalAuditLogWriter { connection, actorUserId, action, entityType, entityId, payload ->
                    if (action == MENU_ITEM_CREATED_AUDIT_ACTION) {
                        transactionObserved = !connection.autoCommit
                        val insertedItemId = requireNotNull(entityId)
                        val inserted = itemRow(connection, insertedItemId)
                        insertedRowObserved =
                            inserted != null &&
                            inserted.venueId == venueId &&
                            inserted.categoryId == category.id &&
                            inserted.name == "Rollback private item"
                        check(transactionObserved) { "Item create audit must run inside the repository transaction" }
                        check(insertedRowObserved) { "Item insert must happen before its create audit" }
                        delegateAuditWriter.appendJson(
                            connection,
                            actorUserId,
                            action,
                            entityType,
                            insertedItemId,
                            payload,
                        )
                        auditInsertObserved = auditExists(connection, action, insertedItemId)
                        check(auditInsertObserved) { "Item create audit insert must execute before failure" }
                        throw SQLException("Synthetic menu item create audit failure", "XX999")
                    }
                    delegateAuditWriter.appendJson(connection, actorUserId, action, entityType, entityId, payload)
                }
            val repository = VenueMenuRepository(dataSource, failingAuditWriter)

            assertFailsWith<DatabaseUnavailableException> {
                repository.createItem(
                    venueId = venueId,
                    categoryId = category.id,
                    name = "Rollback private item",
                    priceMinor = 999_999,
                    currency = "USD",
                    isAvailable = false,
                    actorUserId = AUDIT_ACTOR_ID,
                    source = MenuItemCreateSource.TELEGRAM_BOT,
                    itemType = MenuSemanticType.DRINK,
                )
            }

            assertTrue(transactionObserved)
            assertTrue(insertedRowObserved)
            assertTrue(auditInsertObserved)
            assertEquals(beforeMenu, fixtureRepository.getMenu(venueId))
            assertEquals(beforeAudits, menuItemCreateAudits(jdbcUrl))
            assertEquals(existingItem, loadItem(fixtureRepository, venueId, existingItem.id))
        }

    @Test
    fun `MenuShiftCheck applies item and option changes and audits no-op completion`() =
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
            assertTrue(menuItemAvailabilityChangeAudits(jdbcUrl).isEmpty())
            assertTrue(menuOptionAvailabilityChangeAudits(jdbcUrl).isEmpty())

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
            assertTrue(menuItemAvailabilityChangeAudits(jdbcUrl).isEmpty())
            assertTrue(menuOptionAvailabilityChangeAudits(jdbcUrl).isEmpty())
        }

    @Test
    fun `MenuShiftCheck rejects duplicate missing foreign mismatched and oversized ids without writes or audit`() =
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
    fun `MenuShiftCheck stale expected availability rejects the whole batch`() =
        runBlocking {
            val jdbcUrl = migratedJdbcUrl("venue-menu-shift-check-stale")
            val venueId = seedVenue(jdbcUrl)
            val dataSource = dataSource(jdbcUrl)
            val repository = VenueMenuRepository(dataSource)
            val auditRepository = AuditLogRepository(dataSource, Json)
            val fixture = createMenuFixture(repository, venueId)
            repository.setOptionAvailability(
                venueId = venueId,
                optionId = fixture.secondOption.id,
                isAvailable = false,
                actorUserId = AUDIT_ACTOR_ID,
                source = MenuOptionAvailabilitySource.VENUE_MINI_APP,
            )
            val individualAuditsBeforeShiftCheck = menuOptionAvailabilityChangeAudits(jdbcUrl)

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
            assertTrue(menuItemAvailabilityChangeAudits(jdbcUrl).isEmpty())
            assertEquals(individualAuditsBeforeShiftCheck, menuOptionAvailabilityChangeAudits(jdbcUrl))
        }

    @Test
    fun `MenuShiftCheck audit failure rolls back availability writes`() =
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
            assertTrue(menuItemAvailabilityChangeAudits(jdbcUrl).isEmpty())
        }

    @Test
    fun `direct item availability writes exact audits and no-op missing foreign write none`() =
        runBlocking {
            val jdbcUrl = migratedJdbcUrl("venue-menu-item-availability-audit-success")
            val venueId = seedVenue(jdbcUrl)
            val foreignVenueId = seedVenue(jdbcUrl)
            val repository = VenueMenuRepository(dataSource(jdbcUrl))
            val own = createMenuFixture(repository, venueId)
            val foreign = createMenuFixture(repository, foreignVenueId)
            val original = own.firstItem
            val foreignBefore = itemRow(jdbcUrl, foreign.firstItem.id)

            val disabled =
                requireNotNull(
                    repository.setItemAvailability(
                        venueId = venueId,
                        itemId = original.id,
                        isAvailable = false,
                        actorUserId = AUDIT_ACTOR_ID,
                        source = MenuItemAvailabilitySource.VENUE_MINI_APP,
                    ),
                )
            assertFalse(disabled.isAvailable)
            val disabledAudit = menuItemAvailabilityChangeAudits(jdbcUrl).single()
            assertMenuItemAvailabilityChangeAudit(
                audit = disabledAudit,
                venueId = venueId,
                itemId = original.id,
                oldIsAvailable = true,
                newIsAvailable = false,
                actorUserId = AUDIT_ACTOR_ID,
                source = MenuItemAvailabilitySource.VENUE_MINI_APP,
            )
            val afterDisable = itemRow(jdbcUrl, original.id)

            repeat(2) {
                val noOp =
                    requireNotNull(
                        repository.setItemAvailability(
                            venueId = venueId,
                            itemId = original.id,
                            isAvailable = false,
                            actorUserId = AUDIT_ACTOR_ID,
                            source = MenuItemAvailabilitySource.TELEGRAM_BOT,
                        ),
                    )
                assertFalse(noOp.isAvailable)
            }
            assertEquals(afterDisable, itemRow(jdbcUrl, original.id))
            assertEquals(listOf(disabledAudit), menuItemAvailabilityChangeAudits(jdbcUrl))

            val enabled =
                requireNotNull(
                    repository.setItemAvailability(
                        venueId = venueId,
                        itemId = original.id,
                        isAvailable = true,
                        actorUserId = AUDIT_ACTOR_ID,
                        source = MenuItemAvailabilitySource.TELEGRAM_BOT,
                    ),
                )
            assertTrue(enabled.isAvailable)
            val audits = menuItemAvailabilityChangeAudits(jdbcUrl)
            assertEquals(2, audits.size)
            assertMenuItemAvailabilityChangeAudit(
                audit = audits[1],
                venueId = venueId,
                itemId = original.id,
                oldIsAvailable = false,
                newIsAvailable = true,
                actorUserId = AUDIT_ACTOR_ID,
                source = MenuItemAvailabilitySource.TELEGRAM_BOT,
            )

            assertNull(
                repository.setItemAvailability(
                    venueId = venueId,
                    itemId = Long.MAX_VALUE,
                    isAvailable = false,
                    actorUserId = AUDIT_ACTOR_ID,
                    source = MenuItemAvailabilitySource.VENUE_MINI_APP,
                ),
            )
            assertNull(
                repository.setItemAvailability(
                    venueId = venueId,
                    itemId = foreign.firstItem.id,
                    isAvailable = false,
                    actorUserId = AUDIT_ACTOR_ID,
                    source = MenuItemAvailabilitySource.VENUE_MINI_APP,
                ),
            )
            assertEquals(foreignBefore, itemRow(jdbcUrl, foreign.firstItem.id))
            assertEquals(audits, menuItemAvailabilityChangeAudits(jdbcUrl))
            audits.forEach { audit ->
                val serialized = audit.payload.toString()
                listOf(
                    "name",
                    "price",
                    "currency",
                    "description",
                    "type",
                    "option",
                    "promotion",
                    "cart",
                    "order",
                    "request",
                    "initData",
                    "telegramUserId",
                    "updateId",
                    "callbackData",
                    "media",
                    "secret",
                    "username",
                    "phone",
                ).forEach { forbidden ->
                    assertFalse(serialized.contains(forbidden, ignoreCase = true), forbidden)
                }
            }
        }

    @Test
    fun `compound item update audits only real availability deltas`() =
        runBlocking {
            val jdbcUrl = migratedJdbcUrl("venue-menu-item-availability-compound")
            val venueId = seedVenue(jdbcUrl)
            val repository = VenueMenuRepository(dataSource(jdbcUrl))
            val fixture = createMenuFixture(repository, venueId)

            val availabilityOnly =
                requireNotNull(
                    repository.updateItem(
                        venueId = venueId,
                        itemId = fixture.firstItem.id,
                        categoryId = null,
                        name = null,
                        priceMinor = null,
                        currency = null,
                        isAvailable = false,
                        actorUserId = AUDIT_ACTOR_ID,
                        source = MenuItemAvailabilitySource.VENUE_MINI_APP,
                    ),
                )
            assertFalse(availabilityOnly.isAvailable)

            val metadataAndAvailability =
                requireNotNull(
                    repository.updateItem(
                        venueId = venueId,
                        itemId = fixture.secondItem.id,
                        categoryId = null,
                        name = "Compound item metadata",
                        priceMinor = 77_700,
                        currency = "RUB",
                        isAvailable = false,
                        actorUserId = AUDIT_ACTOR_ID,
                        source = MenuItemAvailabilitySource.VENUE_MINI_APP,
                        itemType = MenuSemanticType.DRINK,
                        itemTypeSpecified = true,
                    ),
                )
            assertEquals("Compound item metadata", metadataAndAvailability.name)
            assertEquals(77_700L, metadataAndAvailability.priceMinor)
            assertEquals(MenuSemanticType.DRINK, metadataAndAvailability.itemType)
            assertFalse(metadataAndAvailability.isAvailable)

            val metadataOnly =
                requireNotNull(
                    repository.updateItem(
                        venueId = venueId,
                        itemId = fixture.secondItem.id,
                        categoryId = null,
                        name = "Metadata only after availability",
                        priceMinor = null,
                        currency = null,
                        isAvailable = false,
                        actorUserId = AUDIT_ACTOR_ID,
                        source = MenuItemAvailabilitySource.VENUE_MINI_APP,
                    ),
                )
            assertEquals("Metadata only after availability", metadataOnly.name)
            assertFalse(metadataOnly.isAvailable)

            repeat(2) {
                requireNotNull(
                    repository.updateItem(
                        venueId = venueId,
                        itemId = fixture.secondItem.id,
                        categoryId = null,
                        name = metadataOnly.name,
                        priceMinor = metadataOnly.priceMinor,
                        currency = metadataOnly.currency,
                        isAvailable = false,
                        actorUserId = AUDIT_ACTOR_ID,
                        source = MenuItemAvailabilitySource.VENUE_MINI_APP,
                    ),
                )
            }

            val audits = menuItemAvailabilityChangeAudits(jdbcUrl)
            assertEquals(listOf(fixture.firstItem.id, fixture.secondItem.id), audits.map { it.entityId })
            audits.forEach { audit ->
                assertEquals(
                    setOf("venueId", "itemId", "oldIsAvailable", "newIsAvailable", "source"),
                    audit.payload.keys,
                )
                assertEquals("true", audit.payload.getValue("oldIsAvailable").jsonPrimitive.content)
                assertEquals("false", audit.payload.getValue("newIsAvailable").jsonPrimitive.content)
            }
            assertTrue(menuOptionRenameAudits(jdbcUrl).isEmpty())
            assertTrue(menuOptionPriceChangeAudits(jdbcUrl).isEmpty())
            assertTrue(menuOptionAvailabilityChangeAudits(jdbcUrl).isEmpty())
        }

    @Test
    fun `item availability audit failure restores compound fields timestamp and audit row`() =
        runBlocking {
            val jdbcUrl = migratedJdbcUrl("venue-menu-item-availability-audit-rollback")
            val venueId = seedVenue(jdbcUrl)
            val dataSource = dataSource(jdbcUrl)
            val fixtureRepository = VenueMenuRepository(dataSource)
            val original = createMenuFixture(fixtureRepository, venueId).firstItem
            val before = itemRow(jdbcUrl, original.id)
            val delegateAuditWriter = AuditLogRepository(dataSource, Json)
            var updatedRowObserved = false
            val failingAuditWriter =
                TransactionalAuditLogWriter { connection, actorUserId, action, entityType, entityId, payload ->
                    delegateAuditWriter.appendJson(connection, actorUserId, action, entityType, entityId, payload)
                    if (action == MENU_ITEM_AVAILABILITY_CHANGED_AUDIT_ACTION) {
                        val current = itemRow(connection, original.id)
                        updatedRowObserved =
                            current?.name == "Rollback item metadata" &&
                            current.priceMinor == 999L &&
                            current.itemType == MenuSemanticType.DRINK.dbValue &&
                            !current.isAvailable
                        check(updatedRowObserved) { "Compound item update must happen before availability audit" }
                        throw SQLException("Synthetic menu item availability audit failure", "XX999")
                    }
                }
            val repository = VenueMenuRepository(dataSource, failingAuditWriter)

            assertFailsWith<DatabaseUnavailableException> {
                repository.updateItem(
                    venueId = venueId,
                    itemId = original.id,
                    categoryId = null,
                    name = "Rollback item metadata",
                    priceMinor = 999,
                    currency = "RUB",
                    isAvailable = false,
                    actorUserId = AUDIT_ACTOR_ID,
                    source = MenuItemAvailabilitySource.VENUE_MINI_APP,
                    itemType = MenuSemanticType.DRINK,
                    itemTypeSpecified = true,
                )
            }

            assertTrue(updatedRowObserved)
            assertEquals(before, itemRow(jdbcUrl, original.id))
            assertTrue(menuItemAvailabilityChangeAudits(jdbcUrl).isEmpty())
        }

    @Test
    fun `direct custom option create persists one exact privacy safe audit and missing foreign write none`() =
        runBlocking {
            val jdbcUrl = migratedJdbcUrl("venue-menu-option-create-custom")
            val venueId = seedVenue(jdbcUrl)
            val foreignVenueId = seedVenue(jdbcUrl)
            val repository = VenueMenuRepository(dataSource(jdbcUrl))
            val category = repository.createCategory(venueId, "Private category")
            val item =
                requireNotNull(
                    repository.createItem(
                        venueId = venueId,
                        categoryId = category.id,
                        name = "Private item",
                        priceMinor = 500,
                        currency = "RUB",
                        isAvailable = true,
                        actorUserId = AUDIT_ACTOR_ID,
                        source = MenuItemCreateSource.VENUE_MINI_APP,
                    ),
                )

            val created =
                requireNotNull(
                    repository.createOption(
                        venueId = venueId,
                        itemId = item.id,
                        name = "Private initData callback option @username secret",
                        priceDeltaMinor = 987_654,
                        isAvailable = false,
                        actorUserId = AUDIT_ACTOR_ID,
                        source = MenuOptionCreateSource.VENUE_MINI_APP,
                    ),
                )

            assertEquals(created, loadItem(repository, venueId, item.id).options.single())
            val audit = menuOptionCreateAudits(jdbcUrl).single()
            assertMenuOptionCreateAudit(
                audit = audit,
                venueId = venueId,
                itemId = item.id,
                optionId = created.id,
                actorUserId = AUDIT_ACTOR_ID,
                source = MenuOptionCreateSource.VENUE_MINI_APP,
            )
            val serialized = audit.payload.toString()
            listOf(
                "name",
                "price",
                "availability",
                "canonical",
                "promotion",
                "cart",
                "order",
                "request",
                "callback",
                "initData",
                "telegram",
                "media",
                "secret",
                "username",
                "phone",
            ).forEach { forbidden ->
                assertFalse(serialized.contains(forbidden, ignoreCase = true), forbidden)
            }

            assertNull(
                repository.createOption(
                    venueId = venueId,
                    itemId = Long.MAX_VALUE,
                    name = "Missing",
                    priceDeltaMinor = 0,
                    isAvailable = true,
                    actorUserId = AUDIT_ACTOR_ID,
                    source = MenuOptionCreateSource.VENUE_MINI_APP,
                ),
            )
            assertNull(
                repository.createOption(
                    venueId = foreignVenueId,
                    itemId = item.id,
                    name = "Foreign",
                    priceDeltaMinor = 0,
                    isAvailable = true,
                    actorUserId = AUDIT_ACTOR_ID,
                    source = MenuOptionCreateSource.VENUE_MINI_APP,
                ),
            )
            assertEquals(listOf(audit), menuOptionCreateAudits(jdbcUrl))
        }

    @Test
    fun `direct canonical option create audits once and collision creates neither row nor audit`() =
        runBlocking {
            val jdbcUrl = migratedJdbcUrl("venue-menu-option-create-canonical")
            val venueId = seedVenue(jdbcUrl)
            val repository = VenueMenuRepository(dataSource(jdbcUrl))
            val category = repository.createCategory(venueId, "Кальянное меню")
            requireNotNull(repository.updateCategoryType(venueId, category.id, MenuSemanticType.HOOKAH))
            val item =
                requireNotNull(
                    repository.createItem(
                        venueId = venueId,
                        categoryId = category.id,
                        name = "Кальян",
                        priceMinor = 100_000,
                        currency = "RUB",
                        isAvailable = true,
                        actorUserId = AUDIT_ACTOR_ID,
                        source = MenuItemCreateSource.VENUE_MINI_APP,
                    ),
                )

            val created =
                requireNotNull(
                    repository.createOption(
                        venueId = venueId,
                        itemId = item.id,
                        name = "Ягодный",
                        priceDeltaMinor = 0,
                        isAvailable = true,
                        actorUserId = AUDIT_ACTOR_ID,
                        source = MenuOptionCreateSource.TELEGRAM_BOT,
                    ),
                )
            val beforeCollision = optionRows(jdbcUrl, item.id)
            val audit = menuOptionCreateAudits(jdbcUrl).single()
            assertMenuOptionCreateAudit(
                audit = audit,
                venueId = venueId,
                itemId = item.id,
                optionId = created.id,
                actorUserId = AUDIT_ACTOR_ID,
                source = MenuOptionCreateSource.TELEGRAM_BOT,
            )

            assertFailsWith<InvalidInputException> {
                repository.createOption(
                    venueId = venueId,
                    itemId = item.id,
                    name = "  ЯГОДНЫЙ ",
                    priceDeltaMinor = 999,
                    isAvailable = false,
                    actorUserId = AUDIT_ACTOR_ID,
                    source = MenuOptionCreateSource.TELEGRAM_BOT,
                )
            }

            assertEquals(beforeCollision, optionRows(jdbcUrl, item.id))
            assertEquals(listOf(audit), menuOptionCreateAudits(jdbcUrl))
        }

    @Test
    fun `direct option create audit failure after insert rolls back row and audit`() =
        runBlocking {
            val jdbcUrl = migratedJdbcUrl("venue-menu-option-create-audit-rollback")
            val venueId = seedVenue(jdbcUrl)
            val dataSource = dataSource(jdbcUrl)
            val fixtureRepository = VenueMenuRepository(dataSource)
            val category = fixtureRepository.createCategory(venueId, "Options")
            val item =
                requireNotNull(
                    fixtureRepository.createItem(
                        venueId = venueId,
                        categoryId = category.id,
                        name = "Item",
                        priceMinor = 100,
                        currency = "RUB",
                        isAvailable = true,
                        actorUserId = AUDIT_ACTOR_ID,
                        source = MenuItemCreateSource.VENUE_MINI_APP,
                    ),
                )
            val delegateAuditWriter = AuditLogRepository(dataSource, Json)
            var insertedRowObserved = false
            var auditInsertCompleted = false
            val failingAuditWriter =
                TransactionalAuditLogWriter { connection, actorUserId, action, entityType, entityId, payload ->
                    if (action == MENU_OPTION_CREATED_AUDIT_ACTION) {
                        insertedRowObserved = optionExists(connection, venueId, requireNotNull(entityId))
                        check(insertedRowObserved) { "Option insert must happen before its create audit" }
                        delegateAuditWriter.appendJson(
                            connection,
                            actorUserId,
                            action,
                            entityType,
                            entityId,
                            payload,
                        )
                        auditInsertCompleted = true
                        throw SQLException("Synthetic menu option create audit failure", "XX999")
                    }
                    delegateAuditWriter.appendJson(connection, actorUserId, action, entityType, entityId, payload)
                }
            val repository = VenueMenuRepository(dataSource, failingAuditWriter)

            assertFailsWith<DatabaseUnavailableException> {
                repository.createOption(
                    venueId = venueId,
                    itemId = item.id,
                    name = "Rollback private option",
                    priceDeltaMinor = 999,
                    isAvailable = false,
                    actorUserId = AUDIT_ACTOR_ID,
                    source = MenuOptionCreateSource.VENUE_MINI_APP,
                )
            }

            assertTrue(insertedRowObserved)
            assertTrue(auditInsertCompleted)
            assertTrue(loadItem(fixtureRepository, venueId, item.id).options.isEmpty())
            assertTrue(menuOptionCreateAudits(jdbcUrl).isEmpty())
        }

    @Test
    fun `atomic base profiles create missing rows and audits preserving existing options then repeat no-op`() =
        runBlocking {
            val jdbcUrl = migratedJdbcUrl("venue-menu-option-create-base-profiles")
            val venueId = seedVenue(jdbcUrl)
            val repository = VenueMenuRepository(dataSource(jdbcUrl))
            val category = repository.createCategory(venueId, "Кальянное меню")
            requireNotNull(repository.updateCategoryType(venueId, category.id, MenuSemanticType.HOOKAH))
            val item =
                requireNotNull(
                    repository.createItem(
                        venueId = venueId,
                        categoryId = category.id,
                        name = "Кальян",
                        priceMinor = 100_000,
                        currency = "RUB",
                        isAvailable = true,
                        actorUserId = AUDIT_ACTOR_ID,
                        source = MenuItemCreateSource.VENUE_MINI_APP,
                    ),
                )
            val canonical =
                requireNotNull(
                    repository.createOption(
                        venueId,
                        item.id,
                        "Ягодный",
                        700,
                        false,
                        AUDIT_ACTOR_ID,
                        MenuOptionCreateSource.VENUE_MINI_APP,
                    ),
                )
            val custom =
                requireNotNull(
                    repository.createOption(
                        venueId,
                        item.id,
                        "Авторский",
                        600,
                        false,
                        AUDIT_ACTOR_ID,
                        MenuOptionCreateSource.VENUE_MINI_APP,
                    ),
                )
            val baselineAudits = menuOptionCreateAudits(jdbcUrl)

            val result =
                requireNotNull(
                    repository.applyMissingBaseProfiles(
                        venueId = venueId,
                        itemId = item.id,
                        actorUserId = AUDIT_ACTOR_ID,
                        source = MenuOptionCreateSource.TELEGRAM_BOT,
                    ),
                )

            assertEquals(7, result.addedCount)
            assertEquals(1, result.existingCount)
            assertEquals(
                listOf("Ягодный", "Авторский") + HookahFlavorProfileService.baseProfiles.drop(1),
                result.options.map { it.name },
            )
            assertEquals(canonical, result.options.single { it.id == canonical.id })
            assertEquals(custom, result.options.single { it.id == custom.id })
            assertEquals(1, result.options.count { it.name.equals("Ягодный", ignoreCase = true) })
            result.options
                .filter { it.id !in setOf(canonical.id, custom.id) }
                .forEach { created ->
                    assertEquals(0L, created.priceDeltaMinor)
                    assertTrue(created.isAvailable)
                }
            val createdAudits = menuOptionCreateAudits(jdbcUrl).drop(baselineAudits.size)
            assertEquals(7, createdAudits.size)
            assertEquals(
                result.options.filter { it.id !in setOf(canonical.id, custom.id) }.map { it.id },
                createdAudits.map { it.entityId },
            )
            createdAudits.forEach { audit ->
                assertMenuOptionCreateAudit(
                    audit = audit,
                    venueId = venueId,
                    itemId = item.id,
                    optionId = audit.entityId,
                    actorUserId = AUDIT_ACTOR_ID,
                    source = MenuOptionCreateSource.TELEGRAM_BOT,
                )
            }

            val beforeNoOpRows = optionRows(jdbcUrl, item.id)
            val beforeNoOpAudits = menuOptionCreateAudits(jdbcUrl)
            val noOp =
                requireNotNull(
                    repository.applyMissingBaseProfiles(
                        venueId = venueId,
                        itemId = item.id,
                        actorUserId = AUDIT_ACTOR_ID,
                        source = MenuOptionCreateSource.TELEGRAM_BOT,
                    ),
                )
            assertEquals(0, noOp.addedCount)
            assertEquals(HookahFlavorProfileService.baseProfiles.size, noOp.existingCount)
            assertEquals(beforeNoOpRows, optionRows(jdbcUrl, item.id))
            assertEquals(beforeNoOpAudits, menuOptionCreateAudits(jdbcUrl))
        }

    @Test
    fun `atomic base profiles partial audit failure restores full option and audit snapshots`() =
        runBlocking {
            val jdbcUrl = migratedJdbcUrl("venue-menu-option-create-base-profiles-audit-rollback")
            val venueId = seedVenue(jdbcUrl)
            val dataSource = dataSource(jdbcUrl)
            val fixtureRepository = VenueMenuRepository(dataSource)
            val category = fixtureRepository.createCategory(venueId, "Кальянное меню")
            requireNotNull(fixtureRepository.updateCategoryType(venueId, category.id, MenuSemanticType.HOOKAH))
            val item =
                requireNotNull(
                    fixtureRepository.createItem(
                        venueId = venueId,
                        categoryId = category.id,
                        name = "Кальян",
                        priceMinor = 100_000,
                        currency = "RUB",
                        isAvailable = true,
                        actorUserId = AUDIT_ACTOR_ID,
                        source = MenuItemCreateSource.VENUE_MINI_APP,
                    ),
                )
            requireNotNull(
                fixtureRepository.createOption(
                    venueId,
                    item.id,
                    "Авторский",
                    321,
                    false,
                    AUDIT_ACTOR_ID,
                    MenuOptionCreateSource.VENUE_MINI_APP,
                ),
            )
            val beforeRows = optionRows(jdbcUrl, item.id)
            val beforeAudits = menuOptionCreateAudits(jdbcUrl)
            val delegateAuditWriter = AuditLogRepository(dataSource, Json)
            var createAuditAttempts = 0
            val failingAuditWriter =
                TransactionalAuditLogWriter { connection, actorUserId, action, entityType, entityId, payload ->
                    delegateAuditWriter.appendJson(connection, actorUserId, action, entityType, entityId, payload)
                    if (action == MENU_OPTION_CREATED_AUDIT_ACTION && ++createAuditAttempts == 3) {
                        throw SQLException("Synthetic third base profile create audit failure", "XX999")
                    }
                }
            val repository = VenueMenuRepository(dataSource, failingAuditWriter)

            assertFailsWith<DatabaseUnavailableException> {
                repository.applyMissingBaseProfiles(
                    venueId = venueId,
                    itemId = item.id,
                    actorUserId = AUDIT_ACTOR_ID,
                    source = MenuOptionCreateSource.VENUE_MINI_APP,
                )
            }

            assertEquals(3, createAuditAttempts)
            assertEquals(beforeRows, optionRows(jdbcUrl, item.id))
            assertEquals(beforeAudits, menuOptionCreateAudits(jdbcUrl))
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
    fun `direct option availability writes exact audits and no-op missing foreign write none`() =
        runBlocking {
            val jdbcUrl = migratedJdbcUrl("venue-menu-option-availability-audit-success")
            val venueId = seedVenue(jdbcUrl)
            val foreignVenueId = seedVenue(jdbcUrl)
            val repository = VenueMenuRepository(dataSource(jdbcUrl))
            val own = createMenuFixture(repository, venueId)
            val foreign = createMenuFixture(repository, foreignVenueId)
            val original = own.firstOption

            val disabled =
                requireNotNull(
                    repository.setOptionAvailability(
                        venueId = venueId,
                        optionId = original.id,
                        isAvailable = false,
                        actorUserId = AUDIT_ACTOR_ID,
                        source = MenuOptionAvailabilitySource.VENUE_MINI_APP,
                    ),
                )
            assertFalse(disabled.isAvailable)
            val disabledAudit = menuOptionAvailabilityChangeAudits(jdbcUrl).single()
            assertMenuOptionAvailabilityChangeAudit(
                audit = disabledAudit,
                venueId = venueId,
                itemId = original.itemId,
                optionId = original.id,
                oldIsAvailable = true,
                newIsAvailable = false,
                actorUserId = AUDIT_ACTOR_ID,
                source = MenuOptionAvailabilitySource.VENUE_MINI_APP.name,
            )
            val afterDisable = optionRows(jdbcUrl, original.itemId)

            repeat(2) {
                val noOp =
                    requireNotNull(
                        repository.setOptionAvailability(
                            venueId = venueId,
                            optionId = original.id,
                            isAvailable = false,
                            actorUserId = AUDIT_ACTOR_ID,
                            source = MenuOptionAvailabilitySource.TELEGRAM_BOT,
                        ),
                    )
                assertFalse(noOp.isAvailable)
            }
            assertEquals(afterDisable, optionRows(jdbcUrl, original.itemId))
            assertEquals(listOf(disabledAudit), menuOptionAvailabilityChangeAudits(jdbcUrl))

            val enabled =
                requireNotNull(
                    repository.setOptionAvailability(
                        venueId = venueId,
                        optionId = original.id,
                        isAvailable = true,
                        actorUserId = AUDIT_ACTOR_ID,
                        source = MenuOptionAvailabilitySource.TELEGRAM_BOT,
                    ),
                )
            assertTrue(enabled.isAvailable)
            val audits = menuOptionAvailabilityChangeAudits(jdbcUrl)
            assertEquals(2, audits.size)
            assertMenuOptionAvailabilityChangeAudit(
                audit = audits[1],
                venueId = venueId,
                itemId = original.itemId,
                optionId = original.id,
                oldIsAvailable = false,
                newIsAvailable = true,
                actorUserId = AUDIT_ACTOR_ID,
                source = MenuOptionAvailabilitySource.TELEGRAM_BOT.name,
            )

            assertNull(
                repository.setOptionAvailability(
                    venueId = venueId,
                    optionId = Long.MAX_VALUE,
                    isAvailable = false,
                    actorUserId = AUDIT_ACTOR_ID,
                    source = MenuOptionAvailabilitySource.VENUE_MINI_APP,
                ),
            )
            assertNull(
                repository.setOptionAvailability(
                    venueId = venueId,
                    optionId = foreign.firstOption.id,
                    isAvailable = false,
                    actorUserId = AUDIT_ACTOR_ID,
                    source = MenuOptionAvailabilitySource.VENUE_MINI_APP,
                ),
            )
            assertEquals(
                foreign.firstOption,
                loadItem(repository, foreignVenueId, foreign.firstItem.id).options.first(),
            )
            assertEquals(audits, menuOptionAvailabilityChangeAudits(jdbcUrl))
            audits.forEach { audit ->
                val serialized = audit.payload.toString()
                listOf(
                    "name",
                    "price",
                    "canonical",
                    "promotion",
                    "cart",
                    "order",
                    "request",
                    "initData",
                    "media",
                    "secret",
                    "username",
                    "phone",
                ).forEach { forbidden ->
                    assertFalse(serialized.contains(forbidden, ignoreCase = true), forbidden)
                }
            }
        }

    @Test
    fun `compound option updates audit every changed family exactly once`() =
        runBlocking {
            val jdbcUrl = migratedJdbcUrl("venue-menu-option-availability-compound-families")
            val venueId = seedVenue(jdbcUrl)
            val repository = VenueMenuRepository(dataSource(jdbcUrl))
            val fixture = createMenuFixture(repository, venueId)
            val availabilityOnly = fixture.firstOption
            val nameAndAvailability = fixture.secondOption
            val priceAndAvailability =
                requireNotNull(
                    repository.createOption(
                        venueId,
                        fixture.firstItem.id,
                        "Price family",
                        30,
                        true,
                        AUDIT_ACTOR_ID,
                        MenuOptionCreateSource.VENUE_MINI_APP,
                    ),
                )
            val allFields =
                requireNotNull(
                    repository.createOption(
                        venueId,
                        fixture.firstItem.id,
                        "All families",
                        40,
                        true,
                        AUDIT_ACTOR_ID,
                        MenuOptionCreateSource.VENUE_MINI_APP,
                    ),
                )

            requireNotNull(
                repository.updateOption(
                    venueId,
                    availabilityOnly.id,
                    null,
                    null,
                    false,
                    AUDIT_ACTOR_ID,
                    MenuOptionRenameSource.VENUE_MINI_APP,
                ),
            )
            requireNotNull(
                repository.updateOption(
                    venueId,
                    nameAndAvailability.id,
                    "Name and availability",
                    null,
                    false,
                    AUDIT_ACTOR_ID,
                    MenuOptionRenameSource.VENUE_MINI_APP,
                ),
            )
            requireNotNull(
                repository.updateOption(
                    venueId,
                    priceAndAvailability.id,
                    null,
                    35,
                    false,
                    AUDIT_ACTOR_ID,
                    MenuOptionRenameSource.VENUE_MINI_APP,
                ),
            )
            requireNotNull(
                repository.updateOption(
                    venueId,
                    allFields.id,
                    "All families changed",
                    45,
                    false,
                    AUDIT_ACTOR_ID,
                    MenuOptionRenameSource.VENUE_MINI_APP,
                ),
            )

            assertEquals(
                listOf(availabilityOnly.id, nameAndAvailability.id, priceAndAvailability.id, allFields.id),
                menuOptionAvailabilityChangeAudits(jdbcUrl).map { it.entityId },
            )
            assertEquals(
                listOf(nameAndAvailability.id, allFields.id),
                menuOptionRenameAudits(jdbcUrl).map { it.entityId },
            )
            assertEquals(
                listOf(priceAndAvailability.id, allFields.id),
                menuOptionPriceChangeAudits(jdbcUrl).map { it.entityId },
            )
        }

    @Test
    fun `option price update audits once and same price retries or unrelated fields write none`() =
        runBlocking {
            val jdbcUrl = migratedJdbcUrl("venue-menu-option-price-audit-success")
            val venueId = seedVenue(jdbcUrl)
            val repository = VenueMenuRepository(dataSource(jdbcUrl))
            val fixture = createMenuFixture(repository, venueId)
            val original = fixture.firstOption

            val updated =
                requireNotNull(
                    repository.updateOption(
                        venueId = venueId,
                        optionId = original.id,
                        name = null,
                        priceDeltaMinor = 125,
                        isAvailable = null,
                        actorUserId = AUDIT_ACTOR_ID,
                        source = MenuOptionRenameSource.VENUE_MINI_APP,
                    ),
                )

            assertEquals(125L, updated.priceDeltaMinor)
            assertEquals(original.name, updated.name)
            assertEquals(original.isAvailable, updated.isAvailable)
            val priceAudit = menuOptionPriceChangeAudits(jdbcUrl).single()
            assertMenuOptionPriceChangeAudit(
                audit = priceAudit,
                venueId = venueId,
                itemId = original.itemId,
                optionId = original.id,
                oldPriceDeltaMinor = original.priceDeltaMinor,
                newPriceDeltaMinor = 125,
                actorUserId = AUDIT_ACTOR_ID,
            )
            val serialized = priceAudit.payload.toString()
            listOf(
                "name",
                "availability",
                "canonical",
                "promotion",
                "cart",
                "order",
                "request",
                "initData",
                "telegram",
                "media",
                "secret",
                "username",
                "phone",
            ).forEach { forbidden ->
                assertFalse(serialized.contains(forbidden, ignoreCase = true), forbidden)
            }

            repeat(2) {
                requireNotNull(
                    repository.updateOption(
                        venueId = venueId,
                        optionId = original.id,
                        name = null,
                        priceDeltaMinor = 125,
                        isAvailable = null,
                        actorUserId = AUDIT_ACTOR_ID,
                        source = MenuOptionRenameSource.VENUE_MINI_APP,
                    ),
                )
            }
            requireNotNull(
                repository.updateOption(
                    venueId = venueId,
                    optionId = original.id,
                    name = "Name only update",
                    priceDeltaMinor = null,
                    isAvailable = null,
                    actorUserId = AUDIT_ACTOR_ID,
                    source = MenuOptionRenameSource.VENUE_MINI_APP,
                ),
            )
            requireNotNull(
                repository.updateOption(
                    venueId = venueId,
                    optionId = original.id,
                    name = null,
                    priceDeltaMinor = null,
                    isAvailable = false,
                    actorUserId = AUDIT_ACTOR_ID,
                    source = MenuOptionRenameSource.VENUE_MINI_APP,
                ),
            )
            assertFailsWith<InvalidInputException> {
                repository.updateOption(
                    venueId = venueId,
                    optionId = original.id,
                    name = null,
                    priceDeltaMinor = 130,
                    isAvailable = null,
                    actorUserId = AUDIT_ACTOR_ID,
                    source = MenuOptionRenameSource.TELEGRAM_BOT,
                )
            }

            assertEquals(listOf(priceAudit), menuOptionPriceChangeAudits(jdbcUrl))
            assertEquals(1, menuOptionRenameAudits(jdbcUrl).size)
            val availabilityAudit = menuOptionAvailabilityChangeAudits(jdbcUrl).single()
            assertMenuOptionAvailabilityChangeAudit(
                audit = availabilityAudit,
                venueId = venueId,
                itemId = original.itemId,
                optionId = original.id,
                oldIsAvailable = true,
                newIsAvailable = false,
                actorUserId = AUDIT_ACTOR_ID,
                source = MenuOptionAvailabilitySource.VENUE_MINI_APP.name,
            )
            val saved = loadItem(repository, venueId, original.itemId).options.single { it.id == original.id }
            assertEquals("Name only update", saved.name)
            assertEquals(125L, saved.priceDeltaMinor)
            assertFalse(saved.isAvailable)
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
            val priceAudit = menuOptionPriceChangeAudits(jdbcUrl).single()
            assertMenuOptionPriceChangeAudit(
                audit = priceAudit,
                venueId = venueId,
                itemId = own.firstItem.id,
                optionId = own.firstOption.id,
                oldPriceDeltaMinor = own.firstOption.priceDeltaMinor,
                newPriceDeltaMinor = renamed.priceDeltaMinor,
                actorUserId = AUDIT_ACTOR_ID,
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

            val nameAndPrice =
                requireNotNull(
                    repository.updateOption(
                        venueId = venueId,
                        optionId = own.firstOption.id,
                        name = "Second compound name",
                        priceDeltaMinor = 130,
                        isAvailable = null,
                        actorUserId = AUDIT_ACTOR_ID,
                        source = MenuOptionRenameSource.VENUE_MINI_APP,
                    ),
                )
            assertEquals("Second compound name", nameAndPrice.name)
            assertEquals(130L, nameAndPrice.priceDeltaMinor)
            assertFalse(nameAndPrice.isAvailable)
            requireNotNull(
                repository.updateOption(
                    venueId = venueId,
                    optionId = own.firstOption.id,
                    name = nameAndPrice.name,
                    priceDeltaMinor = nameAndPrice.priceDeltaMinor,
                    isAvailable = null,
                    actorUserId = AUDIT_ACTOR_ID,
                    source = MenuOptionRenameSource.VENUE_MINI_APP,
                ),
            )
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
            val renameAudits = menuOptionRenameAudits(jdbcUrl)
            val priceAudits = menuOptionPriceChangeAudits(jdbcUrl)
            val availabilityAudits = menuOptionAvailabilityChangeAudits(jdbcUrl)
            assertEquals(2, renameAudits.size)
            assertEquals(2, priceAudits.size)
            assertEquals(2, availabilityAudits.size)
            assertEquals(renamed.name, renameAudits[1].payload.getValue("oldName").jsonPrimitive.content)
            assertEquals(nameAndPrice.name, renameAudits[1].payload.getValue("newName").jsonPrimitive.content)
            assertEquals(renamed.priceDeltaMinor, priceAudits[1].payload.longValue("oldPriceDeltaMinor"))
            assertEquals(nameAndPrice.priceDeltaMinor, priceAudits[1].payload.longValue("newPriceDeltaMinor"))
            assertEquals(
                false,
                availabilityAudits[1].payload.getValue("oldIsAvailable").jsonPrimitive.content.toBoolean(),
            )
            assertEquals(
                true,
                availabilityAudits[1].payload.getValue("newIsAvailable").jsonPrimitive.content.toBoolean(),
            )
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
            assertTrue(menuOptionPriceChangeAudits(jdbcUrl).isEmpty())
            assertTrue(menuOptionAvailabilityChangeAudits(jdbcUrl).isEmpty())
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
            assertTrue(menuOptionPriceChangeAudits(jdbcUrl).isEmpty())
            assertTrue(menuOptionAvailabilityChangeAudits(jdbcUrl).isEmpty())
        }

    @Test
    fun `price audit failure after compound update restores row timestamp and both audit families`() =
        runBlocking {
            val jdbcUrl = migratedJdbcUrl("venue-menu-option-price-audit-rollback")
            val venueId = seedVenue(jdbcUrl)
            val dataSource = dataSource(jdbcUrl)
            val fixtureRepository = VenueMenuRepository(dataSource)
            val fixture = createMenuFixture(fixtureRepository, venueId)
            val original = fixture.firstOption
            val before = optionRows(jdbcUrl, original.itemId)
            val delegateAuditWriter = AuditLogRepository(dataSource, Json)
            var updatedRowObserved = false
            var renameAuditObserved = false
            val failingAuditWriter =
                TransactionalAuditLogWriter { connection, actorUserId, action, entityType, entityId, payload ->
                    if (action == MENU_OPTION_RENAMED_AUDIT_ACTION) {
                        renameAuditObserved = true
                    }
                    delegateAuditWriter.appendJson(
                        connection,
                        actorUserId,
                        action,
                        entityType,
                        entityId,
                        payload,
                    )
                    if (action == MENU_OPTION_PRICE_CHANGED_AUDIT_ACTION) {
                        val current = loadOptionSnapshot(connection, venueId, original.id)
                        updatedRowObserved =
                            current?.name == "Rollback price audit name" &&
                            current.priceDeltaMinor == 999L &&
                            !current.isAvailable
                        check(updatedRowObserved) { "Compound option update must happen before price audit" }
                        check(renameAuditObserved) { "Rename audit must be appended before price audit" }
                        throw SQLException("Synthetic menu option price audit failure", "XX999")
                    }
                }
            val repository = VenueMenuRepository(dataSource, failingAuditWriter)

            assertFailsWith<DatabaseUnavailableException> {
                repository.updateOption(
                    venueId = venueId,
                    optionId = original.id,
                    name = "Rollback price audit name",
                    priceDeltaMinor = 999,
                    isAvailable = false,
                    actorUserId = AUDIT_ACTOR_ID,
                    source = MenuOptionRenameSource.VENUE_MINI_APP,
                )
            }

            assertTrue(updatedRowObserved)
            assertTrue(renameAuditObserved)
            assertEquals(before, optionRows(jdbcUrl, original.itemId))
            assertTrue(menuOptionRenameAudits(jdbcUrl).isEmpty())
            assertTrue(menuOptionPriceChangeAudits(jdbcUrl).isEmpty())
            assertTrue(menuOptionAvailabilityChangeAudits(jdbcUrl).isEmpty())
        }

    @Test
    fun `availability audit failure after compound update restores row timestamp and all audit families`() =
        runBlocking {
            val jdbcUrl = migratedJdbcUrl("venue-menu-option-availability-audit-rollback")
            val venueId = seedVenue(jdbcUrl)
            val dataSource = dataSource(jdbcUrl)
            val fixtureRepository = VenueMenuRepository(dataSource)
            val original = createMenuFixture(fixtureRepository, venueId).firstOption
            val before = optionRows(jdbcUrl, original.itemId)
            val delegateAuditWriter = AuditLogRepository(dataSource, Json)
            val observedActions = mutableListOf<String>()
            var updatedRowObserved = false
            val failingAuditWriter =
                TransactionalAuditLogWriter { connection, actorUserId, action, entityType, entityId, payload ->
                    observedActions += action
                    delegateAuditWriter.appendJson(connection, actorUserId, action, entityType, entityId, payload)
                    if (action == MENU_OPTION_AVAILABILITY_CHANGED_AUDIT_ACTION) {
                        val current = loadOptionSnapshot(connection, venueId, original.id)
                        updatedRowObserved =
                            current?.name == "Rollback availability audit name" &&
                            current.priceDeltaMinor == 999L &&
                            !current.isAvailable
                        check(updatedRowObserved) { "Compound option update must happen before availability audit" }
                        throw SQLException("Synthetic menu option availability audit failure", "XX999")
                    }
                }
            val repository = VenueMenuRepository(dataSource, failingAuditWriter)

            assertFailsWith<DatabaseUnavailableException> {
                repository.updateOption(
                    venueId = venueId,
                    optionId = original.id,
                    name = "Rollback availability audit name",
                    priceDeltaMinor = 999,
                    isAvailable = false,
                    actorUserId = AUDIT_ACTOR_ID,
                    source = MenuOptionRenameSource.VENUE_MINI_APP,
                )
            }

            assertTrue(updatedRowObserved)
            assertEquals(
                listOf(
                    MENU_OPTION_RENAMED_AUDIT_ACTION,
                    MENU_OPTION_PRICE_CHANGED_AUDIT_ACTION,
                    MENU_OPTION_AVAILABILITY_CHANGED_AUDIT_ACTION,
                ),
                observedActions,
            )
            assertEquals(before, optionRows(jdbcUrl, original.itemId))
            assertTrue(menuOptionRenameAudits(jdbcUrl).isEmpty())
            assertTrue(menuOptionPriceChangeAudits(jdbcUrl).isEmpty())
            assertTrue(menuOptionAvailabilityChangeAudits(jdbcUrl).isEmpty())
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
            val baselineCreateAudits = menuOptionCreateAudits(jdbcUrl)

            val result =
                requireNotNull(
                    repository.normalizeHookahFlavorProfiles(
                        venueId = venueId,
                        itemId = fixture.item.id,
                        actorUserId = AUDIT_ACTOR_ID,
                        deleteSource = MenuOptionDeleteSource.TELEGRAM_BOT,
                        createSource = MenuOptionCreateSource.TELEGRAM_BOT,
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
            val createdOptions = normalized.options.filter { it.id !in fixture.options.map(VenueMenuOption::id) }
            val createAudits = menuOptionCreateAudits(jdbcUrl).drop(baselineCreateAudits.size)
            assertEquals(6, createAudits.size)
            assertEquals(createdOptions.map { it.id }, createAudits.map { it.entityId })
            createAudits.forEach { audit ->
                assertMenuOptionCreateAudit(
                    audit = audit,
                    venueId = venueId,
                    itemId = fixture.item.id,
                    optionId = audit.entityId,
                    actorUserId = AUDIT_ACTOR_ID,
                    source = MenuOptionCreateSource.TELEGRAM_BOT,
                )
            }

            val beforeNoOp = optionRows(jdbcUrl, fixture.item.id)
            val beforeNoOpCreateAudits = menuOptionCreateAudits(jdbcUrl)
            val noOp =
                requireNotNull(
                    repository.normalizeHookahFlavorProfiles(
                        venueId = venueId,
                        itemId = fixture.item.id,
                        actorUserId = AUDIT_ACTOR_ID,
                        deleteSource = MenuOptionDeleteSource.TELEGRAM_BOT,
                        createSource = MenuOptionCreateSource.TELEGRAM_BOT,
                    ),
                )
            assertEquals(HookahFlavorProfileNormalizationResult(removedCount = 0, addedCount = 0), noOp)
            assertEquals(beforeNoOp, optionRows(jdbcUrl, fixture.item.id))
            assertEquals(audits, menuOptionDeleteAudits(jdbcUrl))
            assertEquals(beforeNoOpCreateAudits, menuOptionCreateAudits(jdbcUrl))
        }

    @Test
    fun `normalization rechecks current hookah scope before changing options`() =
        runBlocking {
            val jdbcUrl = migratedJdbcUrl("venue-menu-option-normalization-scope")
            val venueId = seedVenue(jdbcUrl)
            val repository = VenueMenuRepository(dataSource(jdbcUrl))
            val fixture = createMenuFixture(repository, venueId)
            val before = optionRows(jdbcUrl, fixture.firstItem.id)
            val beforeCreateAudits = menuOptionCreateAudits(jdbcUrl)

            assertFailsWith<InvalidInputException> {
                repository.normalizeHookahFlavorProfiles(
                    venueId = venueId,
                    itemId = fixture.firstItem.id,
                    actorUserId = AUDIT_ACTOR_ID,
                    deleteSource = MenuOptionDeleteSource.TELEGRAM_BOT,
                    createSource = MenuOptionCreateSource.TELEGRAM_BOT,
                )
            }

            assertEquals(before, optionRows(jdbcUrl, fixture.firstItem.id))
            assertTrue(menuOptionDeleteAudits(jdbcUrl).isEmpty())
            assertEquals(beforeCreateAudits, menuOptionCreateAudits(jdbcUrl))
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
                        actorUserId = AUDIT_ACTOR_ID,
                        source = MenuItemCreateSource.VENUE_MINI_APP,
                    ),
                )
            val firstCanonical =
                requireNotNull(
                    repository.createOption(
                        venueId,
                        item.id,
                        "Ягодный",
                        10,
                        true,
                        AUDIT_ACTOR_ID,
                        MenuOptionCreateSource.VENUE_MINI_APP,
                    ),
                )
            requireNotNull(
                repository.createOption(
                    venueId,
                    item.id,
                    "Ягодный",
                    20,
                    true,
                    AUDIT_ACTOR_ID,
                    MenuOptionCreateSource.VENUE_MINI_APP,
                ),
            )
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
                repository.createOption(
                    venueId,
                    item.id,
                    "Ягодный",
                    0,
                    true,
                    AUDIT_ACTOR_ID,
                    MenuOptionCreateSource.VENUE_MINI_APP,
                )
            }
            val custom =
                requireNotNull(
                    repository.createOption(
                        venueId,
                        item.id,
                        "Авторский",
                        0,
                        true,
                        AUDIT_ACTOR_ID,
                        MenuOptionCreateSource.VENUE_MINI_APP,
                    ),
                )
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
            val beforeCreateAudits = menuOptionCreateAudits(jdbcUrl)
            val failingDataSource = FailAfterOptionInsertsDataSource(delegate, failOnInsert = 3)
            val repository = VenueMenuRepository(failingDataSource)

            assertFailsWith<DatabaseUnavailableException> {
                repository.normalizeHookahFlavorProfiles(
                    venueId = venueId,
                    itemId = fixture.item.id,
                    actorUserId = AUDIT_ACTOR_ID,
                    deleteSource = MenuOptionDeleteSource.TELEGRAM_BOT,
                    createSource = MenuOptionCreateSource.TELEGRAM_BOT,
                )
            }

            assertEquals(3, failingDataSource.completedOptionInserts.get())
            assertEquals(before, optionRows(jdbcUrl, fixture.item.id))
            assertTrue(menuOptionDeleteAudits(jdbcUrl).isEmpty())
            assertEquals(beforeCreateAudits, menuOptionCreateAudits(jdbcUrl))
        }

    @Test
    fun `normalization create audit failure after delete audits rolls back full option and audit snapshots`() =
        runBlocking {
            val jdbcUrl = migratedJdbcUrl("venue-menu-option-normalization-audit-rollback")
            val venueId = seedVenue(jdbcUrl)
            val dataSource = dataSource(jdbcUrl)
            val fixtureRepository = VenueMenuRepository(dataSource)
            val fixture = createNormalizationFixture(fixtureRepository, venueId)
            val before = optionRows(jdbcUrl, fixture.item.id)
            val beforeDeleteAudits = menuOptionDeleteAudits(jdbcUrl)
            val beforeCreateAudits = menuOptionCreateAudits(jdbcUrl)
            val delegateAuditWriter = AuditLogRepository(dataSource, Json)
            val observedActions = mutableListOf<String>()
            var createAuditAttempts = 0
            var normalizedRowsObserved = false
            val failingAuditWriter =
                TransactionalAuditLogWriter { connection, actorUserId, action, entityType, entityId, payload ->
                    observedActions += action
                    delegateAuditWriter.appendJson(
                        connection,
                        actorUserId,
                        action,
                        entityType,
                        entityId,
                        payload,
                    )
                    if (action == MENU_OPTION_CREATED_AUDIT_ACTION) {
                        createAuditAttempts += 1
                        normalizedRowsObserved =
                            optionNames(connection, fixture.item.id).let { names ->
                                names.size == 9 &&
                                    names.none(HookahFlavorProfileService::isObsoleteProfileValue) &&
                                    HookahFlavorProfileService.missingBaseProfiles(names).isEmpty()
                            }
                        check(normalizedRowsObserved) {
                            "All normalization deletes and creates must precede create audits"
                        }
                        if (createAuditAttempts == 2) {
                            throw SQLException("Synthetic second create audit failure", "XX999")
                        }
                    }
                }
            val repository = VenueMenuRepository(dataSource, failingAuditWriter)

            assertFailsWith<DatabaseUnavailableException> {
                repository.normalizeHookahFlavorProfiles(
                    venueId = venueId,
                    itemId = fixture.item.id,
                    actorUserId = AUDIT_ACTOR_ID,
                    deleteSource = MenuOptionDeleteSource.TELEGRAM_BOT,
                    createSource = MenuOptionCreateSource.TELEGRAM_BOT,
                )
            }

            assertEquals(
                List(5) { MENU_OPTION_DELETED_AUDIT_ACTION } +
                    List(2) { MENU_OPTION_CREATED_AUDIT_ACTION },
                observedActions,
            )
            assertEquals(2, createAuditAttempts)
            assertTrue(normalizedRowsObserved)
            assertEquals(before, optionRows(jdbcUrl, fixture.item.id))
            assertEquals(beforeDeleteAudits, menuOptionDeleteAudits(jdbcUrl))
            assertEquals(beforeCreateAudits, menuOptionCreateAudits(jdbcUrl))
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
                    actorUserId = AUDIT_ACTOR_ID,
                    source = MenuItemCreateSource.VENUE_MINI_APP,
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
                    actorUserId = AUDIT_ACTOR_ID,
                    source = MenuItemCreateSource.VENUE_MINI_APP,
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
                    actorUserId = AUDIT_ACTOR_ID,
                    source = MenuOptionCreateSource.VENUE_MINI_APP,
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
                    actorUserId = AUDIT_ACTOR_ID,
                    source = MenuOptionCreateSource.VENUE_MINI_APP,
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
                    actorUserId = AUDIT_ACTOR_ID,
                    source = MenuItemCreateSource.VENUE_MINI_APP,
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
                        actorUserId = AUDIT_ACTOR_ID,
                        source = MenuOptionCreateSource.VENUE_MINI_APP,
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

    private fun menuItemCreateAudits(jdbcUrl: String): List<MenuDeleteAuditRow> =
        menuDeleteAudits(jdbcUrl, MENU_ITEM_CREATED_AUDIT_ACTION)

    private fun menuOptionCreateAudits(jdbcUrl: String): List<MenuDeleteAuditRow> =
        menuDeleteAudits(jdbcUrl, MENU_OPTION_CREATED_AUDIT_ACTION)

    private fun menuOptionRenameAudits(jdbcUrl: String): List<MenuDeleteAuditRow> =
        menuDeleteAudits(jdbcUrl, MENU_OPTION_RENAMED_AUDIT_ACTION)

    private fun menuOptionPriceChangeAudits(jdbcUrl: String): List<MenuDeleteAuditRow> =
        menuDeleteAudits(jdbcUrl, MENU_OPTION_PRICE_CHANGED_AUDIT_ACTION)

    private fun menuOptionAvailabilityChangeAudits(jdbcUrl: String): List<MenuDeleteAuditRow> =
        menuDeleteAudits(jdbcUrl, MENU_OPTION_AVAILABILITY_CHANGED_AUDIT_ACTION)

    private fun menuItemAvailabilityChangeAudits(jdbcUrl: String): List<MenuDeleteAuditRow> =
        menuDeleteAudits(jdbcUrl, MENU_ITEM_AVAILABILITY_CHANGED_AUDIT_ACTION)

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

    private fun assertMenuItemCreateAudit(
        audit: MenuDeleteAuditRow,
        venueId: Long,
        itemId: Long,
        actorUserId: Long,
        source: MenuItemCreateSource,
    ) {
        assertEquals(actorUserId, audit.actorUserId)
        assertEquals(MENU_ITEM_CREATED_AUDIT_ACTION, audit.action)
        assertEquals("menu_item", audit.entityType)
        assertEquals(itemId, audit.entityId)
        assertEquals(setOf("venueId", "itemId", "source"), audit.payload.keys)
        assertEquals(venueId, audit.payload.longValue("venueId"))
        assertEquals(itemId, audit.payload.longValue("itemId"))
        assertEquals(source.name, audit.payload.getValue("source").jsonPrimitive.content)
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

    private fun assertMenuOptionCreateAudit(
        audit: MenuDeleteAuditRow,
        venueId: Long,
        itemId: Long,
        optionId: Long,
        actorUserId: Long,
        source: MenuOptionCreateSource,
    ) {
        assertEquals(actorUserId, audit.actorUserId)
        assertEquals(MENU_OPTION_CREATED_AUDIT_ACTION, audit.action)
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

    private fun assertMenuOptionPriceChangeAudit(
        audit: MenuDeleteAuditRow,
        venueId: Long,
        itemId: Long,
        optionId: Long,
        oldPriceDeltaMinor: Long,
        newPriceDeltaMinor: Long,
        actorUserId: Long,
    ) {
        assertEquals(actorUserId, audit.actorUserId)
        assertEquals(MENU_OPTION_PRICE_CHANGED_AUDIT_ACTION, audit.action)
        assertEquals("menu_item_option", audit.entityType)
        assertEquals(optionId, audit.entityId)
        assertEquals(
            setOf(
                "venueId",
                "itemId",
                "optionId",
                "oldPriceDeltaMinor",
                "newPriceDeltaMinor",
                "source",
            ),
            audit.payload.keys,
        )
        assertEquals(venueId, audit.payload.longValue("venueId"))
        assertEquals(itemId, audit.payload.longValue("itemId"))
        assertEquals(optionId, audit.payload.longValue("optionId"))
        assertEquals(oldPriceDeltaMinor, audit.payload.longValue("oldPriceDeltaMinor"))
        assertEquals(newPriceDeltaMinor, audit.payload.longValue("newPriceDeltaMinor"))
        assertEquals("VENUE_MINI_APP", audit.payload.getValue("source").jsonPrimitive.content)
    }

    private fun assertMenuOptionAvailabilityChangeAudit(
        audit: MenuDeleteAuditRow,
        venueId: Long,
        itemId: Long,
        optionId: Long,
        oldIsAvailable: Boolean,
        newIsAvailable: Boolean,
        actorUserId: Long,
        source: String,
    ) {
        assertEquals(actorUserId, audit.actorUserId)
        assertEquals(MENU_OPTION_AVAILABILITY_CHANGED_AUDIT_ACTION, audit.action)
        assertEquals("menu_item_option", audit.entityType)
        assertEquals(optionId, audit.entityId)
        assertEquals(
            setOf("venueId", "itemId", "optionId", "oldIsAvailable", "newIsAvailable", "source"),
            audit.payload.keys,
        )
        assertEquals(venueId, audit.payload.longValue("venueId"))
        assertEquals(itemId, audit.payload.longValue("itemId"))
        assertEquals(optionId, audit.payload.longValue("optionId"))
        assertEquals(oldIsAvailable, audit.payload.getValue("oldIsAvailable").jsonPrimitive.content.toBoolean())
        assertEquals(newIsAvailable, audit.payload.getValue("newIsAvailable").jsonPrimitive.content.toBoolean())
        assertEquals(source, audit.payload.getValue("source").jsonPrimitive.content)
    }

    private fun assertMenuItemAvailabilityChangeAudit(
        audit: MenuDeleteAuditRow,
        venueId: Long,
        itemId: Long,
        oldIsAvailable: Boolean,
        newIsAvailable: Boolean,
        actorUserId: Long,
        source: MenuItemAvailabilitySource,
    ) {
        assertEquals(actorUserId, audit.actorUserId)
        assertEquals(MENU_ITEM_AVAILABILITY_CHANGED_AUDIT_ACTION, audit.action)
        assertEquals("menu_item", audit.entityType)
        assertEquals(itemId, audit.entityId)
        assertEquals(
            setOf("venueId", "itemId", "oldIsAvailable", "newIsAvailable", "source"),
            audit.payload.keys,
        )
        assertEquals(venueId, audit.payload.longValue("venueId"))
        assertEquals(itemId, audit.payload.longValue("itemId"))
        assertEquals(oldIsAvailable, audit.payload.getValue("oldIsAvailable").jsonPrimitive.content.toBoolean())
        assertEquals(newIsAvailable, audit.payload.getValue("newIsAvailable").jsonPrimitive.content.toBoolean())
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

    private fun optionNames(
        connection: Connection,
        itemId: Long,
    ): List<String> =
        connection.prepareStatement(
            "SELECT name FROM menu_item_options WHERE item_id = ? ORDER BY sort_order, id",
        ).use { statement ->
            statement.setLong(1, itemId)
            statement.executeQuery().use { rs ->
                buildList {
                    while (rs.next()) add(rs.getString("name"))
                }
            }
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

    private fun itemRow(
        jdbcUrl: String,
        itemId: Long,
    ): ItemRowSnapshot =
        DriverManager.getConnection(jdbcUrl, "sa", "").use { connection ->
            requireNotNull(itemRow(connection, itemId))
        }

    private fun categoryRow(
        jdbcUrl: String,
        categoryId: Long,
    ): CategoryRowSnapshot =
        DriverManager.getConnection(jdbcUrl, "sa", "").use { connection ->
            connection.prepareStatement(
                """
                SELECT id, venue_id, name, sort_order, category_type, updated_at
                FROM menu_categories
                WHERE id = ?
                """.trimIndent(),
            ).use { statement ->
                statement.setLong(1, categoryId)
                statement.executeQuery().use { rs ->
                    check(rs.next())
                    rs.toCategoryRowSnapshot()
                }
            }
        }

    private fun categoryRows(
        jdbcUrl: String,
        venueId: Long,
    ): List<CategoryRowSnapshot> =
        DriverManager.getConnection(jdbcUrl, "sa", "").use { connection ->
            connection.prepareStatement(
                """
                SELECT id, venue_id, name, sort_order, category_type, updated_at
                FROM menu_categories
                WHERE venue_id = ?
                ORDER BY id
                """.trimIndent(),
            ).use { statement ->
                statement.setLong(1, venueId)
                statement.executeQuery().use { rs ->
                    buildList {
                        while (rs.next()) add(rs.toCategoryRowSnapshot())
                    }
                }
            }
        }

    private fun java.sql.ResultSet.toCategoryRowSnapshot(): CategoryRowSnapshot =
        CategoryRowSnapshot(
            id = getLong("id"),
            venueId = getLong("venue_id"),
            name = getString("name"),
            sortOrder = getInt("sort_order"),
            categoryType = getString("category_type"),
            updatedAt = getObject("updated_at").toString(),
        )

    private fun itemRow(
        connection: Connection,
        itemId: Long,
    ): ItemRowSnapshot? =
        connection.prepareStatement(
            """
            SELECT id, venue_id, category_id, name, price_minor, currency, is_available,
                   sort_order, item_type, updated_at
            FROM menu_items
            WHERE id = ?
            """.trimIndent(),
        ).use { statement ->
            statement.setLong(1, itemId)
            statement.executeQuery().use { rs ->
                if (!rs.next()) {
                    null
                } else {
                    ItemRowSnapshot(
                        id = rs.getLong("id"),
                        venueId = rs.getLong("venue_id"),
                        categoryId = rs.getLong("category_id"),
                        name = rs.getString("name"),
                        priceMinor = rs.getLong("price_minor"),
                        currency = rs.getString("currency"),
                        isAvailable = rs.getBoolean("is_available"),
                        sortOrder = rs.getInt("sort_order"),
                        itemType = rs.getString("item_type"),
                        updatedAt = rs.getObject("updated_at").toString(),
                    )
                }
            }
        }

    private fun auditExists(
        connection: Connection,
        action: String,
        entityId: Long,
    ): Boolean =
        connection.prepareStatement(
            """
            SELECT COUNT(*)
            FROM audit_log
            WHERE action = ? AND entity_id = ?
            """.trimIndent(),
        ).use { statement ->
            statement.setString(1, action)
            statement.setLong(2, entityId)
            statement.executeQuery().use { rs ->
                check(rs.next())
                rs.getInt(1) == 1
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

    private data class ItemRowSnapshot(
        val id: Long,
        val venueId: Long,
        val categoryId: Long,
        val name: String,
        val priceMinor: Long,
        val currency: String,
        val isAvailable: Boolean,
        val sortOrder: Int,
        val itemType: String?,
        val updatedAt: String,
    )

    private data class CategoryRowSnapshot(
        val id: Long,
        val venueId: Long,
        val name: String,
        val sortOrder: Int,
        val categoryType: String,
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

private suspend fun VenueMenuRepository.createCategory(
    venueId: Long,
    name: String,
    categoryType: MenuSemanticType = MenuSemanticType.OTHER,
): VenueMenuCategory =
    createCategory(
        venueId = venueId,
        name = name,
        actorUserId = 101L,
        source = MenuItemAvailabilitySource.VENUE_MINI_APP,
        categoryType = categoryType,
    )

private suspend fun VenueMenuRepository.updateCategoryType(
    venueId: Long,
    categoryId: Long,
    categoryType: MenuSemanticType,
): VenueMenuCategory? =
    updateCategory(
        venueId = venueId,
        categoryId = categoryId,
        name = null,
        categoryType = categoryType,
        actorUserId = 101L,
        source = MenuItemAvailabilitySource.VENUE_MINI_APP,
    )

private suspend fun VenueMenuRepository.updateItemType(
    venueId: Long,
    itemId: Long,
    itemType: MenuSemanticType?,
): VenueMenuItem? =
    updateItemType(
        venueId = venueId,
        itemId = itemId,
        itemType = itemType,
        actorUserId = 101L,
        source = MenuItemAvailabilitySource.VENUE_MINI_APP,
    )
