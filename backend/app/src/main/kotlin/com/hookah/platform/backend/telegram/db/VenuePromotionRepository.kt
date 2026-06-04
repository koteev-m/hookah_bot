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

enum class VenuePromotionStatus(val dbValue: String) {
    DRAFT("DRAFT"),
    ACTIVE("ACTIVE"),
    PAUSED("PAUSED"),
    ARCHIVED("ARCHIVED"),
    ;

    companion object {
        fun fromDb(value: String?): VenuePromotionStatus? {
            val normalized = value?.trim()?.uppercase(Locale.ROOT) ?: return null
            return entries.firstOrNull { it.dbValue == normalized }
        }
    }
}

enum class VenuePromotionTemplateType(val dbValue: String) {
    TEXT_ONLY("TEXT_ONLY"),
    BANNER("BANNER"),
    HAPPY_HOURS_PERCENT("HAPPY_HOURS_PERCENT"),
    BIRTHDAY_DISCOUNT("BIRTHDAY_DISCOUNT"),
    COMBO("COMBO"),
    GIFT_WITH_ITEM("GIFT_WITH_ITEM"),
    NEW_GUEST_OFFER("NEW_GUEST_OFFER"),
    PROMO_CODE("PROMO_CODE"),
    LOYALTY_NTH_HOOKAH("LOYALTY_NTH_HOOKAH"),
    ;

    companion object {
        fun fromDb(value: String?): VenuePromotionTemplateType? {
            val normalized = value?.trim()?.uppercase(Locale.ROOT) ?: return null
            return entries.firstOrNull { it.dbValue == normalized }
        }
    }
}

data class VenuePromotion(
    val id: Long,
    val venueId: Long,
    val venueName: String,
    val title: String,
    val description: String,
    val terms: String?,
    val startsAt: Instant?,
    val endsAt: Instant?,
    val status: VenuePromotionStatus,
    val createdByUserId: Long,
    val createdAt: Instant,
    val updatedAt: Instant,
    val templateType: VenuePromotionTemplateType = VenuePromotionTemplateType.TEXT_ONLY,
)

data class PromotionPreview(
    val id: Long,
    val title: String,
    val templateType: VenuePromotionTemplateType,
    val startsAt: Instant?,
    val endsAt: Instant?,
)

data class PromotionVenueFeedItem(
    val venueId: Long,
    val venueName: String,
    val city: String?,
    val address: String?,
    val promotionsCount: Int,
    val previewPromotions: List<PromotionPreview>,
)

class VenuePromotionRepository(private val dataSource: DataSource?) {
    suspend fun createPromotion(
        venueId: Long,
        title: String,
        description: String,
        terms: String?,
        startsAt: Instant? = null,
        endsAt: Instant? = null,
        templateType: VenuePromotionTemplateType = VenuePromotionTemplateType.TEXT_ONLY,
        createdByUserId: Long,
    ): VenuePromotion {
        val ds = dataSource ?: throw DatabaseUnavailableException()
        val normalizedTitle = requireText(title, "title")
        val normalizedDescription = requireText(description, "description")
        val normalizedTerms = terms?.trim()?.takeIf { it.isNotBlank() }
        return withContext(Dispatchers.IO) {
            try {
                ds.connection.use { connection ->
                    val id =
                        connection.prepareStatement(
                            """
                            INSERT INTO venue_promotions (
                                venue_id, title, description, terms, starts_at, ends_at, status, template_type, created_by_user_id
                            )
                            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                            """.trimIndent(),
                            Statement.RETURN_GENERATED_KEYS,
                        ).use { statement ->
                            statement.setLong(1, venueId)
                            statement.setString(2, normalizedTitle)
                            statement.setString(3, normalizedDescription)
                            statement.setString(4, normalizedTerms)
                            setInstant(statement, 5, startsAt)
                            setInstant(statement, 6, endsAt)
                            statement.setString(7, VenuePromotionStatus.DRAFT.dbValue)
                            statement.setString(8, templateType.dbValue)
                            statement.setLong(9, createdByUserId)
                            statement.executeUpdate()
                            statement.generatedKeys.use { keys ->
                                if (!keys.next()) {
                                    throw SQLException("No generated key for venue promotion")
                                }
                                keys.getLong(1)
                            }
                        }
                    selectPromotion(connection, venueId = venueId, promotionId = id)
                        ?: throw SQLException("Created venue promotion not found")
                }
            } catch (e: SQLException) {
                throw DatabaseUnavailableException()
            }
        }
    }

