package com.hookah.platform.backend.support

import com.hookah.platform.backend.api.DatabaseUnavailableException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.sql.Connection
import java.sql.ResultSet
import java.sql.SQLException
import java.sql.Statement
import java.sql.Types
import java.time.Instant
import javax.sql.DataSource

enum class SupportThreadCategory {
    BOOKING,
    ORDER_SERVICE,
    MINIAPP_TECHNICAL,
    BILLING,
    OTHER,

    // Legacy values kept so old rows or fixtures do not crash before migration.
    GENERAL,
    ORDER,
    TABLE,
    PLATFORM,
}

enum class SupportThreadStatus {
    OPEN,
    NEW,
    IN_PROGRESS,
    WAITING_USER,
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
    PLATFORM_MINIAPP,
    STAFF_CHAT,
    SYSTEM,
}

enum class SupportThreadType {
    BOOKING_THREAD,
    SUPPORT_TICKET,
}

enum class SupportAssigneeScope {
    VENUE,
    PLATFORM,
}

enum class SupportThreadCreatedSource {
    BOOKING_FLOW,
    GUEST_MINIAPP,
    GUEST_BOT,
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
    val venueId: Long?,
    val venueName: String?,
    val guestDisplayName: String? = null,
    val guestUserId: Long,
    val threadType: SupportThreadType = SupportThreadType.BOOKING_THREAD,
    val assigneeScope: SupportAssigneeScope = SupportAssigneeScope.VENUE,
    val createdSource: SupportThreadCreatedSource = SupportThreadCreatedSource.BOOKING_FLOW,
    val category: SupportThreadCategory,
    val status: SupportThreadStatus,
    val bookingId: Long?,
    val orderId: Long?,
    val orderDisplayLabel: String? = null,
    val tableId: Long? = null,
    val tableSessionId: Long?,
    val tableLabel: String? = null,
    val appVersion: String? = null,
    val correlationId: String? = null,
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

data class SupportOrderContextRecord(
    val orderId: Long,
    val venueId: Long,
    val tableId: Long?,
    val tableSessionId: Long?,
    val displayLabel: String?,
)

data class SupportTicketCreateInput(
    val guestUserId: Long,
    val category: SupportThreadCategory,
    val title: String,
    val message: String,
    val venueId: Long? = null,
    val tableId: Long? = null,
    val tableSessionId: Long? = null,
    val orderId: Long? = null,
    val bookingId: Long? = null,
    val assigneeScope: SupportAssigneeScope,
    val createdSource: SupportThreadCreatedSource,
    val messageSource: SupportMessageSource,
    val appVersion: String? = null,
    val correlationId: String? = null,
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
        statusAfterInsert: SupportThreadStatus? = statusAfterMessage(authorRole),
    ): SupportMessageRecord {
        val ds = dataSource ?: throw DatabaseUnavailableException()
        return withContext(Dispatchers.IO) {
            ds.connection.use { connection ->
                val messageId =
                    insertMessage(
                        connection = connection,
                        threadId = threadId,
                        authorUserId = authorUserId,
                        authorRole = authorRole,
                        source = source,
                        text = text,
                        telegramMessageId = telegramMessageId,
                    )
                updateThreadAfterMessage(connection, threadId, statusAfterInsert)
                selectMessage(connection, messageId) ?: error("support message was not found after insert")
            }
        }
    }

    open suspend fun createTicket(input: SupportTicketCreateInput): SupportThreadDetailRecord {
        val ds = dataSource ?: throw DatabaseUnavailableException()
        return withContext(Dispatchers.IO) {
            ds.connection.use { connection ->
                val previousAutoCommit = connection.autoCommit
                connection.autoCommit = false
                try {
                    val threadId =
                        insertTicketThread(
                            connection = connection,
                            input = input,
                        )
                    insertMessage(
                        connection = connection,
                        threadId = threadId,
                        authorUserId = input.guestUserId,
                        authorRole = SupportMessageAuthorRole.GUEST,
                        source = input.messageSource,
                        text = input.message,
                        telegramMessageId = null,
                    )
                    updateThreadAfterMessage(connection, threadId, SupportThreadStatus.NEW)
                    connection.commit()
                    val thread =
                        selectGuestThread(connection, input.guestUserId, threadId)
                            ?: error("support ticket was not found after insert")
                    SupportThreadDetailRecord(thread = thread, messages = listMessages(connection, threadId))
                } catch (e: Throwable) {
                    connection.rollback()
                    throw e
                } finally {
                    connection.autoCommit = previousAutoCommit
                }
            }
        }
    }

