package com.hookah.platform.backend.billing

import com.hookah.platform.backend.billing.subscription.SubscriptionBillingConfig
import com.hookah.platform.backend.billing.subscription.SubscriptionBillingEngine
import com.hookah.platform.backend.billing.subscription.SubscriptionBillingHooks
import com.hookah.platform.backend.billing.subscription.SubscriptionBillingVenueRepository
import com.hookah.platform.backend.miniapp.subscription.SubscriptionStatus
import com.hookah.platform.backend.miniapp.subscription.db.SubscriptionRepository
import com.hookah.platform.backend.miniapp.venue.AuditLogRepository
import com.hookah.platform.backend.miniapp.venue.VenueStatus
import com.hookah.platform.backend.platform.PlatformSubscriptionSettingsRepository
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import java.sql.Connection
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.UUID

class SubscriptionBillingEngineTest {
    companion object {
        private lateinit var dataSource: HikariDataSource

        @BeforeAll
        @JvmStatic
        fun setup() {
            val dbName = "subscription_engine_${UUID.randomUUID()}"
            dataSource =
                HikariDataSource(
                    HikariConfig().apply {
                        driverClassName = "org.h2.Driver"
                        jdbcUrl =
                            "jdbc:h2:mem:$dbName;MODE=PostgreSQL;" +
                            "DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1"
                        maximumPoolSize = 3
                    },
                )
            Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration/h2")
                .load()
                .migrate()
        }

        @AfterAll
        @JvmStatic
        fun tearDown() {
            dataSource.close()
        }

        private fun insertVenue(
            connection: Connection,
            name: String,
        ): Long {
            return connection.prepareStatement(
                """
                INSERT INTO venues (name, status)
                VALUES (?, ?)
                """.trimIndent(),
                java.sql.Statement.RETURN_GENERATED_KEYS,
            ).use { statement ->
                statement.setString(1, name)
                statement.setString(2, VenueStatus.PUBLISHED.dbValue)
                statement.executeUpdate()
                statement.generatedKeys.use { rs ->
                    rs.next()
                    rs.getLong(1)
                }
            }
        }
    }

    @Test
    fun `overdue invoice becomes past due and after grace suspends subscription`() =
        runBlocking {
            val venueId =
                dataSource.connection.use { connection ->
                    insertVenue(connection, "Subscription Venue")
                }
            val subscriptionRepository = SubscriptionRepository(dataSource)
            subscriptionRepository.updateStatus(venueId, SubscriptionStatus.ACTIVE)
            val invoiceRepository = BillingInvoiceRepository(dataSource)
            val paymentRepository = BillingPaymentRepository(dataSource)
            val invoice =
                invoiceRepository.createInvoice(
                    venueId = venueId,
                    periodStart = LocalDate.of(2024, 5, 1),
                    periodEnd = LocalDate.of(2024, 5, 31),
                    dueAt = Instant.parse("2024-05-01T00:00:00Z"),
                    amountMinor = 5000,
                    currency = "RUB",
                    description = "May subscription",
                    provider = "FAKE",
                    providerInvoiceId = "fake-invoice-paid",
                    paymentUrl = null,
                    providerRawPayload = null,
                    status = InvoiceStatus.OPEN,
                    paidAt = null,
                    actorUserId = null,
                )
            val engine =
                SubscriptionBillingEngine(
                    dataSource = dataSource,
                    venueRepository = SubscriptionBillingVenueRepository(dataSource),
                    settingsRepository = PlatformSubscriptionSettingsRepository(dataSource),
                    billingService =
                        BillingService(
                            provider = FakeBillingProvider(),
                            invoiceRepository = invoiceRepository,
                            paymentRepository = paymentRepository,
                            hooks = SubscriptionBillingHooks(subscriptionRepository),
                        ),
                    invoiceRepository = invoiceRepository,
                    notificationRepository = BillingNotificationRepository(dataSource),
                    subscriptionRepository = subscriptionRepository,
                    auditLogRepository = AuditLogRepository(dataSource, Json),
                    config =
                        SubscriptionBillingConfig(
                            timeZone = ZoneId.of("UTC"),
                            leadDays = 7,
                            reminderDays = 3,
                            graceDays = 3,
                            intervalSeconds = 60,
                        ),
                    platformOwnerUserId = null,
                    json = Json,
                    clock = Clock.fixed(Instant.parse("2024-05-02T12:00:00Z"), ZoneOffset.UTC),
                )

            engine.tick()

            val pastDue = subscriptionRepository.getSubscriptionStatus(venueId)
            assertEquals(SubscriptionStatus.PAST_DUE, pastDue)

            val invoicePastDue = invoiceRepository.getInvoiceById(invoice.id)
            assertEquals(InvoiceStatus.PAST_DUE, invoicePastDue?.status)

            val graceExpiredEngine =
                SubscriptionBillingEngine(
                    dataSource = dataSource,
                    venueRepository = SubscriptionBillingVenueRepository(dataSource),
                    settingsRepository = PlatformSubscriptionSettingsRepository(dataSource),
                    billingService =
                        BillingService(
                            provider = FakeBillingProvider(),
                            invoiceRepository = invoiceRepository,
                            paymentRepository = paymentRepository,
                            hooks = SubscriptionBillingHooks(subscriptionRepository),
                        ),
                    invoiceRepository = invoiceRepository,
                    notificationRepository = BillingNotificationRepository(dataSource),
                    subscriptionRepository = subscriptionRepository,
                    auditLogRepository = AuditLogRepository(dataSource, Json),
                    config =
                        SubscriptionBillingConfig(
                            timeZone = ZoneId.of("UTC"),
                            leadDays = 7,
                            reminderDays = 3,
                            graceDays = 3,
                            intervalSeconds = 60,
                        ),
                    platformOwnerUserId = null,
                    json = Json,
                    clock = Clock.fixed(Instant.parse("2024-05-14T12:00:00Z"), ZoneOffset.UTC),
                )

            graceExpiredEngine.tick()

            val suspended = subscriptionRepository.getSubscriptionStatus(venueId)
            assertEquals(SubscriptionStatus.SUSPENDED_BY_PLATFORM, suspended)
        }

