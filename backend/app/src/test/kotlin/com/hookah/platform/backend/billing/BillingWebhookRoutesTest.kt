package com.hookah.platform.backend.billing

import io.ktor.client.request.post
import io.ktor.client.request.headers
import io.ktor.http.HttpStatusCode
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import io.mockk.mockk
import kotlin.test.Test
import kotlin.test.assertEquals

class BillingWebhookRoutesTest {
    @Test
    fun `missing webhook secret returns forbidden`() = testApplication {
        val config = BillingConfig(provider = "fake", webhookSecret = "super-secret")
        val providerRegistry = BillingProviderRegistry(listOf(FakeBillingProvider()))
        val billingService = BillingService(
            provider = FakeBillingProvider(),
            invoiceRepository = mockk(relaxed = true),
            paymentRepository = mockk(relaxed = true)
        )

        application {
            routing {
                billingWebhookRoutes(config, providerRegistry, billingService)
            }
        }

        val response = client.post("/api/billing/webhook")
        assertEquals(HttpStatusCode.Forbidden, response.status)
    }

    @Test
    fun `valid webhook secret returns ok`() = testApplication {
        val config = BillingConfig(provider = "fake", webhookSecret = "super-secret")
        val providerRegistry = BillingProviderRegistry(listOf(FakeBillingProvider()))
        val billingService = BillingService(
            provider = FakeBillingProvider(),
            invoiceRepository = mockk(relaxed = true),
            paymentRepository = mockk(relaxed = true)
        )

        application {
            routing {
                billingWebhookRoutes(config, providerRegistry, billingService)
            }
        }

        val response = client.post("/api/billing/webhook") {
            headers { append("X-Billing-Webhook-Secret", "super-secret") }
        }
        assertEquals(HttpStatusCode.OK, response.status)
    }
}
