package com.hookah.platform.backend.platform

import com.hookah.platform.backend.api.DatabaseUnavailableException
import java.sql.SQLException
import java.time.Instant
import java.util.Locale
import javax.sql.DataSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class PlatformUserRepository(private val dataSource: DataSource?) {
    suspend fun listUsers(query: String?, limit: Int): List<PlatformTelegramUser> {
        val ds = dataSource ?: throw DatabaseUnavailableException()
        return withContext(Dispatchers.IO) {
            try {
                ds.connection.use { connection ->
                    val sql = StringBuilder(
                        """
                            SELECT telegram_user_id, username, first_name, last_name, updated_at
                            FROM users
                        """.trimIndent()
                    )
                    val params = mutableListOf<Any>()
                    val search = query?.trim()?.lowercase(Locale.ROOT)?.takeIf { it.isNotBlank() }
                    if (search != null) {
                        sql.append(
                            """
                                WHERE LOWER(username) LIKE ?
                                   OR LOWER(first_name) LIKE ?
                                   OR LOWER(last_name) LIKE ?
                            """.trimIndent()
                        )
                        val like = "%$search%"
                        params.add(like)
                        params.add(like)
                        params.add(like)
                    }
                    sql.append(" ORDER BY updated_at DESC, telegram_user_id DESC LIMIT ?")
                    params.add(limit)

                    connection.prepareStatement(sql.toString()).use { statement ->
                        params.forEachIndexed { index, value ->
                            when (value) {
                                is String -> statement.setString(index + 1, value)
                                is Int -> statement.setInt(index + 1, value)
                                is Long -> statement.setLong(index + 1, value)
                                else -> statement.setObject(index + 1, value)
                            }
                        }
                        statement.executeQuery().use { rs ->
                            val result = mutableListOf<PlatformTelegramUser>()
                            while (rs.next()) {
                                result.add(
                                    PlatformTelegramUser(
                                        userId = rs.getLong("telegram_user_id"),
                                        username = rs.getString("username"),
                                        firstName = rs.getString("first_name"),
                                        lastName = rs.getString("last_name"),
                                        lastSeenAt = rs.getTimestamp("updated_at").toInstant()
                                    )
                                )
                            }
                            result
                        }
                    }
                }
            } catch (e: SQLException) {
                throw DatabaseUnavailableException()
            }
        }
    }
}

data class PlatformTelegramUser(
    val userId: Long,
    val username: String?,
    val firstName: String?,
    val lastName: String?,
    val lastSeenAt: Instant
)
