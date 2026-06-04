package com.hookah.platform.backend.telegram.db

import com.hookah.platform.backend.telegram.User
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.sql.ResultSet
import java.sql.Timestamp
import java.time.Instant
import javax.sql.DataSource

data class GuestProfile(
    val telegramUserId: Long,
    val guestDisplayName: String?,
    val birthdayMonth: Int?,
    val birthdayDay: Int?,
    val birthdaySetAt: Instant?,
)

data class TelegramUserContact(
    val telegramUserId: Long,
    val username: String?,
    val firstName: String?,
    val lastName: String?,
)

class UserRepository(private val dataSource: DataSource?) {
    suspend fun upsert(user: User): Long? {
        val ds = dataSource ?: return null
        return withContext(Dispatchers.IO) {
            ds.connection.use { connection ->
                if (isH2(connection.metaData.databaseProductName)) {
                    val sql =
                        """
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
                    val sql =
                        """
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

    suspend fun findGuestProfile(telegramUserId: Long): GuestProfile? {
        val ds = dataSource ?: return null
        return withContext(Dispatchers.IO) {
            ds.connection.use { connection ->
                val sql =
                    """
                    SELECT telegram_user_id, guest_display_name, birthday_month, birthday_day, birthday_set_at
                    FROM users
                    WHERE telegram_user_id = ?
                    """.trimIndent()
                connection.prepareStatement(sql).use { statement ->
                    statement.setLong(1, telegramUserId)
                    statement.executeQuery().use { rs ->
                        if (rs.next()) rs.toGuestProfile() else null
                    }
                }
            }
        }
    }

    suspend fun findTelegramUserContact(telegramUserId: Long): TelegramUserContact? {
        val ds = dataSource ?: return null
        return withContext(Dispatchers.IO) {
            ds.connection.use { connection ->
                connection.prepareStatement(
                    """
                    SELECT telegram_user_id, username, first_name, last_name
                    FROM users
                    WHERE telegram_user_id = ?
                    """.trimIndent(),
                ).use { statement ->
                    statement.setLong(1, telegramUserId)
                    statement.executeQuery().use { rs ->
                        if (rs.next()) {
                            TelegramUserContact(
                                telegramUserId = rs.getLong("telegram_user_id"),
                                username = rs.getString("username"),
                                firstName = rs.getString("first_name"),
                                lastName = rs.getString("last_name"),
                            )
                        } else {
                            null
                        }
                    }
                }
            }
        }
    }

    suspend fun saveGuestDisplayName(
        telegramUserId: Long,
        displayName: String,
    ): GuestProfile? {
        val ds = dataSource ?: return null
        val normalized = normalizeGuestDisplayName(displayName)
        return withContext(Dispatchers.IO) {
            ds.connection.use { connection ->
                val sql =
                    """
                    UPDATE users
                    SET guest_display_name = ?, updated_at = CURRENT_TIMESTAMP
                    WHERE telegram_user_id = ?
                    """.trimIndent()
                connection.prepareStatement(sql).use { statement ->
                    statement.setString(1, normalized)
                    statement.setLong(2, telegramUserId)
                    statement.executeUpdate()
                }
            }
            findGuestProfile(telegramUserId)
        }
    }

    suspend fun saveBirthdayOnce(
        telegramUserId: Long,
        month: Int,
        day: Int,
    ): GuestProfile? {
        require(month in 1..12) { "birthday month must be between 1 and 12" }
        require(day in 1..31) { "birthday day must be between 1 and 31" }
        val ds = dataSource ?: return null
        return withContext(Dispatchers.IO) {
            var updated = false
            ds.connection.use { connection ->
                val sql =
                    """
                    UPDATE users
                    SET birthday_month = ?,
                        birthday_day = ?,
                        birthday_set_at = CURRENT_TIMESTAMP,
                        updated_at = CURRENT_TIMESTAMP
                    WHERE telegram_user_id = ?
                        AND birthday_month IS NULL
                        AND birthday_day IS NULL
                    """.trimIndent()
                connection.prepareStatement(sql).use { statement ->
                    statement.setInt(1, month)
                    statement.setInt(2, day)
                    statement.setLong(3, telegramUserId)
                    updated = statement.executeUpdate() > 0
                }
            }
            if (updated) findGuestProfile(telegramUserId) else null
        }
    }

    companion object {
        const val GUEST_DISPLAY_NAME_MAX_LENGTH = 40

        fun normalizeGuestDisplayName(value: String): String {
            require(!value.contains('\n') && !value.contains('\r')) { "guest display name must be single-line" }
            val normalized = value.trim().replace(Regex("[\\t ]+"), " ")
            require(normalized.isNotBlank()) { "guest display name must not be blank" }
            require(normalized.length <= GUEST_DISPLAY_NAME_MAX_LENGTH) {
                "guest display name length must be <= $GUEST_DISPLAY_NAME_MAX_LENGTH"
            }
            return normalized
        }
    }
}

private fun ResultSet.toGuestProfile(): GuestProfile =
    GuestProfile(
        telegramUserId = getLong("telegram_user_id"),
        guestDisplayName = getString("guest_display_name"),
        birthdayMonth = getNullableInt("birthday_month"),
        birthdayDay = getNullableInt("birthday_day"),
        birthdaySetAt = getTimestamp("birthday_set_at")?.toInstantCompat(),
    )

private fun ResultSet.getNullableInt(column: String): Int? {
    val value = getInt(column)
    return if (wasNull()) null else value
}

private fun Timestamp.toInstantCompat(): Instant = toInstant()

private fun isH2(databaseProductName: String?): Boolean = databaseProductName?.equals("H2", ignoreCase = true) == true
