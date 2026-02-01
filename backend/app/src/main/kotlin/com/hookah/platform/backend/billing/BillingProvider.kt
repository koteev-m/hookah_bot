package com.hookah.platform.backend.billing

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import java.time.Instant
import java.time.LocalDate

interface BillingProvider {
    fun providerName(): String

    suspend fun createInvoice(
        venueId: Long,
        amountMinor: Int,
        currency: String,
        description: String,
        periodStart: LocalDate,
        periodEnd: LocalDate,
        dueAt: Instant
    ): ProviderInvoiceResult

    suspend fun handleWebhook(call: ApplicationCall): PaymentEvent?
}

data class ProviderInvoiceResult(
    val providerInvoiceId: String?,
    val paymentUrl: String?,
    val rawPayload: String?
)

sealed interface PaymentEvent {
    val provider: String
    val providerEventId: String
    val providerInvoiceId: String?
    val amountMinor: Int
    val currency: String
    val occurredAt: Instant
    val rawPayload: String?

    data class Paid(
        override val provider: String,
        override val providerEventId: String,
        override val providerInvoiceId: String?,
        override val amountMinor: Int,
        override val currency: String,
        override val occurredAt: Instant,
        override val rawPayload: String?
    ) : PaymentEvent

    data class Failed(
        override val provider: String,
        override val providerEventId: String,
        override val providerInvoiceId: String?,
        override val amountMinor: Int,
        override val currency: String,
        override val occurredAt: Instant,
        override val rawPayload: String?
    ) : PaymentEvent

    data class Refunded(
        override val provider: String,
        override val providerEventId: String,
        override val providerInvoiceId: String?,
        override val amountMinor: Int,
        override val currency: String,
        override val occurredAt: Instant,
        override val rawPayload: String?
    ) : PaymentEvent
}

class BillingWebhookRejectedException(
    val status: HttpStatusCode,
    message: String? = null
) : RuntimeException(message)
