package com.hookah.platform.backend.billing

import io.ktor.server.application.ApplicationCall
import java.time.Instant
import java.time.LocalDate

class FakeBillingProvider : BillingProvider {
    override fun providerName(): String = "FAKE"

    override fun ownerCheckoutUnavailableReason(): String = "fake_provider_manual_only"

    override suspend fun createInvoice(
        invoiceId: Long,
        venueId: Long,
        amountMinor: Int,
        currency: String,
        description: String,
        periodStart: LocalDate,
        periodEnd: LocalDate,
        dueAt: Instant,
    ): ProviderInvoiceResult {
        val providerInvoiceId = "fake-$invoiceId"
        val paymentUrl = "fake://invoice/$providerInvoiceId"
        return ProviderInvoiceResult(
            providerInvoiceId = providerInvoiceId,
            paymentUrl = paymentUrl,
            rawPayload = null,
        )
    }

    override suspend fun handleWebhook(call: ApplicationCall): PaymentEvent? = null
}
