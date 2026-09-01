package com.hookah.platform.backend.maintenance

import com.hookah.platform.backend.ModuleOverrides
import com.hookah.platform.backend.billing.BillingInvoiceRepository
import com.hookah.platform.backend.billing.InvoiceStatus
import com.hookah.platform.backend.billing.subscription.SubscriptionBillingJob
import com.hookah.platform.backend.miniapp.guest.BookingExpiryWorker
import com.hookah.platform.backend.miniapp.guest.BookingReminderWorker
import com.hookah.platform.backend.miniapp.guest.TableSessionCleanupWorker
import com.hookah.platform.backend.miniapp.guest.db.BookingStatus
import com.hookah.platform.backend.miniapp.guest.db.GuestBookingRepository
import com.hookah.platform.backend.miniapp.subscription.SubscriptionStatus
import com.hookah.platform.backend.miniapp.subscription.db.SubscriptionRepository
import com.hookah.platform.backend.module
import com.hookah.platform.backend.moduleWithOverrides
import io.ktor.server.config.MapApplicationConfig
import io.ktor.server.testing.testApplication
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkConstructor
import io.mockk.unmockkConstructor
import io.mockk.verify
import kotlinx.coroutines.Job
import org.h2.jdbcx.JdbcDataSource
import java.sql.DriverManager
import java.sql.Statement
import java.sql.Timestamp
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class StagingMaintenanceStartupTest {
    @Test
    fun `staging active mode fails closed before database initialization for every invalid identity contract`() {
        val invalidMaintenanceConfigs =
            listOf(
                mapOf("staging.maintenance.mode" to "unknown"),
                mapOf(
                    "staging.maintenance.mode" to "V126_SMOKE",
                    "staging.maintenance.allowedUserIds" to "",
                    "staging.maintenance.allowedChatIds" to SENSITIVE_USER_ID,
                ),
                mapOf(
                    "staging.maintenance.mode" to "V126_SMOKE",
                    "staging.maintenance.allowedUserIds" to SENSITIVE_USER_ID,
                    "staging.maintenance.allowedChatIds" to "-$SENSITIVE_USER_ID",
                ),
                mapOf(
                    "staging.maintenance.mode" to "V126_SMOKE",
                    "staging.maintenance.allowedUserIds" to "$SENSITIVE_USER_ID,$SENSITIVE_USER_ID",
                    "staging.maintenance.allowedChatIds" to SENSITIVE_USER_ID,
                ),
            )

        invalidMaintenanceConfigs.forEach { maintenanceConfig ->
            val error =
                assertFailsWith<IllegalStateException> {
                    testApplication {
                        environment {
                            config =
                                baseStagingConfig(
                                    "db.jdbcUrl" to SENSITIVE_UNREACHABLE_JDBC_URL,
                                    *maintenanceConfig.map { (key, value) -> key to value }.toTypedArray(),
                                )
                        }
                        application { module() }
                        startApplication()
                    }
                }

            assertContains(error.message.orEmpty(), "staging.maintenance")
            assertFalse(error.message.orEmpty().contains(SENSITIVE_USER_ID))
            assertFalse(error.message.orEmpty().contains(SENSITIVE_UNREACHABLE_JDBC_URL))
        }
    }

    @Test
    fun `active mode requires underlying product policy`() {
        val error =
            assertFailsWith<IllegalStateException> {
                testApplication {
                    environment {
                        config =
                            baseStagingConfig(
                                "telegram.trafficPolicy" to "ALLOWLIST",
                                "telegram.allowedUserIds" to SENSITIVE_USER_ID,
                                "telegram.allowedChatIds" to SENSITIVE_USER_ID,
                                *activeMaintenanceConfig(),
                            )
                    }
                    application { module() }
                    startApplication()
                }
            }

        assertContains(error.message.orEmpty(), "V126_SMOKE requires TELEGRAM_TRAFFIC_POLICY=PRODUCT")
        assertFalse(error.message.orEmpty().contains(SENSITIVE_USER_ID))
    }

    @Test
    fun `active mode starts without command configuration while off ignores maintenance lists`() {
        var activeCommandConfigurationStarted = false
        testApplication {
            environment {
                config =
                    baseStagingConfig(
                        "telegram.enabled" to "true",
                        "telegram.token" to "test-bot-token",
                        "telegram.mode" to "webhook",
                        "telegram.webhookPath" to "/telegram/webhook",
                        "telegram.webhookSecretToken" to "test-webhook-secret",
                        "telegram.staffChatLinkSecretPepper" to "test-link-pepper",
                        *activeMaintenanceConfig(),
                    )
            }
            application {
                moduleWithOverrides(
                    ModuleOverrides(
                        telegramCommandMenuConfigurator = { activeCommandConfigurationStarted = true },
                    ),
                )
            }
            startApplication()
        }
        assertFalse(activeCommandConfigurationStarted)

        var offStarted = false
        testApplication {
            environment {
                config =
                    baseStagingConfig(
                        "staging.maintenance.mode" to "OFF",
                        "staging.maintenance.allowedUserIds" to "malformed ignored value",
                        "staging.maintenance.allowedChatIds" to "also ignored",
                    )
            }
            application {
                module()
                offStarted = true
            }
            startApplication()
        }
        assertTrue(offStarted)
    }

    @Test
    fun `active mode does not start autonomous writers or mutate due work`() {
        val jdbcUrl = writerJdbcUrl()
        lateinit var fixture: DueWriterFixture
        val inertJob = mockk<Job>(relaxed = true)

        mockkConstructor(
            SubscriptionBillingJob::class,
            TableSessionCleanupWorker::class,
            BookingExpiryWorker::class,
            BookingReminderWorker::class,
        )
        try {
            every { anyConstructed<SubscriptionBillingJob>().start(any()) } just Runs
            every { anyConstructed<TableSessionCleanupWorker>().start() } returns inertJob
            every { anyConstructed<BookingExpiryWorker>().start() } returns inertJob
            every { anyConstructed<BookingReminderWorker>().start() } returns inertJob

            testApplication {
                environment { config = autonomousWriterConfig(jdbcUrl, active = true) }
                application { module() }
                startApplication()

                fixture = seedDueWriterFixture(jdbcUrl)
                val before = loadDueWriterState(jdbcUrl, fixture)
                assertEquals(DueWriterState.unprocessed, before)

                verify(exactly = 0) { anyConstructed<SubscriptionBillingJob>().start(any()) }
                verify(exactly = 0) { anyConstructed<TableSessionCleanupWorker>().start() }
                verify(exactly = 0) { anyConstructed<BookingExpiryWorker>().start() }
                verify(exactly = 0) { anyConstructed<BookingReminderWorker>().start() }
                assertEquals(before, loadDueWriterState(jdbcUrl, fixture))
            }

            testApplication {
                environment { config = autonomousWriterConfig(jdbcUrl, active = false) }
                application { module() }
                startApplication()

                verify(exactly = 1) { anyConstructed<SubscriptionBillingJob>().start(any()) }
                verify(exactly = 1) { anyConstructed<TableSessionCleanupWorker>().start() }
                verify(exactly = 1) { anyConstructed<BookingExpiryWorker>().start() }
                verify(exactly = 1) { anyConstructed<BookingReminderWorker>().start() }
                assertEquals(DueWriterState.unprocessed, loadDueWriterState(jdbcUrl, fixture))
            }
        } finally {
            unmockkConstructor(
                SubscriptionBillingJob::class,
                TableSessionCleanupWorker::class,
                BookingExpiryWorker::class,
                BookingReminderWorker::class,
            )
        }
    }

    private fun baseStagingConfig(vararg entries: Pair<String, String>): MapApplicationConfig =
        MapApplicationConfig(
            "app.env" to "staging",
            "api.session.jwtSecret" to "startup-test-secret",
            "telegram.trafficPolicy" to "PRODUCT",
            "venue.staffInviteSecretPepper" to "startup-invite-secret",
            *entries,
        )

    private fun activeMaintenanceConfig(): Array<Pair<String, String>> =
        arrayOf(
            "staging.maintenance.mode" to "V126_SMOKE",
            "staging.maintenance.allowedUserIds" to SENSITIVE_USER_ID,
            "staging.maintenance.allowedChatIds" to "$SENSITIVE_USER_ID,$SENSITIVE_GROUP_ID",
        )

    private fun autonomousWriterConfig(
        jdbcUrl: String,
        active: Boolean,
    ): MapApplicationConfig =
        baseStagingConfig(
            "db.jdbcUrl" to jdbcUrl,
            "db.user" to "sa",
            "db.password" to "",
            "telegram.trafficPolicy" to "PRODUCT",
            "telegram.allowedUserIds" to "",
            "telegram.allowedChatIds" to "",
            "billing.subscription.intervalSeconds" to "1",
            "billing.subscription.graceDays" to "36500",
            "guest.tableSession.cleanupIntervalSeconds" to "1",
            "booking.expiry.enabled" to "true",
            "booking.expiry.intervalSeconds" to "1",
            "booking.reminders.enabled" to "true",
            "booking.reminders.intervalSeconds" to "1",
            "staging.maintenance.mode" to if (active) "V126_SMOKE" else "OFF",
            "staging.maintenance.allowedUserIds" to SENSITIVE_USER_ID,
            "staging.maintenance.allowedChatIds" to SENSITIVE_USER_ID,
        )

    private fun writerJdbcUrl(): String =
        "jdbc:h2:mem:maintenance-autonomous-writers-${UUID.randomUUID()};MODE=PostgreSQL;" +
            "DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1"

    private suspend fun seedDueWriterFixture(jdbcUrl: String): DueWriterFixture {
        val now = Instant.now()
        val venueId: Long
        val tableSessionId: Long
        DriverManager.getConnection(jdbcUrl, "sa", "").use { connection ->
            venueId =
                connection.prepareStatement(
                    """
                    INSERT INTO venues (name, city, address, status)
                    VALUES ('Maintenance writer fixture', 'City', 'Address', 'PUBLISHED')
                    """.trimIndent(),
                    Statement.RETURN_GENERATED_KEYS,
                ).use { statement ->
                    statement.executeUpdate()
                    statement.generatedKeys.use { keys ->
                        check(keys.next())
                        keys.getLong(1)
                    }
                }
            connection.prepareStatement(
                """
                INSERT INTO users (telegram_user_id, username, first_name, updated_at)
                VALUES (?, 'maintenance_writer', 'Maintenance', CURRENT_TIMESTAMP)
                """.trimIndent(),
            ).use { statement ->
                statement.setLong(1, SENSITIVE_USER_ID.toLong())
                statement.executeUpdate()
            }
            val tableId =
                connection.prepareStatement(
                    "INSERT INTO venue_tables (venue_id, table_number) VALUES (?, 1)",
                    Statement.RETURN_GENERATED_KEYS,
                ).use { statement ->
                    statement.setLong(1, venueId)
                    statement.executeUpdate()
                    statement.generatedKeys.use { keys ->
                        check(keys.next())
                        keys.getLong(1)
                    }
                }
            tableSessionId =
                connection.prepareStatement(
                    """
                    INSERT INTO table_sessions (
                        venue_id, table_id, started_at, last_activity_at, expires_at, status
                    ) VALUES (?, ?, ?, ?, ?, 'ACTIVE')
                    """.trimIndent(),
                    Statement.RETURN_GENERATED_KEYS,
                ).use { statement ->
                    statement.setLong(1, venueId)
                    statement.setLong(2, tableId)
                    statement.setTimestamp(3, Timestamp.from(now.minus(Duration.ofHours(3))))
                    statement.setTimestamp(4, Timestamp.from(now.minus(Duration.ofHours(2))))
                    statement.setTimestamp(5, Timestamp.from(now.minus(Duration.ofHours(1))))
                    statement.executeUpdate()
                    statement.generatedKeys.use { keys ->
                        check(keys.next())
                        keys.getLong(1)
                    }
                }
        }

        val dataSource = writerDataSource(jdbcUrl)
        val bookingRepository = GuestBookingRepository(dataSource)
        val overdueBooking =
            bookingRepository.create(
                venueId = venueId,
                userId = SENSITIVE_USER_ID.toLong(),
                scheduledAt = now.minus(Duration.ofHours(2)),
                partySize = 2,
                comment = null,
            )
        val reminderBooking =
            bookingRepository.create(
                venueId = venueId,
                userId = SENSITIVE_USER_ID.toLong(),
                scheduledAt = now.plus(Duration.ofHours(2)),
                partySize = 2,
                comment = null,
            )
        check(bookingRepository.updateByVenue(reminderBooking.id, venueId, BookingStatus.CONFIRMED) != null)
        val reminderId =
            DriverManager.getConnection(jdbcUrl, "sa", "").use { connection ->
                connection.prepareStatement(
                    """
                    INSERT INTO booking_reminders (
                        booking_id, kind, scheduled_for, status, attempts, dedupe_key, policy_version
                    ) VALUES (?, 'PRE_VISIT', ?, 'PENDING', 0, ?, 'M7C')
                    """.trimIndent(),
                    Statement.RETURN_GENERATED_KEYS,
                ).use { statement ->
                    statement.setLong(1, reminderBooking.id)
                    statement.setTimestamp(2, Timestamp.from(now.minus(Duration.ofMinutes(1))))
                    statement.setString(3, "maintenance-writer-reminder-${UUID.randomUUID()}")
                    statement.executeUpdate()
                    statement.generatedKeys.use { keys ->
                        check(keys.next())
                        keys.getLong(1)
                    }
                }
            }

        check(SubscriptionRepository(dataSource).updateStatus(venueId, SubscriptionStatus.ACTIVE))
        val periodStart = LocalDate.now().minusMonths(1).withDayOfMonth(1)
        val invoice =
            BillingInvoiceRepository(dataSource).createInvoice(
                venueId = venueId,
                periodStart = periodStart,
                periodEnd = periodStart.plusMonths(1).minusDays(1),
                dueAt = now.minus(Duration.ofDays(1)),
                amountMinor = 5_000,
                currency = "RUB",
                description = "Maintenance writer fixture",
                provider = "FAKE",
                providerInvoiceId = "maintenance-writer-invoice",
                paymentUrl = null,
                providerRawPayload = null,
                status = InvoiceStatus.OPEN,
                paidAt = null,
                actorUserId = null,
            )
        return DueWriterFixture(
            venueId = venueId,
            tableSessionId = tableSessionId,
            overdueBookingId = overdueBooking.id,
            reminderId = reminderId,
            invoiceId = invoice.id,
        )
    }

    private fun writerDataSource(jdbcUrl: String): JdbcDataSource =
        JdbcDataSource().apply {
            setURL(jdbcUrl)
            user = "sa"
            password = ""
        }

    private fun loadDueWriterState(
        jdbcUrl: String,
        fixture: DueWriterFixture,
    ): DueWriterState =
        DriverManager.getConnection(jdbcUrl, "sa", "").use { connection ->
            DueWriterState(
                invoiceStatus =
                    connection.singleString(
                        "SELECT status FROM billing_invoices WHERE id = ?",
                        fixture.invoiceId,
                    ),
                subscriptionStatus =
                    connection.singleString(
                        "SELECT status FROM venue_subscriptions WHERE venue_id = ?",
                        fixture.venueId,
                    ),
                tableSessionStatus =
                    connection.singleString(
                        "SELECT status FROM table_sessions WHERE id = ?",
                        fixture.tableSessionId,
                    ),
                overdueBookingStatus =
                    connection.singleString(
                        "SELECT status FROM bookings WHERE id = ?",
                        fixture.overdueBookingId,
                    ),
                reminderStatus =
                    connection.singleString(
                        "SELECT status FROM booking_reminders WHERE id = ?",
                        fixture.reminderId,
                    ),
                reminderAttempts =
                    connection.singleInt(
                        "SELECT attempts FROM booking_reminders WHERE id = ?",
                        fixture.reminderId,
                    ),
                reminderOutboxCount =
                    connection.singleInt(
                        "SELECT COUNT(*) FROM telegram_outbox WHERE dedupe_key = ?",
                        "booking-reminder:${fixture.reminderId}",
                    ),
            )
        }

    private fun java.sql.Connection.singleString(
        sql: String,
        id: Long,
    ): String =
        prepareStatement(sql).use { statement ->
            statement.setLong(1, id)
            statement.executeQuery().use { rows ->
                check(rows.next())
                rows.getString(1)
            }
        }

    private fun java.sql.Connection.singleInt(
        sql: String,
        parameter: Any,
    ): Int =
        prepareStatement(sql).use { statement ->
            statement.setObject(1, parameter)
            statement.executeQuery().use { rows ->
                check(rows.next())
                rows.getInt(1)
            }
        }

    private data class DueWriterFixture(
        val venueId: Long,
        val tableSessionId: Long,
        val overdueBookingId: Long,
        val reminderId: Long,
        val invoiceId: Long,
    )

    private data class DueWriterState(
        val invoiceStatus: String,
        val subscriptionStatus: String,
        val tableSessionStatus: String,
        val overdueBookingStatus: String,
        val reminderStatus: String,
        val reminderAttempts: Int,
        val reminderOutboxCount: Int,
    ) {
        companion object {
            val unprocessed = DueWriterState("OPEN", "ACTIVE", "ACTIVE", "PENDING", "PENDING", 0, 0)
        }
    }

    private companion object {
        const val SENSITIVE_USER_ID = "711111111111111111"
        const val SENSITIVE_GROUP_ID = "-1002222222222"
        const val SENSITIVE_UNREACHABLE_JDBC_URL = "jdbc:must-not-be-opened-maintenance"
    }
}
