package com.hookah.platform.backend.miniapp.guest

import com.hookah.platform.backend.module
import io.ktor.client.request.get
import io.ktor.server.config.MapApplicationConfig
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.delay
import org.flywaydb.core.Flyway
import java.sql.DriverManager
import java.sql.Statement
import java.sql.Timestamp
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BookingReminderWorkerConfigTest {
    @Test
    fun `missing config disables booking reminder worker`() {
        val config = BookingReminderWorkerConfig.from(MapApplicationConfig())

        assertFalse(config.enabled)
        assertEquals(Duration.ofSeconds(60), config.interval)
        assertEquals(100, config.batchSize)
    }

    @Test
    fun `explicit config controls booking reminder worker safely`() {
        assertTrue(
            BookingReminderWorkerConfig
                .from(MapApplicationConfig("booking.reminders.enabled" to "true"))
                .enabled,
        )
        assertFalse(
            BookingReminderWorkerConfig
                .from(MapApplicationConfig("booking.reminders.enabled" to "false"))
                .enabled,
        )
        assertFalse(
            BookingReminderWorkerConfig
                .from(MapApplicationConfig("booking.reminders.enabled" to ""))
                .enabled,
        )
        assertFalse(
            BookingReminderWorkerConfig
                .from(MapApplicationConfig("booking.reminders.enabled" to "yes"))
                .enabled,
        )
    }

    @Test
    fun `disabled application config leaves due reminders dormant`() =
        testApplication {
            val jdbcUrl = migratedJdbcUrl("booking-reminder-disabled")
            val fixture = seedDueReminder(jdbcUrl, policyVersion = "M7C")
            environment { config = appConfig(jdbcUrl) }
            application { module() }

            client.get("/health")
            delay(1500)

            assertEquals("PENDING", reminderStatus(jdbcUrl, fixture.reminderId))
            assertEquals(0, outboxCount(jdbcUrl))
        }

    @Test
    fun `explicit enabled application config starts only M7c reminder worker`() =
        testApplication {
            val jdbcUrl = migratedJdbcUrl("booking-reminder-enabled")
            val legacyFixture = seedDueReminder(jdbcUrl, policyVersion = "LEGACY")
            val fixture = seedDueReminder(jdbcUrl, policyVersion = "M7C")
            environment { config = appConfig(jdbcUrl, reminderEnabled = "true") }
            application { module() }

            client.get("/health")

            assertTrue(
                waitUntil {
                    reminderStatus(jdbcUrl, fixture.reminderId) == "QUEUED" && outboxCount(jdbcUrl) == 1
                },
            )
            assertEquals("PENDING", reminderStatus(jdbcUrl, legacyFixture.reminderId))
        }

    private fun appConfig(
        jdbcUrl: String,
        reminderEnabled: String? = null,
    ): MapApplicationConfig {
        val entries =
            mutableListOf(
                "app.env" to "dev",
                "db.jdbcUrl" to jdbcUrl,
                "db.user" to "sa",
                "db.password" to "",
                "telegram.enabled" to "false",
                "booking.expiry.enabled" to "false",
                "booking.reminders.intervalSeconds" to "1",
                "booking.reminders.batchSize" to "10",
                "visit.feedback.enabled" to "false",
            )
        reminderEnabled?.let { entries.add("booking.reminders.enabled" to it) }
        return MapApplicationConfig(*entries.toTypedArray())
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

    private fun seedDueReminder(
        jdbcUrl: String,
        policyVersion: String,
    ): ReminderFixture =
        DriverManager.getConnection(jdbcUrl, "sa", "").use { connection ->
            val venueId =
                connection.prepareStatement(
                    """
                    INSERT INTO venues (name, city, address, status)
                    VALUES ('Reminder Venue', 'City', 'Address', 'PUBLISHED')
                    """.trimIndent(),
                    Statement.RETURN_GENERATED_KEYS,
                ).use { statement ->
                    statement.executeUpdate()
                    statement.generatedKeys.use { keys ->
                        keys.next()
                        keys.getLong(1)
                    }
                }
            connection.prepareStatement(
                """
                INSERT INTO venue_settings (venue_id, timezone)
                VALUES (?, 'Europe/Moscow')
                """.trimIndent(),
            ).use { statement ->
                statement.setLong(1, venueId)
                statement.executeUpdate()
            }
            val userId =
                when (policyVersion) {
                    "M7C" -> 424243L
                    else -> 424242L
                }
            connection.prepareStatement(
                """
                INSERT INTO users (telegram_user_id, username, first_name, updated_at)
                VALUES (?, 'guest', 'Guest', CURRENT_TIMESTAMP)
                """.trimIndent(),
            ).use { statement ->
                statement.setLong(1, userId)
                statement.executeUpdate()
            }
            val scheduledAt = Instant.parse("2030-05-10T18:00:00Z")
            val bookingId =
                connection.prepareStatement(
                    """
                    INSERT INTO bookings (
                        venue_id,
                        user_id,
                        scheduled_at,
                        party_size,
                        comment,
                        status,
                        display_date,
                        display_number,
                        arrival_deadline_at
                    )
                    VALUES (?, ?, ?, 2, NULL, 'CONFIRMED', ?, 1, ?)
                    """.trimIndent(),
                    Statement.RETURN_GENERATED_KEYS,
                ).use { statement ->
                    statement.setLong(1, venueId)
                    statement.setLong(2, userId)
                    statement.setTimestamp(3, Timestamp.from(scheduledAt))
                    statement.setObject(4, LocalDate.of(2030, 5, 10))
                    statement.setTimestamp(5, Timestamp.from(scheduledAt.plus(Duration.ofMinutes(30))))
                    statement.executeUpdate()
                    statement.generatedKeys.use { keys ->
                        keys.next()
                        keys.getLong(1)
                    }
                }
            val reminderId =
                connection.prepareStatement(
                    """
                    INSERT INTO booking_reminders (booking_id, kind, scheduled_for, status, dedupe_key, policy_version)
                    VALUES (?, 'PRE_VISIT', ?, 'PENDING', ?, ?)
                    """.trimIndent(),
                    Statement.RETURN_GENERATED_KEYS,
                ).use { statement ->
                    statement.setLong(1, bookingId)
                    statement.setTimestamp(2, Timestamp.from(Instant.now().minusSeconds(60)))
                    statement.setString(3, "test:${UUID.randomUUID()}")
                    statement.setString(4, policyVersion)
                    statement.executeUpdate()
                    statement.generatedKeys.use { keys ->
                        keys.next()
                        keys.getLong(1)
                    }
                }
            ReminderFixture(reminderId = reminderId)
        }

    private fun reminderStatus(
        jdbcUrl: String,
        reminderId: Long,
    ): String =
        DriverManager.getConnection(jdbcUrl, "sa", "").use { connection ->
            connection.prepareStatement("SELECT status FROM booking_reminders WHERE id = ?").use { statement ->
                statement.setLong(1, reminderId)
                statement.executeQuery().use { rs ->
                    rs.next()
                    rs.getString("status")
                }
            }
        }

    private fun outboxCount(jdbcUrl: String): Int =
        DriverManager.getConnection(jdbcUrl, "sa", "").use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery("SELECT COUNT(*) FROM telegram_outbox").use { rs ->
                    rs.next()
                    rs.getInt(1)
                }
            }
        }

    private suspend fun waitUntil(
        timeout: Duration = Duration.ofSeconds(5),
        condition: () -> Boolean,
    ): Boolean {
        val deadline = System.nanoTime() + timeout.toNanos()
        while (System.nanoTime() < deadline) {
            if (condition()) return true
            delay(100)
        }
        return condition()
    }

    private data class ReminderFixture(
        val reminderId: Long,
    )
}