    suspend fun updatePromotion(
        venueId: Long,
        promotionId: Long,
        title: String? = null,
        description: String? = null,
        terms: String? = null,
        clearTerms: Boolean = false,
        startsAt: Instant? = null,
        endsAt: Instant? = null,
    ): VenuePromotion? {
        val ds = dataSource ?: throw DatabaseUnavailableException()
        val updates = mutableListOf<String>()
        val values = mutableListOf<Any?>()
        title?.let {
            updates += "title = ?"
            values += requireText(it, "title")
        }
        description?.let {
            updates += "description = ?"
            values += requireText(it, "description")
        }
        if (clearTerms) {
            updates += "terms = ?"
            values += null
        } else if (terms != null) {
            updates += "terms = ?"
            values += terms.trim().takeIf { it.isNotBlank() }
        }
        startsAt?.let {
            updates += "starts_at = ?"
            values += it
        }
        endsAt?.let {
            updates += "ends_at = ?"
            values += it
        }
        if (updates.isEmpty()) {
            return getPromotionForManagement(venueId, promotionId)
        }
        updates += "updated_at = CURRENT_TIMESTAMP"
        return withContext(Dispatchers.IO) {
            try {
                ds.connection.use { connection ->
                    val updated =
                        connection.prepareStatement(
                            """
                            UPDATE venue_promotions
                            SET ${updates.joinToString(", ")}
                            WHERE venue_id = ? AND id = ?
                            """.trimIndent(),
                        ).use { statement ->
                            values.forEachIndexed { index, value ->
                                setStatementValue(statement, index + 1, value)
                            }
                            statement.setLong(values.size + 1, venueId)
                            statement.setLong(values.size + 2, promotionId)
                            statement.executeUpdate()
                        }
                    if (updated == 0) {
                        null
                    } else {
                        selectPromotion(connection, venueId = venueId, promotionId = promotionId)
                    }
                }
            } catch (e: SQLException) {
                throw DatabaseUnavailableException()
            }
        }
    }

    suspend fun setPromotionStatus(
        venueId: Long,
        promotionId: Long,
        status: VenuePromotionStatus,
    ): VenuePromotion? {
        val ds = dataSource ?: throw DatabaseUnavailableException()
        return withContext(Dispatchers.IO) {
            try {
                ds.connection.use { connection ->
                    val updated =
                        connection.prepareStatement(
                            """
                            UPDATE venue_promotions
                            SET status = ?,
                                updated_at = CURRENT_TIMESTAMP
                            WHERE venue_id = ? AND id = ?
                            """.trimIndent(),
                        ).use { statement ->
                            statement.setString(1, status.dbValue)
                            statement.setLong(2, venueId)
                            statement.setLong(3, promotionId)
                            statement.executeUpdate()
                        }
                    if (updated == 0) {
                        null
                    } else {
                        selectPromotion(connection, venueId = venueId, promotionId = promotionId)
                    }
                }
            } catch (e: SQLException) {
                throw DatabaseUnavailableException()
            }
        }
    }

