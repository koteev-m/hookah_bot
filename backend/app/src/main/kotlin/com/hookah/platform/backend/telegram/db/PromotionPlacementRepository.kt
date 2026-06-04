package com.hookah.platform.backend.telegram.db

import com.hookah.platform.backend.api.DatabaseUnavailableException
import com.hookah.platform.backend.miniapp.subscription.SubscriptionStatus
import com.hookah.platform.backend.miniapp.venue.VenueStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.sql.Connection
import java.sql.ResultSet
import java.sql.SQLException
import java.sql.Statement
import java.sql.Timestamp
import java.time.Instant
import java.util.Locale
import javax.sql.DataSource

enum class PromotionPlacementSurface(val dbValue: String) {
    GLOBAL_PROMOTIONS_TOP("GLOBAL_PROMOTIONS_TOP"),
    VENUE_PROMOTIONS_TOP("VENUE_PROMOTIONS_TOP"),
    ;

    companion object {
        fun fromDb(value: String?): PromotionPlacementSurface? {
            val normalized = value?.trim()?.uppercase(Locale.ROOT) ?: return null
            return entries.firstOrNull { it.dbValue == normalized }
        }
    }
}

enum class PromotionPlacementStatus(val dbValue: String) {
    PENDING("PENDING"),
    APPROVED("APPROVED"),
    ACTIVE("ACTIVE"),
    PAUSED("PAUSED"),
    REJECTED("REJECTED"),
    ARCHIVED("ARCHIVED"),
    ;

    companion object {
        fun fromDb(value: String?): PromotionPlacementStatus? {
            val normalized = value?.trim()?.uppercase(Locale.ROOT) ?: return null
            return entries.firstOrNull { it.dbValue == normalized }
        }
    }
}

data class PromotionPlacement(
    val id: Long,
    val promotionId: Long,
    val venueId: Long,
    val venueName: String,
    val venueCity: String?,
    val venueAddress: String?,
    val promotionTitle: String,
    val promotionDescription: String,
    val promotionTemplateType: VenuePromotionTemplateType,
    val promotionStatus: VenuePromotionStatus,
    val surface: PromotionPlacementSurface,
    val status: PromotionPlacementStatus,
    val startsAt: Instant?,
    val endsAt: Instant?,
    val priority: Int,
    val requestedByUserId: Long,
    val approvedByUserId: Long?,
    val approvedAt: Instant?,
    val rejectedReason: String?,
    val createdAt: Instant,
    val updatedAt: Instant,
    val primaryImageFileId: String?,
)

class PromotionPlacementRepository(private val dataSource: DataSource?) {
    suspend fun createRequest(
        promotionId: Long,
        venueId: Long,
        surface: PromotionPlacementSurface,
        requestedByUserId: Long,
        startsAt: Instant? = null,
        endsAt: Instant? = null,
    ): PromotionPlacement? {
        val ds = dataSource ?: throw DatabaseUnavailableException()
        validatePeriod(startsAt, endsAt)
        return withContext(Dispatchers.IO) {
            try {
                ds.connection.use { connection ->
                    if (!isBannerPromotionForVenue(connection, venueId, promotionId)) {
                        return@withContext null
                    }
                    val id =
                        connection.prepareStatement(
                            """
                            INSERT INTO promotion_placements (
                                promotion_id, venue_id, surface, status, starts_at, ends_at, requested_by_user_id
                            )
                            VALUES (?, ?, ?, ?, ?, ?, ?)
                            """.trimIndent(),
                            Statement.RETURN_GENERATED_KEYS,
                        ).use { statement ->
                            statement.setLong(1, promotionId)
                            statement.setLong(2, venueId)
                            statement.setString(3, surface.dbValue)
                            statement.setString(4, PromotionPlacementStatus.PENDING.dbValue)
                            setInstant(statement, 5, startsAt)
                            setInstant(statement, 6, endsAt)
                            statement.setLong(7, requestedByUserId)
                            statement.executeUpdate()
                            statement.generatedKeys.use { keys ->
                                if (!keys.next()) throw SQLException("No generated key for promotion placement")
                                keys.getLong(1)
                            }
                        }
                    selectPlacement(connection, id)
                }
            } catch (_: SQLException) {
                throw DatabaseUnavailableException()
            }
        }
    }

