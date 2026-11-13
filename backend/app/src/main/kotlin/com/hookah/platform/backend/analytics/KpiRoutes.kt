package com.hookah.platform.backend.analytics

import com.hookah.platform.backend.api.InvalidInputException
import com.hookah.platform.backend.miniapp.venue.requireUserId
import com.hookah.platform.backend.miniapp.venue.requireVenueId
import com.hookah.platform.backend.platform.PlatformConfig
import com.hookah.platform.backend.platform.requirePlatformOwner
import io.ktor.http.HttpHeaders
import io.ktor.http.Parameters
import io.ktor.server.application.call
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.encodeToJsonElement

private val kpiResponseJson = Json { encodeDefaults = true }

internal fun Route.kpiRoutes(
    repository: KpiRepository,
    platformConfig: PlatformConfig,
) {
    get("/venue/{venueId}/stats/v1") {
        val user = call.requireUserId()
        val venue = call.requireVenueId()
        call.response.headers.append(HttpHeaders.CacheControl, "no-store")
        call.respond(
            kpiResponseJson.encodeToJsonElement(
                repository.venue(
                    venue,
                    user,
                    call.request.queryParameters.kpiRequest(false),
                ),
            ),
        )
    }
    get("/platform/analytics/v1") {
        call.requirePlatformOwner(platformConfig)
        call.response.headers.append(HttpHeaders.CacheControl, "no-store")
        call.respond(
            kpiResponseJson.encodeToJsonElement(repository.platform(call.request.queryParameters.kpiRequest(true))),
        )
    }
}

internal fun Parameters.kpiRequest(platform: Boolean): KpiRequest {
    val allowed =
        if (platform) {
            setOf(
                "from",
                "to",
                "section",
            )
        } else {
            setOf(
                "from", "to", "section", "tableId", "search", "category",
                "categoryKey", "expandedCategoryKey", "currency", "sort", "page",
            )
        }
    if (names().any { it !in allowed || getAll(it)?.size != 1 }) {
        throw InvalidInputException(
            "ANALYTICS_UNSUPPORTED_FILTER",
        )
    }
    val from = get("from") ?: throw InvalidInputException("ANALYTICS_FROM_REQUIRED")
    val to = get("to") ?: throw InvalidInputException("ANALYTICS_TO_REQUIRED")
    val section = get("section") ?: "all"
    val sections =
        if (platform) {
            setOf("all", "venues", "subscriptions", "invoices", "onboarding", "support")
        } else {
            setOf("all", "orders", "finance", "bookings", "seatings", "calls", "products", "additions")
        }
    if (section !in sections) throw InvalidInputException("ANALYTICS_INVALID_SECTION")
    val table =
        get("tableId")?.let {
                raw ->
            raw.toLongOrNull()?.takeIf { it > 0 } ?: throw InvalidInputException("ANALYTICS_INVALID_TABLE")
        }
    if (table != null && section !in setOf("orders", "bookings", "seatings", "calls")) {
        throw InvalidInputException("ANALYTICS_TABLE_FILTER_REQUIRES_OPERATIONAL_SECTION")
    }
    val productFilters = setOf("search", "category", "categoryKey", "expandedCategoryKey", "currency", "sort", "page")
    if (section != "products" &&
        names().any {
            it in productFilters
        }
    ) {
        throw InvalidInputException("ANALYTICS_FILTER_NOT_APPLICABLE")
    }
    val search = get("search")?.trim()?.takeIf { it.isNotEmpty() }
    val category = get("category")
    val currency = get("currency")
    val sort = get("sort") ?: "popular"
    val page = get("page")?.toIntOrNull() ?: if (get("page") == null) 1 else 0
    val invalidCurrency = currency != null && currency != "UNKNOWN" && !Regex("[A-Z]{3}").matches(currency)
    if ((search?.length ?: 0) > 100 || (category?.length ?: 0) > 100 ||
        invalidCurrency || sort !in setOf("popular", "rare", "amount") ||
        page !in 1..100000 || (sort == "amount" && currency == null)
    ) {
        throw InvalidInputException("ANALYTICS_INVALID_PRODUCT_FILTER")
    }
    val categoryKey = get("categoryKey")
    val expandedCategoryKey = get("expandedCategoryKey")
    if (listOfNotNull(categoryKey, expandedCategoryKey).any {
            it != "uncategorized" && (it.toLongOrNull()?.takeIf { id -> id > 0 }?.toString() != it)
        } || (categoryKey != null && category != null)
    ) {
        throw InvalidInputException("ANALYTICS_INVALID_PRODUCT_FILTER")
    }
    return KpiRequest(
        from, to, section, table, search, category, currency, sort, page, categoryKey, expandedCategoryKey,
    )
}
