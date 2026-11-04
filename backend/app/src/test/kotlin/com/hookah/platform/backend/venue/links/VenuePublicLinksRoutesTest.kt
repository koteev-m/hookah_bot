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
import kotlinx.serialization.json.contentOrNull
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

    @Test
    fun `subdomain issuance and landing share registry and availability with old paths`() =
        verifyPublicEntry(branded = true, subdomains = true)

    @Test
    fun `confirmed Main Mini App issues direct venue links and preserves fallback and access guards`() =
        verifyPublicEntry(branded = true, subdomains = true, mainMiniApp = true)

    private fun verifyPublicEntry(
        branded: Boolean,
        subdomains: Boolean = false,
        mainMiniApp: Boolean = false,
    ) {
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
                        if (subdomains) {
                            put("venue.publicLinkFormat", "subdomain")
                        }
                        if (mainMiniApp) {
                            put("telegram.mainMiniAppConfigured", "true")
                        }
                    }
                environment { this.config = config }
                application { module() }
                client.get("/health")
                val id = PlatformVenueRepository(ds).createVenue("<Дым & Mix>", "Москва", "Арбат, 1").id
                val other = PlatformVenueRepository(ds).createVenue("Микс Казань", "Казань", "Баумана, 2").id
                ds.connection.use { c ->
                    c.createStatement().use { s ->
                        s.executeUpdate("UPDATE venues SET status = 'PUBLISHED', catalog_listed = FALSE WHERE id = $id")
                        s.executeUpdate("INSERT INTO users (telegram_user_id) VALUES (101), (303)")
                        s.executeUpdate(
                            "INSERT INTO venue_members (venue_id, user_id, role) VALUES " +
                                "($id,101,'OWNER'),($id,303,'MANAGER'),($other,101,'OWNER')",
                        )
                        s.executeUpdate("UPDATE venues SET status = 'PUBLISHED' WHERE id = $other")
                    }
                }
                val links = VenuePublicLinksRepository(ds, null)
                val settings = links.settings(id, 101)
                links.update(id, 101, VenueLinkUpdate(settings.slug, true, "new-dym"))
                val token = SessionTokenService(SessionTokenConfig.from(config, appEnv)).issueToken(101).token
                val manager = SessionTokenService(SessionTokenConfig.from(config, appEnv)).issueToken(303).token

                val expectedHost = if (branded) rootHost else stagingHost
                val management =
                    client.get("/api/venue/$id/public-link?venueId=$other&payload=${venuePayload(other)}") {
                        header(HttpHeaders.Authorization, "Bearer $token")
                        header(HttpHeaders.Host, "evil.example")
                        header("X-Forwarded-Host", "evil.example")
                    }
                assertEquals(HttpStatusCode.OK, management.status)
                assertEquals(
                    if (mainMiniApp) "https://t.me/ExampleBot?startapp=${venuePayload(id)}" else null,
                    Json.parseToJsonElement(management.bodyAsText())
                        .jsonObject["telegramUrl"]?.jsonPrimitive?.contentOrNull,
                )
                assertEquals(
                    if (subdomains) "https://new-dym.$rootHost" else "https://$expectedHost/v/new-dym",
                    Json.parseToJsonElement(management.bodyAsText()).jsonObject["url"]?.jsonPrimitive?.content,
                )
                val otherSettings = links.settings(other, 101)
                val otherHost = "${otherSettings.slug}.$rootHost"
                val otherManagement =
                    client.get("/api/venue/$other/public-link") {
                        header(HttpHeaders.Authorization, "Bearer $token")
                    }
                assertEquals(HttpStatusCode.OK, otherManagement.status)
                assertEquals(
                    if (mainMiniApp) "https://t.me/ExampleBot?startapp=${venuePayload(other)}" else null,
                    Json.parseToJsonElement(otherManagement.bodyAsText())
                        .jsonObject["telegramUrl"]?.jsonPrimitive?.contentOrNull,
                )
                assertEquals(
                    if (subdomains) "https://$otherHost" else "https://$expectedHost/v/${otherSettings.slug}",
                    Json.parseToJsonElement(otherManagement.bodyAsText()).jsonObject["url"]?.jsonPrimitive?.content,
                )
                val foreignManagement =
                    client.get("/api/venue/$other/public-link?venueId=$id") {
                        header(HttpHeaders.Authorization, "Bearer $manager")
                        header(HttpHeaders.Host, otherHost)
                    }
                assertEquals(HttpStatusCode.Forbidden, foreignManagement.status)
                val foreignUpdate =
                    client.put("/api/venue/$other/public-link?venueId=$id") {
                        header(HttpHeaders.Authorization, "Bearer $manager")
                        header(HttpHeaders.ContentType, "application/json")
                        setBody("""{"expectedSlug":"${otherSettings.slug}","confirmed":true,"slug":"stolen"}""")
                    }
                assertEquals(HttpStatusCode.Forbidden, foreignUpdate.status)

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

                suspend fun subdomain(host: String) =
                    client.get("/?slug=new-dym&redirect=https://evil.example") {
                        header(HttpHeaders.Host, host)
                        header("X-Forwarded-Host", "new-dym.$rootHost")
                        header("Forwarded", "host=new-dym.$rootHost")
                    }

                suspend fun assertDeniedHost(host: String) {
                    val page = subdomain(host)
                    assertEquals(HttpStatusCode.NotFound, page.status, host)
                    assertEquals("no-store", page.headers[HttpHeaders.CacheControl])
                    val body = page.bodyAsText()
                    assertTrue(body.contains("Заведение недоступно"))
                    assertFalse(body.contains("t.me"))
                }

                for (slug in listOf(settings.slug, "new-dym")) {
                    val page = subdomain("$slug.$rootHost")
                    assertEquals(HttpStatusCode.OK, page.status)
                    assertEquals("no-store", page.headers[HttpHeaders.CacheControl])
                    assertTrue(page.bodyAsText().contains("https://t.me/ExampleBot?start=${venuePayload(id)}"))
                }
                val otherPage = subdomain(otherHost)
                assertEquals(HttpStatusCode.OK, otherPage.status)
                val otherBody = otherPage.bodyAsText()
                assertTrue(otherBody.contains("Баумана, 2"))
                assertTrue(otherBody.contains("https://t.me/ExampleBot?start=${venuePayload(other)}"))
                assertFalse(otherBody.contains("Арбат, 1"))
                for (host in listOf(
                    "unknown.$rootHost", "staging.$rootHost", "www.$rootHost", "dev.$rootHost",
                    "markettap-stg.$rootHost", "api.$rootHost", "admin.$rootHost",
                    "new-dym.evil.example", "new-dym.$rootHost.evil.example",
                    "x.new-dym.$rootHost", "new-dym.$rootHost:443", "new-dym.$rootHost.", rootHost,
                )) {
                    assertDeniedHost(host)
                }
                repeat(2) {
                    for ((slug, host) in listOf(settings.slug to stagingHost, "new-dym" to expectedHost)) {
                        val page =
                            client.get("/v/$slug?slug=${otherSettings.slug}") {
                                header(HttpHeaders.Host, host)
                                header("X-Forwarded-Host", "evil.example")
                            }
                        assertEquals(HttpStatusCode.OK, page.status)
                        val body = page.bodyAsText()
                        assertTrue(body.contains("&lt;Дым &amp; Mix&gt;"))
                        assertTrue(body.contains("Арбат, 1"))
                        assertTrue(body.contains("https://t.me/ExampleBot?start=${venuePayload(id)}"))
                        assertEquals(mainMiniApp, body.contains("https://t.me/ExampleBot?startapp=${venuePayload(id)}"))
                        assertFalse(body.contains("evil.example"))
                        assertFalse(body.contains("<Дым"))
                        assertEquals("no-store", page.headers[HttpHeaders.CacheControl])
                    }
                    val entry =
                        client.get("/api/guest/venue-entry/${venuePayload(id)}?payload=${venuePayload(other)}") {
                            header(HttpHeaders.Authorization, "Bearer $token")
                        }
                    assertEquals(HttpStatusCode.OK, entry.status)
                    assertEquals(
                        id.toString(),
                        Json.parseToJsonElement(entry.bodyAsText()).jsonObject["venueId"]?.jsonPrimitive?.content,
                    )
                    val otherEntry =
                        client.get("/api/guest/venue-entry/${venuePayload(other)}") {
                            header(HttpHeaders.Authorization, "Bearer $token")
                        }
                    assertEquals(HttpStatusCode.OK, otherEntry.status)
                    assertEquals(
                        other.toString(),
                        Json.parseToJsonElement(otherEntry.bodyAsText()).jsonObject["venueId"]?.jsonPrimitive?.content,
                    )
                    val catalog =
                        client.get(
                            "/api/guest/catalog?q=Mix",
                        ) { header(HttpHeaders.Authorization, "Bearer $token") }
                    assertFalse(catalog.bodyAsText().contains("Арбат"))
                }
                assertEquals(before, counts())
                if (subdomains) {
                    val legacy = insertVenue(ds, "Старое заведение", "Пермь", "Ленина, 3", "PUBLISHED")
                    ds.connection.use { c ->
                        assertTrue(VenueSlugs.reserve(c, legacy, "www"))
                        c.createStatement().use { s ->
                            s.executeUpdate("UPDATE venues SET public_slug = 'www' WHERE id = $legacy")
                            s.executeUpdate(
                                "INSERT INTO venue_members (venue_id, user_id, role) VALUES ($legacy,101,'OWNER')",
                            )
                        }
                    }
                    val legacyHost = "venue--${legacy.toString(36)}.$rootHost"
                    val legacyManagement =
                        client.get("/api/venue/$legacy/public-link") {
                            header(HttpHeaders.Authorization, "Bearer $token")
                        }
                    assertEquals(HttpStatusCode.OK, legacyManagement.status)
                    val legacySettings = Json.parseToJsonElement(legacyManagement.bodyAsText()).jsonObject
                    assertEquals("https://$legacyHost", legacySettings["url"]?.jsonPrimitive?.content)
                    val renamed =
                        client.put("/api/venue/$legacy/public-link?venueId=$id") {
                            header(HttpHeaders.Authorization, "Bearer $token")
                            header(HttpHeaders.ContentType, "application/json")
                            setBody("""{"expectedSlug":"www","confirmed":true,"slug":"permskij-dym"}""")
                        }
                    assertEquals(HttpStatusCode.OK, renamed.status)
                    assertEquals(
                        if (mainMiniApp) "https://t.me/ExampleBot?startapp=${venuePayload(legacy)}" else null,
                        Json.parseToJsonElement(renamed.bodyAsText())
                            .jsonObject["telegramUrl"]?.jsonPrimitive?.contentOrNull,
                    )
                    assertEquals(
                        "https://permskij-dym.$rootHost",
                        Json.parseToJsonElement(renamed.bodyAsText()).jsonObject["url"]?.jsonPrimitive?.content,
                    )
                    val legacyPages =
                        listOf(subdomain(legacyHost), subdomain("permskij-dym.$rootHost"), client.get("/v/www"))
                    for (page in legacyPages) {
                        assertEquals(HttpStatusCode.OK, page.status)
                        assertTrue(page.bodyAsText().contains("https://t.me/ExampleBot?start=${venuePayload(legacy)}"))
                    }
                    assertDeniedHost("www.$rootHost")
                    assertDeniedHost("venue--${id.toString(36)}.$rootHost")
                }
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
                    assertDeniedHost("new-dym.$rootHost")
                    for (path in listOf(
                        "/v/${settings.slug}",
                        "/v/new-dym",
                        "/api/guest/venue/$id",
                        "/api/guest/venue-entry/${venuePayload(id)}?payload=${venuePayload(other)}",
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
                assertDeniedHost("new-dym.$rootHost")
                assertDeniedHost("${settings.slug}.$rootHost")
                for (path in listOf(
                    "/v/${settings.slug}",
                    "/v/new-dym",
                    "/api/guest/venue/$id",
                    "/api/guest/venue-entry/${venuePayload(id)}?payload=${venuePayload(other)}",
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
}
