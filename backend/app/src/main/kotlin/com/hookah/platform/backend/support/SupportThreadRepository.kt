package com.hookah.platform.backend.support

import com.hookah.platform.backend.api.DatabaseUnavailableException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.sql.Connection
import java.sql.ResultSet
import java.sql.SQLException
import java.sql.Statement
import java.time.Instant
import javax.sql.DataSource

enum class SupportThreadCategory {
    BOOKING,
    GENERAL,
    ORDER,
    TABLE,
    PLATFORM,
}

enum class SupportThreadStatus {
    OPEN,
    RESOLVED,
    CLOSED,
}

enum class SupportInboxFilter {
    ACTIVE,
    RESOLVED,
}

enum class SupportMessageAuthorRole {
    GUEST,
    VENUE,
    PLATFORM,
    SYSTEM,
}

enum class SupportMessageSource {
    GUEST_BOT,
    GUEST_MINIAPP,
    VENUE_MINIAPP,
    STAFF_CHAT,
    SYSTEM,
}

data class SupportBookingContextRecord(
    val bookingId: Long,
    val displayNumber: Int?,
    val scheduledAt: Instant?,
    val partySize: Int?,
    val status: String?,
)

data class SupportThreadRecord(
    val id: Long,
    val venueId: Long,
    val venueName: String?,
    val guestDisplayName: String? = null,
    val guestUserId: Long,
    val category: SupportThreadCategory,
    val status: SupportThreadStatus,
    val bookingId: Long?,
    val orderId: Long?,
    val tableSessionId: Long?,
    val title: String,
    val lastMessagePreview: String? = null,
    val lastMessageAt: Instant?,
    val unreadCount: Int = 0,
    val createdAt: Instant,
    val updatedAt: Instant,
    val booking: SupportBookingContextRecord?,
)

data class SupportMessageRecord(
    val id: Long,
    val threadId: Long,
    val authorUserId: Long?,
    val authorRole: SupportMessageAuthorRole,
    val source: SupportMessageSource,
    val text: String,
    val telegramMessageId: Long?,
    val createdAt: Instant,
)

data class SupportThreadDetailRecord(
    val thread: SupportThreadRecord,
    val messages: List<SupportMessageRecord>,
)

open class SupportThreadRepository(private val dataSource: DataSource?) {
    open suspend fun createOrFindBookingThread(
        venueId: Long,
        bookingId: Long,
        guestUserId: Long,
        title: String? = null,
    ): SupportThreadRecord {
        val ds = dataSource ?: throw DatabaseUnavailableException()
        return withContext(Dispatchers.IO) {
            ds.connection.use { connection ->
                selectBookingThread(connection, venueId, bookingId)
                    ?: insertBookingThread(
                        connection = connection,
                        venueId = venueId,
                        bookingId = bookingId,
                        guestUserId = guestUserId,
                        title = title?.takeIf { it.isNotBlank() } ?: "Бронь #$bookingId",
                    )
            }
        }
    }

