package com.hookah.platform.backend.billing

import com.hookah.platform.backend.miniapp.venue.AuditLogRepository
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

suspend fun appendBillingCheckoutEnsureAudit(
    auditLogRepository: AuditLogRepository,
    actorUserId: Long,
    actorType: String,
    overview: BillingOverview,
) {
    val invoice = overview.payableInvoice
    auditLogRepository.appendJson(
        actorUserId = actorUserId,
        action = "BILLING_CHECKOUT_ENSURE",
        entityType = if (invoice == null) "venue" else "billing_invoice",
        entityId = invoice?.id ?: overview.venueId,
        payload =
            buildJsonObject {
                put("actorUserId", actorUserId)
                put("actorType", actorType)
                put("venueId", overview.venueId)
                put("source", "${actorType}_checkout_ensure")
                put("paymentAvailable", overview.paymentAvailable)
                overview.unavailableReason?.let { put("unavailableReason", it.wire) }
                invoice?.let {
                    put("invoiceId", it.id)
                    put("amountMinor", it.amountMinor)
                    put("currency", it.currency)
                    put("periodStart", it.periodStart.toString())
                    put("periodEnd", it.periodEnd.toString())
                    put("status", it.status.dbValue)
                }
                if (invoice == null) {
                    overview.effectivePrice?.let {
                        put("amountMinor", it.priceMinor)
                        put("currency", it.currency)
                    }
                }
            },
    )
}

suspend fun appendBillingCourtesyDaysAudit(
    auditLogRepository: AuditLogRepository,
    actorUserId: Long,
    adjustment: BillingAdjustment,
) {
    auditLogRepository.appendJson(
        actorUserId = actorUserId,
        action = "BILLING_COURTESY_DAYS_ADDED",
        entityType = "billing_adjustment",
        entityId = adjustment.id,
        payload =
            buildJsonObject {
                put("actorUserId", actorUserId)
                put("venueId", adjustment.venueId)
                put("adjustmentId", adjustment.id)
                put("days", adjustment.days)
                put("reason", adjustment.reason)
                put("previousPaidThrough", adjustment.previousPaidThrough.toString())
                put("newPaidThrough", adjustment.newPaidThrough.toString())
            },
    )
}
