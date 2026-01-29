package com.hookah.platform.backend.miniapp.venue

import com.hookah.platform.backend.api.ApiErrorCodes
import com.hookah.platform.backend.miniapp.session.SessionTokenConfig
import com.hookah.platform.backend.miniapp.session.SessionTokenService
import com.hookah.platform.backend.module
import com.hookah.platform.backend.test.assertApiErrorEnvelope
import io.ktor.client.call.body
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
import java.sql.DriverManager
import java.sql.Statement
import java.time.Instant
import java.util.UUID
import java.util.zip.ZipInputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

class VenueTableRoutesTest {
    private val json = Json { ignoreUnknownKeys = true }
    private val appEnv = "test"

    @Test
    fun `owner can batch create tables`() = testApplication {
        val jdbcUrl = buildJdbcUrl("tables-create")
        val config = buildConfig(jdbcUrl)

        environment { this.config = config }
        application { module() }

        client.get("/health")

        val venueId = seedVenueWithRole(jdbcUrl, TELEGRAM_USER_ID, "OWNER")
        val token = issueToken(config)

        val response = client.post("/api/venue/tables/batch-create?venueId=$venueId") {
            headers {
                append(HttpHeaders.Authorization, "Bearer $token")
                append(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            }
            setBody("""{"count":3,"startNumber":5}""")
        }

        assertEquals(HttpStatusCode.OK, response.status)
        val payload = json.decodeFromString(VenueTableBatchCreateResponse.serializer(), response.bodyAsText())
        assertEquals(3, payload.count)
        assertEquals(listOf(5, 6, 7), payload.tables.map { it.tableNumber })

        DriverManager.getConnection(jdbcUrl, "sa", "").use { connection ->
            connection.prepareStatement(
                "SELECT COUNT(*) FROM venue_tables WHERE venue_id = ?"
            ).use { statement ->
                statement.setLong(1, venueId)
                statement.executeQuery().use { rs ->
                    assertTrue(rs.next())
                    assertEquals(3, rs.getInt(1))
                }
            }
            connection.prepareStatement(
                """
                    SELECT COUNT(*) FROM table_tokens tt
                    JOIN venue_tables vt ON vt.id = tt.table_id
                    WHERE vt.venue_id = ? AND tt.is_active = true
                """.trimIndent()
            ).use { statement ->
                statement.setLong(1, venueId)
                statement.executeQuery().use { rs ->
                    assertTrue(rs.next())
                    assertEquals(3, rs.getInt(1))
                }
            }
        }
    }

    @Test
    fun `rotate invalidates old token`() = testApplication {
        val jdbcUrl = buildJdbcUrl("tables-rotate")
        val config = buildConfig(jdbcUrl)

        environment { this.config = config }
        application { module() }

        client.get("/health")

        val venueId = seedVenueWithRole(jdbcUrl, TELEGRAM_USER_ID, "OWNER")
        val tableId = seedTable(jdbcUrl, venueId, 12)
        val oldToken = "old-token"
        seedTableToken(jdbcUrl, tableId, oldToken)
        seedSubscription(jdbcUrl, venueId, "active")

        val token = issueToken(config)
        val rotateResponse = client.post("/api/venue/tables/$tableId/rotate-token?venueId=$venueId") {
            headers { append(HttpHeaders.Authorization, "Bearer $token") }
        }

        assertEquals(HttpStatusCode.OK, rotateResponse.status)

        val resolveResponse = client.get("/api/guest/table/resolve?tableToken=$oldToken") {
            headers { append(HttpHeaders.Authorization, "Bearer $token") }
        }

        assertEquals(HttpStatusCode.NotFound, resolveResponse.status)
        assertApiErrorEnvelope(resolveResponse, ApiErrorCodes.NOT_FOUND)

        DriverManager.getConnection(jdbcUrl, "sa", "").use { connection ->
            connection.prepareStatement(
                "SELECT is_active FROM table_tokens WHERE token = ?"
            ).use { statement ->
                statement.setString(1, oldToken)
                statement.executeQuery().use { rs ->
                    assertTrue(rs.next())
                    assertEquals(false, rs.getBoolean(1))
                }
            }
        }
    }

    @Test
    fun `qr package export returns zip with entries`() = testApplication {
        val jdbcUrl = buildJdbcUrl("tables-export")
        val config = buildConfig(jdbcUrl, webAppUrl = "https://example.com/miniapp/")

        environment { this.config = config }
        application { module() }

        client.get("/health")

        val venueId = seedVenueWithRole(jdbcUrl, TELEGRAM_USER_ID, "OWNER")
        val firstTable = seedTable(jdbcUrl, venueId, 1)
        val secondTable = seedTable(jdbcUrl, venueId, 2)
        seedTableToken(jdbcUrl, firstTable, "token-one")
        seedTableToken(jdbcUrl, secondTable, "token-two")
        seedSubscription(jdbcUrl, venueId, "active")

        val token = issueToken(config)
        val response = client.get("/api/venue/tables/qr-package?venueId=$venueId&format=zip") {
            headers { append(HttpHeaders.Authorization, "Bearer $token") }
        }

        assertEquals(HttpStatusCode.OK, response.status)
        assertNotNull(response.headers[HttpHeaders.ContentDisposition])
        assertEquals("application/zip", response.headers[HttpHeaders.ContentType])
        val bytes = response.body<ByteArray>()
        val entries = mutableListOf<String>()
        ZipInputStream(bytes.inputStream()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                entries.add(entry.name)
                zip.closeEntry()
            }
        }
        assertEquals(3, entries.size)
        assertTrue(entries.contains("table_1.png"))
        assertTrue(entries.contains("table_2.png"))
        assertTrue(entries.contains("manifest.json"))
    }