    open suspend fun addMessage(
        threadId: Long,
        authorUserId: Long?,
        authorRole: SupportMessageAuthorRole,
        source: SupportMessageSource,
        text: String,
        telegramMessageId: Long? = null,
    ): SupportMessageRecord {
        val ds = dataSource ?: throw DatabaseUnavailableException()
        return withContext(Dispatchers.IO) {
            ds.connection.use { connection ->
                val messageId =
                    connection.prepareStatement(
                        """
                        INSERT INTO support_messages (
                            thread_id,
                            author_user_id,
                            author_role,
                            source,
                            text,
                            telegram_message_id
                        )
                        VALUES (?, ?, ?, ?, ?, ?)
                        """.trimIndent(),
                        Statement.RETURN_GENERATED_KEYS,
                    ).use { statement ->
                        statement.setLong(1, threadId)
                        if (authorUserId == null) {
                            statement.setObject(2, null)
                        } else {
                            statement.setLong(2, authorUserId)
                        }
                        statement.setString(3, authorRole.name)
                        statement.setString(4, source.name)
                        statement.setString(5, text)
                        if (telegramMessageId == null) {
                            statement.setObject(6, null)
                        } else {
                            statement.setLong(6, telegramMessageId)
                        }
                        statement.executeUpdate()
                        statement.generatedKeys.use { keys ->
                            if (keys.next()) keys.getLong(1) else error("support message id was not generated")
                        }
                    }
                connection.prepareStatement(
                    """
                    UPDATE support_threads
                    SET last_message_at = NOW(),
                        updated_at = NOW(),
                        status = 'OPEN'
                    WHERE id = ?
                    """.trimIndent(),
                ).use { statement ->
                    statement.setLong(1, threadId)
                    statement.executeUpdate()
                }
                selectMessage(connection, messageId) ?: error("support message was not found after insert")
            }
        }
    }

    open suspend fun listVenueThreads(
        venueId: Long,
        viewerUserId: Long,
        bookingId: Long? = null,
        filter: SupportInboxFilter? = null,
    ): List<SupportThreadRecord> {
        val ds = dataSource ?: throw DatabaseUnavailableException()
        return withContext(Dispatchers.IO) {
            ds.connection.use { connection ->
                val bookingFilter = if (bookingId == null) "" else "AND st.booking_id = ?"
                val statusFilter = statusFilterCondition(filter)
                connection.prepareStatement(
                    """
                    ${threadSelect(unreadCountExpression())}
                    WHERE st.venue_id = ?
                      $bookingFilter
                      $statusFilter
                    ORDER BY COALESCE(st.last_message_at, st.created_at) DESC, st.id DESC
                    LIMIT 100
                    """.trimIndent(),
                ).use { statement ->
                    statement.setLong(1, viewerUserId)
                    statement.setLong(2, viewerUserId)
                    statement.setLong(3, venueId)
                    if (bookingId != null) {
                        statement.setLong(4, bookingId)
                    }
                    statement.executeQuery().use { rs ->
                        buildList {
                            while (rs.next()) {
                                add(rs.toThreadRecord())
                            }
                        }
                    }
                }
            }
        }
    }

    open suspend fun getVenueThread(
        venueId: Long,
        threadId: Long,
    ): SupportThreadDetailRecord? {
        val ds = dataSource ?: throw DatabaseUnavailableException()
        return withContext(Dispatchers.IO) {
            ds.connection.use { connection ->
                val thread = selectVenueThread(connection, venueId, threadId) ?: return@withContext null
                SupportThreadDetailRecord(thread = thread, messages = listMessages(connection, threadId))
            }
        }
    }

    open suspend fun listGuestThreads(userId: Long): List<SupportThreadRecord> {
        return listGuestThreads(userId = userId, filter = null)
    }

    open suspend fun listGuestThreads(
        userId: Long,
        filter: SupportInboxFilter?,
    ): List<SupportThreadRecord> {
        val ds = dataSource ?: throw DatabaseUnavailableException()
        return withContext(Dispatchers.IO) {
            ds.connection.use { connection ->
                val statusFilter = statusFilterCondition(filter)
                connection.prepareStatement(
                    """
                    ${threadSelect(unreadCountExpression())}
                    WHERE st.guest_user_id = ?
                      $statusFilter
                    ORDER BY COALESCE(st.last_message_at, st.created_at) DESC, st.id DESC
                    LIMIT 100
                    """.trimIndent(),
                ).use { statement ->
                    statement.setLong(1, userId)
                    statement.setLong(2, userId)
                    statement.setLong(3, userId)
                    statement.executeQuery().use { rs ->
                        buildList {
                            while (rs.next()) {
                                add(rs.toThreadRecord())
                            }
                        }
                    }
                }
            }
        }
    }

