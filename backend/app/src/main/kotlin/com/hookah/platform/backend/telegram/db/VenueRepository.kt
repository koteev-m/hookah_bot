package com.hookah.platform.backend.telegram.db

import com.hookah.platform.backend.telegram.sanitizeTelegramForLog
import com.hookah.platform.backend.telegram.debugTelegramException
import java.sql.Connection
import java.sql.SQLException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import javax.sql.DataSource

open class VenueRepository(private val dataSource: DataSource?) {
    private val logger = LoggerFactory.getLogger(VenueRepository::class.java)

    suspend fun findVenueById(venueId: Long): VenueShort? {
        val ds = dataSource ?: return null
        return withContext(Dispatchers.IO) {
            ds.connection.use { connection ->
                connection.prepareStatement(
                    """
                        SELECT id, name, staff_chat_id
                        FROM venues
                        WHERE id = ?
                    """.trimIndent()
                ).use { statement ->
                    statement.setLong(1, venueId)
                    statement.executeQuery().use { rs ->
                        if (rs.next()) {
                            VenueShort(
                                id = rs.getLong("id"),
                                name = rs.getString("name"),
                                staffChatId = rs.getLong("staff_chat_id").takeIf { !rs.wasNull() }
                            )
                        } else null
                    }
                }
            }
        }
    }

    suspend fun findVenueByStaffChatId(chatId: Long): VenueShort? {
        val ds = dataSource ?: return null
        return withContext(Dispatchers.IO) {
            ds.connection.use { connection ->
                connection.prepareStatement(
                    """
                        SELECT id, name, staff_chat_id
                        FROM venues
                        WHERE staff_chat_id = ?
                    """.trimIndent()
                ).use { statement ->
                    statement.setLong(1, chatId)
                    statement.executeQuery().use { rs ->
                        if (rs.next()) {
                            VenueShort(
                                id = rs.getLong("id"),
                                name = rs.getString("name"),
                                staffChatId = rs.getLong("staff_chat_id").takeIf { !rs.wasNull() }
                            )
                        } else null
                    }
                }
            }
        }
    }

    suspend fun bindStaffChat(venueId: Long, chatId: Long, userId: Long): BindResult {
        val ds = dataSource ?: return BindResult.DatabaseError
        return withContext(Dispatchers.IO) {
            ds.connection.use { connection ->
                connection.autoCommit = false
                try {
                    val venueRow = connection.prepareStatement(
                        """
                            SELECT id, name, staff_chat_id
                            FROM venues
                            WHERE id = ?
                            FOR UPDATE
                        """.trimIndent()
                    ).use { statement ->
                        statement.setLong(1, venueId)
                        statement.executeQuery().use { rs ->
                            if (rs.next()) {
                                VenueShort(
                                    id = rs.getLong("id"),
                                    name = rs.getString("name"),
                                    staffChatId = rs.getLong("staff_chat_id").takeIf { !rs.wasNull() }
                                )
                            } else null
                        }
                    } ?: run {
                        connection.rollback()
                        return@withContext BindResult.NotFound
                    }

                    if (venueRow.staffChatId == chatId) {
                        connection.rollback()
                        return@withContext BindResult.AlreadyBoundSameChat(venueRow.id, venueRow.name)
                    }

                    val conflictVenue = findVenueIdByChatId(connection, chatId)
                    if (conflictVenue != null && conflictVenue != venueId) {
                        connection.rollback()
                        return@withContext BindResult.ChatAlreadyLinked(conflictVenue)
                    }

                    val updated = connection.prepareStatement(
                        """
                            UPDATE venues
                            SET staff_chat_id = ?, staff_chat_linked_at = now(),
                                staff_chat_linked_by_user_id = ?, staff_chat_unlinked_at = NULL,
                                staff_chat_unlinked_by_user_id = NULL, updated_at = now()
                            WHERE id = ?
                        """.trimIndent()
                    ).use { statement ->
                        statement.setLong(1, chatId)
                        statement.setLong(2, userId)
                        statement.setLong(3, venueId)
                        statement.executeUpdate()
                    }
                    if (updated == 0) {
                        connection.rollback()
                        return@withContext BindResult.NotFound
                    }
                    connection.commit()
                    BindResult.Success(venueId = venueId, venueName = venueRow.name)
                } catch (e: Exception) {
                    connection.rollback()
                    val sqlException = e as? SQLException
                    val isConstraintViolation = sqlException?.sqlState?.startsWith("23") == true
                    val conflictVenueId = if (isConstraintViolation) {
                        runCatching { findVenueIdByChatId(connection, chatId) }.getOrNull()
                            ?: dataSource?.connection?.use {
                                runCatching { findVenueIdByChatId(it, chatId) }.getOrNull()
                            }
                    } else null
                    if (isConstraintViolation) {
                        logger.info(
                            "Staff chat bind conflict chatId={} venueId={} existingVenueId={}",
                            chatId,
                            venueId,
                            conflictVenueId
                        )
                        return@withContext BindResult.ChatAlreadyLinked(conflictVenueId)
                    }
                    logger.warn(
                        "Failed to bind staff chat venueId={} chatId={}: {}",
                        venueId,
                        chatId,
                        sanitizeTelegramForLog(e.message)
                    )
                    logger.debugTelegramException(e) { "bindStaffChat exception venueId=$venueId chatId=$chatId" }
                    BindResult.DatabaseError
                } finally {
                    connection.autoCommit = true
                }
            }
        }
    }

