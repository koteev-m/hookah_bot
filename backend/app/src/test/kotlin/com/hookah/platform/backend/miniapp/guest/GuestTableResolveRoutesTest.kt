package com.hookah.platform.backend.miniapp.guest

import com.hookah.platform.backend.ModuleOverrides
import com.hookah.platform.backend.api.ApiErrorCodes
import com.hookah.platform.backend.miniapp.guest.api.TableResolveResponse
import com.hookah.platform.backend.miniapp.guest.api.TableRestoreResponse
import com.hookah.platform.backend.miniapp.guest.api.TableSessionEndResponse
import com.hookah.platform.backend.miniapp.session.SessionTokenConfig
import com.hookah.platform.backend.miniapp.session.SessionTokenService
import com.hookah.platform.backend.miniapp.venue.VenueStatus
import com.hookah.platform.backend.module
import com.hookah.platform.backend.moduleWithOverrides
import com.hookah.platform.backend.test.assertApiErrorEnvelope
import io.ktor.client.request.get
import io.ktor.client.request.headers
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.http.encodeURLParameter
import io.ktor.server.config.MapApplicationConfig
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import java.sql.DriverManager
import java.sql.Statement
import java.sql.Timestamp
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

class GuestTableResolveRoutesTest {
    private val json = Json { ignoreUnknownKeys = true }
    private val appEnv = "test"

    @Test
    fun `missing authorization returns unauthorized`() =
        testApplication {
            val config =
                MapApplicationConfig(
                    "app.env" to appEnv,
                    "api.session.jwtSecret" to "test-secret",
                    "db.jdbcUrl" to "",
                )

            environment { this.config = config }
            application { module() }

            val response = client.get("/api/guest/table/resolve?tableToken=any-token")

            assertEquals(HttpStatusCode.Unauthorized, response.status)
            assertApiErrorEnvelope(response, ApiErrorCodes.UNAUTHORIZED)
        }

    @Test
    fun `invalid token format returns invalid input without resolve`() =
        testApplication {
            var resolveCalls = 0
            val config =
                MapApplicationConfig(
                    "app.env" to appEnv,
                    "api.session.jwtSecret" to "test-secret",
                    "db.jdbcUrl" to "",
                )

            environment { this.config = config }
            application {
                moduleWithOverrides(
                    ModuleOverrides(
                        tableTokenResolver = {
                            resolveCalls += 1
                            fail("tableTokenResolver must not be called for invalid tokens")
                        },
                    ),
                )
            }

            val token = issueToken(config)
            val invalidTokens =
                listOf(
                    "   bad token  ",
                    "русский",
                    "x".repeat(129),
                )

            invalidTokens.forEach { invalid ->
                val encoded = invalid.encodeURLParameter()
                val response =
                    client.get("/api/guest/table/resolve?tableToken=$encoded") {
                        headers { append(HttpHeaders.Authorization, "Bearer $token") }
                    }

                assertEquals(HttpStatusCode.BadRequest, response.status)
                assertApiErrorEnvelope(response, ApiErrorCodes.INVALID_INPUT)
            }

            assertEquals(0, resolveCalls)
        }

    @Test
    fun `missing table token returns invalid input without resolve`() =
        testApplication {
            var resolveCalls = 0
            val config =
                MapApplicationConfig(
                    "app.env" to appEnv,
                    "api.session.jwtSecret" to "test-secret",
                    "db.jdbcUrl" to "",
                )

            environment { this.config = config }
            application {
                moduleWithOverrides(
                    ModuleOverrides(
                        tableTokenResolver = {
                            resolveCalls += 1
                            fail("tableTokenResolver must not be called for missing tokens")
                        },
                    ),
                )
            }

            val token = issueToken(config)
            val response =
                client.get("/api/guest/table/resolve") {
                    headers { append(HttpHeaders.Authorization, "Bearer $token") }
                }

            assertEquals(HttpStatusCode.BadRequest, response.status)
            assertApiErrorEnvelope(response, ApiErrorCodes.INVALID_INPUT)
            assertEquals(0, resolveCalls)
        }

    @Test
    fun `blank table token returns invalid input without resolve`() =
        testApplication {
            var resolveCalls = 0
            val config =
                MapApplicationConfig(
                    "app.env" to appEnv,
                    "api.session.jwtSecret" to "test-secret",
                    "db.jdbcUrl" to "",
                )

            environment { this.config = config }
            application {
                moduleWithOverrides(
                    ModuleOverrides(
                        tableTokenResolver = {
                            resolveCalls += 1
                            fail("tableTokenResolver must not be called for blank tokens")
                        },
                    ),
                )
            }

            val token = issueToken(config)
            val encoded = "   ".encodeURLParameter()
            val response =
                client.get("/api/guest/table/resolve?tableToken=$encoded") {
                    headers { append(HttpHeaders.Authorization, "Bearer $token") }
                }

            assertEquals(HttpStatusCode.BadRequest, response.status)
            assertApiErrorEnvelope(response, ApiErrorCodes.INVALID_INPUT)
            assertEquals(0, resolveCalls)
        }

    @Test
    fun `unknown token returns not found`() =
        testApplication {
            val jdbcUrl = buildJdbcUrl("guest-table-unknown")
            val config = buildConfig(jdbcUrl)

            environment { this.config = config }
            application { module() }

            client.get("/health")
            val token = issueToken(config)

            val response =
                client.get("/api/guest/table/resolve?tableToken=missing-token") {
                    headers { append(HttpHeaders.Authorization, "Bearer $token") }
                }

            assertEquals(HttpStatusCode.NotFound, response.status)
            assertApiErrorEnvelope(response, ApiErrorCodes.NOT_FOUND)
        }

    @Test
    fun `known token for suspended venue returns not found`() =
        testApplication {
            val jdbcUrl = buildJdbcUrl("guest-table-suspended")
            val config = buildConfig(jdbcUrl)

            environment { this.config = config }
            application { module() }

            client.get("/health")

            val tokenValue = "suspended-token"
            val venueId = seedVenue(jdbcUrl, VenueStatus.SUSPENDED.dbValue)
            val tableId = seedTable(jdbcUrl, venueId, 7)
            seedTableToken(jdbcUrl, tableId, tokenValue)
            seedSubscription(jdbcUrl, venueId, "active")

            val token = issueToken(config)

            val response =
                client.get("/api/guest/table/resolve?tableToken=$tokenValue&resolveMode=create") {
                    headers { append(HttpHeaders.Authorization, "Bearer $token") }
                }

            assertEquals(HttpStatusCode.NotFound, response.status)
            assertApiErrorEnvelope(response, ApiErrorCodes.NOT_FOUND)
        }

    @Test
    fun `known token for published venue returns available`() =
        testApplication {
            val jdbcUrl = buildJdbcUrl("guest-table-trial")
            val config = buildConfig(jdbcUrl)

            environment { this.config = config }
            application { module() }

            client.get("/health")

            val tokenValue = "published-token"
            val venueId = seedVenue(jdbcUrl, VenueStatus.PUBLISHED.dbValue)
            val tableId = seedTable(jdbcUrl, venueId, 3)
            seedTableToken(jdbcUrl, tableId, tokenValue)

            val token = issueToken(config)

            val response =
                client.get("/api/guest/table/resolve?tableToken=$tokenValue&resolveMode=create") {
                    headers { append(HttpHeaders.Authorization, "Bearer $token") }
                }

            assertEquals(HttpStatusCode.OK, response.status)
            val payload = json.decodeFromString(TableResolveResponse.serializer(), response.bodyAsText())
            assertEquals(venueId, payload.venueId)
            assertEquals("Venue", payload.venueName)
            assertEquals(tableId, payload.tableId)
            assertEquals("3", payload.tableNumber)
            assertEquals(VenueStatus.PUBLISHED.dbValue, payload.venueStatus)
            assertTrue(payload.tableSessionId > 0)
            assertEquals("ACTIVE", payload.tableSessionStatus)
            assertEquals(true, payload.tableSessionActive)
            assertNull(payload.tableSessionInactiveReason)
            assertEquals("trial", payload.subscriptionStatus)
            assertEquals(true, payload.available)
            assertEquals(1, countTableSessions(jdbcUrl))
            assertNull(payload.unavailableReason)
        }

    @Test
    fun `platform owner without confirmed telegram context is denied without creating authoritative state`() =
        testApplication {
            val jdbcUrl = buildJdbcUrl("guest-table-platform-no-context")
            val config = buildConfig(jdbcUrl, platformOwnerUserId = PLATFORM_OWNER_USER_ID)

            environment { this.config = config }
            application { module() }

            client.get("/health")
            createTelegramChatContextFixture(jdbcUrl)

            val tokenValue = "platform-no-context-token"
            val venueId = seedVenue(jdbcUrl, VenueStatus.PUBLISHED.dbValue)
            val tableId = seedTable(jdbcUrl, venueId, 40)
            seedTableToken(jdbcUrl, tableId, tokenValue)
            seedSubscription(jdbcUrl, venueId, "ACTIVE")
            seedUser(jdbcUrl, PLATFORM_OWNER_USER_ID)
            val token = issueToken(config, PLATFORM_OWNER_USER_ID)

            val response =
                client.get("/api/guest/table/resolve?tableToken=$tokenValue&resolveMode=create") {
                    headers { append(HttpHeaders.Authorization, "Bearer $token") }
                }

            assertEquals(HttpStatusCode.Forbidden, response.status)
            assertApiErrorEnvelope(response, ApiErrorCodes.FORBIDDEN)
            val body = response.bodyAsText()
            assertTrue(body.contains(PLATFORM_RECONFIRM_MESSAGE))
            assertTrue(!body.contains(tokenValue))
            assertTrue(!body.contains("venueId"))
            assertTrue(!body.contains("tableId"))
            assertTrue(!body.contains("Platform", ignoreCase = true))
            assertEquals(0, countTableSessions(jdbcUrl))
            assertEquals(0, countPersonalTabs(jdbcUrl, PLATFORM_OWNER_USER_ID))
        }

