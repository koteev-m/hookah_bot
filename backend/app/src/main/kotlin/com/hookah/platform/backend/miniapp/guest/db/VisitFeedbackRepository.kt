package com.hookah.platform.backend.miniapp.guest.db

import com.hookah.platform.backend.api.DatabaseUnavailableException
import com.hookah.platform.backend.telegram.db.VenueSettingsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.sql.Connection
import java.sql.ResultSet
import java.sql.SQLException
import java.sql.Timestamp
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import javax.sql.DataSource

enum class VisitFeedbackRequestStatus {
    PENDING,
    SENT,
    CANCELED,
    SKIPPED,
    FAILED,
}

enum class VisitFeedbackStatus {
    SUBMITTED,
    SKIPPED,
}

enum class VisitFeedbackMessageSender {
    STAFF,
    GUEST,
}

enum class VisitFeedbackVenueFilter {
    NEEDS_REPLY,
    LOW,
    ALL,
}

data class VisitFeedbackRequestRecord(
    val id: Long,
    val visitId: Long,
    val venueId: Long,
    val userId: Long,
    val scheduledFor: Instant,
    val status: VisitFeedbackRequestStatus,
    val attempts: Int,
)

data class VisitFeedbackRequestDelivery(
    val requestId: Long,
    val visitId: Long,
    val venueId: Long,
    val userId: Long,
    val venueName: String,
    val attempts: Int,
)

data class VisitFeedbackRecord(
    val id: Long,
    val visitId: Long,
    val venueId: Long,
    val userId: Long,
    val rating: Int?,
    val comment: String?,
    val status: VisitFeedbackStatus,
    val staffNotifiedAt: Instant?,
    val commentStaffNotifiedAt: Instant?,
    val tags: List<String> = emptyList(),
    val createdAt: Instant? = null,
)

data class VisitFeedbackThread(
    val feedbackId: Long,
    val visitId: Long,
    val venueId: Long,
    val guestUserId: Long,
    val rating: Int?,
    val comment: String?,
    val status: VisitFeedbackStatus,
    val venueName: String,
)

data class VisitFeedbackMessageRecord(
    val id: Long,
    val feedbackId: Long,
    val visitId: Long,
    val venueId: Long,
    val guestUserId: Long,
    val senderType: VisitFeedbackMessageSender,
    val senderUserId: Long?,
    val messageText: String,
    val createdAt: Instant,
)

data class VisitFeedbackVenueSummary(
    val feedbackId: Long,
    val visitId: Long,
    val venueId: Long,
    val guestUserId: Long,
    val guestDisplayName: String?,
    val rating: Int?,
    val comment: String?,
    val status: VisitFeedbackStatus,
    val occurredAt: Instant,
    val serviceDate: LocalDate?,
    val hasStaffReply: Boolean,
    val requiresAnswer: Boolean,
    val tags: List<String> = emptyList(),
    val createdAt: Instant? = null,
)

data class VisitFeedbackVenueDetail(
    val feedbackId: Long,
    val visitId: Long,
    val venueId: Long,
    val venueName: String,
    val guestUserId: Long,
    val guestDisplayName: String?,
    val rating: Int?,
    val comment: String?,
    val status: VisitFeedbackStatus,
    val occurredAt: Instant,
    val serviceDate: LocalDate?,
    val hasStaffReply: Boolean,
    val requiresAnswer: Boolean,
    val messages: List<VisitFeedbackMessageRecord>,
    val tags: List<String> = emptyList(),
    val createdAt: Instant? = null,
)

data class VisitFeedbackVenueAggregate(
    val count: Long,
    val averageRating: Double?,
    val lowCount: Long,
)

class VisitFeedbackRepository(private val dataSource: DataSource?) {
    suspend fun scheduleFeedbackRequestForVisit(
        visitId: Long,
        now: Instant = Instant.now(),
    ): VisitFeedbackRequestRecord? {
        val ds = dataSource ?: throw DatabaseUnavailableException()
        return withContext(Dispatchers.IO) {
            try {
                ds.connection.use { connection ->
                    val initialAutoCommit = connection.autoCommit
                    connection.autoCommit = false
                    try {
                        val request = scheduleFeedbackRequestForVisit(connection, visitId, now)
                        connection.commit()
                        request
                    } catch (e: SQLException) {
                        runCatching { connection.rollback() }
                        throw e
                    } finally {
                        runCatching { connection.autoCommit = initialAutoCommit }
                    }
                }
            } catch (e: SQLException) {
                throw DatabaseUnavailableException()
            }
        }
    }

    fun scheduleFeedbackRequestForVisit(
        connection: Connection,
        visitId: Long,
        now: Instant = Instant.now(),
    ): VisitFeedbackRequestRecord? {
        val visit = loadFeedbackVisit(connection, visitId) ?: return null
        if (!visit.hasClosedOrder) return null
        findRequestByVisit(connection, visitId)?.let { return it }
        val scheduledFor = computeScheduledFor(now, visit.zoneId)
        val dedupeKey = "visit_feedback:$visitId"
        insertFeedbackRequest(
            connection = connection,
            visitId = visit.visitId,
            venueId = visit.venueId,
            userId = visit.userId,
            scheduledFor = scheduledFor,
            dedupeKey = dedupeKey,
        )
        return findRequestByVisit(connection, visitId)
    }

