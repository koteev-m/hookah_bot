package com.hookah.platform.backend.billing

import io.ktor.server.application.ApplicationCall
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

class FakeBillingProvider : BillingProvider {
    override fun providerName(): String = "FAKE"

    override suspend fun createInvoice(
        venueId: Long,
        amountMinor: Int,
        currency: String,
        description: String,
        periodStart: LocalDate,
        periodEnd: LocalDate,
        dueAt: Instant
    ): ProviderInvoiceResult {
        val providerInvoiceId = "fake-${UUID.randomUUID()}"
        val paymentUrl = "fake://invoice/$providerInvoiceId"
        return ProviderInvoiceResult(
            providerInvoiceId = providerInvoiceId,
            paymentUrl = paymentUrl,
            rawPayload = null
        )
    }

    override suspend fun handleWebhook(call: ApplicationCall): PaymentEvent? = null
}