    @Test
    fun `platform owner with matching confirmed telegram context reuses session and creates personal tab`() =
        testApplication {
            val jdbcUrl = buildJdbcUrl("guest-table-platform-confirmed")
            val config = buildConfig(jdbcUrl, platformOwnerUserId = PLATFORM_OWNER_USER_ID)

            environment { this.config = config }
            application { module() }

            client.get("/health")
            createTelegramChatContextFixture(jdbcUrl)

            val tokenValue = "platform-confirmed-token"
            val venueId = seedVenue(jdbcUrl, VenueStatus.PUBLISHED.dbValue)
            val tableId = seedTable(jdbcUrl, venueId, 41)
            seedTableToken(jdbcUrl, tableId, tokenValue)
            seedSubscription(jdbcUrl, venueId, "ACTIVE")
            seedUser(jdbcUrl, PLATFORM_OWNER_USER_ID)
            val tableSessionId =
                seedTableSession(
                    jdbcUrl = jdbcUrl,
                    venueId = venueId,
                    tableId = tableId,
                    status = "ACTIVE",
                    expiresAt = Instant.now().plus(1, ChronoUnit.HOURS),
                    endedAt = null,
                )
            seedChatContext(
                jdbcUrl = jdbcUrl,
                chatId = PLATFORM_OWNER_CHAT_ID,
                userId = PLATFORM_OWNER_USER_ID,
                venueId = venueId,
                tableId = tableId,
                tableToken = tokenValue,
                updatedAt = Instant.now(),
            )
            val token = issueToken(config, PLATFORM_OWNER_USER_ID)

            val response =
                client.get("/api/guest/table/resolve?tableToken=$tokenValue&resolveMode=create") {
                    headers { append(HttpHeaders.Authorization, "Bearer $token") }
                }

            assertEquals(HttpStatusCode.OK, response.status)
            val payload = json.decodeFromString(TableResolveResponse.serializer(), response.bodyAsText())
            assertEquals(tableSessionId, payload.tableSessionId)
            assertEquals(true, payload.tableSessionActive)
            assertEquals(1, countTableSessions(jdbcUrl))
            assertEquals(1, countPersonalTabs(jdbcUrl, PLATFORM_OWNER_USER_ID, tableSessionId))
        }

    @Test
    fun `platform owner with mismatched confirmed token is denied without touching session`() =
        testApplication {
            val jdbcUrl = buildJdbcUrl("guest-table-platform-token-mismatch")
            val config = buildConfig(jdbcUrl, platformOwnerUserId = PLATFORM_OWNER_USER_ID)

            environment { this.config = config }
            application { module() }

            client.get("/health")
            createTelegramChatContextFixture(jdbcUrl)

            val requestedToken = "platform-requested-token"
            val venueId = seedVenue(jdbcUrl, VenueStatus.PUBLISHED.dbValue)
            val tableId = seedTable(jdbcUrl, venueId, 42)
            seedTableToken(jdbcUrl, tableId, requestedToken)
            seedSubscription(jdbcUrl, venueId, "ACTIVE")
            seedUser(jdbcUrl, PLATFORM_OWNER_USER_ID)
            val tableSessionId =
                seedTableSession(
                    jdbcUrl = jdbcUrl,
                    venueId = venueId,
                    tableId = tableId,
                    status = "ACTIVE",
                    expiresAt = Instant.now().plus(1, ChronoUnit.HOURS),
                    endedAt = null,
                )
            seedChatContext(
                jdbcUrl = jdbcUrl,
                chatId = PLATFORM_OWNER_CHAT_ID,
                userId = PLATFORM_OWNER_USER_ID,
                venueId = venueId,
                tableId = tableId,
                tableToken = "different-confirmed-token",
                updatedAt = Instant.now(),
            )
            val before = fetchTableSessionTiming(jdbcUrl, tableSessionId)
            val token = issueToken(config, PLATFORM_OWNER_USER_ID)

            val response =
                client.get("/api/guest/table/resolve?tableToken=$requestedToken&resolveMode=create") {
                    headers { append(HttpHeaders.Authorization, "Bearer $token") }
                }

            assertEquals(HttpStatusCode.Forbidden, response.status)
            assertApiErrorEnvelope(response, ApiErrorCodes.FORBIDDEN)
            assertEquals(before, fetchTableSessionTiming(jdbcUrl, tableSessionId))
            assertEquals(0, countPersonalTabs(jdbcUrl, PLATFORM_OWNER_USER_ID))
        }

    @Test
    fun `platform owner with mismatched confirmed venue and table is denied without mutation`() =
        testApplication {
            val jdbcUrl = buildJdbcUrl("guest-table-platform-identity-mismatch")
            val config = buildConfig(jdbcUrl, platformOwnerUserId = PLATFORM_OWNER_USER_ID)

            environment { this.config = config }
            application { module() }

            client.get("/health")
            createTelegramChatContextFixture(jdbcUrl)

            val requestedToken = "platform-identity-token"
            val venueId = seedVenue(jdbcUrl, VenueStatus.PUBLISHED.dbValue)
            val tableId = seedTable(jdbcUrl, venueId, 43)
            seedTableToken(jdbcUrl, tableId, requestedToken)
            seedSubscription(jdbcUrl, venueId, "ACTIVE")
            val differentVenueId = seedVenue(jdbcUrl, VenueStatus.PUBLISHED.dbValue)
            val differentTableId = seedTable(jdbcUrl, differentVenueId, 44)
            seedUser(jdbcUrl, PLATFORM_OWNER_USER_ID)
            val tableSessionId =
                seedTableSession(
                    jdbcUrl = jdbcUrl,
                    venueId = venueId,
                    tableId = tableId,
                    status = "ACTIVE",
                    expiresAt = Instant.now().plus(1, ChronoUnit.HOURS),
                    endedAt = null,
                )
            seedChatContext(
                jdbcUrl = jdbcUrl,
                chatId = PLATFORM_OWNER_CHAT_ID,
                userId = PLATFORM_OWNER_USER_ID,
                venueId = differentVenueId,
                tableId = differentTableId,
                tableToken = requestedToken,
                updatedAt = Instant.now(),
            )
            val before = fetchTableSessionTiming(jdbcUrl, tableSessionId)
            val token = issueToken(config, PLATFORM_OWNER_USER_ID)

            val response =
                client.get("/api/guest/table/resolve?tableToken=$requestedToken&resolveMode=create") {
                    headers { append(HttpHeaders.Authorization, "Bearer $token") }
                }

            assertEquals(HttpStatusCode.Forbidden, response.status)
            assertApiErrorEnvelope(response, ApiErrorCodes.FORBIDDEN)
            assertEquals(before, fetchTableSessionTiming(jdbcUrl, tableSessionId))
            assertEquals(0, countPersonalTabs(jdbcUrl, PLATFORM_OWNER_USER_ID))
        }

    @Test
    fun `platform owner exit marker denies create and explicit old session without clearing exit`() =
        testApplication {
            val jdbcUrl = buildJdbcUrl("guest-table-platform-old-entry")
            val config = buildConfig(jdbcUrl, platformOwnerUserId = PLATFORM_OWNER_USER_ID)

            environment { this.config = config }
            application { module() }

            client.get("/health")
            createTelegramChatContextFixture(jdbcUrl)

            val tokenValue = "platform-old-entry-token"
            val venueId = seedVenue(jdbcUrl, VenueStatus.PUBLISHED.dbValue)
            val tableId = seedTable(jdbcUrl, venueId, 45)
            seedTableToken(jdbcUrl, tableId, tokenValue)
            seedSubscription(jdbcUrl, venueId, "ACTIVE")
            seedUser(jdbcUrl, PLATFORM_OWNER_USER_ID)
            val tableSessionId =
                seedTableSession(
                    jdbcUrl = jdbcUrl,
                    venueId = venueId,
                    tableId = tableId,
                    status = "ACTIVE",
                    expiresAt = Instant.now().plus(1, ChronoUnit.HOURS),
                    endedAt = null,
                )
            seedChatContext(
                jdbcUrl = jdbcUrl,
                chatId = PLATFORM_OWNER_CHAT_ID,
                userId = PLATFORM_OWNER_USER_ID,
                venueId = venueId,
                tableId = tableId,
                tableToken = tokenValue,
                updatedAt = Instant.now(),
            )
            seedUserExit(jdbcUrl, PLATFORM_OWNER_USER_ID, tableSessionId)
            val before = fetchTableSessionTiming(jdbcUrl, tableSessionId)
            val token = issueToken(config, PLATFORM_OWNER_USER_ID)

            val createResponse =
                client.get("/api/guest/table/resolve?tableToken=$tokenValue&resolveMode=create") {
                    headers { append(HttpHeaders.Authorization, "Bearer $token") }
                }
            val explicitResponse =
                client.get("/api/guest/table/resolve?tableToken=$tokenValue&tableSessionId=$tableSessionId") {
                    headers { append(HttpHeaders.Authorization, "Bearer $token") }
                }

            assertEquals(HttpStatusCode.Forbidden, createResponse.status)
            assertApiErrorEnvelope(createResponse, ApiErrorCodes.FORBIDDEN)
            assertEquals(HttpStatusCode.Forbidden, explicitResponse.status)
            assertApiErrorEnvelope(explicitResponse, ApiErrorCodes.FORBIDDEN)
            assertEquals(before, fetchTableSessionTiming(jdbcUrl, tableSessionId))
            assertEquals(1, countUserTableSessionExits(jdbcUrl, PLATFORM_OWNER_USER_ID, tableSessionId))
            assertEquals(0, countPersonalTabs(jdbcUrl, PLATFORM_OWNER_USER_ID))
        }

    @Test
    fun `platform owner old entry is denied across table bound guest APIs without mutation`() =
        testApplication {
            val jdbcUrl = buildJdbcUrl("guest-table-platform-api-old-entry")
            val config = buildConfig(jdbcUrl, platformOwnerUserId = PLATFORM_OWNER_USER_ID)

            environment { this.config = config }
            application { module() }

            client.get("/health")
            createTelegramChatContextFixture(jdbcUrl)

            val tokenValue = "platform-api-old-entry-token"
            val venueId = seedVenue(jdbcUrl, VenueStatus.PUBLISHED.dbValue)
            val tableId = seedTable(jdbcUrl, venueId, 49)
            seedTableToken(jdbcUrl, tableId, tokenValue)
            seedSubscription(jdbcUrl, venueId, "ACTIVE")
            seedUser(jdbcUrl, PLATFORM_OWNER_USER_ID)
            val tableSessionId =
                seedTableSession(
                    jdbcUrl = jdbcUrl,
                    venueId = venueId,
                    tableId = tableId,
                    status = "ACTIVE",
                    expiresAt = Instant.now().plus(1, ChronoUnit.HOURS),
                    endedAt = null,
                )
            seedChatContext(
                jdbcUrl = jdbcUrl,
                chatId = PLATFORM_OWNER_CHAT_ID,
                userId = PLATFORM_OWNER_USER_ID,
                venueId = venueId,
                tableId = tableId,
                tableToken = tokenValue,
                updatedAt = Instant.now(),
            )
            seedUserExit(jdbcUrl, PLATFORM_OWNER_USER_ID, tableSessionId)
            val before = fetchTableSessionTiming(jdbcUrl, tableSessionId)
            val token = issueToken(config, PLATFORM_OWNER_USER_ID)

            val activeOrderResponse =
                client.get("/api/guest/order/active?tableToken=$tokenValue") {
                    headers { append(HttpHeaders.Authorization, "Bearer $token") }
                }
            val tabsResponse =
                client.get("/api/guest/tabs?table_session_id=$tableSessionId") {
                    headers { append(HttpHeaders.Authorization, "Bearer $token") }
                }
            val staffCallResponse =
                client.post("/api/guest/staff-call") {
                    headers { append(HttpHeaders.Authorization, "Bearer $token") }
                    contentType(ContentType.Application.Json)
                    setBody(
                        """{"tableToken":"$tokenValue","tableSessionId":$tableSessionId,"reason":"COME"}""",
                    )
                }

            listOf(activeOrderResponse, tabsResponse, staffCallResponse).forEach { response ->
                assertEquals(HttpStatusCode.Forbidden, response.status)
                assertApiErrorEnvelope(response, ApiErrorCodes.FORBIDDEN)
                val body = response.bodyAsText()
                assertTrue(body.contains(PLATFORM_RECONFIRM_MESSAGE))
                assertTrue(!body.contains(tokenValue))
                assertTrue(!body.contains("Platform", ignoreCase = true))
            }
            assertEquals(before, fetchTableSessionTiming(jdbcUrl, tableSessionId))
            assertEquals(1, countTableSessions(jdbcUrl))
            assertEquals(0, countPersonalTabs(jdbcUrl, PLATFORM_OWNER_USER_ID))
            assertEquals(0, countStaffCalls(jdbcUrl, PLATFORM_OWNER_USER_ID))
            assertEquals(1, countUserTableSessionExits(jdbcUrl, PLATFORM_OWNER_USER_ID, tableSessionId))
        }

