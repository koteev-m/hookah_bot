package com.hookah.platform.backend.miniapp.guest

import com.hookah.platform.backend.miniapp.guest.db.BookingStatus
import com.hookah.platform.backend.miniapp.guest.db.GuestBookingRepository
import com.hookah.platform.backend.telegram.InlineKeyboardMarkup
import com.hookah.platform.backend.telegram.TelegramOutboxEnqueuer
import com.hookah.platform.backend.telegram.db.VenueSettingsRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.flywaydb.core.Flyway
import org.h2.jdbcx.JdbcDataSource
import java.sql.DriverManager
import java.sql.Statement
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals

class BookingReminderWorkerTest {
    @Test
    fun `runOnce sends due reminder once with overnight human text`() =
        runBlocking {
            val jdbcUrl = migratedJdbcUrl("booking-reminder-worker")
            val fixture = seedVenueAndUser(jdbcUrl)
            val repository = GuestBookingRepository(dataSource(jdbcUrl))
            val outboxEnqueuer = mockk<TelegramOutboxEnqueuer>()
            val venueSettingsRepository = mockk<VenueSettingsRepository>()
            val zoneId = ZoneId.of("Europe/Moscow")
            val serviceDate = LocalDate.of(2030, 5, 10)
            val scheduledAt = LocalDateTime.of(serviceDate.plusDays(1), LocalTime.of(2, 0)).atZone(zoneId).toInstant()
            val now = LocalDateTime.of(2030, 5, 6, 12, 0).atZone(zoneId).toInstant()
            val dayReminderAt = LocalDateTime.of(serviceDate, LocalTime.of(11, 0)).atZone(zoneId).toInstant()
            val booking =
                repository.create(
                    venueId = fixture.venueId,
                    userId = fixture.userId,
                    scheduledAt = scheduledAt,
                    partySize = 2,
                    comment = null,
                    venueZoneId = zoneId,
                    serviceDate = serviceDate,
                )
            repository.updateByVenue(booking.id, fixture.venueId, BookingStatus.CONFIRMED)
            repository.scheduleRemindersForBooking(booking.id, now = now, venueZoneId = zoneId)
            coEvery { venueSettingsRepository.resolveZoneId(fixture.venueId, any()) } returns zoneId
            coEvery { outboxEnqueuer.enqueueSendMessage(any(), any(), any(), any()) } returns Unit
            val worker =
                BookingReminderWorker(
                    repository = repository,
                    outboxEnqueuer = outboxEnqueuer,
                    venueSettingsRepository = venueSettingsRepository,
                    interval = Duration.ofSeconds(60),
                    batchSize = 100,
                    scope = CoroutineScope(Dispatchers.Unconfined),
                    nowProvider = { dayReminderAt },
                )

            assertEquals(1, worker.runOnce().sentCount)
            assertEquals(0, worker.runOnce().sentCount)
            coVerify(exactly = 1) {
                outboxEnqueuer.enqueueSendMessage(
                    fixture.userId,
                    match { text ->
                        text.contains("Сегодня ждём вас в пятницу ночью, в 02:00.") &&
                            !text.contains("суббот") &&
                            !text.contains("Дата смены")
                    },
                    match { markup ->
                        markup is InlineKeyboardMarkup &&
                            markup.inlineKeyboard.flatten().any { it.callbackData == "br_ok:${booking.id}" } &&
                            markup.inlineKeyboard.flatten().any { it.callbackData == "br_cancel:${booking.id}" } &&
                            markup.inlineKeyboard.flatten().any { it.callbackData == "br_msg:${booking.id}" }
                    },
                    null,
                )
            }
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
