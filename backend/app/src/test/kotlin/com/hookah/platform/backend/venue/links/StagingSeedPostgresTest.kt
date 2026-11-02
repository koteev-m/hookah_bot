package com.hookah.platform.backend.venue.links

import com.hookah.platform.backend.platform.PlatformVenueRepository
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.testcontainers.DockerClientFactory
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName
import java.nio.file.Files
import java.nio.file.Path
import java.sql.SQLException
import javax.sql.DataSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class StagingSeedPostgresTest {
    private val parameters = StagingSeedParameters(101, 202, 303, "Дым & Лёд", "Москва", "Арбат, 1 'smoke'")

    @Test
    fun `staging seed registers a unique address and preserves issued links on every repeat`() =
        runBlocking {
            withPostgres { ds ->
                val other = PlatformVenueRepository(ds).createVenue("Дым + Лёд", "Казань", "Баумана, 2")
                ds.connection.use { connection ->
                    connection.createStatement().use {
                        it.executeUpdate("INSERT INTO users (telegram_user_id) VALUES (999)")
                    }
                }
                val links = VenuePublicLinksRepository(ds, 999)
                val originalOther = links.settings(other.id, 999)
                links.update(other.id, 999, VenueLinkUpdate(originalOther.slug, true, "other-issued"))
                val oldAddresses = addresses(ds)
                val otherBefore = venueState(ds, other.id)
                ds.connection.use { connection ->
                    val first = seedStagingVenue(connection, parameters, seedSql())
                    assertEquals("dym-lyod-moskva-arbat-1-smoke", first.slug)
                    assertTrue(VenueSlugs.valid(first.slug))
                    assertNotEquals(originalOther.slug, first.slug)
                    assertEquals(first.venueId, links.resolveSlug(first.slug))
                    assertEquals("PUBLISHED", venueState(ds, first.venueId)["status"])
                    assertEquals(parameters.address, venueState(ds, first.venueId)["address"])
                    val firstAddresses = addresses(ds)
                    val repeated = seedStagingVenue(connection, parameters, seedSql())
                    assertEquals(first, repeated)
                    assertEquals(firstAddresses, addresses(ds))

                    links.update(first.venueId, 999, VenueLinkUpdate(first.slug, true, "seed-issued"))
                    val issuedAddresses = addresses(ds)
                    val afterManualChange = seedStagingVenue(connection, parameters, seedSql())
                    assertEquals(first.venueId, afterManualChange.venueId)
                    assertEquals("seed-issued", afterManualChange.slug)
                    assertEquals(first.venueId, links.resolveSlug(first.slug))
                    assertEquals(first.venueId, links.resolveSlug("seed-issued"))
                    assertEquals(issuedAddresses, addresses(ds))
                    assertEquals("PUBLISHED", venueState(ds, first.venueId)["status"])
                }
                assertEquals(otherBefore, venueState(ds, other.id))
                oldAddresses.forEach { (slug, venueId) -> assertEquals(venueId, links.resolveSlug(slug)) }
            }
        }

    @Test
    fun `address allocation failure rolls back the complete staging seed`() =
        runBlocking {
            withPostgres { ds ->
                ds.connection.use { connection ->
                    connection.createStatement().use {
                        it.executeUpdate("ALTER TABLE venue_public_addresses RENAME TO unavailable_addresses")
                    }
                    try {
                        assertFailsWith<SQLException> { seedStagingVenue(connection, parameters, seedSql()) }
                        connection.createStatement().use { statement ->
                            for (table in listOf("venues", "users", "venue_members", "menu_items", "venue_tables")) {
                                statement.executeQuery("SELECT COUNT(*) FROM $table").use { rows ->
                                    assertTrue(rows.next())
                                    assertEquals(0, rows.getInt(1), table)
                                }
                            }
                        }
                        assertTrue(connection.autoCommit)
                    } finally {
                        connection.createStatement().use {
                            it.executeUpdate("ALTER TABLE unavailable_addresses RENAME TO venue_public_addresses")
                        }
                    }
                }
            }
        }

    private fun seedSql(): String {
        val script =
            sequenceOf(Path.of("scripts/seed-staging.sh"), Path.of("../../scripts/seed-staging.sh"))
                .first(Files::isRegularFile)
        return Files.readString(script).substringAfter("<<'SQL'\n").substringBefore("\nSQL")
    }

    private fun addresses(ds: DataSource): Map<String, Long> =
        ds.connection.use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery("SELECT slug, venue_id FROM venue_public_addresses ORDER BY slug").use { rows ->
                    buildMap { while (rows.next()) put(rows.getString(1), rows.getLong(2)) }
                }
            }
        }

    private fun venueState(
        ds: DataSource,
        id: Long,
    ): Map<String, String?> =
        ds.connection.use { connection ->
            connection.prepareStatement(
                "SELECT name, city, address, status, public_slug FROM venues WHERE id = ?",
            ).use {
                it.setLong(1, id)
                it.executeQuery().use { rows ->
                    check(rows.next())
                    listOf("name", "city", "address", "status", "public_slug").associateWith(rows::getString)
                }
            }
        }

    private suspend fun withPostgres(block: suspend (HikariDataSource) -> Unit) {
        assumeTrue(DockerClientFactory.instance().isDockerAvailable, "Docker is required for the staging seed test")
        PostgreSQLContainer<Nothing>(DockerImageName.parse("postgres:17.11-alpine")).use { postgres ->
            postgres.start()
            HikariDataSource(
                HikariConfig().apply {
                    jdbcUrl = postgres.jdbcUrl
                    username = postgres.username
                    password = postgres.password
                    maximumPoolSize = 3
                },
            ).use { ds ->
                flyway(ds).migrate()
                block(ds)
            }
        }
    }
}
