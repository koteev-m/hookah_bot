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
import com.hookah.platform.backend.test.PostgresTestEnv
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.sql.Connection
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset

class SubscriptionBillingPostgresTest {
    private fun insertVenue(
        connection: Connection,
        name: String,
    ): Long {
        return connection.prepareStatement(
            """
            INSERT INTO venues (name, city, address, status)
            VALUES (?, ?, ?, ?)
            """.trimIndent(),
            java.sql.Statement.RETURN_GENERATED_KEYS,
        ).use { statement ->
            statement.setString(1, name)
            statement.setString(2, "Moscow")
            statement.setString(3, "Tverskaya 1")
            statement.setString(4, VenueStatus.PUBLISHED.dbValue)
            statement.executeUpdate()
            statement.generatedKeys.use { rs ->
                rs.next()
                rs.getLong(1)
            }
        }
    }

    @Test
    fun `past due invoice suspends subscription and paid invoice restores active`() =
        runBlocking {
            val database = PostgresTestEnv.createDatabase()
            val dataSource = PostgresTestEnv.createDataSource(database)
            try {
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
                                intervalSeconds = 60,
                            ),
                        platformOwnerUserId = null,
                        json = Json,
                        clock = Clock.fixed(Instant.parse("2024-05-10T12:00:00Z"), ZoneOffset.UTC),
                    )

                engine.tick()

                val suspended = subscriptionRepository.getSubscriptionStatus(venueId)
                assertEquals(SubscriptionStatus.SUSPENDED_BY_PLATFORM, suspended)

                dataSource.connection.use { connection ->
                    connection.prepareStatement(
                        """
                        SELECT status
                        FROM billing_invoices
                        WHERE id = ?
                        """.trimIndent(),
                    ).use { statement ->
                        statement.setLong(1, invoice.id)
                        statement.executeQuery().use { rs ->
                            rs.next()
                            assertEquals(InvoiceStatus.PAST_DUE.dbValue, rs.getString("status"))
                        }
                    }
                }

                val service =
                    BillingService(
                        provider = FakeBillingProvider(),
                        invoiceRepository = invoiceRepository,
                        paymentRepository = paymentRepository,
                        hooks = SubscriptionBillingHooks(subscriptionRepository),
                    )
                service.applyPaymentEvent(
                    PaymentEvent.Paid(
                        provider = "FAKE",
                        providerEventId = "event-paid",
                        providerInvoiceId = invoice.providerInvoiceId,
                        amountMinor = invoice.amountMinor,
                        currency = invoice.currency,
                        occurredAt = Instant.parse("2024-05-10T12:05:00Z"),
                        rawPayload = null,
                    ),
                )

                val active = subscriptionRepository.getSubscriptionStatus(venueId)
                assertEquals(SubscriptionStatus.ACTIVE, active)
            } finally {
                dataSource.close()
            }
        }
}