    open suspend fun findOrderContextForGuest(
        orderId: Long,
        userId: Long,
        venueId: Long? = null,
        tableSessionId: Long? = null,
    ): SupportOrderContextRecord? {
        val ds = dataSource ?: throw DatabaseUnavailableException()
        return withContext(Dispatchers.IO) {
            ds.connection.use { connection ->
                val venueFilter = if (venueId == null) "" else "AND o.venue_id = ?"
                val sessionFilter = if (tableSessionId == null) "" else "AND o.table_session_id = ?"
                connection.prepareStatement(
                    """
                    SELECT o.id,
                           o.venue_id,
                           o.table_id,
                           o.table_session_id,
                           o.display_number,
                           o.display_date
                    FROM orders o
                    WHERE o.id = ?
                      $venueFilter
                      $sessionFilter
                      AND EXISTS (
                          SELECT 1
                          FROM order_batches ob
                          LEFT JOIN guest_batch_idempotency gbi
                            ON gbi.batch_id = ob.id
                           AND gbi.user_id = ?
                          WHERE ob.order_id = o.id
                            AND (
                                ob.author_user_id = ?
                                OR gbi.user_id IS NOT NULL
                                OR EXISTS (
                                    SELECT 1
                                    FROM tab_member tm
                                    WHERE tm.tab_id = ob.tab_id
                                      AND tm.user_id = ?
                                )
                            )
                      )
                    """.trimIndent(),
                ).use { statement ->
                    var index = 1
                    statement.setLong(index++, orderId)
                    if (venueId != null) statement.setLong(index++, venueId)
                    if (tableSessionId != null) statement.setLong(index++, tableSessionId)
                    statement.setLong(index++, userId)
                    statement.setLong(index++, userId)
                    statement.setLong(index, userId)
                    statement.executeQuery().use { rs ->
                        if (!rs.next()) {
                            null
                        } else {
                            val displayNumber = rs.getInt("display_number").takeUnless { rs.wasNull() }
                            val displayDate = rs.getDate("display_date")?.toLocalDate()
                            SupportOrderContextRecord(
                                orderId = rs.getLong("id"),
                                venueId = rs.getLong("venue_id"),
                                tableId = rs.getNullableLong("table_id"),
                                tableSessionId = rs.getNullableLong("table_session_id"),
                                displayLabel =
                                    displayNumber?.let {
                                        if (displayDate == null) "Заказ №$it" else "Заказ №$it от $displayDate"
                                    } ?: "Заказ #${rs.getLong("id")}",
                            )
                        }
                    }
                }
            }
        }
    }

