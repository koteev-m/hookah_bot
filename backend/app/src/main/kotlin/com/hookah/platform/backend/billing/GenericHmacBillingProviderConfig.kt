package com.hookah.platform.backend.billing

import io.ktor.server.config.ApplicationConfig

data class GenericHmacBillingProviderConfig(
    val checkoutBaseUrl: String?,
    val merchantId: String?,
    val checkoutReturnUrl: String?,
    val signingSecret: String?,
    val signatureHeader: String,
) {
    fun validateRequired() {
        require(!checkoutBaseUrl.isNullOrBlank()) { "billing.generic.checkoutBaseUrl must be configured" }
        require(!signingSecret.isNullOrBlank()) { "billing.generic.signingSecret must be configured" }
    }

    companion object {
        fun from(config: ApplicationConfig): GenericHmacBillingProviderConfig {
            return GenericHmacBillingProviderConfig(
                checkoutBaseUrl = config.propertyOrNull("billing.generic.checkoutBaseUrl")?.getString()?.trim(),
                merchantId = config.propertyOrNull("billing.generic.merchantId")?.getString()?.trim(),
                checkoutReturnUrl = config.propertyOrNull("billing.generic.checkoutReturnUrl")?.getString()?.trim(),
                signingSecret = config.propertyOrNull("billing.generic.signingSecret")?.getString()?.trim(),
                signatureHeader =
                    config.propertyOrNull("billing.generic.signatureHeader")?.getString()?.trim()
                        ?.takeIf { it.isNotEmpty() }
                        ?: "X-Billing-Signature",
            )
        }
    }
}
