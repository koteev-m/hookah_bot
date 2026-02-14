package com.hookah.platform.backend.billing

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receiveText
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.time.LocalDate

class GenericHmacBillingProvider(
    private val config: GenericHmacBillingProviderConfig,
    private val signatureAlgorithm: BillingSignatureAlgorithm = HmacSha256HexBillingSignatureAlgorithm(),
    private val json: Json = Json,
) : BillingProvider {
    override fun providerName(): String = PROVIDER_NAME

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
        val checkoutBaseUrl =
            config.checkoutBaseUrl?.takeIf { it.isNotBlank() }
                ?: throw IllegalStateException("billing.generic.checkoutBaseUrl must be configured")
        val signingSecret =
            config.signingSecret?.takeIf { it.isNotBlank() }
                ?: throw IllegalStateException("billing.generic.signingSecret must be configured")

        val providerInvoiceId = "ghbp-$invoiceId"
        val signedPayload = "$providerInvoiceId:$amountMinor:${currency.uppercase()}"
        val signature = signatureAlgorithm.sign(signedPayload, signingSecret)
        val paymentUrl =
            buildCheckoutUrl(
                checkoutBaseUrl = checkoutBaseUrl,
                providerInvoiceId = providerInvoiceId,
                venueId = venueId,
                amountMinor = amountMinor,
                currency = currency,
                description = description,
                returnUrl = config.checkoutReturnUrl,
                merchantId = config.merchantId,
                signature = signature,
            )

        val rawPayload =
            buildString {
                append("{\"providerInvoiceId\":\"")
                append(providerInvoiceId)
                append("\",\"signature\":\"")
                append(signature)
                append("\"}")
            }

        return ProviderInvoiceResult(
            providerInvoiceId = providerInvoiceId,
            paymentUrl = paymentUrl,
            rawPayload = rawPayload,
        )
    }

    override suspend fun handleWebhook(call: ApplicationCall): PaymentEvent {
        val rawPayload = call.receiveText()
        val signatureHeader =
            call.request.headers[config.signatureHeader]
                ?: throw BillingWebhookRejectedException(HttpStatusCode.Unauthorized, "Missing signature header")
        val signingSecret =
            config.signingSecret?.takeIf { it.isNotBlank() }
                ?: throw BillingWebhookRejectedException(HttpStatusCode.InternalServerError, "Missing signing secret")

        if (!signatureAlgorithm.verify(rawPayload, signatureHeader, signingSecret)) {
            throw BillingWebhookRejectedException(HttpStatusCode.Forbidden, "Invalid signature")
        }

        val payload =
            try {
                json.parseToJsonElement(rawPayload).jsonObject
            } catch (_: Exception) {
                throw BillingWebhookRejectedException(HttpStatusCode.BadRequest, "Invalid webhook payload")
            }

        val eventId = payload.requiredString("event_id")
        val providerInvoiceId = payload.requiredString("invoice_id")
        val amountMinor = payload.requiredInt("amount_minor")
        val currency = payload.requiredString("currency")
        val paymentStatus = payload.requiredString("payment_status").lowercase()
        val occurredAt = payload.optionalString("occurred_at")?.let { parseOccurredAt(it) } ?: Instant.now()

        return when (paymentStatus) {
            "paid", "succeeded" ->
                PaymentEvent.Paid(
                    provider = PROVIDER_NAME,
                    providerEventId = eventId,
                    providerInvoiceId = providerInvoiceId,
                    amountMinor = amountMinor,
                    currency = currency,
                    occurredAt = occurredAt,
                    rawPayload = rawPayload,
                )

            "failed" ->
                PaymentEvent.Failed(
                    provider = PROVIDER_NAME,
                    providerEventId = eventId,
                    providerInvoiceId = providerInvoiceId,
                    amountMinor = amountMinor,
                    currency = currency,
                    occurredAt = occurredAt,
                    rawPayload = rawPayload,
                )

            "refunded" ->
                PaymentEvent.Refunded(
                    provider = PROVIDER_NAME,
                    providerEventId = eventId,
                    providerInvoiceId = providerInvoiceId,
                    amountMinor = amountMinor,
                    currency = currency,
                    occurredAt = occurredAt,
                    rawPayload = rawPayload,
                )

            else -> throw BillingWebhookRejectedException(HttpStatusCode.BadRequest, "Unsupported payment_status")
        }
    }

    private fun parseOccurredAt(value: String): Instant {
        return try {
            Instant.parse(value)
        } catch (_: Exception) {
            throw BillingWebhookRejectedException(HttpStatusCode.BadRequest, "Invalid occurred_at")
        }
    }

    private fun buildCheckoutUrl(
        checkoutBaseUrl: String,
        providerInvoiceId: String,
        venueId: Long,
        amountMinor: Int,
        currency: String,
        description: String,
        returnUrl: String?,
        merchantId: String?,
        signature: String,
    ): String {
        val query =
            linkedMapOf(
                "invoice_id" to providerInvoiceId,
                "venue_id" to venueId.toString(),
                "amount_minor" to amountMinor.toString(),
                "currency" to currency,
                "description" to description,
                "signature" to signature,
            ).apply {
                returnUrl?.takeIf { it.isNotBlank() }?.let { put("return_url", it) }
                merchantId?.takeIf { it.isNotBlank() }?.let { put("merchant_id", it) }
            }
                .entries
                .joinToString("&") { (key, value) ->
                    "${encode(key)}=${encode(value)}"
                }

        val separator = if (checkoutBaseUrl.contains('?')) '&' else '?'
        return "$checkoutBaseUrl$separator$query"
    }

    private fun JsonObject.requiredString(key: String): String {
        val value = this[key]?.jsonPrimitive?.contentOrNull?.trim()
        return value?.takeIf { it.isNotEmpty() }
            ?: throw BillingWebhookRejectedException(HttpStatusCode.BadRequest, "$key is required")
    }

    private fun JsonObject.optionalString(key: String): String? =
        this[key]?.jsonPrimitive?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }

    private fun JsonObject.requiredInt(key: String): Int {
        return this[key]?.jsonPrimitive?.intOrNull
            ?: throw BillingWebhookRejectedException(HttpStatusCode.BadRequest, "$key must be integer")
    }

    private fun encode(value: String): String = URLEncoder.encode(value, StandardCharsets.UTF_8)

    companion object {
        const val PROVIDER_NAME: String = "GENERIC_HMAC"
    }
}
