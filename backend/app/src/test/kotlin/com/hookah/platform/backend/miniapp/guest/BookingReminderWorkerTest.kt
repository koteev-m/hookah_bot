package com.hookah.platform.backend.miniapp.guest

import com.hookah.platform.backend.miniapp.guest.db.BookingStatus
import com.hookah.platform.backend.miniapp.guest.db.GuestBookingRepository
import com.hookah.platform.backend.telegram.InlineKeyboardMarkup
import com.hookah.platform.backend.telegram.TelegramOutboxEnqueueOutcome
import com.hookah.platform.backend.telegram.TelegramOutboxEnqueuer
import com.hookah.platform.backend.telegram.TelegramTrafficPolicy
import com.hookah.platform.backend.telegram.db.TelegramOutboxRepository
import com.hookah.platform.backend.telegram.db.VenueSettingsRepository
import io.ktor.server.config.MapApplicationConfig
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
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
    private val unrestrictedScope = TelegramTrafficPolicy.unrestricted().outboundClaimScope

    @Test
    fun `runOnce queues one M7c reminder with final transactional copy`() =
        runBlocking {
            val jdbcUrl = migratedJdbcUrl("booking-reminder-worker")
            val fixture = seedVenueAndUser(jdbcUrl)
            val repository = GuestBookingRepository(dataSource(jdbcUrl))
            val outboxEnqueuer = mockk<TelegramOutboxEnqueuer>()
            val venueSettingsRepository = mockk<VenueSettingsRepository>()
            val zoneId = ZoneId.of("Europe/Moscow")
            val serviceDate = LocalDate.of(2030, 5, 10)
            val scheduledAt = LocalDateTime.of(serviceDate, LocalTime.of(20, 0)).atZone(zoneId).toInstant()
            val now = LocalDateTime.of(2030, 5, 5, 12, 0).atZone(zoneId).toInstant()
            val reminderAt = LocalDateTime.of(2030, 5, 9, 20, 0).atZone(zoneId).toInstant()
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
            coEvery {
                outboxEnqueuer.enqueueSendMessage(any(), any(), any(), any(), any())
            } returns TelegramOutboxEnqueueOutcome.ENQUEUED
            val worker =
                BookingReminderWorker(
                    repository = repository,
                    outboxEnqueuer = outboxEnqueuer,
                    outboundClaimScope = unrestrictedScope,
                    venueSettingsRepository = venueSettingsRepository,
                    interval = Duration.ofSeconds(60),
                    batchSize = 100,
                    scope = CoroutineScope(Dispatchers.Unconfined),
                    nowProvider = { reminderAt },
                )

            assertEquals(1, worker.runOnce().queuedCount)
            assertEquals(0, worker.runOnce().queuedCount)
            coVerify(exactly = 1) {
                outboxEnqueuer.enqueueSendMessage(
                    fixture.userId,
                    match { text ->
                        text.contains("Напоминаем о брони") &&
                            text.contains("Место: Booking Venue") &&
                            text.contains("Бронь №1 · 10.05.2030, 20:00") &&
                            !text.contains("Дата и время:") &&
                            text.contains("Гостей: 2") &&
                            text.contains("Держим стол до 20:30.")
                    },
                    match { markup ->
                        markup is InlineKeyboardMarkup &&
                            markup.inlineKeyboard.flatten().any {
                                it.callbackData?.startsWith("br_ok:${booking.id}:") == true
                            } &&
                            markup.inlineKeyboard.flatten().any { it.callbackData == "br_cancel:${booking.id}" } &&
                            markup.inlineKeyboard.flatten().any { it.callbackData == "br_reschedule:${booking.id}" } &&
                            markup.inlineKeyboard.flatten().none { it.callbackData == "br_msg:${booking.id}" }
                    },
                    null,
                    match { it?.startsWith("booking-reminder:") == true },
                )
            }
            assertEquals("QUEUED", reminderStatus(jdbcUrl, booking.id))
        }

    @Test
    fun `traffic policy skip leaves reminder unchanged and does not starve later allowed reminder`() =
        runBlocking {
            val jdbcUrl = migratedJdbcUrl("booking-reminder-policy-skip")
            val fixture = seedVenueAndUser(jdbcUrl)
            val allowedUserId = fixture.userId + 1
            insertUser(jdbcUrl, allowedUserId)
            val repository = GuestBookingRepository(dataSource(jdbcUrl))
            val venueSettingsRepository = mockk<VenueSettingsRepository>()
            val zoneId = ZoneId.of("Europe/Moscow")
            val serviceDate = LocalDate.of(2030, 5, 10)
            val scheduledAt = LocalDateTime.of(serviceDate, LocalTime.of(20, 0)).atZone(zoneId).toInstant()
            val now = LocalDateTime.of(2030, 5, 5, 12, 0).atZone(zoneId).toInstant()
            val reminderAt = LocalDateTime.of(2030, 5, 9, 20, 0).atZone(zoneId).toInstant()
            val deniedBooking =
                repository.create(
                    venueId = fixture.venueId,
                    userId = fixture.userId,
                    scheduledAt = scheduledAt,
                    partySize = 2,
                    comment = null,
                    venueZoneId = zoneId,
                    serviceDate = serviceDate,
                )
            val allowedBooking =
                repository.create(
                    venueId = fixture.venueId,
                    userId = allowedUserId,
                    scheduledAt = scheduledAt,
                    partySize = 3,
                    comment = null,
                    venueZoneId = zoneId,
                    serviceDate = serviceDate,
                )
            listOf(deniedBooking, allowedBooking).forEach { booking ->
                repository.updateByVenue(booking.id, fixture.venueId, BookingStatus.CONFIRMED)
                repository.scheduleRemindersForBooking(booking.id, now = now, venueZoneId = zoneId)
            }
            val deniedBefore = reminderSnapshot(jdbcUrl, deniedBooking.id)
            val allowedBefore = reminderSnapshot(jdbcUrl, allowedBooking.id)
            assertEquals(true, deniedBefore.id < allowedBefore.id)
            val trafficPolicy =
                TelegramTrafficPolicy.from(
                    MapApplicationConfig(
                        "telegram.trafficPolicy" to "ALLOWLIST",
                        "telegram.allowedUserIds" to allowedUserId.toString(),
                        "telegram.allowedChatIds" to allowedUserId.toString(),
                    ),
                    appEnv = "staging",
                )
            val outboxEnqueuer =
                TelegramOutboxEnqueuer(
                    TelegramOutboxRepository(dataSource(jdbcUrl), trafficPolicy),
                    Json { ignoreUnknownKeys = true },
                    trafficPolicy,
                )
            coEvery { venueSettingsRepository.resolveZoneId(fixture.venueId, any()) } returns zoneId
            val worker =
                BookingReminderWorker(
                    repository = repository,
                    outboxEnqueuer = outboxEnqueuer,
                    outboundClaimScope = trafficPolicy.outboundClaimScope,
                    venueSettingsRepository = venueSettingsRepository,
                    interval = Duration.ofSeconds(60),
                    batchSize = 1,
                    scope = CoroutineScope(Dispatchers.Unconfined),
                    nowProvider = { reminderAt },
                )

            val result = worker.runOnce()

            assertEquals(1, result.queuedCount)
            assertEquals(0, result.failedCount)
            assertEquals(deniedBefore, reminderSnapshot(jdbcUrl, deniedBooking.id))
            assertEquals("QUEUED", reminderStatus(jdbcUrl, allowedBooking.id))
            assertEquals(0, outboxCount(jdbcUrl, fixture.userId))
            val allowedOutbox = outboxEnvelope(jdbcUrl, allowedUserId)
            assertEquals("NEW", allowedOutbox.status)
            assertEquals("sendMessage", allowedOutbox.method)
            assertEquals(allowedUserId, allowedOutbox.payloadChatId)
            assertEquals(true, allowedOutbox.dedupeKey.startsWith("booking-reminder:"))

            val groupOnlyPolicy =
                TelegramTrafficPolicy.from(
                    MapApplicationConfig(
                        "telegram.trafficPolicy" to "ALLOWLIST",
                        "telegram.allowedUserIds" to fixture.userId.toString(),
                        "telegram.allowedChatIds" to "-100123",
                    ),
                    appEnv = "staging",
                )
            assertEquals(
                emptyList(),
                repository.pickDueReminders(
                    now = reminderAt,
                    limit = 1,
                    outboundClaimScope = groupOnlyPolicy.outboundClaimScope,
                ),
            )
            assertEquals(deniedBefore, reminderSnapshot(jdbcUrl, deniedBooking.id))
        }

    @Test
    fun `enqueue exception remains a failed reminder delivery`() =
        runBlocking {
            val jdbcUrl = migratedJdbcUrl("booking-reminder-enqueue-error")
            val fixture = seedVenueAndUser(jdbcUrl)
            val repository = GuestBookingRepository(dataSource(jdbcUrl))
            val outboxEnqueuer = mockk<TelegramOutboxEnqueuer>()
            val venueSettingsRepository = mockk<VenueSettingsRepository>()
            val zoneId = ZoneId.of("Europe/Moscow")
            val serviceDate = LocalDate.of(2030, 5, 10)
            val scheduledAt = LocalDateTime.of(serviceDate, LocalTime.of(20, 0)).atZone(zoneId).toInstant()
            val now = LocalDateTime.of(2030, 5, 5, 12, 0).atZone(zoneId).toInstant()
            val reminderAt = LocalDateTime.of(2030, 5, 9, 20, 0).atZone(zoneId).toInstant()
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
            coEvery {
                outboxEnqueuer.enqueueSendMessage(any(), any(), any(), any(), any())
            } throws IllegalStateException("enqueue failed")
            val worker =
                BookingReminderWorker(
                    repository = repository,
                    outboxEnqueuer = outboxEnqueuer,
                    outboundClaimScope = unrestrictedScope,
                    venueSettingsRepository = venueSettingsRepository,
                    interval = Duration.ofSeconds(60),
                    batchSize = 100,
                    scope = CoroutineScope(Dispatchers.Unconfined),
                    nowProvider = { reminderAt },
                )

            val result = worker.runOnce()

            assertEquals(0, result.queuedCount)
            assertEquals(1, result.failedCount)
            assertEquals("FAILED", reminderStatus(jdbcUrl, booking.id))
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

    private fun insertUser(
        jdbcUrl: String,
        userId: Long,
    ) {
        DriverManager.getConnection(jdbcUrl, "sa", "").use { connection ->
            connection.prepareStatement(
                """
                INSERT INTO users (telegram_user_id, username, first_name, updated_at)
                VALUES (?, 'allowed_guest', 'Allowed Guest', CURRENT_TIMESTAMP)
                """.trimIndent(),
            ).use { statement ->
                statement.setLong(1, userId)
                statement.executeUpdate()
            }
        }
    }

    private fun reminderSnapshot(
        jdbcUrl: String,
        bookingId: Long,
    ): ReminderDeliverySnapshot =
        DriverManager.getConnection(jdbcUrl, "sa", "").use { connection ->
            connection.prepareStatement(
                """
                SELECT id, booking_id, kind, scheduled_for, status, attempts, dedupe_key,
                       sent_at, last_error, created_at, updated_at, policy_version
                FROM booking_reminders
                WHERE booking_id = ?
                  AND policy_version = 'M7C'
                """.trimIndent(),
            ).use { statement ->
                statement.setLong(1, bookingId)
                statement.executeQuery().use { rs ->
                    check(rs.next())
                    ReminderDeliverySnapshot(
                        id = rs.getLong("id"),
                        bookingId = rs.getLong("booking_id"),
                        kind = rs.getString("kind"),
                        scheduledFor = rs.getTimestamp("scheduled_for").toInstant(),
                        status = rs.getString("status"),
                        attempts = rs.getInt("attempts"),
                        dedupeKey = rs.getString("dedupe_key"),
                        sentAt = rs.getTimestamp("sent_at")?.toInstant(),
                        lastError = rs.getString("last_error"),
                        createdAt = rs.getTimestamp("created_at").toInstant(),
                        updatedAt = rs.getTimestamp("updated_at").toInstant(),
                        policyVersion = rs.getString("policy_version"),
                    )
                }
            }
        }

    private fun reminderStatus(
        jdbcUrl: String,
        bookingId: Long,
    ): String =
        DriverManager.getConnection(jdbcUrl, "sa", "").use { connection ->
            connection.prepareStatement(
                """
                SELECT status
                FROM booking_reminders
                WHERE booking_id = ?
                """.trimIndent(),
            ).use { statement ->
                statement.setLong(1, bookingId)
                statement.executeQuery().use { rs ->
                    rs.next()
                    rs.getString("status")
                }
            }
        }

    private fun outboxCount(
        jdbcUrl: String,
        chatId: Long,
    ): Int =
        DriverManager.getConnection(jdbcUrl, "sa", "").use { connection ->
            connection.prepareStatement("SELECT COUNT(*) FROM telegram_outbox WHERE chat_id = ?").use { statement ->
                statement.setLong(1, chatId)
                statement.executeQuery().use { rs ->
                    rs.next()
                    rs.getInt(1)
                }
            }
        }

    private fun outboxEnvelope(
        jdbcUrl: String,
        chatId: Long,
    ): OutboxEnvelope =
        DriverManager.getConnection(jdbcUrl, "sa", "").use { connection ->
            connection.prepareStatement(
                """
                SELECT method, payload_json, status, dedupe_key
                FROM telegram_outbox
                WHERE chat_id = ?
                """.trimIndent(),
            ).use { statement ->
                statement.setLong(1, chatId)
                statement.executeQuery().use { rs ->
                    check(rs.next())
                    val payload = Json.parseToJsonElement(rs.getString("payload_json")).jsonObject
                    OutboxEnvelope(
                        method = rs.getString("method"),
                        payloadChatId = payload.getValue("chat_id").jsonPrimitive.content.toLong(),
                        status = rs.getString("status"),
                        dedupeKey = rs.getString("dedupe_key"),
                    )
                }
            }
        }

    private data class BookingFixture(
        val venueId: Long,
        val userId: Long,
    )

    private data class ReminderDeliverySnapshot(
        val id: Long,
        val bookingId: Long,
        val kind: String,
        val scheduledFor: Instant,
        val status: String,
        val attempts: Int,
        val dedupeKey: String,
        val sentAt: Instant?,
        val lastError: String?,
        val createdAt: Instant,
        val updatedAt: Instant,
        val policyVersion: String,
    )

    private data class OutboxEnvelope(
        val method: String,
        val payloadChatId: Long,
        val status: String,
        val dedupeKey: String,
    )
}