    suspend fun archivePromotion(
        venueId: Long,
        promotionId: Long,
    ): VenuePromotion? {
        val ds = dataSource ?: throw DatabaseUnavailableException()
        return withContext(Dispatchers.IO) {
            try {
                ds.connection.use { connection ->
                    connection.autoCommit = false
                    try {
                        val updated =
                            connection.prepareStatement(
                                """
                                UPDATE venue_promotions
                                SET status = ?,
                                    updated_at = CURRENT_TIMESTAMP
                                WHERE venue_id = ? AND id = ?
                                """.trimIndent(),
                            ).use { statement ->
                                statement.setString(1, VenuePromotionStatus.ARCHIVED.dbValue)
                                statement.setLong(2, venueId)
                                statement.setLong(3, promotionId)
                                statement.executeUpdate()
                            }
                        val result =
                            if (updated == 0) {
                                null
                            } else {
                                connection.prepareStatement(
                                    """
                                    UPDATE promotion_rules
                                    SET status = ?,
                                        updated_at = CURRENT_TIMESTAMP
                                    WHERE venue_id = ? AND promotion_id = ?
                                    """.trimIndent(),
                                ).use { statement ->
                                    statement.setString(1, VenuePromotionStatus.ARCHIVED.dbValue)
                                    statement.setLong(2, venueId)
                                    statement.setLong(3, promotionId)
                                    statement.executeUpdate()
                                }
                                selectPromotion(connection, venueId = venueId, promotionId = promotionId)
                            }
                        connection.commit()
                        result
                    } catch (e: Exception) {
                        connection.rollback()
                        throw e
                    } finally {
                        connection.autoCommit = true
                    }
                }
            } catch (e: SQLException) {
                throw DatabaseUnavailableException()
            }
        }
    }

    suspend fun listVenuePromotionsForManagement(
        venueId: Long,
        limit: Int = 50,
    ): List<VenuePromotion> {
        val ds = dataSource ?: throw DatabaseUnavailableException()
        return withContext(Dispatchers.IO) {
            try {
                ds.connection.use { connection ->
                    connection.prepareStatement(
                        """
                        SELECT ${promotionColumns()}
                        FROM venue_promotions p
                        JOIN venues v ON v.id = p.venue_id
                        WHERE p.venue_id = ?
                          AND p.status <> ?
                          AND p.template_type <> ?
                        ORDER BY p.updated_at DESC, p.id DESC
                        LIMIT ?
                        """.trimIndent(),
                    ).use { statement ->
                        statement.setLong(1, venueId)
                        statement.setString(2, VenuePromotionStatus.ARCHIVED.dbValue)
                        statement.setString(3, VenuePromotionTemplateType.LOYALTY_NTH_HOOKAH.dbValue)
                        statement.setInt(4, limit.coerceIn(1, 100))
                        statement.executeQuery().use { rs -> rs.toPromotions() }
                    }
                }
            } catch (e: SQLException) {
                throw DatabaseUnavailableException()
            }
        }
    }

    suspend fun listArchivedPromotionsForManagement(
        venueId: Long,
        limit: Int = 50,
    ): List<VenuePromotion> {
        val ds = dataSource ?: throw DatabaseUnavailableException()
        return withContext(Dispatchers.IO) {
            try {
                ds.connection.use { connection ->
                    connection.prepareStatement(
                        """
                        SELECT ${promotionColumns()}
                        FROM venue_promotions p
                        JOIN venues v ON v.id = p.venue_id
                        WHERE p.venue_id = ?
                          AND p.status = ?
                          AND p.template_type <> ?
                        ORDER BY p.updated_at DESC, p.id DESC
                        LIMIT ?
                        """.trimIndent(),
                    ).use { statement ->
                        statement.setLong(1, venueId)
                        statement.setString(2, VenuePromotionStatus.ARCHIVED.dbValue)
                        statement.setString(3, VenuePromotionTemplateType.LOYALTY_NTH_HOOKAH.dbValue)
                        statement.setInt(4, limit.coerceIn(1, 100))
                        statement.executeQuery().use { rs -> rs.toPromotions() }
                    }
                }
            } catch (e: SQLException) {
                throw DatabaseUnavailableException()
            }
        }
    }

    suspend fun getPromotionForManagement(
        venueId: Long,
        promotionId: Long,
    ): VenuePromotion? {
        val ds = dataSource ?: throw DatabaseUnavailableException()
        return withContext(Dispatchers.IO) {
            try {
                ds.connection.use { connection -> selectPromotion(connection, venueId, promotionId) }
            } catch (e: SQLException) {
                throw DatabaseUnavailableException()
            }
        }
    }

