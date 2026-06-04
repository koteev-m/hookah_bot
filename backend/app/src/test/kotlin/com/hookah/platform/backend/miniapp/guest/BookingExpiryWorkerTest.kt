package com.hookah.platform.backend.miniapp.guest

import com.hookah.platform.backend.miniapp.guest.db.BookingStatus
import com.hookah.platform.backend.miniapp.guest.db.GuestBookingRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.flywaydb.core.Flyway
import org.h2.jdbcx.JdbcDataSource
import java.sql.DriverManager
import java.sql.Statement
import java.time.Duration
import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals

class BookingExpiryWorkerTest {
    @Test
    fun `runOnce expires due bookings and is idempotent`() =
        runBlocking {
            val jdbcUrl = migratedJdbcUrl("booking-expiry-worker")
            val fixture = seedVenueAndUser(jdbcUrl)
            val repository = GuestBookingRepository(dataSource(jdbcUrl))
            val now = Instant.now()
            val due =
                repository.create(
                    venueId = fixture.venueId,
                    userId = fixture.userId,
                    scheduledAt = now.minus(Duration.ofHours(2)),
                    partySize = 2,
                    comment = null,
                )
            val notDue =
                repository.create(
                    venueId = fixture.venueId,
                    userId = fixture.userId,
                    scheduledAt = now.plus(Duration.ofHours(2)),
                    partySize = 2,
                    comment = null,
                )
            val worker =
                BookingExpiryWorker(
                    repository = repository,
                    interval = Duration.ofSeconds(60),
                    batchSize = 100,
                    scope = CoroutineScope(Dispatchers.Unconfined),
                    nowProvider = { now },
                )

            assertEquals(1, worker.runOnce().expiredCount)
            assertEquals(0, worker.runOnce().expiredCount)
            assertEquals(BookingStatus.EXPIRED, repository.findByVenue(due.id, fixture.venueId)?.status)
            assertEquals(BookingStatus.PENDING, repository.findByVenue(notDue.id, fixture.venueId)?.status)
        }

    private fun migratedJdbcUrl(prefix: String): String {
        val jdbcUrl =
            "jdbc:h2:mem:$prefix-${UUID.randomUUID()};MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;" +
                "DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1"
        Flyway.configure()
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

    private fun seedVenueAndUser(jdbcUrl: String): BookingFixture =
        DriverManager.getConnection(jdbcUrl, "sa", "").use { connection ->
            val venueId =
                connection.prepareStatement(
                    """
                    INSERT INTO venues (name, city, address, status)
                    VALUES ('Booking Venue', 'City', 'Address', 'PUBLISHED')
                    """.trimIndent(),
                    Statement.RETURN_GENERATED_KEYS,
                ).use { statement ->
                    statement.executeUpdate()
                    statement.generatedKeys.use { keys ->
                        keys.next()
                        keys.getLong(1)
                    }
                }
            val userId = 424242L
            connection.prepareStatement(
                """
                INSERT INTO users (telegram_user_id, username, first_name, updated_at)
                VALUES (?, 'guest', 'Guest', CURRENT_TIMESTAMP)
                """.trimIndent(),
            ).use { statement ->
                statement.setLong(1, userId)
                statement.executeUpdate()
            }
            BookingFixture(venueId = venueId, userId = userId)
        }

    private data class BookingFixture(
        val venueId: Long,
        val userId: Long,
    )
}
