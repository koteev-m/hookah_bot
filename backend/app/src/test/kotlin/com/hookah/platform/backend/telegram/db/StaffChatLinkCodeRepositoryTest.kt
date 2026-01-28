package com.hookah.platform.backend.telegram.db

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import java.time.Instant
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.runBlocking
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class StaffChatLinkCodeRepositoryTest {
    companion object {
        private lateinit var dataSource: HikariDataSource

        @BeforeAll
        @JvmStatic
        fun setup() {
            val dbName = "staff_link_${UUID.randomUUID()}"
            dataSource = HikariDataSource(
                HikariConfig().apply {
                    driverClassName = "org.h2.Driver"
                    jdbcUrl = "jdbc:h2:mem:$dbName;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1"
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
    }

    @Test
    fun `link code is single use`() = runBlocking {
        val nowRef = AtomicReference(Instant.parse("2024-01-01T00:00:00Z"))
        val repository = StaffChatLinkCodeRepository(
            dataSource = dataSource,
            pepper = "pepper",
            ttlSeconds = 300,
            now = { nowRef.get() }
        )
        val (venueId, userId) = seedVenueAndUser()
        val created = repository.createLinkCode(venueId, userId)!!

        val first = repository.linkAndBindWithCode(
            code = created.code,
            usedByUserId = userId,
            chatId = 999L,
            messageId = null,
            authorize = { _, _ -> true },
            bind = { _, _ -> BindResult.Success(venueId, "Venue") }
        )

        val second = repository.linkAndBindWithCode(
            code = created.code,
            usedByUserId = userId,
            chatId = 999L,
            messageId = null,
            authorize = { _, _ -> true },
            bind = { _, _ -> BindResult.Success(venueId, "Venue") }
        )

        assertTrue(first is LinkAndBindResult.Success)
        assertEquals(LinkAndBindResult.InvalidOrExpired, second)
    }

    @Test
    fun `expired link code is rejected`() = runBlocking {
        val nowRef = AtomicReference(Instant.parse("2024-01-01T00:00:00Z"))
        val repository = StaffChatLinkCodeRepository(
            dataSource = dataSource,
            pepper = "pepper",
            ttlSeconds = 10,
            now = { nowRef.get() }
        )
        val (venueId, userId) = seedVenueAndUser()
        val created = repository.createLinkCode(venueId, userId)!!
        nowRef.set(nowRef.get().plusSeconds(20))

        val result = repository.linkAndBindWithCode(
            code = created.code,
            usedByUserId = userId,
            chatId = 1000L,
            messageId = null,
            authorize = { _, _ -> true },
            bind = { _, _ -> BindResult.Success(venueId, "Venue") }
        )

        assertEquals(LinkAndBindResult.InvalidOrExpired, result)
    }

    private fun seedVenueAndUser(): Pair<Long, Long> {
        val userId = 9009L
        val venueId = dataSource.connection.use { connection ->
            connection.prepareStatement(
                """
                    MERGE INTO users (telegram_user_id, username, first_name, last_name)
                    KEY (telegram_user_id)
                    VALUES (?, 'user', 'Test', 'User')
                """.trimIndent()
            ).use { statement ->
                statement.setLong(1, userId)
                statement.executeUpdate()
            }
            connection.prepareStatement(
                """
                    INSERT INTO venues (name, city, address, status)
                    VALUES ('Venue', 'City', 'Address', 'active_published')
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
        return venueId to userId
    }
}
