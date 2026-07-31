package com.hookah.platform.backend.miniapp.venue.menu

import com.hookah.platform.backend.api.DatabaseUnavailableException
import com.hookah.platform.backend.api.InvalidInputException
import com.hookah.platform.backend.api.MenuShiftCheckStaleException
import com.hookah.platform.backend.api.NotFoundException
import com.hookah.platform.backend.miniapp.venue.AuditLogRepository
import com.hookah.platform.backend.miniapp.venue.VenueStatus
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import org.flywaydb.core.Flyway
import org.h2.jdbcx.JdbcDataSource
import java.sql.DriverManager
import java.sql.Statement
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

    private fun seedVenue(jdbcUrl: String): Long =
        DriverManager.getConnection(jdbcUrl, "sa", "").use { connection ->
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
}