    @Test
    fun `overdue invoice does not downgrade canceled subscription`() =
        runBlocking {
            val venueId =
                dataSource.connection.use { connection ->
                    insertVenue(connection, "Canceled Overdue Venue")
                }
            val subscriptionRepository = SubscriptionRepository(dataSource)
            subscriptionRepository.updateStatus(venueId, SubscriptionStatus.CANCELED)
            val invoiceRepository = BillingInvoiceRepository(dataSource)
            val paymentRepository = BillingPaymentRepository(dataSource)
            val invoice =
                invoiceRepository.createInvoice(
                    venueId = venueId,
                    periodStart = LocalDate.of(2024, 5, 1),
                    periodEnd = LocalDate.of(2024, 5, 31),
                    dueAt = Instant.parse("2024-05-01T00:00:00Z"),
                    amountMinor = 5000,
                    currency = "RUB",
                    description = "May subscription",
                    provider = "FAKE",
                    providerInvoiceId = "fake-invoice-canceled-overdue",
                    paymentUrl = null,
                    providerRawPayload = null,
                    status = InvoiceStatus.OPEN,
                    paidAt = null,
                    actorUserId = null,
                )
            val engine =
                SubscriptionBillingEngine(
                    dataSource = dataSource,
                    venueRepository = SubscriptionBillingVenueRepository(dataSource),
                    settingsRepository = PlatformSubscriptionSettingsRepository(dataSource),
                    billingService =
                        BillingService(
                            provider = FakeBillingProvider(),
                            invoiceRepository = invoiceRepository,
                            paymentRepository = paymentRepository,
                            hooks = SubscriptionBillingHooks(subscriptionRepository),
                        ),
                    invoiceRepository = invoiceRepository,
                    notificationRepository = BillingNotificationRepository(dataSource),
                    subscriptionRepository = subscriptionRepository,
                    auditLogRepository = AuditLogRepository(dataSource, Json),
                    config =
                        SubscriptionBillingConfig(
                            timeZone = ZoneId.of("UTC"),
                            leadDays = 7,
                            reminderDays = 3,
                            graceDays = 3,
                            intervalSeconds = 60,
                        ),
                    platformOwnerUserId = null,
                    json = Json,
                    clock = Clock.fixed(Instant.parse("2024-05-02T12:00:00Z"), ZoneOffset.UTC),
                )

            engine.tick()

            val status = subscriptionRepository.getSubscriptionStatus(venueId)
            assertEquals(SubscriptionStatus.CANCELED, status)

            val invoicePastDue = invoiceRepository.getInvoiceById(invoice.id)
            assertEquals(InvoiceStatus.PAST_DUE, invoicePastDue?.status)
        }

