package com.hookah.platform.backend.ai

import com.hookah.platform.backend.telegram.DialogStateType
import org.flywaydb.core.Flyway
import org.h2.jdbcx.JdbcDataSource
import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.test.assertEquals

class AiDialogStateMigrationTest {
    @Test
    fun `AI draft dialog state is allowed by migrated constraint`() {
        val dataSource = dataSource(migratedJdbcUrl())

        dataSource.connection.use { connection ->
            connection.prepareStatement(
                """
                INSERT INTO telegram_dialog_state (chat_id, state, payload)
                VALUES (?, ?, ?)
                """.trimIndent(),
            ).use { statement ->
                statement.setLong(1, 100L)
                statement.setString(2, DialogStateType.AI_WAIT_PROMOTION_TEXT_BRIEF.name)
                statement.setString(3, """{"venueId":"10","origin":"GENERAL"}""")
                statement.executeUpdate()
            }

            connection.prepareStatement("SELECT state FROM telegram_dialog_state WHERE chat_id = ?").use { statement ->
                statement.setLong(1, 100L)
                statement.executeQuery().use { rs ->
                    rs.next()
                    assertEquals(DialogStateType.AI_WAIT_PROMOTION_TEXT_BRIEF.name, rs.getString("state"))
                }
            }
        }
    }

    private fun migratedJdbcUrl(): String {
        val jdbcUrl =
            "jdbc:h2:mem:ai-dialog-state-${UUID.randomUUID()};MODE=PostgreSQL;" +
                "DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1"
        Flyway
            .configure()
            .dataSource(jdbcUrl, "sa", "")
            .locations("classpath:db/migration/h2")
            .load()
            .migrate()
        return jdbcUrl
    }

    private fun dataSource(jdbcUrl: String): JdbcDataSource =
        JdbcDataSource().apply {
            setURL(jdbcUrl)
            user = "sa"
            password = ""
        }
}
