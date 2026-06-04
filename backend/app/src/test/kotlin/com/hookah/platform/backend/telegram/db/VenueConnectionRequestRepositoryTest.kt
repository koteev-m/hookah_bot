package com.hookah.platform.backend.telegram.db

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class VenueConnectionRequestRepositoryTest {
    @Test
    fun `closed approved unlinked request no longer blocks new applicant request`(): Unit =
        runBlocking {
            val dataSource = testDataSource()
            try {
                val repository = VenueConnectionRequestRepository(dataSource)
                val telegramUserId = 7011L

                val firstRequestId =
                    assertNotNull(
                        repository.createRequest(
                            telegramUserId = telegramUserId,
                            venueName = "Old Smoke",
                            city = "Москва",
                            contact = "@owner",
                            comment = null,
                        ),
                    )

                assertTrue(repository.setStatusByOwner(firstRequestId, VenueConnectionRequestRepository.STATUS_APPROVED))
                assertEquals(
                    firstRequestId,
                    repository.findActiveUnlinkedByUser(telegramUserId)?.id,
                )

                assertTrue(repository.closeApprovedUnlinkedByOwner(firstRequestId))
                assertNull(repository.findActiveUnlinkedByUser(telegramUserId))

                val secondRequestId =
                    assertNotNull(
                        repository.createRequest(
                            telegramUserId = telegramUserId,
                            venueName = "New Smoke",
                            city = "Москва",
                            contact = "@owner",
                            comment = "new request",
                        ),
                    )

                assertNotEquals(firstRequestId, secondRequestId)
                assertEquals(
                    secondRequestId,
                    repository.findActiveUnlinkedByUser(telegramUserId)?.id,
                )
            } finally {
                dataSource.close()
            }
        }

    private fun testDataSource(): HikariDataSource {
        val dbName = "venue_connection_requests_${UUID.randomUUID()}"
        val dataSource =
            HikariDataSource(
                HikariConfig().apply {
                    driverClassName = "org.h2.Driver"
                    jdbcUrl =
                        "jdbc:h2:mem:$dbName;MODE=PostgreSQL;" +
                            "DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1"
                    maximumPoolSize = 3
                },
            )
        dataSource.connection.use { connection ->
            connection.createStatement().use { statement ->
                statement.execute(
                    """
                    CREATE TABLE venue_connection_requests (
                        id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
                        telegram_user_id BIGINT NOT NULL,
                        venue_name VARCHAR(255) NOT NULL,
                        city VARCHAR(120) NOT NULL,
                        contact VARCHAR(200) NOT NULL,
                        comment VARCHAR(500) NULL,
                        status VARCHAR(32) NOT NULL DEFAULT 'PENDING',
                        created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
                        linked_venue_id BIGINT NULL,
                        trial_configured BOOLEAN NOT NULL DEFAULT FALSE,
                        trial_ends_on DATE NULL,
                        current_price_rub BIGINT NULL,
                        future_price_rub BIGINT NULL,
                        future_price_effective_on DATE NULL,
                        commercial_note VARCHAR(1000) NULL
                    )
                    """.trimIndent(),
                )
            }
        }
        return dataSource
    }
}