    @Test
    fun `platform owner confirmed context remains allowed through active order route`() =
        testApplication {
            val jdbcUrl = buildJdbcUrl("guest-table-platform-api-confirmed")
            val config = buildConfig(jdbcUrl, platformOwnerUserId = PLATFORM_OWNER_USER_ID)

            environment { this.config = config }
            application { module() }

            client.get("/health")
            createTelegramChatContextFixture(jdbcUrl)

            val tokenValue = "platform-api-confirmed-token"
            val venueId = seedVenue(jdbcUrl, VenueStatus.PUBLISHED.dbValue)
            val tableId = seedTable(jdbcUrl, venueId, 50)
            seedTableToken(jdbcUrl, tableId, tokenValue)
            seedSubscription(jdbcUrl, venueId, "ACTIVE")
            seedUser(jdbcUrl, PLATFORM_OWNER_USER_ID)
            val tableSessionId =
                seedTableSession(
                    jdbcUrl = jdbcUrl,
                    venueId = venueId,
                    tableId = tableId,
                    status = "ACTIVE",
                    expiresAt = Instant.now().plus(1, ChronoUnit.HOURS),
                    endedAt = null,
                )
            seedChatContext(
                jdbcUrl = jdbcUrl,
                chatId = PLATFORM_OWNER_CHAT_ID,
                userId = PLATFORM_OWNER_USER_ID,
                venueId = venueId,
                tableId = tableId,
                tableToken = tokenValue,
                updatedAt = Instant.now(),
            )
            val before = fetchTableSessionTiming(jdbcUrl, tableSessionId) ?: error("Expected session")
            val token = issueToken(config, PLATFORM_OWNER_USER_ID)

            val response =
                client.get("/api/guest/order/active?tableToken=$tokenValue") {
                    headers { append(HttpHeaders.Authorization, "Bearer $token") }
                }

            assertEquals(HttpStatusCode.OK, response.status)
            val after = fetchTableSessionTiming(jdbcUrl, tableSessionId) ?: error("Expected session")
            assertTrue(after.lastActivityAt > before.lastActivityAt)
            assertEquals(1, countTableSessions(jdbcUrl))
            assertEquals(1, countPersonalTabs(jdbcUrl, PLATFORM_OWNER_USER_ID, tableSessionId))
            assertEquals(0, countUserTableSessionExits(jdbcUrl, PLATFORM_OWNER_USER_ID, tableSessionId))
        }

    @Test
    fun `platform owner stale confirmation epoch cannot attach to a newer session`() =
        testApplication {
            val jdbcUrl = buildJdbcUrl("guest-table-platform-stale-confirmation")
            val config = buildConfig(jdbcUrl, platformOwnerUserId = PLATFORM_OWNER_USER_ID)

            environment { this.config = config }
            application { module() }

            client.get("/health")
            createTelegramChatContextFixture(jdbcUrl)

            val now = Instant.now()
            val tokenValue = "platform-stale-confirmation-token"
            val venueId = seedVenue(jdbcUrl, VenueStatus.PUBLISHED.dbValue)
            val tableId = seedTable(jdbcUrl, venueId, 51)
            seedTableToken(jdbcUrl, tableId, tokenValue)
            seedSubscription(jdbcUrl, venueId, "ACTIVE")
            seedUser(jdbcUrl, PLATFORM_OWNER_USER_ID)
            seedChatContext(
                jdbcUrl = jdbcUrl,
                chatId = PLATFORM_OWNER_CHAT_ID,
                userId = PLATFORM_OWNER_USER_ID,
                venueId = venueId,
                tableId = tableId,
                tableToken = tokenValue,
                updatedAt = now.minus(10, ChronoUnit.MINUTES),
            )
            val tableSessionId =
                seedTableSession(
                    jdbcUrl = jdbcUrl,
                    venueId = venueId,
                    tableId = tableId,
                    status = "ACTIVE",
                    expiresAt = now.plus(1, ChronoUnit.HOURS),
                    endedAt = null,
                    startedAt = now.minus(5, ChronoUnit.MINUTES),
                )
            val before = fetchTableSessionTiming(jdbcUrl, tableSessionId)
            val token = issueToken(config, PLATFORM_OWNER_USER_ID)

            val response =
                client.get("/api/guest/order/active?tableToken=$tokenValue") {
                    headers { append(HttpHeaders.Authorization, "Bearer $token") }
                }

            assertEquals(HttpStatusCode.Forbidden, response.status)
            assertApiErrorEnvelope(response, ApiErrorCodes.FORBIDDEN)
            assertEquals(before, fetchTableSessionTiming(jdbcUrl, tableSessionId))
            assertEquals(0, countPersonalTabs(jdbcUrl, PLATFORM_OWNER_USER_ID))
        }

    @Test
    fun `platform owner session end clears confirmed context after token revoke`() =
        testApplication {
            val jdbcUrl = buildJdbcUrl("guest-table-platform-end-revoked-token")
            val config = buildConfig(jdbcUrl, platformOwnerUserId = PLATFORM_OWNER_USER_ID)
            var teardownCallbackIdentity: Pair<Long, Long>? = null

            environment { this.config = config }
            application {
                moduleWithOverrides(
                    ModuleOverrides(
                        afterPlatformGuestTeardown = { chatId, actorUserId ->
                            teardownCallbackIdentity = chatId to actorUserId
                        },
                    ),
                )
            }

            client.get("/health")
            createTelegramChatContextFixture(jdbcUrl)

            val tokenValue = "platform-end-revoked-token"
            val venueId = seedVenue(jdbcUrl, VenueStatus.PUBLISHED.dbValue)
            val tableId = seedTable(jdbcUrl, venueId, 52)
            seedTableToken(jdbcUrl, tableId, tokenValue)
            seedSubscription(jdbcUrl, venueId, "ACTIVE")
            seedUser(jdbcUrl, PLATFORM_OWNER_USER_ID)
            val tableSessionId =
                seedTableSession(
                    jdbcUrl = jdbcUrl,
                    venueId = venueId,
                    tableId = tableId,
                    status = "ACTIVE",
                    expiresAt = Instant.now().plus(1, ChronoUnit.HOURS),
                    endedAt = null,
                )
            seedTab(jdbcUrl, venueId, tableSessionId, PLATFORM_OWNER_USER_ID)
            seedChatContext(
                jdbcUrl = jdbcUrl,
                chatId = PLATFORM_OWNER_CHAT_ID,
                userId = PLATFORM_OWNER_USER_ID,
                venueId = venueId,
                tableId = tableId,
                tableToken = tokenValue,
                updatedAt = Instant.now(),
            )
            setTableTokenActive(jdbcUrl, tokenValue, active = false)
            val token = issueToken(config, PLATFORM_OWNER_USER_ID)

            val response =
                client.post("/api/guest/table/session/end") {
                    headers { append(HttpHeaders.Authorization, "Bearer $token") }
                    contentType(ContentType.Application.Json)
                    setBody("""{"tableToken":"$tokenValue","tableSessionId":$tableSessionId}""")
                }

            assertEquals(HttpStatusCode.OK, response.status)
            val payload = json.decodeFromString(TableSessionEndResponse.serializer(), response.bodyAsText())
            assertEquals(true, payload.ended)
            assertEquals(tableSessionId, payload.tableSessionId)
            assertEquals(0, countChatContexts(jdbcUrl, PLATFORM_OWNER_USER_ID))
            assertEquals(1, countUserTableSessionExits(jdbcUrl, PLATFORM_OWNER_USER_ID, tableSessionId))
            assertEquals(PLATFORM_OWNER_CHAT_ID to PLATFORM_OWNER_USER_ID, teardownCallbackIdentity)
        }

    @Test
    fun `platform owner restore without confirmed context returns empty without touching session`() =
        testApplication {
            val jdbcUrl = buildJdbcUrl("guest-table-platform-restore-guard")
            val config = buildConfig(jdbcUrl, platformOwnerUserId = PLATFORM_OWNER_USER_ID)

            environment { this.config = config }
            application { module() }

            client.get("/health")
            createTelegramChatContextFixture(jdbcUrl)

            val venueId = seedVenue(jdbcUrl, VenueStatus.PUBLISHED.dbValue)
            val tableId = seedTable(jdbcUrl, venueId, 46)
            seedTableToken(jdbcUrl, tableId, "platform-restore-guard-token")
            seedSubscription(jdbcUrl, venueId, "ACTIVE")
            seedUser(jdbcUrl, PLATFORM_OWNER_USER_ID)
            val tableSessionId =
                seedTableSession(
                    jdbcUrl = jdbcUrl,
                    venueId = venueId,
                    tableId = tableId,
                    status = "ACTIVE",
                    expiresAt = Instant.now().plus(1, ChronoUnit.HOURS),
                    endedAt = null,
                )
            val tabId = seedTab(jdbcUrl, venueId, tableSessionId, PLATFORM_OWNER_USER_ID)
            seedOrder(
                jdbcUrl = jdbcUrl,
                venueId = venueId,
                tableId = tableId,
                tableSessionId = tableSessionId,
                tabId = tabId,
                status = "ACTIVE",
                authorUserId = PLATFORM_OWNER_USER_ID,
            )
            val before = fetchTableSessionTiming(jdbcUrl, tableSessionId)
            val token = issueToken(config, PLATFORM_OWNER_USER_ID)

            val response =
                client.get("/api/guest/table/restore") {
                    headers { append(HttpHeaders.Authorization, "Bearer $token") }
                }

            assertEquals(HttpStatusCode.OK, response.status)
            val payload = json.decodeFromString(TableRestoreResponse.serializer(), response.bodyAsText())
            assertNull(payload.context)
            assertEquals(before, fetchTableSessionTiming(jdbcUrl, tableSessionId))
        }