    suspend fun listActivePromotionsForGuest(
        limit: Int = 20,
        now: Instant = Instant.now(),
    ): List<VenuePromotion> {
        val ds = dataSource ?: throw DatabaseUnavailableException()
        val blockedStatuses = SubscriptionStatus.blockedDbValues
        val blockedPlaceholders = blockedStatuses.joinToString(",") { "?" }
        return withContext(Dispatchers.IO) {
            try {
                ds.connection.use { connection ->
                    connection.prepareStatement(
                        """
                        SELECT ${promotionColumns()}
                        FROM venue_promotions p
                        JOIN venues v ON v.id = p.venue_id
                        LEFT JOIN venue_subscriptions vs ON vs.venue_id = v.id
                        WHERE p.status = ?
                          AND (p.starts_at IS NULL OR p.starts_at <= ?)
                          AND (p.ends_at IS NULL OR p.ends_at >= ?)
                          AND v.status = ?
                          AND (vs.status IS NULL OR LOWER(vs.status) NOT IN ($blockedPlaceholders))
                        ORDER BY COALESCE(p.starts_at, p.created_at) DESC, p.updated_at DESC, p.id DESC
                        LIMIT ?
                        """.trimIndent(),
                    ).use { statement ->
                        var index = 1
                        statement.setString(index++, VenuePromotionStatus.ACTIVE.dbValue)
                        statement.setTimestamp(index++, Timestamp.from(now))
                        statement.setTimestamp(index++, Timestamp.from(now))
                        statement.setString(index++, VenueStatus.PUBLISHED.dbValue)
                        blockedStatuses.forEach { status -> statement.setString(index++, status) }
                        statement.setInt(index, limit.coerceIn(1, 50))
                        statement.executeQuery().use { rs -> rs.toPromotions() }
                    }
                }
            } catch (e: SQLException) {
                throw DatabaseUnavailableException()
            }
        }
    }

