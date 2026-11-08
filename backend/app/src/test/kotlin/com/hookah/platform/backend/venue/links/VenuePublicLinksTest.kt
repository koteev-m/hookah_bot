package com.hookah.platform.backend.venue.links

import com.hookah.platform.backend.api.ForbiddenException
import com.hookah.platform.backend.api.InvalidInputException
import com.hookah.platform.backend.miniapp.guest.db.GuestVenueRepository
import com.hookah.platform.backend.platform.PlatformVenueRepository
import com.hookah.platform.backend.telegram.db.VenueRepository
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import kotlinx.coroutines.runBlocking
import org.flywaydb.core.Flyway
import java.sql.Statement
import java.util.UUID
import javax.sql.DataSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class VenuePublicLinksTest {
    @Test
    fun `transliteration is deterministic bounded and payload is a separate namespace`() {
        assertEquals("dym-lyod-mix-24", VenueSlugs.normalize("Дым & Лёд / Mix — 24!"))
        assertEquals("yoshkar-ola-shchuka-yug", VenueSlugs.normalize("Йошкар-Ола: Щука Юг"))
        assertEquals("", VenueSlugs.normalize("!!! ъь 💨"))
        assertEquals(64, VenueSlugs.normalize("д".repeat(200)).length)
        listOf("../x", "a--b", "-a", "a-", "Upper", "", "я", "a".repeat(65)).forEach {
            assertFalse(VenueSlugs.valid(it), it)
        }
        assertTrue(isVenuePayload(venuePayload(Long.MAX_VALUE)))
        assertFalse(isVenuePayload("venue_" + "a".repeat(37)))
        assertEquals(Long.MAX_VALUE, parseVenuePayload(venuePayload(Long.MAX_VALUE)))
        listOf("venue_", "venue_0", "venue_-1", "venue_01", "venue_1?redirect=x", "staff_invite_1").forEach {
            assertNull(parseVenuePayload(it))
        }
        assertNull(VenuePublicLinkConfig.from("https://user:pass@example.org/miniapp", "ExampleBot"))
        assertNull(VenuePublicLinkConfig.from("javascript:alert(1)", "ExampleBot"))
        assertNull(VenuePublicLinkConfig.from("http://example.org/miniapp", "ExampleBot"))
        assertNull(VenuePublicLinkConfig.from("https://example.org", "bad/name"))
        val config =
            assertNotNull(VenuePublicLinkConfig.from("https://stage.example.org/miniapp/?mode=home", "ExampleBot"))
        assertEquals("https://stage.example.org/v/dym", config.publicUrl(1, "dym"))
        assertEquals("https://t.me/ExampleBot?start=venue_1", config.telegramUrl(1))
        assertNull(config.directMiniAppUrl(1))
        assertEquals("https://t.me/ExampleBot?startapp=venue_1", config.telegramUrl(1, true))
    }

    @Test
    fun `explicit HTTPS public origin changes issued URLs only`() {
        val miniApp = "https://miniapp.example.org/miniapp/?mode=home"
        val branded = "https://hookahtootah.club"
        for (env in listOf("staging", "production")) {
            val default = assertNotNull(VenuePublicLinkConfig.from(miniApp, "ExampleBot", appEnv = env))
            assertEquals("https://miniapp.example.org/v/dym", default.publicUrl(1, "dym"))
        }
        assertEquals(
            "https://staging.hookahtootah.club/v/dym",
            VenuePublicLinkConfig.from(
                "https://staging.hookahtootah.club/miniapp/",
                "ExampleBot",
                appEnv = "staging",
            )?.publicUrl(1, "dym"),
        )
        val config =
            assertNotNull(
                VenuePublicLinkConfig.from(miniApp, "ExampleBot", true, "$branded/", "production"),
            )
        assertEquals("$branded/v/dym", config.publicUrl(1, "dym"))
        assertEquals("https://t.me/ExampleBot?startapp=venue_1", config.telegramUrl(1))
        assertEquals("https://t.me/ExampleBot?startapp=venue_1", config.directMiniAppUrl(1))
        assertEquals("https://t.me/ExampleBot?startapp=venue_10", config.directMiniAppUrl(36))
        assertEquals("https://t.me/ExampleBot?start=venue_1", config.telegramUrl(1, false))
        assertNull(VenuePublicLinkConfig.from(null, "ExampleBot", true, branded, "production"))
        for (invalid in listOf(
            "http://example.org", "//example.org", " ", "https://user@example.org", "https://example.org:",
            "https://example.org:0", "https://example.org:65536", "$branded/v", "$branded?", "$branded#fragment",
        )) {
            assertFailsWith<IllegalArgumentException>(invalid) {
                VenuePublicLinkConfig.from(miniApp, "ExampleBot", true, invalid, "production")
            }
        }
    }

    @Test
    fun `staging accepts only the approved branded origin`() {
        val miniApp = "https://staging.hookahtootah.club/miniapp/"
        val branded = "https://hookahtootah.club"
        val config =
            assertNotNull(VenuePublicLinkConfig.from(miniApp, "ExampleBot", publicOrigin = branded, appEnv = "staging"))
        assertEquals("$branded/v/dym", config.publicUrl(1, "dym"))
        assertEquals("https://t.me/ExampleBot?start=venue_1", config.telegramUrl(1))
        for (invalid in listOf(
            "https://example.org", "http://hookahtootah.club", "https://www.hookahtootah.club", "$branded.evil.example",
            "$branded/", "$branded:443", "$branded/v", "$branded?", "$branded#fragment",
            "https://user@hookahtootah.club",
        )) {
            assertFailsWith<IllegalArgumentException>(invalid) {
                VenuePublicLinkConfig.from(miniApp, "ExampleBot", publicOrigin = invalid, appEnv = "staging")
            }
        }
        for (env in listOf("dev", "test")) {
            assertFailsWith<IllegalArgumentException>(env) {
                VenuePublicLinkConfig.from(miniApp, "ExampleBot", publicOrigin = branded, appEnv = env)
            }
        }
    }

    @Test
    fun `subdomain issuance is explicit and gives legacy slugs a distinct DNS namespace`() {
        val miniApp = "https://staging.hookahtootah.club/miniapp/"
        val root = "https://hookahtootah.club"
        val long = "a".repeat(64)
        val active =
            assertNotNull(VenuePublicLinkConfig.from(miniApp, "ExampleBot", true, root, "staging", "subdomain"))
        assertEquals("https://dym.hookahtootah.club", active.publicUrl(1, "dym"))
        assertEquals("https://venue--z.hookahtootah.club", active.publicUrl(35, long))
        assertEquals("https://venue--10.hookahtootah.club", active.publicUrl(36, "www"))
        assertEquals("https://t.me/ExampleBot?startapp=venue_1", active.telegramUrl(1))
        for (format in listOf(null, "path")) {
            val config = assertNotNull(VenuePublicLinkConfig.from(miniApp, "ExampleBot", true, root, "staging", format))
            assertEquals("$root/v/dym", config.publicUrl(1, "dym"))
            assertEquals("$root/v/$long", config.publicUrl(35, long))
            assertEquals("dym", config.labelFromHost("DYM.hookahtootah.club"))
        }
        for (format in listOf("", "SUBDOMAIN", "https://another.example")) {
            assertFailsWith<IllegalArgumentException> {
                VenuePublicLinkConfig.from(miniApp, "ExampleBot", appEnv = "staging", publicLinkFormat = format)
            }
        }
        for ((url, env) in listOf(miniApp to "production", miniApp to "dev", "https://example.org" to "staging")) {
            assertFailsWith<IllegalArgumentException> {
                VenuePublicLinkConfig.from(url, "ExampleBot", appEnv = env, publicLinkFormat = "subdomain")
            }
        }
    }

    @Test
    fun `new venues automatically get distinct DNS labels even for service names and long collisions`() =
        runBlocking {
            h2().use { ds ->
                val platform = PlatformVenueRepository(ds)
                val links = VenuePublicLinksRepository(ds, 999)
                val names =
                    listOf("WWW", "DEV", "staging", "markettap-stg", "api", "admin", "Д".repeat(64), "Д".repeat(65))
                val venues = names.map { platform.createVenue(it, "Москва", "Арбат, 1").id }
                val slugs = venues.map { assertNotNull(links.settings(it, 999).slug) }
                assertEquals(venues.size, slugs.toSet().size)
                for ((id, slug) in venues.zip(slugs)) {
                    assertTrue(VenueSlugs.validSubdomain(slug), slug)
                    assertEquals(id, links.resolveSlug(slug))
                }
                for (invalid in listOf("www", "dev", "staging", "markettap-stg", "api", "admin", "a".repeat(64))) {
                    assertFailsWith<InvalidInputException> {
                        links.update(venues.first(), 999, VenueLinkUpdate(slugs.first(), true, invalid))
                    }
                    assertNull(links.resolveSlug(invalid))
                }
            }
        }

    @Test
    fun `migration backfills every lifecycle without changing publication or location and rerun is stable`() {
        h2(migrate = false).use { ds ->
            flyway(ds, "129").migrate()
            val first = insertVenue(ds, "Дым", "Москва", "Арбат, 1", "PUBLISHED")
            val second = insertVenue(ds, "Дым", "Казань", "Баумана, 2", "DRAFT")
            val third = insertVenue(ds, "!!!", null, null, "DELETED")
            val reserved = insertVenue(ds, "www", null, null, "PUBLISHED")
            val long = "a".repeat(64)
            val legacyLong = insertVenue(ds, long, null, null, "PUBLISHED")
            val migration = flyway(ds, "131").migrate()
            assertEquals(2, migration.migrationsExecuted)
            runBlocking {
                val links = VenuePublicLinksRepository(ds, 999)
                assertEquals(first, links.resolveSlug("dym"))
                assertEquals(second, links.resolveSlug("dym-kazan-baumana-2"))
                assertEquals(third, links.resolveSlug("venue"))
                assertEquals(reserved, links.resolveSlug("www"))
                assertEquals(legacyLong, links.resolveSlug(long))
                assertNull(links.resolveSubdomain("www"))
                assertEquals(legacyLong, links.resolveSubdomain(VenueSlugs.dnsLabel(long, legacyLong)))
                assertEquals(reserved, links.resolveSubdomain(VenueSlugs.dnsLabel("www", reserved)))
                assertNull(links.resolveSubdomain("venue--${first.toString(36)}"))
                val updated = links.update(legacyLong, 999, VenueLinkUpdate(long, true, long, false))
                assertEquals(long, updated.slug)
                assertFalse(updated.catalogListed)
            }
            ds.connection.use { c ->
                c.createStatement().use { s ->
                    s.executeQuery("SELECT COUNT(*) FROM venues WHERE catalog_listed = TRUE").use { r ->
                        assertTrue(r.next())
                        assertEquals(4, r.getInt(1))
                    }
                    s.executeQuery("SELECT status, address FROM venues WHERE id = $second").use { r ->
                        assertTrue(r.next())
                        assertEquals("DRAFT", r.getString(1))
                        assertEquals("Баумана, 2", r.getString(2))
                    }
                }
            }
            assertEquals(0, flyway(ds, "131").migrate().migrationsExecuted)
        }
    }

    @Test
    fun `creation collisions stable aliases unlisted owner scope and transactional audit`() =
        runBlocking {
            h2().use { ds -> verifyLinks(ds) }
        }
}

