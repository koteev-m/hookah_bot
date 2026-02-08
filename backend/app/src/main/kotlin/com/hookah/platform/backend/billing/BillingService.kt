package com.hookah.platform.backend.billing

import com.hookah.platform.backend.api.DatabaseUnavailableException
import kotlinx.coroutines.CancellationException
import java.sql.SQLException
import java.time.Instant
import java.time.LocalDate

class BillingService(
    private val provider: BillingProvider,
    private val invoiceRepository: BillingInvoiceRepository,
    private val paymentRepository: BillingPaymentRepository,
    private val hooks: BillingHooks = NoopBillingHooks,
) {
    suspend fun createDraftOrOpenInvoice(
        venueId: Long,
        amountMinor: Int,
        currency: String,
        description: String,
        periodStart: LocalDate,
        periodEnd: LocalDate,
        dueAt: Instant,
    ): BillingInvoice {
        try {
            val invoice =
                invoiceRepository.createInvoice(
                    venueId = venueId,
                    periodStart = periodStart,
                    periodEnd = periodEnd,
                    dueAt = dueAt,
                    amountMinor = amountMinor,
                    currency = currency,
                    description = description,
                    provider = provider.providerName(),
                    providerInvoiceId = null,
                    paymentUrl = null,
                    providerRawPayload = null,
                    status = InvoiceStatus.OPEN,
                    paidAt = null,
                    actorUserId = null,
                )

            if (invoice.providerInvoiceId != null ||
                invoice.paymentUrl != null ||
                invoice.providerRawPayload != null
            ) {
                return invoice
            }

            val providerResult =
                provider.createInvoice(
                    invoiceId = invoice.id,
                    venueId = venueId,
                    amountMinor = amountMinor,
                    currency = currency,
                    description = description,
                    periodStart = periodStart,
                    periodEnd = periodEnd,
                    dueAt = dueAt,
                )

            if (providerResult.providerInvoiceId == null &&
                providerResult.paymentUrl == null &&
                providerResult.rawPayload == null
            ) {
                return invoice
            }

            return invoiceRepository.updateProviderDetails(
                invoiceId = invoice.id,
                providerInvoiceId = providerResult.providerInvoiceId,
                paymentUrl = providerResult.paymentUrl,
                providerRawPayload = providerResult.rawPayload,
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: SQLException) {
            throw DatabaseUnavailableException()
        }
    }

    suspend fun applyPaymentEvent(event: PaymentEvent) {
        try {
            val providerInvoiceId = event.providerInvoiceId ?: return
            val invoice =
                invoiceRepository.findInvoiceByProviderInvoiceId(
                    provider = event.provider,
                    providerInvoiceId = providerInvoiceId,
                ) ?: return

            val status =
                when (event) {
                    is PaymentEvent.Paid -> PaymentStatus.SUCCEEDED
                    is PaymentEvent.Failed -> PaymentStatus.FAILED
                    is PaymentEvent.Refunded -> PaymentStatus.REFUNDED
                }

            val inserted =
                paymentRepository.insertPaymentEventIdempotent(
                    invoiceId = invoice.id,
                    provider = event.provider,
                    providerEventId = event.providerEventId,
                    amountMinor = event.amountMinor,
                    currency = event.currency,
                    status = status,
                    occurredAt = event.occurredAt,
                    rawPayload = event.rawPayload,
                )

            if (!inserted && event !is PaymentEvent.Paid) {
                return
            }

            if (event is PaymentEvent.Paid) {
                val matchesInvoice =
                    event.amountMinor == invoice.amountMinor &&
                        event.currency == invoice.currency
                if (matchesInvoice) {
                    val marked =
                        invoiceRepository.markInvoicePaid(
                            invoiceId = invoice.id,
                            paidAt = event.occurredAt,
                            actorUserId = null,
                        )
                    if (marked) {
                        hooks.onInvoicePaid(invoice.copy(status = InvoiceStatus.PAID, paidAt = event.occurredAt), event)
                    }
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: SQLException) {
            throw DatabaseUnavailableException()
        }
    }
}
