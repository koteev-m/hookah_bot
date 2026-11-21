package com.hookah.platform.backend.billing.subscription

import com.hookah.platform.backend.billing.BillingHooks
import com.hookah.platform.backend.billing.BillingInvoice
import com.hookah.platform.backend.billing.PaymentEvent
import com.hookah.platform.backend.miniapp.subscription.SubscriptionStatus
import com.hookah.platform.backend.miniapp.subscription.db.SubscriptionRepository
import java.time.Clock
import java.time.LocalDate

class SubscriptionBillingHooks
    @JvmOverloads
    constructor(
        private val subscriptionRepository: SubscriptionRepository,
        private val clock: Clock = Clock.systemUTC(),
    ) : BillingHooks {
        override suspend fun onInvoicePaid(
            invoice: BillingInvoice,
            event: PaymentEvent.Paid,
        ) {
            val today = LocalDate.now(clock)
            if (today < invoice.periodStart || today > invoice.periodEnd) return
            subscriptionRepository.updateStatusIfCurrentIn(
                venueId = invoice.venueId,
                allowedCurrentStatuses = setOf(SubscriptionStatus.TRIAL, SubscriptionStatus.PAST_DUE),
                requireNoUnpaidOn = today,
                nextStatus = SubscriptionStatus.ACTIVE,
                paidStart = invoice.paidAt ?: event.occurredAt,
            )
        }
    }
