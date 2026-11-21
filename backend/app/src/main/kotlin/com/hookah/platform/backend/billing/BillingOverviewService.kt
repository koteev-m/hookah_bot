package com.hookah.platform.backend.billing

import com.hookah.platform.backend.api.InvalidInputException
import com.hookah.platform.backend.billing.subscription.SubscriptionBillingConfig
import com.hookah.platform.backend.billing.subscription.SubscriptionBillingPeriodResolver
import com.hookah.platform.backend.miniapp.subscription.SubscriptionStatus
import com.hookah.platform.backend.miniapp.subscription.db.SubscriptionRepository
import com.hookah.platform.backend.miniapp.subscription.db.VenueSubscriptionState
import com.hookah.platform.backend.miniapp.venue.AuditLogRepository
import com.hookah.platform.backend.platform.PlatformEffectivePrice
import com.hookah.platform.backend.platform.PlatformSubscriptionSettings
import com.hookah.platform.backend.platform.PlatformSubscriptionSettingsRepository
import com.hookah.platform.backend.platform.PlatformSubscriptionSnapshot
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.net.URI
import java.sql.Connection
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

private const val OUTSTANDING_PAGE_SIZE = 50

enum class BillingCheckoutUnavailableReason(val wire: String) {
    PROVIDER_NOT_CONFIGURED("provider_not_configured"),
    EXTERNAL_CHECKOUT_UNAVAILABLE("external_checkout_unavailable"),
    MISSING_PRICE("missing_price"),
    FREE_PERIOD("free_period"),
    MISSING_BILLING_PERIOD("missing_billing_period"),
    FAKE_PROVIDER_MANUAL_ONLY("fake_provider_manual_only"),
    ALREADY_PAID("already_paid"),
    ADVANCE_WINDOW_NOT_OPEN("advance_window_not_open"),
}

data class BillingOverview(
    val venueId: Long,
    val subscriptionState: VenueSubscriptionState?,
    val settings: PlatformSubscriptionSettings,
    val effectivePrice: PlatformEffectivePrice?,
    val basePaidThrough: LocalDate?,
    val paidThrough: LocalDate?,
    val nextPaymentDate: LocalDate?,
    val nextInvoicePeriodStart: LocalDate?,
    val nextInvoicePeriodEnd: LocalDate?,
    val courtesyDays: Int,
    val latestCourtesyAdjustment: BillingAdjustment?,
    val payableInvoice: BillingInvoice?,
    val invoices: List<BillingInvoice>,
    val outstandingInvoices: OutstandingInvoicePage,
    val checkoutUrl: String?,
    val paymentAvailable: Boolean,
    val platformCheckoutEnsureAvailable: Boolean,
    val checkoutEnsureAvailable: Boolean,
    val unavailableReason: BillingCheckoutUnavailableReason?,
)

@Serializable
data class OwnerBillingInvoiceDto(
    val id: Long,
    val periodStart: String,
    val periodEnd: String,
    val dueAt: String,
    val amountMinor: Int,
    val currency: String,
    val status: String,
    val checkoutUrl: String? = null,
    val paidAt: String? = null,
)

@Serializable
data class OutstandingInvoicePage(
    val items: List<OwnerBillingInvoiceDto>,
    val hasMore: Boolean,
    val nextAfterId: Long? = null,
)

@Serializable
data class OwnerBillingOverviewResponse(
    val venueId: Long,
    val subscriptionStatus: String,
    val trialEndAt: String? = null,
    val paidStartAt: String? = null,
    val lifecycleUpdatedAt: String? = null,
    val settingsTrialEndDate: String? = null,
    val settingsPaidStartDate: String? = null,
    val priceMinor: Int? = null,
    val currency: String? = null,
    val basePaidThrough: String? = null,
    val paidThrough: String? = null,
    val nextPaymentDate: String? = null,
    val nextInvoicePeriodStart: String? = null,
    val nextInvoicePeriodEnd: String? = null,
    val courtesyUntil: String? = null,
    val courtesyDays: Int = 0,
    val lastCourtesyDays: Int? = null,
    val lastCourtesyReason: String? = null,
    val lastCourtesyCreatedAt: String? = null,
    val paymentAvailable: Boolean,
    val platformCheckoutEnsureAvailable: Boolean = false,
    val checkoutEnsureAvailable: Boolean,
    val unavailableReason: String? = null,
    val checkoutUrl: String? = null,
    val payableInvoice: OwnerBillingInvoiceDto? = null,
    val invoices: List<OwnerBillingInvoiceDto>,
    val outstandingInvoices: OutstandingInvoicePage? = null,
)

