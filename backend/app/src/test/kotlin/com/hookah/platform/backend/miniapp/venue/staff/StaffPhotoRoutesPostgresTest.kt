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
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class StaffPhotoRoutesPostgresTest {
    @Test fun `owner and manager directly replace protected photos and legacy clients cannot mutate consent`() =
        testApplication {
            val database = PostgresTestEnv.createDatabase()
            val config = PostgresTestEnv.buildConfig(database)
            environment { this.config = config }
            application { moduleWithOverrides(ModuleOverrides(staffPhotoStorage = FakeStaffPhotoStorage())) }
            client.get("/health")
            PostgresTestEnv.createDataSource(database, false).use { ds ->
                val f = PhotoFixture(ds)
                val tokens = SessionTokenService(SessionTokenConfig.from(config, "test"))
                val owner = tokens.issueToken(101).token
                val manager = tokens.issueToken(105).token
                val path = "/api/venue/${f.venue}/staff/profiles/${f.protected}/photos"

                suspend fun state(token: String): JsonObject {
                    val response = client.get(path) { bearerAuth(token) }
                    assertEquals(HttpStatusCode.OK, response.status)
                    return Json.parseToJsonElement(response.bodyAsText()).jsonObject
                }

                suspend fun upload(
                    revision: Int,
                    token: String = owner,
                    requestId: String = UUID.randomUUID().toString(),
                ): JsonObject {
                    val response =
                        client.post(path) {
                            bearerAuth(token)
                            contentType(ContentType.Image.PNG)
                            header("Idempotency-Key", requestId)
                            header("X-Photo-Revision", revision)
                            setBody(syntheticPhoto())
                        }
                    assertEquals(HttpStatusCode.OK, response.status)
                    return Json.parseToJsonElement(response.bodyAsText()).jsonObject
                }

                suspend fun action(
                    token: String,
                    action: String,
                    photo: String,
                    revision: Int,
                ): HttpResponse =
                    client.post("$path/$action") {
                        bearerAuth(token)
                        contentType(ContentType.Application.Json)
                        setBody(
                            buildJsonObject {
                                put("revision", revision)
                                put("photoId", photo)
                                put("confirmed", true)
                                put("policy", STAFF_PHOTO_CONSENT_POLICY)
                            }.toString(),
                        )
                    }
                val requestId = UUID.randomUUID().toString()
                val uploaded = upload(0, requestId = requestId)
                val photoId = uploaded["current"]!!.jsonObject["id"]!!.jsonPrimitive.content
                assertEquals(uploaded, upload(0, requestId = requestId))
                assertEquals("2", uploaded["revision"]!!.jsonPrimitive.content)
                assertEquals(JsonNull, uploaded["candidate"] ?: JsonNull)
                val ownResponse = client.get("/api/venue/${f.venue}/staff/photos/me") { bearerAuth(manager) }
                assertEquals(HttpStatusCode.OK, ownResponse.status)
                val own = Json.parseToJsonElement(ownResponse.bodyAsText()).jsonArray.single().jsonObject
                assertEquals(f.protected.toString(), own["profileId"]!!.jsonPrimitive.content)
                val ownPhoto = own["photo"]!!.jsonObject
                assertEquals(photoId, ownPhoto["current"]!!.jsonObject["id"]!!.jsonPrimitive.content)
                assertEquals("true", ownPhoto["isSelf"]!!.jsonPrimitive.content)
                assertEquals("false", ownPhoto["canConsent"]!!.jsonPrimitive.content)
                assertEquals("true", ownPhoto["canManage"]!!.jsonPrimitive.content)
                val otherManager = tokens.issueToken(102).token
                assertEquals("true", state(otherManager)["canManage"]!!.jsonPrimitive.content)
                for (action in listOf("consent", "publish", "revoke")) {
                    for (actor in listOf(owner, manager, otherManager)) {
                        val response = action(actor, action, photoId, 2)
                        assertEquals(HttpStatusCode.Gone, response.status)
                        assertEquals(
                            "STAFF_PHOTO_ACTION_RETIRED",
                            Json.parseToJsonElement(
                                response.bodyAsText(),
                            ).jsonObject["error"]!!.jsonObject["code"]!!.jsonPrimitive.content,
                        )
                    }
                }
                for (actor in listOf(103L, 104L)) {
                    for (action in listOf("consent", "publish", "revoke", "hide", "delete")) {
                        assertEquals(
                            HttpStatusCode.Forbidden,
                            action(tokens.issueToken(actor).token, action, photoId, 2).status,
                        )
                    }
                }
                assertEquals("2", state(owner)["revision"]!!.jsonPrimitive.content)
                val replacement = upload(2, otherManager)["current"]!!.jsonObject
                val replacementId = replacement["id"]!!.jsonPrimitive.content
                assertNotEquals(photoId, replacementId)
                assertEquals("NONE", replacement["consent"]!!.jsonPrimitive.content)
                assertEquals(replacementId, state(owner)["current"]!!.jsonObject["id"]!!.jsonPrimitive.content)
                assertEquals(HttpStatusCode.NotFound, client.get("$path/$photoId/content") { bearerAuth(owner) }.status)
                assertEquals(
                    0L,
                    f.count(
                        """
                        SELECT count(*) FROM staff_profile_photos WHERE consent_actor_id IS NOT NULL OR consent_kind IS
                        NOT NULL OR consent_at IS NOT NULL OR consent_policy IS NOT NULL
                        """.trimIndent(),
                    ),
                )
                assertEquals(0L, f.count("SELECT count(*) FROM staff_profile_photos WHERE profile_id=${f.display}"))
                assertEquals(HttpStatusCode.Conflict, action(manager, "delete", replacementId, 2).status)
                assertEquals(HttpStatusCode.OK, action(manager, "delete", replacementId, 4).status)
                assertEquals(
                    HttpStatusCode.NotFound,
                    client.get("$path/$replacementId/content") { bearerAuth(owner) }.status,
                )
                assertEquals(HttpStatusCode.Conflict, action(owner, "delete", replacementId, 4).status)
                assertEquals(JsonNull, state(owner)["current"] ?: JsonNull)
            }
        }

    @Test fun `authenticated direct photo API visibility privacy and safe failures`() =
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
                assertEquals(HttpStatusCode.Forbidden, upload(syntheticPhoto(), user = 104).status)
                assertEquals(HttpStatusCode.Forbidden, client.get(path) { bearerAuth(token(104)) }.status)
                assertEquals(HttpStatusCode.BadRequest, upload(syntheticPhoto(), ContentType.Image.JPEG).status)
                assertEquals(HttpStatusCode.BadRequest, upload("<svg/>".toByteArray()).status)
                assertEquals(HttpStatusCode.PayloadTooLarge, upload(ByteArray(STAFF_PHOTO_MAX_INPUT_BYTES + 1)).status)
                val response = upload(syntheticPhoto())
                assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
                val photo = state()["current"]!!.jsonObject
                val id = photo["id"]!!.jsonPrimitive.content
                val preview = photo["previewUrl"]!!.jsonPrimitive.content
                val guest = "/api/guest/venue/${f.venue}/staff/${f.linked}/photos/$id"
                assertEquals(HttpStatusCode.NotFound, client.get(guest) { bearerAuth(token(104)) }.status)
                assertEquals(HttpStatusCode.Gone, action("publish").status)
                assertEquals(HttpStatusCode.Gone, action("consent").status)
                assertEquals(HttpStatusCode.Forbidden, action("consent", 103).status)
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
                f.sql("UPDATE staff_profiles SET is_guest_visible=FALSE WHERE id=${f.linked}")
                assertEquals(HttpStatusCode.NotFound, client.get(guest) { bearerAuth(token(104)) }.status)
                f.sql("UPDATE staff_profiles SET is_guest_visible=TRUE, published_at=NULL WHERE id=${f.linked}")
                assertEquals(HttpStatusCode.NotFound, client.get(guest) { bearerAuth(token(104)) }.status)
                f.sql("UPDATE staff_profiles SET published_at=CURRENT_TIMESTAMP WHERE id=${f.linked}")
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
                assertEquals(HttpStatusCode.Forbidden, action("revoke", 103).status)
                assertEquals(HttpStatusCode.OK, action("delete", 102).status)
                assertEquals(HttpStatusCode.NotFound, client.get(preview) { bearerAuth(token(101)) }.status)
                assertEquals(HttpStatusCode.NotFound, client.get(guest) { bearerAuth(token(104)) }.status)
                f.sql("UPDATE venues SET status='DELETED' WHERE id=${f.venue}")
                assertEquals(HttpStatusCode.NotFound, client.get(path) { bearerAuth(token(101)) }.status)
                assertEquals(HttpStatusCode.NotFound, client.get(preview) { bearerAuth(token(101)) }.status)
            }
        }
}
