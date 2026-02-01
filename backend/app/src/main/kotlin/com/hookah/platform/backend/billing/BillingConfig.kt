package com.hookah.platform.backend.billing

import io.ktor.server.config.ApplicationConfig
import java.util.Locale


data class BillingConfig(
    val provider: String,
    val webhookSecret: String?
) {
    val normalizedProvider: String = provider.trim().lowercase(Locale.ROOT)

    companion object {
        fun from(config: ApplicationConfig): BillingConfig {
            val provider = config.propertyOrNull("billing.provider")?.getString()
                ?.takeIf { it.isNotBlank() }
                ?: "fake"
            val webhookSecret = config.propertyOrNull("billing.webhookSecret")?.getString()
                ?.takeIf { it.isNotBlank() }
            return BillingConfig(provider = provider, webhookSecret = webhookSecret)
        }
    }
}
