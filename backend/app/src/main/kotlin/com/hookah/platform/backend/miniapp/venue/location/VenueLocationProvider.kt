package com.hookah.platform.backend.miniapp.venue.location

import com.hookah.platform.backend.telegram.sanitizeTelegramForLog
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.server.config.ApplicationConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.slf4j.LoggerFactory

private const val SUGGEST_URL = "https://suggest-maps.yandex.ru/v1/suggest"
private const val GEOCODER_URL = "https://geocode-maps.yandex.ru/v1"
private const val DEFAULT_TIMEOUT_MS = 2_500L
private const val MAX_PROVIDER_RESULTS = 7

data class VenueLocationProviderConfig(
    val enabled: Boolean,
    val apiKey: String?,
    val timeoutMs: Long,
) {
    companion object {
        fun from(config: ApplicationConfig): VenueLocationProviderConfig {
            val enabled = config.optionalBoolean("yandex.maps.geodata.enabled") ?: false
            val apiKey = config.optionalString("yandex.maps.geodata.apiKey")?.trim()?.takeIf { it.isNotBlank() }
            val timeoutMs =
                config.optionalLong("yandex.maps.geodata.timeoutMs")
                    ?.takeIf { it > 0 }
                    ?: DEFAULT_TIMEOUT_MS
            return VenueLocationProviderConfig(
                enabled = enabled,
                apiKey = apiKey,
                timeoutMs = timeoutMs,
            )
        }
    }
}

interface VenueLocationProvider {
    suspend fun suggest(request: VenueLocationSuggestProviderRequest): VenueLocationSuggestProviderResult

    suspend fun resolve(request: VenueLocationResolveProviderRequest): VenueLocationResolveProviderResult
}

data class VenueLocationSuggestProviderRequest(
    val kind: VenueLocationSuggestionKind,
    val query: String,
    val countryCode: String,
    val city: String?,
    val sessionToken: String?,
)

data class VenueLocationResolveProviderRequest(
    val providerUri: String?,
    val query: String?,
    val countryCode: String?,
    val city: String?,
)

enum class VenueLocationSuggestionKind {
    CITY,
    ADDRESS,
}

data class VenueLocationSuggestProviderResult(
    val items: List<VenueLocationSuggestionItem>,
    val unavailable: Boolean = false,
)

data class VenueLocationResolveProviderResult(
    val location: VenueLocationResolvedItem?,
    val unavailable: Boolean = false,
)

@Serializable
data class VenueLocationSuggestionItem(
    val id: String,
    val title: String,
    val subtitle: String? = null,
    val countryCode: String? = null,
    val city: String? = null,
    val address: String? = null,
    val formattedAddress: String? = null,
    val providerUri: String? = null,
)

@Serializable
data class VenueLocationResolvedItem(
    val countryCode: String? = null,
    val city: String? = null,
    val address: String? = null,
    val formattedAddress: String? = null,
    val latitude: Double? = null,
    val longitude: Double? = null,
)

class DisabledVenueLocationProvider : VenueLocationProvider {
    override suspend fun suggest(request: VenueLocationSuggestProviderRequest): VenueLocationSuggestProviderResult =
        VenueLocationSuggestProviderResult(items = emptyList(), unavailable = true)

    override suspend fun resolve(request: VenueLocationResolveProviderRequest): VenueLocationResolveProviderResult =
        VenueLocationResolveProviderResult(location = null, unavailable = true)
}

