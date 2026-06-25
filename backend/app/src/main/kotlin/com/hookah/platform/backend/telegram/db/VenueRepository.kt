package com.hookah.platform.backend.telegram.db

import com.hookah.platform.backend.api.DatabaseUnavailableException
import com.hookah.platform.backend.miniapp.subscription.SubscriptionStatus
import com.hookah.platform.backend.miniapp.venue.VenueStatus
import com.hookah.platform.backend.telegram.debugTelegramException
import com.hookah.platform.backend.telegram.sanitizeTelegramForLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import java.sql.Connection
import java.sql.ResultSet
import java.sql.SQLException
import java.time.Instant
import javax.sql.DataSource

open class VenueRepository(private val dataSource: DataSource?) {
    private val logger = LoggerFactory.getLogger(VenueRepository::class.java)
    private val botTestCatalogVenues =
        listOf(
            BotTestCatalogVenueSeed(
                name = "Тестовая кальянная",
                city = "Москва",
                address = "Тверская, 1",
            ),
            BotTestCatalogVenueSeed(
                name = "Дым и Лёд",
                city = "Москва",
                address = "Покровка, 12",
            ),
            BotTestCatalogVenueSeed(
                name = "Hookah Lounge 24",
                city = "Москва",
                address = "Новый Арбат, 24",
            ),
        )

    suspend fun findVenueById(venueId: Long): VenueShort? {
        val ds = dataSource ?: return null
        return withContext(Dispatchers.IO) {
            ds.connection.use { connection ->
                connection.prepareStatement(
                    """
                    SELECT id, name, staff_chat_id
                    FROM venues
                    WHERE id = ?
                    """.trimIndent(),
                ).use { statement ->
                    statement.setLong(1, venueId)
                    statement.executeQuery().use { rs ->
                        if (rs.next()) {
                            VenueShort(
                                id = rs.getLong("id"),
                                name = rs.getString("name"),
                                staffChatId = rs.getLong("staff_chat_id").takeIf { !rs.wasNull() },
                            )
                        } else {
                            null
                        }
                    }
                }
            }
        }
    }

    suspend fun listCatalogVenuesForGuest(): List<CatalogVenueShort> {
        val ds = dataSource ?: throw DatabaseUnavailableException()
        val blockedStatuses = SubscriptionStatus.blockedDbValues
        val blockedPlaceholders = blockedStatuses.joinToString(",") { "?" }
        return withContext(Dispatchers.IO) {
            try {
                ds.connection.use { connection ->
                    connection.prepareStatement(
                        """
                        SELECT v.id, v.name, v.city, v.address, v.guest_contact, v.card_description, v.status
                        FROM venues v
                        LEFT JOIN venue_subscriptions vs ON vs.venue_id = v.id
                        WHERE v.status = ?
                          AND (vs.status IS NULL OR LOWER(vs.status) NOT IN ($blockedPlaceholders))
                        ORDER BY v.id ASC
                        """.trimIndent(),
                    ).use { statement ->
                        statement.setString(1, VenueStatus.PUBLISHED.dbValue)
                        blockedStatuses.forEachIndexed { index, status ->
                            statement.setString(index + 2, status)
                        }
                        statement.executeQuery().use { rs ->
                            val venues = mutableListOf<CatalogVenueShort>()
                            while (rs.next()) {
                                mapCatalogVenue(rs)?.let { venues.add(it) }
                            }
                            venues
                        }
                    }
                }
            } catch (_: SQLException) {
                throw DatabaseUnavailableException()
            }
        }
    }