    @Test
    fun `platform owner restore with matching confirmed context returns and touches same session`() =
        testApplication {
            val jdbcUrl = buildJdbcUrl("guest-table-platform-restore-confirmed")
            val config = buildConfig(jdbcUrl, platformOwnerUserId = PLATFORM_OWNER_USER_ID)

            environment { this.config = config }
            application { module() }

            client.get("/health")
            createTelegramChatContextFixture(jdbcUrl)

            val tokenValue = "platform-restore-confirmed-token"
            val venueId = seedVenue(jdbcUrl, VenueStatus.PUBLISHED.dbValue)
            val tableId = seedTable(jdbcUrl, venueId, 48)
            seedTableToken(jdbcUrl, tableId, tokenValue)
            seedSubscription(jdbcUrl, venueId, "ACTIVE")
            seedUser(jdbcUrl, PLATFORM_OWNER_USER_ID)
            val tableSessionId =
                seedTableSession(
                    jdbcUrl = jdbcUrl,
                    venueId = venueId,
                    tableId = tableId,
                    status = "ACTIVE",
                    expiresAt = Instant.now().plus(1, ChronoUnit.HOURS),
                    endedAt = null,
                )
            val tabId = seedTab(jdbcUrl, venueId, tableSessionId, PLATFORM_OWNER_USER_ID)
            seedOrder(
                jdbcUrl = jdbcUrl,
                venueId = venueId,
                tableId = tableId,
                tableSessionId = tableSessionId,
                tabId = tabId,
                status = "ACTIVE",
                authorUserId = PLATFORM_OWNER_USER_ID,
            )
            seedChatContext(
                jdbcUrl = jdbcUrl,
                chatId = PLATFORM_OWNER_CHAT_ID,
                userId = PLATFORM_OWNER_USER_ID,
                venueId = venueId,
                tableId = tableId,
                tableToken = tokenValue,
                updatedAt = Instant.now(),
            )
            val before = fetchTableSessionTiming(jdbcUrl, tableSessionId) ?: error("Expected session timing")
            val token = issueToken(config, PLATFORM_OWNER_USER_ID)

            val response =
                client.get("/api/guest/table/restore") {
                    headers { append(HttpHeaders.Authorization, "Bearer $token") }
                }

            assertEquals(HttpStatusCode.OK, response.status)
            val payload = json.decodeFromString(TableRestoreResponse.serializer(), response.bodyAsText())
            val context = payload.context ?: error("Expected confirmed Platform Guest context")
            assertEquals(tokenValue, context.tableToken)
            assertEquals(tableSessionId, context.tableSessionId)
            val after = fetchTableSessionTiming(jdbcUrl, tableSessionId) ?: error("Expected session timing")
            assertTrue(after.lastActivityAt > before.lastActivityAt)
            assertEquals(1, countTableSessions(jdbcUrl))
            assertEquals(1, countPersonalTabs(jdbcUrl, PLATFORM_OWNER_USER_ID, tableSessionId))
        }

    @Test
    fun `ordinary guest create remains allowed when a different actor is platform owner`() =
        testApplication {
            val jdbcUrl = buildJdbcUrl("guest-table-ordinary-with-platform-owner")
            val config = buildConfig(jdbcUrl, platformOwnerUserId = PLATFORM_OWNER_USER_ID)

            environment { this.config = config }
            application { module() }

            client.get("/health")
            createTelegramChatContextFixture(jdbcUrl)

            val tokenValue = "ordinary-with-platform-owner-token"
            val venueId = seedVenue(jdbcUrl, VenueStatus.PUBLISHED.dbValue)
            val tableId = seedTable(jdbcUrl, venueId, 47)
            seedTableToken(jdbcUrl, tableId, tokenValue)
            val token = issueToken(config, TELEGRAM_USER_ID)

            val response =
                client.get("/api/guest/table/resolve?tableToken=$tokenValue&resolveMode=create") {
                    headers { append(HttpHeaders.Authorization, "Bearer $token") }
                }

            assertEquals(HttpStatusCode.OK, response.status)
            val payload = json.decodeFromString(TableResolveResponse.serializer(), response.bodyAsText())
            assertEquals(true, payload.tableSessionActive)
            assertEquals(1, countTableSessions(jdbcUrl))
            assertEquals(1, countPersonalTabs(jdbcUrl, TELEGRAM_USER_ID, payload.tableSessionId))
        }

    @Test
    fun `restore returns latest active table context for authenticated tab member`() =
        testApplication {
            val jdbcUrl = buildJdbcUrl("guest-table-restore-active")
            val config = buildConfig(jdbcUrl)

            environment { this.config = config }
            application { module() }

            client.get("/health")

            val venueId = seedVenue(jdbcUrl, VenueStatus.PUBLISHED.dbValue)
            val tableId = seedTable(jdbcUrl, venueId, 9)
            seedTableToken(jdbcUrl, tableId, "restore-active-token")
            seedUser(jdbcUrl, TELEGRAM_USER_ID)
            val tableSessionId =
                seedTableSession(
                    jdbcUrl = jdbcUrl,
                    venueId = venueId,
                    tableId = tableId,
                    status = "ACTIVE",
                    expiresAt = Instant.now().plus(1, ChronoUnit.HOURS),
                    endedAt = null,
                    lastActivityAt = Instant.now().minus(1, ChronoUnit.MINUTES),
                )
            val tabId = seedTab(jdbcUrl, venueId, tableSessionId, TELEGRAM_USER_ID)
            seedOrder(jdbcUrl, venueId, tableId, tableSessionId, tabId, "ACTIVE")
            val token = issueToken(config)

            val response =
                client.get("/api/guest/table/restore") {
                    headers { append(HttpHeaders.Authorization, "Bearer $token") }
                }

            assertEquals(HttpStatusCode.OK, response.status)
            val payload = json.decodeFromString(TableRestoreResponse.serializer(), response.bodyAsText())
            val context = payload.context ?: error("Expected restored table context")
            assertEquals("restore-active-token", context.tableToken)
            assertEquals(tabId, context.tabId)
            assertEquals(venueId, context.venueId)
            assertEquals(tableId, context.tableId)
            assertEquals(tableSessionId, context.tableSessionId)
            assertEquals("9", context.tableNumber)
            assertEquals("ACTIVE", context.tableSessionStatus)
            assertEquals(true, context.tableSessionActive)
            assertEquals(true, context.available)
        }

    @Test
    fun `restore does not return another user's table context`() =
        testApplication {
            val jdbcUrl = buildJdbcUrl("guest-table-restore-other-user")
            val config = buildConfig(jdbcUrl)

            environment { this.config = config }
            application { module() }

            client.get("/health")

            val ownerUserId = TELEGRAM_USER_ID
            val anotherUserId = 789L
            val venueId = seedVenue(jdbcUrl, VenueStatus.PUBLISHED.dbValue)
            val tableId = seedTable(jdbcUrl, venueId, 10)
            seedTableToken(jdbcUrl, tableId, "restore-other-user-token")
            seedUser(jdbcUrl, ownerUserId)
            seedUser(jdbcUrl, anotherUserId)
            val tableSessionId =
                seedTableSession(
                    jdbcUrl = jdbcUrl,
                    venueId = venueId,
                    tableId = tableId,
                    status = "ACTIVE",
                    expiresAt = Instant.now().plus(1, ChronoUnit.HOURS),
                    endedAt = null,
                )
            val tabId = seedTab(jdbcUrl, venueId, tableSessionId, ownerUserId)
            seedOrder(jdbcUrl, venueId, tableId, tableSessionId, tabId, "ACTIVE")
            val token = issueToken(config, anotherUserId)

            val response =
                client.get("/api/guest/table/restore") {
                    headers { append(HttpHeaders.Authorization, "Bearer $token") }
                }

            assertEquals(HttpStatusCode.OK, response.status)
            val payload = json.decodeFromString(TableRestoreResponse.serializer(), response.bodyAsText())
            assertNull(payload.context)
        }

    @Test
    fun `restore skips session with only closed bill`() =
        testApplication {
            val jdbcUrl = buildJdbcUrl("guest-table-restore-closed")
            val config = buildConfig(jdbcUrl)

            environment { this.config = config }
            application { module() }

            client.get("/health")

            val venueId = seedVenue(jdbcUrl, VenueStatus.PUBLISHED.dbValue)
            val tableId = seedTable(jdbcUrl, venueId, 11)
            seedTableToken(jdbcUrl, tableId, "restore-closed-token")
            seedUser(jdbcUrl, TELEGRAM_USER_ID)
            val tableSessionId =
                seedTableSession(
                    jdbcUrl = jdbcUrl,
                    venueId = venueId,
                    tableId = tableId,
                    status = "ACTIVE",
                    expiresAt = Instant.now().plus(1, ChronoUnit.HOURS),
                    endedAt = null,
                )
            val tabId = seedTab(jdbcUrl, venueId, tableSessionId, TELEGRAM_USER_ID)
            seedOrder(jdbcUrl, venueId, tableId, tableSessionId, tabId, "CLOSED")
            val token = issueToken(config)

            val response =
                client.get("/api/guest/table/restore") {
                    headers { append(HttpHeaders.Authorization, "Bearer $token") }
                }

            assertEquals(HttpStatusCode.OK, response.status)
            val payload = json.decodeFromString(TableRestoreResponse.serializer(), response.bodyAsText())
            assertNull(payload.context)
        }