    suspend fun listPending(limit: Int = 20): List<PromotionPlacement> {
        val ds = dataSource ?: throw DatabaseUnavailableException()
        return withContext(Dispatchers.IO) {
            try {
                ds.connection.use { connection ->
                    connection.prepareStatement(
                        """
                        SELECT ${placementColumns()}
                        FROM promotion_placements pp
                        JOIN venue_promotions p ON p.id = pp.promotion_id
                        JOIN venues v ON v.id = pp.venue_id
                        LEFT JOIN venue_promotion_media m ON m.id = (
                            SELECT m2.id
                            FROM venue_promotion_media m2
                            WHERE m2.promotion_id = p.id AND m2.media_type = ?
                            ORDER BY m2.sort_order ASC, m2.id ASC
                            LIMIT 1
                        )
                        WHERE pp.status = ?
                        ORDER BY pp.created_at ASC, pp.id ASC
                        LIMIT ?
                        """.trimIndent(),
                    ).use { statement ->
                        statement.setString(1, VenuePromotionMediaType.IMAGE.dbValue)
                        statement.setString(2, PromotionPlacementStatus.PENDING.dbValue)
                        statement.setInt(3, limit.coerceIn(1, 100))
                        statement.executeQuery().use { rs -> rs.toPlacements() }
                    }
                }
            } catch (_: SQLException) {
                throw DatabaseUnavailableException()
            }
        }
    }

    suspend fun listActiveForPlatformManagement(
        limit: Int = 20,
        now: Instant = Instant.now(),
    ): List<PromotionPlacement> = listByStatusForPlatformManagement(PromotionPlacementStatus.ACTIVE, limit, now)

    suspend fun listFinishedForPlatformManagement(
        limit: Int = 20,
        now: Instant = Instant.now(),
    ): List<PromotionPlacement> = listFinishedForManagement(venueId = null, now = now, limit = limit)

    suspend fun listForVenueManagement(
        venueId: Long,
        status: PromotionPlacementStatus,
        limit: Int = 20,
        now: Instant = Instant.now(),
    ): List<PromotionPlacement> {
        val ds = dataSource ?: throw DatabaseUnavailableException()
        val activePeriodFilter =
            if (status == PromotionPlacementStatus.ACTIVE) {
                "AND (pp.ends_at IS NULL OR pp.ends_at >= ?)"
            } else {
                ""
            }
        return withContext(Dispatchers.IO) {
            try {
                ds.connection.use { connection ->
                    connection.prepareStatement(
                        """
                        SELECT ${placementColumns()}
                        FROM promotion_placements pp
                        JOIN venue_promotions p ON p.id = pp.promotion_id
                        JOIN venues v ON v.id = pp.venue_id
                        LEFT JOIN venue_promotion_media m ON m.id = (
                            SELECT m2.id
                            FROM venue_promotion_media m2
                            WHERE m2.promotion_id = p.id AND m2.media_type = ?
                            ORDER BY m2.sort_order ASC, m2.id ASC
                            LIMIT 1
                        )
                        WHERE pp.venue_id = ?
                          AND pp.status = ?
                          $activePeriodFilter
                        ORDER BY pp.updated_at DESC, pp.id DESC
                        LIMIT ?
                        """.trimIndent(),
                    ).use { statement ->
                        var index = 1
                        statement.setString(index++, VenuePromotionMediaType.IMAGE.dbValue)
                        statement.setLong(index++, venueId)
                        statement.setString(index++, status.dbValue)
                        if (status == PromotionPlacementStatus.ACTIVE) {
                            statement.setTimestamp(index++, Timestamp.from(now))
                        }
                        statement.setInt(index, limit.coerceIn(1, 100))
                        statement.executeQuery().use { rs -> rs.toPlacements() }
                    }
                }
            } catch (_: SQLException) {
                throw DatabaseUnavailableException()
            }
        }
    }

    suspend fun listFinishedForVenueManagement(
        venueId: Long,
        limit: Int = 20,
        now: Instant = Instant.now(),
    ): List<PromotionPlacement> = listFinishedForManagement(venueId = venueId, now = now, limit = limit)