    suspend fun ensureBotTestCatalogVenues() {
        val ds = dataSource ?: throw DatabaseUnavailableException()
        return withContext(Dispatchers.IO) {
            try {
                ds.connection.use { connection ->
                    connection.autoCommit = false
                    try {
                        botTestCatalogVenues.forEach { venue ->
                            connection.prepareStatement(
                                """
                                INSERT INTO venues (name, city, address, status)
                                SELECT ?, ?, ?, ?
                                WHERE NOT EXISTS (
                                    SELECT 1
                                    FROM venues
                                    WHERE LOWER(name) = LOWER(?)
                                )
                                """.trimIndent(),
                            ).use { statement ->
                                statement.setString(1, venue.name)
                                statement.setString(2, venue.city)
                                statement.setString(3, venue.address)
                                statement.setString(4, VenueStatus.PUBLISHED.dbValue)
                                statement.setString(5, venue.name)
                                statement.executeUpdate()
                            }
                        }
                        connection.commit()
                    } catch (e: SQLException) {
                        runCatching { connection.rollback() }
                        throw e
                    } finally {
                        connection.autoCommit = true
                    }
                }
            } catch (_: SQLException) {
                throw DatabaseUnavailableException()
            }
        }
    }

    suspend fun findCatalogVenueByIdForGuest(id: Long): CatalogVenueShort? {
        val ds = dataSource ?: throw DatabaseUnavailableException()
        val blockedStatuses = SubscriptionStatus.blockedDbValues
        val blockedPlaceholders = blockedStatuses.joinToString(",") { "?" }
        return withContext(Dispatchers.IO) {
            try {
                ds.connection.use { connection ->
                    connection.prepareStatement(
                        """
                        SELECT v.id, v.name, v.city, v.address, v.guest_contact, v.card_description, v.status
                        FROM venues v
                        LEFT JOIN venue_subscriptions vs ON vs.venue_id = v.id
                        WHERE v.id = ?
                          AND v.status = ?
                          AND (vs.status IS NULL OR LOWER(vs.status) NOT IN ($blockedPlaceholders))
                        """.trimIndent(),
                    ).use { statement ->
                        statement.setLong(1, id)
                        statement.setString(2, VenueStatus.PUBLISHED.dbValue)
                        blockedStatuses.forEachIndexed { index, status ->
                            statement.setString(index + 3, status)
                        }
                        statement.executeQuery().use { rs ->
                            if (rs.next()) mapCatalogVenue(rs) else null
                        }
                    }
                }
            } catch (_: SQLException) {
                throw DatabaseUnavailableException()
            }
        }
    }

    suspend fun findPublicCardSettings(venueId: Long): VenuePublicCardSettings? {
        val ds = dataSource ?: throw DatabaseUnavailableException()
        return withContext(Dispatchers.IO) {
            try {
                ds.connection.use { connection ->
                    loadPublicCardSettings(connection, venueId)
                }
            } catch (_: SQLException) {
                throw DatabaseUnavailableException()
            }
        }
    }

    suspend fun updatePublicCardSettings(
        venueId: Long,
        city: String?,
        address: String?,
        guestContact: String?,
        cardDescription: String?,
    ): VenuePublicCardSettings? {
        val ds = dataSource ?: throw DatabaseUnavailableException()
        return withContext(Dispatchers.IO) {
            try {
                ds.connection.use { connection ->
                    connection.autoCommit = false
                    try {
                        val updatedRows =
                            connection.prepareStatement(
                                """
                                UPDATE venues
                                SET city = ?,
                                    address = ?,
                                    guest_contact = ?,
                                    card_description = ?,
                                    updated_at = now()
                                WHERE id = ?
                                """.trimIndent(),
                            ).use { statement ->
                                statement.setString(1, city)
                                statement.setString(2, address)
                                statement.setString(3, guestContact)
                                statement.setString(4, cardDescription)
                                statement.setLong(5, venueId)
                                statement.executeUpdate()
                            }
                        if (updatedRows <= 0) {
                            connection.rollback()
                            return@withContext null
                        }
                        val updated = loadPublicCardSettings(connection, venueId)
                        connection.commit()
                        updated
                    } catch (e: SQLException) {
                        runCatching { connection.rollback() }
                        throw e
                    } finally {
                        connection.autoCommit = true
                    }
                }
            } catch (_: SQLException) {
                throw DatabaseUnavailableException()
            }
        }
    }