    open suspend fun listVenueThreads(
        venueId: Long,
        viewerUserId: Long,
        bookingId: Long? = null,
        filter: SupportInboxFilter? = null,
        threadType: SupportThreadType? = null,
    ): List<SupportThreadRecord> {
        val ds = dataSource ?: throw DatabaseUnavailableException()
        return withContext(Dispatchers.IO) {
            ds.connection.use { connection ->
                val bookingFilter = if (bookingId == null) "" else "AND st.booking_id = ?"
                val typeFilter = if (threadType == null) "" else "AND st.thread_type = ?"
                val statusFilter = statusFilterCondition(filter)
                connection.prepareStatement(
                    """
                    ${threadSelect(unreadCountExpression())}
                    WHERE st.venue_id = ?
                      $bookingFilter
                      $typeFilter
                      $statusFilter
                    ORDER BY COALESCE(st.last_message_at, st.created_at) DESC, st.id DESC
                    LIMIT 100
                    """.trimIndent(),
                ).use { statement ->
                    var index = 1
                    statement.setLong(index++, viewerUserId)
                    statement.setLong(index++, viewerUserId)
                    statement.setLong(index++, venueId)
                    if (bookingId != null) {
                        statement.setLong(index++, bookingId)
                    }
                    if (threadType != null) {
                        statement.setString(index, threadType.name)
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

    open suspend fun listPlatformThreads(
        viewerUserId: Long,
        filter: SupportInboxFilter? = null,
        assigneeScope: SupportAssigneeScope? = null,
        venueId: Long? = null,
        threadType: SupportThreadType? = null,
    ): List<SupportThreadRecord> {
        val ds = dataSource ?: throw DatabaseUnavailableException()
        return withContext(Dispatchers.IO) {
            ds.connection.use { connection ->
                val statusFilter = statusFilterCondition(filter)
                val scopeFilter = if (assigneeScope == null) "" else "AND st.assignee_scope = ?"
                val venueFilter = if (venueId == null) "" else "AND st.venue_id = ?"
                val typeFilter = if (threadType == null) "" else "AND st.thread_type = ?"
                connection.prepareStatement(
                    """
                    ${threadSelect(unreadCountExpression())}
                    WHERE 1 = 1
                      $statusFilter
                      $scopeFilter
                      $venueFilter
                      $typeFilter
                    ORDER BY COALESCE(st.last_message_at, st.created_at) DESC, st.id DESC
                    LIMIT 200
                    """.trimIndent(),
                ).use { statement ->
                    var index = 1
                    statement.setLong(index++, viewerUserId)
                    statement.setLong(index++, viewerUserId)
                    if (assigneeScope != null) {
                        statement.setString(index++, assigneeScope.name)
                    }
                    if (venueId != null) {
                        statement.setLong(index++, venueId)
                    }
                    if (threadType != null) {
                        statement.setString(index, threadType.name)
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
        threadType: SupportThreadType? = null,
    ): List<SupportThreadRecord> {
        val ds = dataSource ?: throw DatabaseUnavailableException()
        return withContext(Dispatchers.IO) {
            ds.connection.use { connection ->
                val statusFilter = statusFilterCondition(filter)
                val typeFilter = if (threadType == null) "" else "AND st.thread_type = ?"
                connection.prepareStatement(
                    """
                    ${threadSelect(unreadCountExpression())}
                    WHERE st.guest_user_id = ?
                      $typeFilter
                      $statusFilter
                    ORDER BY COALESCE(st.last_message_at, st.created_at) DESC, st.id DESC
                    LIMIT 100
                    """.trimIndent(),
                ).use { statement ->
                    var index = 1
                    statement.setLong(index++, userId)
                    statement.setLong(index++, userId)
                    statement.setLong(index++, userId)
                    if (threadType != null) {
                        statement.setString(index, threadType.name)
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

    open suspend fun updateThreadAssigneeScope(
        threadId: Long,
        assigneeScope: SupportAssigneeScope,
    ) {
        val ds = dataSource ?: throw DatabaseUnavailableException()
        withContext(Dispatchers.IO) {
            ds.connection.use { connection ->
                connection.prepareStatement(
                    """
                    UPDATE support_threads
                    SET assignee_scope = ?,
                        updated_at = NOW()
                    WHERE id = ?
                    """.trimIndent(),
                ).use { statement ->
                    statement.setString(1, assigneeScope.name)
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

    open suspend fun getPlatformThread(threadId: Long): SupportThreadDetailRecord? {
        val ds = dataSource ?: throw DatabaseUnavailableException()
        return withContext(Dispatchers.IO) {
            ds.connection.use { connection ->
                val thread = selectThread(connection, threadId) ?: return@withContext null
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
              AND (st.thread_type = 'BOOKING_THREAD' OR st.thread_type IS NULL)
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
                    thread_type,
                    assignee_scope,
                    created_source,
                    title
                )
                VALUES (?, ?, 'BOOKING', 'IN_PROGRESS', ?, 'BOOKING_THREAD', 'VENUE', 'BOOKING_FLOW', ?)
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

    private fun selectThread(
        connection: Connection,
        threadId: Long,
    ): SupportThreadRecord? =
        connection.prepareStatement(
            """
            ${threadSelect()}
            WHERE st.id = ?
            """.trimIndent(),
        ).use { statement ->
            statement.setLong(1, threadId)
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

    private fun insertTicketThread(
        connection: Connection,
        input: SupportTicketCreateInput,
    ): Long =
        connection.prepareStatement(
            """
            INSERT INTO support_threads (
                venue_id,
                guest_user_id,
                category,
                status,
                booking_id,
                order_id,
                table_id,
                table_session_id,
                thread_type,
                assignee_scope,
                created_source,
                app_version,
                correlation_id,
                title
            )
            VALUES (?, ?, ?, 'NEW', ?, ?, ?, ?, 'SUPPORT_TICKET', ?, ?, ?, ?, ?)
            """.trimIndent(),
            Statement.RETURN_GENERATED_KEYS,
        ).use { statement ->
            statement.setNullableLong(1, input.venueId)
            statement.setLong(2, input.guestUserId)
            statement.setString(3, input.category.name)
            statement.setNullableLong(4, input.bookingId)
            statement.setNullableLong(5, input.orderId)
            statement.setNullableLong(6, input.tableId)
            statement.setNullableLong(7, input.tableSessionId)
            statement.setString(8, input.assigneeScope.name)
            statement.setString(9, input.createdSource.name)
            statement.setNullableString(10, input.appVersion)
            statement.setNullableString(11, input.correlationId)
            statement.setString(12, input.title)
            statement.executeUpdate()
            statement.generatedKeys.use { keys ->
                if (keys.next()) keys.getLong(1) else error("support ticket id was not generated")
            }
        }

    private fun insertMessage(
        connection: Connection,
        threadId: Long,
        authorUserId: Long?,
        authorRole: SupportMessageAuthorRole,
        source: SupportMessageSource,
        text: String,
        telegramMessageId: Long?,
    ): Long =
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
            statement.setNullableLong(2, authorUserId)
            statement.setString(3, authorRole.name)
            statement.setString(4, source.name)
            statement.setString(5, text)
            statement.setNullableLong(6, telegramMessageId)
            statement.executeUpdate()
            statement.generatedKeys.use { keys ->
                if (keys.next()) keys.getLong(1) else error("support message id was not generated")
            }
        }

    private fun updateThreadAfterMessage(
        connection: Connection,
        threadId: Long,
        status: SupportThreadStatus?,
    ) {
        val statusAssignment = if (status == null) "" else ", status = ?"
        connection.prepareStatement(
            """
            UPDATE support_threads
            SET last_message_at = NOW(),
                updated_at = NOW()
                $statusAssignment
            WHERE id = ?
            """.trimIndent(),
        ).use { statement ->
            if (status == null) {
                statement.setLong(1, threadId)
            } else {
                statement.setString(1, status.name)
                statement.setLong(2, threadId)
            }
            statement.executeUpdate()
        }
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
            venueId = getNullableLong("venue_id"),
            venueName = getString("venue_name"),
            guestDisplayName = buildGuestDisplayName(),
            guestUserId = getLong("guest_user_id"),
            threadType = enumValueOrDefault(getString("thread_type"), SupportThreadType.BOOKING_THREAD),
            assigneeScope = enumValueOrDefault(getString("assignee_scope"), SupportAssigneeScope.VENUE),
            createdSource = enumValueOrDefault(getString("created_source"), SupportThreadCreatedSource.BOOKING_FLOW),
            category = SupportThreadCategory.valueOf(getString("category")),
            status = SupportThreadStatus.valueOf(getString("status")),
            bookingId = bookingId,
            orderId = getLong("order_id").takeUnless { wasNull() },
            orderDisplayLabel = getString("order_display_label"),
            tableId = getNullableLong("table_id"),
            tableSessionId = getLong("table_session_id").takeUnless { wasNull() },
            tableLabel = getString("table_label"),
            appVersion = getString("app_version"),
            correlationId = getString("correlation_id"),
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
                SupportInboxFilter.ACTIVE -> "AND st.status IN ('OPEN', 'NEW', 'IN_PROGRESS', 'WAITING_USER')"
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
                   st.thread_type AS thread_type,
                   st.assignee_scope AS assignee_scope,
                   st.created_source AS created_source,
                   st.category AS category,
                   st.status AS status,
                   st.booking_id AS booking_id,
                   st.order_id AS order_id,
                   CASE
                       WHEN o.display_number IS NULL THEN NULL
                       WHEN o.display_date IS NULL THEN 'Заказ №' || o.display_number
                       ELSE 'Заказ №' || o.display_number || ' от ' || o.display_date
                   END AS order_display_label,
                   st.table_id AS table_id,
                   st.table_session_id AS table_session_id,
                   CASE
                       WHEN vt.table_number IS NULL THEN NULL
                       ELSE 'Стол №' || vt.table_number
                   END AS table_label,
                   st.app_version AS app_version,
                   st.correlation_id AS correlation_id,
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
            LEFT JOIN venues v ON v.id = st.venue_id
            LEFT JOIN users u ON u.telegram_user_id = st.guest_user_id
            LEFT JOIN bookings b ON b.id = st.booking_id
            LEFT JOIN orders o ON o.id = st.order_id
            LEFT JOIN venue_tables vt ON vt.id = COALESCE(st.table_id, o.table_id)
            """
    }
}

private fun statusAfterMessage(authorRole: SupportMessageAuthorRole): SupportThreadStatus =
    when (authorRole) {
        SupportMessageAuthorRole.GUEST -> SupportThreadStatus.IN_PROGRESS
        SupportMessageAuthorRole.VENUE,
        SupportMessageAuthorRole.PLATFORM,
        SupportMessageAuthorRole.SYSTEM,
        -> SupportThreadStatus.WAITING_USER
    }

private fun java.sql.PreparedStatement.setNullableLong(
    index: Int,
    value: Long?,
) {
    if (value == null) {
        setNull(index, Types.BIGINT)
    } else {
        setLong(index, value)
    }
}

private fun java.sql.PreparedStatement.setNullableString(
    index: Int,
    value: String?,
) {
    if (value == null) {
        setNull(index, Types.VARCHAR)
    } else {
        setString(index, value)
    }
}

private fun ResultSet.getNullableLong(column: String): Long? {
    val value = getLong(column)
    return if (wasNull()) null else value
}

private inline fun <reified T : Enum<T>> enumValueOrDefault(
    value: String?,
    default: T,
): T = value?.let { runCatching { enumValueOf<T>(it) }.getOrNull() } ?: default