    suspend fun listPromotionVenuesForGuest(
        limit: Int = 5,
        offset: Int = 0,
        now: Instant = Instant.now(),
        excludePromotionIds: Set<Long> = emptySet(),
        excludeVenueIds: Set<Long> = emptySet(),
    ): List<PromotionVenueFeedItem> {
        val ds = dataSource ?: throw DatabaseUnavailableException()
        val blockedStatuses = SubscriptionStatus.blockedDbValues
        val blockedPlaceholders = blockedStatuses.joinToString(",") { "?" }
        val excludedIds = excludePromotionIds.filter { it > 0L }.distinct().sorted()
        val excludedVenueIds = excludeVenueIds.filter { it > 0L }.distinct().sorted()
        val excludedPlaceholders = excludedIds.joinToString(",") { "?" }
        val excludedVenuePlaceholders = excludedVenueIds.joinToString(",") { "?" }
        val excludedClause =
            if (excludedIds.isEmpty()) {
                ""
            } else {
                "AND p.id NOT IN ($excludedPlaceholders)"
            }
        val excludedVenueClause =
            if (excludedVenueIds.isEmpty()) {
                ""
            } else {
                "AND v.id NOT IN ($excludedVenuePlaceholders)"
            }
        return withContext(Dispatchers.IO) {
            try {
                ds.connection.use { connection ->
                    connection.prepareStatement(
                        """
                        WITH active_promotions AS (
                            SELECT
                                p.id,
                                p.venue_id,
                                v.name AS venue_name,
                                v.city,
                                v.address,
                                p.title,
                                p.template_type,
                                p.starts_at,
                                p.ends_at,
                                p.created_at,
                                p.updated_at
                            FROM venue_promotions p
                            JOIN venues v ON v.id = p.venue_id
                            LEFT JOIN venue_subscriptions vs ON vs.venue_id = v.id
                            WHERE p.status = ?
                              AND (p.starts_at IS NULL OR p.starts_at <= ?)
                              AND (p.ends_at IS NULL OR p.ends_at >= ?)
                              AND v.status = ?
                              AND (vs.status IS NULL OR LOWER(vs.status) NOT IN ($blockedPlaceholders))
                              $excludedClause
                              $excludedVenueClause
                        ),
                        venue_page AS (
                            SELECT
                                venue_id,
                                venue_name,
                                city,
                                address,
                                COUNT(*) AS promotions_count,
                                MAX(COALESCE(starts_at, created_at)) AS latest_promotion_at,
                                MAX(updated_at) AS latest_updated_at
                            FROM active_promotions
                            GROUP BY venue_id, venue_name, city, address
                            ORDER BY latest_promotion_at DESC, latest_updated_at DESC, venue_id DESC
                            LIMIT ? OFFSET ?
                        ),
                        ranked_promotions AS (
                            SELECT
                                ap.*,
                                ROW_NUMBER() OVER (
                                    PARTITION BY ap.venue_id
                                    ORDER BY COALESCE(ap.starts_at, ap.created_at) DESC, ap.updated_at DESC, ap.id DESC
                                ) AS rn
                            FROM active_promotions ap
                            JOIN venue_page vp ON vp.venue_id = ap.venue_id
                        )
                        SELECT
                            vp.venue_id,
                            vp.venue_name,
                            vp.city,
                            vp.address,
                            vp.promotions_count,
                            vp.latest_promotion_at,
                            vp.latest_updated_at,
                            rp.id AS promotion_id,
                            rp.title AS promotion_title,
                            rp.template_type AS promotion_template_type,
                            rp.starts_at AS promotion_starts_at,
                            rp.ends_at AS promotion_ends_at,
                            rp.rn AS promotion_rank
                        FROM venue_page vp
                        LEFT JOIN ranked_promotions rp ON rp.venue_id = vp.venue_id AND rp.rn <= 3
                        ORDER BY vp.latest_promotion_at DESC, vp.latest_updated_at DESC, vp.venue_id DESC, rp.rn ASC
                        """.trimIndent(),
                    ).use { statement ->
                        var index = 1
                        statement.setString(index++, VenuePromotionStatus.ACTIVE.dbValue)
                        statement.setTimestamp(index++, Timestamp.from(now))
                        statement.setTimestamp(index++, Timestamp.from(now))
                        statement.setString(index++, VenueStatus.PUBLISHED.dbValue)
                        blockedStatuses.forEach { status -> statement.setString(index++, status) }
                        excludedIds.forEach { id -> statement.setLong(index++, id) }
                        excludedVenueIds.forEach { id -> statement.setLong(index++, id) }
                        statement.setInt(index++, limit.coerceIn(1, 20))
                        statement.setInt(index, offset.coerceAtLeast(0))
                        statement.executeQuery().use { rs -> rs.toPromotionVenueFeedItems() }
                    }
                }
            } catch (e: SQLException) {
                throw DatabaseUnavailableException()
            }
        }
    }

