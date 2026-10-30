package com.hookah.platform.backend.miniapp.venue.media

import com.hookah.platform.backend.ModuleOverrides
import com.hookah.platform.backend.miniapp.session.SessionTokenConfig
import com.hookah.platform.backend.miniapp.session.SessionTokenService
import com.hookah.platform.backend.miniapp.venue.staff.syntheticPhoto
import com.hookah.platform.backend.moduleWithOverrides
import com.hookah.platform.backend.telegram.TelegramDownloadedFile
import com.hookah.platform.backend.test.PostgresTestEnv
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class VenueInfoMediaRoutesPostgresTest {
    @Test fun `own venue API uploads and private draft published guest share guarded opaque bytes`() =
        testApplication {
            val database = PostgresTestEnv.createDatabase()
            val config = PostgresTestEnv.buildConfig(database)
            val storage = FakeInfoStorage()
            environment { this.config = config }
            application {
                moduleWithOverrides(
                    ModuleOverrides(
                        venueInfoPdfValidator = ::fixturePdfAdmission,
                        venueInfoMediaStorage = storage,
                        telegramFileDownloader = {
                            TelegramDownloadedFile("legacy".toByteArray(), ContentType.Image.PNG)
                        },
                    ),
                )
            }
            client.get("/health")
            MediaFixture(PostgresTestEnv.createDataSource(database, false), storage).use { f ->
                f.init()
                f.sql("UPDATE venues SET status='DRAFT' WHERE id=${f.venue}")
                val tokens = SessionTokenService(SessionTokenConfig.from(config, "test"))
                val owner = tokens.issueToken(101).token
                val manager = tokens.issueToken(102).token
                val staff = tokens.issueToken(103).token
                val foreign = tokens.issueToken(104).token
                val root = "/api/venue/${f.venue}"

                suspend fun state(token: String = owner): JsonObject {
                    val response = client.get("$root/info-media") { bearerAuth(token) }
                    assertEquals(HttpStatusCode.OK, response.status)
                    val body = response.bodyAsText()
                    assertFalse(body.contains("object_key"))
                    assertFalse(body.contains("venue-info/"))
                    return Json.parseToJsonElement(body).jsonObject
                }

                suspend fun upload(
                    bytes: ByteArray,
                    mime: ContentType,
                    token: String = manager,
                ): HttpResponse {
                    val section = state().getValue("sections").jsonArray.first().jsonObject
                    return client.post("$root/info-sections/${f.section}/media") {
                        bearerAuth(token)
                        contentType(mime)
                        header("X-Media-Revision", section.getValue("revision").jsonPrimitive.content)
                        header("Idempotency-Key", UUID.randomUUID().toString())
                        setBody(bytes)
                    }
                }
                assertEquals(HttpStatusCode.Unauthorized, client.get("$root/info-media").status)
                for (token in listOf(staff, foreign)) {
                    assertEquals(HttpStatusCode.Forbidden, client.get("$root/info-media") { bearerAuth(token) }.status)
                    assertEquals(
                        HttpStatusCode.Forbidden,
                        upload(syntheticPhoto(), ContentType.Image.PNG, token).status,
                    )
                }
                assertEquals(HttpStatusCode.OK, upload(syntheticPhoto(), ContentType.Image.PNG).status)
                assertEquals(HttpStatusCode.OK, upload(syntheticPdf(), ContentType.Application.Pdf, owner).status)
                val row = f.legacy.listBySectionId(f.section).first()
                assertNotNull(row.publicId)
                val privatePath = "$root/guest-preview/info-sections/${f.section}/media/${row.publicId}"
                assertEquals(HttpStatusCode.Unauthorized, client.get(privatePath).status)
                assertEquals(HttpStatusCode.Forbidden, client.get(privatePath) { bearerAuth(staff) }.status)
                assertEquals(HttpStatusCode.Forbidden, client.get(privatePath) { bearerAuth(foreign) }.status)
                val private = client.get(privatePath) { bearerAuth(manager) }
                assertEquals(HttpStatusCode.OK, private.status)
                assertEquals("nosniff", private.headers["X-Content-Type-Options"])
                val guest = "/api/guest/venue/${f.venue}/info-sections/${f.section}/media/${row.publicId}"
                assertEquals(HttpStatusCode.NotFound, client.get(guest).status)
                val draft = client.get("$root/guest-preview") { bearerAuth(owner) }
                assertEquals(HttpStatusCode.OK, draft.status)
                assertTrue(draft.bodyAsText().contains("PRIVATE_DRAFT"))
                f.sql("UPDATE venues SET status='PUBLISHED' WHERE id=${f.venue}")
                f.sql("INSERT INTO venue_subscriptions(venue_id,status) VALUES (${f.venue},'ACTIVE')")
                assertEquals(HttpStatusCode.OK, client.get(guest).status)
                val published = client.get("$root/guest-preview") { bearerAuth(owner) }
                assertTrue(published.bodyAsText().contains("PUBLISHED_PUBLIC"))
                // A visibility change during R2 GET must discard already read bytes.
                storage.beforeGet = { f.sql("UPDATE venue_info_sections SET is_visible=FALSE WHERE id=${f.section}") }
                assertEquals(HttpStatusCode.NotFound, client.get(guest).status)
                assertEquals(HttpStatusCode.NotFound, client.get(privatePath) { bearerAuth(owner) }.status)
            }
        }

    @Test fun `invalid bytes limits and scoped mutation do not leak or touch foreign files`() =
        testApplication {
            val database = PostgresTestEnv.createDatabase()
            val config = PostgresTestEnv.buildConfig(database)
            val storage = FakeInfoStorage()
            environment { this.config = config }
            application {
                moduleWithOverrides(
                    ModuleOverrides(venueInfoPdfValidator = ::fixturePdfAdmission, venueInfoMediaStorage = storage),
                )
            }
            client.get("/health")
            MediaFixture(PostgresTestEnv.createDataSource(database, false), storage).use { f ->
                f.init()
                val tokens = SessionTokenService(SessionTokenConfig.from(config, "test"))
                val owner = tokens.issueToken(101).token
                val staff = tokens.issueToken(103).token
                val root = "/api/venue/${f.venue}/info-sections/${f.section}"

                suspend fun upload(bytes: ByteArray) =
                    client.post("$root/media") {
                        bearerAuth(owner)
                        contentType(ContentType.Application.Pdf)
                        header("X-Media-Revision", 0)
                        header("Idempotency-Key", UUID.randomUUID().toString())
                        setBody(bytes)
                    }
                assertEquals(HttpStatusCode.BadRequest, upload("MZfake".toByteArray()).status)
                assertEquals(HttpStatusCode.BadRequest, upload(syntheticPdf(true)).status)
                assertEquals(HttpStatusCode.PayloadTooLarge, upload(ByteArray(VENUE_INFO_MEDIA_MAX_BYTES + 1)).status)
                assertEquals(0, storage.puts)
                f.upload()
                val id = f.current().media.single().id
                val rev = f.current().revision
                val content = "$root/media/$id/content"
                assertEquals(HttpStatusCode.OK, client.get(content) { bearerAuth(owner) }.status)
                assertEquals(HttpStatusCode.Forbidden, client.get(content) { bearerAuth(staff) }.status)

                suspend fun action(
                    token: String,
                    media: String,
                    kind: String,
                ) = client.post("$root/media-actions") {
                    bearerAuth(token)
                    contentType(ContentType.Application.Json)
                    setBody("{\"revision\":$rev,\"mediaId\":\"$media\",\"action\":\"$kind\"}")
                }
                assertEquals(HttpStatusCode.Forbidden, action(staff, id, "delete").status)
                assertEquals(HttpStatusCode.NotFound, action(owner, UUID.randomUUID().toString(), "delete").status)
                assertEquals(HttpStatusCode.OK, action(owner, id, "hide").status)
                assertEquals(
                    HttpStatusCode.NotFound,
                    client.get(
                        "/api/venue/${f.venue}/guest-preview/info-sections/${f.section}/media/$id",
                    ) {
                        bearerAuth(owner)
                    }.status,
                )
                assertEquals(HttpStatusCode.OK, client.get(content) { bearerAuth(owner) }.status)
            }
        }
}