    suspend fun pickDueRequests(
        now: Instant = Instant.now(),
        limit: Int = DEFAULT_BATCH_SIZE,
    ): List<VisitFeedbackRequestDelivery> {
        require(limit > 0) { "limit must be > 0" }
        val ds = dataSource ?: throw DatabaseUnavailableException()
        return withContext(Dispatchers.IO) {
            try {
                ds.connection.use { connection ->
                    val initialAutoCommit = connection.autoCommit
                    connection.autoCommit = false
                    try {
                        val requests = selectDueRequests(connection, now, limit)
                        incrementAttempts(connection, requests.map { it.requestId })
                        connection.commit()
                        requests.map { it.copy(attempts = it.attempts + 1) }
                    } catch (e: SQLException) {
                        runCatching { connection.rollback() }
                        throw e
                    } finally {
                        runCatching { connection.autoCommit = initialAutoCommit }
                    }
                }
            } catch (e: SQLException) {
                throw DatabaseUnavailableException()
            }
        }
    }

    suspend fun markRequestSent(
        requestId: Long,
        sentAt: Instant = Instant.now(),
    ): Boolean =
        updateRequestStatus(
            requestId = requestId,
            status = VisitFeedbackRequestStatus.SENT,
            sentAt = sentAt,
            lastError = null,
        )

    suspend fun markRequestFailed(
        requestId: Long,
        lastError: String?,
    ): Boolean =
        updateRequestStatus(
            requestId = requestId,
            status = VisitFeedbackRequestStatus.FAILED,
            sentAt = null,
            lastError = lastError,
        )

    suspend fun submitRating(
        visitId: Long,
        userId: Long,
        rating: Int,
        now: Instant = Instant.now(),
    ): VisitFeedbackRecord? {
        require(rating in 1..5) { "rating must be between 1 and 5" }
        val ds = dataSource ?: throw DatabaseUnavailableException()
        return withContext(Dispatchers.IO) {
            try {
                ds.connection.use { connection ->
                    val visit = loadOwnedVisibleFeedbackVisit(connection, visitId, userId) ?: return@use null
                    val existing = findFeedback(connection, visitId, userId)
                    if (existing == null) {
                        insertFeedback(
                            connection = connection,
                            visit = visit,
                            rating = rating,
                            comment = null,
                            status = VisitFeedbackStatus.SUBMITTED,
                        )
                    } else if (existing.status != VisitFeedbackStatus.SUBMITTED) {
                        connection.prepareStatement(
                            """
                            UPDATE visit_feedback
                            SET rating = ?,
                                status = ?,
                                updated_at = ?
                            WHERE id = ?
                            """.trimIndent(),
                        ).use { statement ->
                            statement.setInt(1, rating)
                            statement.setString(2, VisitFeedbackStatus.SUBMITTED.name)
                            statement.setTimestamp(3, Timestamp.from(now))
                            statement.setLong(4, existing.id)
                            statement.executeUpdate()
                        }
                    } else {
                        return@use existing
                    }
                    findFeedback(connection, visitId, userId)
                }
            } catch (e: SQLException) {
                throw DatabaseUnavailableException()
            }
        }
    }

    suspend fun submitFeedback(
        visitId: Long,
        userId: Long,
        rating: Int,
        tags: List<String> = emptyList(),
        comment: String? = null,
        now: Instant = Instant.now(),
    ): VisitFeedbackRecord? {
        require(rating in 1..5) { "rating must be between 1 and 5" }
        val normalizedTags = normalizeTags(tags)
        val normalizedComment = normalizeComment(comment)
        val ds = dataSource ?: throw DatabaseUnavailableException()
        return withContext(Dispatchers.IO) {
            try {
                ds.connection.use { connection ->
                    val visit = loadOwnedVisibleFeedbackVisit(connection, visitId, userId) ?: return@use null
                    val existing = findFeedback(connection, visitId, userId)
                    if (existing == null) {
                        try {
                            insertFeedback(
                                connection = connection,
                                visit = visit,
                                rating = rating,
                                comment = normalizedComment,
                                status = VisitFeedbackStatus.SUBMITTED,
                                tags = normalizedTags,
                            )
                        } catch (e: SQLException) {
                            if (!e.isDuplicateKeyViolation()) throw e
                        }
                    } else if (existing.status != VisitFeedbackStatus.SUBMITTED) {
                        updateFeedbackSubmission(
                            connection = connection,
                            feedbackId = existing.id,
                            rating = rating,
                            tags = normalizedTags,
                            comment = normalizedComment,
                            now = now,
                        )
                    } else {
                        return@use existing
                    }
                    findFeedback(connection, visitId, userId)
                }
            } catch (e: SQLException) {
                throw DatabaseUnavailableException()
            }
        }
    }

    suspend fun findGuestFeedback(
        visitId: Long,
        userId: Long,
    ): VisitFeedbackRecord? {
        val ds = dataSource ?: throw DatabaseUnavailableException()
        return withContext(Dispatchers.IO) {
            try {
                ds.connection.use { connection ->
                    loadOwnedVisibleFeedbackVisit(connection, visitId, userId) ?: return@use null
                    findFeedback(connection, visitId, userId)
                }
            } catch (e: SQLException) {
                throw DatabaseUnavailableException()
            }
        }
    }

    suspend fun skipFeedback(
        visitId: Long,
        userId: Long,
        now: Instant = Instant.now(),
    ): VisitFeedbackRecord? {
        val ds = dataSource ?: throw DatabaseUnavailableException()
        return withContext(Dispatchers.IO) {
            try {
                ds.connection.use { connection ->
                    val visit = loadOwnedVisibleFeedbackVisit(connection, visitId, userId) ?: return@use null
                    val existing = findFeedback(connection, visitId, userId)
                    if (existing == null) {
                        insertFeedback(
                            connection = connection,
                            visit = visit,
                            rating = null,
                            comment = null,
                            status = VisitFeedbackStatus.SKIPPED,
                        )
                    } else if (existing.status != VisitFeedbackStatus.SUBMITTED) {
                        connection.prepareStatement(
                            """
                            UPDATE visit_feedback
                            SET status = ?,
                                updated_at = ?
                            WHERE id = ?
                            """.trimIndent(),
                        ).use { statement ->
                            statement.setString(1, VisitFeedbackStatus.SKIPPED.name)
                            statement.setTimestamp(2, Timestamp.from(now))
                            statement.setLong(3, existing.id)
                            statement.executeUpdate()
                        }
                    }
                    findFeedback(connection, visitId, userId)
                }
            } catch (e: SQLException) {
                throw DatabaseUnavailableException()
            }
        }
    }