    @Test
    fun `restore picks latest active context deterministically`() =
        testApplication {
            val jdbcUrl = buildJdbcUrl("guest-table-restore-latest")
            val config = buildConfig(jdbcUrl)

            environment { this.config = config }
            application { module() }

            client.get("/health")

            seedUser(jdbcUrl, TELEGRAM_USER_ID)
            val firstVenueId = seedVenue(jdbcUrl, VenueStatus.PUBLISHED.dbValue)
            val firstTableId = seedTable(jdbcUrl, firstVenueId, 21)
            seedTableToken(jdbcUrl, firstTableId, "restore-first-token")
            val firstSessionId =
                seedTableSession(
                    jdbcUrl = jdbcUrl,
                    venueId = firstVenueId,
                    tableId = firstTableId,
                    status = "ACTIVE",
                    expiresAt = Instant.now().plus(1, ChronoUnit.HOURS),
                    endedAt = null,
                    lastActivityAt = Instant.now().minus(30, ChronoUnit.MINUTES),
                )
            val firstTabId = seedTab(jdbcUrl, firstVenueId, firstSessionId, TELEGRAM_USER_ID)
            seedOrder(
                jdbcUrl = jdbcUrl,
                venueId = firstVenueId,
                tableId = firstTableId,
                tableSessionId = firstSessionId,
                tabId = firstTabId,
                status = "ACTIVE",
                updatedAt = Instant.now().minus(30, ChronoUnit.MINUTES),
            )

            val secondVenueId = seedVenue(jdbcUrl, VenueStatus.PUBLISHED.dbValue)
            val secondTableId = seedTable(jdbcUrl, secondVenueId, 22)
            seedTableToken(jdbcUrl, secondTableId, "restore-second-token")
            val secondSessionId =
                seedTableSession(
                    jdbcUrl = jdbcUrl,
                    venueId = secondVenueId,
                    tableId = secondTableId,
                    status = "ACTIVE",
                    expiresAt = Instant.now().plus(1, ChronoUnit.HOURS),
                    endedAt = null,
                    lastActivityAt = Instant.now().minus(1, ChronoUnit.MINUTES),
                )
            val secondTabId = seedTab(jdbcUrl, secondVenueId, secondSessionId, TELEGRAM_USER_ID)
            seedOrder(
                jdbcUrl = jdbcUrl,
                venueId = secondVenueId,
                tableId = secondTableId,
                tableSessionId = secondSessionId,
                tabId = secondTabId,
                status = "ACTIVE",
                updatedAt = Instant.now().minus(1, ChronoUnit.MINUTES),
            )
            val token = issueToken(config)

            val response =
                client.get("/api/guest/table/restore") {
                    headers { append(HttpHeaders.Authorization, "Bearer $token") }
                }

            assertEquals(HttpStatusCode.OK, response.status)
            val payload = json.decodeFromString(TableRestoreResponse.serializer(), response.bodyAsText())
            val context = payload.context ?: error("Expected restored table context")
            assertEquals("restore-second-token", context.tableToken)
            assertEquals(secondTabId, context.tabId)
            assertEquals(secondSessionId, context.tableSessionId)
            assertEquals("22", context.tableNumber)
        }

    @Test
    fun `end table session with empty personal tab exits only current user and qr can reenter`() =
        testApplication {
            val jdbcUrl = buildJdbcUrl("guest-table-end-empty-tab")
            val config = buildConfig(jdbcUrl)

            environment { this.config = config }
            application { module() }

            client.get("/health")

            val guestA = TELEGRAM_USER_ID
            val guestB = 789L
            val tokenValue = "end-empty-token"
            val venueId = seedVenue(jdbcUrl, VenueStatus.PUBLISHED.dbValue)
            val tableId = seedTable(jdbcUrl, venueId, 31)
            seedTableToken(jdbcUrl, tableId, tokenValue)
            seedUser(jdbcUrl, guestA)
            seedUser(jdbcUrl, guestB)
            val tableSessionId =
                seedTableSession(
                    jdbcUrl = jdbcUrl,
                    venueId = venueId,
                    tableId = tableId,
                    status = "ACTIVE",
                    expiresAt = Instant.now().plus(1, ChronoUnit.HOURS),
                    endedAt = null,
                )
            seedTab(jdbcUrl, venueId, tableSessionId, guestA)
            val guestBTabId = seedTab(jdbcUrl, venueId, tableSessionId, guestB)
            val guestAToken = issueToken(config, guestA)
            val guestBToken = issueToken(config, guestB)

            val endResponse =
                client.post("/api/guest/table/session/end") {
                    headers { append(HttpHeaders.Authorization, "Bearer $guestAToken") }
                    contentType(ContentType.Application.Json)
                    setBody("""{"tableToken":"$tokenValue","tableSessionId":$tableSessionId}""")
                }

            assertEquals(HttpStatusCode.OK, endResponse.status)
            val endPayload = json.decodeFromString(TableSessionEndResponse.serializer(), endResponse.bodyAsText())
            assertEquals(true, endPayload.ended)
            assertEquals(tableSessionId, endPayload.tableSessionId)
            assertNull(endPayload.blockedReason)
            assertEquals(1, countUserTableSessionExits(jdbcUrl, guestA, tableSessionId))
            assertEquals(0, countUserTableSessionExits(jdbcUrl, guestB, tableSessionId))
            assertEquals("ACTIVE", fetchTableSessionStatus(jdbcUrl, tableSessionId))

            val guestARestore =
                client.get("/api/guest/table/restore") {
                    headers { append(HttpHeaders.Authorization, "Bearer $guestAToken") }
                }
            val guestARestorePayload =
                json.decodeFromString(
                    TableRestoreResponse.serializer(),
                    guestARestore.bodyAsText(),
                )
            assertNull(guestARestorePayload.context)

            val guestBRestore =
                client.get("/api/guest/table/restore") {
                    headers { append(HttpHeaders.Authorization, "Bearer $guestBToken") }
                }
            val guestBRestorePayload =
                json.decodeFromString(
                    TableRestoreResponse.serializer(),
                    guestBRestore.bodyAsText(),
                )
            val guestBContext = guestBRestorePayload.context ?: error("Expected guest B context to remain restorable")
            assertEquals(guestBTabId, guestBContext.tabId)
            assertEquals(tableSessionId, guestBContext.tableSessionId)

            val reenterResponse =
                client.get("/api/guest/table/resolve?tableToken=$tokenValue&resolveMode=create") {
                    headers { append(HttpHeaders.Authorization, "Bearer $guestAToken") }
                }

            assertEquals(HttpStatusCode.OK, reenterResponse.status)
            val reenterPayload = json.decodeFromString(TableResolveResponse.serializer(), reenterResponse.bodyAsText())
            assertEquals(tableSessionId, reenterPayload.tableSessionId)
            assertEquals(true, reenterPayload.tableSessionActive)
            assertEquals(0, countUserTableSessionExits(jdbcUrl, guestA, tableSessionId))
        }

    @Test
    fun `end table session returns not found when session belongs to another user at same table`() =
        testApplication {
            val jdbcUrl = buildJdbcUrl("guest-table-end-other-user")
            val config = buildConfig(jdbcUrl)

            environment { this.config = config }
            application { module() }

            client.get("/health")

            val guestA = TELEGRAM_USER_ID
            val guestB = 789L
            val tokenValue = "end-other-user-token"
            val venueId = seedVenue(jdbcUrl, VenueStatus.PUBLISHED.dbValue)
            val tableId = seedTable(jdbcUrl, venueId, 32)
            seedTableToken(jdbcUrl, tableId, tokenValue)
            seedUser(jdbcUrl, guestA)
            seedUser(jdbcUrl, guestB)
            val tableSessionId =
                seedTableSession(
                    jdbcUrl = jdbcUrl,
                    venueId = venueId,
                    tableId = tableId,
                    status = "ACTIVE",
                    expiresAt = Instant.now().plus(1, ChronoUnit.HOURS),
                    endedAt = null,
                )
            seedTab(jdbcUrl, venueId, tableSessionId, guestB)
            val guestAToken = issueToken(config, guestA)

            val endResponse =
                client.post("/api/guest/table/session/end") {
                    headers { append(HttpHeaders.Authorization, "Bearer $guestAToken") }
                    contentType(ContentType.Application.Json)
                    setBody("""{"tableToken":"$tokenValue","tableSessionId":$tableSessionId}""")
                }

            assertEquals(HttpStatusCode.NotFound, endResponse.status)
            assertApiErrorEnvelope(endResponse, ApiErrorCodes.NOT_FOUND)
            assertEquals(0, countUserTableSessionExits(jdbcUrl, guestA, tableSessionId))
            assertEquals("ACTIVE", fetchTableSessionStatus(jdbcUrl, tableSessionId))
        }

    @Test
    fun `end table session blocks active order for current user`() =
        testApplication {
            val jdbcUrl = buildJdbcUrl("guest-table-end-active-order")
            val config = buildConfig(jdbcUrl)

            environment { this.config = config }
            application { module() }

            client.get("/health")

            val tokenValue = "end-order-token"
            val venueId = seedVenue(jdbcUrl, VenueStatus.PUBLISHED.dbValue)
            val tableId = seedTable(jdbcUrl, venueId, 33)
            seedTableToken(jdbcUrl, tableId, tokenValue)
            seedUser(jdbcUrl, TELEGRAM_USER_ID)
            val tableSessionId =
                seedTableSession(
                    jdbcUrl = jdbcUrl,
                    venueId = venueId,
                    tableId = tableId,
                    status = "ACTIVE",
                    expiresAt = Instant.now().plus(1, ChronoUnit.HOURS),
                    endedAt = null,
                )
            val tabId = seedTab(jdbcUrl, venueId, tableSessionId, TELEGRAM_USER_ID)
            seedOrder(jdbcUrl, venueId, tableId, tableSessionId, tabId, "ACTIVE", authorUserId = TELEGRAM_USER_ID)
            val token = issueToken(config)

            val endResponse =
                client.post("/api/guest/table/session/end") {
                    headers { append(HttpHeaders.Authorization, "Bearer $token") }
                    contentType(ContentType.Application.Json)
                    setBody("""{"tableToken":"$tokenValue","tableSessionId":$tableSessionId}""")
                }

            assertEquals(HttpStatusCode.OK, endResponse.status)
            val payload = json.decodeFromString(TableSessionEndResponse.serializer(), endResponse.bodyAsText())
            assertEquals(false, payload.ended)
            assertEquals("ACTIVE_ORDER", payload.blockedReason)
            assertEquals("Сначала закройте счёт. После этого визит можно завершить.", payload.message)
            assertEquals(0, countUserTableSessionExits(jdbcUrl, TELEGRAM_USER_ID, tableSessionId))
        }

    @Test
    fun `another guest active order at same table does not block current user exit`() =
        testApplication {
            val jdbcUrl = buildJdbcUrl("guest-table-end-foreign-order")
            val config = buildConfig(jdbcUrl)

            environment { this.config = config }
            application { module() }

            client.get("/health")

            val guestA = TELEGRAM_USER_ID
            val guestB = 789L
            val tokenValue = "end-foreign-order-token"
            val venueId = seedVenue(jdbcUrl, VenueStatus.PUBLISHED.dbValue)
            val tableId = seedTable(jdbcUrl, venueId, 34)
            seedTableToken(jdbcUrl, tableId, tokenValue)
            seedUser(jdbcUrl, guestA)
            seedUser(jdbcUrl, guestB)
            val tableSessionId =
                seedTableSession(
                    jdbcUrl = jdbcUrl,
                    venueId = venueId,
                    tableId = tableId,
                    status = "ACTIVE",
                    expiresAt = Instant.now().plus(1, ChronoUnit.HOURS),
                    endedAt = null,
                )
            seedTab(jdbcUrl, venueId, tableSessionId, guestA)
            val guestBTabId = seedTab(jdbcUrl, venueId, tableSessionId, guestB)
            seedOrder(jdbcUrl, venueId, tableId, tableSessionId, guestBTabId, "ACTIVE", authorUserId = guestB)
            val guestAToken = issueToken(config, guestA)

            val endResponse =
                client.post("/api/guest/table/session/end") {
                    headers { append(HttpHeaders.Authorization, "Bearer $guestAToken") }
                    contentType(ContentType.Application.Json)
                    setBody("""{"tableToken":"$tokenValue","tableSessionId":$tableSessionId}""")
                }

            assertEquals(HttpStatusCode.OK, endResponse.status)
            val payload = json.decodeFromString(TableSessionEndResponse.serializer(), endResponse.bodyAsText())
            assertEquals(true, payload.ended)
            assertNull(payload.blockedReason)
            assertEquals(1, countUserTableSessionExits(jdbcUrl, guestA, tableSessionId))
            assertEquals(0, countUserTableSessionExits(jdbcUrl, guestB, tableSessionId))
        }