class YandexVenueLocationProvider(
    private val config: VenueLocationProviderConfig,
    private val httpClient: HttpClient,
    private val json: Json,
) : VenueLocationProvider {
    private val logger = LoggerFactory.getLogger(YandexVenueLocationProvider::class.java)

    override suspend fun suggest(request: VenueLocationSuggestProviderRequest): VenueLocationSuggestProviderResult {
        val apiKey = config.apiKey ?: return VenueLocationSuggestProviderResult(emptyList(), unavailable = true)
        return safeProviderCall(
            unavailable = VenueLocationSuggestProviderResult(emptyList(), unavailable = true),
            operation = "suggest",
        ) {
            val responseText =
                withTimeout(config.timeoutMs) {
                    httpClient.get(SUGGEST_URL) {
                        parameter("apikey", apiKey)
                        parameter("text", buildSuggestText(request))
                        parameter("lang", "ru_RU")
                        parameter("results", MAX_PROVIDER_RESULTS)
                        parameter("highlight", 0)
                        parameter("countries", request.countryCode.lowercase())
                        parameter(
                            "types",
                            if (request.kind == VenueLocationSuggestionKind.CITY) "locality" else "house",
                        )
                        parameter("print_address", 1)
                        parameter("attrs", "uri")
                        request.sessionToken?.takeIf { it.isNotBlank() }?.let { parameter("sessiontoken", it) }
                    }.body<String>()
                }
            val root = json.parseToJsonElement(responseText).jsonObject
            val items =
                root["results"]
                    ?.jsonArrayOrNull()
                    ?.mapNotNull { element -> mapSuggestion(element, request) }
                    .orEmpty()
            VenueLocationSuggestProviderResult(items = items)
        }
    }

    override suspend fun resolve(request: VenueLocationResolveProviderRequest): VenueLocationResolveProviderResult {
        val apiKey = config.apiKey ?: return VenueLocationResolveProviderResult(null, unavailable = true)
        val uri = request.providerUri?.trim()?.takeIf { it.isNotBlank() }
        val geocode = buildGeocodeQuery(request)
        if (uri == null && geocode == null) {
            return VenueLocationResolveProviderResult(location = null)
        }
        return safeProviderCall(
            unavailable = VenueLocationResolveProviderResult(null, unavailable = true),
            operation = "resolve",
        ) {
            val responseText =
                withTimeout(config.timeoutMs) {
                    httpClient.get(GEOCODER_URL) {
                        parameter("apikey", apiKey)
                        parameter("lang", "ru_RU")
                        parameter("format", "json")
                        parameter("results", 1)
                        if (uri != null) {
                            parameter("uri", uri)
                        } else {
                            parameter("geocode", geocode)
                        }
                    }.body<String>()
                }
            val root = json.parseToJsonElement(responseText).jsonObject
            VenueLocationResolveProviderResult(location = mapResolvedLocation(root))
        }
    }

    private suspend fun <T> safeProviderCall(
        unavailable: T,
        operation: String,
        block: suspend () -> T,
    ): T =
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: HttpRequestTimeoutException) {
            logger.warn("Yandex geodata {} timed out", operation)
            unavailable
        } catch (e: Exception) {
            logger.warn("Yandex geodata {} failed: {}", operation, sanitizeTelegramForLog(e.message))
            unavailable
        }

    private fun buildSuggestText(request: VenueLocationSuggestProviderRequest): String =
        if (request.kind == VenueLocationSuggestionKind.ADDRESS && !request.city.isNullOrBlank()) {
            "${request.city.trim()}, ${request.query.trim()}"
        } else {
            request.query.trim()
        }

    private fun buildGeocodeQuery(request: VenueLocationResolveProviderRequest): String? {
        val parts =
            listOfNotNull(
                request.countryCode?.trim()?.uppercase()?.takeIf { it.isNotBlank() },
                request.city?.trim()?.takeIf { it.isNotBlank() },
                request.query?.trim()?.takeIf { it.isNotBlank() },
            )
        return parts.joinToString(", ").ifBlank { null }
    }

    private fun mapSuggestion(
        element: JsonElement,
        request: VenueLocationSuggestProviderRequest,
    ): VenueLocationSuggestionItem? {
        val obj = element.jsonObjectOrNull() ?: return null
        val title = obj.stringAt("title", "text") ?: return null
        val subtitle = obj.stringAt("subtitle", "text")
        val formattedAddress = obj.stringAt("address", "formatted_address")
        val components = obj["address"]?.jsonObjectOrNull()?.get("component")?.jsonArrayOrNull().orEmpty()
        val locality = components.firstComponentName("LOCALITY", "locality")
        val street = components.firstComponentName("STREET", "street")
        val house = components.firstComponentName("HOUSE", "house")
        val providerUri = obj["uri"]?.jsonPrimitiveOrNull()?.content
        val address =
            when {
                request.kind == VenueLocationSuggestionKind.CITY -> null
                street != null && house != null -> "$street, $house"
                street != null -> street
                else -> title
            }
        return VenueLocationSuggestionItem(
            id = providerUri ?: formattedAddress ?: title,
            title = title,
            subtitle = subtitle,
            countryCode = request.countryCode.uppercase(),
            city = locality ?: if (request.kind == VenueLocationSuggestionKind.CITY) title else request.city,
            address = address,
            formattedAddress = formattedAddress ?: listOfNotNull(subtitle, title).joinToString(", ").ifBlank { null },
            providerUri = providerUri,
        )
    }

    private fun mapResolvedLocation(root: JsonObject): VenueLocationResolvedItem? {
        val geoObject =
            root["response"]
                ?.jsonObjectOrNull()
                ?.get("GeoObjectCollection")
                ?.jsonObjectOrNull()
                ?.get("featureMember")
                ?.jsonArrayOrNull()
                ?.firstOrNull()
                ?.jsonObjectOrNull()
                ?.get("GeoObject")
                ?.jsonObjectOrNull()
                ?: return null
        val metadata =
            geoObject["metaDataProperty"]
                ?.jsonObjectOrNull()
                ?.get("GeocoderMetaData")
                ?.jsonObjectOrNull()
        val address = metadata?.get("Address")?.jsonObjectOrNull()
        val components = address?.get("Components")?.jsonArrayOrNull().orEmpty()
        val countryCode = address?.stringAt("country_code")
        val formatted = address?.stringAt("formatted") ?: metadata?.stringAt("text") ?: geoObject.stringAt("name")
        val city = components.firstComponentName("locality")
        val street = components.firstComponentName("street")
        val house = components.firstComponentName("house")
        val normalizedAddress =
            when {
                street != null && house != null -> "$street, $house"
                street != null -> street
                else -> geoObject.stringAt("name")
            }
        val point = geoObject.stringAt("Point", "pos")?.split(Regex("\\s+")).orEmpty()
        val lon = point.getOrNull(0)?.toDoubleOrNull()
        val lat = point.getOrNull(1)?.toDoubleOrNull()
        return VenueLocationResolvedItem(
            countryCode = countryCode?.uppercase(),
            city = city,
            address = normalizedAddress,
            formattedAddress = formatted,
            latitude = lat?.takeIf { it in -90.0..90.0 },
            longitude = lon?.takeIf { it in -180.0..180.0 },
        )
    }
}