    suspend fun saveComment(
        visitId: Long,
        userId: Long,
        comment: String,
        now: Instant = Instant.now(),
    ): VisitFeedbackRecord? {
        val normalized = comment.trim()
        require(normalized.isNotBlank()) { "comment must not be blank" }
        require(normalized.length <= MAX_COMMENT_LENGTH) { "comment is too long" }
        val ds = dataSource ?: throw DatabaseUnavailableException()
        return withContext(Dispatchers.IO) {
            try {
                ds.connection.use { connection ->
                    val visit = loadOwnedVisibleFeedbackVisit(connection, visitId, userId) ?: return@use null
                    val existing = findFeedback(connection, visitId, userId)
                    if (existing == null) {
                        insertFeedback(
                            connection = connection,
                            visit = visit,
                            rating = null,
                            comment = normalized,
                            status = VisitFeedbackStatus.SUBMITTED,
                        )
                    } else {
                        connection.prepareStatement(
                            """
                            UPDATE visit_feedback
                            SET comment = ?,
                                status = ?,
                                updated_at = ?
                            WHERE id = ?
                            """.trimIndent(),
                        ).use { statement ->
                            statement.setString(1, normalized)
                            statement.setString(2, VisitFeedbackStatus.SUBMITTED.name)
                            statement.setTimestamp(3, Timestamp.from(now))
                            statement.setLong(4, existing.id)
                            statement.executeUpdate()
                        }
                    }
                    findFeedback(connection, visitId, userId)
                }
            } catch (e: SQLException) {
                throw DatabaseUnavailableException()
            }
        }
    }

    suspend fun markStaffNotified(
        feedbackId: Long,
        now: Instant = Instant.now(),
    ): Boolean {
        val ds = dataSource ?: throw DatabaseUnavailableException()
        return withContext(Dispatchers.IO) {
            try {
                ds.connection.use { connection ->
                    connection.prepareStatement(
                        """
                        UPDATE visit_feedback
                        SET staff_notified_at = ?,
                            updated_at = ?
                        WHERE id = ? AND staff_notified_at IS NULL
                        """.trimIndent(),
                    ).use { statement ->
                        val timestamp = Timestamp.from(now)
                        statement.setTimestamp(1, timestamp)
                        statement.setTimestamp(2, timestamp)
                        statement.setLong(3, feedbackId)
                        statement.executeUpdate() > 0
                    }
                }
            } catch (e: SQLException) {
                throw DatabaseUnavailableException()
            }
        }
    }

    suspend fun markCommentStaffNotified(
        feedbackId: Long,
        now: Instant = Instant.now(),
    ): Boolean {
        val ds = dataSource ?: throw DatabaseUnavailableException()
        return withContext(Dispatchers.IO) {
            try {
                ds.connection.use { connection ->
                    connection.prepareStatement(
                        """
                        UPDATE visit_feedback
                        SET comment_staff_notified_at = ?,
                            updated_at = ?
                        WHERE id = ? AND comment_staff_notified_at IS NULL
                        """.trimIndent(),
                    ).use { statement ->
                        val timestamp = Timestamp.from(now)
                        statement.setTimestamp(1, timestamp)
                        statement.setTimestamp(2, timestamp)
                        statement.setLong(3, feedbackId)
                        statement.executeUpdate() > 0
                    }
                }
            } catch (e: SQLException) {
                throw DatabaseUnavailableException()
            }
        }
    }

    suspend fun findFeedbackThread(feedbackId: Long): VisitFeedbackThread? {
        val ds = dataSource ?: throw DatabaseUnavailableException()
        return withContext(Dispatchers.IO) {
            try {
                ds.connection.use { connection -> findFeedbackThread(connection, feedbackId) }
            } catch (e: SQLException) {
                throw DatabaseUnavailableException()
            }
        }
    }

    suspend fun saveFeedbackMessage(
        feedbackId: Long,
        senderType: VisitFeedbackMessageSender,
        senderUserId: Long?,
        messageText: String,
        now: Instant = Instant.now(),
    ): VisitFeedbackMessageRecord? {
        val normalized = messageText.trim()
        require(normalized.isNotBlank()) { "message must not be blank" }
        require(normalized.length <= MAX_MESSAGE_LENGTH) { "message is too long" }
        val ds = dataSource ?: throw DatabaseUnavailableException()
        return withContext(Dispatchers.IO) {
            try {
                ds.connection.use { connection ->
                    val thread = findFeedbackThread(connection, feedbackId) ?: return@use null
                    insertFeedbackMessage(
                        connection = connection,
                        thread = thread,
                        senderType = senderType,
                        senderUserId = senderUserId,
                        messageText = normalized,
                        now = now,
                    )
                }
            } catch (e: SQLException) {
                throw DatabaseUnavailableException()
            }
        }
    }

    suspend fun listVenueFeedback(
        venueId: Long,
        filter: VisitFeedbackVenueFilter = VisitFeedbackVenueFilter.ALL,
        limit: Int = 10,
        offset: Int = 0,
    ): List<VisitFeedbackVenueSummary> {
        val ds = dataSource ?: throw DatabaseUnavailableException()
        val safeLimit = limit.coerceIn(1, 50)
        val safeOffset = offset.coerceAtLeast(0)
        return withContext(Dispatchers.IO) {
            try {
                ds.connection.use { connection ->
                    listVenueFeedback(connection, venueId, filter, safeLimit, safeOffset)
                }
            } catch (e: SQLException) {
                throw DatabaseUnavailableException()
            }
        }
    }

