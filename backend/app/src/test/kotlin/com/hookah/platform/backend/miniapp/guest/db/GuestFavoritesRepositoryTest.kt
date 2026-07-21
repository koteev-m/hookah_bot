package com.hookah.platform.backend.miniapp.guest.db

import com.hookah.platform.backend.miniapp.venue.VenueStatus
import kotlinx.coroutines.runBlocking
import org.flywaydb.core.Flyway
import org.h2.jdbcx.JdbcDataSource
import java.sql.Connection
import java.sql.DriverManager
import java.sql.PreparedStatement
import java.sql.Statement
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import javax.sql.DataSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GuestFavoritesRepositoryTest {
    @Test
    fun `venue favorites are idempotent and scoped to current user`() =
        runBlocking {
            val jdbcUrl = migratedJdbcUrl("fav-venues-scope")
            val fixture = seedFavoritesFixture(jdbcUrl)
            val repository = GuestFavoritesRepository(dataSource(jdbcUrl))

            assertTrue(repository.addVenueFavorite(GUEST_ONE, fixture.visibleVenueId))
            assertTrue(repository.addVenueFavorite(GUEST_ONE, fixture.visibleVenueId))
            assertTrue(repository.addVenueFavorite(GUEST_TWO, fixture.visibleVenueId))

            val guestOneVenues = repository.listFavoriteVenues(GUEST_ONE)
            val guestTwoVenues = repository.listFavoriteVenues(GUEST_TWO)

            assertEquals(listOf(fixture.visibleVenueId), guestOneVenues.map { it.venueId })
            assertEquals(listOf(fixture.visibleVenueId), guestTwoVenues.map { it.venueId })
            assertTrue(repository.isVenueFavorite(GUEST_ONE, fixture.visibleVenueId))

            assertTrue(repository.removeVenueFavorite(GUEST_ONE, fixture.visibleVenueId))
            assertFalse(repository.removeVenueFavorite(GUEST_ONE, fixture.visibleVenueId))
            assertFalse(repository.isVenueFavorite(GUEST_ONE, fixture.visibleVenueId))
        }

    @Test
    fun `venue favorites hide unpublished and blocked venues`() =
        runBlocking {
            val jdbcUrl = migratedJdbcUrl("fav-venues-hidden")
            val fixture = seedFavoritesFixture(jdbcUrl)
            val repository = GuestFavoritesRepository(dataSource(jdbcUrl))

            assertFalse(repository.addVenueFavorite(GUEST_ONE, fixture.hiddenVenueId))
            assertFalse(repository.addVenueFavorite(GUEST_ONE, fixture.blockedVenueId))
            DriverManager.getConnection(jdbcUrl, "sa", "").use { connection ->
                insertVenueFavorite(connection, GUEST_ONE, fixture.hiddenVenueId)
                insertVenueFavorite(connection, GUEST_ONE, fixture.blockedVenueId)
                insertVenueFavorite(connection, GUEST_ONE, fixture.visibleVenueId)
            }

            val venues = repository.listFavoriteVenues(GUEST_ONE)

            assertEquals(listOf(fixture.visibleVenueId), venues.map { it.venueId })
        }

    @Test
    fun `venue favorites reject every unavailable lifecycle and blocked subscription status`() =
        runBlocking {
            val jdbcUrl = migratedJdbcUrl("fav-venues-all-blocked")
            DriverManager.getConnection(jdbcUrl, "sa", "").use { connection ->
                insertUser(connection, GUEST_ONE)
            }
            val repository = GuestFavoritesRepository(dataSource(jdbcUrl))
            val unavailableVenueIds = mutableListOf<Long>()

            DriverManager.getConnection(jdbcUrl, "sa", "").use { connection ->
                VenueStatus.entries
                    .filterNot { it == VenueStatus.PUBLISHED }
                    .forEach { status ->
                        val venueId = insertVenue(connection, "Venue ${status.name}", status.dbValue)
                        assertFalse(repository.addVenueFavorite(GUEST_ONE, venueId), status.name)
                        insertVenueFavorite(connection, GUEST_ONE, venueId)
                        unavailableVenueIds.add(venueId)
                    }
                listOf("CANCELED", "SUSPENDED", "SUSPENDED_BY_PLATFORM").forEach { status ->
                    val venueId = insertVenue(connection, "Subscription $status", VenueStatus.PUBLISHED.dbValue)
                    insertSubscription(connection, venueId, status)
                    assertFalse(repository.addVenueFavorite(GUEST_ONE, venueId), status)
                    insertVenueFavorite(connection, GUEST_ONE, venueId)
                    unavailableVenueIds.add(venueId)
                }
            }

            assertEquals(emptyList(), repository.listFavoriteVenues(GUEST_ONE))
            unavailableVenueIds.forEach { venueId ->
                assertTrue(repository.isVenueFavorite(GUEST_ONE, venueId))
            }
        }

    @Test
    fun `favorite row survives temporary unavailability and returns after republish`() =
        runBlocking {
            val jdbcUrl = migratedJdbcUrl("fav-venues-republish")
            val fixture = seedFavoritesFixture(jdbcUrl)
            val repository = GuestFavoritesRepository(dataSource(jdbcUrl))

            assertTrue(repository.addVenueFavorite(GUEST_ONE, fixture.visibleVenueId))
            updateVenueStatus(jdbcUrl, fixture.visibleVenueId, VenueStatus.HIDDEN.dbValue)
            assertEquals(emptyList(), repository.listFavoriteVenues(GUEST_ONE))
            assertTrue(repository.isVenueFavorite(GUEST_ONE, fixture.visibleVenueId))

            updateVenueStatus(jdbcUrl, fixture.visibleVenueId, VenueStatus.PUBLISHED.dbValue)
            insertSubscription(jdbcUrl, fixture.visibleVenueId, "SUSPENDED_BY_PLATFORM")
            assertEquals(emptyList(), repository.listFavoriteVenues(GUEST_ONE))
            assertTrue(repository.isVenueFavorite(GUEST_ONE, fixture.visibleVenueId))

            insertSubscription(jdbcUrl, fixture.visibleVenueId, "ACTIVE")
            assertEquals(listOf(fixture.visibleVenueId), repository.listFavoriteVenues(GUEST_ONE).map { it.venueId })
        }

    @Test
    fun `physical venue deletion cascades favorite row`() =
        runBlocking {
            val jdbcUrl = migratedJdbcUrl("fav-venues-delete-cascade")
            val fixture = seedFavoritesFixture(jdbcUrl)
            val repository = GuestFavoritesRepository(dataSource(jdbcUrl))

            assertTrue(repository.addVenueFavorite(GUEST_ONE, fixture.visibleVenueId))
            DriverManager.getConnection(jdbcUrl, "sa", "").use { connection ->
                connection.prepareStatement("DELETE FROM venues WHERE id = ?").use { statement ->
                    statement.setLong(1, fixture.visibleVenueId)
                    assertEquals(1, statement.executeUpdate())
                }
                connection.prepareStatement(
                    "SELECT COUNT(*) FROM guest_favorite_venues WHERE user_id = ? AND venue_id = ?",
                ).use { statement ->
                    statement.setLong(1, GUEST_ONE)
                    statement.setLong(2, fixture.visibleVenueId)
                    statement.executeQuery().use { rs ->
                        rs.next()
                        assertEquals(0, rs.getInt(1))
                    }
                }
            }
        }

    @Test
    fun `catalog favorite ids are resolved with one batch query`() =
        runBlocking {
            val jdbcUrl = migratedJdbcUrl("fav-venues-batch")
            val fixture = seedFavoritesFixture(jdbcUrl)
            DriverManager.getConnection(jdbcUrl, "sa", "").use { connection ->
                insertVenueFavorite(connection, GUEST_ONE, fixture.visibleVenueId)
            }
            val queryCount = AtomicInteger()
            val repository =
                GuestFavoritesRepository(
                    CountingDataSource(dataSource(jdbcUrl)) { sql ->
                        if (sql.contains("FROM guest_favorite_venues") && sql.contains("venue_id IN")) {
                            queryCount.incrementAndGet()
                        }
                    },
                )

            val ids =
                repository.findFavoriteVenueIds(
                    userId = GUEST_ONE,
                    venueIds = listOf(fixture.visibleVenueId, fixture.hiddenVenueId, fixture.blockedVenueId),
                )

            assertEquals(setOf(fixture.visibleVenueId), ids)
            assertEquals(1, queryCount.get())
        }

    @Test
    fun `item favorites filter venue availability and unavailable items`() =
        runBlocking {
            val jdbcUrl = migratedJdbcUrl("fav-items-availability")
            val fixture = seedFavoritesFixture(jdbcUrl)
            val repository = GuestFavoritesRepository(dataSource(jdbcUrl))

            assertTrue(repository.addItemFavorite(GUEST_ONE, fixture.visibleVenueId, fixture.availableItemId))
            assertTrue(repository.addItemFavorite(GUEST_ONE, fixture.visibleVenueId, fixture.availableItemId))
            assertFalse(repository.addItemFavorite(GUEST_ONE, fixture.visibleVenueId, fixture.unavailableItemId))
            assertFalse(repository.addItemFavorite(GUEST_ONE, fixture.hiddenVenueId, fixture.hiddenVenueItemId))
            assertFalse(repository.addItemFavorite(GUEST_ONE, fixture.visibleVenueId, fixture.otherVenueItemId))
            DriverManager.getConnection(jdbcUrl, "sa", "").use { connection ->
                insertItemFavorite(connection, GUEST_ONE, fixture.visibleVenueId, fixture.unavailableItemId)
                insertItemFavorite(connection, GUEST_TWO, fixture.visibleVenueId, fixture.availableItemId)
            }

            val guestOneItems = repository.listFavoriteItemsForVenue(GUEST_ONE, fixture.visibleVenueId)
            val guestTwoItems = repository.listFavoriteItemsForVenue(GUEST_TWO, fixture.visibleVenueId)

            assertEquals(listOf(fixture.availableItemId), guestOneItems.map { it.itemId })
            assertEquals(listOf(fixture.availableItemId), guestTwoItems.map { it.itemId })
            assertTrue(repository.isItemFavorite(GUEST_ONE, fixture.availableItemId))

            assertTrue(repository.removeItemFavorite(GUEST_ONE, fixture.availableItemId))
            assertFalse(repository.removeItemFavorite(GUEST_ONE, fixture.availableItemId))
            assertFalse(repository.isItemFavorite(GUEST_ONE, fixture.availableItemId))
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

    private fun seedFavoritesFixture(jdbcUrl: String): FavoritesFixture =
        DriverManager.getConnection(jdbcUrl, "sa", "").use { connection ->
            insertUser(connection, GUEST_ONE)
            insertUser(connection, GUEST_TWO)
            val visibleVenueId = insertVenue(connection, "Mix", VenueStatus.PUBLISHED.dbValue)
            val hiddenVenueId = insertVenue(connection, "Hidden", VenueStatus.HIDDEN.dbValue)
            val blockedVenueId = insertVenue(connection, "Blocked", VenueStatus.PUBLISHED.dbValue)
            insertSubscription(connection, blockedVenueId, "SUSPENDED_BY_PLATFORM")
            val visibleCategoryId = insertCategory(connection, visibleVenueId, "Кальяны")
            val hiddenCategoryId = insertCategory(connection, hiddenVenueId, "Кальяны")
            val otherCategoryId = insertCategory(connection, blockedVenueId, "Кальяны")
            FavoritesFixture(
                visibleVenueId = visibleVenueId,
                hiddenVenueId = hiddenVenueId,
                blockedVenueId = blockedVenueId,
                availableItemId = insertItem(connection, visibleVenueId, visibleCategoryId, "Кальян", available = true),
                unavailableItemId = insertItem(connection, visibleVenueId, visibleCategoryId, "Сок", available = false),
                hiddenVenueItemId = insertItem(connection, hiddenVenueId, hiddenCategoryId, "Скрыто", available = true),
                otherVenueItemId = insertItem(connection, blockedVenueId, otherCategoryId, "Другое", available = true),
            )
        }

    private fun insertUser(
        connection: Connection,
        userId: Long,
    ) {
        connection.prepareStatement(
            """
            INSERT INTO users (telegram_user_id, username, first_name)
            VALUES (?, ?, ?)
            """.trimIndent(),
        ).use { statement ->
            statement.setLong(1, userId)
            statement.setString(2, "user$userId")
            statement.setString(3, "User $userId")
            statement.executeUpdate()
        }
    }

    private fun insertVenue(
        connection: Connection,
        name: String,
        status: String,
    ): Long =
        connection.prepareStatement(
            """
            INSERT INTO venues (name, city, address, status)
            VALUES (?, 'Москва', 'Тверская, 1', ?)
            """.trimIndent(),
            Statement.RETURN_GENERATED_KEYS,
        ).use { statement ->
            statement.setString(1, name)
            statement.setString(2, status)
            statement.executeUpdate()
            statement.generatedKeys.use { keys ->
                keys.next()
                keys.getLong(1)
            }
        }

    private fun insertSubscription(
        connection: Connection,
        venueId: Long,
        status: String,
    ) {
        connection.prepareStatement(
            """
            MERGE INTO venue_subscriptions (venue_id, status, updated_at)
            KEY (venue_id)
            VALUES (?, ?, CURRENT_TIMESTAMP)
            """.trimIndent(),
        ).use { statement ->
            statement.setLong(1, venueId)
            statement.setString(2, status)
            statement.executeUpdate()
        }
    }

    private fun insertSubscription(
        jdbcUrl: String,
        venueId: Long,
        status: String,
    ) {
        DriverManager.getConnection(jdbcUrl, "sa", "").use { connection ->
            insertSubscription(connection, venueId, status)
        }
    }

    private fun updateVenueStatus(
        jdbcUrl: String,
        venueId: Long,
        status: String,
    ) {
        DriverManager.getConnection(jdbcUrl, "sa", "").use { connection ->
            connection.prepareStatement("UPDATE venues SET status = ? WHERE id = ?").use { statement ->
                statement.setString(1, status)
                statement.setLong(2, venueId)
                statement.executeUpdate()
            }
        }
    }

    private fun insertCategory(
        connection: Connection,
        venueId: Long,
        name: String,
    ): Long =
        connection.prepareStatement(
            """
            INSERT INTO menu_categories (venue_id, name)
            VALUES (?, ?)
            """.trimIndent(),
            Statement.RETURN_GENERATED_KEYS,
        ).use { statement ->
            statement.setLong(1, venueId)
            statement.setString(2, name)
            statement.executeUpdate()
            statement.generatedKeys.use { keys ->
                keys.next()
                keys.getLong(1)
            }
        }

    private fun insertItem(
        connection: Connection,
        venueId: Long,
        categoryId: Long,
        name: String,
        available: Boolean,
    ): Long =
        connection.prepareStatement(
            """
            INSERT INTO menu_items (venue_id, category_id, name, price_minor, currency, is_available)
            VALUES (?, ?, ?, 1000, 'RUB', ?)
            """.trimIndent(),
            Statement.RETURN_GENERATED_KEYS,
        ).use { statement ->
            statement.setLong(1, venueId)
            statement.setLong(2, categoryId)
            statement.setString(3, name)
            statement.setBoolean(4, available)
            statement.executeUpdate()
            statement.generatedKeys.use { keys ->
                keys.next()
                keys.getLong(1)
            }
        }

    private fun insertVenueFavorite(
        connection: Connection,
        userId: Long,
        venueId: Long,
    ) {
        connection.prepareStatement(
            "INSERT INTO guest_favorite_venues (user_id, venue_id) VALUES (?, ?)",
        ).use { statement ->
            statement.setLong(1, userId)
            statement.setLong(2, venueId)
            statement.executeUpdate()
        }
    }

    private fun insertItemFavorite(
        connection: Connection,
        userId: Long,
        venueId: Long,
        itemId: Long,
    ) {
        connection.prepareStatement(
            "MERGE INTO guest_favorite_items (user_id, venue_id, menu_item_id) " +
                "KEY (user_id, menu_item_id) VALUES (?, ?, ?)",
        ).use { statement ->
            statement.setLong(1, userId)
            statement.setLong(2, venueId)
            statement.setLong(3, itemId)
            statement.executeUpdate()
        }
    }

    private data class FavoritesFixture(
        val visibleVenueId: Long,
        val hiddenVenueId: Long,
        val blockedVenueId: Long,
        val availableItemId: Long,
        val unavailableItemId: Long,
        val hiddenVenueItemId: Long,
        val otherVenueItemId: Long,
    )

    private class CountingDataSource(
        private val delegate: DataSource,
        private val onPrepare: (String) -> Unit,
    ) : DataSource by delegate {
        override fun getConnection(): Connection = CountingConnection(delegate.connection, onPrepare)

        override fun getConnection(
            username: String?,
            password: String?,
        ): Connection = CountingConnection(delegate.getConnection(username, password), onPrepare)
    }

    private class CountingConnection(
        private val delegate: Connection,
        private val onPrepare: (String) -> Unit,
    ) : Connection by delegate {
        override fun prepareStatement(sql: String): PreparedStatement {
            onPrepare(sql)
            return delegate.prepareStatement(sql)
        }
    }

    private companion object {
        const val GUEST_ONE = 1001L
        const val GUEST_TWO = 1002L
    }
}
