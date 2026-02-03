package com.hookah.platform.backend.platform

import com.hookah.platform.backend.api.InvalidInputException
import com.hookah.platform.backend.api.NotFoundException
import com.hookah.platform.backend.billing.BillingInvoiceRepository
import com.hookah.platform.backend.billing.BillingService
import com.hookah.platform.backend.billing.InvoiceStatus
import com.hookah.platform.backend.billing.PaymentEvent
import com.hookah.platform.backend.miniapp.venue.AuditLogRepository
import io.ktor.server.application.call
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import java.time.Instant
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

@Serializable
data class PlatformInvoiceDto(
    val id: Long,
    val periodStart: String,
    val periodEnd: String,
    val dueAt: String,
    val amountMinor: Int,
    val currency: String,
    val status: String,
    val paymentUrl: String? = null,
    val paidAt: String? = null
)

@Serializable
data class PlatformInvoiceListResponse(
    val invoices: List<PlatformInvoiceDto>
)

@Serializable
data class PlatformMarkInvoicePaidRequest(
    val occurredAt: String? = null
)

@Serializable
data class PlatformMarkInvoicePaidResponse(
    val ok: Boolean,
    val alreadyPaid: Boolean
)

fun Route.platformBillingRoutes(
    platformConfig: PlatformConfig,
    billingInvoiceRepository: BillingInvoiceRepository,
    billingService: BillingService,
    auditLogRepository: AuditLogRepository
) {
    route("/platform/venues") {
        get("/{venueId}/invoices") {
            call.requirePlatformOwner(platformConfig)
            val venueId = call.parameters["venueId"]?.toLongOrNull()
                ?: throw InvalidInputException("venueId must be a number")
            val statusRaw = call.request.queryParameters["status"]
            val limit = call.request.queryParameters["limit"]?.toIntOrNull()?.coerceIn(1, 200) ?: 50
            val offset = call.request.queryParameters["offset"]?.toIntOrNull()?.coerceAtLeast(0) ?: 0

            val status = when (statusRaw?.trim()?.lowercase()) {
                null, "", "any" -> null
                else -> InvoiceStatus.fromDb(statusRaw)
                    ?: throw InvalidInputException("status must be one of: DRAFT, OPEN, PAID, PAST_DUE, VOID, any")
            }

            val invoices = billingInvoiceRepository.listInvoicesByVenue(
                venueId = venueId,
                status = status,
                limit = limit,
                offset = offset
            )
            call.respond(PlatformInvoiceListResponse(invoices = invoices.map { it.toDto() }))
        }
    }

    route("/platform/invoices") {
        post("/{invoiceId}/mark-paid") {
            val actorUserId = call.requirePlatformOwner(platformConfig)
            val invoiceId = call.parameters["invoiceId"]?.toLongOrNull()
                ?: throw InvalidInputException("invoiceId must be a number")
            val request = call.receive<PlatformMarkInvoicePaidRequest>()
            val invoice = billingInvoiceRepository.getInvoiceById(invoiceId)
                ?: throw NotFoundException()

            if (invoice.status == InvoiceStatus.PAID) {
                auditLogRepository.appendJson(
                    actorUserId = actorUserId,
                    action = "BILLING_MARK_PAID",
                    entityType = "billing_invoice",
                    entityId = invoice.id,
                    payload = buildJsonObject {
                        put("invoiceId", invoice.id)
                        put("venueId", invoice.venueId)
                        put("alreadyPaid", true)
                    }
                )
                call.respond(PlatformMarkInvoicePaidResponse(ok = true, alreadyPaid = true))
                return@post
            }

            val providerInvoiceId = invoice.providerInvoiceId?.takeIf { it.isNotBlank() }
                ?: throw InvalidInputException("invoice providerInvoiceId is missing")
            val occurredAt = request.occurredAt?.let { parseInstant(it, "occurredAt") } ?: Instant.now()
            val event = PaymentEvent.Paid(
                provider = "FAKE",
                providerEventId = "manual:$invoiceId",
                providerInvoiceId = providerInvoiceId,
                amountMinor = invoice.amountMinor,
                currency = invoice.currency,
                occurredAt = occurredAt,
                rawPayload = null
            )
            billingService.applyPaymentEvent(event)
            auditLogRepository.appendJson(
                actorUserId = actorUserId,
                action = "BILLING_MARK_PAID",
                entityType = "billing_invoice",
                entityId = invoice.id,
                payload = buildJsonObject {
                    put("invoiceId", invoice.id)
                    put("venueId", invoice.venueId)
                    put("alreadyPaid", false)
                    put("occurredAt", occurredAt.toString())
                }
            )
            call.respond(PlatformMarkInvoicePaidResponse(ok = true, alreadyPaid = false))
        }
    }
}

private fun com.hookah.platform.backend.billing.BillingInvoice.toDto(): PlatformInvoiceDto =
    PlatformInvoiceDto(
        id = id,
        periodStart = periodStart.toString(),
        periodEnd = periodEnd.toString(),
        dueAt = dueAt.toString(),
        amountMinor = amountMinor,
        currency = currency,
        status = status.dbValue,
        paymentUrl = paymentUrl,
        paidAt = paidAt?.toString()
    )

private fun parseInstant(value: String, field: String): Instant {
    val trimmed = value.trim()
    if (trimmed.isEmpty()) {
        throw InvalidInputException("$field must not be blank")
    }
    return try {
        Instant.parse(trimmed)
    } catch (e: Exception) {
        throw InvalidInputException("$field must be a valid ISO timestamp")
    }
}
