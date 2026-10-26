package com.hookah.platform.backend.miniapp.venue

import com.google.zxing.BinaryBitmap
import com.google.zxing.MultiFormatReader
import com.google.zxing.client.j2se.BufferedImageLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.hookah.platform.backend.ModuleOverrides
import com.hookah.platform.backend.api.ApiErrorCodes
import com.hookah.platform.backend.miniapp.security.MiniAppAbuseProtection
import com.hookah.platform.backend.miniapp.session.SessionTokenConfig
import com.hookah.platform.backend.miniapp.session.SessionTokenService
import com.hookah.platform.backend.miniapp.venue.tables.VenueTableRepository
import com.hookah.platform.backend.module
import com.hookah.platform.backend.moduleWithOverrides
import com.hookah.platform.backend.test.assertApiErrorEnvelope
import com.lowagie.text.pdf.PdfReader
import com.lowagie.text.pdf.parser.PdfTextExtractor
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.client.request.headers
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.config.MapApplicationConfig
import io.ktor.server.testing.testApplication
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.h2.jdbcx.JdbcDataSource
import java.nio.file.Files
import java.nio.file.Path
import java.sql.DriverManager
import java.sql.Statement
import java.time.Instant
import java.util.UUID
import java.util.zip.ZipInputStream
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class VenueTableRoutesTest {
    private val json = Json { ignoreUnknownKeys = true }
    private val appEnv = "test"

    @Test
    fun `owner can batch create tables`() =
        testApplication {
            val jdbcUrl = buildJdbcUrl("tables-create")
            val config = buildConfig(jdbcUrl)

            environment { this.config = config }
            application { module() }

            client.get("/health")

            val venueId = seedVenueWithRole(jdbcUrl, TELEGRAM_USER_ID, "OWNER")
            val token = issueToken(config)

            val response =
                client.post("/api/venue/tables/batch-create?venueId=$venueId") {
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
                    "SELECT COUNT(*) FROM venue_tables WHERE venue_id = ?",
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
                    """.trimIndent(),
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
    fun `rotate invalidates old token`() =
        testApplication {
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
            val rotateResponse =
                client.post("/api/venue/tables/$tableId/rotate-token?venueId=$venueId") {
                    headers { append(HttpHeaders.Authorization, "Bearer $token") }
                }

            assertEquals(HttpStatusCode.OK, rotateResponse.status)

            val resolveResponse =
                client.get("/api/guest/table/resolve?tableToken=$oldToken") {
                    headers { append(HttpHeaders.Authorization, "Bearer $token") }
                }

            assertEquals(HttpStatusCode.NotFound, resolveResponse.status)
            assertApiErrorEnvelope(resolveResponse, ApiErrorCodes.NOT_FOUND)

            DriverManager.getConnection(jdbcUrl, "sa", "").use { connection ->
                connection.prepareStatement(
                    "SELECT is_active FROM table_tokens WHERE token = ?",
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
    fun `qr package export returns zip with entries`() =
        testApplication {
            val fixturePath =
                generateSequence(Path.of("").toAbsolutePath()) { it.parent }
                    .map { it.resolve("miniapp/e2e/fixtures/table-qr.json") }
                    .firstOrNull(Files::isRegularFile)
                    ?: error("miniapp/e2e/fixtures/table-qr.json not found from test working directory")
            val fixture = json.decodeFromString(TableQrFixture.serializer(), Files.readString(fixturePath))
            val jdbcUrl = buildJdbcUrl("tables-export")
            val config =
                buildConfig(
                    jdbcUrl,
                    webAppUrl = "https://example.com/miniapp/",
                    botUsername = fixture.botUsername,
                )

            environment { this.config = config }
            application { module() }

            client.get("/health")

            val venueId = seedVenueWithRole(jdbcUrl, TELEGRAM_USER_ID, "OWNER")
            val firstTable = seedTable(jdbcUrl, venueId, 1)
            val secondTable = seedTable(jdbcUrl, venueId, 2)
            seedTableToken(jdbcUrl, firstTable, fixture.tableToken)
            seedTableToken(jdbcUrl, secondTable, "token-two")
            seedSubscription(jdbcUrl, venueId, "active")

            val token = issueToken(config)
            val response =
                client.get("/api/venue/tables/qr-package?venueId=$venueId&format=zip") {
                    headers { append(HttpHeaders.Authorization, "Bearer $token") }
                }

            assertEquals(HttpStatusCode.OK, response.status)
            assertNotNull(response.headers[HttpHeaders.ContentDisposition])
            assertEquals("application/zip", response.headers[HttpHeaders.ContentType])
            val bytes = response.body<ByteArray>()
            val entries = mutableListOf<String>()
            var manifestContent: String? = null
            var decodedTableQr: String? = null
            ZipInputStream(bytes.inputStream()).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    entries.add(entry.name)
                    if (entry.name == "manifest.json") {
                        manifestContent = zip.readBytes().toString(Charsets.UTF_8)
                    }
                    if (entry.name == "table_1.png") {
                        val image = ImageIO.read(zip.readBytes().inputStream())
                        val bitmap = BinaryBitmap(HybridBinarizer(BufferedImageLuminanceSource(image)))
                        decodedTableQr = MultiFormatReader().decode(bitmap).text
                    }
                    zip.closeEntry()
                }
            }
            assertEquals(3, entries.size)
            assertTrue(entries.contains("table_1.png"))
            assertTrue(entries.contains("table_2.png"))
            assertTrue(entries.contains("manifest.json"))
            assertNotNull(manifestContent)
            assertEquals(fixture.qrText, decodedTableQr)
            assertTrue(manifestContent!!.contains(fixture.qrText))
            assertTrue(manifestContent!!.contains("https://t.me/hookah_test_bot?start=token-two"))
        }

    @Test
    fun `staff can read tables but cannot mutate tables or qr tokens`() =
        testApplication {
            val jdbcUrl = buildJdbcUrl("tables-staff-readonly")
            val config =
                buildConfig(
                    jdbcUrl,
                    webAppUrl = "https://example.com/miniapp/",
                    botUsername = "hookah_test_bot",
                )

            environment { this.config = config }
            application { module() }

            client.get("/health")

            val ownerId = 4101L
            val staffId = 4102L
            val venueId = seedVenueWithRole(jdbcUrl, ownerId, "OWNER")
            seedVenueWithRole(jdbcUrl, staffId, "STAFF", venueId)
            val tableId = seedTable(jdbcUrl, venueId, 7)
            seedTableToken(jdbcUrl, tableId, "staff-readonly-token")
            val staffToken = issueToken(config, staffId)

            val listResponse =
                client.get("/api/venue/tables?venueId=$venueId") {
                    headers { append(HttpHeaders.Authorization, "Bearer $staffToken") }
                }

            assertEquals(HttpStatusCode.OK, listResponse.status)
            val listPayload = json.decodeFromString(VenueTablesResponse.serializer(), listResponse.bodyAsText())
            assertEquals(listOf(7), listPayload.tables.map { it.tableNumber })

            val createResponse =
                client.post("/api/venue/tables/batch-create?venueId=$venueId") {
                    headers {
                        append(HttpHeaders.Authorization, "Bearer $staffToken")
                        append(HttpHeaders.ContentType, ContentType.Application.Json.toString())
                    }
                    setBody("""{"count":1,"startNumber":8}""")
                }

            assertEquals(HttpStatusCode.Forbidden, createResponse.status)
            assertApiErrorEnvelope(createResponse, ApiErrorCodes.FORBIDDEN)

            val rotateSingleResponse =
                client.post("/api/venue/tables/$tableId/rotate-token?venueId=$venueId") {
                    headers { append(HttpHeaders.Authorization, "Bearer $staffToken") }
                }

            assertEquals(HttpStatusCode.Forbidden, rotateSingleResponse.status)
            assertApiErrorEnvelope(rotateSingleResponse, ApiErrorCodes.FORBIDDEN)

            val rotateSelectedResponse =
                client.post("/api/venue/tables/rotate-tokens?venueId=$venueId") {
                    headers {
                        append(HttpHeaders.Authorization, "Bearer $staffToken")
                        append(HttpHeaders.ContentType, ContentType.Application.Json.toString())
                    }
                    setBody("""{"tableIds":[$tableId]}""")
                }

            assertEquals(HttpStatusCode.Forbidden, rotateSelectedResponse.status)
            assertApiErrorEnvelope(rotateSelectedResponse, ApiErrorCodes.FORBIDDEN)

            val rotateAllResponse =
                client.post("/api/venue/tables/rotate-tokens?venueId=$venueId") {
                    headers {
                        append(HttpHeaders.Authorization, "Bearer $staffToken")
                        append(HttpHeaders.ContentType, ContentType.Application.Json.toString())
                    }
                    setBody("""{}""")
                }

            assertEquals(HttpStatusCode.Forbidden, rotateAllResponse.status)
            assertApiErrorEnvelope(rotateAllResponse, ApiErrorCodes.FORBIDDEN)

            val exportResponse =
                client.get("/api/venue/tables/qr-package?venueId=$venueId&format=zip") {
                    headers { append(HttpHeaders.Authorization, "Bearer $staffToken") }
                }

            assertEquals(HttpStatusCode.Forbidden, exportResponse.status)
            assertApiErrorEnvelope(exportResponse, ApiErrorCodes.FORBIDDEN)
        }

    @Test
    fun `explicit numbers preserve order tokens and one audit with late conflict rollback`() =
        testApplication {
            val jdbcUrl = buildJdbcUrl("numbers")
            val config = buildConfig(jdbcUrl)
            environment { this.config = config }
            application { module() }
            client.get("/health")
            val venueId = seedVenueWithRole(jdbcUrl, TELEGRAM_USER_ID, "OWNER")
            val token = issueToken(config)

            suspend fun create(body: String) =
                client.post("/api/venue/tables/batch-create?venueId=$venueId") {
                    headers {
                        append(HttpHeaders.Authorization, "Bearer $token")
                        append(HttpHeaders.ContentType, "application/json")
                    }
                    setBody(body)
                }
            val response = create("""{"count":6,"tableNumbers":[1,3,5,8,12,20]}""")
            assertEquals(HttpStatusCode.OK, response.status)
            val tables = json.decodeFromString(VenueTableBatchCreateResponse.serializer(), response.bodyAsText()).tables
            assertEquals(listOf(1, 3, 5, 8, 12, 20), tables.map { it.tableNumber })
            val before = tableCounts(jdbcUrl)
            assertEquals(listOf(6, 6, 1), before)
            assertEquals(HttpStatusCode.BadRequest, create("""{"count":3,"tableNumbers":[21,22,20]}""").status)
            assertEquals(before, tableCounts(jdbcUrl))
            val ds =
                JdbcDataSource().apply {
                    setURL(jdbcUrl)
                    user = "sa"
                    password = ""
                }
            val exported = VenueTableRepository(ds).listTablesWithTokens(venueId)
            assertEquals(tables.map { it.tableId }, exported.map { it.tableId })
            assertEquals(6, exported.map { it.token }.distinct().size)
            val legacy = create("""{"count":2}""")
            assertEquals(HttpStatusCode.OK, legacy.status)
            assertEquals(
                listOf(
                    21,
                    22,
                ),
                json.decodeFromString(VenueTableBatchCreateResponse.serializer(), legacy.bodyAsText()).tables.map {
                    it.tableNumber
                },
            )
        }

    @Test
    fun `invalid explicit batches and foreign or staff requests create nothing`() =
        testApplication {
            val jdbcUrl = buildJdbcUrl("invalid-numbers")
            val config = buildConfig(jdbcUrl)
            environment { this.config = config }
            application { module() }
            client.get("/health")
            val venueId = seedVenueWithRole(jdbcUrl, TELEGRAM_USER_ID, "OWNER")
            val foreignVenue = seedVenueWithRole(jdbcUrl, 8001, "OWNER")
            seedVenueWithRole(jdbcUrl, 8002, "STAFF", venueId)
            val invalid =
                listOf(
                    """{"count":2,"tableNumbers":[1,1]}""", """{"count":2,"tableNumbers":[1,0]}""",
                    """{"count":1,"tableNumbers":[-1]}""", """{"count":2,"tableNumbers":[1]}""",
                    """{"count":1,"tableNumbers":[1.5]}""", """{"count":1,"tableNumbers":[2147483648]}""",
                    """{"count":501,"tableNumbers":[1]}""", """{"count":0,"tableNumbers":[]}""",
                    """{"count":1,"startNumber":1,"tableNumbers":[2]}""",
                    """{"count":2,"startNumber":2147483647}""",
                )
            for (body in invalid) {
                val response =
                    client.post("/api/venue/tables/batch-create?venueId=$venueId") {
                        headers {
                            append(HttpHeaders.Authorization, "Bearer ${issueToken(config)}")
                            append(HttpHeaders.ContentType, "application/json")
                        }
                        setBody(body)
                    }
                assertEquals(HttpStatusCode.BadRequest, response.status, body)
            }
            for ((user, venue) in listOf(TELEGRAM_USER_ID to foreignVenue, 8002L to venueId)) {
                val response =
                    client.post("/api/venue/tables/batch-create?venueId=$venue") {
                        headers {
                            append(HttpHeaders.Authorization, "Bearer ${issueToken(config, user)}")
                            append(HttpHeaders.ContentType, "application/json")
                        }
                        setBody("""{"count":1,"tableNumbers":[1]}""")
                    }
                assertEquals(HttpStatusCode.Forbidden, response.status)
            }
            assertEquals(listOf(0, 0, 0), tableCounts(jdbcUrl))
        }

    @Test
    fun `audit failure rolls back entire batch and tokens`() =
        testApplication {
            val jdbcUrl = buildJdbcUrl("audit-rollback")
            environment { config = buildConfig(jdbcUrl) }
            application { module() }
            client.get("/health")
            val venueId = seedVenueWithRole(jdbcUrl, TELEGRAM_USER_ID, "OWNER")
            val ds =
                JdbcDataSource().apply {
                    setURL(jdbcUrl)
                    user = "sa"
                    password = ""
                }
            assertFailsWith<IllegalStateException> {
                VenueTableRepository(
                    ds,
                ).batchCreateTables(venueId, listOf(7, 11)) { _, _ -> error("synthetic audit failure") }
            }
            assertEquals(listOf(0, 0, 0), tableCounts(jdbcUrl))
        }

    @Test
    fun `png and pdf carry correct table labels and legacy default zip without token rotation`() =
        testApplication {
            val jdbcUrl = buildJdbcUrl("png-pdf")
            val config = buildConfig(jdbcUrl, botUsername = "hookah_test_bot")
            environment { this.config = config }
            application { module() }
            client.get("/health")
            val venueId = seedVenueWithRole(jdbcUrl, TELEGRAM_USER_ID, "OWNER")
            val tableId = seedTable(jdbcUrl, venueId, 20)
            seedTableToken(jdbcUrl, tableId, "synthetic-table-20")
            val otherVenue = seedVenueWithRole(jdbcUrl, 8001, "OWNER")
            val otherTable = seedTable(jdbcUrl, otherVenue, 20)
            seedTableToken(jdbcUrl, otherTable, "synthetic-foreign")
            seedVenueWithRole(jdbcUrl, 8002, "STAFF", venueId)
            val before = tableCounts(jdbcUrl)
            val png =
                client.get("/api/venue/tables/qr-package?venueId=$venueId&format=png&tableId=$tableId") {
                    headers { append(HttpHeaders.Authorization, "Bearer ${issueToken(config)}") }
                }
            assertEquals(HttpStatusCode.OK, png.status)
            assertTrue(png.headers[HttpHeaders.ContentType]!!.startsWith("image/png"))
            assertTrue(png.headers[HttpHeaders.ContentDisposition]!!.contains("table-20-"))
            assertEquals("no-store", png.headers[HttpHeaders.CacheControl])
            val bitmap =
                BinaryBitmap(
                    HybridBinarizer(BufferedImageLuminanceSource(ImageIO.read(png.body<ByteArray>().inputStream()))),
                )
            assertEquals(
                "https://t.me/hookah_test_bot?start=synthetic-table-20",
                MultiFormatReader().decode(bitmap).text,
            )
            val pdf =
                client.get("/api/venue/tables/qr-package?venueId=$venueId&format=pdf") {
                    headers { append(HttpHeaders.Authorization, "Bearer ${issueToken(config)}") }
                }
            assertTrue(pdf.headers[HttpHeaders.ContentType]!!.startsWith("application/pdf"))
            val reader = PdfReader(pdf.body<ByteArray>())
            assertEquals(1, reader.numberOfPages)
            assertTrue(PdfTextExtractor(reader).getTextFromPage(1).contains("Table 20"))
            reader.close()
            val legacy =
                client.get("/api/venue/tables/qr-package?venueId=$venueId") {
                    headers { append(HttpHeaders.Authorization, "Bearer ${issueToken(config)}") }
                }
            assertTrue(legacy.headers[HttpHeaders.ContentType]!!.startsWith("application/zip"))
            for ((user, venue, id, status) in listOf(
                listOf(TELEGRAM_USER_ID, venueId, otherTable, 404L),
                listOf(TELEGRAM_USER_ID, otherVenue, otherTable, 403L),
                listOf(8002L, venueId, tableId, 403L),
            )) {
                val denied =
                    client.get("/api/venue/tables/qr-package?venueId=$venue&format=png&tableId=$id") {
                        headers { append(HttpHeaders.Authorization, "Bearer ${issueToken(config, user)}") }
                    }
                assertEquals(status.toInt(), denied.status.value)
                assertFalse(denied.bodyAsText().contains("synthetic-"))
            }
            assertEquals(before, tableCounts(jdbcUrl))
        }

    @Test
    fun `telegram delivery uses own private chat confirms send and limits repeats`() =
        testApplication {
            val jdbcUrl = buildJdbcUrl("delivery")
            val config =
                buildConfig(jdbcUrl, botUsername = "hookah_test_bot").apply {
                    put("telegram.enabled", "true")
                    put("telegram.token", "synthetic-test-token")
                    put("telegram.mode", "webhook")
                    put("telegram.staffChatLinkSecretPepper", "synthetic-test-pepper")
                }
            var now = 0L
            var sendCalls = 0
            var sendOk = true
            var loseTelegramResponse = false
            var chatOk = true
            val paths = mutableListOf<String>()
            environment { this.config = config }
            application {
                moduleWithOverrides(
                    ModuleOverrides(
                        tableSessionCleanupEnabled = false,
                        miniAppAbuseProtection = MiniAppAbuseProtection(nowMillis = { now }),
                        telegramCommandMenuConfigurator = {},
                        telegramHttpClientFactory = { codec ->
                            HttpClient(
                                MockEngine { request ->
                                    paths.add(request.url.encodedPath.substringAfterLast('/'))
                                    val payload =
                                        when (request.url.encodedPath.substringAfterLast('/')) {
                                            "getChat" -> {
                                                assertEquals(
                                                    TELEGRAM_USER_ID.toString(),
                                                    request.url.parameters["chat_id"],
                                                )
                                                if (chatOk) {
                                                    """{"ok":true,"result":{"id":$TELEGRAM_USER_ID,"type":"private"}}"""
                                                } else {
                                                    """{"ok":false,"error_code":400}"""
                                                }
                                            }
                                            "sendDocument" -> {
                                                sendCalls++
                                                if (loseTelegramResponse) {
                                                    throw java.io.IOException("synthetic lost response")
                                                }
                                                if (sendOk) {
                                                    """{"ok":true,"result":{"message_id":1,
                                                    "chat":{"id":$TELEGRAM_USER_ID,"type":"private"}}}"""
                                                } else {
                                                    """{"ok":false,"error_code":403}"""
                                                }
                                            }
                                            else -> error("Unexpected mock API operation")
                                        }
                                    respond(
                                        payload,
                                        HttpStatusCode.OK,
                                        headersOf(HttpHeaders.ContentType, "application/json"),
                                    )
                                },
                            ) { install(ContentNegotiation) { json(codec) } }
                        },
                    ),
                )
            }
            client.get("/health")
            val venueId = seedVenueWithRole(jdbcUrl, TELEGRAM_USER_ID, "OWNER")
            val table = seedTable(jdbcUrl, venueId, 4)
            seedTableToken(jdbcUrl, table, "synthetic-delivery-qr")
            seedVenueWithRole(jdbcUrl, 8002, "STAFF", venueId)
            val foreign = seedVenueWithRole(jdbcUrl, 8003, "OWNER")

            suspend fun send(
                user: Long = TELEGRAM_USER_ID,
                venue: Long = venueId,
                body: String = """{"format":"pdf"}""",
            ) = client.post("/api/venue/tables/qr-delivery?venueId=$venue") {
                headers {
                    append(HttpHeaders.Authorization, "Bearer ${issueToken(config, user)}")
                    append(HttpHeaders.ContentType, "application/json")
                }
                setBody(body)
            }
            assertEquals(HttpStatusCode.Forbidden, send(user = 8002).status)
            assertEquals(HttpStatusCode.Forbidden, send(venue = foreign).status)
            assertTrue(paths.isEmpty())
            val success = send(body = """{"format":"png","tableId":$table,"chatId":8003}""")
            assertEquals(HttpStatusCode.OK, success.status)
            assertTrue(success.bodyAsText().contains("\"sent\":true"))
            assertEquals(1, sendCalls)
            assertEquals(HttpStatusCode.TooManyRequests, send().status)
            assertEquals(1, sendCalls)
            now += 11000
            sendOk = false
            val definiteRejection = send()
            assertEquals(HttpStatusCode.BadRequest, definiteRejection.status)
            assertFalse(definiteRejection.bodyAsText().contains("deliveryOutcome"))
            assertEquals(2, sendCalls)
            now += 11000
            chatOk = false
            assertEquals(HttpStatusCode.BadRequest, send().status)
            assertEquals(2, sendCalls)
            DriverManager.getConnection(jdbcUrl, "sa", "").use { c ->
                c.createStatement().use { st ->
                    st.executeQuery("SELECT COUNT(*) FROM audit_log WHERE action = 'TABLE_QR_TELEGRAM_SENT'").use {
                            rs ->
                        rs.next()
                        assertEquals(1, rs.getInt(1))
                    }
                }
            }

            // A confirmed Telegram send followed by audit failure must not report success or suggest a blind retry.
            val auditFailureVenue = seedVenueWithRole(jdbcUrl, TELEGRAM_USER_ID, "OWNER")
            val auditFailureTable = seedTable(jdbcUrl, auditFailureVenue, 9)
            seedTableToken(jdbcUrl, auditFailureTable, "synthetic-audit-failure-qr")
            DriverManager.getConnection(jdbcUrl, "sa", "").use { c ->
                c.createStatement().use { st ->
                    st.execute(
                        "ALTER TABLE audit_log ADD CONSTRAINT synthetic_delivery_audit_failure " +
                            "CHECK (action <> 'TABLE_QR_TELEGRAM_SENT' OR entity_id <> $auditFailureVenue)",
                    )
                }
            }
            now += 11000
            sendOk = true
            chatOk = true
            val auditFailure = send(venue = auditFailureVenue)
            assertEquals(HttpStatusCode.BadRequest, auditFailure.status)
            assertApiErrorEnvelope(auditFailure, ApiErrorCodes.INVALID_INPUT)
            val warning = "Файл мог быть отправлен. Проверьте личный чат с ботом перед повторной отправкой."
            val auditFailureBody = auditFailure.bodyAsText()
            assertTrue(auditFailureBody.contains(warning))
            assertTrue(auditFailureBody.contains("\"deliveryOutcome\":\"unknown\""))
            assertFalse(auditFailureBody.contains("synthetic-audit-failure-qr"))
            assertEquals(3, sendCalls)
            DriverManager.getConnection(jdbcUrl, "sa", "").use { c ->
                c.createStatement().use { st ->
                    st.executeQuery("SELECT COUNT(*) FROM audit_log WHERE action = 'TABLE_QR_TELEGRAM_SENT'").use {
                            rs ->
                        rs.next()
                        assertEquals(1, rs.getInt(1))
                    }
                }
            }

            // Telegram accepted the mocked upload, but its response was lost. No automatic retry is allowed.
            now += 11000
            loseTelegramResponse = true
            val lostResponse = send()
            assertEquals(HttpStatusCode.BadRequest, lostResponse.status)
            assertTrue(lostResponse.bodyAsText().contains(warning))
            assertTrue(lostResponse.bodyAsText().contains("\"deliveryOutcome\":\"unknown\""))
            assertEquals(4, sendCalls)

            now += 11000
            val invalidFormat = send(body = """{"format":"zip"}""")
            assertEquals(HttpStatusCode.BadRequest, invalidFormat.status)
            assertFalse(invalidFormat.bodyAsText().contains("deliveryOutcome"))
            val forbidden = send(user = 8002)
            assertEquals(HttpStatusCode.Forbidden, forbidden.status)
            assertFalse(forbidden.bodyAsText().contains("deliveryOutcome"))
            assertEquals(4, sendCalls)
        }

    @Test
    fun `bulk export rejects more than five hundred tables while single PNG still works`() =
        testApplication {
            val jdbcUrl = buildJdbcUrl("export-limit")
            val config = buildConfig(jdbcUrl, botUsername = "hookah_test_bot")
            environment { this.config = config }
            application { module() }
            client.get("/health")
            val venueId = seedVenueWithRole(jdbcUrl, TELEGRAM_USER_ID, "OWNER")
            val ds =
                JdbcDataSource().apply {
                    setURL(jdbcUrl)
                    user = "sa"
                    password = ""
                }
            val repository = VenueTableRepository(ds)
            repository.batchCreateTables(venueId, 1, 500)
            val last = repository.createTable(venueId, 501, null)
            val before = tableCounts(jdbcUrl)
            for (format in listOf("pdf", "zip")) {
                val response =
                    client.get("/api/venue/tables/qr-package?venueId=$venueId&format=$format") {
                        headers { append(HttpHeaders.Authorization, "Bearer ${issueToken(config)}") }
                    }
                assertEquals(HttpStatusCode.BadRequest, response.status)
            }
            val single =
                client.get("/api/venue/tables/qr-package?venueId=$venueId&format=png&tableId=${last.tableId}") {
                    headers { append(HttpHeaders.Authorization, "Bearer ${issueToken(config)}") }
                }
            assertEquals(HttpStatusCode.OK, single.status)
            assertEquals(before, tableCounts(jdbcUrl))
        }

    private fun tableCounts(jdbcUrl: String): List<Int> =
        DriverManager.getConnection(jdbcUrl, "sa", "").use { connection ->
            listOf(
                "SELECT COUNT(*) FROM venue_tables",
                "SELECT COUNT(*) FROM table_tokens",
                "SELECT COUNT(*) FROM audit_log WHERE action = 'TABLES_BATCH_CREATE'",
            ).map {
                    query ->
                connection.createStatement().use {
                        statement ->
                    statement.executeQuery(query).use { rs ->
                        rs.next()
                        rs.getInt(1)
                    }
                }
            }
        }

    private fun buildJdbcUrl(prefix: String): String {
        val dbName = "$prefix-${UUID.randomUUID()}"
        return "jdbc:h2:mem:$dbName;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1"
    }

    private fun buildConfig(
        jdbcUrl: String,
        webAppUrl: String? = null,
        botUsername: String? = null,
    ): MapApplicationConfig {
        val config =
            mutableMapOf(
                "app.env" to appEnv,
                "api.session.jwtSecret" to "test-secret",
                "db.jdbcUrl" to jdbcUrl,
                "db.user" to "sa",
                "db.password" to "",
            )
        if (webAppUrl != null) {
            config["telegram.webAppPublicUrl"] = webAppUrl
        }
        if (botUsername != null) {
            config["telegram.botUsername"] = botUsername
        }
        return MapApplicationConfig(*config.map { it.key to it.value }.toTypedArray())
    }

    private fun issueToken(
        config: MapApplicationConfig,
        userId: Long = TELEGRAM_USER_ID,
    ): String {
        val service = SessionTokenService(SessionTokenConfig.from(config, appEnv))
        return service.issueToken(userId).token
    }

    private fun seedVenueWithRole(
        jdbcUrl: String,
        userId: Long,
        role: String,
        venueId: Long? = null,
    ): Long {
        DriverManager.getConnection(jdbcUrl, "sa", "").use { connection ->
            connection.prepareStatement(
                """
                MERGE INTO users (telegram_user_id, username, first_name, last_name)
                KEY (telegram_user_id)
                VALUES (?, 'user', 'Test', 'User')
                """.trimIndent(),
            ).use { statement ->
                statement.setLong(1, userId)
                statement.executeUpdate()
            }
            val resolvedVenueId =
                venueId ?: connection.prepareStatement(
                    """
                    INSERT INTO venues (name, city, address, status)
                    VALUES ('Venue', 'City', 'Address', ?)
                    """.trimIndent(),
                    Statement.RETURN_GENERATED_KEYS,
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
                """.trimIndent(),
            ).use { statement ->
                statement.setLong(1, resolvedVenueId)
                statement.setLong(2, userId)
                statement.setString(3, role)
                statement.executeUpdate()
            }
            return resolvedVenueId
        }
    }

    private fun seedTable(
        jdbcUrl: String,
        venueId: Long,
        tableNumber: Int,
    ): Long {
        DriverManager.getConnection(jdbcUrl, "sa", "").use { connection ->
            return connection.prepareStatement(
                """
                INSERT INTO venue_tables (venue_id, table_number, is_active)
                VALUES (?, ?, true)
                """.trimIndent(),
                Statement.RETURN_GENERATED_KEYS,
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

    private fun seedTableToken(
        jdbcUrl: String,
        tableId: Long,
        token: String,
    ) {
        DriverManager.getConnection(jdbcUrl, "sa", "").use { connection ->
            connection.prepareStatement(
                """
                INSERT INTO table_tokens (token, table_id, is_active, issued_at)
                VALUES (?, ?, true, ?)
                """.trimIndent(),
            ).use { statement ->
                statement.setString(1, token)
                statement.setLong(2, tableId)
                statement.setTimestamp(3, java.sql.Timestamp.from(Instant.now()))
                statement.executeUpdate()
            }
        }
    }

    private fun seedSubscription(
        jdbcUrl: String,
        venueId: Long,
        status: String,
    ) {
        DriverManager.getConnection(jdbcUrl, "sa", "").use { connection ->
            connection.prepareStatement(
                """
                MERGE INTO venue_subscriptions (venue_id, status, updated_at)
                KEY (venue_id)
                VALUES (?, ?, CURRENT_TIMESTAMP)
                """.trimIndent(),
            ).use { statement ->
                statement.setLong(1, venueId)
                statement.setString(2, status.uppercase())
                statement.executeUpdate()
            }
        }
    }

    @Serializable
    private data class TableQrFixture(
        val botUsername: String,
        val tableToken: String,
        val qrText: String,
    )

    @Serializable
    private data class VenueTableDto(
        val tableId: Long,
        val tableNumber: Int,
        val tableLabel: String,
        val isActive: Boolean,
        val activeTokenIssuedAt: String? = null,
    )

    @Serializable
    private data class VenueTablesResponse(
        val tables: List<VenueTableDto>,
    )

    @Serializable
    private data class VenueTableCreatedDto(
        val tableId: Long,
        val tableNumber: Int,
        val tableLabel: String,
        val activeTokenIssuedAt: String,
    )

    @Serializable
    private data class VenueTableBatchCreateResponse(
        val count: Int,
        val tables: List<VenueTableCreatedDto>,
    )

    private companion object {
        private const val TELEGRAM_USER_ID = 1001L
    }
}