    suspend fun getForVenueManagement(
        venueId: Long,
        id: Long,
    ): PromotionPlacement? {
        val ds = dataSource ?: throw DatabaseUnavailableException()
        return withContext(Dispatchers.IO) {
            try {
                ds.connection.use { connection ->
                    connection.prepareStatement(
                        """
                        SELECT ${placementColumns()}
                        FROM promotion_placements pp
                        JOIN venue_promotions p ON p.id = pp.promotion_id
                        JOIN venues v ON v.id = pp.venue_id
                        LEFT JOIN venue_promotion_media m ON m.id = (
                            SELECT m2.id
                            FROM venue_promotion_media m2
                            WHERE m2.promotion_id = p.id AND m2.media_type = ?
                            ORDER BY m2.sort_order ASC, m2.id ASC
                            LIMIT 1
                        )
                        WHERE pp.venue_id = ?
                          AND pp.id = ?
                        """.trimIndent(),
                    ).use { statement ->
                        statement.setString(1, VenuePromotionMediaType.IMAGE.dbValue)
                        statement.setLong(2, venueId)
                        statement.setLong(3, id)
                        statement.executeQuery().use { rs -> if (rs.next()) rs.toPlacement() else null }
                    }
                }
            } catch (_: SQLException) {
                throw DatabaseUnavailableException()
            }
        }
    }

    suspend fun getForPlatformManagement(id: Long): PromotionPlacement? {
        val ds = dataSource ?: throw DatabaseUnavailableException()
        return withContext(Dispatchers.IO) {
            try {
                ds.connection.use { connection -> selectPlacement(connection, id) }
            } catch (_: SQLException) {
                throw DatabaseUnavailableException()
            }
        }
    }

    suspend fun approve(
        id: Long,
        platformUserId: Long,
    ): PromotionPlacement? =
        updateStatus(
            id = id,
            status = PromotionPlacementStatus.ACTIVE,
            platformUserId = platformUserId,
            rejectedReason = null,
            setApproved = true,
        )

    suspend fun approveForPeriod(
        id: Long,
        platformUserId: Long,
        startsAt: Instant,
        endsAt: Instant,
    ): PromotionPlacement? {
        validatePeriod(startsAt, endsAt)
        val ds = dataSource ?: throw DatabaseUnavailableException()
        return withContext(Dispatchers.IO) {
            try {
                ds.connection.use { connection ->
                    val updated =
                        connection.prepareStatement(
                            """
                            UPDATE promotion_placements
                            SET status = ?,
                                starts_at = ?,
                                ends_at = ?,
                                approved_by_user_id = ?,
                                approved_at = CURRENT_TIMESTAMP,
                                rejected_reason = NULL,
                                updated_at = CURRENT_TIMESTAMP
                            WHERE id = ?
                            """.trimIndent(),
                        ).use { statement ->
                            statement.setString(1, PromotionPlacementStatus.ACTIVE.dbValue)
                            statement.setTimestamp(2, Timestamp.from(startsAt))
                            statement.setTimestamp(3, Timestamp.from(endsAt))
                            statement.setLong(4, platformUserId)
                            statement.setLong(5, id)
                            statement.executeUpdate()
                        }
                    if (updated == 0) null else selectPlacement(connection, id)
                }
            } catch (_: SQLException) {
                throw DatabaseUnavailableException()
            }
        }
    }

    suspend fun reject(
        id: Long,
        platformUserId: Long,
        reason: String? = null,
    ): PromotionPlacement? =
        updateStatus(
            id = id,
            status = PromotionPlacementStatus.REJECTED,
            platformUserId = platformUserId,
            rejectedReason = reason?.trim()?.takeIf { it.isNotBlank() },
            setApproved = false,
        )

    suspend fun pause(id: Long): PromotionPlacement? = updateStatus(id = id, status = PromotionPlacementStatus.PAUSED)

    suspend fun archive(id: Long): PromotionPlacement? =
        updateStatus(id = id, status = PromotionPlacementStatus.ARCHIVED)

    suspend fun listActiveForGlobalPromotions(
        now: Instant = Instant.now(),
        limit: Int = 5,
    ): List<PromotionPlacement> =
        listActiveForSurface(
            surface = PromotionPlacementSurface.GLOBAL_PROMOTIONS_TOP,
            venueId = null,
            now = now,
            limit = limit,
        )

    suspend fun listActiveForVenuePromotions(
        venueId: Long,
        now: Instant = Instant.now(),
        limit: Int = 5,
    ): List<PromotionPlacement> =
        listActiveForSurface(
            surface = PromotionPlacementSurface.VENUE_PROMOTIONS_TOP,
            venueId = venueId,
            now = now,
            limit = limit,
        )