    suspend fun findStaffChatStatus(venueId: Long): StaffChatStatus? {
        val ds = dataSource ?: return null
        return withContext(Dispatchers.IO) {
            ds.connection.use { connection ->
                connection.prepareStatement(
                    """
                    SELECT id, staff_chat_id, staff_chat_linked_at, staff_chat_linked_by_user_id,
                           staff_chat_unlinked_at, staff_chat_unlinked_by_user_id
                    FROM venues
                    WHERE id = ?
                    """.trimIndent(),
                ).use { statement ->
                    statement.setLong(1, venueId)
                    statement.executeQuery().use { rs ->
                        if (rs.next()) {
                            StaffChatStatus(
                                venueId = rs.getLong("id"),
                                staffChatId = rs.getLong("staff_chat_id").takeIf { !rs.wasNull() },
                                linkedAt = rs.getTimestamp("staff_chat_linked_at")?.toInstant(),
                                linkedByUserId = rs.getLong("staff_chat_linked_by_user_id").takeIf { !rs.wasNull() },
                                unlinkedAt = rs.getTimestamp("staff_chat_unlinked_at")?.toInstant(),
                                unlinkedByUserId =
                                    rs.getLong(
                                        "staff_chat_unlinked_by_user_id",
                                    ).takeIf { !rs.wasNull() },
                            )
                        } else {
                            null
                        }
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
                    """.trimIndent(),
                ).use { statement ->
                    statement.setLong(1, chatId)
                    statement.executeQuery().use { rs ->
                        if (rs.next()) {
                            VenueShort(
                                id = rs.getLong("id"),
                                name = rs.getString("name"),
                                staffChatId = rs.getLong("staff_chat_id").takeIf { !rs.wasNull() },
                            )
                        } else {
                            null
                        }
                    }
                }
            }
        }
    }

    suspend fun bindStaffChat(
        venueId: Long,
        chatId: Long,
        userId: Long,
    ): BindResult {
        val ds = dataSource ?: return BindResult.DatabaseError
        return withContext(Dispatchers.IO) {
            ds.connection.use { connection ->
                bindStaffChatInternal(connection, venueId, chatId, userId, manageTransaction = true)
            }
        }
    }

    fun bindStaffChatInTransaction(
        connection: Connection,
        venueId: Long,
        chatId: Long,
        userId: Long,
    ): BindResult {
        return bindStaffChatInternal(connection, venueId, chatId, userId, manageTransaction = false)
    }

    suspend fun unlinkStaffChatByChatId(
        chatId: Long,
        userId: Long,
    ): UnlinkResult {
        val ds = dataSource ?: return UnlinkResult.DatabaseError
        return withContext(Dispatchers.IO) {
            ds.connection.use { connection ->
                connection.autoCommit = false
                try {
                    val venue =
                        connection.prepareStatement(
                            """
                            SELECT id, name FROM venues WHERE staff_chat_id = ? FOR UPDATE
                            """.trimIndent(),
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
                        """.trimIndent(),
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
                        sanitizeTelegramForLog(e.message),
                    )
                    logger.debugTelegramException(e) { "unlinkStaffChat exception chatId=$chatId" }
                    UnlinkResult.DatabaseError
                } finally {
                    connection.autoCommit = true
                }
            }
        }
    }

