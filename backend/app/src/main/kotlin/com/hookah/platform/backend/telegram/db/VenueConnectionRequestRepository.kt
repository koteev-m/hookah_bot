package com.hookah.platform.backend.telegram.db

import com.hookah.platform.backend.api.DatabaseUnavailableException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.sql.ResultSet
import java.sql.SQLException
import java.sql.Statement
import java.time.Instant
import java.time.LocalDate
import javax.sql.DataSource

data class VenueConnectionRequestRecord(
    val id: Long,
    val telegramUserId: Long,
    val venueName: String,
    val city: String,
    val contact: String,
    val comment: String?,
    val status: String,
    val createdAt: Instant,
    val linkedVenueId: Long? = null,
    val trialConfigured: Boolean = false,
    val trialEndsOn: LocalDate? = null,
    val currentPriceRub: Long? = null,
    val futurePriceRub: Long? = null,
    val futurePriceEffectiveOn: LocalDate? = null,
    val commercialNote: String? = null,
)

class VenueConnectionRequestRepository(private val dataSource: DataSource?) {
    companion object {
        const val STATUS_PENDING = "PENDING"
        const val STATUS_APPROVED = "APPROVED"
        const val STATUS_REJECTED = "REJECTED"
        const val STATUS_CANCELLED = "CANCELLED"
    }

