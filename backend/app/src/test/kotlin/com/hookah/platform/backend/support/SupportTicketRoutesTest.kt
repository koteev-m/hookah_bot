package com.hookah.platform.backend.support

import com.hookah.platform.backend.miniapp.session.SessionTokenConfig
import com.hookah.platform.backend.miniapp.session.SessionTokenService
import com.hookah.platform.backend.module
import io.ktor.client.request.get
import io.ktor.client.request.headers
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.config.MapApplicationConfig
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.sql.DriverManager
import java.sql.Statement
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SupportTicketRoutesTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `support tickets are scoped across guest venue staff and platform`() =
        testApplication {
            val jdbcUrl = buildJdbcUrl("support-ticket-routes")
            val platformOwnerId = 900001L
            val config = buildConfig(jdbcUrl, platformOwnerId)

            environment { this.config = config }
            application { module() }
            client.get("/health")

            val venueId = seedVenue(jdbcUrl)
            seedUser(jdbcUrl, GUEST_ID)
            seedUser(jdbcUrl, OWNER_ID)
            seedUser(jdbcUrl, MANAGER_ID)
            seedUser(jdbcUrl, STAFF_ID)
            seedUser(jdbcUrl, platformOwnerId)
            seedVenueMember(jdbcUrl, venueId, OWNER_ID, "OWNER")
            seedVenueMember(jdbcUrl, venueId, MANAGER_ID, "MANAGER")
            seedVenueMember(jdbcUrl, venueId, STAFF_ID, "STAFF")

            val guestToken = issueToken(config, GUEST_ID)
            val ownerToken = issueToken(config, OWNER_ID)
            val managerToken = issueToken(config, MANAGER_ID)
            val staffToken = issueToken(config, STAFF_ID)
            val platformToken = issueToken(config, platformOwnerId)

            val platformOnlyResponse =
                client.post("/api/guest/support/threads") {
                    headers {
                        append(HttpHeaders.Authorization, "Bearer $guestToken")
                        append(HttpHeaders.ContentType, ContentType.Application.Json.toString())
                    }
                    setBody(
                        """
                        {
                          "category":"MINIAPP_TECHNICAL",
                          "title":"Mini App зависает",
                          "message":"Не открывается экран заказа",
                          "appVersion":"1.2.3",
                          "correlationId":"corr-support-test"
                        }
                        """.trimIndent(),
                    )
                }
            assertEquals(HttpStatusCode.OK, platformOnlyResponse.status)
            val platformOnlyThread =
                json.parseToJsonElement(platformOnlyResponse.bodyAsText())
                    .jsonObject
                    .getValue("thread")
                    .jsonObject
            val platformOnlyThreadId = platformOnlyThread.getValue("threadId").jsonPrimitive.content.toLong()
            assertTrue(platformOnlyThread["venueId"] == null || platformOnlyThread["venueId"] == JsonNull)
            assertEquals(null, supportThreadVenueId(jdbcUrl, platformOnlyThreadId))
            assertEquals("SUPPORT_TICKET", platformOnlyThread.getValue("threadType").jsonPrimitive.content)
            assertEquals("PLATFORM", platformOnlyThread.getValue("assigneeScope").jsonPrimitive.content)
            assertEquals("NEW", platformOnlyThread.getValue("status").jsonPrimitive.content)
            assertEquals("Новый", platformOnlyThread.getValue("statusLabel").jsonPrimitive.content)
            assertEquals("MINIAPP_TECHNICAL", platformOnlyThread.getValue("category").jsonPrimitive.content)

            val platformOnlyUpdatedAt = supportThreadUpdatedAt(jdbcUrl, platformOnlyThreadId)
            val venueListForPlatformOnly =
                client.get("/api/venue/$venueId/support/threads?filter=active") {
                    headers { append(HttpHeaders.Authorization, "Bearer $ownerToken") }
                }
            assertEquals(HttpStatusCode.OK, venueListForPlatformOnly.status)
            assertTrue(
                json.parseToJsonElement(venueListForPlatformOnly.bodyAsText())
                    .jsonObject
                    .getValue("items")
                    .jsonArray
                    .isEmpty(),
            )

            val platformList =
                client.get("/api/platform/support/threads?filter=active") {
                    headers { append(HttpHeaders.Authorization, "Bearer $platformToken") }
                }
            assertEquals(HttpStatusCode.OK, platformList.status)
            assertEquals(
                platformOnlyThreadId.toString(),
                json.parseToJsonElement(platformList.bodyAsText())
                    .jsonObject
                    .getValue("items")
                    .jsonArray
                    .single()
                    .jsonObject
                    .getValue("threadId")
                    .jsonPrimitive
                    .content,
            )
            assertEquals(platformOnlyUpdatedAt, supportThreadUpdatedAt(jdbcUrl, platformOnlyThreadId))

            val platformReply =
                client.post("/api/platform/support/threads/$platformOnlyThreadId/messages") {
                    headers {
                        append(HttpHeaders.Authorization, "Bearer $platformToken")
                        append(HttpHeaders.ContentType, ContentType.Application.Json.toString())
                    }
                    setBody("""{"message":"Проверяем техническую проблему."}""")
                }
            assertEquals(HttpStatusCode.OK, platformReply.status)
            assertEquals(
                "WAITING_USER",
                json.parseToJsonElement(platformReply.bodyAsText())
                    .jsonObject
                    .getValue("thread")
                    .jsonObject
                    .getValue("status")
                    .jsonPrimitive
                    .content,
            )
            assertTrue(outboxTexts(jdbcUrl, GUEST_ID).last().contains("Проверяем техническую проблему."))

            val venueThreadId = seedVenueSupportTicket(jdbcUrl, venueId, GUEST_ID)
            val staffListResponse =
                client.get("/api/venue/$venueId/support/threads?filter=active") {
                    headers { append(HttpHeaders.Authorization, "Bearer $staffToken") }
                }
            assertEquals(HttpStatusCode.Forbidden, staffListResponse.status)

            val managerListResponse =
                client.get("/api/venue/$venueId/support/threads?filter=active") {
                    headers { append(HttpHeaders.Authorization, "Bearer $managerToken") }
                }
            assertEquals(HttpStatusCode.OK, managerListResponse.status)
            assertEquals(
                venueThreadId.toString(),
                json.parseToJsonElement(managerListResponse.bodyAsText())
                    .jsonObject
                    .getValue("items")
                    .jsonArray
                    .single()
                    .jsonObject
                    .getValue("threadId")
                    .jsonPrimitive
                    .content,
            )

            val venueReplyResponse =
                client.post("/api/venue/$venueId/support/threads/$venueThreadId/messages") {
                    headers {
                        append(HttpHeaders.Authorization, "Bearer $ownerToken")
                        append(HttpHeaders.ContentType, ContentType.Application.Json.toString())
                    }
                    setBody("""{"message":"Разберёмся на месте."}""")
                }
            assertEquals(HttpStatusCode.OK, venueReplyResponse.status)

            val escalateResponse =
                client.post("/api/venue/$venueId/support/threads/$venueThreadId/escalate") {
                    headers { append(HttpHeaders.Authorization, "Bearer $managerToken") }
                }
            assertEquals(HttpStatusCode.OK, escalateResponse.status)
            val escalatedThread =
                json.parseToJsonElement(escalateResponse.bodyAsText())
                    .jsonObject
                    .getValue("thread")
                    .jsonObject
            assertEquals("PLATFORM", escalatedThread.getValue("assigneeScope").jsonPrimitive.content)
            assertEquals("WAITING_USER", escalatedThread.getValue("status").jsonPrimitive.content)

            val venueReplyWhilePlatformAssigned =
                client.post("/api/venue/$venueId/support/threads/$venueThreadId/messages") {
                    headers {
                        append(HttpHeaders.Authorization, "Bearer $ownerToken")
                        append(HttpHeaders.ContentType, ContentType.Application.Json.toString())
                    }
                    setBody("""{"message":"Ответ после эскалации."}""")
                }
            assertEquals(HttpStatusCode.Forbidden, venueReplyWhilePlatformAssigned.status)

            val assignBackResponse =
                client.post("/api/platform/support/threads/$venueThreadId/assign") {
                    headers {
                        append(HttpHeaders.Authorization, "Bearer $platformToken")
                        append(HttpHeaders.ContentType, ContentType.Application.Json.toString())
                    }
                    setBody("""{"assigneeScope":"VENUE","venueId":$venueId}""")
                }
            assertEquals(HttpStatusCode.OK, assignBackResponse.status)
            assertEquals(
                "VENUE",
                json.parseToJsonElement(assignBackResponse.bodyAsText())
                    .jsonObject
                    .getValue("thread")
                    .jsonObject
                    .getValue("assigneeScope")
                    .jsonPrimitive
                    .content,
            )

            val closeResponse =
                client.post("/api/platform/support/threads/$venueThreadId/status") {
                    headers {
                        append(HttpHeaders.Authorization, "Bearer $platformToken")
                        append(HttpHeaders.ContentType, ContentType.Application.Json.toString())
                    }
                    setBody("""{"status":"CLOSED"}""")
                }
            assertEquals(HttpStatusCode.OK, closeResponse.status)
            assertEquals(
                "CLOSED",
                json.parseToJsonElement(closeResponse.bodyAsText())
                    .jsonObject
                    .getValue("thread")
                    .jsonObject
                    .getValue("status")
                    .jsonPrimitive
                    .content,
            )

            val auditActions = supportAuditActions(jdbcUrl, venueThreadId)
            assertTrue("SUPPORT_TICKET_SCOPE_CHANGED" in auditActions)
            assertTrue("SUPPORT_TICKET_ESCALATED" in auditActions)
            assertTrue("SUPPORT_TICKET_ASSIGNED" in auditActions)
            assertTrue("SUPPORT_TICKET_STATUS_CHANGED" in auditActions)
        }

    private fun buildJdbcUrl(prefix: String): String {
        val dbName = "$prefix-${UUID.randomUUID()}"
        return "jdbc:h2:mem:$dbName;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1"
    }

    private fun buildConfig(
        jdbcUrl: String,
        platformOwnerId: Long,
    ): MapApplicationConfig =
        MapApplicationConfig(
            "ktor.environment" to "test",
            "app.env" to "test",
            "db.jdbcUrl" to jdbcUrl,
            "db.user" to "sa",
            "db.password" to "",
            "api.session.jwtSecret" to "secret-secret-secret-secret-secret",
            "api.session.issuer" to "hookah",
            "api.session.audience" to "miniapp",
            "api.session.ttlSeconds" to "3600",
            "platform.ownerUserId" to platformOwnerId.toString(),
            "venue.staffInviteSecretPepper" to "invite-pepper",
        )

    private fun issueToken(
        config: MapApplicationConfig,
        userId: Long,
    ): String {
        val tokenConfig =
            SessionTokenConfig(
                jwtSecret = config.property("api.session.jwtSecret").getString(),
                issuer = config.property("api.session.issuer").getString(),
                audience = config.property("api.session.audience").getString(),
                ttlSeconds = config.property("api.session.ttlSeconds").getString().toLong(),
            )
        return SessionTokenService(tokenConfig).issueToken(userId).token
    }

    private fun seedVenue(jdbcUrl: String): Long =
        DriverManager.getConnection(jdbcUrl, "sa", "").use { connection ->
            val venueId =
                connection.prepareStatement(
                    """
                    INSERT INTO venues (name, city, address, status)
                    VALUES ('Support Venue', 'City', 'Address', 'PUBLISHED')
                    """.trimIndent(),
                    Statement.RETURN_GENERATED_KEYS,
                ).use { statement ->
                    statement.executeUpdate()
                    statement.generatedKeys.use { keys ->
                        keys.next()
                        keys.getLong(1)
                    }
                }
            connection.prepareStatement(
                """
                INSERT INTO venue_subscriptions (venue_id, status, trial_end, paid_start, updated_at)
                VALUES (?, 'ACTIVE', NULL, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """.trimIndent(),
            ).use { statement ->
                statement.setLong(1, venueId)
                statement.executeUpdate()
            }
            venueId
        }

    private fun seedUser(
        jdbcUrl: String,
        userId: Long,
    ) {
        DriverManager.getConnection(jdbcUrl, "sa", "").use { connection ->
            connection.prepareStatement(
                """
                INSERT INTO users (telegram_user_id, username, first_name, updated_at)
                VALUES (?, ?, 'Name', CURRENT_TIMESTAMP)
                """.trimIndent(),
            ).use { statement ->
                statement.setLong(1, userId)
                statement.setString(2, "u$userId")
                statement.executeUpdate()
            }
        }
    }

    private fun seedVenueMember(
        jdbcUrl: String,
        venueId: Long,
        userId: Long,
        role: String,
    ) {
        DriverManager.getConnection(jdbcUrl, "sa", "").use { connection ->
            connection.prepareStatement(
                """
                INSERT INTO venue_members (venue_id, user_id, role, created_at)
                VALUES (?, ?, ?, CURRENT_TIMESTAMP)
                """.trimIndent(),
            ).use { statement ->
                statement.setLong(1, venueId)
                statement.setLong(2, userId)
                statement.setString(3, role)
                statement.executeUpdate()
            }
        }
    }

    private fun seedVenueSupportTicket(
        jdbcUrl: String,
        venueId: Long,
        guestUserId: Long,
    ): Long =
        DriverManager.getConnection(jdbcUrl, "sa", "").use { connection ->
            val threadId =
                connection.prepareStatement(
                    """
                    INSERT INTO support_threads (
                        venue_id,
                        guest_user_id,
                        category,
                        status,
                        title,
                        last_message_at,
                        thread_type,
                        assignee_scope,
                        created_source
                    )
                    VALUES (?, ?, 'OTHER', 'NEW', 'Проблема в зале', CURRENT_TIMESTAMP,
                            'SUPPORT_TICKET', 'VENUE', 'GUEST_MINIAPP')
                    """.trimIndent(),
                    Statement.RETURN_GENERATED_KEYS,
                ).use { statement ->
                    statement.setLong(1, venueId)
                    statement.setLong(2, guestUserId)
                    statement.executeUpdate()
                    statement.generatedKeys.use { keys ->
                        keys.next()
                        keys.getLong(1)
                    }
                }
            connection.prepareStatement(
                """
                INSERT INTO support_messages (thread_id, author_user_id, author_role, source, text)
                VALUES (?, ?, 'GUEST', 'GUEST_MINIAPP', 'Нужна помощь в зале')
                """.trimIndent(),
            ).use { statement ->
                statement.setLong(1, threadId)
                statement.setLong(2, guestUserId)
                statement.executeUpdate()
            }
            threadId
        }

    private fun supportThreadUpdatedAt(
        jdbcUrl: String,
        threadId: Long,
    ): String =
        DriverManager.getConnection(jdbcUrl, "sa", "").use { connection ->
            connection.prepareStatement(
                """
                SELECT CAST(updated_at AS VARCHAR) AS updated_at
                FROM support_threads
                WHERE id = ?
                """.trimIndent(),
            ).use { statement ->
                statement.setLong(1, threadId)
                statement.executeQuery().use { rs ->
                    rs.next()
                    rs.getString("updated_at")
                }
            }
        }

    private fun supportThreadVenueId(
        jdbcUrl: String,
        threadId: Long,
    ): Long? =
        DriverManager.getConnection(jdbcUrl, "sa", "").use { connection ->
            connection.prepareStatement(
                """
                SELECT venue_id
                FROM support_threads
                WHERE id = ?
                """.trimIndent(),
            ).use { statement ->
                statement.setLong(1, threadId)
                statement.executeQuery().use { rs ->
                    rs.next()
                    rs.getLong("venue_id").takeIf { !rs.wasNull() }
                }
            }
        }

    private fun outboxTexts(
        jdbcUrl: String,
        chatId: Long,
    ): List<String> =
        DriverManager.getConnection(jdbcUrl, "sa", "").use { connection ->
            connection.prepareStatement(
                """
                SELECT payload_json
                FROM telegram_outbox
                WHERE chat_id = ?
                ORDER BY id
                """.trimIndent(),
            ).use { statement ->
                statement.setLong(1, chatId)
                statement.executeQuery().use { rs ->
                    val result = mutableListOf<String>()
                    while (rs.next()) {
                        val payload = json.parseToJsonElement(rs.getString("payload_json")).jsonObject
                        result.add(payload.getValue("text").jsonPrimitive.content)
                    }
                    result
                }
            }
        }

    private fun supportAuditActions(
        jdbcUrl: String,
        threadId: Long,
    ): List<String> =
        DriverManager.getConnection(jdbcUrl, "sa", "").use { connection ->
            connection.prepareStatement(
                """
                SELECT action
                FROM audit_log
                WHERE entity_type = 'support_ticket'
                  AND entity_id = ?
                ORDER BY id
                """.trimIndent(),
            ).use { statement ->
                statement.setLong(1, threadId)
                statement.executeQuery().use { rs ->
                    val result = mutableListOf<String>()
                    while (rs.next()) {
                        result.add(rs.getString("action"))
                    }
                    result
                }
            }
        }

    private companion object {
        private const val GUEST_ID = 424242L
        private const val OWNER_ID = 666666L
        private const val MANAGER_ID = 777777L
        private const val STAFF_ID = 888888L
    }
}
