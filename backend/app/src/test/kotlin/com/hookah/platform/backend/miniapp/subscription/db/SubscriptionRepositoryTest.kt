package com.hookah.platform.backend.miniapp.subscription.db

import com.hookah.platform.backend.miniapp.subscription.SubscriptionStatus
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import kotlinx.coroutines.runBlocking
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import java.sql.Connection

class SubscriptionRepositoryTest {
    companion object {
        private lateinit var dataSource: HikariDataSource

        @BeforeAll
        @JvmStatic
        fun setup() {
            dataSource = HikariDataSource(
                HikariConfig().apply {
                    driverClassName = "org.h2.Driver"
                    jdbcUrl = "jdbc:h2:mem:subscription_repo;MODE=PostgreSQL;DATABASE_TO_UPPER=false;DB_CLOSE_DELAY=-1"
                    maximumPoolSize = 3
                }
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

        private fun insertVenue(connection: Connection): Long {
            return connection.prepareStatement(
                """
                    INSERT INTO venues (name, status)
                    VALUES ('Test Venue', 'active_published')
                """.trimIndent(),
                java.sql.Statement.RETURN_GENERATED_KEYS
            ).use { statement ->
                statement.executeUpdate()
                statement.generatedKeys.use { rs ->
                    rs.next()
                    rs.getLong(1)
                }
            }
        }
    }

    @Test
    fun `getSubscriptionStatus backfills trial subscription`() = runBlocking {
        val venueId = dataSource.connection.use { connection ->
            insertVenue(connection)
        }

        val repository = SubscriptionRepository(dataSource)
        val status = repository.getSubscriptionStatus(venueId)

        assertEquals(SubscriptionStatus.TRIAL, status)

        dataSource.connection.use { connection ->
            connection.prepareStatement(
                """
                    SELECT COUNT(*)
                    FROM venue_subscriptions
                    WHERE venue_id = ?
                """.trimIndent()
            ).use { statement ->
                statement.setLong(1, venueId)
                statement.executeQuery().use { rs ->
                    rs.next()
                    assertTrue(rs.getInt(1) == 1)
                }
            }
        }
    }
}
