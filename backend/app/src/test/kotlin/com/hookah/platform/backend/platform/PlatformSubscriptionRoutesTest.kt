package com.hookah.platform.backend.platform

import com.hookah.platform.backend.api.ApiErrorCodes
import com.hookah.platform.backend.miniapp.session.SessionTokenConfig
import com.hookah.platform.backend.miniapp.session.SessionTokenService
import com.hookah.platform.backend.miniapp.venue.VenueStatus
import com.hookah.platform.backend.module
import com.hookah.platform.backend.test.assertApiErrorEnvelope
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.headers
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.config.MapApplicationConfig
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import java.sql.DriverManager
import java.sql.Statement
import java.time.LocalDate
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals

class PlatformSubscriptionRoutesTest {
    private val json = Json { ignoreUnknownKeys = true }
    private val appEnv = "test"

    @Test
    fun `non owner cannot access subscription endpoints`() =
        testApplication {
            val jdbcUrl = buildJdbcUrl("platform-subscription-rbac")
            val ownerId = 1101L
            val config = buildConfig(jdbcUrl, ownerId)

            environment { this.config = config }
            application { module() }

            client.get("/health")

            val venueId = seedVenue(jdbcUrl)
            val token = issueToken(config, userId = 2202L)

            val getResponse =
                client.get("/api/platform/venues/$venueId/subscription") {
                    headers { append(HttpHeaders.Authorization, "Bearer $token") }
                }

            assertEquals(HttpStatusCode.Forbidden, getResponse.status)
            assertApiErrorEnvelope(getResponse, ApiErrorCodes.FORBIDDEN)

            val putResponse =
                client.put("/api/platform/venues/$venueId/subscription") {
                    headers { append(HttpHeaders.Authorization, "Bearer $token") }
                    contentType(ContentType.Application.Json)
                    setBody(
                        json.encodeToString(
                            PlatformSubscriptionSettingsUpdateRequest.serializer(),
                            PlatformSubscriptionSettingsUpdateRequest(),
                        ),
                    )
                }

            assertEquals(HttpStatusCode.Forbidden, putResponse.status)
            assertApiErrorEnvelope(putResponse, ApiErrorCodes.FORBIDDEN)

            val scheduleResponse =
                client.put("/api/platform/venues/$venueId/price-schedule") {
                    headers { append(HttpHeaders.Authorization, "Bearer $token") }
                    contentType(ContentType.Application.Json)
                    setBody(
                        json.encodeToString(
                            PlatformPriceScheduleUpdateRequest.serializer(),
                            PlatformPriceScheduleUpdateRequest(items = emptyList()),
                        ),
                    )
                }

            assertEquals(HttpStatusCode.Forbidden, scheduleResponse.status)
            assertApiErrorEnvelope(scheduleResponse, ApiErrorCodes.FORBIDDEN)
        }

    @Test
    fun `rejects invalid trial and paid dates`() =
        testApplication {
            val jdbcUrl = buildJdbcUrl("platform-subscription-dates")
            val ownerId = 3303L
            val config = buildConfig(jdbcUrl, ownerId)

            environment { this.config = config }
            application { module() }

            client.get("/health")

            val venueId = seedVenue(jdbcUrl)
            val token = issueToken(config, userId = ownerId)

            val response =
                client.put("/api/platform/venues/$venueId/subscription") {
                    headers { append(HttpHeaders.Authorization, "Bearer $token") }
                    contentType(ContentType.Application.Json)
                    setBody(
                        json.encodeToString(
                            PlatformSubscriptionSettingsUpdateRequest.serializer(),
                            PlatformSubscriptionSettingsUpdateRequest(
                                trialEndDate = "2024-01-10",
                                paidStartDate = "2024-01-01",
                            ),
                        ),
                    )
                }

            assertEquals(HttpStatusCode.BadRequest, response.status)
            assertApiErrorEnvelope(response, ApiErrorCodes.INVALID_INPUT)
        }

    @Test
    fun `rejects invalid pricing`() =
        testApplication {
            val jdbcUrl = buildJdbcUrl("platform-subscription-prices")
            val ownerId = 4404L
            val config = buildConfig(jdbcUrl, ownerId)

            environment { this.config = config }
            application { module() }

            client.get("/health")

            val venueId = seedVenue(jdbcUrl)
            val token = issueToken(config, userId = ownerId)

            val settingsResponse =
                client.put("/api/platform/venues/$venueId/subscription") {
                    headers { append(HttpHeaders.Authorization, "Bearer $token") }
                    contentType(ContentType.Application.Json)
                    setBody(
                        json.encodeToString(
                            PlatformSubscriptionSettingsUpdateRequest.serializer(),
                            PlatformSubscriptionSettingsUpdateRequest(basePriceMinor = 0),
                        ),
                    )
                }

            assertEquals(HttpStatusCode.BadRequest, settingsResponse.status)
            assertApiErrorEnvelope(settingsResponse, ApiErrorCodes.INVALID_INPUT)

            val scheduleResponse =
                client.put("/api/platform/venues/$venueId/price-schedule") {
                    headers { append(HttpHeaders.Authorization, "Bearer $token") }
                    contentType(ContentType.Application.Json)
                    setBody(
                        json.encodeToString(
                            PlatformPriceScheduleUpdateRequest.serializer(),
                            PlatformPriceScheduleUpdateRequest(
                                items =
                                    listOf(
                                        PlatformPriceScheduleItemInput(
                                            effectiveFrom = "2024-02-01",
                                            priceMinor = 0,
                                            currency = "RUB",
                                        ),
                                    ),
                            ),
                        ),
                    )
                }

            assertEquals(HttpStatusCode.BadRequest, scheduleResponse.status)
            assertApiErrorEnvelope(scheduleResponse, ApiErrorCodes.INVALID_INPUT)
        }