    suspend fun listPromotionVenueFeedItemsByVenueIds(
        venueIds: List<Long>,
        now: Instant = Instant.now(),
        excludePromotionIds: Set<Long> = emptySet(),
    ): List<PromotionVenueFeedItem> {
        val normalizedVenueIds = venueIds.filter { it > 0L }.distinct()
        if (normalizedVenueIds.isEmpty()) return emptyList()
        val ds = dataSource ?: throw DatabaseUnavailableException()
        val blockedStatuses = SubscriptionStatus.blockedDbValues
        val blockedPlaceholders = blockedStatuses.joinToString(",") { "?" }
        val excludedIds = excludePromotionIds.filter { it > 0L }.distinct().sorted()
        val excludedPlaceholders = excludedIds.joinToString(",") { "?" }
        val venuePlaceholders = normalizedVenueIds.joinToString(",") { "?" }
        val excludedClause =
            if (excludedIds.isEmpty()) {
                ""
            } else {
                "AND p.id NOT IN ($excludedPlaceholders)"
            }
        return withContext(Dispatchers.IO) {
            try {
                ds.connection.use { connection ->
                    val items =
                        connection.prepareStatement(
                            """
                            WITH active_promotions AS (
                                SELECT
                                    p.id,
                                    p.venue_id,
                                    v.name AS venue_name,
                                    v.city,
                                    v.address,
                                    p.title,
                                    p.template_type,
                                    p.starts_at,
                                    p.ends_at,
                                    p.created_at,
                                    p.updated_at
                                FROM venue_promotions p
                                JOIN venues v ON v.id = p.venue_id
                                LEFT JOIN venue_subscriptions vs ON vs.venue_id = v.id
                                WHERE p.status = ?
                                  AND (p.starts_at IS NULL OR p.starts_at <= ?)
                                  AND (p.ends_at IS NULL OR p.ends_at >= ?)
                                  AND v.status = ?
                                  AND (vs.status IS NULL OR LOWER(vs.status) NOT IN ($blockedPlaceholders))
                                  AND v.id IN ($venuePlaceholders)
                                  $excludedClause
                            ),
                            venue_page AS (
                                SELECT
                                    venue_id,
                                    venue_name,
                                    city,
                                    address,
                                    COUNT(*) AS promotions_count,
                                    MAX(COALESCE(starts_at, created_at)) AS latest_promotion_at,
                                    MAX(updated_at) AS latest_updated_at
                                FROM active_promotions
                                GROUP BY venue_id, venue_name, city, address
                            ),
                            ranked_promotions AS (
                                SELECT
                                    ap.*,
                                    ROW_NUMBER() OVER (
                                        PARTITION BY ap.venue_id
                                        ORDER BY COALESCE(ap.starts_at, ap.created_at) DESC, ap.updated_at DESC, ap.id DESC
                                    ) AS rn
                                FROM active_promotions ap
                                JOIN venue_page vp ON vp.venue_id = ap.venue_id
                            )
                            SELECT
                                vp.venue_id,
                                vp.venue_name,
                                vp.city,
                                vp.address,
                                vp.promotions_count,
                                vp.latest_promotion_at,
                                vp.latest_updated_at,
                                rp.id AS promotion_id,
                                rp.title AS promotion_title,
                                rp.template_type AS promotion_template_type,
                                rp.starts_at AS promotion_starts_at,
                                rp.ends_at AS promotion_ends_at,
                                rp.rn AS promotion_rank
                            FROM venue_page vp
                            LEFT JOIN ranked_promotions rp ON rp.venue_id = vp.venue_id AND rp.rn <= 3
                            ORDER BY vp.venue_id DESC, rp.rn ASC
                            """.trimIndent(),
                        ).use { statement ->
                            var index = 1
                            statement.setString(index++, VenuePromotionStatus.ACTIVE.dbValue)
                            statement.setTimestamp(index++, Timestamp.from(now))
                            statement.setTimestamp(index++, Timestamp.from(now))
                            statement.setString(index++, VenueStatus.PUBLISHED.dbValue)
                            blockedStatuses.forEach { status -> statement.setString(index++, status) }
                            normalizedVenueIds.forEach { id -> statement.setLong(index++, id) }
                            excludedIds.forEach { id -> statement.setLong(index++, id) }
                            statement.executeQuery().use { rs -> rs.toPromotionVenueFeedItems() }
                        }
                    val byVenueId = items.associateBy { it.venueId }
                    normalizedVenueIds.mapNotNull { byVenueId[it] }
                }
            } catch (e: SQLException) {
                throw DatabaseUnavailableException()
            }
        }
    }