    @Test
    fun `overdue invoice does not downgrade suspended by platform subscription`() =
        runBlocking {
            val venueId =
                dataSource.connection.use { connection ->
                    insertVenue(connection, "Suspended Overdue Venue")
                }
            val subscriptionRepository = SubscriptionRepository(dataSource)
            subscriptionRepository.updateStatus(venueId, SubscriptionStatus.SUSPENDED_BY_PLATFORM)
            val invoiceRepository = BillingInvoiceRepository(dataSource)
            val paymentRepository = BillingPaymentRepository(dataSource)
            val invoice =
                invoiceRepository.createInvoice(
                    venueId = venueId,
                    periodStart = LocalDate.of(2024, 5, 1),
                    periodEnd = LocalDate.of(2024, 5, 31),
                    dueAt = Instant.parse("2024-05-01T00:00:00Z"),
                    amountMinor = 5000,
                    currency = "RUB",
                    description = "May subscription",
                    provider = "FAKE",
                    providerInvoiceId = "fake-invoice-suspended-overdue",
                    paymentUrl = null,
                    providerRawPayload = null,
                    status = InvoiceStatus.OPEN,
                    paidAt = null,
                    actorUserId = null,
                )
            val engine =
                SubscriptionBillingEngine(
                    dataSource = dataSource,
                    venueRepository = SubscriptionBillingVenueRepository(dataSource),
                    settingsRepository = PlatformSubscriptionSettingsRepository(dataSource),
                    billingService =
                        BillingService(
                            provider = FakeBillingProvider(),
                            invoiceRepository = invoiceRepository,
                            paymentRepository = paymentRepository,
                            hooks = SubscriptionBillingHooks(subscriptionRepository),
                        ),
                    invoiceRepository = invoiceRepository,
                    notificationRepository = BillingNotificationRepository(dataSource),
                    subscriptionRepository = subscriptionRepository,
                    auditLogRepository = AuditLogRepository(dataSource, Json),
                    config =
                        SubscriptionBillingConfig(
                            timeZone = ZoneId.of("UTC"),
                            leadDays = 7,
                            reminderDays = 3,
                            graceDays = 3,
                            intervalSeconds = 60,
                        ),
                    platformOwnerUserId = null,
                    json = Json,
                    clock = Clock.fixed(Instant.parse("2024-05-02T12:00:00Z"), ZoneOffset.UTC),
                )

            engine.tick()

            val status = subscriptionRepository.getSubscriptionStatus(venueId)
            assertEquals(SubscriptionStatus.SUSPENDED_BY_PLATFORM, status)

            val invoicePastDue = invoiceRepository.getInvoiceById(invoice.id)
            assertEquals(InvoiceStatus.PAST_DUE, invoicePastDue?.status)
        }

    @Test
    fun `subscription can be canceled explicitly`() =
        runBlocking {
            val venueId =
                dataSource.connection.use { connection ->
                    insertVenue(connection, "Canceled Venue")
                }
            val subscriptionRepository = SubscriptionRepository(dataSource)

            val updated = subscriptionRepository.updateStatus(venueId, SubscriptionStatus.CANCELED)

            assertEquals(true, updated)
            assertEquals(SubscriptionStatus.CANCELED, subscriptionRepository.getSubscriptionStatus(venueId))
        }

    @Test
    fun `reminder notifications are idempotent`() =
        runBlocking {
            val venueId =
                dataSource.connection.use { connection ->
                    insertVenue(connection, "Reminder Venue")
                }
            val invoiceRepository = BillingInvoiceRepository(dataSource)
            val invoice =
                invoiceRepository.createInvoice(
                    venueId = venueId,
                    periodStart = LocalDate.of(2024, 6, 1),
                    periodEnd = LocalDate.of(2024, 6, 30),
                    dueAt = Instant.parse("2024-06-03T00:00:00Z"),
                    amountMinor = 3000,
                    currency = "RUB",
                    description = "June subscription",
                    provider = "FAKE",
                    providerInvoiceId = "fake-invoice-reminder",
                    paymentUrl = null,
                    providerRawPayload = null,
                    status = InvoiceStatus.OPEN,
                    paidAt = null,
                    actorUserId = null,
                )
            val engine =
                SubscriptionBillingEngine(
                    dataSource = dataSource,
                    venueRepository = SubscriptionBillingVenueRepository(dataSource),
                    settingsRepository = PlatformSubscriptionSettingsRepository(dataSource),
                    billingService =
                        BillingService(
                            provider = FakeBillingProvider(),
                            invoiceRepository = invoiceRepository,
                            paymentRepository = BillingPaymentRepository(dataSource),
                            hooks = SubscriptionBillingHooks(SubscriptionRepository(dataSource)),
                        ),
                    invoiceRepository = invoiceRepository,
                    notificationRepository = BillingNotificationRepository(dataSource),
                    subscriptionRepository = SubscriptionRepository(dataSource),
                    auditLogRepository = AuditLogRepository(dataSource, Json),
                    config =
                        SubscriptionBillingConfig(
                            timeZone = ZoneId.of("UTC"),
                            leadDays = 7,
                            reminderDays = 3,
                            graceDays = 3,
                            intervalSeconds = 60,
                        ),
                    platformOwnerUserId = null,
                    json = Json,
                    clock = Clock.fixed(Instant.parse("2024-06-01T10:00:00Z"), ZoneOffset.UTC),
                )

            engine.tick()
            engine.tick()

            dataSource.connection.use { connection ->
                connection.prepareStatement(
                    """
                    SELECT COUNT(*)
                    FROM billing_notifications
                    WHERE invoice_id = ?
                      AND kind = ?
                    """.trimIndent(),
                ).use { statement ->
                    statement.setLong(1, invoice.id)
                    statement.setString(2, BillingNotificationKind.UPCOMING_DUE.dbValue)
                    statement.executeQuery().use { rs ->
                        rs.next()
                        assertEquals(1, rs.getInt(1))
                    }
                }
            }
        }
}
