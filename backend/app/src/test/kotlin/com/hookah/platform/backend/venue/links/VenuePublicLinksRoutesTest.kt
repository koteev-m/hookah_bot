package com.hookah.platform.backend.venue.links

import com.hookah.platform.backend.miniapp.session.SessionTokenConfig
import com.hookah.platform.backend.miniapp.session.SessionTokenService
import com.hookah.platform.backend.module
import com.hookah.platform.backend.platform.PlatformVenueRepository
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.config.MapApplicationConfig
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class VenuePublicLinksRoutesTest {
    @Test
    fun `public entry is safe read only and aliases direct ids and payload share lifecycle subscription guards`() =
        verifyPublicEntry(branded = false)

    @Test
    fun `branded origin preserves previous host alias and Telegram fallback`() = verifyPublicEntry(branded = true)

    private fun verifyPublicEntry(branded: Boolean) =
        testApplication {
            h2().use { ds ->
                val appEnv = "staging"
                val stagingHost = "staging.hookahtootah.club"
                val rootHost = "hookahtootah.club"
                val config =
                    MapApplicationConfig(
                        "app.env" to appEnv,
                        "db.jdbcUrl" to ds.jdbcUrl,
                        "db.user" to "sa",
                        "db.password" to "",
                        "api.session.jwtSecret" to "links-test-secret",
                        "telegram.enabled" to "false",
                        "telegram.trafficPolicy" to "PRODUCT",
                        "telegram.webAppPublicUrl" to "https://$stagingHost/miniapp/",
                        "telegram.botUsername" to "ExampleBot",
                        "venue.staffInviteSecretPepper" to "synthetic-brand-route-fixture",
                    ).apply {
                        if (branded) {
                            put("venue.publicLinkOrigin", "https://$rootHost")
                        }
                    }
                environment { this.config = config }
                application { module() }
                client.get("/health")
                val id = PlatformVenueRepository(ds).createVenue("<Дым & Mix>", "Москва", "Арбат, 1").id
                ds.connection.use { c ->
                    c.createStatement().use { s ->
                        s.executeUpdate("UPDATE venues SET status = 'PUBLISHED', catalog_listed = FALSE WHERE id = $id")
                        s.executeUpdate("INSERT INTO users (telegram_user_id) VALUES (101), (303)")
                        s.executeUpdate(
                            "INSERT INTO venue_members (venue_id, user_id, role) VALUES " +
                                "($id,101,'OWNER'),($id,303,'MANAGER')",
                        )
                    }
                }
                val links = VenuePublicLinksRepository(ds, null)
                val settings = links.settings(id, 101)
                links.update(id, 101, VenueLinkUpdate(settings.slug, true, "new-dym"))
                val token = SessionTokenService(SessionTokenConfig.from(config, appEnv)).issueToken(101).token
                val manager = SessionTokenService(SessionTokenConfig.from(config, appEnv)).issueToken(303).token

                val expectedHost = if (branded) rootHost else stagingHost
                val management =
                    client.get("/api/venue/$id/public-link") {
                        header(HttpHeaders.Authorization, "Bearer $token")
                        header(HttpHeaders.Host, "evil.example")
                        header("X-Forwarded-Host", "evil.example")
                    }
                assertEquals(HttpStatusCode.OK, management.status)
                assertEquals(
                    "https://$expectedHost/v/new-dym",
                    Json.parseToJsonElement(management.bodyAsText()).jsonObject["url"]?.jsonPrimitive?.content,
                )

                fun counts() =
                    ds.connection.use { c ->
                        listOf(
                            "venue_subscriptions",
                            "venue_public_addresses",
                            "table_sessions",
                            "tab",
                            "orders",
                            "bookings",
                            "visits",
                            "venue_members",
                            "audit_log",
                        ).associateWith {
                                name ->
                            c.createStatement().use {
                                    s ->
                                s.executeQuery("SELECT COUNT(*) FROM $name").use { r ->
                                    r.next()
                                    r.getInt(1)
                                }
                            }
                        }
                    }
                val before = counts()
                repeat(2) {
                    for ((slug, host) in listOf(settings.slug to stagingHost, "new-dym" to expectedHost)) {
                        val page =
                            client.get("/v/$slug?redirect=https://evil.example") {
                                header(HttpHeaders.Host, host)
                                header("X-Forwarded-Host", "evil.example")
                            }
                        assertEquals(HttpStatusCode.OK, page.status)
                        val body = page.bodyAsText()
                        assertTrue(body.contains("&lt;Дым &amp; Mix&gt;"))
                        assertTrue(body.contains("Арбат, 1"))
                        assertTrue(body.contains("https://t.me/ExampleBot?start=${venuePayload(id)}"))
                        assertFalse(body.contains("evil.example"))
                        assertFalse(body.contains("<Дым"))
                        assertEquals("no-store", page.headers[HttpHeaders.CacheControl])
                    }
                    val entry =
                        client.get("/api/guest/venue-entry/${venuePayload(id)}") {
                            header(HttpHeaders.Authorization, "Bearer $token")
                        }
                    assertEquals(HttpStatusCode.OK, entry.status)
                    assertEquals(
                        id.toString(),
                        Json.parseToJsonElement(entry.bodyAsText()).jsonObject["venueId"]?.jsonPrimitive?.content,
                    )
                    val catalog =
                        client.get(
                            "/api/guest/catalog?q=Mix",
                        ) { header(HttpHeaders.Authorization, "Bearer $token") }
                    assertFalse(catalog.bodyAsText().contains("Арбат"))
                }
                assertEquals(before, counts())
                assertEquals(
                    HttpStatusCode.Unauthorized,
                    client.get("/api/guest/venue-entry/${venuePayload(id)}").status,
                )
                assertEquals(HttpStatusCode.Unauthorized, client.put("/api/venue/$id/public-link").status)
                val denied =
                    client.put("/api/venue/$id/public-link") {
                        header(HttpHeaders.Authorization, "Bearer $manager")
                        header(HttpHeaders.ContentType, "application/json")
                        setBody("""{"expectedSlug":"new-dym","confirmed":true,"slug":"stolen"}""")
                    }
                assertEquals(HttpStatusCode.Forbidden, denied.status)
                for (status in listOf("DRAFT", "HIDDEN", "PAUSED", "SUSPENDED", "ARCHIVED", "DELETED")) {
                    ds.connection.use {
                            c ->
                        c.createStatement().use {
                            it.executeUpdate(
                                "UPDATE venues SET status = '$status' WHERE id = $id",
                            )
                        }
                    }
                    for (path in listOf(
                        "/v/${settings.slug}",
                        "/v/new-dym",
                        "/api/guest/venue/$id",
                        "/api/guest/venue-entry/${venuePayload(id)}",
                    )) {
                        assertEquals(
                            HttpStatusCode.NotFound,
                            client.get(path) {
                                header(HttpHeaders.Authorization, "Bearer $token")
                            }.status,
                            "$status $path",
                        )
                    }
                }
                ds.connection.use { c ->
                    c.createStatement().use { s ->
                        s.executeUpdate("UPDATE venues SET status = 'PUBLISHED' WHERE id = $id")
                        s.executeUpdate("INSERT INTO venue_subscriptions (venue_id, status) VALUES ($id, 'SUSPENDED')")
                    }
                }
                for (path in listOf(
                    "/v/${settings.slug}",
                    "/v/new-dym",
                    "/api/guest/venue/$id",
                    "/api/guest/venue-entry/${venuePayload(id)}",
                )) {
                    assertEquals(
                        HttpStatusCode.NotFound,
                        client.get(path) { header(HttpHeaders.Authorization, "Bearer $token") }.status,
                    )
                }
                val unknown = client.get("/v/unknown") { header(HttpHeaders.Host, expectedHost) }
                assertEquals(HttpStatusCode.NotFound, unknown.status)
                assertEquals("no-store", unknown.headers[HttpHeaders.CacheControl])
                assertTrue(unknown.bodyAsText().contains("Заведение недоступно"))
                assertEquals(
                    HttpStatusCode.NotFound,
                    client.get("/api/guest/venue-entry/venue_invalid!") {
                        header(HttpHeaders.Authorization, "Bearer $token")
                    }.status,
                )
            }
        }
}