    suspend fun listActivePromotionsForVenue(
        venueId: Long,
        limit: Int = 20,
        now: Instant = Instant.now(),
    ): List<VenuePromotion> {
        val ds = dataSource ?: throw DatabaseUnavailableException()
        val blockedStatuses = SubscriptionStatus.blockedDbValues
        val blockedPlaceholders = blockedStatuses.joinToString(",") { "?" }
        return withContext(Dispatchers.IO) {
            try {
                ds.connection.use { connection ->
                    connection.prepareStatement(
                        """
                        SELECT ${promotionColumns()}
                        FROM venue_promotions p
                        JOIN venues v ON v.id = p.venue_id
                        LEFT JOIN venue_subscriptions vs ON vs.venue_id = v.id
                        WHERE p.venue_id = ?
                          AND p.status = ?
                          AND (p.starts_at IS NULL OR p.starts_at <= ?)
                          AND (p.ends_at IS NULL OR p.ends_at >= ?)
                          AND v.status = ?
                          AND (vs.status IS NULL OR LOWER(vs.status) NOT IN ($blockedPlaceholders))
                        ORDER BY COALESCE(p.starts_at, p.created_at) DESC, p.updated_at DESC, p.id DESC
                        LIMIT ?
                        """.trimIndent(),
                    ).use { statement ->
                        var index = 1
                        statement.setLong(index++, venueId)
                        statement.setString(index++, VenuePromotionStatus.ACTIVE.dbValue)
                        statement.setTimestamp(index++, Timestamp.from(now))
                        statement.setTimestamp(index++, Timestamp.from(now))
                        statement.setString(index++, VenueStatus.PUBLISHED.dbValue)
                        blockedStatuses.forEach { status -> statement.setString(index++, status) }
                        statement.setInt(index, limit.coerceIn(1, 50))
                        statement.executeQuery().use { rs -> rs.toPromotions() }
                    }
                }
            } catch (e: SQLException) {
                throw DatabaseUnavailableException()
            }
        }
    }

    suspend fun getPromotionForGuest(
        promotionId: Long,
        now: Instant = Instant.now(),
    ): VenuePromotion? {
        val ds = dataSource ?: throw DatabaseUnavailableException()
        val blockedStatuses = SubscriptionStatus.blockedDbValues
        val blockedPlaceholders = blockedStatuses.joinToString(",") { "?" }
        return withContext(Dispatchers.IO) {
            try {
                ds.connection.use { connection ->
                    connection.prepareStatement(
                        """
                        SELECT ${promotionColumns()}
                        FROM venue_promotions p
                        JOIN venues v ON v.id = p.venue_id
                        LEFT JOIN venue_subscriptions vs ON vs.venue_id = v.id
                        WHERE p.id = ?
                          AND p.status = ?
                          AND (p.starts_at IS NULL OR p.starts_at <= ?)
                          AND (p.ends_at IS NULL OR p.ends_at >= ?)
                          AND v.status = ?
                          AND (vs.status IS NULL OR LOWER(vs.status) NOT IN ($blockedPlaceholders))
                        """.trimIndent(),
                    ).use { statement ->
                        var index = 1
                        statement.setLong(index++, promotionId)
                        statement.setString(index++, VenuePromotionStatus.ACTIVE.dbValue)
                        statement.setTimestamp(index++, Timestamp.from(now))
                        statement.setTimestamp(index++, Timestamp.from(now))
                        statement.setString(index++, VenueStatus.PUBLISHED.dbValue)
                        blockedStatuses.forEach { status -> statement.setString(index++, status) }
                        statement.executeQuery().use { rs -> if (rs.next()) rs.toPromotion() else null }
                    }
                }
            } catch (e: SQLException) {
                throw DatabaseUnavailableException()
            }
        }
    }

    private fun selectPromotion(
        connection: Connection,
        venueId: Long,
        promotionId: Long,
    ): VenuePromotion? =
        connection.prepareStatement(
            """
            SELECT ${promotionColumns()}
            FROM venue_promotions p
            JOIN venues v ON v.id = p.venue_id
            WHERE p.venue_id = ? AND p.id = ?
            """.trimIndent(),
        ).use { statement ->
            statement.setLong(1, venueId)
            statement.setLong(2, promotionId)
            statement.executeQuery().use { rs -> if (rs.next()) rs.toPromotion() else null }
        }

