package com.hookah.platform.backend.miniapp.venue

import com.hookah.platform.backend.ModuleOverrides
import com.hookah.platform.backend.api.ApiErrorCodes
import com.hookah.platform.backend.api.ForbiddenException
import com.hookah.platform.backend.miniapp.session.SessionTokenConfig
import com.hookah.platform.backend.miniapp.session.SessionTokenService
import com.hookah.platform.backend.miniapp.venue.staff.TodayStaffSource
import com.hookah.platform.backend.miniapp.venue.staff.VenueStaffModuleSettingsRepository
import com.hookah.platform.backend.miniapp.venue.staff.VenueStaffModuleSettingsWrite
import com.hookah.platform.backend.moduleWithOverrides
import com.hookah.platform.backend.telegram.db.VenueSettingsRepository
import com.hookah.platform.backend.test.assertApiErrorEnvelope
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.headers
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.config.MapApplicationConfig
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.h2.jdbcx.JdbcDataSource
import java.sql.DriverManager
import java.sql.Statement
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class VenueStaffModuleSettingsRoutesTest {
    private val json = Json { ignoreUnknownKeys = true }
    private val fixedNow = Instant.parse("2035-04-05T06:07:08Z")

    @Test
    fun `owner and manager manage own module settings through narrow permission with safe audit`() =
        testApplication {
            val jdbcUrl = buildJdbcUrl("owner-manager")
            val config = buildConfig(jdbcUrl)
            environment { this.config = config }
            application {
                moduleWithOverrides(
                    ModuleOverrides(staffScheduleClock = Clock.fixed(fixedNow, ZoneOffset.UTC)),
                )
            }
            client.get("/health")

            val ownerId = 91_001L
            val managerId = 91_002L
            val ownerVenueId = createVenueWithMember(jdbcUrl, ownerId, "OWNER")
            val managerVenueId = createVenueWithMember(jdbcUrl, managerId, "MANAGER")
            val ownerToken = issueToken(config, ownerId)
            val managerToken = issueToken(config, managerId)

            val ownerDefaults = readSettings(client.getSettings(ownerVenueId, ownerToken))
            val managerDefaults = readSettings(client.getSettings(managerVenueId, managerToken))
            assertEquals(DEFAULT_UPDATED_AT, ownerDefaults.updatedAt)
            assertEquals(DEFAULT_UPDATED_AT, managerDefaults.updatedAt)

            val ownerSaved =
                readSettings(
                    client.putSettings(
                        venueId = ownerVenueId,
                        token = ownerToken,
                        body = settingsBody(false, false, "SCHEDULE", ownerDefaults.updatedAt),
                    ),
                )
            val managerSaved =
                readSettings(
                    client.putSettings(
                        venueId = managerVenueId,
                        token = managerToken,
                        body = settingsBody(false, true, "SCHEDULE", managerDefaults.updatedAt),
                    ),
                )

            assertEquals(false, ownerSaved.teamScheduleModuleEnabled)
            assertEquals(false, ownerSaved.guestTeamVisible)
            assertEquals("SCHEDULE", ownerSaved.todayStaffSource)
            assertEquals(false, managerSaved.teamScheduleModuleEnabled)
            assertEquals(true, managerSaved.guestTeamVisible)
            assertEquals("SCHEDULE", managerSaved.todayStaffSource)

            val venueMe =
                json.decodeFromString(
                    VenueMeResponse.serializer(),
                    client.get("/api/venue/me") {
                        headers { append(HttpHeaders.Authorization, "Bearer $managerToken") }
                    }.bodyAsText(),
                )
            val managerAccess = venueMe.venues.single { it.venueId == managerVenueId }
            assertTrue("STAFF_MODULE_SETTINGS_MANAGE" in managerAccess.permissions)
            assertFalse("VENUE_SETTINGS" in managerAccess.permissions)

            val audits = loadSettingsAudits(jdbcUrl)
            assertEquals(2, audits.size)
            assertSafeAudit(
                row = audits.single { it.entityId == ownerVenueId },
                actorUserId = ownerId,
                venueId = ownerVenueId,
                changedFields =
                    setOf(
                        "teamScheduleModuleEnabled",
                        "guestTeamVisible",
                        "todayStaffSource",
                    ),
            )
            assertSafeAudit(
                row = audits.single { it.entityId == managerVenueId },
                actorUserId = managerId,
                venueId = managerVenueId,
                changedFields = setOf("teamScheduleModuleEnabled", "todayStaffSource"),
            )
        }

    @Test
    fun `staff foreign and unaffiliated actors are denied without settings state or audit leak`() =
        testApplication {
            val jdbcUrl = buildJdbcUrl("denials")
            val config = buildConfig(jdbcUrl)
            environment { this.config = config }
            application { moduleWithOverrides(ModuleOverrides()) }
            client.get("/health")

            val ownerId = 92_001L
            val staffId = 92_002L
            val foreignManagerId = 92_003L
            val unaffiliatedId = 92_004L
            val venueId = createVenueWithMember(jdbcUrl, ownerId, "OWNER")
            addVenueMember(jdbcUrl, venueId, staffId, "STAFF")
            createVenueWithMember(jdbcUrl, foreignManagerId, "MANAGER")
            seedUser(jdbcUrl, unaffiliatedId)

            val ownerToken = issueToken(config, ownerId)
            val defaults = readSettings(client.getSettings(venueId, ownerToken))
            val disabled =
                readSettings(
                    client.putSettings(
                        venueId,
                        ownerToken,
                        settingsBody(false, false, "SCHEDULE", defaults.updatedAt),
                    ),
                )
            val auditCountBefore = loadSettingsAudits(jdbcUrl).size

            listOf(staffId, foreignManagerId, unaffiliatedId).forEach { deniedUserId ->
                val token = issueToken(config, deniedUserId)
                val getResponse = client.getSettings(venueId, token)
                assertEquals(HttpStatusCode.Forbidden, getResponse.status)
                assertApiErrorEnvelope(getResponse, ApiErrorCodes.FORBIDDEN)

                val putResponse =
                    client.putSettings(
                        venueId,
                        token,
                        settingsBody(true, true, "MANUAL", disabled.updatedAt),
                    )
                assertEquals(HttpStatusCode.Forbidden, putResponse.status)
                assertApiErrorEnvelope(putResponse, ApiErrorCodes.FORBIDDEN)
            }

            assertEquals(auditCountBefore, loadSettingsAudits(jdbcUrl).size)
            assertEquals(disabled, readSettings(client.getSettings(venueId, ownerToken)))
        }

    @Test
    fun `PUT requires exact full object and rejects hidden authority fields`() =
        testApplication {
            val jdbcUrl = buildJdbcUrl("strict-payload")
            val config = buildConfig(jdbcUrl)
            environment { this.config = config }
            application { moduleWithOverrides(ModuleOverrides()) }
            client.get("/health")

            val ownerId = 93_001L
            val venueId = createVenueWithMember(jdbcUrl, ownerId, "OWNER")
            val token = issueToken(config, ownerId)
            val invalidBodies =
                listOf(
                    """
                    {
                      "teamScheduleModuleEnabled": true,
                      "todayStaffSource": "MANUAL",
                      "expectedUpdatedAt": "$DEFAULT_UPDATED_AT"
                    }
                    """.trimIndent(),
                    """
                    {
                      "teamScheduleModuleEnabled": true,
                      "guestTeamVisible": true,
                      "todayStaffSource": "MANUAL",
                      "expectedUpdatedAt": "$DEFAULT_UPDATED_AT",
                      "actorUserId": $ownerId
                    }
                    """.trimIndent(),
                    settingsBody(true, true, "schedule", DEFAULT_UPDATED_AT),
                    settingsBody(true, true, "MANUAL", "not-an-instant"),
                    """
                    {
                      "teamScheduleModuleEnabled": "true",
                      "guestTeamVisible": true,
                      "todayStaffSource": "MANUAL",
                      "expectedUpdatedAt": "$DEFAULT_UPDATED_AT"
                    }
                    """.trimIndent(),
                )

            invalidBodies.forEach { body ->
                val response = client.putSettings(venueId, token, body)
                assertEquals(HttpStatusCode.BadRequest, response.status, response.bodyAsText())
                assertApiErrorEnvelope(response, ApiErrorCodes.INVALID_INPUT)
            }

            assertNull(loadSettingsRow(jdbcUrl, venueId))
            assertTrue(loadSettingsAudits(jdbcUrl).isEmpty())
        }

    @Test
    fun `consecutive writes advance CAS preserve nested values and stale or no-op writes do not mutate`() =
        testApplication {
            val jdbcUrl = buildJdbcUrl("cas")
            val config = buildConfig(jdbcUrl)
            environment { this.config = config }
            application {
                moduleWithOverrides(
                    ModuleOverrides(staffScheduleClock = Clock.fixed(fixedNow, ZoneOffset.UTC)),
                )
            }
            client.get("/health")

            val ownerId = 94_001L
            val venueId = createVenueWithMember(jdbcUrl, ownerId, "OWNER")
            val token = issueToken(config, ownerId)
            val defaults = readSettings(client.getSettings(venueId, token))

            val disabled =
                readSettings(
                    client.putSettings(
                        venueId,
                        token,
                        settingsBody(false, false, "SCHEDULE", defaults.updatedAt),
                    ),
                )
            val reenabled =
                readSettings(
                    client.putSettings(
                        venueId,
                        token,
                        settingsBody(true, false, "SCHEDULE", disabled.updatedAt),
                    ),
                )

            assertTrue(Instant.parse(reenabled.updatedAt).isAfter(Instant.parse(disabled.updatedAt)))
            assertEquals(Instant.parse(disabled.updatedAt).plusMillis(1), Instant.parse(reenabled.updatedAt))
            assertEquals(false, reenabled.guestTeamVisible)
            assertEquals("SCHEDULE", reenabled.todayStaffSource)

            val noOp =
                readSettings(
                    client.putSettings(
                        venueId,
                        token,
                        settingsBody(true, false, "SCHEDULE", reenabled.updatedAt),
                    ),
                )
            assertEquals(reenabled, noOp)
            assertEquals(2, loadSettingsAudits(jdbcUrl).size)

            val staleResponse =
                client.putSettings(
                    venueId,
                    token,
                    settingsBody(false, true, "MANUAL", disabled.updatedAt),
                )
            assertEquals(HttpStatusCode.Conflict, staleResponse.status)
            val staleBody = staleResponse.bodyAsText()
            assertTrue(staleBody.contains(ApiErrorCodes.STAFF_MODULE_SETTINGS_STALE))
            assertTrue(staleBody.contains(STALE_MESSAGE))

            assertEquals(reenabled, readSettings(client.getSettings(venueId, token)))
            assertEquals(2, loadSettingsAudits(jdbcUrl).size)
        }

    @Test
    fun `unrelated venue settings writer advances future shared token and makes old staff CAS stale`() =
        testApplication {
            val jdbcUrl = buildJdbcUrl("shared-settings-cas")
            val config = buildConfig(jdbcUrl)
            environment { this.config = config }
            application {
                moduleWithOverrides(
                    ModuleOverrides(staffScheduleClock = Clock.fixed(fixedNow, ZoneOffset.UTC)),
                )
            }
            client.get("/health")

            val ownerId = 94_101L
            val venueId = createVenueWithMember(jdbcUrl, ownerId, "OWNER")
            val token = issueToken(config, ownerId)
            val defaults = readSettings(client.getSettings(venueId, token))
            val staffSaved =
                readSettings(
                    client.putSettings(
                        venueId,
                        token,
                        settingsBody(false, false, "SCHEDULE", defaults.updatedAt),
                    ),
                )
            val staffUpdatedAt = Instant.parse(staffSaved.updatedAt)
            assertEquals(fixedNow, staffUpdatedAt)

            VenueSettingsRepository(h2DataSource(jdbcUrl)).updateTimezone(
                venueId = venueId,
                timezone = "Asia/Tomsk",
                fallbackTimezone = VenueSettingsRepository.DEFAULT_AUTO_TIMEZONE,
            )

            val afterUnrelatedWrite = readSettings(client.getSettings(venueId, token))
            val unrelatedUpdatedAt = Instant.parse(afterUnrelatedWrite.updatedAt)
            assertEquals(staffUpdatedAt.plusMillis(1), unrelatedUpdatedAt)
            assertEquals(staffSaved.copy(updatedAt = unrelatedUpdatedAt.toString()), afterUnrelatedWrite)

            val staleResponse =
                client.putSettings(
                    venueId,
                    token,
                    settingsBody(true, true, "MANUAL", staffSaved.updatedAt),
                )
            assertEquals(HttpStatusCode.Conflict, staleResponse.status)
            assertApiErrorEnvelope(staleResponse, ApiErrorCodes.STAFF_MODULE_SETTINGS_STALE)
            assertEquals(afterUnrelatedWrite, readSettings(client.getSettings(venueId, token)))
            assertEquals(1, loadSettingsAudits(jdbcUrl).size)
        }

    @Test
    fun `direct repository update rejects actor after demotion or membership revoke`() =
        testApplication {
            val jdbcUrl = buildJdbcUrl("repository-actor-recheck")
            val config = buildConfig(jdbcUrl)
            environment { this.config = config }
            application {
                moduleWithOverrides(
                    ModuleOverrides(staffScheduleClock = Clock.fixed(fixedNow, ZoneOffset.UTC)),
                )
            }
            client.get("/health")

            val ownerId = 94_201L
            val venueId = createVenueWithMember(jdbcUrl, ownerId, "OWNER")
            val token = issueToken(config, ownerId)
            val defaults = readSettings(client.getSettings(venueId, token))
            val saved =
                readSettings(
                    client.putSettings(
                        venueId,
                        token,
                        settingsBody(false, true, "MANUAL", defaults.updatedAt),
                    ),
                )
            val dataSource = h2DataSource(jdbcUrl)
            val repository =
                VenueStaffModuleSettingsRepository(
                    dataSource = dataSource,
                    clock = Clock.fixed(fixedNow.plusSeconds(1), ZoneOffset.UTC),
                )
            val auditRepository = AuditLogRepository(dataSource, json)
            val attemptedWrite =
                VenueStaffModuleSettingsWrite(
                    teamScheduleModuleEnabled = true,
                    guestTeamVisible = false,
                    todayStaffSource = TodayStaffSource.SCHEDULE,
                )
            val before = loadSettingsRow(jdbcUrl, venueId)
            val auditCountBefore = loadSettingsAudits(jdbcUrl).size

            updateVenueMemberRole(jdbcUrl, venueId, ownerId, "STAFF")
            assertFailsWith<ForbiddenException> {
                repository.update(
                    venueId = venueId,
                    actorUserId = ownerId,
                    expectedUpdatedAt = Instant.parse(saved.updatedAt),
                    input = attemptedWrite,
                    auditLogRepository = auditRepository,
                )
            }
            assertEquals(before, loadSettingsRow(jdbcUrl, venueId))
            assertEquals(auditCountBefore, loadSettingsAudits(jdbcUrl).size)

            updateVenueMemberRole(jdbcUrl, venueId, ownerId, "OWNER")
            deleteVenueMember(jdbcUrl, venueId, ownerId)
            assertFailsWith<ForbiddenException> {
                repository.update(
                    venueId = venueId,
                    actorUserId = ownerId,
                    expectedUpdatedAt = Instant.parse(saved.updatedAt),
                    input = attemptedWrite,
                    auditLogRepository = auditRepository,
                )
            }
            assertEquals(before, loadSettingsRow(jdbcUrl, venueId))
            assertEquals(auditCountBefore, loadSettingsAudits(jdbcUrl).size)
        }

    @Test
    fun `default first PUT materializes row without false success audit`() =
        testApplication {
            val jdbcUrl = buildJdbcUrl("materialize-noop")
            val config = buildConfig(jdbcUrl)
            environment { this.config = config }
            application {
                moduleWithOverrides(
                    ModuleOverrides(staffScheduleClock = Clock.fixed(fixedNow, ZoneOffset.UTC)),
                )
            }
            client.get("/health")

            val ownerId = 95_001L
            val venueId = createVenueWithMember(jdbcUrl, ownerId, "OWNER")
            val token = issueToken(config, ownerId)
            val defaults = readSettings(client.getSettings(venueId, token))
            assertNull(loadSettingsRow(jdbcUrl, venueId))

            val materialized =
                readSettings(
                    client.putSettings(
                        venueId,
                        token,
                        settingsBody(true, true, "MANUAL", defaults.updatedAt),
                    ),
                )
            assertTrue(Instant.parse(materialized.updatedAt).isAfter(Instant.EPOCH))
            assertNotNull(loadSettingsRow(jdbcUrl, venueId))
            assertTrue(loadSettingsAudits(jdbcUrl).isEmpty())

            val repeated =
                readSettings(
                    client.putSettings(
                        venueId,
                        token,
                        settingsBody(true, true, "MANUAL", materialized.updatedAt),
                    ),
                )
            assertEquals(materialized, repeated)
            assertTrue(loadSettingsAudits(jdbcUrl).isEmpty())
        }

    @Test
    fun `audit failure rolls back settings mutation and lazy materialization`() =
        testApplication {
            val jdbcUrl = buildJdbcUrl("audit-rollback")
            val config = buildConfig(jdbcUrl)
            environment { this.config = config }
            application { moduleWithOverrides(ModuleOverrides()) }
            client.get("/health")

            val ownerId = 96_001L
            val venueId = createVenueWithMember(jdbcUrl, ownerId, "OWNER")
            val token = issueToken(config, ownerId)
            val defaults = readSettings(client.getSettings(venueId, token))
            DriverManager.getConnection(jdbcUrl, "sa", "").use { connection ->
                connection.createStatement().use { statement ->
                    statement.execute(
                        """
                        ALTER TABLE audit_log
                        ADD CONSTRAINT reject_staff_module_settings_audit
                        CHECK (entity_type <> 'venue_staff_module_settings')
                        """.trimIndent(),
                    )
                }
            }

            val response =
                client.putSettings(
                    venueId,
                    token,
                    settingsBody(false, false, "SCHEDULE", defaults.updatedAt),
                )
            assertEquals(HttpStatusCode.ServiceUnavailable, response.status)
            assertApiErrorEnvelope(response, ApiErrorCodes.DATABASE_UNAVAILABLE)
            assertNull(loadSettingsRow(jdbcUrl, venueId))
            assertTrue(loadSettingsAudits(jdbcUrl).isEmpty())
        }

    private suspend fun HttpClient.getSettings(
        venueId: Long,
        token: String,
    ): HttpResponse =
        get("/api/venue/$venueId/staff-module-settings") {
            headers { append(HttpHeaders.Authorization, "Bearer $token") }
        }

    private suspend fun HttpClient.putSettings(
        venueId: Long,
        token: String,
        body: String,
    ): HttpResponse =
        put("/api/venue/$venueId/staff-module-settings") {
            headers { append(HttpHeaders.Authorization, "Bearer $token") }
            contentType(ContentType.Application.Json)
            setBody(body)
        }

    private suspend fun readSettings(response: HttpResponse): VenueStaffModuleSettingsResponse {
        assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
        return json.decodeFromString(VenueStaffModuleSettingsResponse.serializer(), response.bodyAsText())
    }

    private fun settingsBody(
        teamScheduleModuleEnabled: Boolean,
        guestTeamVisible: Boolean,
        todayStaffSource: String,
        expectedUpdatedAt: String,
    ): String =
        """
        {
          "teamScheduleModuleEnabled": $teamScheduleModuleEnabled,
          "guestTeamVisible": $guestTeamVisible,
          "todayStaffSource": "$todayStaffSource",
          "expectedUpdatedAt": "$expectedUpdatedAt"
        }
        """.trimIndent()

    private fun buildJdbcUrl(prefix: String): String =
        "jdbc:h2:mem:$prefix-${UUID.randomUUID()};MODE=PostgreSQL;" +
            "DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1"

    private fun buildConfig(jdbcUrl: String): MapApplicationConfig =
        MapApplicationConfig(
            "app.env" to APP_ENV,
            "api.session.jwtSecret" to "test-secret",
            "db.jdbcUrl" to jdbcUrl,
            "db.user" to "sa",
            "db.password" to "",
        )

    private fun h2DataSource(jdbcUrl: String): JdbcDataSource =
        JdbcDataSource().apply {
            setURL(jdbcUrl)
            user = "sa"
            password = ""
        }

    private fun issueToken(
        config: MapApplicationConfig,
        userId: Long,
    ): String = SessionTokenService(SessionTokenConfig.from(config, APP_ENV)).issueToken(userId).token

    private fun createVenueWithMember(
        jdbcUrl: String,
        userId: Long,
        role: String,
    ): Long {
        seedUser(jdbcUrl, userId)
        DriverManager.getConnection(jdbcUrl, "sa", "").use { connection ->
            val venueId =
                connection.prepareStatement(
                    """
                    INSERT INTO venues (name, city, address, status)
                    VALUES ('Venue', 'City', 'Address', 'PUBLISHED')
                    """.trimIndent(),
                    Statement.RETURN_GENERATED_KEYS,
                ).use { statement ->
                    statement.executeUpdate()
                    statement.generatedKeys.use { resultSet ->
                        check(resultSet.next())
                        resultSet.getLong(1)
                    }
                }
            insertVenueMember(connection = connection, venueId = venueId, userId = userId, role = role)
            return venueId
        }
    }

    private fun addVenueMember(
        jdbcUrl: String,
        venueId: Long,
        userId: Long,
        role: String,
    ) {
        seedUser(jdbcUrl, userId)
        DriverManager.getConnection(jdbcUrl, "sa", "").use { connection ->
            insertVenueMember(connection, venueId, userId, role)
        }
    }

    private fun updateVenueMemberRole(
        jdbcUrl: String,
        venueId: Long,
        userId: Long,
        role: String,
    ) {
        DriverManager.getConnection(jdbcUrl, "sa", "").use { connection ->
            connection.prepareStatement(
                "UPDATE venue_members SET role = ? WHERE venue_id = ? AND user_id = ?",
            ).use { statement ->
                statement.setString(1, role)
                statement.setLong(2, venueId)
                statement.setLong(3, userId)
                assertEquals(1, statement.executeUpdate())
            }
        }
    }

    private fun deleteVenueMember(
        jdbcUrl: String,
        venueId: Long,
        userId: Long,
    ) {
        DriverManager.getConnection(jdbcUrl, "sa", "").use { connection ->
            connection.prepareStatement(
                "DELETE FROM venue_members WHERE venue_id = ? AND user_id = ?",
            ).use { statement ->
                statement.setLong(1, venueId)
                statement.setLong(2, userId)
                assertEquals(1, statement.executeUpdate())
            }
        }
    }

    private fun seedUser(
        jdbcUrl: String,
        userId: Long,
    ) {
        DriverManager.getConnection(jdbcUrl, "sa", "").use { connection ->
            connection.prepareStatement(
                """
                MERGE INTO users (telegram_user_id, username, first_name, last_name)
                KEY (telegram_user_id)
                VALUES (?, ?, 'Test', 'User')
                """.trimIndent(),
            ).use { statement ->
                statement.setLong(1, userId)
                statement.setString(2, "user$userId")
                statement.executeUpdate()
            }
        }
    }

    private fun insertVenueMember(
        connection: java.sql.Connection,
        venueId: Long,
        userId: Long,
        role: String,
    ) {
        connection.prepareStatement(
            "INSERT INTO venue_members (venue_id, user_id, role) VALUES (?, ?, ?)",
        ).use { statement ->
            statement.setLong(1, venueId)
            statement.setLong(2, userId)
            statement.setString(3, role)
            statement.executeUpdate()
        }
    }

    private fun loadSettingsRow(
        jdbcUrl: String,
        venueId: Long,
    ): SettingsRow? {
        DriverManager.getConnection(jdbcUrl, "sa", "").use { connection ->
            connection.prepareStatement(
                """
                SELECT team_schedule_module_enabled,
                       guest_team_visible,
                       today_staff_source,
                       updated_at
                FROM venue_settings
                WHERE venue_id = ?
                """.trimIndent(),
            ).use { statement ->
                statement.setLong(1, venueId)
                statement.executeQuery().use { resultSet ->
                    if (!resultSet.next()) return null
                    return SettingsRow(
                        teamScheduleModuleEnabled = resultSet.getBoolean("team_schedule_module_enabled"),
                        guestTeamVisible = resultSet.getBoolean("guest_team_visible"),
                        todayStaffSource = resultSet.getString("today_staff_source"),
                        updatedAt = resultSet.getTimestamp("updated_at").toInstant(),
                    )
                }
            }
        }
    }

    private fun loadSettingsAudits(jdbcUrl: String): List<AuditRow> {
        DriverManager.getConnection(jdbcUrl, "sa", "").use { connection ->
            connection.prepareStatement(
                """
                SELECT actor_user_id, action, entity_type, entity_id, payload_json
                FROM audit_log
                WHERE action = 'STAFF_MODULE_SETTINGS_UPDATED'
                ORDER BY id
                """.trimIndent(),
            ).use { statement ->
                statement.executeQuery().use { resultSet ->
                    val rows = mutableListOf<AuditRow>()
                    while (resultSet.next()) {
                        rows +=
                            AuditRow(
                                actorUserId = resultSet.getLong("actor_user_id"),
                                action = resultSet.getString("action"),
                                entityType = resultSet.getString("entity_type"),
                                entityId = resultSet.getLong("entity_id"),
                                payload = json.parseToJsonElement(resultSet.getString("payload_json")).jsonObject,
                            )
                    }
                    return rows
                }
            }
        }
    }

    private fun assertSafeAudit(
        row: AuditRow,
        actorUserId: Long,
        venueId: Long,
        changedFields: Set<String>,
    ) {
        assertEquals(actorUserId, row.actorUserId)
        assertEquals("STAFF_MODULE_SETTINGS_UPDATED", row.action)
        assertEquals("venue_staff_module_settings", row.entityType)
        assertEquals(venueId, row.entityId)
        assertEquals(setOf("venueId", "changedFields", "old", "new"), row.payload.keys)
        assertEquals(venueId.toString(), row.payload.getValue("venueId").jsonPrimitive.content)
        assertEquals(
            changedFields,
            row.payload.getValue("changedFields").jsonArray.map { it.jsonPrimitive.content }.toSet(),
        )
        val old = row.payload.getValue("old").jsonObject
        val new = row.payload.getValue("new").jsonObject
        val safeKeys = setOf("teamScheduleModuleEnabled", "guestTeamVisible", "todayStaffSource")
        assertEquals(safeKeys, old.keys)
        assertEquals(safeKeys, new.keys)
        assertEquals(true, old.getValue("teamScheduleModuleEnabled").jsonPrimitive.boolean)
        assertEquals("MANUAL", old.getValue("todayStaffSource").jsonPrimitive.content)
        assertFalse("actorUserId" in row.payload)
        assertFalse("staffProfileId" in row.payload)
        assertFalse("shiftId" in row.payload)
    }

    private data class SettingsRow(
        val teamScheduleModuleEnabled: Boolean,
        val guestTeamVisible: Boolean,
        val todayStaffSource: String,
        val updatedAt: Instant,
    )

    private data class AuditRow(
        val actorUserId: Long,
        val action: String,
        val entityType: String,
        val entityId: Long,
        val payload: JsonObject,
    )

    private companion object {
        const val APP_ENV = "test"
        const val DEFAULT_UPDATED_AT = "1970-01-01T00:00:00Z"
        const val STALE_MESSAGE =
            "Настройки изменились. " +
                "Обновите данные и повторите действие."
    }
}