    suspend fun loadVenueFeedbackAggregate(venueId: Long): VisitFeedbackVenueAggregate {
        val ds = dataSource ?: throw DatabaseUnavailableException()
        return withContext(Dispatchers.IO) {
            try {
                ds.connection.use { connection ->
                    connection.prepareStatement(
                        """
                        SELECT COUNT(*) AS feedback_count,
                               AVG(rating) AS average_rating,
                               COALESCE(SUM(CASE WHEN rating BETWEEN 1 AND 3 THEN 1 ELSE 0 END), 0) AS low_count
                        FROM visit_feedback
                        WHERE venue_id = ?
                          AND status = ?
                        """.trimIndent(),
                    ).use { statement ->
                        statement.setLong(1, venueId)
                        statement.setString(2, VisitFeedbackStatus.SUBMITTED.name)
                        statement.executeQuery().use { rs ->
                            if (rs.next()) {
                                val average = rs.getDouble("average_rating")
                                VisitFeedbackVenueAggregate(
                                    count = rs.getLong("feedback_count"),
                                    averageRating = if (rs.wasNull()) null else average,
                                    lowCount = rs.getLong("low_count"),
                                )
                            } else {
                                VisitFeedbackVenueAggregate(count = 0L, averageRating = null, lowCount = 0L)
                            }
                        }
                    }
                }
            } catch (e: SQLException) {
                throw DatabaseUnavailableException()
            }
        }
    }

    suspend fun getVenueFeedbackDetail(
        venueId: Long,
        feedbackId: Long,
    ): VisitFeedbackVenueDetail? {
        val ds = dataSource ?: throw DatabaseUnavailableException()
        return withContext(Dispatchers.IO) {
            try {
                ds.connection.use { connection ->
                    val detail = findVenueFeedbackDetail(connection, venueId, feedbackId) ?: return@use null
                    detail.copy(messages = listFeedbackMessages(connection, feedbackId))
                }
            } catch (e: SQLException) {
                throw DatabaseUnavailableException()
            }
        }
    }

    private fun loadFeedbackVisit(
        connection: Connection,
        visitId: Long,
    ): FeedbackVisit? =
        connection.prepareStatement(
            """
            SELECT v.id AS visit_id,
                   v.venue_id,
                   v.user_id,
                   v.order_id,
                   v.table_session_id,
                   COALESCE(NULLIF(vs.timezone, ''), ?) AS timezone,
                   EXISTS (
                       SELECT 1
                       FROM orders o
                       WHERE o.status = 'CLOSED'
                         AND (
                             (v.order_id IS NOT NULL AND o.id = v.order_id)
                             OR (v.table_session_id IS NOT NULL AND o.table_session_id = v.table_session_id)
                         )
                   ) AS has_closed_order
            FROM visits v
            JOIN venues venue ON venue.id = v.venue_id
            LEFT JOIN venue_settings vs ON vs.venue_id = v.venue_id
            WHERE v.id = ?
            """.trimIndent(),
        ).use { statement ->
            statement.setString(1, VenueSettingsRepository.DEFAULT_AUTO_TIMEZONE)
            statement.setLong(2, visitId)
            statement.executeQuery().use { rs -> if (rs.next()) rs.toFeedbackVisit() else null }
        }

    private fun loadOwnedVisibleFeedbackVisit(
        connection: Connection,
        visitId: Long,
        userId: Long,
    ): FeedbackVisit? =
        connection.prepareStatement(
            """
            SELECT v.id AS visit_id,
                   v.venue_id,
                   v.user_id,
                   v.order_id,
                   v.table_session_id,
                   COALESCE(NULLIF(vs.timezone, ''), ?) AS timezone,
                   EXISTS (
                       SELECT 1
                       FROM orders o
                       WHERE o.status = 'CLOSED'
                         AND (
                             (v.order_id IS NOT NULL AND o.id = v.order_id)
                             OR (v.table_session_id IS NOT NULL AND o.table_session_id = v.table_session_id)
                         )
                   ) AS has_closed_order
            FROM visits v
            JOIN venues venue ON venue.id = v.venue_id
            LEFT JOIN bookings b ON b.id = v.booking_id
            LEFT JOIN venue_settings vs ON vs.venue_id = v.venue_id
            WHERE v.id = ?
              AND v.user_id = ?
              AND v.source IN ('ORDER_CLOSED', 'BOOKING_SEATED', 'MERGED')
              AND (v.booking_id IS NULL OR b.status = 'SEATED')
            """.trimIndent(),
        ).use { statement ->
            statement.setString(1, VenueSettingsRepository.DEFAULT_AUTO_TIMEZONE)
            statement.setLong(2, visitId)
            statement.setLong(3, userId)
            statement.executeQuery().use { rs -> if (rs.next()) rs.toFeedbackVisit() else null }
        }

    private fun findRequestByVisit(
        connection: Connection,
        visitId: Long,
    ): VisitFeedbackRequestRecord? =
        connection.prepareStatement(
            """
            SELECT id, visit_id, venue_id, user_id, scheduled_for, status, attempts
            FROM visit_feedback_requests
            WHERE visit_id = ?
            ORDER BY id
            LIMIT 1
            """.trimIndent(),
        ).use { statement ->
            statement.setLong(1, visitId)
            statement.executeQuery().use { rs -> if (rs.next()) rs.toFeedbackRequestRecord() else null }
        }