    private fun promotionColumns(): String =
        """
        p.id,
        p.venue_id,
        v.name AS venue_name,
        p.title,
        p.description,
        p.terms,
        p.starts_at,
        p.ends_at,
        p.status,
        p.template_type,
        p.created_by_user_id,
        p.created_at,
        p.updated_at
        """.trimIndent()

    private fun ResultSet.toPromotions(): List<VenuePromotion> {
        val promotions = mutableListOf<VenuePromotion>()
        while (next()) {
            toPromotion()?.let { promotions.add(it) }
        }
        return promotions
    }

    private fun ResultSet.toPromotion(): VenuePromotion? {
        val status = VenuePromotionStatus.fromDb(getString("status")) ?: return null
        val templateType = VenuePromotionTemplateType.fromDb(getString("template_type")) ?: VenuePromotionTemplateType.TEXT_ONLY
        return VenuePromotion(
            id = getLong("id"),
            venueId = getLong("venue_id"),
            venueName = getString("venue_name"),
            title = getString("title"),
            description = getString("description"),
            terms = getString("terms"),
            startsAt = getTimestamp("starts_at")?.toInstant(),
            endsAt = getTimestamp("ends_at")?.toInstant(),
            status = status,
            createdByUserId = getLong("created_by_user_id"),
            createdAt = getTimestamp("created_at").toInstant(),
            updatedAt = getTimestamp("updated_at").toInstant(),
            templateType = templateType,
        )
    }

    private fun ResultSet.toPromotionVenueFeedItems(): List<PromotionVenueFeedItem> {
        val venues = linkedMapOf<Long, PromotionVenueFeedAccumulator>()
        while (next()) {
            val venueId = getLong("venue_id")
            val accumulator =
                venues.getOrPut(venueId) {
                    PromotionVenueFeedAccumulator(
                        venueId = venueId,
                        venueName = getString("venue_name"),
                        city = getString("city"),
                        address = getString("address"),
                        promotionsCount = getInt("promotions_count"),
                    )
                }
            val promotionId = getLong("promotion_id")
            if (!wasNull()) {
                val templateType =
                    VenuePromotionTemplateType.fromDb(getString("promotion_template_type"))
                        ?: VenuePromotionTemplateType.TEXT_ONLY
                accumulator.previewPromotions +=
                    PromotionPreview(
                        id = promotionId,
                        title = getString("promotion_title"),
                        templateType = templateType,
                        startsAt = getTimestamp("promotion_starts_at")?.toInstant(),
                        endsAt = getTimestamp("promotion_ends_at")?.toInstant(),
                    )
            }
        }
        return venues.values.map { it.toFeedItem() }
    }

    private data class PromotionVenueFeedAccumulator(
        val venueId: Long,
        val venueName: String,
        val city: String?,
        val address: String?,
        val promotionsCount: Int,
        val previewPromotions: MutableList<PromotionPreview> = mutableListOf(),
    ) {
        fun toFeedItem(): PromotionVenueFeedItem =
            PromotionVenueFeedItem(
                venueId = venueId,
                venueName = venueName,
                city = city,
                address = address,
                promotionsCount = promotionsCount,
                previewPromotions = previewPromotions.toList(),
            )
    }

    private fun requireText(
        value: String,
        name: String,
    ): String {
        val trimmed = value.trim()
        require(trimmed.isNotBlank()) { "$name must not be blank" }
        return trimmed
    }

    private fun setInstant(
        statement: java.sql.PreparedStatement,
        index: Int,
        value: Instant?,
    ) {
        if (value == null) {
            statement.setTimestamp(index, null)
        } else {
            statement.setTimestamp(index, Timestamp.from(value))
        }
    }

    private fun setStatementValue(
        statement: java.sql.PreparedStatement,
        index: Int,
        value: Any?,
    ) {
        when (value) {
            null -> statement.setObject(index, null)
            is String -> statement.setString(index, value)
            is Instant -> statement.setTimestamp(index, Timestamp.from(value))
            else -> statement.setObject(index, value)
        }
    }
}
