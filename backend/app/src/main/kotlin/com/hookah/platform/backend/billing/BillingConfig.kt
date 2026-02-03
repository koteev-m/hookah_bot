package com.hookah.platform.backend.billing

import io.ktor.server.config.ApplicationConfig
import java.util.Locale
import com.hookah.platform.backend.security.IpAllowlist


data class BillingConfig(
    val provider: String,
    val webhookSecret: String,
    val webhookIpAllowlist: IpAllowlist?
) {
    val normalizedProvider: String = provider.trim().lowercase(Locale.ROOT)

    companion object {
        fun from(config: ApplicationConfig, appEnv: String): BillingConfig {
            val provider = config.propertyOrNull("billing.provider")?.getString()
                ?.takeIf { it.isNotBlank() }
                ?: "fake"
            val webhookSecret = config.propertyOrNull("billing.webhookSecret")?.getString()
                ?.trim()
                ?.takeIf { it.isNotBlank() }
                ?: if (appEnv == "dev" || appEnv == "test") "dev-billing-webhook-secret" else null
            if (appEnv == "prod" && webhookSecret.isNullOrBlank()) {
                error("billing.webhookSecret must be configured for env=$appEnv")
            }
            val webhookIpAllowlist = IpAllowlist.parse(
                config.propertyOrNull("billing.webhookIpAllowlist")?.getString()
            )
            return BillingConfig(
                provider = provider,
                webhookSecret = webhookSecret ?: "dev-billing-webhook-secret",
                webhookIpAllowlist = webhookIpAllowlist
            )
        }
    }
}