    suspend fun createRequest(
        telegramUserId: Long,
        venueName: String,
        city: String,
        contact: String,
        comment: String?,
    ): Long? {
        val ds = dataSource ?: throw DatabaseUnavailableException()
        return withContext(Dispatchers.IO) {
            try {
                ds.connection.use { connection ->
                    val sql =
                        """
                        INSERT INTO venue_connection_requests (telegram_user_id, venue_name, city, contact, comment, status)
                        VALUES (?, ?, ?, ?, ?, ?)
                        """.trimIndent()
                    connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS).use { statement ->
                        statement.setLong(1, telegramUserId)
                        statement.setString(2, venueName)
                        statement.setString(3, city)
                        statement.setString(4, contact)
                        statement.setString(5, comment)
                        statement.setString(6, STATUS_PENDING)
                        statement.executeUpdate()
                        statement.generatedKeys.use { keys ->
                            if (keys.next()) {
                                return@withContext keys.getLong(1)
                            }
                        }
                    }
                    null
                }
            } catch (_: SQLException) {
                throw DatabaseUnavailableException()
            }
        }
    }

    suspend fun findPendingByUser(telegramUserId: Long): VenueConnectionRequestRecord? {
        val ds = dataSource ?: throw DatabaseUnavailableException()
        return withContext(Dispatchers.IO) {
            try {
                ds.connection.use { connection ->
                    val sql =
                        """
                        SELECT
                            id,
                            telegram_user_id,
                            venue_name,
                            city,
                            contact,
                            comment,
                            status,
                            created_at,
                            linked_venue_id,
                            trial_configured,
                            trial_ends_on,
                            current_price_rub,
                            future_price_rub,
                            future_price_effective_on,
                            commercial_note
                        FROM venue_connection_requests
                        WHERE telegram_user_id = ? AND status = ?
                        ORDER BY created_at DESC, id DESC
                        LIMIT 1
                        """.trimIndent()
                    connection.prepareStatement(sql).use { statement ->
                        statement.setLong(1, telegramUserId)
                        statement.setString(2, STATUS_PENDING)
                        statement.executeQuery().use { rs ->
                            if (rs.next()) {
                                mapRecord(rs)
                            } else {
                                null
                            }
                        }
                    }
                }
            } catch (_: SQLException) {
                throw DatabaseUnavailableException()
            }
        }
    }

    suspend fun findPendingByIdForUser(
        requestId: Long,
        telegramUserId: Long,
    ): VenueConnectionRequestRecord? {
        val ds = dataSource ?: throw DatabaseUnavailableException()
        return withContext(Dispatchers.IO) {
            try {
                ds.connection.use { connection ->
                    val sql =
                        """
                        SELECT
                            id,
                            telegram_user_id,
                            venue_name,
                            city,
                            contact,
                            comment,
                            status,
                            created_at,
                            linked_venue_id,
                            trial_configured,
                            trial_ends_on,
                            current_price_rub,
                            future_price_rub,
                            future_price_effective_on,
                            commercial_note
                        FROM venue_connection_requests
                        WHERE id = ? AND telegram_user_id = ? AND status = ?
                        LIMIT 1
                        """.trimIndent()
                    connection.prepareStatement(sql).use { statement ->
                        statement.setLong(1, requestId)
                        statement.setLong(2, telegramUserId)
                        statement.setString(3, STATUS_PENDING)
                        statement.executeQuery().use { rs ->
                            if (rs.next()) {
                                mapRecord(rs)
                            } else {
                                null
                            }
                        }
                    }
                }
            } catch (_: SQLException) {
                throw DatabaseUnavailableException()
            }
        }
    }

    suspend fun updatePendingRequest(
        requestId: Long,
        telegramUserId: Long,
        venueName: String,
        city: String,
        contact: String,
        comment: String?,
    ): Boolean {
        val ds = dataSource ?: throw DatabaseUnavailableException()
        return withContext(Dispatchers.IO) {
            try {
                ds.connection.use { connection ->
                    val sql =
                        """
                        UPDATE venue_connection_requests
                        SET venue_name = ?, city = ?, contact = ?, comment = ?
                        WHERE id = ? AND telegram_user_id = ? AND status = ?
                        """.trimIndent()
                    connection.prepareStatement(sql).use { statement ->
                        statement.setString(1, venueName)
                        statement.setString(2, city)
                        statement.setString(3, contact)
                        statement.setString(4, comment)
                        statement.setLong(5, requestId)
                        statement.setLong(6, telegramUserId)
                        statement.setString(7, STATUS_PENDING)
                        statement.executeUpdate() > 0
                    }
                }
            } catch (_: SQLException) {
                throw DatabaseUnavailableException()
            }
        }
    }

    suspend fun cancelPendingRequest(
        requestId: Long,
        telegramUserId: Long,
    ): Boolean {
        val ds = dataSource ?: throw DatabaseUnavailableException()
        return withContext(Dispatchers.IO) {
            try {
                ds.connection.use { connection ->
                    val sql =
                        """
                        UPDATE venue_connection_requests
                        SET status = ?
                        WHERE id = ? AND telegram_user_id = ? AND status = ?
                        """.trimIndent()
                    connection.prepareStatement(sql).use { statement ->
                        statement.setString(1, STATUS_CANCELLED)
                        statement.setLong(2, requestId)
                        statement.setLong(3, telegramUserId)
                        statement.setString(4, STATUS_PENDING)
                        statement.executeUpdate() > 0
                    }
                }
            } catch (_: SQLException) {
                throw DatabaseUnavailableException()
            }
        }
    }

    suspend fun setStatusByOwner(
        requestId: Long,
        status: String,
    ): Boolean {
        if (status != STATUS_APPROVED && status != STATUS_REJECTED) {
            return false
        }
        val ds = dataSource ?: throw DatabaseUnavailableException()
        return withContext(Dispatchers.IO) {
            try {
                ds.connection.use { connection ->
                    val sql =
                        """
                        UPDATE venue_connection_requests
                        SET status = ?
                        WHERE id = ? AND status = ?
                        """.trimIndent()
                    connection.prepareStatement(sql).use { statement ->
                        statement.setString(1, status)
                        statement.setLong(2, requestId)
                        statement.setString(3, STATUS_PENDING)
                        statement.executeUpdate() > 0
                    }
                }
            } catch (_: SQLException) {
                throw DatabaseUnavailableException()
            }
        }
    }

    suspend fun findById(requestId: Long): VenueConnectionRequestRecord? {
        val ds = dataSource ?: throw DatabaseUnavailableException()
        return withContext(Dispatchers.IO) {
            try {
                ds.connection.use { connection ->
                    val sql =
                        """
                        SELECT
                            id,
                            telegram_user_id,
                            venue_name,
                            city,
                            contact,
                            comment,
                            status,
                            created_at,
                            linked_venue_id,
                            trial_configured,
                            trial_ends_on,
                            current_price_rub,
                            future_price_rub,
                            future_price_effective_on,
                            commercial_note
                        FROM venue_connection_requests
                        WHERE id = ?
                        LIMIT 1
                        """.trimIndent()
                    connection.prepareStatement(sql).use { statement ->
                        statement.setLong(1, requestId)
                        statement.executeQuery().use { rs ->
                            if (rs.next()) mapRecord(rs) else null
                        }
                    }
                }
            } catch (_: SQLException) {
                throw DatabaseUnavailableException()
            }
        }
    }

    suspend fun findApprovedByLinkedVenue(venueId: Long): VenueConnectionRequestRecord? {
        val ds = dataSource ?: throw DatabaseUnavailableException()
        return withContext(Dispatchers.IO) {
            try {
                ds.connection.use { connection ->
                    val sql =
                        """
                        SELECT
                            id,
                            telegram_user_id,
                            venue_name,
                            city,
                            contact,
                            comment,
                            status,
                            created_at,
                            linked_venue_id,
                            trial_configured,
                            trial_ends_on,
                            current_price_rub,
                            future_price_rub,
                            future_price_effective_on,
                            commercial_note
                        FROM venue_connection_requests
                        WHERE linked_venue_id = ? AND status = ?
                        ORDER BY created_at DESC, id DESC
                        LIMIT 1
                        """.trimIndent()
                    connection.prepareStatement(sql).use { statement ->
                        statement.setLong(1, venueId)
                        statement.setString(2, STATUS_APPROVED)
                        statement.executeQuery().use { rs ->
                            if (rs.next()) mapRecord(rs) else null
                        }
                    }
                }
            } catch (_: SQLException) {
                throw DatabaseUnavailableException()
            }
        }
    }

    suspend fun linkApprovedRequestToVenue(
        requestId: Long,
        venueId: Long,
    ): Boolean {
        val ds = dataSource ?: throw DatabaseUnavailableException()
        return withContext(Dispatchers.IO) {
            try {
                ds.connection.use { connection ->
                    val sql =
                        """
                        UPDATE venue_connection_requests
                        SET linked_venue_id = ?
                        WHERE id = ? AND status = ? AND linked_venue_id IS NULL
                        """.trimIndent()
                    connection.prepareStatement(sql).use { statement ->
                        statement.setLong(1, venueId)
                        statement.setLong(2, requestId)
                        statement.setString(3, STATUS_APPROVED)
                        statement.executeUpdate() > 0
                    }
                }
            } catch (_: SQLException) {
                throw DatabaseUnavailableException()
            }
        }
    }

    suspend fun updateCommercialTerms(
        requestId: Long,
        trialConfigured: Boolean,
        trialEndsOn: LocalDate?,
        currentPriceRub: Long,
        futurePriceRub: Long?,
        futurePriceEffectiveOn: LocalDate?,
        commercialNote: String?,
    ): Boolean {
        val ds = dataSource ?: throw DatabaseUnavailableException()
        return withContext(Dispatchers.IO) {
            try {
                ds.connection.use { connection ->
                    val sql =
                        """
                        UPDATE venue_connection_requests
                        SET trial_configured = ?,
                            trial_ends_on = ?,
                            current_price_rub = ?,
                            future_price_rub = ?,
                            future_price_effective_on = ?,
                            commercial_note = ?
                        WHERE id = ? AND status = ? AND linked_venue_id IS NULL
                        """.trimIndent()
                    connection.prepareStatement(sql).use { statement ->
                        statement.setBoolean(1, trialConfigured)
                        if (trialEndsOn != null) {
                            statement.setDate(2, java.sql.Date.valueOf(trialEndsOn))
                        } else {
                            statement.setNull(2, java.sql.Types.DATE)
                        }
                        statement.setLong(3, currentPriceRub)
                        if (futurePriceRub != null) {
                            statement.setLong(4, futurePriceRub)
                        } else {
                            statement.setNull(4, java.sql.Types.BIGINT)
                        }
                        if (futurePriceEffectiveOn != null) {
                            statement.setDate(5, java.sql.Date.valueOf(futurePriceEffectiveOn))
                        } else {
                            statement.setNull(5, java.sql.Types.DATE)
                        }
                        statement.setString(6, commercialNote)
                        statement.setLong(7, requestId)
                        statement.setString(8, STATUS_APPROVED)
                        statement.executeUpdate() > 0
                    }
                }
            } catch (_: SQLException) {
                throw DatabaseUnavailableException()
            }
        }
    }

    suspend fun listPendingRequests(limit: Int): List<VenueConnectionRequestRecord> {
        val ds = dataSource ?: throw DatabaseUnavailableException()
        return withContext(Dispatchers.IO) {
            try {
                ds.connection.use { connection ->
                    val sql =
                        """
                        SELECT
                            id,
                            telegram_user_id,
                            venue_name,
                            city,
                            contact,
                            comment,
                            status,
                            created_at,
                            linked_venue_id,
                            trial_configured,
                            trial_ends_on,
                            current_price_rub,
                            future_price_rub,
                            future_price_effective_on,
                            commercial_note
                        FROM venue_connection_requests
                        WHERE status = ?
                        ORDER BY created_at DESC, id DESC
                        LIMIT ?
                        """.trimIndent()
                    connection.prepareStatement(sql).use { statement ->
                        statement.setString(1, STATUS_PENDING)
                        statement.setInt(2, limit.coerceAtLeast(1))
                        statement.executeQuery().use { rs ->
                            val items = mutableListOf<VenueConnectionRequestRecord>()
                            while (rs.next()) {
                                items.add(mapRecord(rs))
                            }
                            items
                        }
                    }
                }
            } catch (_: SQLException) {
                throw DatabaseUnavailableException()
            }
        }
    }

    suspend fun listNewRequests(limit: Int): List<VenueConnectionRequestRecord> = listPendingRequests(limit)

    private fun mapRecord(rs: ResultSet): VenueConnectionRequestRecord =
        VenueConnectionRequestRecord(
            id = rs.getLong("id"),
            telegramUserId = rs.getLong("telegram_user_id"),
            venueName = rs.getString("venue_name"),
            city = rs.getString("city"),
            contact = rs.getString("contact"),
            comment = rs.getString("comment"),
            status = rs.getString("status"),
            createdAt = rs.getTimestamp("created_at").toInstant(),
            linkedVenueId = rs.getLong("linked_venue_id").takeIf { !rs.wasNull() },
            trialConfigured = rs.getBoolean("trial_configured").takeIf { !rs.wasNull() } ?: false,
            trialEndsOn = rs.getDate("trial_ends_on")?.toLocalDate(),
            currentPriceRub = rs.getLong("current_price_rub").takeIf { !rs.wasNull() },
            futurePriceRub = rs.getLong("future_price_rub").takeIf { !rs.wasNull() },
            futurePriceEffectiveOn = rs.getDate("future_price_effective_on")?.toLocalDate(),
            commercialNote = rs.getString("commercial_note"),
        )
}
