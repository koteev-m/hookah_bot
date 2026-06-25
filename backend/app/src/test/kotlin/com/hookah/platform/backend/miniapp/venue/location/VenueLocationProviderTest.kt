package com.hookah.platform.backend.miniapp.venue.location

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.server.config.MapApplicationConfig
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class VenueLocationProviderTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `disabled integration works with no keys`() =
        runBlocking {
            val calls = mutableListOf<String>()
            val client =
                HttpClient(
                    MockEngine { request ->
                        calls += request.url.encodedPath
                        respondJson("{}")
                    },
                )
            val provider =
                createVenueLocationProvider(
                    MapApplicationConfig("yandex.maps.geodata.enabled" to "false"),
                    client,
                    json,
                )

            assertIs<DisabledVenueLocationProvider>(provider)
            assertTrue(provider.suggest(citySuggestRequest()).unavailable)
            assertTrue(provider.resolve(addressResolveRequest()).unavailable)
            assertEquals(emptyList(), calls)
        }

    @Test
    fun `geosuggest uses its own configured key`() =
        runBlocking {
            val apiKeys = mutableListOf<String?>()
            val client =
                HttpClient(
                    MockEngine { request ->
                        apiKeys += request.url.parameters["apikey"]
                        respondJson(geosuggestResponse)
                    },
                )
            val provider = yandexProvider(client)

            val result = provider.suggest(citySuggestRequest())

            assertFalse(result.unavailable)
            assertEquals(listOf<String?>("geosuggest-test-key"), apiKeys)
            assertEquals("Москва", result.items.single().city)
            assertFalse(result.toString().contains("geosuggest-test-key"))
            assertFalse(result.toString().contains("geocoder-test-key"))
        }

    @Test
    fun `geocoder uses its own configured key`() =
        runBlocking {
            val apiKeys = mutableListOf<String?>()
            val client =
                HttpClient(
                    MockEngine { request ->
                        apiKeys += request.url.parameters["apikey"]
                        respondJson(geocoderResponse)
                    },
                )
            val provider = yandexProvider(client)

            val result = provider.resolve(addressResolveRequest())

            assertFalse(result.unavailable)
            assertEquals(listOf<String?>("geocoder-test-key"), apiKeys)
            assertEquals(55.7522, result.location?.latitude)
            assertEquals(37.6156, result.location?.longitude)
            assertFalse(result.toString().contains("geosuggest-test-key"))
            assertFalse(result.toString().contains("geocoder-test-key"))
        }

    @Test
    fun `missing Geosuggest key affects only suggestions`() =
        runBlocking {
            val apiKeys = mutableListOf<String?>()
            val client =
                HttpClient(
                    MockEngine { request ->
                        apiKeys += request.url.parameters["apikey"]
                        respondJson(geocoderResponse)
                    },
                )
            val provider =
                yandexProvider(
                    client = client,
                    geosuggestApiKey = null,
                    geocoderApiKey = "geocoder-test-key",
                )

            val suggest = provider.suggest(citySuggestRequest())
            val resolve = provider.resolve(addressResolveRequest())

            assertTrue(suggest.unavailable)
            assertFalse(resolve.unavailable)
            assertEquals(listOf<String?>("geocoder-test-key"), apiKeys)
        }

    @Test
    fun `missing Geocoder key affects only resolve`() =
        runBlocking {
            val apiKeys = mutableListOf<String?>()
            val client =
                HttpClient(
                    MockEngine { request ->
                        apiKeys += request.url.parameters["apikey"]
                        respondJson(geosuggestResponse)
                    },
                )
            val provider =
                yandexProvider(
                    client = client,
                    geosuggestApiKey = "geosuggest-test-key",
                    geocoderApiKey = null,
                )

            val suggest = provider.suggest(citySuggestRequest())
            val resolve = provider.resolve(addressResolveRequest())

            assertFalse(suggest.unavailable)
            assertTrue(resolve.unavailable)
            assertEquals(listOf<String?>("geosuggest-test-key"), apiKeys)
        }

    @Test
    fun `enabled provider starts without keys and returns unavailable per operation`() =
        runBlocking {
            val calls = mutableListOf<String>()
            val client =
                HttpClient(
                    MockEngine { request ->
                        calls += request.url.encodedPath
                        respondJson("{}")
                    },
                )
            val provider =
                createVenueLocationProvider(
                    MapApplicationConfig("yandex.maps.geodata.enabled" to "true"),
                    client,
                    json,
                )

            assertIs<YandexVenueLocationProvider>(provider)
            assertTrue(provider.suggest(citySuggestRequest()).unavailable)
            assertTrue(provider.resolve(addressResolveRequest()).unavailable)
            assertEquals(emptyList(), calls)
        }

    @Test
    fun `provider config reads separate key paths`() {
        val config =
            VenueLocationProviderConfig.from(
                MapApplicationConfig(
                    "yandex.maps.geodata.enabled" to "true",
                    "yandex.maps.geodata.geosuggestApiKey" to " geosuggest-test-key ",
                    "yandex.maps.geodata.geocoderApiKey" to " geocoder-test-key ",
                    "yandex.maps.geodata.timeoutMs" to "1500",
                ),
            )

        assertTrue(config.enabled)
        assertEquals("geosuggest-test-key", config.geosuggestApiKey)
        assertEquals("geocoder-test-key", config.geocoderApiKey)
        assertEquals(1500L, config.timeoutMs)
    }

    private fun yandexProvider(
        client: HttpClient,
        geosuggestApiKey: String? = "geosuggest-test-key",
        geocoderApiKey: String? = "geocoder-test-key",
    ): YandexVenueLocationProvider =
        YandexVenueLocationProvider(
            config =
                VenueLocationProviderConfig(
                    enabled = true,
                    geosuggestApiKey = geosuggestApiKey,
                    geocoderApiKey = geocoderApiKey,
                    timeoutMs = 1_000,
                ),
            httpClient = client,
            json = json,
        )

    private fun citySuggestRequest(): VenueLocationSuggestProviderRequest =
        VenueLocationSuggestProviderRequest(
            kind = VenueLocationSuggestionKind.CITY,
            query = "Мо",
            countryCode = "RU",
            city = null,
            sessionToken = "test-session",
        )

    private fun addressResolveRequest(): VenueLocationResolveProviderRequest =
        VenueLocationResolveProviderRequest(
            providerUri = "ymapsbm1://geo?data=test",
            query = "Новый Арбат, 24",
            countryCode = "RU",
            city = "Москва",
        )

    private fun MockRequestHandleScope.respondJson(content: String) =
        respond(
            content = content,
            status = HttpStatusCode.OK,
            headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
        )

    private val geosuggestResponse =
        """
        {
          "results": [
            {
              "title": { "text": "Москва" },
              "subtitle": { "text": "Россия" },
              "address": {
                "formatted_address": "Россия, Москва",
                "component": [
                  { "name": "Москва", "kind": ["LOCALITY"] }
                ]
              },
              "uri": "ymapsbm1://geo?data=city"
            }
          ]
        }
        """.trimIndent()

    private val geocoderResponse =
        """
        {
          "response": {
            "GeoObjectCollection": {
              "featureMember": [
                {
                  "GeoObject": {
                    "name": "Новый Арбат, 24",
                    "Point": { "pos": "37.6156 55.7522" },
                    "metaDataProperty": {
                      "GeocoderMetaData": {
                        "text": "Россия, Москва, Новый Арбат, 24",
                        "Address": {
                          "country_code": "RU",
                          "formatted": "Россия, Москва, Новый Арбат, 24",
                          "Components": [
                            { "kind": "locality", "name": "Москва" },
                            { "kind": "street", "name": "Новый Арбат" },
                            { "kind": "house", "name": "24" }
                          ]
                        }
                      }
                    }
                  }
                }
              ]
            }
          }
        }
        """.trimIndent()
}