    private fun insertFeedbackRequest(
        connection: Connection,
        visitId: Long,
        venueId: Long,
        userId: Long,
        scheduledFor: Instant,
        dedupeKey: String,
    ) {
        connection.prepareStatement(
            """
            INSERT INTO visit_feedback_requests (visit_id, venue_id, user_id, scheduled_for, status, dedupe_key)
            VALUES (?, ?, ?, ?, ?, ?)
            """.trimIndent(),
        ).use { statement ->
            statement.setLong(1, visitId)
            statement.setLong(2, venueId)
            statement.setLong(3, userId)
            statement.setTimestamp(4, Timestamp.from(scheduledFor))
            statement.setString(5, VisitFeedbackRequestStatus.PENDING.name)
            statement.setString(6, dedupeKey)
            statement.executeUpdate()
        }
    }

    private fun selectDueRequests(
        connection: Connection,
        now: Instant,
        limit: Int,
    ): List<VisitFeedbackRequestDelivery> =
        connection.prepareStatement(
            """
            SELECT vfr.id AS request_id,
                   vfr.visit_id,
                   vfr.venue_id,
                   vfr.user_id,
                   venue.name AS venue_name,
                   vfr.attempts
            FROM visit_feedback_requests vfr
            JOIN visits v ON v.id = vfr.visit_id
            JOIN venues venue ON venue.id = vfr.venue_id
            WHERE vfr.status = ?
              AND vfr.scheduled_for <= ?
              AND NOT EXISTS (
                  SELECT 1 FROM visit_feedback vf
                  WHERE vf.visit_id = vfr.visit_id
                    AND vf.user_id = vfr.user_id
                    AND vf.status = ?
              )
            ORDER BY vfr.scheduled_for, vfr.id
            LIMIT ?
            """.trimIndent(),
        ).use { statement ->
            statement.setString(1, VisitFeedbackRequestStatus.PENDING.name)
            statement.setTimestamp(2, Timestamp.from(now))
            statement.setString(3, VisitFeedbackStatus.SKIPPED.name)
            statement.setInt(4, limit)
            statement.executeQuery().use { rs ->
                buildList {
                    while (rs.next()) add(rs.toFeedbackRequestDelivery())
                }
            }
        }

    private fun incrementAttempts(
        connection: Connection,
        requestIds: List<Long>,
    ) {
        if (requestIds.isEmpty()) return
        val placeholders = requestIds.joinToString(",") { "?" }
        connection.prepareStatement(
            """
            UPDATE visit_feedback_requests
            SET attempts = attempts + 1,
                updated_at = CURRENT_TIMESTAMP
            WHERE id IN ($placeholders)
            """.trimIndent(),
        ).use { statement ->
            requestIds.forEachIndexed { index, id -> statement.setLong(index + 1, id) }
            statement.executeUpdate()
        }
    }

    private suspend fun updateRequestStatus(
        requestId: Long,
        status: VisitFeedbackRequestStatus,
        sentAt: Instant?,
        lastError: String?,
    ): Boolean {
        val ds = dataSource ?: throw DatabaseUnavailableException()
        return withContext(Dispatchers.IO) {
            try {
                ds.connection.use { connection ->
                    connection.prepareStatement(
                        """
                        UPDATE visit_feedback_requests
                        SET status = ?,
                            sent_at = ?,
                            last_error = ?,
                            updated_at = CURRENT_TIMESTAMP
                        WHERE id = ?
                        """.trimIndent(),
                    ).use { statement ->
                        statement.setString(1, status.name)
                        if (sentAt == null) {
                            statement.setNull(2, java.sql.Types.TIMESTAMP)
                        } else {
                            statement.setTimestamp(2, Timestamp.from(sentAt))
                        }
                        statement.setString(3, lastError)
                        statement.setLong(4, requestId)
                        statement.executeUpdate() > 0
                    }
                }
            } catch (e: SQLException) {
                throw DatabaseUnavailableException()
            }
        }
    }

    private fun findFeedback(
        connection: Connection,
        visitId: Long,
        userId: Long,
    ): VisitFeedbackRecord? =
        connection.prepareStatement(
            """
            SELECT id,
                   visit_id,
                   venue_id,
                   user_id,
                   rating,
                   comment,
                   status,
                   staff_notified_at,
                   comment_staff_notified_at,
                   tags_json,
                   created_at
            FROM visit_feedback
            WHERE visit_id = ? AND user_id = ?
            """.trimIndent(),
        ).use { statement ->
            statement.setLong(1, visitId)
            statement.setLong(2, userId)
            statement.executeQuery().use { rs -> if (rs.next()) rs.toFeedbackRecord() else null }
        }

    private fun findFeedbackThread(
        connection: Connection,
        feedbackId: Long,
    ): VisitFeedbackThread? =
        connection.prepareStatement(
            """
            SELECT vf.id AS feedback_id,
                   vf.visit_id,
                   vf.venue_id,
                   vf.user_id AS guest_user_id,
                   vf.rating,
                   vf.comment,
                   vf.status,
                   venue.name AS venue_name
            FROM visit_feedback vf
            JOIN venues venue ON venue.id = vf.venue_id
            WHERE vf.id = ?
            """.trimIndent(),
        ).use { statement ->
            statement.setLong(1, feedbackId)
            statement.executeQuery().use { rs -> if (rs.next()) rs.toFeedbackThread() else null }
        }

