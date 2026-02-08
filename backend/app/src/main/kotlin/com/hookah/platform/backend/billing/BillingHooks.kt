package com.hookah.platform.backend.billing

interface BillingHooks {
    suspend fun onInvoicePaid(
        invoice: BillingInvoice,
        event: PaymentEvent.Paid,
    ) {}
}

object NoopBillingHooks : BillingHooks
