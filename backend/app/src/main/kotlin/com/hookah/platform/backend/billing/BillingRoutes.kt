package com.hookah.platform.backend.billing

import com.hookah.platform.backend.api.ForbiddenException
import com.hookah.platform.backend.security.constantTimeEquals
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import kotlinx.coroutines.CancellationException

fun Route.billingWebhookRoutes(
    config: BillingConfig,
    providerRegistry: BillingProviderRegistry,
    billingService: BillingService,
) {
    route("/api/billing") {
        post("/webhook/{provider?}") {
            val allowlist = config.webhookIpAllowlist
            if (allowlist != null) {
                val clientIp =
                    if (config.webhookIpAllowlistUseXForwardedFor) {
                        call.request.headers["X-Forwarded-For"]
                            ?.split(',', limit = 2)
                            ?.firstOrNull()
                            ?.trim()
                    } else {
                        call.request.local.remoteHost
                    }
                if (!allowlist.isAllowed(clientIp)) {
                    throw ForbiddenException()
                }
            }

            val headerSecret =
                call.request.headers["X-Webhook-Secret-Token"]
                    ?: call.request.headers["X-Billing-Webhook-Secret"]
            if (!constantTimeEquals(config.webhookSecret, headerSecret)) {
                throw ForbiddenException()
            }

            val providerName =
                call.parameters["provider"]?.takeIf { it.isNotBlank() }
                    ?: config.normalizedProvider
            val provider = providerRegistry.resolve(providerName)
            if (provider == null) {
                call.respond(HttpStatusCode.BadRequest)
                return@post
            }

            try {
                val event = provider.handleWebhook(call)
                if (event != null) {
                    billingService.applyPaymentEvent(event)
                }
                call.respond(HttpStatusCode.OK)
            } catch (e: BillingWebhookRejectedException) {
                call.respond(e.status)
            } catch (e: CancellationException) {
                throw e
            }
        }
    }
}
