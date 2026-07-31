package com.hookah.platform.backend.miniapp.venue

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.test.assertEquals

class AuditLogRepositoryTest {
    @Test
    fun `append writes payload_json`() =
        runBlocking {
            val dbName = "audit_log_${UUID.randomUUID()}"
            val dataSource =
                HikariDataSource(
                    HikariConfig().apply {
                        driverClassName = "org.h2.Driver"
                        jdbcUrl =
                            "jdbc:h2:mem:$dbName;MODE=PostgreSQL;" +
                            "DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1"
                        maximumPoolSize = 2
                    },
                )
            try {
                Flyway.configure()
                    .dataSource(dataSource)
                    .locations("classpath:db/migration/h2")
                    .load()
                    .migrate()

                val repository = AuditLogRepository(dataSource, Json)
                val payloadJson = """{"key":"value"}"""

                repository.append(
                    actorUserId = 10,
                    action = "TEST_ACTION",
                    entityType = "venue",
                    entityId = 42,
                    payloadJson = payloadJson,
                )

                dataSource.connection.use { connection ->
                    connection.prepareStatement(
                        """
                        SELECT actor_user_id, action, entity_type, entity_id, payload_json
                        FROM audit_log
                        """.trimIndent(),
                    ).use { statement ->
                        statement.executeQuery().use { rs ->
                            rs.next()
                            assertEquals(10L, rs.getLong("actor_user_id"))
                            assertEquals("TEST_ACTION", rs.getString("action"))
                            assertEquals("venue", rs.getString("entity_type"))
                            assertEquals(42L, rs.getLong("entity_id"))
                            assertEquals(payloadJson, rs.getString("payload_json"))
                        }
                    }
                }
            } finally {
                dataSource.close()
            }
        }

    @Test
    fun `connection append participates in caller transaction rollback`() {
        val dbName = "audit_log_transaction_${UUID.randomUUID()}"
        val dataSource =
            HikariDataSource(
                HikariConfig().apply {
                    driverClassName = "org.h2.Driver"
                    jdbcUrl =
                        "jdbc:h2:mem:$dbName;MODE=PostgreSQL;" +
                        "DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1"
                    maximumPoolSize = 2
                },
            )
        try {
            Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration/h2")
                .load()
                .migrate()

            val repository = AuditLogRepository(dataSource, Json)
            dataSource.connection.use { connection ->
                connection.autoCommit = false
                repository.appendJson(
                    connection = connection,
                    actorUserId = 10,
                    action = "TRANSACTION_ACTION",
                    entityType = "venue",
                    entityId = 42,
                    payload = buildJsonObject { put("changedItemCount", 1) },
                )
                connection.rollback()
            }

            dataSource.connection.use { connection ->
                connection.prepareStatement(
                    "SELECT COUNT(*) FROM audit_log WHERE action = 'TRANSACTION_ACTION'",
                ).use { statement ->
                    statement.executeQuery().use { rs ->
                        rs.next()
                        assertEquals(0, rs.getInt(1))
                    }
                }
            }
        } finally {
            dataSource.close()
        }
    }
}
