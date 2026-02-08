package com.hookah.platform.backend.billing

import java.time.Instant
import java.time.LocalDate
import java.util.Locale

enum class InvoiceStatus(val dbValue: String) {
    DRAFT("DRAFT"),
    OPEN("OPEN"),
    PAID("PAID"),
    PAST_DUE("PAST_DUE"),
    VOID("VOID"),
    ;

    companion object {
        fun fromDb(value: String?): InvoiceStatus? {
            val normalized = value?.trim()?.uppercase(Locale.ROOT) ?: return null
            return entries.firstOrNull { it.dbValue == normalized }
        }
    }
}

enum class PaymentStatus(val dbValue: String) {
    SUCCEEDED("SUCCEEDED"),
    FAILED("FAILED"),
    REFUNDED("REFUNDED"),
    ;

    companion object {
        fun fromDb(value: String?): PaymentStatus? {
            val normalized = value?.trim()?.uppercase(Locale.ROOT) ?: return null
            return entries.firstOrNull { it.dbValue == normalized }
        }
    }
}

data class BillingInvoice(
    val id: Long,
    val venueId: Long,
    val periodStart: LocalDate,
    val periodEnd: LocalDate,
    val dueAt: Instant,
    val amountMinor: Int,
    val currency: String,
    val description: String,
    val provider: String,
    val providerInvoiceId: String?,
    val paymentUrl: String?,
    val providerRawPayload: String?,
    val status: InvoiceStatus,
    val createdAt: Instant,
    val updatedAt: Instant,
    val paidAt: Instant?,
    val updatedByUserId: Long?,
)

data class BillingPayment(
    val id: Long,
    val invoiceId: Long,
    val provider: String,
    val providerEventId: String,
    val amountMinor: Int,
    val currency: String,
    val status: PaymentStatus,
    val occurredAt: Instant,
    val createdAt: Instant,
    val rawPayload: String?,
)
