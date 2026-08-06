package com.hookah.platform.backend.miniapp.venue

import org.flywaydb.core.Flyway
import org.h2.jdbcx.JdbcDataSource
import java.sql.Connection
import java.sql.SQLException
import java.sql.Statement
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class VenueStaffModuleSettingsMigrationTest {
    @Test
    fun `H2 migration backfills defaults preserves existing settings and enforces source constraint`() {
        val jdbcUrl =
            "jdbc:h2:mem:staff-module-migration-${UUID.randomUUID()};MODE=PostgreSQL;" +
                "DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1"
        val dataSource = dataSource(jdbcUrl)
        val beforeFlyway = flyway(dataSource, target = "121")
        beforeFlyway.migrate()
        assertEquals("121", beforeFlyway.info().current().version.version)

        val originalUpdatedAt = Instant.parse("2026-07-15T12:34:56.789Z")
        val existingVenueId =
            dataSource.connection.use { connection ->
                val venueId = insertVenue(connection, "Existing venue")
                connection.prepareStatement(
                    """
                    INSERT INTO venue_settings (
                        venue_id,
                        notify_orders_enabled,
                        notify_staff_calls_enabled,
                        notify_cancellations_enabled,
                        timezone,
                        public_review_url,
                        updated_at
                    )
                    VALUES (?, FALSE, TRUE, FALSE, 'Asia/Tomsk', 'https://example.com/reviews', ?)
                    """.trimIndent(),
                ).use { statement ->
                    statement.setLong(1, venueId)
                    statement.setTimestamp(2, Timestamp.from(originalUpdatedAt))
                    statement.executeUpdate()
                }
                assertEquals(1, countSettingsRows(connection))
                venueId
            }

        val migrationFlyway = flyway(dataSource, target = "122")
        val migrationResult = migrationFlyway.migrate()
        assertEquals(1, migrationResult.migrationsExecuted)
        assertEquals("122", migrationFlyway.info().current().version.version)

        dataSource.connection.use { connection ->
            connection.prepareStatement(
                """
                SELECT notify_orders_enabled,
                       notify_staff_calls_enabled,
                       notify_cancellations_enabled,
                       timezone,
                       public_review_url,
                       team_schedule_module_enabled,
                       guest_team_visible,
                       today_staff_source,
                       updated_at
                FROM venue_settings
                WHERE venue_id = ?
                """.trimIndent(),
            ).use { statement ->
                statement.setLong(1, existingVenueId)
                statement.executeQuery().use { resultSet ->
                    assertTrue(resultSet.next())
                    assertFalse(resultSet.getBoolean("notify_orders_enabled"))
                    assertTrue(resultSet.getBoolean("notify_staff_calls_enabled"))
                    assertFalse(resultSet.getBoolean("notify_cancellations_enabled"))
                    assertEquals("Asia/Tomsk", resultSet.getString("timezone"))
                    assertEquals("https://example.com/reviews", resultSet.getString("public_review_url"))
                    assertTrue(resultSet.getBoolean("team_schedule_module_enabled"))
                    assertTrue(resultSet.getBoolean("guest_team_visible"))
                    assertEquals("MANUAL", resultSet.getString("today_staff_source"))
                    assertEquals(originalUpdatedAt, resultSet.getTimestamp("updated_at").toInstant())
                }
            }

            val lazyVenueId = insertVenue(connection, "Lazy venue")
            connection.prepareStatement(
                """
                INSERT INTO venue_settings (
                    venue_id,
                    notify_orders_enabled,
                    notify_staff_calls_enabled,
                    notify_cancellations_enabled,
                    timezone
                )
                VALUES (?, TRUE, TRUE, TRUE, 'Europe/Moscow')
                """.trimIndent(),
            ).use { statement ->
                statement.setLong(1, lazyVenueId)
                statement.executeUpdate()
            }
            assertDefaultModuleSettings(connection, lazyVenueId)
            assertEquals(2, countSettingsRows(connection))

            assertFailsWith<SQLException> {
                connection.prepareStatement(
                    "UPDATE venue_settings SET today_staff_source = 'UNKNOWN' WHERE venue_id = ?",
                ).use { statement ->
                    statement.setLong(1, lazyVenueId)
                    statement.executeUpdate()
                }
            }
            assertFailsWith<SQLException> {
                connection.prepareStatement(
                    "UPDATE venue_settings SET today_staff_source = NULL WHERE venue_id = ?",
                ).use { statement ->
                    statement.setLong(1, lazyVenueId)
                    statement.executeUpdate()
                }
            }
            assertDefaultModuleSettings(connection, lazyVenueId)
        }
    }

    private fun flyway(
        dataSource: JdbcDataSource,
        target: String? = null,
    ): Flyway {
        val configuration =
            Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration/h2")
        if (target != null) {
            configuration.target(target)
        }
        return configuration.load()
    }

    private fun dataSource(jdbcUrl: String): JdbcDataSource =
        JdbcDataSource().apply {
            setURL(jdbcUrl)
            user = "sa"
            password = ""
        }

    private fun insertVenue(
        connection: Connection,
        name: String,
    ): Long =
        connection.prepareStatement(
            "INSERT INTO venues (name, status) VALUES (?, 'PUBLISHED')",
            Statement.RETURN_GENERATED_KEYS,
        ).use { statement ->
            statement.setString(1, name)
            statement.executeUpdate()
            statement.generatedKeys.use { resultSet ->
                check(resultSet.next())
                resultSet.getLong(1)
            }
        }

    private fun assertDefaultModuleSettings(
        connection: Connection,
        venueId: Long,
    ) {
        connection.prepareStatement(
            """
            SELECT team_schedule_module_enabled,
                   guest_team_visible,
                   today_staff_source
            FROM venue_settings
            WHERE venue_id = ?
            """.trimIndent(),
        ).use { statement ->
            statement.setLong(1, venueId)
            statement.executeQuery().use { resultSet ->
                assertTrue(resultSet.next())
                assertTrue(resultSet.getBoolean("team_schedule_module_enabled"))
                assertTrue(resultSet.getBoolean("guest_team_visible"))
                assertEquals("MANUAL", resultSet.getString("today_staff_source"))
            }
        }
    }

    private fun countSettingsRows(connection: Connection): Int =
        connection.createStatement().use { statement ->
            statement.executeQuery("SELECT COUNT(*) FROM venue_settings").use { resultSet ->
                resultSet.next()
                resultSet.getInt(1)
            }
        }
}
