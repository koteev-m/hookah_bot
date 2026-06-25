package com.hookah.platform.backend.location

import java.net.URLEncoder
import java.nio.charset.StandardCharsets

private val russianCountryPrefixes = listOf("Россия, ", "Российская Федерация, ")

data class VenueLocationDisplay(
    val name: String,
    val countryCode: String? = null,
    val city: String? = null,
    val address: String? = null,
    val formattedAddress: String? = null,
    val latitude: Double? = null,
    val longitude: Double? = null,
)

fun formatVenueDisplayAddress(location: VenueLocationDisplay): String? {
    val formatted = location.formattedAddress?.trim().orEmpty().ifBlank { null }
    if (formatted != null) {
        return if (location.countryCode.equals("RU", ignoreCase = true)) {
            russianCountryPrefixes.fold(formatted) { current, prefix ->
                current.removePrefix(prefix)
            }.trim().ifBlank { formatted }
        } else {
            formatted
        }
    }

    val country = location.countryCode?.trim().orEmpty().uppercase().ifBlank { null }
    val city = location.city?.trim().orEmpty().ifBlank { null }
    val address = location.address?.trim().orEmpty().ifBlank { null }
    val parts =
        buildList {
            if (country != null && country != "RU") add(country)
            if (city != null) add(city)
            if (address != null && (city == null || !address.contains(city, ignoreCase = true))) add(address)
        }
    return parts.joinToString(", ").ifBlank { null }
}

fun buildYandexVenueRouteUrl(location: VenueLocationDisplay): String {
    val lat = location.latitude
    val lon = location.longitude
    if (lat != null && lon != null && lat in -90.0..90.0 && lon in -180.0..180.0) {
        return "https://yandex.ru/maps/?rtext=~$lat,$lon&rtt=auto"
    }

    val address = formatVenueDisplayAddress(location) ?: "Адрес уточняется"
    val query = URLEncoder.encode("${location.name}, $address", StandardCharsets.UTF_8)
    return "https://yandex.ru/maps/?text=$query"
}
