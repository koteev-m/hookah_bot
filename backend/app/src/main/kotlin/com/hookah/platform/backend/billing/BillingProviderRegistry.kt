package com.hookah.platform.backend.billing

import java.util.Locale

class BillingProviderRegistry(providers: List<BillingProvider>) {
    private val providersByName = providers.associateBy { it.providerName().lowercase(Locale.ROOT) }

    fun resolve(name: String): BillingProvider? {
        val normalized = name.trim().lowercase(Locale.ROOT)
        return providersByName[normalized]
    }
}
