package com.hookah.platform.backend.telegram.db

import com.hookah.platform.backend.telegram.User
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.sql.DataSource

class UserRepository(private val dataSource: DataSource?) {
    suspend fun upsert(user: User): Long? {
        val ds = dataSource ?: return null
        return withContext(Dispatchers.IO) {
            ds.connection.use { connection ->
                if (isH2(connection.metaData.databaseProductName)) {
                    val sql = """
                        MERGE INTO users (telegram_user_id, username, first_name, last_name, updated_at)
                        KEY (telegram_user_id)
                        VALUES (?, ?, ?, ?, CURRENT_TIMESTAMP)
                    """.trimIndent()
                    connection.prepareStatement(sql).use { statement ->
                        statement.setLong(1, user.id)
                        statement.setString(2, user.username)
                        statement.setString(3, user.firstName)
                        statement.setString(4, user.lastName)
                        statement.executeUpdate()
                        user.id
                    }
                } else {
                    val sql = """
                        INSERT INTO users (telegram_user_id, username, first_name, last_name, updated_at)
                        VALUES (?, ?, ?, ?, now())
                        ON CONFLICT (telegram_user_id) DO UPDATE SET
                            username = EXCLUDED.username,
                            first_name = EXCLUDED.first_name,
                            last_name = EXCLUDED.last_name,
                            updated_at = now()
                        RETURNING telegram_user_id
                    """.trimIndent()
                    connection.prepareStatement(sql).use { statement ->
                        statement.setLong(1, user.id)
                        statement.setString(2, user.username)
                        statement.setString(3, user.firstName)
                        statement.setString(4, user.lastName)
                        statement.executeQuery().use { rs ->
                            if (rs.next()) rs.getLong(1) else null
                        }
                    }
                }
            }
        }
    }
}

private fun isH2(databaseProductName: String?): Boolean =
    databaseProductName?.equals("H2", ignoreCase = true) == true