    suspend fun unlinkStaffChatByVenueId(
        venueId: Long,
        userId: Long,
    ): UnlinkResult {
        val ds = dataSource ?: return UnlinkResult.DatabaseError
        return withContext(Dispatchers.IO) {
            ds.connection.use { connection ->
                connection.autoCommit = false
                try {
                    val venue =
                        connection.prepareStatement(
                            """
                            SELECT id, name, staff_chat_id
                            FROM venues
                            WHERE id = ?
                            FOR UPDATE
                            """.trimIndent(),
                        ).use { statement ->
                            statement.setLong(1, venueId)
                            statement.executeQuery().use { rs ->
                                if (rs.next()) {
                                    VenueShort(
                                        id = rs.getLong("id"),
                                        name = rs.getString("name"),
                                        staffChatId = rs.getLong("staff_chat_id").takeIf { !rs.wasNull() },
                                    )
                                } else {
                                    null
                                }
                            }
                        } ?: run {
                            connection.rollback()
                            return@withContext UnlinkResult.NotLinked
                        }
                    if (venue.staffChatId == null) {
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
                        """.trimIndent(),
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
                        "Failed to unlink staff chat venueId={}: {}",
                        venueId,
                        sanitizeTelegramForLog(e.message),
                    )
                    logger.debugTelegramException(e) { "unlinkStaffChatByVenueId exception venueId=$venueId" }
                    UnlinkResult.DatabaseError
                } finally {
                    connection.autoCommit = true
                }
            }
        }
    }

    private fun loadPublicCardSettings(
        connection: Connection,
        venueId: Long,
    ): VenuePublicCardSettings? {
        return connection.prepareStatement(
            """
            SELECT id, name, city, address, guest_contact, card_description
            FROM venues
            WHERE id = ?
            """.trimIndent(),
        ).use { statement ->
            statement.setLong(1, venueId)
            statement.executeQuery().use { rs ->
                if (rs.next()) {
                    VenuePublicCardSettings(
                        venueId = rs.getLong("id"),
                        name = rs.getString("name"),
                        city = rs.getString("city"),
                        address = rs.getString("address"),
                        guestContact = rs.getString("guest_contact"),
                        cardDescription = rs.getString("card_description"),
                    )
                } else {
                    null
                }
            }
        }
    }

    protected open fun findVenueIdByChatId(
        connection: Connection,
        chatId: Long,
    ): Long? {
        return connection.prepareStatement(
            "SELECT id FROM venues WHERE staff_chat_id = ?",
        ).use { statement ->
            statement.setLong(1, chatId)
            statement.executeQuery().use { rs -> if (rs.next()) rs.getLong(1) else null }
        }
    }

    private fun bindStaffChatInternal(
        connection: Connection,
        venueId: Long,
        chatId: Long,
        userId: Long,
        manageTransaction: Boolean,
    ): BindResult {
        val initialAutoCommit = connection.autoCommit
        if (manageTransaction) {
            connection.autoCommit = false
        }
        try {
            val venueRow =
                connection.prepareStatement(
                    """
                    SELECT id, name, staff_chat_id
                    FROM venues
                    WHERE id = ?
                    FOR UPDATE
                    """.trimIndent(),
                ).use { statement ->
                    statement.setLong(1, venueId)
                    statement.executeQuery().use { rs ->
                        if (rs.next()) {
                            VenueShort(
                                id = rs.getLong("id"),
                                name = rs.getString("name"),
                                staffChatId = rs.getLong("staff_chat_id").takeIf { !rs.wasNull() },
                            )
                        } else {
                            null
                        }
                    }
                } ?: return rollbackAndReturn(manageTransaction, connection) { BindResult.NotFound }

            if (venueRow.staffChatId == chatId) {
                return rollbackAndReturn(manageTransaction, connection) {
                    BindResult.AlreadyBoundSameChat(venueRow.id, venueRow.name)
                }
            }

            val conflictVenue = findVenueIdByChatId(connection, chatId)
            if (conflictVenue != null && conflictVenue != venueId) {
                return rollbackAndReturn(manageTransaction, connection) {
                    BindResult.ChatAlreadyLinked(conflictVenue)
                }
            }

            val updated =
                connection.prepareStatement(
                    """
                    UPDATE venues
                    SET staff_chat_id = ?, staff_chat_linked_at = now(),
                        staff_chat_linked_by_user_id = ?, staff_chat_unlinked_at = NULL,
                        staff_chat_unlinked_by_user_id = NULL, updated_at = now()
                    WHERE id = ?
                    """.trimIndent(),
                ).use { statement ->
                    statement.setLong(1, chatId)
                    statement.setLong(2, userId)
                    statement.setLong(3, venueId)
                    statement.executeUpdate()
                }
            if (updated == 0) {
                return rollbackAndReturn(manageTransaction, connection) { BindResult.NotFound }
            }
            if (manageTransaction) {
                connection.commit()
            }
            return BindResult.Success(venueId = venueId, venueName = venueRow.name)
        } catch (e: Exception) {
            if (manageTransaction) {
                runCatching { connection.rollback() }
            }
            val isUniqueViolation = e.hasSqlState("23505")
            val conflictVenueId =
                if (isUniqueViolation && manageTransaction) {
                    runCatching { findVenueIdByChatId(connection, chatId) }.getOrNull()
                } else {
                    null
                }
            if (isUniqueViolation) {
                logger.info(
                    "Staff chat bind conflict chatId={} venueId={} existingVenueId={}",
                    chatId,
                    venueId,
                    conflictVenueId,
                )
                return BindResult.ChatAlreadyLinked(conflictVenueId)
            }
            logger.warn(
                "Failed to bind staff chat venueId={} chatId={}: {}",
                venueId,
                chatId,
                sanitizeTelegramForLog(e.message),
            )
            logger.debugTelegramException(e) { "bindStaffChat exception venueId=$venueId chatId=$chatId" }
            return BindResult.DatabaseError
        } finally {
            if (manageTransaction) {
                connection.autoCommit = initialAutoCommit
            }
        }
    }

    private fun rollbackAndReturn(
        manageTransaction: Boolean,
        connection: Connection,
        block: () -> BindResult,
    ): BindResult {
        if (manageTransaction) {
            runCatching { connection.rollback() }
        }
        return block()
    }

    private fun mapCatalogVenue(rs: ResultSet): CatalogVenueShort? {
        val status = VenueStatus.fromDb(rs.getString("status")) ?: return null
        if (status != VenueStatus.PUBLISHED) return null
        return CatalogVenueShort(
            id = rs.getLong("id"),
            name = rs.getString("name"),
            city = rs.getString("city"),
            address = rs.getString("address"),
            guestContact = rs.getString("guest_contact"),
            cardDescription = rs.getString("card_description"),
        )
    }

    private data class BotTestCatalogVenueSeed(
        val name: String,
        val city: String,
        val address: String,
    )
}

internal fun Throwable.hasSqlState(sqlState: String): Boolean {
    val visited = mutableSetOf<Throwable>()
    val toProcess = ArrayDeque<Throwable>()
    toProcess.add(this)

    while (toProcess.isNotEmpty()) {
        val current = toProcess.removeFirst()
        if (!visited.add(current)) continue

        if (current is SQLException && current.sqlState == sqlState) return true

        current.cause?.let { cause ->
            if (!visited.contains(cause)) {
                toProcess.add(cause)
            }
        }
        if (current is SQLException) {
            current.nextException?.let { next ->
                if (!visited.contains(next)) {
                    toProcess.add(next)
                }
            }
        }
        current.suppressed.forEach { suppressed ->
            if (!visited.contains(suppressed)) {
                toProcess.add(suppressed)
            }
        }
    }
    return false
}

data class VenueShort(
    val id: Long,
    val name: String,
    val staffChatId: Long?,
)

data class CatalogVenueShort(
    val id: Long,
    val name: String,
    val city: String?,
    val address: String?,
    val guestContact: String? = null,
    val cardDescription: String? = null,
)

data class VenuePublicCardSettings(
    val venueId: Long,
    val name: String,
    val city: String?,
    val address: String?,
    val guestContact: String?,
    val cardDescription: String?,
)

data class StaffChatStatus(
    val venueId: Long,
    val staffChatId: Long?,
    val linkedAt: Instant?,
    val linkedByUserId: Long?,
    val unlinkedAt: Instant?,
    val unlinkedByUserId: Long?,
)

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
