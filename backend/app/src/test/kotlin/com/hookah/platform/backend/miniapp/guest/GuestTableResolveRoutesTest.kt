package com.hookah.platform.backend.miniapp.guest

import com.hookah.platform.backend.ModuleOverrides
import com.hookah.platform.backend.api.ApiErrorCodes
import com.hookah.platform.backend.miniapp.guest.api.TableResolveResponse
import com.hookah.platform.backend.miniapp.guest.api.TableRestoreResponse
import com.hookah.platform.backend.miniapp.session.SessionTokenConfig
import com.hookah.platform.backend.miniapp.session.SessionTokenService
import com.hookah.platform.backend.miniapp.venue.VenueStatus
import com.hookah.platform.backend.module
import com.hookah.platform.backend.moduleWithOverrides
import com.hookah.platform.backend.test.assertApiErrorEnvelope
import io.ktor.client.request.get
import io.ktor.client.request.headers
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
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

    private fun buildConfig(jdbcUrl: String): MapApplicationConfig {
        return MapApplicationConfig(
            "app.env" to appEnv,
            "api.session.jwtSecret" to "test-secret",
            "db.jdbcUrl" to jdbcUrl,
            "db.user" to "sa",
            "db.password" to "",
        )
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

    private fun seedTableSession(
        jdbcUrl: String,
        venueId: Long,
        tableId: Long,
        status: String,
        expiresAt: Instant,
        endedAt: Instant?,
        lastActivityAt: Instant = Instant.now().minus(1, ChronoUnit.HOURS),
    ): Long {
        val now = Instant.now()
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
                statement.setTimestamp(3, Timestamp.from(now.minus(2, ChronoUnit.HOURS)))
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
                statement.setLong(3, TELEGRAM_USER_ID)
                statement.setString(4, "[]")
                statement.executeUpdate()
            }
            return orderId
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
    }
}