class BillingOverviewService(
    private val subscriptionRepository: SubscriptionRepository,
    private val settingsRepository: PlatformSubscriptionSettingsRepository,
    private val invoiceRepository: BillingInvoiceRepository,
    private val adjustmentRepository: BillingAdjustmentRepository,
    private val billingService: BillingService,
    private val provider: BillingProvider,
    private val config: SubscriptionBillingConfig,
    private val clock: Clock = Clock.systemUTC(),
) {
    suspend fun readOverview(
        venueId: Long,
        outstandingAfterId: String? = null,
    ): BillingOverview? {
        val afterId =
            outstandingAfterId?.let { raw ->
                raw.toLongOrNull()?.takeIf { it > 0 }
                    ?: throw InvalidInputException("outstandingAfterId must be positive")
            } ?: 0L
        return adjustmentRepository.withConnection { connection ->
            connection.isReadOnly = true
            connection.transactionIsolation = Connection.TRANSACTION_REPEATABLE_READ
            connection.autoCommit = false
            try {
                readOverview(connection, venueId, afterId).also { connection.commit() }
            } catch (failure: Throwable) {
                connection.rollback()
                throw failure
            }
        }
    }

    private fun readOverview(
        connection: Connection,
        venueId: Long,
        afterId: Long = 0L,
    ): BillingOverview? {
        val snapshot = settingsRepository.getSubscriptionSnapshot(connection, venueId) ?: return null
        val basePaidThrough = invoiceRepository.findLatestPaidInvoicePeriodEnd(connection, venueId)
        val latestCourtesyAdjustment = adjustmentRepository.findLatestCourtesyAdjustment(connection, venueId)
        val outstanding =
            invoiceRepository.listOutstandingAfter(connection, venueId, afterId, OUTSTANDING_PAGE_SIZE + 1)
        val hasMore = outstanding.size > OUTSTANDING_PAGE_SIZE
        val items = outstanding.take(OUTSTANDING_PAGE_SIZE).map { it.toOwnerBillingInvoiceDto(this) }
        return buildOverview(
            outstandingInvoices = OutstandingInvoicePage(items, hasMore, if (hasMore) items.last().id else null),
            venueId = venueId,
            snapshot = snapshot,
            subscriptionState = subscriptionRepository.findSubscriptionState(connection, venueId),
            payableInvoice =
                listOfNotNull(
                    invoiceRepository.listInvoicesByVenue(connection, venueId, InvoiceStatus.OPEN, 1, 0).firstOrNull(),
                    invoiceRepository.listInvoicesByVenue(
                        connection,
                        venueId,
                        InvoiceStatus.PAST_DUE,
                        1,
                        0,
                    ).firstOrNull(),
                ).maxWithOrNull(compareBy<BillingInvoice> { it.periodStart }.thenBy { it.id }),
            invoices =
                invoiceRepository.listInvoicesByVenue(
                    connection,
                    venueId,
                    status = null,
                    limit = 50,
                    offset = 0,
                ),
            basePaidThrough = basePaidThrough,
            latestCourtesyAdjustment = latestCourtesyAdjustment,
            courtesyDays = adjustmentRepository.countCourtesyDays(connection, venueId),
        )
    }

    suspend fun ensureCheckout(
        venueId: Long,
        allowAdvance: Boolean,
    ): BillingOverview? {
        val snapshot = settingsRepository.getSubscriptionSnapshot(venueId) ?: return null
        val basePaidThrough = invoiceRepository.findLatestPaidInvoicePeriodEnd(venueId)
        val latestCourtesyAdjustment = adjustmentRepository.findLatestCourtesyAdjustment(venueId)
        val effectivePaidThrough = effectivePaidThrough(basePaidThrough, latestCourtesyAdjustment)
        val next = resolveBillablePeriod(snapshot, effectivePaidThrough, enforceLeadWindow = false)
        if ((next?.price ?: snapshot.effectivePriceToday)?.priceMinor == 0) return readOverview(venueId)
        subscriptionRepository.ensureRowExistsForVenue(venueId)
        val period =
            if (allowAdvance) next else resolveBillablePeriod(snapshot, effectivePaidThrough, enforceLeadWindow = true)
        if (period != null) {
            val existing =
                invoiceRepository.getInvoiceByPeriod(
                    venueId = venueId,
                    periodStart = period.periodStart,
                    periodEnd = period.periodEnd,
                )
            if (existing == null) {
                if (provider.ownerCheckoutAvailable()) {
                    billingService.createDraftOrOpenInvoice(
                        venueId = venueId,
                        amountMinor = period.price.priceMinor,
                        currency = period.price.currency,
                        description =
                            SubscriptionBillingPeriodResolver.buildInvoiceDescription(
                                periodStart = period.periodStart,
                                periodEnd = period.periodEnd,
                                zone = config.timeZone,
                            ),
                        periodStart = period.periodStart,
                        periodEnd = period.periodEnd,
                        dueAt = period.periodStart.atStartOfDay(config.timeZone).toInstant(),
                    )
                } else {
                    createManualInvoice(period)
                }
            } else if (
                provider.ownerCheckoutAvailable() &&
                (existing.status == InvoiceStatus.OPEN || existing.status == InvoiceStatus.PAST_DUE)
            ) {
                ensureProviderCheckoutDetails(existing)
            }
        }

        return readOverview(venueId)
    }

    suspend fun resumeSubscription(
        venueId: Long,
        actor: Long,
        reason: String,
        expectedUpdatedAt: Instant,
        audit: AuditLogRepository,
    ): OwnerBillingOverviewResponse {
        val explanation = reason.trim()
        if (explanation.isEmpty()) throw InvalidInputException("Укажите причину восстановления подписки.")
        return adjustmentRepository.withVenueTransaction(venueId) { connection ->
            val basis =
                subscriptionRepository.resumeSuspended(
                    connection,
                    venueId,
                    expectedUpdatedAt,
                    clock.withZone(config.timeZone),
                )
            audit.appendJson(
                connection = connection,
                actorUserId = actor,
                action = "BILLING_SUBSCRIPTION_RESUMED",
                entityType = "venue",
                entityId = venueId,
                payload =
                    buildJsonObject {
                        put("reason", explanation)
                        put("basis", basis.name.lowercase())
                        put("previousStatus", SubscriptionStatus.SUSPENDED_BY_PLATFORM.wire)
                        put("newStatus", basis.status.wire)
                    },
            )
            checkNotNull(readOverview(connection, venueId)).toResponse(this)
        }
    }

    suspend fun addCourtesyDays(
        venueId: Long,
        days: Int,
        reason: String,
        actorUserId: Long,
        operation: UUID,
        audit: AuditLogRepository,
    ): OwnerBillingOverviewResponse {
        val trimmed = reason.trim()
        return adjustmentRepository.withVenueTransaction(venueId) { connection ->
            adjustmentRepository.replay(connection, operation, venueId, actorUserId, days, reason)?.let {
                return@withVenueTransaction it
            }
            if (days <= 0 || trimmed.isEmpty()) throw InvalidInputException("Positive days and a reason are required")
            val previous =
                effectivePaidThrough(
                    invoiceRepository.findLatestPaidInvoicePeriodEnd(connection, venueId),
                    adjustmentRepository.findLatestCourtesyAdjustment(connection, venueId),
                ) ?: throw InvalidInputException("NO_PAID_PERIOD_TO_EXTEND")
            val until = previous.plusDays(days.toLong())
            val overlapping =
                invoiceRepository.findOpenOrPastDueInvoiceOverlapping(connection, venueId, previous.plusDays(1), until)
            if (overlapping != null) throw InvalidInputException("OPEN_INVOICE_OVERLAPS_COURTESY_PERIOD")
            val grant =
                adjustmentRepository.createCourtesyDaysAdjustment(
                    connection,
                    venueId,
                    days,
                    trimmed,
                    previous,
                    until,
                    actorUserId,
                )
            if (until >= LocalDate.now(clock.withZone(config.timeZone))) {
                subscriptionRepository.activatePastDueForCourtesy(connection, venueId)
            }
            appendBillingCourtesyDaysAudit(connection, audit, actorUserId, grant)
            val response = checkNotNull(readOverview(connection, venueId)).toResponse(this)
            adjustmentRepository.recordResponse(connection, grant.id, operation, reason, response)
            response
        }
    }

    private fun buildOverview(
        venueId: Long,
        snapshot: PlatformSubscriptionSnapshot,
        subscriptionState: VenueSubscriptionState?,
        invoices: List<BillingInvoice>,
        outstandingInvoices: OutstandingInvoicePage,
        payableInvoice: BillingInvoice?,
        basePaidThrough: LocalDate?,
        latestCourtesyAdjustment: BillingAdjustment?,
        courtesyDays: Int,
    ): BillingOverview {
        val paidThrough = effectivePaidThrough(basePaidThrough, latestCourtesyAdjustment)
        val platformPeriod = resolveBillablePeriod(snapshot, paidThrough, enforceLeadWindow = false)
        val venuePeriod = resolveBillablePeriod(snapshot, paidThrough, enforceLeadWindow = true)
        val checkoutUrl = payableInvoice?.let(::safeOwnerCheckoutUrl)
        val unavailableReason =
            when {
                checkoutUrl != null -> null
                payableInvoice != null -> resolveCheckoutUnavailableReason(payableInvoice)
                platformPeriod?.price?.priceMinor == 0 -> BillingCheckoutUnavailableReason.FREE_PERIOD
                platformPeriod == null -> resolveBillablePeriodUnavailableReason(snapshot, paidThrough)
                venuePeriod == null -> BillingCheckoutUnavailableReason.ADVANCE_WINDOW_NOT_OPEN
                !provider.ownerCheckoutAvailable() -> providerUnavailableReason()
                else -> BillingCheckoutUnavailableReason.EXTERNAL_CHECKOUT_UNAVAILABLE
            }
        val nextPaymentDate =
            when {
                paidThrough == null || platformPeriod?.price?.priceMinor == 0 -> null
                else -> platformPeriod?.periodStart ?: paidThrough.plusDays(1)
            }

        return BillingOverview(
            venueId = venueId,
            subscriptionState = subscriptionState,
            settings = snapshot.settings,
            effectivePrice = platformPeriod?.price ?: snapshot.effectivePriceToday,
            basePaidThrough = basePaidThrough,
            paidThrough = paidThrough,
            nextPaymentDate = nextPaymentDate,
            nextInvoicePeriodStart = platformPeriod?.periodStart,
            nextInvoicePeriodEnd = platformPeriod?.periodEnd,
            courtesyDays = courtesyDays,
            latestCourtesyAdjustment = latestCourtesyAdjustment,
            payableInvoice = payableInvoice,
            invoices = invoices,
            outstandingInvoices = outstandingInvoices,
            checkoutUrl = checkoutUrl,
            paymentAvailable = checkoutUrl != null,
            platformCheckoutEnsureAvailable = platformPeriod != null && platformPeriod.price.priceMinor > 0,
            checkoutEnsureAvailable = venuePeriod != null && venuePeriod.price.priceMinor > 0,
            unavailableReason = unavailableReason,
        )
    }

    private fun effectivePaidThrough(
        basePaidThrough: LocalDate?,
        latestCourtesyAdjustment: BillingAdjustment?,
    ): LocalDate? =
        listOfNotNull(basePaidThrough, latestCourtesyAdjustment?.newPaidThrough)
            .maxOrNull()

    private fun resolveBillablePeriod(
        snapshot: PlatformSubscriptionSnapshot,
        paidThrough: LocalDate?,
        enforceLeadWindow: Boolean,
    ): BillablePeriod? {
        val anchor =
            SubscriptionBillingPeriodResolver.resolvePaidAnchor(
                trialEndDate = snapshot.settings.trialEndDate,
                paidStartDate = snapshot.settings.paidStartDate,
            ) ?: return null
        val today = LocalDate.now(clock.withZone(config.timeZone))
        val periodStart =
            SubscriptionBillingPeriodResolver.invoicePeriodStart(anchor, paidThrough, today) { date ->
                settingsRepository.resolveEffectivePrice(snapshot.settings, snapshot.schedule, date)?.priceMinor
            }
        val leadDate = periodStart.minusDays(config.leadDays)
        if (enforceLeadWindow && today.isBefore(leadDate)) {
            return null
        }
        val periodEnd = SubscriptionBillingPeriodResolver.resolvePeriodEnd(periodStart)
        val price =
            settingsRepository.resolveEffectivePrice(
                settings = snapshot.settings,
                schedule = snapshot.schedule,
                today = periodStart,
            ) ?: return null
        return BillablePeriod(
            venueId = snapshot.settings.venueId,
            periodStart = periodStart,
            periodEnd = periodEnd,
            price = price,
        )
    }

    private fun resolveBillablePeriodUnavailableReason(
        snapshot: PlatformSubscriptionSnapshot,
        paidThrough: LocalDate?,
    ): BillingCheckoutUnavailableReason {
        val anchor =
            SubscriptionBillingPeriodResolver.resolvePaidAnchor(
                trialEndDate = snapshot.settings.trialEndDate,
                paidStartDate = snapshot.settings.paidStartDate,
            ) ?: return BillingCheckoutUnavailableReason.MISSING_BILLING_PERIOD
        val today = LocalDate.now(clock.withZone(config.timeZone))
        val periodStart =
            SubscriptionBillingPeriodResolver.invoicePeriodStart(anchor, paidThrough, today) { date ->
                settingsRepository.resolveEffectivePrice(snapshot.settings, snapshot.schedule, date)?.priceMinor
            }
        val price =
            settingsRepository.resolveEffectivePrice(
                settings = snapshot.settings,
                schedule = snapshot.schedule,
                today = periodStart,
            )
        return if (price == null) {
            BillingCheckoutUnavailableReason.MISSING_PRICE
        } else {
            BillingCheckoutUnavailableReason.EXTERNAL_CHECKOUT_UNAVAILABLE
        }
    }

    private suspend fun createManualInvoice(period: BillablePeriod) {
        invoiceRepository.createInvoice(
            venueId = period.venueId,
            amountMinor = period.price.priceMinor,
            currency = period.price.currency,
            description =
                SubscriptionBillingPeriodResolver.buildInvoiceDescription(
                    periodStart = period.periodStart,
                    periodEnd = period.periodEnd,
                    zone = config.timeZone,
                ),
            periodStart = period.periodStart,
            periodEnd = period.periodEnd,
            dueAt = period.periodStart.atStartOfDay(config.timeZone).toInstant(),
            provider = provider.providerName(),
            providerInvoiceId = null,
            paymentUrl = null,
            providerRawPayload = null,
            status = InvoiceStatus.OPEN,
            paidAt = null,
            actorUserId = null,
        )
    }

    private fun resolveCheckoutUnavailableReason(invoice: BillingInvoice): BillingCheckoutUnavailableReason {
        if (!provider.ownerCheckoutAvailable()) {
            return providerUnavailableReason()
        }
        val rawUrl = invoice.paymentUrl?.trim()
        if (rawUrl.isNullOrEmpty()) {
            return BillingCheckoutUnavailableReason.EXTERNAL_CHECKOUT_UNAVAILABLE
        }
        return BillingCheckoutUnavailableReason.EXTERNAL_CHECKOUT_UNAVAILABLE
    }

    private suspend fun ensureProviderCheckoutDetails(invoice: BillingInvoice) {
        val alreadyHasProviderDetails =
            !invoice.providerInvoiceId.isNullOrBlank() ||
                !invoice.paymentUrl.isNullOrBlank() ||
                !invoice.providerRawPayload.isNullOrBlank()
        if (alreadyHasProviderDetails) {
            return
        }
        val providerResult =
            provider.createInvoice(
                invoiceId = invoice.id,
                venueId = invoice.venueId,
                amountMinor = invoice.amountMinor,
                currency = invoice.currency,
                description = invoice.description,
                periodStart = invoice.periodStart,
                periodEnd = invoice.periodEnd,
                dueAt = invoice.dueAt,
            )
        invoiceRepository.updateProviderDetails(
            invoiceId = invoice.id,
            providerInvoiceId = providerResult.providerInvoiceId,
            paymentUrl = providerResult.paymentUrl,
            providerRawPayload = providerResult.rawPayload,
        )
    }

    private fun providerUnavailableReason(): BillingCheckoutUnavailableReason =
        when (provider.ownerCheckoutUnavailableReason()) {
            BillingCheckoutUnavailableReason.FAKE_PROVIDER_MANUAL_ONLY.wire ->
                BillingCheckoutUnavailableReason.FAKE_PROVIDER_MANUAL_ONLY
            BillingCheckoutUnavailableReason.EXTERNAL_CHECKOUT_UNAVAILABLE.wire ->
                BillingCheckoutUnavailableReason.EXTERNAL_CHECKOUT_UNAVAILABLE
            else -> BillingCheckoutUnavailableReason.PROVIDER_NOT_CONFIGURED
        }

    fun safeOwnerCheckoutUrl(invoice: BillingInvoice): String? {
        if (!provider.ownerCheckoutAvailable()) {
            return null
        }
        val rawUrl = invoice.paymentUrl?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val uri =
            try {
                URI(rawUrl)
            } catch (_: Exception) {
                return null
            }
        val scheme = uri.scheme?.lowercase() ?: return null
        return if (scheme == "https" || scheme == "http") rawUrl else null
    }

    private data class BillablePeriod(
        val venueId: Long,
        val periodStart: LocalDate,
        val periodEnd: LocalDate,
        val price: PlatformEffectivePrice,
    )
}

