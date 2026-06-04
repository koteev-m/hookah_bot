package com.hookah.platform.backend.telegram.db

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import java.sql.DriverManager
import java.time.ZoneId
import java.util.UUID
import javax.sql.DataSource
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class VenueSettingsRepositoryTest {
    @Test
    fun `timezone can be inferred from Tomsk and resolved`() =
        runBlocking {
            val dataSource = testDataSource()
            seedVenue(dataSource, venueId = 10L)
            val repository = VenueSettingsRepository(dataSource)

            val saved =
                repository.updateTimezoneFromLocation(
                    venueId = 10L,
                    city = "Томск",
                    address = "проспект Ленина, 10",
                )

            assertEquals("Asia/Tomsk", saved.timezone)
            assertEquals(ZoneId.of("Asia/Tomsk"), repository.resolveZoneId(10L, ZoneId.of("Europe/Moscow")))
        }

    @Test
    fun `timezone can be inferred from Moscow`() =
        runBlocking {
            val dataSource = testDataSource()
            seedVenue(dataSource, venueId = 10L)
            val repository = VenueSettingsRepository(dataSource)

            val saved =
                repository.updateTimezoneFromLocation(
                    venueId = 10L,
                    city = "Москва",
                    address = "Новый Арбат, 24",
                )

            assertEquals("Europe/Moscow", saved.timezone)
            assertEquals(ZoneId.of("Europe/Moscow"), repository.resolveZoneId(10L, ZoneId.of("Asia/Tomsk")))
        }

    @Test
    fun `unknown city falls back safely`() =
        runBlocking {
            val dataSource = testDataSource()
            seedVenue(dataSource, venueId = 10L)
            val repository = VenueSettingsRepository(dataSource)

            val saved =
                repository.updateTimezoneFromLocation(
                    venueId = 10L,
                    city = "Новый неизвестный город",
                    address = "центральная улица, 1",
                )

            assertEquals("Europe/Moscow", saved.timezone)
            assertEquals(ZoneId.of("Europe/Moscow"), repository.resolveZoneId(10L, ZoneId.of("Asia/Tomsk")))
        }

    @Test
    fun `invalid stored timezone falls back safely`() =
        runBlocking {
            val dataSource = testDataSource()
            seedVenue(dataSource, venueId = 10L)
            insertSettings(dataSource, venueId = 10L, timezone = "Not/AZone")
            val repository = VenueSettingsRepository(dataSource)

            val resolved = repository.resolveZoneId(10L, ZoneId.of("Europe/Moscow"))

            assertEquals(ZoneId.of("Europe/Moscow"), resolved)
        }

    @Test
    fun `public review url can be saved updated and cleared`() =
        runBlocking {
            val dataSource = testDataSource()
            seedVenue(dataSource, venueId = 10L)
            val repository = VenueSettingsRepository(dataSource)

            val saved = repository.updatePublicReviewUrl(10L, " https://yandex.ru/maps/org/mix/reviews ")
            assertEquals("https://yandex.ru/maps/org/mix/reviews", saved.publicReviewUrl)
            assertEquals("https://yandex.ru/maps/org/mix/reviews", repository.getPublicReviewUrl(10L))

            val updated = repository.updatePublicReviewUrl(10L, "https://example.com/review")
            assertEquals("https://example.com/review", updated.publicReviewUrl)

            val cleared = repository.clearPublicReviewUrl(10L)
            assertNull(cleared.publicReviewUrl)
            assertNull(repository.getPublicReviewUrl(10L))
        }

    @Test
    fun `public review url validation rejects unsafe values`() =
        runBlocking {
            val dataSource = testDataSource()
            seedVenue(dataSource, venueId = 10L)
            val repository = VenueSettingsRepository(dataSource)

            assertFailsWith<IllegalArgumentException> { repository.updatePublicReviewUrl(10L, "") }
            assertFailsWith<IllegalArgumentException> { repository.updatePublicReviewUrl(10L, "http://example.com") }
            assertFailsWith<IllegalArgumentException> { repository.updatePublicReviewUrl(10L, "example.com") }
            assertFailsWith<IllegalArgumentException> {
                repository.updatePublicReviewUrl(10L, "https://" + "a".repeat(2049))
            }
        }

    @Test
    fun `public review cta tracking is idempotent and can be marked done`() =
        runBlocking {
            val dataSource = testDataSource()
            seedVenue(dataSource, venueId = 10L)
            seedUser(dataSource, userId = 200L)
            val repository = VenueSettingsRepository(dataSource)

            assertEquals(false, repository.hasPublicReviewCtaBeenShown(200L, 10L))
            val shown = repository.markPublicReviewCtaShown(200L, 10L)
            assertEquals(200L, shown.userId)
            assertEquals(10L, shown.venueId)
            assertNull(shown.markedDoneAt)
            assertEquals(true, repository.hasPublicReviewCtaBeenShown(200L, 10L))

            repository.markPublicReviewCtaShown(200L, 10L)
            assertEquals(1, countPublicReviewCtaRows(dataSource, 200L, 10L))

            val done = repository.markPublicReviewCtaDone(200L, 10L)
            assertNotNull(done.markedDoneAt)
            assertEquals(1, countPublicReviewCtaRows(dataSource, 200L, 10L))
        }

    private fun testDataSource(): DataSource {
        val url =
            "jdbc:h2:mem:venue-settings-${UUID.randomUUID()};MODE=PostgreSQL;" +
                "DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1"
        return object : DataSource {
            init {
                DriverManager.getConnection(url).use { connection ->
                    connection.createStatement().use { statement ->
                        statement.execute(
                            """
                            CREATE TABLE venues (
                                id BIGINT PRIMARY KEY,
                                name CLOB NOT NULL
                            )
                            """.trimIndent(),
                        )
                        statement.execute(
                            """
                            CREATE TABLE users (
                                telegram_user_id BIGINT PRIMARY KEY,
                                username VARCHAR(255) NULL
                            )
                            """.trimIndent(),
                        )
                        statement.execute(
                            """
                            CREATE TABLE venue_settings (
                                venue_id BIGINT PRIMARY KEY,
                                notify_orders_enabled BOOLEAN NOT NULL DEFAULT TRUE,
                                notify_staff_calls_enabled BOOLEAN NOT NULL DEFAULT TRUE,
                                notify_cancellations_enabled BOOLEAN NOT NULL DEFAULT TRUE,
                                timezone VARCHAR(100) NULL,
                                public_review_url VARCHAR NULL,
                                created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
                                updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
                                CONSTRAINT fk_venue_settings_venue
                                    FOREIGN KEY (venue_id) REFERENCES venues(id) ON DELETE CASCADE
                            )
                            """.trimIndent(),
                        )
                        statement.execute(
                            """
                            CREATE TABLE guest_public_review_cta (
                                user_id BIGINT NOT NULL,
                                venue_id BIGINT NOT NULL,
                                first_shown_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
                                last_shown_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
                                clicked_at TIMESTAMP WITH TIME ZONE NULL,
                                marked_done_at TIMESTAMP WITH TIME ZONE NULL,
                                PRIMARY KEY (user_id, venue_id),
                                CONSTRAINT fk_guest_public_review_cta_user
                                    FOREIGN KEY (user_id) REFERENCES users(telegram_user_id) ON DELETE CASCADE,
                                CONSTRAINT fk_guest_public_review_cta_venue
                                    FOREIGN KEY (venue_id) REFERENCES venues(id) ON DELETE CASCADE
                            )
                            """.trimIndent(),
                        )
                    }
                }
            }

            override fun getConnection() = DriverManager.getConnection(url)

            override fun getConnection(
                username: String?,
                password: String?,
            ) = DriverManager.getConnection(url, username, password)

            override fun <T : Any?> unwrap(iface: Class<T>?): T = throw UnsupportedOperationException()

            override fun isWrapperFor(iface: Class<*>?) = false

            override fun getLogWriter() = null

            override fun setLogWriter(out: java.io.PrintWriter?) = Unit

            override fun setLoginTimeout(seconds: Int) = Unit

            override fun getLoginTimeout() = 0

            override fun getParentLogger() = java.util.logging.Logger.getGlobal()
        }
    }

    private fun seedVenue(
        dataSource: DataSource,
        venueId: Long,
    ) {
        dataSource.connection.use { connection ->
            connection.prepareStatement("INSERT INTO venues (id, name) VALUES (?, 'Mix')").use { statement ->
                statement.setLong(1, venueId)
                statement.executeUpdate()
            }
        }
    }

    private fun seedUser(
        dataSource: DataSource,
        userId: Long,
    ) {
        dataSource.connection.use { connection ->
            connection.prepareStatement("INSERT INTO users (telegram_user_id, username) VALUES (?, 'guest')").use { statement ->
                statement.setLong(1, userId)
                statement.executeUpdate()
            }
        }
    }

    private fun countPublicReviewCtaRows(
        dataSource: DataSource,
        userId: Long,
        venueId: Long,
    ): Int =
        dataSource.connection.use { connection ->
            connection.prepareStatement(
                """
                SELECT COUNT(*)
                FROM guest_public_review_cta
                WHERE user_id = ? AND venue_id = ?
                """.trimIndent(),
            ).use { statement ->
                statement.setLong(1, userId)
                statement.setLong(2, venueId)
                statement.executeQuery().use { rs -> if (rs.next()) rs.getInt(1) else 0 }
            }
        }

    private fun insertSettings(
        dataSource: DataSource,
        venueId: Long,
        timezone: String,
    ) {
        dataSource.connection.use { connection ->
            connection.prepareStatement(
                """
                INSERT INTO venue_settings (
                    venue_id,
                    notify_orders_enabled,
                    notify_staff_calls_enabled,
                    notify_cancellations_enabled,
                    timezone
                )
                VALUES (?, TRUE, TRUE, TRUE, ?)
                """.trimIndent(),
            ).use { statement ->
                statement.setLong(1, venueId)
                statement.setString(2, timezone)
                statement.executeUpdate()
            }
        }
    }
}