    @Test
    fun `end table session blocks active staff call for current user`() =
        testApplication {
            val jdbcUrl = buildJdbcUrl("guest-table-end-active-staff-call")
            val config = buildConfig(jdbcUrl)

            environment { this.config = config }
            application { module() }

            client.get("/health")

            val tokenValue = "end-staff-call-token"
            val venueId = seedVenue(jdbcUrl, VenueStatus.PUBLISHED.dbValue)
            val tableId = seedTable(jdbcUrl, venueId, 35)
            seedTableToken(jdbcUrl, tableId, tokenValue)
            seedUser(jdbcUrl, TELEGRAM_USER_ID)
            val tableSessionId =
                seedTableSession(
                    jdbcUrl = jdbcUrl,
                    venueId = venueId,
                    tableId = tableId,
                    status = "ACTIVE",
                    expiresAt = Instant.now().plus(1, ChronoUnit.HOURS),
                    endedAt = null,
                )
            seedTab(jdbcUrl, venueId, tableSessionId, TELEGRAM_USER_ID)
            seedStaffCall(jdbcUrl, venueId, tableId, tableSessionId, TELEGRAM_USER_ID, "ACK")
            val token = issueToken(config)

            val endResponse =
                client.post("/api/guest/table/session/end") {
                    headers { append(HttpHeaders.Authorization, "Bearer $token") }
                    contentType(ContentType.Application.Json)
                    setBody("""{"tableToken":"$tokenValue","tableSessionId":$tableSessionId}""")
                }

            assertEquals(HttpStatusCode.OK, endResponse.status)
            val payload = json.decodeFromString(TableSessionEndResponse.serializer(), endResponse.bodyAsText())
            assertEquals(false, payload.ended)
            assertEquals("ACTIVE_STAFF_CALL", payload.blockedReason)
            assertEquals("Дождитесь завершения вызова персонала или обратитесь к сотруднику.", payload.message)
            assertEquals(0, countUserTableSessionExits(jdbcUrl, TELEGRAM_USER_ID, tableSessionId))
        }

    @Test
    fun `done own staff call and another guest active staff call do not block current user exit`() =
        testApplication {
            val jdbcUrl = buildJdbcUrl("guest-table-end-done-foreign-staff-call")
            val config = buildConfig(jdbcUrl)

            environment { this.config = config }
            application { module() }

            client.get("/health")

            val guestA = TELEGRAM_USER_ID
            val guestB = 789L
            val tokenValue = "end-done-staff-call-token"
            val venueId = seedVenue(jdbcUrl, VenueStatus.PUBLISHED.dbValue)
            val tableId = seedTable(jdbcUrl, venueId, 36)
            seedTableToken(jdbcUrl, tableId, tokenValue)
            seedUser(jdbcUrl, guestA)
            seedUser(jdbcUrl, guestB)
            val tableSessionId =
                seedTableSession(
                    jdbcUrl = jdbcUrl,
                    venueId = venueId,
                    tableId = tableId,
                    status = "ACTIVE",
                    expiresAt = Instant.now().plus(1, ChronoUnit.HOURS),
                    endedAt = null,
                )
            seedTab(jdbcUrl, venueId, tableSessionId, guestA)
            seedTab(jdbcUrl, venueId, tableSessionId, guestB)
            seedStaffCall(jdbcUrl, venueId, tableId, tableSessionId, guestA, "DONE")
            seedStaffCall(jdbcUrl, venueId, tableId, tableSessionId, guestB, "NEW")
            val guestAToken = issueToken(config, guestA)

            val endResponse =
                client.post("/api/guest/table/session/end") {
                    headers { append(HttpHeaders.Authorization, "Bearer $guestAToken") }
                    contentType(ContentType.Application.Json)
                    setBody("""{"tableToken":"$tokenValue","tableSessionId":$tableSessionId}""")
                }

            assertEquals(HttpStatusCode.OK, endResponse.status)
            val payload = json.decodeFromString(TableSessionEndResponse.serializer(), endResponse.bodyAsText())
            assertEquals(true, payload.ended)
            assertNull(payload.blockedReason)
            assertEquals(1, countUserTableSessionExits(jdbcUrl, guestA, tableSessionId))
        }

    @Test
    fun `table token without session id and without create mode does not create table session`() =
        testApplication {
            val jdbcUrl = buildJdbcUrl("guest-table-no-create")
            val config = buildConfig(jdbcUrl)

            environment { this.config = config }
            application { module() }

            client.get("/health")

            val tokenValue = "no-create-token"
            val venueId = seedVenue(jdbcUrl, VenueStatus.PUBLISHED.dbValue)
            val tableId = seedTable(jdbcUrl, venueId, 104)
            seedTableToken(jdbcUrl, tableId, tokenValue)
            val token = issueToken(config)

            val response =
                client.get("/api/guest/table/resolve?tableToken=$tokenValue") {
                    headers { append(HttpHeaders.Authorization, "Bearer $token") }
                }

            assertEquals(HttpStatusCode.OK, response.status)
            val payload = json.decodeFromString(TableResolveResponse.serializer(), response.bodyAsText())
            assertEquals(venueId, payload.venueId)
            assertEquals(tableId, payload.tableId)
            assertEquals(0L, payload.tableSessionId)
            assertEquals("UNKNOWN", payload.tableSessionStatus)
            assertEquals(false, payload.tableSessionActive)
            assertEquals("TABLE_SESSION_MISSING", payload.tableSessionInactiveReason)
            assertEquals(true, payload.available)
            assertEquals(0, countTableSessions(jdbcUrl))
        }

    @Test
    fun `requested ended table session resolves to inactive without creating new session`() =
        testApplication {
            val jdbcUrl = buildJdbcUrl("guest-table-ended-session")
            val config = buildConfig(jdbcUrl)

            environment { this.config = config }
            application { module() }

            client.get("/health")

            val tokenValue = "ended-session-token"
            val venueId = seedVenue(jdbcUrl, VenueStatus.PUBLISHED.dbValue)
            val tableId = seedTable(jdbcUrl, venueId, 104)
            seedTableToken(jdbcUrl, tableId, tokenValue)
            val endedSessionId =
                seedTableSession(
                    jdbcUrl = jdbcUrl,
                    venueId = venueId,
                    tableId = tableId,
                    status = "ENDED",
                    expiresAt = Instant.now().plus(1, ChronoUnit.HOURS),
                    endedAt = Instant.now(),
                )
            val token = issueToken(config)

            val response =
                client.get("/api/guest/table/resolve?tableToken=$tokenValue&tableSessionId=$endedSessionId") {
                    headers { append(HttpHeaders.Authorization, "Bearer $token") }
                }

            assertEquals(HttpStatusCode.OK, response.status)
            val payload = json.decodeFromString(TableResolveResponse.serializer(), response.bodyAsText())
            assertEquals(endedSessionId, payload.tableSessionId)
            assertEquals("ENDED", payload.tableSessionStatus)
            assertEquals(false, payload.tableSessionActive)
            assertEquals("TABLE_SESSION_ENDED", payload.tableSessionInactiveReason)
            assertEquals(true, payload.available)
            assertEquals(1, countTableSessions(jdbcUrl))
        }

    @Test
    fun `requested expired table session resolves to inactive without creating new session`() =
        testApplication {
            val jdbcUrl = buildJdbcUrl("guest-table-expired-session")
            val config = buildConfig(jdbcUrl)

            environment { this.config = config }
            application { module() }

            client.get("/health")

            val tokenValue = "expired-session-token"
            val venueId = seedVenue(jdbcUrl, VenueStatus.PUBLISHED.dbValue)
            val tableId = seedTable(jdbcUrl, venueId, 104)
            seedTableToken(jdbcUrl, tableId, tokenValue)
            val expiredSessionId =
                seedTableSession(
                    jdbcUrl = jdbcUrl,
                    venueId = venueId,
                    tableId = tableId,
                    status = "ACTIVE",
                    expiresAt = Instant.now().minus(1, ChronoUnit.MINUTES),
                    endedAt = null,
                )
            val token = issueToken(config)

            val response =
                client.get("/api/guest/table/resolve?tableToken=$tokenValue&tableSessionId=$expiredSessionId") {
                    headers { append(HttpHeaders.Authorization, "Bearer $token") }
                }

            assertEquals(HttpStatusCode.OK, response.status)
            val payload = json.decodeFromString(TableResolveResponse.serializer(), response.bodyAsText())
            assertEquals(expiredSessionId, payload.tableSessionId)
            assertEquals("EXPIRED", payload.tableSessionStatus)
            assertEquals(false, payload.tableSessionActive)
            assertEquals("TABLE_SESSION_EXPIRED", payload.tableSessionInactiveReason)
            assertEquals(true, payload.available)
            assertEquals(1, countTableSessions(jdbcUrl))
        }

    @Test
    fun `fresh qr resolve creates a new active session after previous session ended`() =
        testApplication {
            val jdbcUrl = buildJdbcUrl("guest-table-fresh-session")
            val config = buildConfig(jdbcUrl)

            environment { this.config = config }
            application { module() }

            client.get("/health")

            val tokenValue = "fresh-session-token"
            val venueId = seedVenue(jdbcUrl, VenueStatus.PUBLISHED.dbValue)
            val tableId = seedTable(jdbcUrl, venueId, 104)
            seedTableToken(jdbcUrl, tableId, tokenValue)
            seedTableSession(
                jdbcUrl = jdbcUrl,
                venueId = venueId,
                tableId = tableId,
                status = "ENDED",
                expiresAt = Instant.now().plus(1, ChronoUnit.HOURS),
                endedAt = Instant.now(),
            )
            val token = issueToken(config)

            val response =
                client.get("/api/guest/table/resolve?tableToken=$tokenValue&resolveMode=create") {
                    headers { append(HttpHeaders.Authorization, "Bearer $token") }
                }

            assertEquals(HttpStatusCode.OK, response.status)
            val payload = json.decodeFromString(TableResolveResponse.serializer(), response.bodyAsText())
            assertEquals("ACTIVE", payload.tableSessionStatus)
            assertEquals(true, payload.tableSessionActive)
            assertNull(payload.tableSessionInactiveReason)
            assertEquals(2, countTableSessions(jdbcUrl))
        }

