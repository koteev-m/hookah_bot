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
