package com.hookah.platform.backend.billing.subscription

import com.hookah.platform.backend.billing.BillingInvoice
import com.hookah.platform.backend.billing.BillingInvoiceRepository
import com.hookah.platform.backend.billing.BillingNotificationKind
import com.hookah.platform.backend.billing.BillingNotificationRepository
import com.hookah.platform.backend.billing.BillingService
import com.hookah.platform.backend.miniapp.subscription.SubscriptionStatus
import com.hookah.platform.backend.miniapp.subscription.db.SubscriptionRepository
import com.hookah.platform.backend.miniapp.venue.AuditLogRepository
import com.hookah.platform.backend.platform.PlatformSubscriptionSettingsRepository
import com.hookah.platform.backend.telegram.sanitizeTelegramForLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.slf4j.LoggerFactory
import java.sql.SQLException
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import javax.sql.DataSource

class SubscriptionBillingEngine(
    private val dataSource: DataSource?,
    private val venueRepository: SubscriptionBillingVenueRepository,
    private val settingsRepository: PlatformSubscriptionSettingsRepository,
    private val billingService: BillingService,
    private val invoiceRepository: BillingInvoiceRepository,
    private val notificationRepository: BillingNotificationRepository,
    private val subscriptionRepository: SubscriptionRepository,
    private val auditLogRepository: AuditLogRepository,
    private val config: SubscriptionBillingConfig,
    private val platformOwnerUserId: Long?,
    private val json: Json,
    private val clock: Clock = Clock.systemUTC(),
) {
    private val logger = LoggerFactory.getLogger(SubscriptionBillingEngine::class.java)

    suspend fun tick() {
        val ds = dataSource ?: return
        val now = Instant.now(clock)
        withAdvisoryLock(ds) {
            ensureUpcomingInvoices(now)
            sendInvoiceReminders(now)
            freezeOverdue(now)
        }
    }

    private suspend fun ensureUpcomingInvoices(now: Instant) {
        val zone = config.timeZone
        val today = LocalDate.now(clock.withZone(zone))
        val venueIds = venueRepository.listVenueIds()
        for (venueId in venueIds) {
            subscriptionRepository.ensureRowExistsForVenue(venueId)
            val snapshot = settingsRepository.getSubscriptionSnapshot(venueId) ?: continue
            val anchor = resolvePaidAnchor(snapshot.settings.trialEndDate, snapshot.settings.paidStartDate)
            if (anchor == null) {
                logger.debug("Subscription billing skipped: venueId={} not configured", venueId)
                continue
            }
            val periodStart = resolvePeriodStart(anchor, today)
            val leadDate = periodStart.minusDays(config.leadDays)
            if (today.isBefore(leadDate)) {
                continue
            }
            val periodEnd = periodStart.plusMonths(1).minusDays(1)
            val effectivePrice =
                settingsRepository.resolveEffectivePrice(
                    settings = snapshot.settings,
                    schedule = snapshot.schedule,
                    today = periodStart,
                ) ?: run {
                    logger.debug("Subscription billing skipped: venueId={} no price on {}", venueId, periodStart)
                    continue
                }
            val dueAt = periodStart.atStartOfDay(zone).toInstant()
            val description = buildInvoiceDescription(periodStart, periodEnd, zone)
            billingService.createDraftOrOpenInvoice(
                venueId = venueId,
                amountMinor = effectivePrice.priceMinor,
                currency = effectivePrice.currency,
                description = description,
                periodStart = periodStart,
                periodEnd = periodEnd,
                dueAt = dueAt,
            )
        }
    }

    private suspend fun sendInvoiceReminders(now: Instant) {
        val reminderEnd = now.plus(config.reminderDays, ChronoUnit.DAYS)
        val invoices = invoiceRepository.listOpenInvoicesDueBetween(now, reminderEnd)
        for (invoice in invoices) {
            val payload = buildReminderPayload(invoice, config.timeZone)
            val payloadJson = json.encodeToString(JsonObject.serializer(), payload)
            val inserted =
                notificationRepository.insertNotificationIdempotent(
                    invoiceId = invoice.id,
                    kind = BillingNotificationKind.UPCOMING_DUE,
                    sentAt = now,
                    payloadJson = payloadJson,
                )
            if (!inserted) {
                continue
            }
            if (platformOwnerUserId == null) {
                logger.info(
                    "Subscription reminder stored without audit log: invoiceId={} venueId={}",
                    invoice.id,
                    invoice.venueId,
                )
                continue
            }
            auditLogRepository.appendJson(
                actorUserId = platformOwnerUserId,
                action = "billing_invoice_reminder",
                entityType = "billing_invoice",
                entityId = invoice.id,
                payload = payload,
            )
        }
    }

    private suspend fun freezeOverdue(now: Instant) {
        val overdue = invoiceRepository.listOpenInvoicesDueBefore(now)
        for (invoice in overdue) {
            val marked = invoiceRepository.markInvoicePastDue(invoice.id)
            if (!marked) {
                continue
            }
            subscriptionRepository.updateStatus(
                venueId = invoice.venueId,
                status = SubscriptionStatus.SUSPENDED_BY_PLATFORM,
            )
        }
    }

    private fun resolvePaidAnchor(
        trialEndDate: LocalDate?,
        paidStartDate: LocalDate?,
    ): LocalDate? {
        // Если paidStartDate не задан, используем дату окончания trial как старт первого платного периода.
        return paidStartDate ?: trialEndDate
    }

    private fun resolvePeriodStart(
        anchor: LocalDate,
        today: LocalDate,
    ): LocalDate {
        var start = anchor
        while (!start.plusMonths(1).isAfter(today)) {
            start = start.plusMonths(1)
        }
        return start
    }

    private fun buildInvoiceDescription(
        periodStart: LocalDate,
        periodEnd: LocalDate,
        zone: ZoneId,
    ): String {
        return "Подписка ($periodStart - $periodEnd, ${zone.id})"
    }

    private fun buildReminderPayload(
        invoice: BillingInvoice,
        zone: ZoneId,
    ): JsonObject {
        return buildJsonObject {
            put("kind", BillingNotificationKind.UPCOMING_DUE.dbValue)
            put("invoiceId", invoice.id)
            put("venueId", invoice.venueId)
            put("periodStart", invoice.periodStart.toString())
            put("periodEnd", invoice.periodEnd.toString())
            put("dueAt", invoice.dueAt.toString())
            put("amountMinor", invoice.amountMinor)
            put("currency", invoice.currency)
            put("paymentUrl", invoice.paymentUrl)
            put("timeZone", zone.id)
        }
    }

    private suspend fun <T> withAdvisoryLock(
        dataSource: DataSource,
        block: suspend () -> T,
    ): T? {
        return withContext(Dispatchers.IO) {
            try {
                dataSource.connection.use { connection ->
                    val product = connection.metaData.databaseProductName
                    if (!product.equals("PostgreSQL", ignoreCase = true)) {
                        return@withContext block()
                    }
                    val acquired =
                        connection.prepareStatement(
                            "SELECT pg_try_advisory_lock(?)",
                        ).use { statement ->
                            statement.setLong(1, ADVISORY_LOCK_KEY)
                            statement.executeQuery().use { rs ->
                                rs.next()
                                rs.getBoolean(1)
                            }
                        }
                    if (!acquired) {
                        logger.debug("Subscription billing skipped: advisory lock not acquired")
                        return@withContext null
                    }
                    try {
                        block()
                    } finally {
                        runCatching {
                            connection.prepareStatement("SELECT pg_advisory_unlock(?)").use { statement ->
                                statement.setLong(1, ADVISORY_LOCK_KEY)
                                statement.executeQuery()
                            }
                        }.onFailure { error ->
                            logger.warn(
                                "Failed to release subscription billing advisory lock: {}",
                                sanitizeTelegramForLog(error.message),
                            )
                        }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: SQLException) {
                logger.warn("Subscription billing advisory lock failed: {}", sanitizeTelegramForLog(e.message))
                block()
            }
        }
    }

    companion object {
        private const val ADVISORY_LOCK_KEY = 918_221_045L
    }
}
