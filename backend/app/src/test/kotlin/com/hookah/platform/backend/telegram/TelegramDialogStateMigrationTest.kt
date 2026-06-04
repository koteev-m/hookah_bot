package com.hookah.platform.backend.telegram

import org.flywaydb.core.Flyway
import org.h2.jdbcx.JdbcDataSource
import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.test.assertEquals

class TelegramDialogStateMigrationTest {
    @Test
    fun `all Kotlin dialog states are allowed by migrated constraint`() {
        val dataSource = dataSource(migratedJdbcUrl())

        dataSource.connection.use { connection ->
            DialogStateType.entries.forEachIndexed { index, state ->
                val chatId = 10_000L + index
                connection.prepareStatement(
                    """
                    INSERT INTO telegram_dialog_state (chat_id, state, payload)
                    VALUES (?, ?, ?)
                    """.trimIndent(),
                ).use { statement ->
                    statement.setLong(1, chatId)
                    statement.setString(2, state.name)
                    statement.setString(3, """{"state":"${state.name}"}""")
                    statement.executeUpdate()
                }

                connection.prepareStatement("SELECT state FROM telegram_dialog_state WHERE chat_id = ?").use { statement ->
                    statement.setLong(1, chatId)
                    statement.executeQuery().use { rs ->
                        rs.next()
                        assertEquals(state.name, rs.getString("state"))
                    }
                }
            }
        }
    }

    private fun migratedJdbcUrl(): String {
        val jdbcUrl =
            "jdbc:h2:mem:telegram-dialog-state-${UUID.randomUUID()};MODE=PostgreSQL;" +
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