    private suspend fun updateStatus(
        id: Long,
        status: PromotionPlacementStatus,
        platformUserId: Long? = null,
        rejectedReason: String? = null,
        setApproved: Boolean = false,
    ): PromotionPlacement? {
        val ds = dataSource ?: throw DatabaseUnavailableException()
        return withContext(Dispatchers.IO) {
            try {
                ds.connection.use { connection ->
                    val updated =
                        connection.prepareStatement(
                            """
                            UPDATE promotion_placements
                            SET status = ?,
                                approved_by_user_id = CASE WHEN ? THEN ? ELSE approved_by_user_id END,
                                approved_at = CASE WHEN ? THEN CURRENT_TIMESTAMP ELSE approved_at END,
                                rejected_reason = ?,
                                updated_at = CURRENT_TIMESTAMP
                            WHERE id = ?
                            """.trimIndent(),
                        ).use { statement ->
                            statement.setString(1, status.dbValue)
                            statement.setBoolean(2, setApproved)
                            if (platformUserId == null) {
                                statement.setObject(3, null)
                            } else {
                                statement.setLong(3, platformUserId)
                            }
                            statement.setBoolean(4, setApproved)
                            statement.setString(5, rejectedReason)
                            statement.setLong(6, id)
                            statement.executeUpdate()
                        }
                    if (updated == 0) null else selectPlacement(connection, id)
                }
            } catch (_: SQLException) {
                throw DatabaseUnavailableException()
            }
        }
    }

    private suspend fun listActiveForSurface(
        surface: PromotionPlacementSurface,
        venueId: Long?,
        now: Instant,
        limit: Int,
    ): List<PromotionPlacement> {
        val ds = dataSource ?: throw DatabaseUnavailableException()
        val blockedStatuses = SubscriptionStatus.blockedDbValues
        val blockedPlaceholders = blockedStatuses.joinToString(",") { "?" }
        val venueFilter = if (venueId == null) "" else "AND pp.venue_id = ?"
        return withContext(Dispatchers.IO) {
            try {
                ds.connection.use { connection ->
                    connection.prepareStatement(
                        """
                        SELECT ${placementColumns()}
                        FROM promotion_placements pp
                        JOIN venue_promotions p ON p.id = pp.promotion_id
                        JOIN venues v ON v.id = pp.venue_id
                        JOIN venue_promotion_media m ON m.id = (
                            SELECT m2.id
                            FROM venue_promotion_media m2
                            WHERE m2.promotion_id = p.id AND m2.media_type = ?
                            ORDER BY m2.sort_order ASC, m2.id ASC
                            LIMIT 1
                        )
                        LEFT JOIN venue_subscriptions vs ON vs.venue_id = v.id
                        WHERE pp.surface = ?
                          AND pp.status = ?
                          AND (pp.starts_at IS NULL OR pp.starts_at <= ?)
                          AND (pp.ends_at IS NULL OR pp.ends_at >= ?)
                          AND p.status = ?
                          AND p.template_type = ?
                          AND (p.starts_at IS NULL OR p.starts_at <= ?)
                          AND (p.ends_at IS NULL OR p.ends_at >= ?)
                          AND v.status = ?
                          AND (vs.status IS NULL OR LOWER(vs.status) NOT IN ($blockedPlaceholders))
                          $venueFilter
                        ORDER BY pp.priority ASC,
                                 CASE WHEN pp.starts_at IS NULL THEN 1 ELSE 0 END,
                                 pp.starts_at DESC,
                                 pp.created_at DESC,
                                 pp.id DESC
                        LIMIT ?
                        """.trimIndent(),
                    ).use { statement ->
                        var index = 1
                        statement.setString(index++, VenuePromotionMediaType.IMAGE.dbValue)
                        statement.setString(index++, surface.dbValue)
                        statement.setString(index++, PromotionPlacementStatus.ACTIVE.dbValue)
                        statement.setTimestamp(index++, Timestamp.from(now))
                        statement.setTimestamp(index++, Timestamp.from(now))
                        statement.setString(index++, VenuePromotionStatus.ACTIVE.dbValue)
                        statement.setString(index++, VenuePromotionTemplateType.BANNER.dbValue)
                        statement.setTimestamp(index++, Timestamp.from(now))
                        statement.setTimestamp(index++, Timestamp.from(now))
                        statement.setString(index++, VenueStatus.PUBLISHED.dbValue)
                        blockedStatuses.forEach { status -> statement.setString(index++, status) }
                        if (venueId != null) {
                            statement.setLong(index++, venueId)
                        }
                        statement.setInt(index, limit.coerceIn(1, 20))
                        statement.executeQuery().use { rs -> rs.toPlacements() }
                    }
                }
            } catch (_: SQLException) {
                throw DatabaseUnavailableException()
            }
        }
    }

