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
import javax.sql.DataSource

data class PromotionVenuePlacement(
    val id: Long,
    val venueId: Long,
    val venueName: String,
    val venueCity: String?,
    val venueAddress: String?,
    val surface: PromotionPlacementSurface,
    val status: PromotionPlacementStatus,
    val startsAt: Instant?,
    val endsAt: Instant?,
    val priority: Int,
    val requestedByUserId: Long,
    val requesterUsername: String?,
    val requesterFirstName: String?,
    val requesterLastName: String?,
    val requesterVenueRole: String?,
    val approvedByUserId: Long?,
    val approvedAt: Instant?,
    val rejectedReason: String?,
    val createdAt: Instant,
    val updatedAt: Instant,
    val activePromotionsCount: Int,
)

class PromotionVenuePlacementRepository(private val dataSource: DataSource?) {
    suspend fun createRequest(
        venueId: Long,
        requestedByUserId: Long,
    ): PromotionVenuePlacement? {
        val ds = dataSource ?: throw DatabaseUnavailableException()
        return withContext(Dispatchers.IO) {
            try {
                ds.connection.use { connection ->
                    if (!venueExists(connection, venueId)) {
                        return@withContext null
                    }
                    val id =
                        connection.prepareStatement(
                            """
                            INSERT INTO promotion_venue_placements (
                                venue_id, surface, status, requested_by_user_id
                            )
                            VALUES (?, ?, ?, ?)
                            """.trimIndent(),
                            Statement.RETURN_GENERATED_KEYS,
                        ).use { statement ->
                            statement.setLong(1, venueId)
                            statement.setString(2, PromotionPlacementSurface.GLOBAL_PROMOTIONS_TOP.dbValue)
                            statement.setString(3, PromotionPlacementStatus.PENDING.dbValue)
                            statement.setLong(4, requestedByUserId)
                            statement.executeUpdate()
                            statement.generatedKeys.use { keys ->
                                if (!keys.next()) throw SQLException("No generated key for promotion venue placement")
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

    suspend fun listPendingForPlatform(limit: Int = 20): List<PromotionVenuePlacement> =
        listByStatusForPlatform(PromotionPlacementStatus.PENDING, limit)

    suspend fun listActiveForPlatformManagement(
        limit: Int = 20,
        now: Instant = Instant.now(),
    ): List<PromotionVenuePlacement> = listByStatusForPlatform(PromotionPlacementStatus.ACTIVE, limit, now)

    suspend fun listFinishedForPlatformManagement(
        limit: Int = 20,
        now: Instant = Instant.now(),
    ): List<PromotionVenuePlacement> = listFinishedForManagement(venueId = null, now = now, limit = limit)

    suspend fun listForVenueManagement(
        venueId: Long,
        status: PromotionPlacementStatus,
        limit: Int = 20,
        now: Instant = Instant.now(),
    ): List<PromotionVenuePlacement> {
        val ds = dataSource ?: throw DatabaseUnavailableException()
        val activePeriodFilter =
            if (status == PromotionPlacementStatus.ACTIVE) {
                "AND (pvp.ends_at IS NULL OR pvp.ends_at >= ?)"
            } else {
                ""
            }
        return withContext(Dispatchers.IO) {
            try {
                ds.connection.use { connection ->
                    connection.prepareStatement(
                        """
                        SELECT ${placementColumns()}
                        FROM promotion_venue_placements pvp
                        JOIN venues v ON v.id = pvp.venue_id
                        WHERE pvp.venue_id = ?
                          AND pvp.status = ?
                          $activePeriodFilter
                        ORDER BY pvp.updated_at DESC, pvp.id DESC
                        LIMIT ?
                        """.trimIndent(),
                    ).use { statement ->
                        var index = 1
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
    ): List<PromotionVenuePlacement> = listFinishedForManagement(venueId = venueId, now = now, limit = limit)

    suspend fun getForVenueManagement(
        venueId: Long,
        id: Long,
    ): PromotionVenuePlacement? {
        val ds = dataSource ?: throw DatabaseUnavailableException()
        return withContext(Dispatchers.IO) {
            try {
                ds.connection.use { connection ->
                    connection.prepareStatement(
                        """
                        SELECT ${placementColumns()}
                        FROM promotion_venue_placements pvp
                        JOIN venues v ON v.id = pvp.venue_id
                        WHERE pvp.venue_id = ?
                          AND pvp.id = ?
                        """.trimIndent(),
                    ).use { statement ->
                        statement.setLong(1, venueId)
                        statement.setLong(2, id)
                        statement.executeQuery().use { rs -> if (rs.next()) rs.toPlacement() else null }
                    }
                }
            } catch (_: SQLException) {
                throw DatabaseUnavailableException()
            }
        }
    }

    suspend fun getForPlatformManagement(id: Long): PromotionVenuePlacement? {
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
        startsAt: Instant,
        endsAt: Instant,
        priority: Int = 100,
    ): PromotionVenuePlacement? {
        validatePeriod(startsAt, endsAt)
        val ds = dataSource ?: throw DatabaseUnavailableException()
        return withContext(Dispatchers.IO) {
            try {
                ds.connection.use { connection ->
                    val updated =
                        connection.prepareStatement(
                            """
                            UPDATE promotion_venue_placements
                            SET status = ?,
                                starts_at = ?,
                                ends_at = ?,
                                priority = ?,
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
                            statement.setInt(4, priority)
                            statement.setLong(5, platformUserId)
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

    suspend fun reject(
        id: Long,
        platformUserId: Long,
        reason: String? = null,
    ): PromotionVenuePlacement? =
        updateStatus(
            id = id,
            status = PromotionPlacementStatus.REJECTED,
            rejectedReason = reason?.trim()?.takeIf { it.isNotBlank() },
        )

    suspend fun pause(id: Long): PromotionVenuePlacement? =
        updateStatus(id = id, status = PromotionPlacementStatus.PAUSED)

    suspend fun archive(id: Long): PromotionVenuePlacement? =
        updateStatus(id = id, status = PromotionPlacementStatus.ARCHIVED)

    suspend fun listActiveForGlobalFeed(
        now: Instant = Instant.now(),
        limit: Int = 5,
    ): List<PromotionVenuePlacement> {
        val ds = dataSource ?: throw DatabaseUnavailableException()
        val blockedStatuses = SubscriptionStatus.blockedDbValues
        val blockedPlaceholders = blockedStatuses.joinToString(",") { "?" }
        return withContext(Dispatchers.IO) {
            try {
                ds.connection.use { connection ->
                    connection.prepareStatement(
                        """
                        SELECT ${placementColumns()}
                        FROM promotion_venue_placements pvp
                        JOIN venues v ON v.id = pvp.venue_id
                        LEFT JOIN venue_subscriptions vs ON vs.venue_id = v.id
                        WHERE pvp.surface = ?
                          AND pvp.status = ?
                          AND (pvp.starts_at IS NULL OR pvp.starts_at <= ?)
                          AND (pvp.ends_at IS NULL OR pvp.ends_at >= ?)
                          AND v.status = ?
                          AND (vs.status IS NULL OR LOWER(vs.status) NOT IN ($blockedPlaceholders))
                          AND EXISTS (
                              SELECT 1
                              FROM venue_promotions p
                              WHERE p.venue_id = pvp.venue_id
                                AND p.status = ?
                                AND (p.starts_at IS NULL OR p.starts_at <= ?)
                                AND (p.ends_at IS NULL OR p.ends_at >= ?)
                          )
                        ORDER BY pvp.priority ASC,
                                 CASE WHEN pvp.starts_at IS NULL THEN 1 ELSE 0 END,
                                 pvp.starts_at DESC,
                                 pvp.created_at DESC,
                                 pvp.id DESC
                        LIMIT ?
                        """.trimIndent(),
                    ).use { statement ->
                        var index = 1
                        statement.setString(index++, PromotionPlacementSurface.GLOBAL_PROMOTIONS_TOP.dbValue)
                        statement.setString(index++, PromotionPlacementStatus.ACTIVE.dbValue)
                        statement.setTimestamp(index++, Timestamp.from(now))
                        statement.setTimestamp(index++, Timestamp.from(now))
                        statement.setString(index++, VenueStatus.PUBLISHED.dbValue)
                        blockedStatuses.forEach { status -> statement.setString(index++, status) }
                        statement.setString(index++, VenuePromotionStatus.ACTIVE.dbValue)
                        statement.setTimestamp(index++, Timestamp.from(now))
                        statement.setTimestamp(index++, Timestamp.from(now))
                        statement.setInt(index, limit.coerceIn(1, 5))
                        statement.executeQuery().use { rs -> rs.toPlacements() }
                    }
                }
            } catch (_: SQLException) {
                throw DatabaseUnavailableException()
            }
        }
    }

    private suspend fun listByStatusForPlatform(
        status: PromotionPlacementStatus,
        limit: Int,
        now: Instant? = null,
    ): List<PromotionVenuePlacement> {
        val ds = dataSource ?: throw DatabaseUnavailableException()
        val activePeriodFilter =
            if (status == PromotionPlacementStatus.ACTIVE && now != null) {
                "AND (pvp.ends_at IS NULL OR pvp.ends_at >= ?)"
            } else {
                ""
            }
        return withContext(Dispatchers.IO) {
            try {
                ds.connection.use { connection ->
                    connection.prepareStatement(
                        """
                        SELECT ${placementColumns()}
                        FROM promotion_venue_placements pvp
                        JOIN venues v ON v.id = pvp.venue_id
                        WHERE pvp.surface = ?
                          AND pvp.status = ?
                        $activePeriodFilter
                        ORDER BY pvp.updated_at DESC, pvp.id DESC
                        LIMIT ?
                        """.trimIndent(),
                    ).use { statement ->
                        var index = 1
                        statement.setString(index++, PromotionPlacementSurface.GLOBAL_PROMOTIONS_TOP.dbValue)
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
    ): List<PromotionVenuePlacement> {
        val ds = dataSource ?: throw DatabaseUnavailableException()
        val venueFilter = if (venueId == null) "" else "AND pvp.venue_id = ?"
        return withContext(Dispatchers.IO) {
            try {
                ds.connection.use { connection ->
                    connection.prepareStatement(
                        """
                        SELECT ${placementColumns()}
                        FROM promotion_venue_placements pvp
                        JOIN venues v ON v.id = pvp.venue_id
                        WHERE (
                            pvp.status IN (?, ?, ?)
                            OR (pvp.status = ? AND pvp.ends_at IS NOT NULL AND pvp.ends_at < ?)
                        )
                        $venueFilter
                        ORDER BY pvp.updated_at DESC, pvp.id DESC
                        LIMIT ?
                        """.trimIndent(),
                    ).use { statement ->
                        var index = 1
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

    private suspend fun updateStatus(
        id: Long,
        status: PromotionPlacementStatus,
        platformUserId: Long? = null,
        rejectedReason: String? = null,
    ): PromotionVenuePlacement? {
        val ds = dataSource ?: throw DatabaseUnavailableException()
        return withContext(Dispatchers.IO) {
            try {
                ds.connection.use { connection ->
                    val updated =
                        connection.prepareStatement(
                            """
                            UPDATE promotion_venue_placements
                            SET status = ?,
                                approved_by_user_id = CASE WHEN ? IS NULL THEN approved_by_user_id ELSE ? END,
                                rejected_reason = ?,
                                updated_at = CURRENT_TIMESTAMP
                            WHERE id = ?
                            """.trimIndent(),
                        ).use { statement ->
                            statement.setString(1, status.dbValue)
                            if (platformUserId == null) {
                                statement.setObject(2, null)
                                statement.setObject(3, null)
                            } else {
                                statement.setLong(2, platformUserId)
                                statement.setLong(3, platformUserId)
                            }
                            statement.setString(4, rejectedReason)
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

    private fun venueExists(
        connection: Connection,
        venueId: Long,
    ): Boolean =
        connection.prepareStatement(
            """
            SELECT 1
            FROM venues
            WHERE id = ?
            LIMIT 1
            """.trimIndent(),
        ).use { statement ->
            statement.setLong(1, venueId)
            statement.executeQuery().use { rs -> rs.next() }
        }

    private fun selectPlacement(
        connection: Connection,
        id: Long,
    ): PromotionVenuePlacement? =
        connection.prepareStatement(
            """
            SELECT ${placementColumns()}
            FROM promotion_venue_placements pvp
            JOIN venues v ON v.id = pvp.venue_id
            WHERE pvp.id = ?
            """.trimIndent(),
        ).use { statement ->
            statement.setLong(1, id)
            statement.executeQuery().use { rs -> if (rs.next()) rs.toPlacement() else null }
        }

    private fun placementColumns(): String =
        """
        pvp.id,
        pvp.venue_id,
        v.name AS venue_name,
        v.city AS venue_city,
        v.address AS venue_address,
        pvp.surface,
        pvp.status AS placement_status,
        pvp.starts_at,
        pvp.ends_at,
        pvp.priority,
        pvp.requested_by_user_id,
        (
            SELECT u.username
            FROM users u
            WHERE u.telegram_user_id = pvp.requested_by_user_id
        ) AS requester_username,
        (
            SELECT u.first_name
            FROM users u
            WHERE u.telegram_user_id = pvp.requested_by_user_id
        ) AS requester_first_name,
        (
            SELECT u.last_name
            FROM users u
            WHERE u.telegram_user_id = pvp.requested_by_user_id
        ) AS requester_last_name,
        (
            SELECT vm.role
            FROM venue_members vm
            WHERE vm.venue_id = pvp.venue_id
              AND vm.user_id = pvp.requested_by_user_id
            LIMIT 1
        ) AS requester_venue_role,
        pvp.approved_by_user_id,
        pvp.approved_at,
        pvp.rejected_reason,
        pvp.created_at,
        pvp.updated_at,
        (
            SELECT COUNT(*)
            FROM venue_promotions p
            WHERE p.venue_id = v.id
              AND p.status = 'ACTIVE'
              AND (p.starts_at IS NULL OR p.starts_at <= CURRENT_TIMESTAMP)
              AND (p.ends_at IS NULL OR p.ends_at >= CURRENT_TIMESTAMP)
        ) AS active_promotions_count
        """.trimIndent()

    private fun ResultSet.toPlacements(): List<PromotionVenuePlacement> {
        val placements = mutableListOf<PromotionVenuePlacement>()
        while (next()) {
            toPlacement()?.let { placements += it }
        }
        return placements
    }

    private fun ResultSet.toPlacement(): PromotionVenuePlacement? {
        val surface = PromotionPlacementSurface.fromDb(getString("surface")) ?: return null
        val status = PromotionPlacementStatus.fromDb(getString("placement_status")) ?: return null
        val approvedUserId = getLong("approved_by_user_id").let { if (wasNull()) null else it }
        return PromotionVenuePlacement(
            id = getLong("id"),
            venueId = getLong("venue_id"),
            venueName = getString("venue_name"),
            venueCity = getString("venue_city"),
            venueAddress = getString("venue_address"),
            surface = surface,
            status = status,
            startsAt = getTimestamp("starts_at")?.toInstant(),
            endsAt = getTimestamp("ends_at")?.toInstant(),
            priority = getInt("priority"),
            requestedByUserId = getLong("requested_by_user_id"),
            requesterUsername = getString("requester_username"),
            requesterFirstName = getString("requester_first_name"),
            requesterLastName = getString("requester_last_name"),
            requesterVenueRole = getString("requester_venue_role"),
            approvedByUserId = approvedUserId,
            approvedAt = getTimestamp("approved_at")?.toInstant(),
            rejectedReason = getString("rejected_reason"),
            createdAt = getTimestamp("created_at").toInstant(),
            updatedAt = getTimestamp("updated_at").toInstant(),
            activePromotionsCount = getInt("active_promotions_count"),
        )
    }

    private fun validatePeriod(
        startsAt: Instant,
        endsAt: Instant,
    ) {
        require(startsAt < endsAt) { "placement start must be before end" }
    }
}