internal suspend fun verifyLinks(ds: DataSource) {
    val platform = PlatformVenueRepository(ds)
    val first = platform.createVenue("Дым", "Москва", "Арбат, 1").id
    val second = platform.createVenue("Дым", "Казань", "Баумана, 2").id
    val links = VenuePublicLinksRepository(ds, 999)
    ds.connection.use { c ->
        c.createStatement().use { s ->
            s.executeUpdate("INSERT INTO users (telegram_user_id) VALUES (101), (202), (303), (999)")
            s.executeUpdate(
                "INSERT INTO venue_members (venue_id, user_id, role) VALUES " +
                    "($first,101,'OWNER'), ($second,202,'OWNER'), ($first,303,'MANAGER')",
            )
            s.executeUpdate("UPDATE venues SET status = 'PUBLISHED'")
        }
    }
    val original = links.settings(first, 101)
    val other = links.settings(second, 202)
    assertEquals("dym", original.slug)
    assertEquals("dym-kazan-baumana-2", other.slug)
    assertNotEquals(original.slug, other.slug)
    assertFalse(links.settings(first, 303).canManage)
    assertFailsWith<ForbiddenException> { links.settings(second, 303) }
    assertFailsWith<ForbiddenException> {
        links.update(first, 303, VenueLinkUpdate(original.slug, true, "stolen"))
    }
    assertFailsWith<InvalidInputException> {
        links.update(first, 101, VenueLinkUpdate(original.slug, false, "new-dym"))
    }
    val changed = links.update(first, 101, VenueLinkUpdate(original.slug, true, "new-dym", false))
    assertEquals(first, links.resolveSlug("dym"))
    assertEquals(first, links.resolveSlug("new-dym"))
    assertFalse(changed.catalogListed)
    assertFalse(GuestVenueRepository(ds).listCatalogVenues("Дым").any { it.id == first })
    assertFalse(VenueRepository(ds).listCatalogVenuesForGuest().any { it.id == first })
    assertNotNull(VenueRepository(ds).findCatalogVenueByIdForGuest(first))
    assertFailsWith<InvalidInputException> {
        links.update(second, 202, VenueLinkUpdate(other.slug, true, "dym"))
    }
    platform.updateVenueName(first, "Новое название")
    assertEquals("new-dym", links.settings(first, 101).slug)
    ds.connection.use { c ->
        c.createStatement().use { s ->
            s.executeQuery("SELECT COUNT(*) FROM audit_log WHERE action = 'VENUE_PUBLIC_LINK_UPDATED'").use { r ->
                assertTrue(r.next())
                assertEquals(1, r.getInt(1))
            }
            s.executeUpdate("ALTER TABLE audit_log RENAME TO link_audit_unavailable")
        }
    }
    try {
        assertFailsWith<java.sql.SQLException> {
            links.update(first, 101, VenueLinkUpdate(changed.slug, true, "must-rollback", true))
        }
        assertNull(links.resolveSlug("must-rollback"))
        assertEquals(changed.slug, links.settings(first, 101).slug)
        assertFalse(links.settings(first, 101).catalogListed)
    } finally {
        ds.connection.use {
                c ->
            c.createStatement().use { it.executeUpdate("ALTER TABLE link_audit_unavailable RENAME TO audit_log") }
        }
    }
    ds.connection.use { c ->
        c.createStatement().use { s ->
            s.executeUpdate("INSERT INTO venue_members (venue_id, user_id, role) VALUES ($second,101,'OWNER')")
            s.executeUpdate("DELETE FROM venues WHERE id = $first")
        }
    }
    assertEquals(second, links.settings(second, 101).venueId)
    assertEquals(first, links.resolveSlug("dym"))
    assertFailsWith<InvalidInputException> { links.update(second, 999, VenueLinkUpdate(other.slug, true, "new-dym")) }
    assertEquals(
        "platform-choice",
        links.update(second, 999, VenueLinkUpdate(other.slug, true, "platform-choice")).slug,
    )
}