    @Test
    fun `effective price prefers override then schedule then base`() =
        testApplication {
            val jdbcUrl = buildJdbcUrl("platform-subscription-effective")
            val ownerId = 5505L
            val config = buildConfig(jdbcUrl, ownerId)

            environment { this.config = config }
            application { module() }

            client.get("/health")

            val venueId = seedVenue(jdbcUrl)
            val token = issueToken(config, userId = ownerId)

            val baseResponse =
                client.put("/api/platform/venues/$venueId/subscription") {
                    headers { append(HttpHeaders.Authorization, "Bearer $token") }
                    contentType(ContentType.Application.Json)
                    setBody(
                        json.encodeToString(
                            PlatformSubscriptionSettingsUpdateRequest.serializer(),
                            PlatformSubscriptionSettingsUpdateRequest(basePriceMinor = 1000, currency = "RUB"),
                        ),
                    )
                }

            assertEquals(HttpStatusCode.OK, baseResponse.status)

            val baseSnapshot = getSubscription(client, venueId, token)
            assertEquals(1000, baseSnapshot.effectivePriceToday?.priceMinor)

            val today = LocalDate.now()
            val scheduleResponse =
                client.put("/api/platform/venues/$venueId/price-schedule") {
                    headers { append(HttpHeaders.Authorization, "Bearer $token") }
                    contentType(ContentType.Application.Json)
                    setBody(
                        json.encodeToString(
                            PlatformPriceScheduleUpdateRequest.serializer(),
                            PlatformPriceScheduleUpdateRequest(
                                items =
                                    listOf(
                                        PlatformPriceScheduleItemInput(
                                            effectiveFrom = today.minusDays(1).toString(),
                                            priceMinor = 2000,
                                            currency = "RUB",
                                        ),
                                        PlatformPriceScheduleItemInput(
                                            effectiveFrom = today.plusDays(3).toString(),
                                            priceMinor = 3000,
                                            currency = "RUB",
                                        ),
                                    ),
                            ),
                        ),
                    )
                }

            assertEquals(HttpStatusCode.OK, scheduleResponse.status)

            val scheduledSnapshot = getSubscription(client, venueId, token)
            assertEquals(2000, scheduledSnapshot.effectivePriceToday?.priceMinor)

            val overrideResponse =
                client.put("/api/platform/venues/$venueId/subscription") {
                    headers { append(HttpHeaders.Authorization, "Bearer $token") }
                    contentType(ContentType.Application.Json)
                    setBody(
                        json.encodeToString(
                            PlatformSubscriptionSettingsUpdateRequest.serializer(),
                            PlatformSubscriptionSettingsUpdateRequest(priceOverrideMinor = 4000),
                        ),
                    )
                }

            assertEquals(HttpStatusCode.OK, overrideResponse.status)

            val overrideSnapshot = getSubscription(client, venueId, token)
            assertEquals(4000, overrideSnapshot.effectivePriceToday?.priceMinor)
            assertEquals("RUB", overrideSnapshot.effectivePriceToday?.currency)
        }

    private suspend fun getSubscription(
        client: HttpClient,
        venueId: Long,
        token: String,
    ): PlatformSubscriptionSettingsResponse {
        val response =
            client.get("/api/platform/venues/$venueId/subscription") {
                headers { append(HttpHeaders.Authorization, "Bearer $token") }
            }
        assertEquals(HttpStatusCode.OK, response.status)
        return json.decodeFromString(PlatformSubscriptionSettingsResponse.serializer(), response.bodyAsText())
    }

    private fun buildJdbcUrl(prefix: String): String {
        val dbName = "$prefix-${UUID.randomUUID()}"
        return "jdbc:h2:mem:$dbName;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1"
    }

    private fun buildConfig(
        jdbcUrl: String,
        ownerId: Long,
    ): MapApplicationConfig {
        return MapApplicationConfig(
            "app.env" to appEnv,
            "api.session.jwtSecret" to "test-secret",
            "db.jdbcUrl" to jdbcUrl,
            "db.user" to "sa",
            "db.password" to "",
            "platform.ownerUserId" to ownerId.toString(),
            "venue.staffInviteSecretPepper" to "invite-pepper",
        )
    }

    private fun issueToken(
        config: MapApplicationConfig,
        userId: Long,
    ): String {
        val service = SessionTokenService(SessionTokenConfig.from(config, appEnv))
        return service.issueToken(userId).token
    }

    private fun seedVenue(jdbcUrl: String): Long {
        DriverManager.getConnection(jdbcUrl, "sa", "").use { connection ->
            connection.prepareStatement(
                """
                INSERT INTO venues (name, city, address, status)
                VALUES ('Seed', 'City', 'Address', ?)
                """.trimIndent(),
                Statement.RETURN_GENERATED_KEYS,
            ).use { statement ->
                statement.setString(1, VenueStatus.DRAFT.dbValue)
                statement.executeUpdate()
                statement.generatedKeys.use { rs ->
                    if (rs.next()) return rs.getLong(1)
                }
            }
        }
        error("Failed to insert venue")
    }
}
