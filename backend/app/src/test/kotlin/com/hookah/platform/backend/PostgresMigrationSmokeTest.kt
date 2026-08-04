package com.hookah.platform.backend

import com.hookah.platform.backend.telegram.db.VenueSettingsRepository
import com.hookah.platform.backend.test.PostgresTestEnv
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.runBlocking
import org.flywaydb.core.Flyway
import java.sql.Connection
import java.sql.DriverManager
import java.sql.SQLException
import java.sql.Statement
import java.sql.Timestamp
import java.time.Instant
import javax.sql.DataSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PostgresMigrationSmokeTest {
    @Test
    fun `health endpoint works and billing tables exist on postgres`() =
        testApplication {
            val database = PostgresTestEnv.createDatabase()
            environment {
                config = PostgresTestEnv.buildConfig(database)
            }
            application { module() }

            val response = client.get("/health")
            assertEquals(HttpStatusCode.OK, response.status)
            assertTrue(response.bodyAsText().contains("ok"))

            DriverManager.getConnection(database.jdbcUrl, database.user, database.password).use { connection ->
                connection.prepareStatement("SELECT 1 FROM billing_invoices LIMIT 1").use { statement ->
                    statement.executeQuery().use { resultSet ->
                        resultSet.next()
                    }
                }
                connection.prepareStatement("SELECT 1 FROM billing_payments LIMIT 1").use { statement ->
                    statement.executeQuery().use { resultSet ->
                        resultSet.next()
                    }
                }
                connection.prepareStatement("SELECT 1 FROM billing_adjustments LIMIT 1").use { statement ->
                    statement.executeQuery().use { resultSet ->
                        resultSet.next()
                    }
                }
            }
        }

    @Test
    fun `staff module settings migration backfills defaults and preserves existing postgres row`() {
        val database = PostgresTestEnv.createDatabase()
        PostgresTestEnv.createDataSource(database, migrate = false).use { dataSource ->
            val beforeFlyway = postgresFlyway(dataSource, target = "120")
            beforeFlyway.migrate()
            assertEquals("120", beforeFlyway.info().current().version.version)

            val originalUpdatedAt = Instant.parse("2026-07-15T12:34:56.789Z")
            val existingVenueId =
                dataSource.connection.use { connection ->
                    val venueId = insertVenue(connection, "Existing PostgreSQL venue")
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

            val migrationResult = postgresFlyway(dataSource).migrate()
            assertEquals(1, migrationResult.migrationsExecuted)
            assertEquals("121", postgresFlyway(dataSource).info().current().version.version)

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

                val lazyVenueId = insertVenue(connection, "Lazy PostgreSQL venue")
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

                val futureCasToken = Instant.parse("2035-01-01T00:00:00Z")
                connection.prepareStatement(
                    "UPDATE venue_settings SET updated_at = ? WHERE venue_id = ?",
                ).use { statement ->
                    statement.setTimestamp(1, Timestamp.from(futureCasToken))
                    statement.setLong(2, lazyVenueId)
                    statement.executeUpdate()
                }
                runBlocking {
                    VenueSettingsRepository(dataSource).updateTimezone(
                        venueId = lazyVenueId,
                        timezone = "Asia/Tomsk",
                        fallbackTimezone = "Europe/Moscow",
                    )
                }
                assertTrue(loadSettingsUpdatedAt(connection, lazyVenueId).isAfter(futureCasToken))
            }
        }
    }

    private fun postgresFlyway(
        dataSource: DataSource,
        target: String? = null,
    ): Flyway {
        val configuration =
            Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration/postgresql")
        if (target != null) {
            configuration.target(target)
        }
        return configuration.load()
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

    private fun loadSettingsUpdatedAt(
        connection: Connection,
        venueId: Long,
    ): Instant =
        connection.prepareStatement("SELECT updated_at FROM venue_settings WHERE venue_id = ?").use { statement ->
            statement.setLong(1, venueId)
            statement.executeQuery().use { resultSet ->
                check(resultSet.next())
                resultSet.getTimestamp("updated_at").toInstant()
            }
        }
}