    private suspend fun listByStatusForPlatformManagement(
        status: PromotionPlacementStatus,
        limit: Int,
        now: Instant? = null,
    ): List<PromotionPlacement> {
        val ds = dataSource ?: throw DatabaseUnavailableException()
        val activePeriodFilter =
            if (status == PromotionPlacementStatus.ACTIVE && now != null) {
                "AND (pp.ends_at IS NULL OR pp.ends_at >= ?)"
            } else {
                ""
            }
        return withContext(Dispatchers.IO) {
            try {
                ds.connection.use { connection ->
                    connection.prepareStatement(
                        """
                        SELECT ${placementColumns()}
                        FROM promotion_placements pp
                        JOIN venue_promotions p ON p.id = pp.promotion_id
                        JOIN venues v ON v.id = pp.venue_id
                        LEFT JOIN venue_promotion_media m ON m.id = (
                            SELECT m2.id
                            FROM venue_promotion_media m2
                            WHERE m2.promotion_id = p.id AND m2.media_type = ?
                            ORDER BY m2.sort_order ASC, m2.id ASC
                            LIMIT 1
                        )
                        WHERE pp.status = ?
                        $activePeriodFilter
                        ORDER BY pp.updated_at DESC, pp.id DESC
                        LIMIT ?
                        """.trimIndent(),
                    ).use { statement ->
                        var index = 1
                        statement.setString(index++, VenuePromotionMediaType.IMAGE.dbValue)
                        statement.setString(index++, status.dbValue)
                        if (status == PromotionPlacementStatus.ACTIVE && now != null) {
                            statement.setTimestamp(index++, Timestamp.from(now))
                        }
                        statement.setInt(index, limit.coerceIn(1, 100))
                        statement.executeQuery().use { rs -> rs.toPlacements() }
                    }
                }
            } catch (_: SQLException) {
                throw DatabaseUnavailableException()
            }
        }
    }

    private suspend fun listFinishedForManagement(
        venueId: Long?,
        now: Instant,
        limit: Int,
    ): List<PromotionPlacement> {
        val ds = dataSource ?: throw DatabaseUnavailableException()
        val venueFilter = if (venueId == null) "" else "AND pp.venue_id = ?"
        return withContext(Dispatchers.IO) {
            try {
                ds.connection.use { connection ->
                    connection.prepareStatement(
                        """
                        SELECT ${placementColumns()}
                        FROM promotion_placements pp
                        JOIN venue_promotions p ON p.id = pp.promotion_id
                        JOIN venues v ON v.id = pp.venue_id
                        LEFT JOIN venue_promotion_media m ON m.id = (
                            SELECT m2.id
                            FROM venue_promotion_media m2
                            WHERE m2.promotion_id = p.id AND m2.media_type = ?
                            ORDER BY m2.sort_order ASC, m2.id ASC
                            LIMIT 1
                        )
                        WHERE (
                            pp.status IN (?, ?, ?)
                            OR (pp.status = ? AND pp.ends_at IS NOT NULL AND pp.ends_at < ?)
                        )
                        $venueFilter
                        ORDER BY pp.updated_at DESC, pp.id DESC
                        LIMIT ?
                        """.trimIndent(),
                    ).use { statement ->
                        var index = 1
                        statement.setString(index++, VenuePromotionMediaType.IMAGE.dbValue)
                        statement.setString(index++, PromotionPlacementStatus.ARCHIVED.dbValue)
                        statement.setString(index++, PromotionPlacementStatus.REJECTED.dbValue)
                        statement.setString(index++, PromotionPlacementStatus.PAUSED.dbValue)
                        statement.setString(index++, PromotionPlacementStatus.ACTIVE.dbValue)
                        statement.setTimestamp(index++, Timestamp.from(now))
                        if (venueId != null) {
                            statement.setLong(index++, venueId)
                        }
                        statement.setInt(index, limit.coerceIn(1, 100))
                        statement.executeQuery().use { rs -> rs.toPlacements() }
                    }
                }
            } catch (_: SQLException) {
                throw DatabaseUnavailableException()
            }
        }
    }