    @Test
    fun `table session started event is emitted once for active session reuse`() =
        testApplication {
            val jdbcUrl = buildJdbcUrl("guest-table-event")
            val config = buildConfig(jdbcUrl)

            environment { this.config = config }
            application { module() }

            client.get("/health")

            val tokenValue = "event-token"
            val venueId = seedVenue(jdbcUrl, VenueStatus.PUBLISHED.dbValue)
            val tableId = seedTable(jdbcUrl, venueId, 15)
            seedTableToken(jdbcUrl, tableId, tokenValue)
            val token = issueToken(config)

            repeat(2) {
                val response =
                    client.get("/api/guest/table/resolve?tableToken=$tokenValue&resolveMode=create") {
                        headers { append(HttpHeaders.Authorization, "Bearer $token") }
                    }
                assertEquals(HttpStatusCode.OK, response.status)
            }

            assertEquals(1, countAnalyticsEvents(jdbcUrl, "table_session_started", venueId))
        }

    @Test
    fun `known token for past due subscription returns available`() =
        testApplication {
            val jdbcUrl = buildJdbcUrl("guest-table-past-due")
            val config = buildConfig(jdbcUrl)

            environment { this.config = config }
            application { module() }

            client.get("/health")

            val tokenValue = "past-due-token"
            val venueId = seedVenue(jdbcUrl, VenueStatus.PUBLISHED.dbValue)
            val tableId = seedTable(jdbcUrl, venueId, 12)
            seedTableToken(jdbcUrl, tableId, tokenValue)
            seedSubscription(jdbcUrl, venueId, "PAST_DUE")

            val token = issueToken(config)

            val response =
                client.get("/api/guest/table/resolve?tableToken=$tokenValue&resolveMode=create") {
                    headers { append(HttpHeaders.Authorization, "Bearer $token") }
                }

            assertEquals(HttpStatusCode.OK, response.status)
            val payload = json.decodeFromString(TableResolveResponse.serializer(), response.bodyAsText())
            assertEquals(venueId, payload.venueId)
            assertEquals(tableId, payload.tableId)
            assertEquals("12", payload.tableNumber)
            assertEquals(VenueStatus.PUBLISHED.dbValue, payload.venueStatus)
            assertEquals("past_due", payload.subscriptionStatus)
            assertEquals(true, payload.available)
            assertNull(payload.unavailableReason)
        }

    @Test
    fun `known token for suspended by platform subscription returns unavailable`() =
        testApplication {
            val jdbcUrl = buildJdbcUrl("guest-table-suspended-by-platform")
            val config = buildConfig(jdbcUrl)

            environment { this.config = config }
            application { module() }

            client.get("/health")

            val tokenValue = "suspended-platform-token"
            val venueId = seedVenue(jdbcUrl, VenueStatus.PUBLISHED.dbValue)
            val tableId = seedTable(jdbcUrl, venueId, 14)
            seedTableToken(jdbcUrl, tableId, tokenValue)
            seedSubscription(jdbcUrl, venueId, "SUSPENDED_BY_PLATFORM")

            val token = issueToken(config)

            val response =
                client.get("/api/guest/table/resolve?tableToken=$tokenValue&resolveMode=create") {
                    headers { append(HttpHeaders.Authorization, "Bearer $token") }
                }

            assertEquals(HttpStatusCode.OK, response.status)
            val payload = json.decodeFromString(TableResolveResponse.serializer(), response.bodyAsText())
            assertEquals(venueId, payload.venueId)
            assertEquals(tableId, payload.tableId)
            assertEquals("14", payload.tableNumber)
            assertEquals(VenueStatus.PUBLISHED.dbValue, payload.venueStatus)
            assertEquals("suspended_by_platform", payload.subscriptionStatus)
            assertEquals(false, payload.available)
            assertEquals("SUBSCRIPTION_BLOCKED", payload.unavailableReason)
        }

    private fun buildJdbcUrl(prefix: String): String {
        val dbName = "$prefix-${UUID.randomUUID()}"
        return "jdbc:h2:mem:$dbName;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1"
    }

    private fun buildConfig(
        jdbcUrl: String,
        platformOwnerUserId: Long? = null,
    ): MapApplicationConfig {
        val entries =
            mutableListOf(
                "app.env" to appEnv,
                "api.session.jwtSecret" to "test-secret",
                "db.jdbcUrl" to jdbcUrl,
                "db.user" to "sa",
                "db.password" to "",
            )
        platformOwnerUserId?.let { entries += "telegram.platformOwnerId" to it.toString() }
        return MapApplicationConfig(*entries.toTypedArray())
    }

    private fun issueToken(
        config: MapApplicationConfig,
        userId: Long = TELEGRAM_USER_ID,
    ): String {
        val service = SessionTokenService(SessionTokenConfig.from(config, appEnv))
        return service.issueToken(userId).token
    }

    private fun seedUser(
        jdbcUrl: String,
        userId: Long,
    ) {
        DriverManager.getConnection(jdbcUrl, "sa", "").use { connection ->
            connection.prepareStatement(
                """
                INSERT INTO users (telegram_user_id, first_name)
                VALUES (?, ?)
                """.trimIndent(),
            ).use { statement ->
                statement.setLong(1, userId)
                statement.setString(2, "Guest $userId")
                statement.executeUpdate()
            }
        }
    }

    private fun createTelegramChatContextFixture(jdbcUrl: String) {
        DriverManager.getConnection(jdbcUrl, "sa", "").use { connection ->
            connection.createStatement().use { statement ->
                statement.execute(
                    """
                    CREATE TABLE IF NOT EXISTS telegram_chat_context (
                        chat_id BIGINT PRIMARY KEY,
                        user_id BIGINT NOT NULL REFERENCES users(telegram_user_id) ON DELETE CASCADE,
                        venue_id BIGINT NULL REFERENCES venues(id) ON DELETE SET NULL,
                        table_id BIGINT NULL REFERENCES venue_tables(id) ON DELETE SET NULL,
                        table_token VARCHAR(64) NULL,
                        updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
                    )
                    """.trimIndent(),
                )
                statement.execute(
                    """
                    CREATE INDEX IF NOT EXISTS idx_telegram_chat_context_user
                    ON telegram_chat_context (user_id)
                    """.trimIndent(),
                )
            }
        }
    }

    private fun seedChatContext(
        jdbcUrl: String,
        chatId: Long,
        userId: Long,
        venueId: Long,
        tableId: Long,
        tableToken: String,
        updatedAt: Instant,
    ) {
        DriverManager.getConnection(jdbcUrl, "sa", "").use { connection ->
            connection.prepareStatement(
                """
                INSERT INTO telegram_chat_context (
                    chat_id,
                    user_id,
                    venue_id,
                    table_id,
                    table_token,
                    updated_at
                )
                VALUES (?, ?, ?, ?, ?, ?)
                """.trimIndent(),
            ).use { statement ->
                statement.setLong(1, chatId)
                statement.setLong(2, userId)
                statement.setLong(3, venueId)
                statement.setLong(4, tableId)
                statement.setString(5, tableToken)
                statement.setTimestamp(6, Timestamp.from(updatedAt))
                statement.executeUpdate()
            }
        }
    }

    private fun seedVenue(
        jdbcUrl: String,
        status: String,
    ): Long {
        DriverManager.getConnection(jdbcUrl, "sa", "").use { connection ->
            connection.prepareStatement(
                """
                INSERT INTO venues (name, city, address, status)
                VALUES (?, ?, ?, ?)
                """.trimIndent(),
                Statement.RETURN_GENERATED_KEYS,
            ).use { statement ->
                statement.setString(1, "Venue")
                statement.setString(2, "City")
                statement.setString(3, "Address")
                statement.setString(4, status)
                statement.executeUpdate()
                statement.generatedKeys.use { rs ->
                    if (rs.next()) {
                        return rs.getLong(1)
                    }
                }
            }
        }
        error("Failed to insert venue")
    }

    private fun seedTable(
        jdbcUrl: String,
        venueId: Long,
        tableNumber: Int,
    ): Long {
        DriverManager.getConnection(jdbcUrl, "sa", "").use { connection ->
            connection.prepareStatement(
                """
                INSERT INTO venue_tables (venue_id, table_number)
                VALUES (?, ?)
                """.trimIndent(),
                Statement.RETURN_GENERATED_KEYS,
            ).use { statement ->
                statement.setLong(1, venueId)
                statement.setInt(2, tableNumber)
                statement.executeUpdate()
                statement.generatedKeys.use { rs ->
                    if (rs.next()) {
                        return rs.getLong(1)
                    }
                }
            }
        }
        error("Failed to insert table")
    }

    private fun seedTableToken(
        jdbcUrl: String,
        tableId: Long,
        token: String,
    ) {
        DriverManager.getConnection(jdbcUrl, "sa", "").use { connection ->
            connection.prepareStatement(
                """
                INSERT INTO table_tokens (token, table_id, is_active)
                VALUES (?, ?, true)
                """.trimIndent(),
            ).use { statement ->
                statement.setString(1, token)
                statement.setLong(2, tableId)
                statement.executeUpdate()
            }
        }
    }

    private fun setTableTokenActive(
        jdbcUrl: String,
        token: String,
        active: Boolean,
    ) {
        DriverManager.getConnection(jdbcUrl, "sa", "").use { connection ->
            connection.prepareStatement("UPDATE table_tokens SET is_active = ? WHERE token = ?").use { statement ->
                statement.setBoolean(1, active)
                statement.setString(2, token)
                statement.executeUpdate()
            }
        }
    }

    private fun seedTableSession(
        jdbcUrl: String,
        venueId: Long,
        tableId: Long,
        status: String,
        expiresAt: Instant,
        endedAt: Instant?,
        lastActivityAt: Instant = Instant.now().minus(1, ChronoUnit.HOURS),
        startedAt: Instant = Instant.now().minus(2, ChronoUnit.HOURS),
    ): Long {
        DriverManager.getConnection(jdbcUrl, "sa", "").use { connection ->
            connection.prepareStatement(
                """
                INSERT INTO table_sessions (
                    venue_id,
                    table_id,
                    started_at,
                    last_activity_at,
                    expires_at,
                    ended_at,
                    status
                )
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """.trimIndent(),
                Statement.RETURN_GENERATED_KEYS,
            ).use { statement ->
                statement.setLong(1, venueId)
                statement.setLong(2, tableId)
                statement.setTimestamp(3, Timestamp.from(startedAt))
                statement.setTimestamp(4, Timestamp.from(lastActivityAt))
                statement.setTimestamp(5, Timestamp.from(expiresAt))
                statement.setTimestamp(6, endedAt?.let { Timestamp.from(it) })
                statement.setString(7, status)
                statement.executeUpdate()
                statement.generatedKeys.use { rs ->
                    if (rs.next()) {
                        return rs.getLong(1)
                    }
                }
            }
        }
        error("Failed to insert table session")
    }

