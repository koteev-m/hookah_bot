package com.hookah.platform.backend.miniapp.venue.media

import com.hookah.platform.backend.ModuleOverrides
import com.hookah.platform.backend.ProductionTrafficGate
import com.hookah.platform.backend.miniapp.session.SessionTokenConfig
import com.hookah.platform.backend.miniapp.session.SessionTokenService
import com.hookah.platform.backend.miniapp.venue.staff.syntheticPhoto
import com.hookah.platform.backend.moduleWithOverrides
import com.hookah.platform.backend.support.SupportThreadRepository
import com.hookah.platform.backend.telegram.TelegramDownloadedFile
import com.hookah.platform.backend.test.PostgresTestEnv
import io.ktor.client.call.body
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
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class VenueInfoMediaRoutesPostgresTest {
    @Test fun `recovery blocks all media surfaces and requires new authority upload and explicit publication`(
        @TempDir decisions: Path,
    ) = testApplication {
        val database = PostgresTestEnv.createDatabase()
        val config = PostgresTestEnv.buildConfig(database)
        val tokenConfig = SessionTokenConfig.from(config, "test")
        val actor = SessionTokenService(tokenConfig).issueToken(101).token
        val stale = SessionTokenService(tokenConfig.copy(jwtSecret = "previous-secret")).issueToken(101).token
        val storage = FakeInfoStorage()
        val old = UUID.randomUUID().toString()
        storage.objects["venue-info/$old.pdf"] = syntheticPdf()
        val hold = Files.writeString(decisions.resolve("recovery-hold"), "incident")
        val gate = ProductionTrafficGate(decisions, "fixture")
        environment { this.config = config }
        application {
            moduleWithOverrides(
                ModuleOverrides(
                    productionTrafficGate = gate,
                    venueInfoPdfValidator = ::fixturePdfAdmission,
                    venueInfoMediaStorage = storage,
                ),
            )
        }
        assertEquals(HttpStatusCode.ServiceUnavailable, client.get("/health").status)
        Files.writeString(decisions.resolve("activate"), "fixture ${gate.instance}")
        Files.writeString(decisions.resolve("open"), "fixture ${gate.instance}")
        assertEquals(HttpStatusCode.OK, client.get("/health") { header("X-Hookah-Recovery-Probe", "1") }.status)
        assertEquals(
            HttpStatusCode.ServiceUnavailable,
            client.get("/health") {
                header("X-Hookah-Recovery-Probe", "1")
                header("X-Hookah-Pilot-Ingress", "public")
            }.status,
        )
        MediaFixture(PostgresTestEnv.createDataSource(database, false), storage).use { f ->
            f.init()
            val root = "/api/venue/${f.venue}"
            val guest = "/api/guest/venue/${f.venue}"
            val section = "info-sections/${f.section}"
            val paths =
                listOf(
                    "$root/$section/media/$old/content",
                    "$root/guest-preview/$section/media/$old",
                    "$guest/$section/media/$old",
                    "$root/staff/profiles/${f.seed.display}/photos/$old/content",
                    "$guest/staff/${f.seed.display}/photos/$old",
                    "$root/hall/1/plans/$old",
                    "/api/guest/venues/${f.venue}/hall/1/plans/$old",
                )
            for (path in paths + "/api/guest/tabs?table_session_id=1") {
                assertEquals(
                    HttpStatusCode.ServiceUnavailable,
                    client.get(path) {
                        header("X-Hookah-Recovery-Probe", "1")
                        bearerAuth(actor)
                    }.status,
                    path,
                )
            }
            f.sql("DELETE FROM venue_members WHERE venue_id=${f.venue}")
            f.sql("UPDATE venue_info_sections SET is_visible=FALSE WHERE id=${f.section}")
            val table = f.count("INSERT INTO venue_tables(venue_id, table_number) VALUES(${f.venue},99) RETURNING id")
            val session =
                f.count(
                    "INSERT INTO table_sessions(venue_id,table_id,started_at,last_activity_at," +
                        "expires_at,ended_at,status) " +
                        "VALUES(${f.venue},$table,now(),now(),now(),now(),'ENDED') RETURNING id",
                )
            val tab =
                f.count(
                    "INSERT INTO tab(venue_id,table_session_id,type,status) " +
                        "VALUES(${f.venue},$session,'SHARED','CLOSED') RETURNING id",
                )
            f.sql("INSERT INTO tab_member(tab_id,user_id,role) VALUES($tab,101,'MEMBER')")
            val order =
                f.count(
                    "INSERT INTO orders(venue_id,table_id,table_session_id,status,closed_at) " +
                        "VALUES(${f.venue},$table,$session,'CLOSED',now()) RETURNING id",
                )
            f.sql("INSERT INTO order_batches(order_id,source,status,tab_id) VALUES($order,'MINIAPP','DELIVERED',$tab)")
            PostgresTestEnv.createDataSource(database, false).use { ds ->
                val support = SupportThreadRepository(ds)
                assertNotNull(support.findOrderContextForGuest(order, 101))
                f.sql("DELETE FROM tab_member WHERE tab_id=$tab")
                assertNull(support.findOrderContextForGuest(order, 101))
            }
            Files.writeString(decisions.resolve("recovery-accepted"), "reviewed")
            assertEquals(HttpStatusCode.ServiceUnavailable, client.get(paths.first()) { bearerAuth(actor) }.status)
            Files.delete(hold)
            Files.writeString(decisions.resolve("open"), "fixture stale-instance")
            assertEquals(HttpStatusCode.ServiceUnavailable, client.get(paths.first()) { bearerAuth(actor) }.status)
            Files.writeString(decisions.resolve("open"), "fixture ${gate.instance}")
            val tabList = "/api/guest/tabs?table_session_id=$session"
            assertEquals(HttpStatusCode.NotFound, client.get(tabList) { bearerAuth(actor) }.status)
            assertEquals(1L, f.count("SELECT count(*) FROM order_batches WHERE tab_id=$tab AND order_id=$order"))

            suspend fun upload(token: String) =
                client.post("$root/$section/media") {
                    bearerAuth(token)
                    contentType(ContentType.Application.Pdf)
                    header("X-Media-Revision", 0)
                    header("Idempotency-Key", UUID.randomUUID().toString())
                    setBody(syntheticPdf())
                }
            assertEquals(HttpStatusCode.Unauthorized, upload(stale).status)
            assertEquals(HttpStatusCode.Forbidden, upload(actor).status)
            f.sql("INSERT INTO venue_members (venue_id,user_id,role) VALUES (${f.venue},101,'OWNER')")
            assertEquals(HttpStatusCode.OK, upload(actor).status)
            val fresh = f.legacy.listBySectionId(f.section).single().publicId
            assertNotNull(fresh)
            assertTrue(fresh != old)
            for (path in paths) {
                val status = client.get(path) { bearerAuth(actor) }.status
                assertTrue(status in listOf(HttpStatusCode.NotFound, HttpStatusCode.Forbidden), "$path $status")
            }
            val freshPath = "$guest/$section/media/$fresh"
            assertEquals(HttpStatusCode.NotFound, client.get(freshPath) { bearerAuth(actor) }.status)
            val revision = f.current().revision
            val publish =
                client.post("$root/$section/media-actions") {
                    bearerAuth(actor)
                    contentType(ContentType.Application.Json)
                    setBody("""{"action":"show","revision":$revision}""")
                }
            assertEquals(HttpStatusCode.OK, publish.status, publish.bodyAsText())
            assertEquals(HttpStatusCode.OK, client.get(freshPath) { bearerAuth(actor) }.status)
            assertEquals(HttpStatusCode.NotFound, client.get("$guest/$section/media/$old") { bearerAuth(actor) }.status)
        }
    }

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

    @Test fun `owner and manager replace lost hidden private media without granting guest access`() =
        testApplication {
            val db = PostgresTestEnv.createDatabase()
            val config = PostgresTestEnv.buildConfig(db)
            val storage = FakeInfoStorage()
            environment { this.config = config }
            application {
                moduleWithOverrides(
                    ModuleOverrides(venueInfoMediaStorage = storage, venueInfoPdfValidator = ::fixturePdfAdmission),
                )
            }
            client.get("/health")
            MediaFixture(PostgresTestEnv.createDataSource(db, false), storage).use { f ->
                f.init()
                f.sql("UPDATE venues SET status='PUBLISHED' WHERE id=${f.venue}")
                f.sql("INSERT INTO venue_subscriptions(venue_id,status) VALUES (${f.venue},'ACTIVE')")
                f.upload()
                val tokens = SessionTokenService(SessionTokenConfig.from(config, "test"))
                val root = "/api/venue/${f.venue}/info-sections/${f.section}"
                val guest = "/api/guest/venue/${f.venue}/info-sections/${f.section}/media"
                val original = f.current().media.single().id
                assertEquals(HttpStatusCode.OK, client.get("$guest/$original").status)
                f.action(original, "hide")
                val bytes = syntheticPdf()
                for (actor in listOf(101L, 102L)) {
                    val token = tokens.issueToken(actor).token
                    val old = f.current().media.single()
                    assertFalse(old.visible)
                    assertNotNull(storage.objects.remove(storage.objects.keys.single()))
                    val revision = f.current().revision

                    suspend fun replace(credential: String) =
                        client.post("$root/media") {
                            bearerAuth(credential)
                            contentType(ContentType.Application.Pdf)
                            header("X-Media-Revision", revision)
                            header("Idempotency-Key", UUID.randomUUID().toString())
                            header("X-Replaces-Media", old.id)
                            setBody(bytes)
                        }

                    assertEquals(HttpStatusCode.Forbidden, replace(tokens.issueToken(103L).token).status)
                    val result = replace(token)
                    assertEquals(HttpStatusCode.OK, result.status, result.bodyAsText())
                    val fresh = f.current().media.single()
                    assertTrue(fresh.id != old.id)
                    assertEquals(old.sortOrder, fresh.sortOrder)
                    assertFalse(fresh.visible)
                    assertEquals(HttpStatusCode.NotFound, client.get("$guest/${fresh.id}").status)
                    assertEquals(HttpStatusCode.NotFound, client.get("$guest/${old.id}").status)
                    val retired = client.get("$root/media/${old.id}/content") { bearerAuth(token) }
                    assertEquals(HttpStatusCode.NotFound, retired.status)
                    val preview = client.get("$root/media/${fresh.id}/content") { bearerAuth(token) }
                    assertEquals(HttpStatusCode.OK, preview.status)
                    assertContentEquals(bytes, preview.body<ByteArray>())
                    assertEquals(
                        1L,
                        f.count(
                            "SELECT count(*) FROM venue_info_section_media " +
                                "WHERE public_id='${old.id}' AND deleted_at IS NOT NULL",
                        ),
                    )
                }
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
