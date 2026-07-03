package com.hookah.platform.backend.billing

import com.hookah.platform.backend.api.InvalidInputException
import com.hookah.platform.backend.billing.subscription.SubscriptionBillingConfig
import com.hookah.platform.backend.billing.subscription.SubscriptionBillingPeriodResolver
import com.hookah.platform.backend.miniapp.subscription.SubscriptionStatus
import com.hookah.platform.backend.miniapp.subscription.db.SubscriptionRepository
import com.hookah.platform.backend.miniapp.subscription.db.VenueSubscriptionState
import com.hookah.platform.backend.platform.PlatformEffectivePrice
import com.hookah.platform.backend.platform.PlatformSubscriptionSettings
import com.hookah.platform.backend.platform.PlatformSubscriptionSettingsRepository
import com.hookah.platform.backend.platform.PlatformSubscriptionSnapshot
import kotlinx.serialization.Serializable
import java.net.URI
import java.time.Clock
import java.time.LocalDate

enum class BillingCheckoutUnavailableReason(val wire: String) {
    PROVIDER_NOT_CONFIGURED("provider_not_configured"),
    EXTERNAL_CHECKOUT_UNAVAILABLE("external_checkout_unavailable"),
    MISSING_PRICE("missing_price"),
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
    suspend fun readOverview(venueId: Long): BillingOverview? {
        val snapshot = settingsRepository.getSubscriptionSnapshot(venueId) ?: return null
        val basePaidThrough = invoiceRepository.findLatestPaidInvoicePeriodEnd(venueId)
        val latestCourtesyAdjustment = adjustmentRepository.findLatestCourtesyAdjustment(venueId)
        return buildOverview(
            venueId = venueId,
            snapshot = snapshot,
            subscriptionState = subscriptionRepository.findSubscriptionState(venueId),
            invoices = invoiceRepository.listInvoicesByVenue(venueId, status = null, limit = 50, offset = 0),
            basePaidThrough = basePaidThrough,
            latestCourtesyAdjustment = latestCourtesyAdjustment,
            courtesyDays = adjustmentRepository.countCourtesyDays(venueId),
        )
    }

    suspend fun ensureCheckout(
        venueId: Long,
        allowAdvance: Boolean,
    ): BillingOverview? {
        val snapshot = settingsRepository.getSubscriptionSnapshot(venueId) ?: return null
        subscriptionRepository.ensureRowExistsForVenue(venueId)
        val basePaidThrough = invoiceRepository.findLatestPaidInvoicePeriodEnd(venueId)
        val latestCourtesyAdjustment = adjustmentRepository.findLatestCourtesyAdjustment(venueId)
        val effectivePaidThrough = effectivePaidThrough(basePaidThrough, latestCourtesyAdjustment)
        val period = resolveBillablePeriod(snapshot, effectivePaidThrough, enforceLeadWindow = !allowAdvance)
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

    suspend fun addCourtesyDays(
        venueId: Long,
        days: Int,
        reason: String,
        actorUserId: Long,
    ): BillingAdjustment {
        if (days <= 0) {
            throw InvalidInputException("days must be greater than 0")
        }
        val trimmedReason = reason.trim()
        if (trimmedReason.isEmpty()) {
            throw InvalidInputException("reason must not be blank")
        }
        val snapshot =
            settingsRepository.getSubscriptionSnapshot(venueId)
                ?: throw InvalidInputException("venue not found")
        val basePaidThrough = invoiceRepository.findLatestPaidInvoicePeriodEnd(venueId)
        val latestCourtesyAdjustment = adjustmentRepository.findLatestCourtesyAdjustment(venueId)
        val previousPaidThrough =
            effectivePaidThrough(basePaidThrough, latestCourtesyAdjustment)
                ?: throw InvalidInputException("NO_PAID_PERIOD_TO_EXTEND")
        val newPaidThrough = previousPaidThrough.plusDays(days.toLong())
        val overlapping =
            invoiceRepository.findOpenOrPastDueInvoiceOverlapping(
                venueId = venueId,
                periodStart = previousPaidThrough.plusDays(1),
                periodEnd = newPaidThrough,
            )
        if (overlapping != null) {
            throw InvalidInputException("OPEN_INVOICE_OVERLAPS_COURTESY_PERIOD")
        }
        val adjustment =
            adjustmentRepository.createCourtesyDaysAdjustment(
                venueId = venueId,
                days = days,
                reason = trimmedReason,
                previousPaidThrough = previousPaidThrough,
                newPaidThrough = newPaidThrough,
                actorUserId = actorUserId,
            )
        val today = LocalDate.now(clock.withZone(config.timeZone))
        if (!newPaidThrough.isBefore(today)) {
            subscriptionRepository.updateStatusIfCurrentIn(
                venueId = snapshot.settings.venueId,
                allowedCurrentStatuses = setOf(SubscriptionStatus.PAST_DUE),
                nextStatus = SubscriptionStatus.ACTIVE,
            )
        }
        return adjustment
    }

    private fun buildOverview(
        venueId: Long,
        snapshot: PlatformSubscriptionSnapshot,
        subscriptionState: VenueSubscriptionState?,
        invoices: List<BillingInvoice>,
        basePaidThrough: LocalDate?,
        latestCourtesyAdjustment: BillingAdjustment?,
        courtesyDays: Int,
    ): BillingOverview {
        val payableInvoice =
            invoices.firstOrNull {
                it.status == InvoiceStatus.OPEN || it.status == InvoiceStatus.PAST_DUE
            }
        val paidThrough = effectivePaidThrough(basePaidThrough, latestCourtesyAdjustment)
        val platformPeriod = resolveBillablePeriod(snapshot, paidThrough, enforceLeadWindow = false)
        val venuePeriod = resolveBillablePeriod(snapshot, paidThrough, enforceLeadWindow = true)
        val checkoutUrl = payableInvoice?.let(::safeOwnerCheckoutUrl)
        val unavailableReason =
            when {
                checkoutUrl != null -> null
                payableInvoice != null -> resolveCheckoutUnavailableReason(payableInvoice)
                platformPeriod == null -> resolveBillablePeriodUnavailableReason(snapshot, paidThrough)
                venuePeriod == null -> BillingCheckoutUnavailableReason.ADVANCE_WINDOW_NOT_OPEN
                !provider.ownerCheckoutAvailable() -> providerUnavailableReason()
                else -> BillingCheckoutUnavailableReason.EXTERNAL_CHECKOUT_UNAVAILABLE
            }
        val nextPaymentDate = paidThrough?.plusDays(1)

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
            checkoutUrl = checkoutUrl,
            paymentAvailable = checkoutUrl != null,
            platformCheckoutEnsureAvailable = platformPeriod != null,
            checkoutEnsureAvailable = venuePeriod != null,
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
            if (paidThrough == null) {
                SubscriptionBillingPeriodResolver.resolvePeriodStart(anchor, today)
            } else {
                SubscriptionBillingPeriodResolver.resolveNextPeriodStart(paidThrough)
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
            if (paidThrough == null) {
                SubscriptionBillingPeriodResolver.resolvePeriodStart(anchor, today)
            } else {
                SubscriptionBillingPeriodResolver.resolveNextPeriodStart(paidThrough)
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