    private fun isBannerPromotionForVenue(
        connection: Connection,
        venueId: Long,
        promotionId: Long,
    ): Boolean =
        connection.prepareStatement(
            """
            SELECT 1
            FROM venue_promotions
            WHERE venue_id = ?
              AND id = ?
              AND template_type = ?
            LIMIT 1
            """.trimIndent(),
        ).use { statement ->
            statement.setLong(1, venueId)
            statement.setLong(2, promotionId)
            statement.setString(3, VenuePromotionTemplateType.BANNER.dbValue)
            statement.executeQuery().use { rs -> rs.next() }
        }

    private fun selectPlacement(
        connection: Connection,
        id: Long,
    ): PromotionPlacement? =
        connection.prepareStatement(
            """
            SELECT ${placementColumns()}
            FROM promotion_placements pp
            JOIN venue_promotions p ON p.id = pp.promotion_id
            JOIN venues v ON v.id = pp.venue_id
            LEFT JOIN venue_promotion_media m ON m.id = (
                SELECT m2.id
                FROM venue_promotion_media m2
                WHERE m2.promotion_id = p.id AND m2.media_type = ?
                ORDER BY m2.sort_order ASC, m2.id ASC
                LIMIT 1
            )
            WHERE pp.id = ?
            """.trimIndent(),
        ).use { statement ->
            statement.setString(1, VenuePromotionMediaType.IMAGE.dbValue)
            statement.setLong(2, id)
            statement.executeQuery().use { rs -> if (rs.next()) rs.toPlacement() else null }
        }

    private fun placementColumns(): String =
        """
        pp.id,
        pp.promotion_id,
        pp.venue_id,
        v.name AS venue_name,
        v.city AS venue_city,
        v.address AS venue_address,
        p.title AS promotion_title,
        p.description AS promotion_description,
        p.template_type AS promotion_template_type,
        p.status AS promotion_status,
        pp.surface,
        pp.status,
        pp.starts_at,
        pp.ends_at,
        pp.priority,
        pp.requested_by_user_id,
        pp.approved_by_user_id,
        pp.approved_at,
        pp.rejected_reason,
        pp.created_at,
        pp.updated_at,
        m.telegram_file_id AS primary_image_file_id
        """.trimIndent()

    private fun ResultSet.toPlacements(): List<PromotionPlacement> {
        val placements = mutableListOf<PromotionPlacement>()
        while (next()) {
            toPlacement()?.let { placements += it }
        }
        return placements
    }

    private fun ResultSet.toPlacement(): PromotionPlacement? {
        val surface = PromotionPlacementSurface.fromDb(getString("surface")) ?: return null
        val status = PromotionPlacementStatus.fromDb(getString("status")) ?: return null
        val promotionStatus = VenuePromotionStatus.fromDb(getString("promotion_status")) ?: return null
        val templateType = VenuePromotionTemplateType.fromDb(getString("promotion_template_type")) ?: return null
        val approvedUserId = getLong("approved_by_user_id").let { if (wasNull()) null else it }
        return PromotionPlacement(
            id = getLong("id"),
            promotionId = getLong("promotion_id"),
            venueId = getLong("venue_id"),
            venueName = getString("venue_name"),
            venueCity = getString("venue_city"),
            venueAddress = getString("venue_address"),
            promotionTitle = getString("promotion_title"),
            promotionDescription = getString("promotion_description"),
            promotionTemplateType = templateType,
            promotionStatus = promotionStatus,
            surface = surface,
            status = status,
            startsAt = getTimestamp("starts_at")?.toInstant(),
            endsAt = getTimestamp("ends_at")?.toInstant(),
            priority = getInt("priority"),
            requestedByUserId = getLong("requested_by_user_id"),
            approvedByUserId = approvedUserId,
            approvedAt = getTimestamp("approved_at")?.toInstant(),
            rejectedReason = getString("rejected_reason"),
            createdAt = getTimestamp("created_at").toInstant(),
            updatedAt = getTimestamp("updated_at").toInstant(),
            primaryImageFileId = getString("primary_image_file_id"),
        )
    }

    private fun validatePeriod(
        startsAt: Instant?,
        endsAt: Instant?,
    ) {
        require(startsAt == null || endsAt == null || startsAt < endsAt) {
            "placement start must be before end"
        }
    }

    private fun setInstant(
        statement: java.sql.PreparedStatement,
        index: Int,
        value: Instant?,
    ) {
        if (value == null) {
            statement.setObject(index, null)
        } else {
            statement.setTimestamp(index, Timestamp.from(value))
        }
    }
}
