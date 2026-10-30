package com.hookah.platform.backend.miniapp.venue.media

import com.hookah.platform.backend.test.PostgresTestEnv
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class VenueInfoMediaMigrationPostgresTest {
    @Test fun `V128 preserves exact legacy references ids order visibility and old migration checksums`() {
        val database = PostgresTestEnv.createDatabase()
        PostgresTestEnv.createDataSource(database, false).use { ds ->
            val before =
                Flyway.configure().dataSource(
                    ds,
                ).locations("classpath:db/migration/postgresql").target("127").load()
            before.migrate()
            ds.connection.use { assertEquals("PostgreSQL", it.metaData.databaseProductName) }
            ds.connection.use { c ->
                c.createStatement().use { s ->
                    s.execute(
                        """
                        INSERT INTO venues(id,name,city,address,status)
                        OVERRIDING SYSTEM VALUE
                        VALUES (700,'Synthetic','Test','Test','DRAFT')
                        """,
                    )
                    s.execute(
                        """
                        INSERT INTO venue_info_sections(id,venue_id,title,section_type,sort_order,is_visible)
                        OVERRIDING SYSTEM VALUE
                        VALUES (701,700,'Rules','rules',70,FALSE)
                        """,
                    )
                    s.execute(
                        """
                        INSERT INTO venue_info_section_media(id,section_id,media_type,telegram_file_id,sort_order)
                        OVERRIDING SYSTEM VALUE
                        VALUES (702,701,'pdf','legacy-exact-File_ID_001',90)
                        """,
                    )
                }
            }
            val after = Flyway.configure().dataSource(ds).locations("classpath:db/migration/postgresql").load()
            assertEquals(1, after.migrate().migrationsExecuted)
            assertTrue(after.validateWithResult().validationSuccessful)
            ds.connection.use { c ->
                c.createStatement().use { s ->
                    s.executeQuery(
                        """
                        SELECT m.*,s.is_visible AS section_visible
                        FROM venue_info_section_media m
                        JOIN venue_info_sections s ON s.id=m.section_id
                        WHERE m.id=702
                        """,
                    ).use {
                            r ->
                        assertTrue(r.next())
                        assertEquals("legacy-exact-File_ID_001", r.getString("telegram_file_id"))
                        assertEquals(90, r.getInt("sort_order"))
                        assertFalse(r.getBoolean("section_visible"))
                        assertTrue(r.getBoolean("is_visible"))
                        assertEquals("READY", r.getString("status"))
                        assertEquals("TELEGRAM_FILE", r.getString("source_type"))
                        assertNull(r.getString("public_id"))
                        assertNull(r.getString("object_key"))
                    }
                    s.executeQuery("SELECT COUNT(*) FROM venue_info_media_cleanup").use {
                        it.next()
                        assertEquals(0, it.getInt(1))
                    }
                }
            }
            assertEquals("128", after.info().current().version.version)
        }
    }
}