    private fun listVenueFeedback(
        connection: Connection,
        venueId: Long,
        filter: VisitFeedbackVenueFilter,
        limit: Int,
        offset: Int,
    ): List<VisitFeedbackVenueSummary> =
        connection.prepareStatement(
            """
            WITH feedback_rows AS (
                SELECT vf.id AS feedback_id,
                       MAX(CASE WHEN vfm.sender_type = 'STAFF' THEN vfm.created_at ELSE NULL END) AS last_staff_message_at,
                       MAX(CASE WHEN vfm.sender_type = 'GUEST' THEN vfm.created_at ELSE NULL END) AS last_guest_message_at
                FROM visit_feedback vf
                LEFT JOIN visit_feedback_messages vfm ON vfm.feedback_id = vf.id
                WHERE vf.venue_id = ?
                GROUP BY vf.id
            )
            SELECT vf.id AS feedback_id,
                   vf.visit_id,
                   vf.venue_id,
                   vf.user_id AS guest_user_id,
                   COALESCE(NULLIF(u.guest_display_name, ''), NULLIF(u.first_name, ''), NULLIF(u.username, '')) AS guest_display_name,
                   vf.rating,
                   vf.comment,
                   vf.tags_json,
                   vf.status,
                   v.occurred_at,
                   v.service_date,
                   vf.created_at,
                   (fr.last_staff_message_at IS NOT NULL) AS has_staff_reply,
                   (
                       vf.rating BETWEEN 1 AND 3
                       AND (
                           fr.last_staff_message_at IS NULL
                           OR (
                               fr.last_guest_message_at IS NOT NULL
                               AND fr.last_guest_message_at > fr.last_staff_message_at
                           )
                       )
                   ) AS requires_answer
            FROM visit_feedback vf
            JOIN visits v ON v.id = vf.visit_id
            JOIN feedback_rows fr ON fr.feedback_id = vf.id
            LEFT JOIN users u ON u.telegram_user_id = vf.user_id
            WHERE vf.venue_id = ?
              AND vf.status = ?
              AND (
                  ? = 'ALL'
                  OR (? = 'LOW' AND vf.rating BETWEEN 1 AND 3)
                  OR (
                      ? = 'NEEDS_REPLY'
                      AND vf.rating BETWEEN 1 AND 3
                      AND (
                          fr.last_staff_message_at IS NULL
                          OR (
                              fr.last_guest_message_at IS NOT NULL
                              AND fr.last_guest_message_at > fr.last_staff_message_at
                          )
                      )
                  )
              )
            ORDER BY vf.created_at DESC, vf.id DESC
            LIMIT ?
            OFFSET ?
            """.trimIndent(),
        ).use { statement ->
            statement.setLong(1, venueId)
            statement.setLong(2, venueId)
            statement.setString(3, VisitFeedbackStatus.SUBMITTED.name)
            statement.setString(4, filter.name)
            statement.setString(5, filter.name)
            statement.setString(6, filter.name)
            statement.setInt(7, limit)
            statement.setInt(8, offset)
            statement.executeQuery().use { rs ->
                buildList {
                    while (rs.next()) {
                        add(rs.toVenueFeedbackSummary())
                    }
                }
            }
        }

    private fun findVenueFeedbackDetail(
        connection: Connection,
        venueId: Long,
        feedbackId: Long,
    ): VisitFeedbackVenueDetail? =
        connection.prepareStatement(
            """
            WITH feedback_message_state AS (
                SELECT vf.id AS feedback_id,
                       MAX(CASE WHEN vfm.sender_type = 'STAFF' THEN vfm.created_at ELSE NULL END) AS last_staff_message_at,
                       MAX(CASE WHEN vfm.sender_type = 'GUEST' THEN vfm.created_at ELSE NULL END) AS last_guest_message_at
                FROM visit_feedback vf
                LEFT JOIN visit_feedback_messages vfm ON vfm.feedback_id = vf.id
                WHERE vf.id = ?
                GROUP BY vf.id
            )
            SELECT vf.id AS feedback_id,
                   vf.visit_id,
                   vf.venue_id,
                   venue.name AS venue_name,
                   vf.user_id AS guest_user_id,
                   COALESCE(NULLIF(u.guest_display_name, ''), NULLIF(u.first_name, ''), NULLIF(u.username, '')) AS guest_display_name,
                   vf.rating,
                   vf.comment,
                   vf.tags_json,
                   vf.status,
                   v.occurred_at,
                   v.service_date,
                   vf.created_at,
                   (fms.last_staff_message_at IS NOT NULL) AS has_staff_reply,
                   (
                       vf.rating BETWEEN 1 AND 3
                       AND (
                           fms.last_staff_message_at IS NULL
                           OR (
                               fms.last_guest_message_at IS NOT NULL
                               AND fms.last_guest_message_at > fms.last_staff_message_at
                           )
                       )
                   ) AS requires_answer
            FROM visit_feedback vf
            JOIN visits v ON v.id = vf.visit_id
            JOIN venues venue ON venue.id = vf.venue_id
            JOIN feedback_message_state fms ON fms.feedback_id = vf.id
            LEFT JOIN users u ON u.telegram_user_id = vf.user_id
            WHERE vf.venue_id = ?
              AND vf.id = ?
              AND vf.status = ?
            """.trimIndent(),
        ).use { statement ->
            statement.setLong(1, feedbackId)
            statement.setLong(2, venueId)
            statement.setLong(3, feedbackId)
            statement.setString(4, VisitFeedbackStatus.SUBMITTED.name)
            statement.executeQuery().use { rs ->
                if (rs.next()) rs.toVenueFeedbackDetail(messages = emptyList()) else null
            }
        }

    private fun listFeedbackMessages(
        connection: Connection,
        feedbackId: Long,
    ): List<VisitFeedbackMessageRecord> =
        connection.prepareStatement(
            """
            SELECT id,
                   feedback_id,
                   visit_id,
                   venue_id,
                   guest_user_id,
                   sender_type,
                   sender_user_id,
                   message_text,
                   created_at
            FROM visit_feedback_messages
            WHERE feedback_id = ?
            ORDER BY created_at ASC, id ASC
            """.trimIndent(),
        ).use { statement ->
            statement.setLong(1, feedbackId)
            statement.executeQuery().use { rs ->
                buildList {
                    while (rs.next()) {
                        add(rs.toFeedbackMessageRecord())
                    }
                }
            }
        }

