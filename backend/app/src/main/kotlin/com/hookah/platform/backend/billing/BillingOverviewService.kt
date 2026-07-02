package com.hookah.platform.backend.billing

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
}

data class BillingOverview(
    val venueId: Long,
    val subscriptionState: VenueSubscriptionState?,
    val settings: PlatformSubscriptionSettings,
    val effectivePrice: PlatformEffectivePrice?,
    val paidThrough: LocalDate?,
    val payableInvoice: BillingInvoice?,
    val invoices: List<BillingInvoice>,
    val checkoutUrl: String?,
    val paymentAvailable: Boolean,
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
    val paidThrough: String? = null,
    val paymentAvailable: Boolean,
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
    private val billingService: BillingService,
    private val provider: BillingProvider,
    private val config: SubscriptionBillingConfig,
    private val clock: Clock = Clock.systemUTC(),
) {
    suspend fun readOverview(venueId: Long): BillingOverview? {
        val snapshot = settingsRepository.getSubscriptionSnapshot(venueId) ?: return null
        return buildOverview(
            venueId = venueId,
            snapshot = snapshot,
            subscriptionState = subscriptionRepository.findSubscriptionState(venueId),
            invoices = invoiceRepository.listInvoicesByVenue(venueId, status = null, limit = 50, offset = 0),
        )
    }

    suspend fun ensureCheckout(venueId: Long): BillingOverview? {
        val snapshot = settingsRepository.getSubscriptionSnapshot(venueId) ?: return null
        subscriptionRepository.ensureRowExistsForVenue(venueId)
        val period = resolveBillablePeriod(snapshot)
        if (period != null) {
            val existing =
                invoiceRepository.getInvoiceByPeriod(
                    venueId = venueId,
                    periodStart = period.periodStart,
                    periodEnd = period.periodEnd,
                )
            if (provider.ownerCheckoutAvailable()) {
                if (existing == null) {
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
                } else if (existing.status == InvoiceStatus.OPEN || existing.status == InvoiceStatus.PAST_DUE) {
                    ensureProviderCheckoutDetails(existing)
                }
            }
        }

        return readOverview(venueId)
    }

    private fun buildOverview(
        venueId: Long,
        snapshot: PlatformSubscriptionSnapshot,
        subscriptionState: VenueSubscriptionState?,
        invoices: List<BillingInvoice>,
    ): BillingOverview {
        val payableInvoice =
            invoices.firstOrNull {
                it.status == InvoiceStatus.OPEN || it.status == InvoiceStatus.PAST_DUE
            }
        val paidThrough =
            invoices
                .asSequence()
                .filter { it.status == InvoiceStatus.PAID }
                .maxByOrNull { it.periodEnd }
                ?.periodEnd
        val currentPeriod = resolveBillablePeriod(snapshot)
        val currentPeriodPaid =
            currentPeriod != null &&
                invoices.any {
                    it.periodStart == currentPeriod.periodStart &&
                        it.periodEnd == currentPeriod.periodEnd &&
                        it.status == InvoiceStatus.PAID
                }
        val checkoutUrl = payableInvoice?.let(::safeOwnerCheckoutUrl)
        val unavailableReason =
            when {
                checkoutUrl != null -> null
                currentPeriodPaid -> BillingCheckoutUnavailableReason.ALREADY_PAID
                currentPeriod == null -> resolveBillablePeriodUnavailableReason(snapshot)
                payableInvoice != null -> resolveCheckoutUnavailableReason(payableInvoice)
                !provider.ownerCheckoutAvailable() -> providerUnavailableReason()
                else -> BillingCheckoutUnavailableReason.EXTERNAL_CHECKOUT_UNAVAILABLE
            }
        val checkoutEnsureAvailable =
            currentPeriod != null &&
                !currentPeriodPaid &&
                provider.ownerCheckoutAvailable()

        return BillingOverview(
            venueId = venueId,
            subscriptionState = subscriptionState,
            settings = snapshot.settings,
            effectivePrice = snapshot.effectivePriceToday,
            paidThrough = paidThrough,
            payableInvoice = payableInvoice,
            invoices = invoices,
            checkoutUrl = checkoutUrl,
            paymentAvailable = checkoutUrl != null,
            checkoutEnsureAvailable = checkoutEnsureAvailable,
            unavailableReason = unavailableReason,
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

    private fun resolveBillablePeriod(snapshot: PlatformSubscriptionSnapshot): BillablePeriod? {
        val anchor =
            SubscriptionBillingPeriodResolver.resolvePaidAnchor(
                trialEndDate = snapshot.settings.trialEndDate,
                paidStartDate = snapshot.settings.paidStartDate,
            ) ?: return null
        val today = LocalDate.now(clock.withZone(config.timeZone))
        val periodStart = SubscriptionBillingPeriodResolver.resolvePeriodStart(anchor, today)
        val leadDate = periodStart.minusDays(config.leadDays)
        if (today.isBefore(leadDate)) {
            return null
        }
        val periodEnd = periodStart.plusMonths(1).minusDays(1)
        val price =
            settingsRepository.resolveEffectivePrice(
                settings = snapshot.settings,
                schedule = snapshot.schedule,
                today = periodStart,
            ) ?: return null
        return BillablePeriod(
            periodStart = periodStart,
            periodEnd = periodEnd,
            price = price,
        )
    }

    private fun resolveBillablePeriodUnavailableReason(
        snapshot: PlatformSubscriptionSnapshot,
    ): BillingCheckoutUnavailableReason {
        val anchor =
            SubscriptionBillingPeriodResolver.resolvePaidAnchor(
                trialEndDate = snapshot.settings.trialEndDate,
                paidStartDate = snapshot.settings.paidStartDate,
            ) ?: return BillingCheckoutUnavailableReason.MISSING_BILLING_PERIOD
        val today = LocalDate.now(clock.withZone(config.timeZone))
        val periodStart = SubscriptionBillingPeriodResolver.resolvePeriodStart(anchor, today)
        val leadDate = periodStart.minusDays(config.leadDays)
        if (today.isBefore(leadDate)) {
            return BillingCheckoutUnavailableReason.MISSING_BILLING_PERIOD
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
        paidThrough = paidThrough?.toString(),
        paymentAvailable = paymentAvailable,
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
