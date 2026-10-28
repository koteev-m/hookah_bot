package com.hookah.platform.backend.miniapp.venue.staff

import com.hookah.platform.backend.ModuleOverrides
import com.hookah.platform.backend.miniapp.security.MiniAppAbuseProtection
import com.hookah.platform.backend.miniapp.session.SessionTokenConfig
import com.hookah.platform.backend.miniapp.session.SessionTokenService
import com.hookah.platform.backend.moduleWithOverrides
import com.hookah.platform.backend.test.PostgresTestEnv
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID
import kotlin.test.assertEquals

class StaffPhotoRoutesPostgresTest {
    @Test fun `authenticated photo API publication visibility privacy and safe failures`() =
        testApplication {
            val database = PostgresTestEnv.createDatabase()
            val config =
                PostgresTestEnv.buildConfig(database).apply {
                    put("venue.staffInviteSecretPepper", "synthetic-test-pepper")
                }
            val storage = FakeStaffPhotoStorage()
            val abuse = MiniAppAbuseProtection()
            environment { this.config = config }
            application {
                moduleWithOverrides(
                    ModuleOverrides(staffPhotoStorage = storage, miniAppAbuseProtection = abuse),
                )
            }
            client.get("/health")
            PostgresTestEnv.createDataSource(database, false).use { ds ->
                val f = PhotoFixture(ds)
                val tokens = SessionTokenService(SessionTokenConfig.from(config, "test"))

                fun token(user: Long) = tokens.issueToken(user).token
                val path = "/api/venue/${f.venue}/staff/profiles/${f.linked}/photos"

                suspend fun state(user: Long = 101): JsonObject {
                    val response = client.get(path) { bearerAuth(token(user)) }
                    assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
                    return Json.parseToJsonElement(response.bodyAsText()).jsonObject
                }

                suspend fun action(
                    action: String,
                    user: Long = 101,
                    photoId: String? = null,
                ): HttpResponse {
                    val current = state(user)
                    val id =
                        photoId ?: current["candidate"]?.takeIf { it != JsonNull }
                            ?.jsonObject?.get("id")?.jsonPrimitive?.content
                            ?: current["current"]?.takeIf { it != JsonNull }
                                ?.jsonObject?.get("id")?.jsonPrimitive?.content
                    return client.post("$path/$action") {
                        bearerAuth(token(user))
                        contentType(ContentType.Application.Json)
                        setBody(
                            buildJsonObject {
                                put("revision", current["revision"]!!)
                                put("photoId", id)
                                put("confirmed", true)
                                put("policy", STAFF_PHOTO_CONSENT_POLICY)
                            }.toString(),
                        )
                    }
                }

                suspend fun upload(
                    bytes: ByteArray,
                    mime: ContentType = ContentType.Image.PNG,
                    user: Long = 101,
                ): HttpResponse =
                    client.post(path) {
                        bearerAuth(token(user))
                        contentType(mime)
                        header("Idempotency-Key", UUID.randomUUID().toString())
                        header("X-Photo-Revision", 0)
                        setBody(bytes)
                    }
                assertEquals(HttpStatusCode.Unauthorized, client.get(path).status)
                assertEquals(HttpStatusCode.Forbidden, upload(syntheticPhoto(), user = 103).status)
                assertEquals(HttpStatusCode.Forbidden, client.get(path) { bearerAuth(token(104)) }.status)
                assertEquals(HttpStatusCode.BadRequest, upload(syntheticPhoto(), ContentType.Image.JPEG).status)
                assertEquals(HttpStatusCode.BadRequest, upload("<svg/>".toByteArray()).status)
                assertEquals(HttpStatusCode.PayloadTooLarge, upload(ByteArray(STAFF_PHOTO_MAX_INPUT_BYTES + 1)).status)
                val response = upload(syntheticPhoto())
                assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
                val photo = state()["candidate"]!!.jsonObject
                val id = photo["id"]!!.jsonPrimitive.content
                val preview = photo["previewUrl"]!!.jsonPrimitive.content
                val guest = "/api/guest/venue/${f.venue}/staff/${f.linked}/photos/$id"
                assertEquals(HttpStatusCode.NotFound, client.get(guest) { bearerAuth(token(104)) }.status)
                assertEquals(HttpStatusCode.Forbidden, action("publish").status)
                assertEquals(HttpStatusCode.Forbidden, action("consent").status)
                assertEquals(HttpStatusCode.OK, action("consent", 103).status)
                assertEquals(HttpStatusCode.OK, action("publish").status)
                // Published photo still requires the existing Today/MANUAL resolver.
                assertEquals(HttpStatusCode.NotFound, client.get(guest) { bearerAuth(token(104)) }.status)
                val today = LocalDate.now(ZoneId.of("UTC"))
                f.sql(
                    """
                    INSERT INTO staff_shifts (venue_id,staff_profile_id,shift_date,status,is_guest_visible,
                    manually_marked_active,created_by_user_id)
                    VALUES (${f.venue},${f.linked},'$today','active',TRUE,TRUE,101)
                    """.trimIndent(),
                )
                val delivered = client.get(guest) { bearerAuth(token(104)) }
                assertEquals(HttpStatusCode.OK, delivered.status, delivered.bodyAsText())
                assertEquals("no-store, private", delivered.headers[HttpHeaders.CacheControl])
                assertEquals("nosniff", delivered.headers["X-Content-Type-Options"])
                assertEquals("image/jpeg", delivered.headers[HttpHeaders.ContentType])
                f.sql("UPDATE venue_settings SET guest_team_visible=FALSE WHERE venue_id=${f.venue}")
                assertEquals(HttpStatusCode.NotFound, client.get(guest) { bearerAuth(token(104)) }.status)
                f.sql(
                    """
                    UPDATE venue_settings SET guest_team_visible=TRUE, today_staff_source='SCHEDULE' WHERE
                    venue_id=${f.venue}
                    """.trimIndent(),
                )
                assertEquals(HttpStatusCode.NotFound, client.get(guest) { bearerAuth(token(104)) }.status)
                f.sql(
                    """
                    UPDATE venue_settings SET today_staff_source='MANUAL', team_schedule_module_enabled=FALSE
                    WHERE venue_id=${f.venue}
                    """.trimIndent(),
                )
                repeat(120) { abuse.tryStaffPhotoUpload((it / 30 + 1000).toLong(), f.venue) }
                assertEquals(HttpStatusCode.OK, action("revoke", 103).status)
                assertEquals(HttpStatusCode.NotFound, client.get(preview) { bearerAuth(token(101)) }.status)
                assertEquals(HttpStatusCode.NotFound, client.get(guest) { bearerAuth(token(104)) }.status)
                f.sql("UPDATE venues SET status='DELETED' WHERE id=${f.venue}")
                assertEquals(HttpStatusCode.NotFound, client.get(path) { bearerAuth(token(101)) }.status)
                assertEquals(HttpStatusCode.NotFound, client.get(preview) { bearerAuth(token(101)) }.status)
            }
        }
}
