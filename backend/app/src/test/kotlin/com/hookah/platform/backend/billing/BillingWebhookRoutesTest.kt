package com.hookah.platform.backend.billing

import com.hookah.platform.backend.api.ForbiddenException
import io.ktor.client.request.headers
import io.ktor.client.request.post
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.install
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.response.respond
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import io.mockk.mockk
import kotlin.test.Test
import kotlin.test.assertEquals

class BillingWebhookRoutesTest {
    @Test
    fun `missing webhook secret returns forbidden`() = testApplication {
        val config = BillingConfig(
            provider = "fake",
            webhookSecret = "super-secret",
            webhookIpAllowlist = null,
            webhookIpAllowlistUseXForwardedFor = false
        )
        val providerRegistry = BillingProviderRegistry(listOf(FakeBillingProvider()))
        val billingService = BillingService(
            provider = FakeBillingProvider(),
            invoiceRepository = mockk(relaxed = true),
            paymentRepository = mockk(relaxed = true)
        )

        application {
            install(StatusPages) {
                exception<ForbiddenException> { call, _ ->
                    call.respond(HttpStatusCode.Forbidden)
                }
            }
            routing {
                billingWebhookRoutes(config, providerRegistry, billingService)
            }
        }

        val response = client.post("/api/billing/webhook")
        assertEquals(HttpStatusCode.Forbidden, response.status)
    }

    @Test
    fun `invalid webhook secret returns forbidden`() = testApplication {
        val config = BillingConfig(
            provider = "fake",
            webhookSecret = "super-secret",
            webhookIpAllowlist = null,
            webhookIpAllowlistUseXForwardedFor = false
        )
        val providerRegistry = BillingProviderRegistry(listOf(FakeBillingProvider()))
        val billingService = BillingService(
            provider = FakeBillingProvider(),
            invoiceRepository = mockk(relaxed = true),
            paymentRepository = mockk(relaxed = true)
        )

        application {
            install(StatusPages) {
                exception<ForbiddenException> { call, _ ->
                    call.respond(HttpStatusCode.Forbidden)
                }
            }
            routing {
                billingWebhookRoutes(config, providerRegistry, billingService)
            }
        }

        val response = client.post("/api/billing/webhook") {
            headers { append("X-Webhook-Secret-Token", "wrong-secret") }
        }
        assertEquals(HttpStatusCode.Forbidden, response.status)
    }

    @Test
    fun `valid webhook secret returns ok`() = testApplication {
        val config = BillingConfig(
            provider = "fake",
            webhookSecret = "super-secret",
            webhookIpAllowlist = null,
            webhookIpAllowlistUseXForwardedFor = false
        )
        val providerRegistry = BillingProviderRegistry(listOf(FakeBillingProvider()))
        val billingService = BillingService(
            provider = FakeBillingProvider(),
            invoiceRepository = mockk(relaxed = true),
            paymentRepository = mockk(relaxed = true)
        )

        application {
            install(StatusPages) {
                exception<ForbiddenException> { call, _ ->
                    call.respond(HttpStatusCode.Forbidden)
                }
            }
            routing {
                billingWebhookRoutes(config, providerRegistry, billingService)
            }
        }

        val response = client.post("/api/billing/webhook") {
            headers { append("X-Webhook-Secret-Token", "super-secret") }
        }
        assertEquals(HttpStatusCode.OK, response.status)
    }

    @Test
    fun `allowlist allows forwarded ip`() = testApplication {
        val config = BillingConfig(
            provider = "fake",
            webhookSecret = "super-secret",
            webhookIpAllowlist = com.hookah.platform.backend.security.IpAllowlist.parse("203.0.113.10"),
            webhookIpAllowlistUseXForwardedFor = true
        )
        val providerRegistry = BillingProviderRegistry(listOf(FakeBillingProvider()))
        val billingService = BillingService(
            provider = FakeBillingProvider(),
            invoiceRepository = mockk(relaxed = true),
            paymentRepository = mockk(relaxed = true)
        )

        application {
            install(StatusPages) {
                exception<ForbiddenException> { call, _ ->
                    call.respond(HttpStatusCode.Forbidden)
                }
            }
            routing {
                billingWebhookRoutes(config, providerRegistry, billingService)
            }
        }

        val response = client.post("/api/billing/webhook") {
            headers {
                append("X-Webhook-Secret-Token", "super-secret")
                append("X-Forwarded-For", "203.0.113.10")
            }
        }
        assertEquals(HttpStatusCode.OK, response.status)
    }

    @Test
    fun `allowlist blocks forwarded ip not in allowlist`() = testApplication {
        val config = BillingConfig(
            provider = "fake",
            webhookSecret = "super-secret",
            webhookIpAllowlist = com.hookah.platform.backend.security.IpAllowlist.parse("203.0.113.10"),
            webhookIpAllowlistUseXForwardedFor = true
        )
        val providerRegistry = BillingProviderRegistry(listOf(FakeBillingProvider()))
        val billingService = BillingService(
            provider = FakeBillingProvider(),
            invoiceRepository = mockk(relaxed = true),
            paymentRepository = mockk(relaxed = true)
        )

        application {
            install(StatusPages) {
                exception<ForbiddenException> { call, _ ->
                    call.respond(HttpStatusCode.Forbidden)
                }
            }
            routing {
                billingWebhookRoutes(config, providerRegistry, billingService)
            }
        }

        val response = client.post("/api/billing/webhook") {
            headers {
                append("X-Webhook-Secret-Token", "super-secret")
                append("X-Forwarded-For", "203.0.113.11")
            }
        }
        assertEquals(HttpStatusCode.Forbidden, response.status)
    }
}
