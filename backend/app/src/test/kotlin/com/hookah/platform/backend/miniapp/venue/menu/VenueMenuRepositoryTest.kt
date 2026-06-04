package com.hookah.platform.backend.miniapp.venue.menu

import com.hookah.platform.backend.miniapp.venue.VenueStatus
import kotlinx.coroutines.runBlocking
import org.flywaydb.core.Flyway
import org.h2.jdbcx.JdbcDataSource
import java.sql.DriverManager
import java.sql.Statement
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

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