fun BillingOverview.toResponse(service: BillingOverviewService): OwnerBillingOverviewResponse =
    OwnerBillingOverviewResponse(
        venueId = venueId,
        subscriptionStatus = subscriptionState?.status?.wire ?: SubscriptionStatus.UNKNOWN.wire,
        trialEndAt = subscriptionState?.trialEnd?.toString(),
        paidStartAt = subscriptionState?.paidStart?.toString(),
        lifecycleUpdatedAt = subscriptionState?.updatedAt?.toString(),
        settingsTrialEndDate = settings.trialEndDate?.toString(),
        settingsPaidStartDate = settings.paidStartDate?.toString(),
        priceMinor = effectivePrice?.priceMinor,
        currency = effectivePrice?.currency ?: settings.currency,
        basePaidThrough = basePaidThrough?.toString(),
        courtesyUntil = latestCourtesyAdjustment?.newPaidThrough?.toString(),
        paidThrough = paidThrough?.toString(),
        nextPaymentDate = nextPaymentDate?.toString(),
        nextInvoicePeriodStart = nextInvoicePeriodStart?.toString(),
        nextInvoicePeriodEnd = nextInvoicePeriodEnd?.toString(),
        courtesyDays = courtesyDays,
        lastCourtesyDays = latestCourtesyAdjustment?.days,
        lastCourtesyReason = latestCourtesyAdjustment?.reason,
        lastCourtesyCreatedAt = latestCourtesyAdjustment?.createdAt?.toString(),
        paymentAvailable = paymentAvailable,
        platformCheckoutEnsureAvailable = platformCheckoutEnsureAvailable,
        checkoutEnsureAvailable = checkoutEnsureAvailable,
        unavailableReason = unavailableReason?.wire,
        checkoutUrl = checkoutUrl,
        payableInvoice = payableInvoice?.toOwnerBillingInvoiceDto(service),
        invoices = invoices.map { it.toOwnerBillingInvoiceDto(service) },
        outstandingInvoices = outstandingInvoices,
    )

fun BillingInvoice.toOwnerBillingInvoiceDto(service: BillingOverviewService): OwnerBillingInvoiceDto =
    OwnerBillingInvoiceDto(
        id = id,
        periodStart = periodStart.toString(),
        periodEnd = periodEnd.toString(),
        dueAt = dueAt.toString(),
        amountMinor = amountMinor,
        currency = currency,
        status = status.dbValue,
        checkoutUrl = service.safeOwnerCheckoutUrl(this),
        paidAt = paidAt?.toString(),
    )
