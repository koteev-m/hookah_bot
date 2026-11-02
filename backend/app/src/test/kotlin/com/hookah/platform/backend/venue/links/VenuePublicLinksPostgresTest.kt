package com.hookah.platform.backend.venue.links

import com.hookah.platform.backend.platform.PlatformVenueRepository
import com.hookah.platform.backend.test.PostgresTestEnv
import kotlinx.coroutines.runBlocking
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class VenuePublicLinksPostgresTest {
    @Test
    fun `postgres migration backfills legacy rows and validates migration checksums`() {
        val database = PostgresTestEnv.createDatabase()
        PostgresTestEnv.createDataSource(database, migrate = false).use { ds ->
            flyway(ds, "128").migrate()
            val a = insertVenue(ds, "Одинаковое", "Москва", "Арбат, 1", "PUBLISHED")
            val b = insertVenue(ds, "Одинаковое", "Казань", "Баумана, 2", "DELETED")
            assertEquals(2, flyway(ds).migrate().migrationsExecuted)
            flyway(ds).validate()
            runBlocking {
                val links = VenuePublicLinksRepository(ds, 999)
                assertEquals(a, links.resolveSlug("odinakovoe"))
                assertEquals(b, links.resolveSlug("odinakovoe-kazan-baumana-2"))
            }
            assertEquals(0, flyway(ds).migrate().migrationsExecuted)
        }
    }

    @Test
    fun `postgres concurrent identical creates both commit with distinct reserved addresses`() {
        val database = PostgresTestEnv.createDatabase()
        PostgresTestEnv.createDataSource(database).use { ds ->
            val pool = Executors.newFixedThreadPool(3)
            val start = CountDownLatch(1)
            try {
                val futures =
                    (1..3).map {
                        pool.submit<Long> {
                            assertTrue(start.await(10, TimeUnit.SECONDS))
                            runBlocking { PlatformVenueRepository(ds).createVenue("Дым", "Москва", "Арбат, 1").id }
                        }
                    }
                start.countDown()
                val ids = futures.map { it.get(30, TimeUnit.SECONDS) }
                assertNotEquals(ids[0], ids[1])
                ds.connection.use { c ->
                    c.createStatement().use { s ->
                        s.executeQuery("SELECT COUNT(DISTINCT public_slug) FROM venues").use { r ->
                            assertTrue(r.next())
                            assertEquals(3, r.getInt(1))
                        }
                        s.executeQuery("SELECT COUNT(*) FROM venue_public_addresses").use { r ->
                            assertTrue(r.next())
                            assertEquals(3, r.getInt(1))
                        }
                    }
                }
            } finally {
                pool.shutdownNow()
            }
        }
    }

    @Test
    fun `postgres aliases permissions deletion reservations and audit rollback`() =
        runBlocking {
            val database = PostgresTestEnv.createDatabase()
            PostgresTestEnv.createDataSource(database).use { ds -> verifyLinks(ds) }
        }
}