    private fun insertFeedbackMessage(
        connection: Connection,
        thread: VisitFeedbackThread,
        senderType: VisitFeedbackMessageSender,
        senderUserId: Long?,
        messageText: String,
        now: Instant,
    ): VisitFeedbackMessageRecord =
        connection.prepareStatement(
            """
            INSERT INTO visit_feedback_messages (
                feedback_id,
                visit_id,
                venue_id,
                guest_user_id,
                sender_type,
                sender_user_id,
                message_text,
                created_at
            )
            VALUES (?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent(),
            java.sql.Statement.RETURN_GENERATED_KEYS,
        ).use { statement ->
            statement.setLong(1, thread.feedbackId)
            statement.setLong(2, thread.visitId)
            statement.setLong(3, thread.venueId)
            statement.setLong(4, thread.guestUserId)
            statement.setString(5, senderType.name)
            if (senderUserId == null) {
                statement.setNull(6, java.sql.Types.BIGINT)
            } else {
                statement.setLong(6, senderUserId)
            }
            statement.setString(7, messageText)
            statement.setTimestamp(8, Timestamp.from(now))
            statement.executeUpdate()
            statement.generatedKeys.use { keys ->
                keys.next()
                VisitFeedbackMessageRecord(
                    id = keys.getLong(1),
                    feedbackId = thread.feedbackId,
                    visitId = thread.visitId,
                    venueId = thread.venueId,
                    guestUserId = thread.guestUserId,
                    senderType = senderType,
                    senderUserId = senderUserId,
                    messageText = messageText,
                    createdAt = now,
                )
            }
        }

    private fun insertFeedback(
        connection: Connection,
        visit: FeedbackVisit,
        rating: Int?,
        comment: String?,
        status: VisitFeedbackStatus,
        tags: List<String> = emptyList(),
    ) {
        connection.prepareStatement(
            """
            INSERT INTO visit_feedback (visit_id, venue_id, user_id, rating, comment, status, tags_json)
            VALUES (?, ?, ?, ?, ?, ?, ?)
            """.trimIndent(),
        ).use { statement ->
            statement.setLong(1, visit.visitId)
            statement.setLong(2, visit.venueId)
            statement.setLong(3, visit.userId)
            if (rating == null) {
                statement.setNull(4, java.sql.Types.INTEGER)
            } else {
                statement.setInt(4, rating)
            }
            statement.setString(5, comment)
            statement.setString(6, status.name)
            statement.setString(7, encodeTags(tags))
            statement.executeUpdate()
        }
    }

    private fun updateFeedbackSubmission(
        connection: Connection,
        feedbackId: Long,
        rating: Int,
        tags: List<String>,
        comment: String?,
        now: Instant,
    ) {
        connection.prepareStatement(
            """
            UPDATE visit_feedback
            SET rating = ?,
                tags_json = ?,
                comment = ?,
                status = ?,
                updated_at = ?
            WHERE id = ?
            """.trimIndent(),
        ).use { statement ->
            statement.setInt(1, rating)
            statement.setString(2, encodeTags(tags))
            statement.setString(3, comment)
            statement.setString(4, VisitFeedbackStatus.SUBMITTED.name)
            statement.setTimestamp(5, Timestamp.from(now))
            statement.setLong(6, feedbackId)
            statement.executeUpdate()
        }
    }

    private fun computeScheduledFor(
        closedAt: Instant,
        zoneId: ZoneId,
    ): Instant {
        val localClosedAt = closedAt.atZone(zoneId)
        return localClosedAt
            .toLocalDate()
            .plusDays(1)
            .atTime(LocalTime.NOON)
            .atZone(zoneId)
            .toInstant()
    }

    private fun ResultSet.toFeedbackVisit(): FeedbackVisit {
        val timezone = getString("timezone")
        val zoneId =
            runCatching {
                ZoneId.of(
                    timezone,
                )
            }.getOrElse { ZoneId.of(VenueSettingsRepository.DEFAULT_AUTO_TIMEZONE) }
        return FeedbackVisit(
            visitId = getLong("visit_id"),
            venueId = getLong("venue_id"),
            userId = getLong("user_id"),
            hasClosedOrder = getBoolean("has_closed_order"),
            zoneId = zoneId,
        )
    }

    private fun ResultSet.toFeedbackRequestRecord(): VisitFeedbackRequestRecord =
        VisitFeedbackRequestRecord(
            id = getLong("id"),
            visitId = getLong("visit_id"),
            venueId = getLong("venue_id"),
            userId = getLong("user_id"),
            scheduledFor = getTimestamp("scheduled_for").toInstant(),
            status = VisitFeedbackRequestStatus.valueOf(getString("status")),
            attempts = getInt("attempts"),
        )

    private fun ResultSet.toFeedbackRequestDelivery(): VisitFeedbackRequestDelivery =
        VisitFeedbackRequestDelivery(
            requestId = getLong("request_id"),
            visitId = getLong("visit_id"),
            venueId = getLong("venue_id"),
            userId = getLong("user_id"),
            venueName = getString("venue_name"),
            attempts = getInt("attempts"),
        )

    private fun ResultSet.toFeedbackRecord(): VisitFeedbackRecord {
        val ratingValue = getInt("rating")
        val ratingWasNull = wasNull()
        val staffNotified = getTimestamp("staff_notified_at")
        val commentStaffNotified = getTimestamp("comment_staff_notified_at")
        val createdAt = getTimestamp("created_at")
        return VisitFeedbackRecord(
            id = getLong("id"),
            visitId = getLong("visit_id"),
            venueId = getLong("venue_id"),
            userId = getLong("user_id"),
            rating = if (ratingWasNull) null else ratingValue,
            comment = getString("comment"),
            status = VisitFeedbackStatus.valueOf(getString("status")),
            staffNotifiedAt = staffNotified?.toInstant(),
            commentStaffNotifiedAt = commentStaffNotified?.toInstant(),
            tags = decodeTags(getString("tags_json")),
            createdAt = createdAt?.toInstant(),
        )
    }

    private fun ResultSet.toFeedbackThread(): VisitFeedbackThread {
        val ratingValue = getInt("rating")
        val ratingWasNull = wasNull()
        return VisitFeedbackThread(
            feedbackId = getLong("feedback_id"),
            visitId = getLong("visit_id"),
            venueId = getLong("venue_id"),
            guestUserId = getLong("guest_user_id"),
            rating = if (ratingWasNull) null else ratingValue,
            comment = getString("comment"),
            status = VisitFeedbackStatus.valueOf(getString("status")),
            venueName = getString("venue_name"),
        )
    }

    private fun ResultSet.toVenueFeedbackSummary(): VisitFeedbackVenueSummary {
        val ratingValue = getInt("rating")
        val ratingWasNull = wasNull()
        return VisitFeedbackVenueSummary(
            feedbackId = getLong("feedback_id"),
            visitId = getLong("visit_id"),
            venueId = getLong("venue_id"),
            guestUserId = getLong("guest_user_id"),
            guestDisplayName = getString("guest_display_name"),
            rating = if (ratingWasNull) null else ratingValue,
            comment = getString("comment"),
            status = VisitFeedbackStatus.valueOf(getString("status")),
            occurredAt = getTimestamp("occurred_at").toInstant(),
            serviceDate = getDate("service_date")?.toLocalDate(),
            hasStaffReply = getBoolean("has_staff_reply"),
            requiresAnswer = getBoolean("requires_answer"),
            tags = decodeTags(getString("tags_json")),
            createdAt = getTimestamp("created_at")?.toInstant(),
        )
    }

    private fun ResultSet.toVenueFeedbackDetail(messages: List<VisitFeedbackMessageRecord>): VisitFeedbackVenueDetail {
        val ratingValue = getInt("rating")
        val ratingWasNull = wasNull()
        return VisitFeedbackVenueDetail(
            feedbackId = getLong("feedback_id"),
            visitId = getLong("visit_id"),
            venueId = getLong("venue_id"),
            venueName = getString("venue_name"),
            guestUserId = getLong("guest_user_id"),
            guestDisplayName = getString("guest_display_name"),
            rating = if (ratingWasNull) null else ratingValue,
            comment = getString("comment"),
            status = VisitFeedbackStatus.valueOf(getString("status")),
            occurredAt = getTimestamp("occurred_at").toInstant(),
            serviceDate = getDate("service_date")?.toLocalDate(),
            hasStaffReply = getBoolean("has_staff_reply"),
            requiresAnswer = getBoolean("requires_answer"),
            messages = messages,
            tags = decodeTags(getString("tags_json")),
            createdAt = getTimestamp("created_at")?.toInstant(),
        )
    }

    private fun ResultSet.toFeedbackMessageRecord(): VisitFeedbackMessageRecord {
        val senderUserIdValue = getLong("sender_user_id")
        val senderUserIdWasNull = wasNull()
        return VisitFeedbackMessageRecord(
            id = getLong("id"),
            feedbackId = getLong("feedback_id"),
            visitId = getLong("visit_id"),
            venueId = getLong("venue_id"),
            guestUserId = getLong("guest_user_id"),
            senderType = VisitFeedbackMessageSender.valueOf(getString("sender_type")),
            senderUserId = if (senderUserIdWasNull) null else senderUserIdValue,
            messageText = getString("message_text"),
            createdAt = getTimestamp("created_at").toInstant(),
        )
    }

    private fun normalizeTags(tags: List<String>): List<String> {
        require(tags.size <= MAX_TAGS) { "too many tags" }
        return tags.map { tag ->
            val normalized = tag.trim()
            require(normalized in ALLOWED_TAGS) { "unknown feedback tag" }
            normalized
        }.distinct()
    }

    private fun normalizeComment(comment: String?): String? {
        val normalized = comment?.trim()?.takeIf { it.isNotBlank() }
        require((normalized?.length ?: 0) <= MAX_COMMENT_LENGTH) { "comment is too long" }
        return normalized
    }

    private fun encodeTags(tags: List<String>): String = json.encodeToString(tags)

    private fun decodeTags(raw: String?): List<String> =
        runCatching {
            json.decodeFromString<List<String>>(raw ?: "[]")
                .filter { it in ALLOWED_TAGS }
                .distinct()
        }.getOrDefault(emptyList())

    private fun SQLException.isDuplicateKeyViolation(): Boolean {
        if (sqlState == "23505") return true
        return generateSequence(nextException) { it.nextException }.any { it.sqlState == "23505" }
    }

    private data class FeedbackVisit(
        val visitId: Long,
        val venueId: Long,
        val userId: Long,
        val hasClosedOrder: Boolean,
        val zoneId: ZoneId,
    )

    private companion object {
        const val DEFAULT_BATCH_SIZE: Int = 100
        const val MAX_COMMENT_LENGTH: Int = 1000
        const val MAX_MESSAGE_LENGTH: Int = 1000
        const val MAX_TAGS: Int = 5
        val ALLOWED_TAGS: Set<String> =
            setOf(
                "service",
                "hookah_quality",
                "taste",
                "speed",
                "atmosphere",
                "cleanliness",
                "booking",
                "price",
            )
        val json: Json = Json { ignoreUnknownKeys = true }
    }
}