internal fun h2(migrate: Boolean = true): HikariDataSource {
    val ds =
        HikariDataSource(
            HikariConfig().apply {
                jdbcUrl = "jdbc:h2:mem:venue_links_${UUID.randomUUID()};MODE=PostgreSQL;" +
                    "DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1"
                username = "sa"
                password = ""
                maximumPoolSize = 4
            },
        )
    if (migrate) flyway(ds).migrate()
    return ds
}

internal fun flyway(
    ds: DataSource,
    target: String? = null,
): Flyway =
    Flyway.configure().dataSource(ds)
        .locations(
            "classpath:db/migration/" +
                if (ds.connection.use { it.metaData.databaseProductName } == "H2") "h2" else "postgresql",
        )
        .apply { if (target != null) target(target) }.load()

internal fun insertVenue(
    ds: DataSource,
    name: String,
    city: String?,
    address: String?,
    status: String,
): Long =
    ds.connection.use { c ->
        c.prepareStatement(
            "INSERT INTO venues (name, city, address, status) VALUES (?, ?, ?, ?)",
            Statement.RETURN_GENERATED_KEYS,
        ).use {
                s ->
            s.setString(1, name)
            s.setString(2, city)
            s.setString(3, address)
            s.setString(4, status)
            s.executeUpdate()
            s.generatedKeys.use { r ->
                check(r.next())
                r.getLong(1)
            }
        }
    }