    private fun seedTab(
        jdbcUrl: String,
        venueId: Long,
        tableSessionId: Long,
        userId: Long,
    ): Long {
        DriverManager.getConnection(jdbcUrl, "sa", "").use { connection ->
            val tabId =
                connection.prepareStatement(
                    """
                    INSERT INTO tab (venue_id, table_session_id, type, owner_user_id, status)
                    VALUES (?, ?, 'PERSONAL', ?, 'ACTIVE')
                    """.trimIndent(),
                    Statement.RETURN_GENERATED_KEYS,
                ).use { statement ->
                    statement.setLong(1, venueId)
                    statement.setLong(2, tableSessionId)
                    statement.setLong(3, userId)
                    statement.executeUpdate()
                    statement.generatedKeys.use { rs ->
                        if (rs.next()) {
                            rs.getLong(1)
                        } else {
                            error("Failed to insert tab")
                        }
                    }
                }
            connection.prepareStatement(
                """
                INSERT INTO tab_member (tab_id, user_id, role)
                VALUES (?, ?, 'OWNER')
                """.trimIndent(),
            ).use { statement ->
                statement.setLong(1, tabId)
                statement.setLong(2, userId)
                statement.executeUpdate()
            }
            return tabId
        }
    }

    private fun seedOrder(
        jdbcUrl: String,
        venueId: Long,
        tableId: Long,
        tableSessionId: Long,
        tabId: Long,
        status: String,
        updatedAt: Instant = Instant.now(),
        authorUserId: Long = TELEGRAM_USER_ID,
    ): Long {
        DriverManager.getConnection(jdbcUrl, "sa", "").use { connection ->
            val orderId =
                connection.prepareStatement(
                    """
                    INSERT INTO orders (venue_id, table_id, table_session_id, status, created_at, updated_at)
                    VALUES (?, ?, ?, ?, ?, ?)
                    """.trimIndent(),
                    Statement.RETURN_GENERATED_KEYS,
                ).use { statement ->
                    val timestamp = Timestamp.from(updatedAt)
                    statement.setLong(1, venueId)
                    statement.setLong(2, tableId)
                    statement.setLong(3, tableSessionId)
                    statement.setString(4, status)
                    statement.setTimestamp(5, timestamp)
                    statement.setTimestamp(6, timestamp)
                    statement.executeUpdate()
                    statement.generatedKeys.use { rs ->
                        if (rs.next()) {
                            rs.getLong(1)
                        } else {
                            error("Failed to insert order")
                        }
                    }
                }
            connection.prepareStatement(
                """
                INSERT INTO order_batches (order_id, tab_id, author_user_id, source, status, items_snapshot)
                VALUES (?, ?, ?, 'MINIAPP', 'NEW', ?)
                """.trimIndent(),
            ).use { statement ->
                statement.setLong(1, orderId)
                statement.setLong(2, tabId)
                statement.setLong(3, authorUserId)
                statement.setString(4, "[]")
                statement.executeUpdate()
            }
            return orderId
        }
    }

    private fun seedStaffCall(
        jdbcUrl: String,
        venueId: Long,
        tableId: Long,
        tableSessionId: Long,
        userId: Long,
        status: String,
    ) {
        DriverManager.getConnection(jdbcUrl, "sa", "").use { connection ->
            connection.prepareStatement(
                """
                INSERT INTO staff_calls (
                    venue_id,
                    table_id,
                    table_session_id,
                    created_by_user_id,
                    reason,
                    status,
                    created_at
                )
                VALUES (?, ?, ?, ?, 'COME', ?, ?)
                """.trimIndent(),
            ).use { statement ->
                statement.setLong(1, venueId)
                statement.setLong(2, tableId)
                statement.setLong(3, tableSessionId)
                statement.setLong(4, userId)
                statement.setString(5, status)
                statement.setTimestamp(6, Timestamp.from(Instant.now()))
                statement.executeUpdate()
            }
        }
    }

    private fun seedUserExit(
        jdbcUrl: String,
        userId: Long,
        tableSessionId: Long,
    ) {
        DriverManager.getConnection(jdbcUrl, "sa", "").use { connection ->
            connection.prepareStatement(
                """
                INSERT INTO guest_table_session_exits (user_id, table_session_id, exited_at)
                VALUES (?, ?, ?)
                """.trimIndent(),
            ).use { statement ->
                statement.setLong(1, userId)
                statement.setLong(2, tableSessionId)
                statement.setTimestamp(3, Timestamp.from(Instant.now()))
                statement.executeUpdate()
            }
        }
    }

    private fun countTableSessions(jdbcUrl: String): Int {
        DriverManager.getConnection(jdbcUrl, "sa", "").use { connection ->
            connection.prepareStatement("SELECT COUNT(*) FROM table_sessions").use { statement ->
                statement.executeQuery().use { rs ->
                    if (rs.next()) {
                        return rs.getInt(1)
                    }
                }
            }
        }
        return 0
    }

    private fun countPersonalTabs(
        jdbcUrl: String,
        userId: Long,
        tableSessionId: Long? = null,
    ): Int {
        val sessionFilter = if (tableSessionId == null) "" else " AND table_session_id = ?"
        DriverManager.getConnection(jdbcUrl, "sa", "").use { connection ->
            connection.prepareStatement(
                """
                SELECT COUNT(*)
                FROM tab
                WHERE type = 'PERSONAL'
                  AND owner_user_id = ?
                  $sessionFilter
                """.trimIndent(),
            ).use { statement ->
                statement.setLong(1, userId)
                tableSessionId?.let { statement.setLong(2, it) }
                statement.executeQuery().use { rs ->
                    if (rs.next()) {
                        return rs.getInt(1)
                    }
                }
            }
        }
        return 0
    }

    private fun fetchTableSessionTiming(
        jdbcUrl: String,
        tableSessionId: Long,
    ): TableSessionTiming? {
        DriverManager.getConnection(jdbcUrl, "sa", "").use { connection ->
            connection.prepareStatement(
                """
                SELECT last_activity_at, expires_at
                FROM table_sessions
                WHERE id = ?
                """.trimIndent(),
            ).use { statement ->
                statement.setLong(1, tableSessionId)
                statement.executeQuery().use { rs ->
                    if (rs.next()) {
                        return TableSessionTiming(
                            lastActivityAt = rs.getTimestamp("last_activity_at").toInstant(),
                            expiresAt = rs.getTimestamp("expires_at").toInstant(),
                        )
                    }
                }
            }
        }
        return null
    }

    private fun countUserTableSessionExits(
        jdbcUrl: String,
        userId: Long,
        tableSessionId: Long,
    ): Int {
        DriverManager.getConnection(jdbcUrl, "sa", "").use { connection ->
            connection.prepareStatement(
                """
                SELECT COUNT(*)
                FROM guest_table_session_exits
                WHERE user_id = ?
                  AND table_session_id = ?
                """.trimIndent(),
            ).use { statement ->
                statement.setLong(1, userId)
                statement.setLong(2, tableSessionId)
                statement.executeQuery().use { rs ->
                    if (rs.next()) {
                        return rs.getInt(1)
                    }
                }
            }
        }
        return 0
    }

    private fun countChatContexts(
        jdbcUrl: String,
        userId: Long,
    ): Int {
        DriverManager.getConnection(jdbcUrl, "sa", "").use { connection ->
            connection.prepareStatement(
                "SELECT COUNT(*) FROM telegram_chat_context WHERE user_id = ?",
            ).use { statement ->
                statement.setLong(1, userId)
                statement.executeQuery().use { rs ->
                    if (rs.next()) {
                        return rs.getInt(1)
                    }
                }
            }
        }
        return 0
    }

    private fun countStaffCalls(
        jdbcUrl: String,
        userId: Long,
    ): Int {
        DriverManager.getConnection(jdbcUrl, "sa", "").use { connection ->
            connection.prepareStatement(
                "SELECT COUNT(*) FROM staff_calls WHERE created_by_user_id = ?",
            ).use { statement ->
                statement.setLong(1, userId)
                statement.executeQuery().use { rs ->
                    if (rs.next()) {
                        return rs.getInt(1)
                    }
                }
            }
        }
        return 0
    }

    private fun fetchTableSessionStatus(
        jdbcUrl: String,
        tableSessionId: Long,
    ): String? {
        DriverManager.getConnection(jdbcUrl, "sa", "").use { connection ->
            connection.prepareStatement("SELECT status FROM table_sessions WHERE id = ?").use { statement ->
                statement.setLong(1, tableSessionId)
                statement.executeQuery().use { rs ->
                    if (rs.next()) {
                        return rs.getString("status")
                    }
                }
            }
        }
        return null
    }

    private fun countAnalyticsEvents(
        jdbcUrl: String,
        eventType: String,
        venueId: Long,
    ): Int {
        DriverManager.getConnection(jdbcUrl, "sa", "").use { connection ->
            connection.prepareStatement(
                "SELECT COUNT(*) FROM analytics_events WHERE event_type = ? AND venue_id = ?",
            ).use { statement ->
                statement.setString(1, eventType)
                statement.setLong(2, venueId)
                statement.executeQuery().use { rs ->
                    if (rs.next()) {
                        return rs.getInt(1)
                    }
                }
            }
        }
        return 0
    }

    private fun seedSubscription(
        jdbcUrl: String,
        venueId: Long,
        status: String,
    ) {
        val now = Instant.now()
        val trialEnd = now.plus(14, ChronoUnit.DAYS)
        DriverManager.getConnection(jdbcUrl, "sa", "").use { connection ->
            connection.prepareStatement(
                """
                INSERT INTO venue_subscriptions (venue_id, status, trial_end, paid_start, updated_at)
                VALUES (?, ?, ?, ?, ?)
                """.trimIndent(),
            ).use { statement ->
                statement.setLong(1, venueId)
                statement.setString(2, status.uppercase())
                statement.setTimestamp(3, Timestamp.from(trialEnd))
                statement.setTimestamp(4, Timestamp.from(now))
                statement.setTimestamp(5, Timestamp.from(now))
                statement.executeUpdate()
            }
        }
    }

    private companion object {
        const val TELEGRAM_USER_ID: Long = 456L
        const val PLATFORM_OWNER_USER_ID: Long = 4_567L
        const val PLATFORM_OWNER_CHAT_ID: Long = 98_765L
        const val PLATFORM_RECONFIRM_MESSAGE: String =
            "Откройте QR-код ещё раз и подтвердите вход в Telegram."
    }

    private data class TableSessionTiming(
        val lastActivityAt: Instant,
        val expiresAt: Instant,
    )
}