fun createVenueLocationProvider(
    config: ApplicationConfig,
    httpClient: HttpClient,
    json: Json,
): VenueLocationProvider {
    val providerConfig = VenueLocationProviderConfig.from(config)
    if (!providerConfig.enabled || providerConfig.apiKey == null) {
        return DisabledVenueLocationProvider()
    }
    return YandexVenueLocationProvider(providerConfig, httpClient, json)
}

private fun ApplicationConfig.optionalString(path: String): String? =
    runCatching { property(path).getString() }.getOrNull()

private fun ApplicationConfig.optionalBoolean(path: String): Boolean? =
    optionalString(path)?.trim()?.lowercase()?.let {
        when (it) {
            "true" -> true
            "false" -> false
            else -> null
        }
    }

private fun ApplicationConfig.optionalLong(path: String): Long? = optionalString(path)?.trim()?.toLongOrNull()

private fun JsonElement.jsonObjectOrNull(): JsonObject? = this as? JsonObject

private fun JsonElement.jsonArrayOrNull(): JsonArray? = this as? JsonArray

private fun JsonElement.jsonPrimitiveOrNull() = runCatching { jsonPrimitive }.getOrNull()

private fun JsonObject.stringAt(vararg path: String): String? {
    var current: JsonElement = this
    path.forEach { key ->
        current = current.jsonObjectOrNull()?.get(key) ?: return null
    }
    return current.jsonPrimitiveOrNull()?.content?.trim()?.takeIf { it.isNotBlank() }
}

private fun List<JsonElement>.firstComponentName(vararg kinds: String): String? {
    val expected = kinds.map { it.lowercase() }.toSet()
    return firstNotNullOfOrNull { component ->
        val obj = component.jsonObjectOrNull() ?: return@firstNotNullOfOrNull null
        val componentKinds =
            obj["kind"]
                ?.let { kind ->
                    kind.jsonArrayOrNull()?.mapNotNull { it.jsonPrimitiveOrNull()?.content }
                        ?: listOfNotNull(kind.jsonPrimitiveOrNull()?.content)
                }
                .orEmpty()
                .map { it.lowercase() }
        if (componentKinds.any { it in expected }) obj.stringAt("name") else null
    }
}
