package com.hookah.platform.backend.platform

import com.hookah.platform.backend.miniapp.venue.VenueStatus
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import kotlinx.coroutines.runBlocking
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import java.sql.Connection
import java.sql.Statement
import java.time.LocalDate
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class PlatformSubscriptionSettingsRepositoryTest {
    companion object {
        private lateinit var dataSource: HikariDataSource

        @BeforeAll
        @JvmStatic
        fun setup() {
            val dbName = "platform_subscription_settings_${UUID.randomUUID()}"
            dataSource =
                HikariDataSource(
                    HikariConfig().apply {
                        driverClassName = "org.h2.Driver"
                        jdbcUrl =
                            "jdbc:h2:mem:$dbName;MODE=PostgreSQL;" +
                                "DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1"
                        maximumPoolSize = 3
                    },
                )
            Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration/h2")
                .load()
                .migrate()
        }

        @AfterAll
        @JvmStatic
        fun tearDown() {
            dataSource.close()
        }

        private fun insertVenue(connection: Connection): Long =
            connection.prepareStatement(
                """
                INSERT INTO venues (name, city, status)
                VALUES ('Commercial Terms Venue', 'Москва', ?)
                """.trimIndent(),
                Statement.RETURN_GENERATED_KEYS,
            ).use { statement ->
                statement.setString(1, VenueStatus.DRAFT.dbValue)
                statement.executeUpdate()
                statement.generatedKeys.use { rs ->
                    rs.next()
                    rs.getLong(1)
                }
            }
    }

    @Test
    fun `apply commercial terms persists trial monthly price and future schedule idempotently`() =
        runBlocking {
            val venueId = dataSource.connection.use { connection -> insertVenue(connection) }
            val repository = PlatformSubscriptionSettingsRepository(dataSource)
            val trialEndDate = LocalDate.parse("2026-08-31")
            val futurePrice =
                PlatformPriceScheduleItem(
                    venueId = venueId,
                    effectiveFrom = LocalDate.parse("2026-09-01"),
                    priceMinor = 1_500_000,
                    currency = PlatformSubscriptionSettingsRepository.DEFAULT_CURRENCY,
                )

            repeat(2) {
                val snapshot =
                    repository.applyCommercialTerms(
                        venueId = venueId,
                        trialEndDate = trialEndDate,
                        basePriceMinor = 1_000_000,
                        futurePrice = futurePrice,
                        actorUserId = 999L,
                    )

                assertNotNull(snapshot)
                assertEquals(trialEndDate, snapshot.settings.trialEndDate)
                assertEquals(trialEndDate, snapshot.settings.paidStartDate)
                assertEquals(1_000_000, snapshot.settings.basePriceMinor)
                assertEquals(listOf(futurePrice), snapshot.schedule)
            }

            dataSource.connection.use { connection ->
                connection.prepareStatement(
                    """
                    SELECT status, trial_end, paid_start
                    FROM venue_subscriptions
                    WHERE venue_id = ?
                    """.trimIndent(),
                ).use { statement ->
                    statement.setLong(1, venueId)
                    statement.executeQuery().use { rs ->
                        rs.next()
                        assertEquals("TRIAL", rs.getString("status"))
                        assertEquals(trialEndDate, rs.getTimestamp("trial_end").toLocalDateTime().toLocalDate())
                        assertEquals(trialEndDate, rs.getTimestamp("paid_start").toLocalDateTime().toLocalDate())
                    }
                }
                connection.prepareStatement(
                    """
                    SELECT COUNT(*)
                    FROM venue_price_schedule
                    WHERE venue_id = ?
                    """.trimIndent(),
                ).use { statement ->
                    statement.setLong(1, venueId)
                    statement.executeQuery().use { rs ->
                        rs.next()
                        assertEquals(1, rs.getInt(1))
                    }
                }
            }
        }

    @Test
    fun `apply commercial terms with zero trial stores active subscription without trial end`() =
        runBlocking {
            val venueId = dataSource.connection.use { connection -> insertVenue(connection) }
            val repository = PlatformSubscriptionSettingsRepository(dataSource)

            val snapshot =
                repository.applyCommercialTerms(
                    venueId = venueId,
                    trialEndDate = null,
                    basePriceMinor = 1_000_000,
                    futurePrice = null,
                    actorUserId = 999L,
                )

            assertNotNull(snapshot)
            assertNull(snapshot.settings.trialEndDate)
            assertEquals(1_000_000, snapshot.settings.basePriceMinor)
            assertEquals(emptyList(), snapshot.schedule)

            dataSource.connection.use { connection ->
                connection.prepareStatement(
                    """
                    SELECT status, trial_end, paid_start
                    FROM venue_subscriptions
                    WHERE venue_id = ?
                    """.trimIndent(),
                ).use { statement ->
                    statement.setLong(1, venueId)
                    statement.executeQuery().use { rs ->
                        rs.next()
                        assertEquals("ACTIVE", rs.getString("status"))
                        assertNull(rs.getTimestamp("trial_end"))
                        assertNotNull(rs.getTimestamp("paid_start"))
                    }
                }
            }
            Unit
        }
}
