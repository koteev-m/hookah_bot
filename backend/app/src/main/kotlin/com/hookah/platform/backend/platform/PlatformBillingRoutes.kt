package com.hookah.platform.backend.platform

import com.hookah.platform.backend.api.InvalidInputException
import com.hookah.platform.backend.api.NotFoundException
import com.hookah.platform.backend.billing.BillingInvoiceRepository
import com.hookah.platform.backend.billing.BillingOverviewService
import com.hookah.platform.backend.billing.BillingService
import com.hookah.platform.backend.billing.InvoiceStatus
import com.hookah.platform.backend.billing.appendBillingCheckoutEnsureAudit
import com.hookah.platform.backend.billing.toResponse
import com.hookah.platform.backend.miniapp.venue.AuditLogRepository
import io.ktor.server.application.call
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.Instant

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
    val checkoutUrl: String? = null,
    val paidAt: String? = null,
)

@Serializable
data class PlatformInvoiceListResponse(
    val invoices: List<PlatformInvoiceDto>,
)

@Serializable
data class PlatformMarkInvoicePaidRequest(
    val occurredAt: String? = null,
)

@Serializable
data class PlatformMarkInvoicePaidResponse(
    val ok: Boolean,
    val alreadyPaid: Boolean,
)

fun Route.platformBillingRoutes(
    platformConfig: PlatformConfig,
    billingInvoiceRepository: BillingInvoiceRepository,
    billingService: BillingService,
    billingOverviewService: BillingOverviewService,
    auditLogRepository: AuditLogRepository,
) {
    route("/platform/venues") {
        get("/{venueId}/billing") {
            call.requirePlatformOwner(platformConfig)
            val venueId =
                call.parameters["venueId"]?.toLongOrNull()
                    ?: throw InvalidInputException("venueId must be a number")
            val overview = billingOverviewService.readOverview(venueId) ?: throw NotFoundException()
            call.respond(overview.toResponse(billingOverviewService))
        }

        post("/{venueId}/billing/checkout") {
            val actorUserId = call.requirePlatformOwner(platformConfig)
            val venueId =
                call.parameters["venueId"]?.toLongOrNull()
                    ?: throw InvalidInputException("venueId must be a number")
            val overview = billingOverviewService.ensureCheckout(venueId) ?: throw NotFoundException()
            appendBillingCheckoutEnsureAudit(
                auditLogRepository = auditLogRepository,
                actorUserId = actorUserId,
                actorType = "platform_owner",
                overview = overview,
            )
            call.respond(overview.toResponse(billingOverviewService))
        }

        get("/{venueId}/invoices") {
            call.requirePlatformOwner(platformConfig)
            val venueId =
                call.parameters["venueId"]?.toLongOrNull()
                    ?: throw InvalidInputException("venueId must be a number")
            val statusRaw = call.request.queryParameters["status"]
            val limit = call.request.queryParameters["limit"]?.toIntOrNull()?.coerceIn(1, 200) ?: 50
            val offset = call.request.queryParameters["offset"]?.toIntOrNull()?.coerceAtLeast(0) ?: 0

            val statusNormalized = statusRaw?.trim()
            val status =
                when (statusNormalized?.lowercase()) {
                    null, "", "any" -> null
                    else ->
                        InvoiceStatus.fromDb(statusNormalized)
                            ?: throw InvalidInputException(
                                "status must be one of: DRAFT, OPEN, PAID, PAST_DUE, VOID, any",
                            )
                }

            val invoices =
                billingInvoiceRepository.listInvoicesByVenue(
                    venueId = venueId,
                    status = status,
                    limit = limit,
                    offset = offset,
                )
            call.respond(PlatformInvoiceListResponse(invoices = invoices.map { it.toDto(billingOverviewService) }))
        }
    }

    route("/platform/invoices") {
        post("/{invoiceId}/mark-paid") {
            val actorUserId = call.requirePlatformOwner(platformConfig)
            val invoiceId =
                call.parameters["invoiceId"]?.toLongOrNull()
                    ?: throw InvalidInputException("invoiceId must be a number")
            val request = call.receive<PlatformMarkInvoicePaidRequest>()
            val invoice =
                billingInvoiceRepository.getInvoiceById(invoiceId)
                    ?: throw NotFoundException()
            val previousStatus = invoice.status

            if (invoice.status == InvoiceStatus.PAID) {
                auditLogRepository.appendJson(
                    actorUserId = actorUserId,
                    action = "BILLING_MARK_PAID",
                    entityType = "billing_invoice",
                    entityId = invoice.id,
                    payload =
                        buildJsonObject {
                            put("actorUserId", actorUserId)
                            put("invoiceId", invoice.id)
                            put("venueId", invoice.venueId)
                            put("amountMinor", invoice.amountMinor)
                            put("currency", invoice.currency)
                            put("previousStatus", previousStatus.dbValue)
                            put("newStatus", InvoiceStatus.PAID.dbValue)
                            put("source", "manual_platform_action")
                            put("alreadyPaid", true)
                        },
                )
                call.respond(PlatformMarkInvoicePaidResponse(ok = true, alreadyPaid = true))
                return@post
            }

            if (invoice.status != InvoiceStatus.OPEN && invoice.status != InvoiceStatus.PAST_DUE) {
                throw InvalidInputException("invoice must be OPEN or PAST_DUE to mark paid")
            }

            val occurredAt = request.occurredAt?.let { parseInstant(it, "occurredAt") } ?: Instant.now()
            billingService.applyManualPayment(invoice, occurredAt = occurredAt, actorUserId = actorUserId)
            val updatedInvoice = billingInvoiceRepository.getInvoiceById(invoice.id)
            auditLogRepository.appendJson(
                actorUserId = actorUserId,
                action = "BILLING_MARK_PAID",
                entityType = "billing_invoice",
                entityId = invoice.id,
                payload =
                    buildJsonObject {
                        put("actorUserId", actorUserId)
                        put("invoiceId", invoice.id)
                        put("venueId", invoice.venueId)
                        put("amountMinor", invoice.amountMinor)
                        put("currency", invoice.currency)
                        put("previousStatus", previousStatus.dbValue)
                        put("newStatus", updatedInvoice?.status?.dbValue ?: InvoiceStatus.PAID.dbValue)
                        put("source", "manual_platform_action")
                        put("alreadyPaid", false)
                        put("occurredAt", occurredAt.toString())
                    },
            )
            call.respond(PlatformMarkInvoicePaidResponse(ok = true, alreadyPaid = false))
        }
    }
}

private fun com.hookah.platform.backend.billing.BillingInvoice.toDto(
    billingOverviewService: BillingOverviewService,
): PlatformInvoiceDto {
    val checkoutUrl = billingOverviewService.safeOwnerCheckoutUrl(this)
    return PlatformInvoiceDto(
        id = id,
        periodStart = periodStart.toString(),
        periodEnd = periodEnd.toString(),
        dueAt = dueAt.toString(),
        amountMinor = amountMinor,
        currency = currency,
        status = status.dbValue,
        paymentUrl = checkoutUrl,
        checkoutUrl = checkoutUrl,
        paidAt = paidAt?.toString(),
    )
}

private fun parseInstant(
    value: String,
    field: String,
): Instant {
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