    open suspend fun markThreadRead(
        threadId: Long,
        userId: Long,
    ) {
        val ds = dataSource ?: throw DatabaseUnavailableException()
        withContext(Dispatchers.IO) {
            ds.connection.use { connection ->
                val updated =
                    connection.prepareStatement(
                        """
                        UPDATE support_thread_reads
                        SET last_read_at = CURRENT_TIMESTAMP
                        WHERE thread_id = ?
                          AND user_id = ?
                        """.trimIndent(),
                    ).use { statement ->
                        statement.setLong(1, threadId)
                        statement.setLong(2, userId)
                        statement.executeUpdate()
                    }
                if (updated > 0) return@use
                try {
                    connection.prepareStatement(
                        """
                        INSERT INTO support_thread_reads (thread_id, user_id, last_read_at)
                        VALUES (?, ?, CURRENT_TIMESTAMP)
                        """.trimIndent(),
                    ).use { statement ->
                        statement.setLong(1, threadId)
                        statement.setLong(2, userId)
                        statement.executeUpdate()
                    }
                } catch (_: SQLException) {
                    connection.prepareStatement(
                        """
                        UPDATE support_thread_reads
                        SET last_read_at = CURRENT_TIMESTAMP
                        WHERE thread_id = ?
                          AND user_id = ?
                        """.trimIndent(),
                    ).use { statement ->
                        statement.setLong(1, threadId)
                        statement.setLong(2, userId)
                        statement.executeUpdate()
                    }
                }
            }
        }
    }

    open suspend fun updateThreadStatus(
        threadId: Long,
        status: SupportThreadStatus,
    ) {
        val ds = dataSource ?: throw DatabaseUnavailableException()
        withContext(Dispatchers.IO) {
            ds.connection.use { connection ->
                connection.prepareStatement(
                    """
                    UPDATE support_threads
                    SET status = ?,
                        updated_at = NOW()
                    WHERE id = ?
                    """.trimIndent(),
                ).use { statement ->
                    statement.setString(1, status.name)
                    statement.setLong(2, threadId)
                    statement.executeUpdate()
                }
            }
        }
    }

    open suspend fun getGuestThread(
        userId: Long,
        threadId: Long,
    ): SupportThreadDetailRecord? {
        val ds = dataSource ?: throw DatabaseUnavailableException()
        return withContext(Dispatchers.IO) {
            ds.connection.use { connection ->
                val thread = selectGuestThread(connection, userId, threadId) ?: return@withContext null
                SupportThreadDetailRecord(thread = thread, messages = listMessages(connection, threadId))
            }
        }
    }

    private fun selectBookingThread(
        connection: Connection,
        venueId: Long,
        bookingId: Long,
    ): SupportThreadRecord? =
        connection.prepareStatement(
            """
            ${threadSelect()}
            WHERE st.venue_id = ?
              AND st.booking_id = ?
              AND st.category = 'BOOKING'
            ORDER BY st.id ASC
            LIMIT 1
            """.trimIndent(),
        ).use { statement ->
            statement.setLong(1, venueId)
            statement.setLong(2, bookingId)
            statement.executeQuery().use { rs -> if (rs.next()) rs.toThreadRecord() else null }
        }

    private fun insertBookingThread(
        connection: Connection,
        venueId: Long,
        bookingId: Long,
        guestUserId: Long,
        title: String,
    ): SupportThreadRecord {
        val threadId =
            connection.prepareStatement(
                """
                INSERT INTO support_threads (
                    venue_id,
                    guest_user_id,
                    category,
                    status,
                    booking_id,
                    title
                )
                VALUES (?, ?, 'BOOKING', 'OPEN', ?, ?)
                """.trimIndent(),
                Statement.RETURN_GENERATED_KEYS,
            ).use { statement ->
                statement.setLong(1, venueId)
                statement.setLong(2, guestUserId)
                statement.setLong(3, bookingId)
                statement.setString(4, title)
                statement.executeUpdate()
                statement.generatedKeys.use { keys ->
                    if (keys.next()) keys.getLong(1) else error("support thread id was not generated")
                }
            }
        return selectVenueThread(connection, venueId, threadId) ?: error("support thread was not found after insert")
    }

