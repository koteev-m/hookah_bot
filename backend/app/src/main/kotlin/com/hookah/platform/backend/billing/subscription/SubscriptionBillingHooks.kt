package com.hookah.platform.backend.billing.subscription

import com.hookah.platform.backend.billing.BillingHooks
import com.hookah.platform.backend.billing.BillingInvoice
import com.hookah.platform.backend.billing.PaymentEvent
import com.hookah.platform.backend.miniapp.subscription.SubscriptionStatus
import com.hookah.platform.backend.miniapp.subscription.db.SubscriptionRepository
import java.time.Instant

class SubscriptionBillingHooks(
    private val subscriptionRepository: SubscriptionRepository,
) : BillingHooks {
    override suspend fun onInvoicePaid(
        invoice: BillingInvoice,
        event: PaymentEvent.Paid,
    ) {
        subscriptionRepository.updateStatus(
            venueId = invoice.venueId,
            status = SubscriptionStatus.ACTIVE,
            paidStart = resolvePaidStart(invoice.paidAt, event.occurredAt),
        )
    }

    private fun resolvePaidStart(
        paidAt: Instant?,
        eventAt: Instant,
    ): Instant {
        return paidAt ?: eventAt
    }
}