    private fun buildJdbcUrl(prefix: String): String {
        val dbName = "$prefix-${UUID.randomUUID()}"
        return "jdbc:h2:mem:$dbName;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1"
    }

    private fun buildConfig(jdbcUrl: String, webAppUrl: String? = null): MapApplicationConfig {
        val config = mutableMapOf(
            "app.env" to appEnv,
            "api.session.jwtSecret" to "test-secret",
            "db.jdbcUrl" to jdbcUrl,
            "db.user" to "sa",
            "db.password" to ""
        )
        if (webAppUrl != null) {
            config["telegram.webAppPublicUrl"] = webAppUrl
        }
        return MapApplicationConfig(*config.map { it.key to it.value }.toTypedArray())
    }

    private fun issueToken(config: MapApplicationConfig): String {
        val service = SessionTokenService(SessionTokenConfig.from(config, appEnv))
        return service.issueToken(TELEGRAM_USER_ID).token
    }

    private fun seedVenueWithRole(jdbcUrl: String, userId: Long, role: String): Long {
        DriverManager.getConnection(jdbcUrl, "sa", "").use { connection ->
            connection.prepareStatement(
                """
                    MERGE INTO users (telegram_user_id, username, first_name, last_name)
                    KEY (telegram_user_id)
                    VALUES (?, 'user', 'Test', 'User')
                """.trimIndent()
            ).use { statement ->
                statement.setLong(1, userId)
                statement.executeUpdate()
            }
            val venueId = connection.prepareStatement(
                """
                    INSERT INTO venues (name, city, address, status)
                    VALUES ('Venue', 'City', 'Address', ?)
                """.trimIndent(),
                Statement.RETURN_GENERATED_KEYS
            ).use { statement ->
                statement.setString(1, VenueStatus.PUBLISHED.dbValue)
                statement.executeUpdate()
                statement.generatedKeys.use { rs ->
                    if (rs.next()) rs.getLong(1) else error("Failed to insert venue")
                }
            }
            connection.prepareStatement(
                """
                    INSERT INTO venue_members (venue_id, user_id, role)
                    VALUES (?, ?, ?)
                """.trimIndent()
            ).use { statement ->
                statement.setLong(1, venueId)
                statement.setLong(2, userId)
                statement.setString(3, role)
                statement.executeUpdate()
            }
            return venueId
        }
    }

    private fun seedTable(jdbcUrl: String, venueId: Long, tableNumber: Int): Long {
        DriverManager.getConnection(jdbcUrl, "sa", "").use { connection ->
            return connection.prepareStatement(
                """
                    INSERT INTO venue_tables (venue_id, table_number, is_active)
                    VALUES (?, ?, true)
                """.trimIndent(),
                Statement.RETURN_GENERATED_KEYS
            ).use { statement ->
                statement.setLong(1, venueId)
                statement.setInt(2, tableNumber)
                statement.executeUpdate()
                statement.generatedKeys.use { rs ->
                    if (rs.next()) rs.getLong(1) else error("Failed to insert table")
                }
            }
        }
    }

    private fun seedTableToken(jdbcUrl: String, tableId: Long, token: String) {
        DriverManager.getConnection(jdbcUrl, "sa", "").use { connection ->
            connection.prepareStatement(
                """
                    INSERT INTO table_tokens (token, table_id, is_active, issued_at)
                    VALUES (?, ?, true, ?)
                """.trimIndent()
            ).use { statement ->
                statement.setString(1, token)
                statement.setLong(2, tableId)
                statement.setTimestamp(3, java.sql.Timestamp.from(Instant.now()))
                statement.executeUpdate()
            }
        }
    }

    private fun seedSubscription(jdbcUrl: String, venueId: Long, status: String) {
        DriverManager.getConnection(jdbcUrl, "sa", "").use { connection ->
            connection.prepareStatement(
                """
                    MERGE INTO venue_subscriptions (venue_id, status, updated_at)
                    KEY (venue_id)
                    VALUES (?, ?, CURRENT_TIMESTAMP)
                """.trimIndent()
            ).use { statement ->
                statement.setLong(1, venueId)
                statement.setString(2, status.uppercase())
                statement.executeUpdate()
            }
        }
    }

    @Serializable
    private data class VenueTableCreatedDto(
        val tableId: Long,
        val tableNumber: Int,
        val tableLabel: String,
        val activeTokenIssuedAt: String
    )

    @Serializable
    private data class VenueTableBatchCreateResponse(
        val count: Int,
        val tables: List<VenueTableCreatedDto>
    )

    private companion object {
        private const val TELEGRAM_USER_ID = 1001L
    }
}