    private fun selectVenueThread(
        connection: Connection,
        venueId: Long,
        threadId: Long,
    ): SupportThreadRecord? =
        connection.prepareStatement(
            """
            ${threadSelect()}
            WHERE st.venue_id = ?
              AND st.id = ?
            """.trimIndent(),
        ).use { statement ->
            statement.setLong(1, venueId)
            statement.setLong(2, threadId)
            statement.executeQuery().use { rs -> if (rs.next()) rs.toThreadRecord() else null }
        }

    private fun selectGuestThread(
        connection: Connection,
        userId: Long,
        threadId: Long,
    ): SupportThreadRecord? =
        connection.prepareStatement(
            """
            ${threadSelect()}
            WHERE st.guest_user_id = ?
              AND st.id = ?
            """.trimIndent(),
        ).use { statement ->
            statement.setLong(1, userId)
            statement.setLong(2, threadId)
            statement.executeQuery().use { rs -> if (rs.next()) rs.toThreadRecord() else null }
        }

    private fun listMessages(
        connection: Connection,
        threadId: Long,
    ): List<SupportMessageRecord> =
        connection.prepareStatement(
            """
            SELECT id,
                   thread_id,
                   author_user_id,
                   author_role,
                   source,
                   text,
                   telegram_message_id,
                   created_at
            FROM support_messages
            WHERE thread_id = ?
            ORDER BY created_at ASC, id ASC
            """.trimIndent(),
        ).use { statement ->
            statement.setLong(1, threadId)
            statement.executeQuery().use { rs ->
                buildList {
                    while (rs.next()) {
                        add(rs.toMessageRecord())
                    }
                }
            }
        }

    private fun selectMessage(
        connection: Connection,
        messageId: Long,
    ): SupportMessageRecord? =
        connection.prepareStatement(
            """
            SELECT id,
                   thread_id,
                   author_user_id,
                   author_role,
                   source,
                   text,
                   telegram_message_id,
                   created_at
            FROM support_messages
            WHERE id = ?
            """.trimIndent(),
        ).use { statement ->
            statement.setLong(1, messageId)
            statement.executeQuery().use { rs -> if (rs.next()) rs.toMessageRecord() else null }
        }

    private fun ResultSet.toThreadRecord(): SupportThreadRecord {
        val bookingId = getLong("booking_id").takeUnless { wasNull() }
        val bookingDisplayNumber = getInt("booking_display_number").takeUnless { wasNull() }
        val bookingScheduledAt = getTimestamp("booking_scheduled_at")?.toInstant()
        val bookingPartySize = getInt("booking_party_size").takeUnless { wasNull() }
        val bookingStatus = getString("booking_status")
        return SupportThreadRecord(
            id = getLong("thread_id"),
            venueId = getLong("venue_id"),
            venueName = getString("venue_name"),
            guestDisplayName = buildGuestDisplayName(),
            guestUserId = getLong("guest_user_id"),
            category = SupportThreadCategory.valueOf(getString("category")),
            status = SupportThreadStatus.valueOf(getString("status")),
            bookingId = bookingId,
            orderId = getLong("order_id").takeUnless { wasNull() },
            tableSessionId = getLong("table_session_id").takeUnless { wasNull() },
            title = getString("title"),
            lastMessagePreview = getString("last_message_preview"),
            lastMessageAt = getTimestamp("last_message_at")?.toInstant(),
            unreadCount = getInt("unread_count").coerceAtLeast(0),
            createdAt = getTimestamp("created_at").toInstant(),
            updatedAt = getTimestamp("updated_at").toInstant(),
            booking =
                bookingId?.let {
                    SupportBookingContextRecord(
                        bookingId = it,
                        displayNumber = bookingDisplayNumber,
                        scheduledAt = bookingScheduledAt,
                        partySize = bookingPartySize,
                        status = bookingStatus,
                    )
                },
        )
    }

