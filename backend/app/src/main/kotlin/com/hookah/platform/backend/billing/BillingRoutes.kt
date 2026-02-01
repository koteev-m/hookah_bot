package com.hookah.platform.backend.billing

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
    billingService: BillingService
) {
    route("/api/billing") {
        post("/webhook/{provider?}") {
            val providerName = call.parameters["provider"]?.takeIf { it.isNotBlank() }
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