    suspend fun unlinkStaffChatByChatId(chatId: Long, userId: Long): UnlinkResult {
        val ds = dataSource ?: return UnlinkResult.DatabaseError
        return withContext(Dispatchers.IO) {
            ds.connection.use { connection ->
                connection.autoCommit = false
                try {
                    val venue = connection.prepareStatement(
                        """
                            SELECT id, name FROM venues WHERE staff_chat_id = ? FOR UPDATE
                        """.trimIndent()
                    ).use { statement ->
                        statement.setLong(1, chatId)
                        statement.executeQuery().use { rs ->
                            if (rs.next()) VenueShort(rs.getLong("id"), rs.getString("name"), chatId) else null
                        }
                    } ?: run {
                        connection.rollback()
                        return@withContext UnlinkResult.NotLinked
                    }
                    connection.prepareStatement(
                        """
                            UPDATE venues
                            SET staff_chat_id = NULL,
                                staff_chat_unlinked_at = now(),
                                staff_chat_unlinked_by_user_id = ?,
                                updated_at = now()
                            WHERE id = ?
                        """.trimIndent()
                    ).use { statement ->
                        statement.setLong(1, userId)
                        statement.setLong(2, venue.id)
                        statement.executeUpdate()
                    }
                    connection.commit()
                    UnlinkResult.Success(venue.id, venue.name)
                } catch (e: Exception) {
                    connection.rollback()
                    logger.warn(
                        "Failed to unlink staff chat chatId={}: {}",
                        chatId,
                        sanitizeTelegramForLog(e.message)
                    )
                    logger.debugTelegramException(e) { "unlinkStaffChat exception chatId=$chatId" }
                    UnlinkResult.DatabaseError
                } finally {
                    connection.autoCommit = true
                }
            }
        }
    }

    protected open fun findVenueIdByChatId(connection: Connection, chatId: Long): Long? {
        return connection.prepareStatement(
            "SELECT id FROM venues WHERE staff_chat_id = ? FOR SHARE"
        ).use { statement ->
            statement.setLong(1, chatId)
            statement.executeQuery().use { rs -> if (rs.next()) rs.getLong(1) else null }
        }
    }
}

sealed interface BindResult {
    data class Success(val venueId: Long, val venueName: String) : BindResult
    data class AlreadyBoundSameChat(val venueId: Long, val venueName: String) : BindResult
    data class ChatAlreadyLinked(val venueId: Long?) : BindResult
    data object NotFound : BindResult
    data object DatabaseError : BindResult
}

sealed interface UnlinkResult {
    data class Success(val venueId: Long, val venueName: String) : UnlinkResult
    data object NotLinked : UnlinkResult
    data object DatabaseError : UnlinkResult
}

data class VenueShort(
    val id: Long,
    val name: String,
    val staffChatId: Long?
)