    private fun ResultSet.buildGuestDisplayName(): String? {
        val displayName = getString("guest_display_name")?.trim()?.takeIf { it.isNotBlank() }
        if (displayName != null) return displayName
        val firstName = getString("guest_first_name")?.trim()?.takeIf { it.isNotBlank() }
        val lastName = getString("guest_last_name")?.trim()?.takeIf { it.isNotBlank() }
        val fullName = listOfNotNull(firstName, lastName).joinToString(" ").takeIf { it.isNotBlank() }
        if (fullName != null) return fullName
        return getString("guest_username")?.trim()?.takeIf { it.isNotBlank() }?.let { "@${it.removePrefix("@")}" }
    }

    private fun ResultSet.toMessageRecord(): SupportMessageRecord =
        SupportMessageRecord(
            id = getLong("id"),
            threadId = getLong("thread_id"),
            authorUserId = getLong("author_user_id").takeUnless { wasNull() },
            authorRole = SupportMessageAuthorRole.valueOf(getString("author_role")),
            source = SupportMessageSource.valueOf(getString("source")),
            text = getString("text"),
            telegramMessageId = getLong("telegram_message_id").takeUnless { wasNull() },
            createdAt = getTimestamp("created_at").toInstant(),
        )

    private companion object {
        private fun statusFilterCondition(filter: SupportInboxFilter?): String =
            when (filter) {
                SupportInboxFilter.ACTIVE -> "AND st.status = 'OPEN'"
                SupportInboxFilter.RESOLVED -> "AND st.status IN ('RESOLVED', 'CLOSED')"
                null -> ""
            }

        private fun unreadCountExpression(): String =
            """
            (
                SELECT COUNT(*)
                FROM support_messages sm_unread
                LEFT JOIN support_thread_reads sr
                    ON sr.thread_id = st.id
                   AND sr.user_id = ?
                WHERE sm_unread.thread_id = st.id
                  AND (sm_unread.author_user_id IS NULL OR sm_unread.author_user_id <> ?)
                  AND (sr.last_read_at IS NULL OR sm_unread.created_at > sr.last_read_at)
            )
            """.trimIndent()

        private fun threadSelect(unreadExpression: String = "0"): String =
            """
            SELECT st.id AS thread_id,
                   st.venue_id AS venue_id,
                   v.name AS venue_name,
                   u.guest_display_name AS guest_display_name,
                   u.username AS guest_username,
                   u.first_name AS guest_first_name,
                   u.last_name AS guest_last_name,
                   st.guest_user_id AS guest_user_id,
                   st.category AS category,
                   st.status AS status,
                   st.booking_id AS booking_id,
                   st.order_id AS order_id,
                   st.table_session_id AS table_session_id,
                   st.title AS title,
                   st.last_message_at AS last_message_at,
                   (
                       SELECT sm_last.text
                       FROM support_messages sm_last
                       WHERE sm_last.thread_id = st.id
                       ORDER BY sm_last.created_at DESC, sm_last.id DESC
                       LIMIT 1
                   ) AS last_message_preview,
                   $unreadExpression AS unread_count,
                   st.created_at AS created_at,
                   st.updated_at AS updated_at,
                   b.display_number AS booking_display_number,
                   b.scheduled_at AS booking_scheduled_at,
                   b.party_size AS booking_party_size,
                   b.status AS booking_status
            FROM support_threads st
            JOIN venues v ON v.id = st.venue_id
            LEFT JOIN users u ON u.telegram_user_id = st.guest_user_id
            LEFT JOIN bookings b ON b.id = st.booking_id
            """
    }
}
