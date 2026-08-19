package com.hookah.platform.backend.support

import com.hookah.platform.backend.api.BookingMessageIdempotencyPayloadMismatchException
import com.hookah.platform.backend.api.DatabaseUnavailableException
import com.hookah.platform.backend.api.InvalidInputException
import com.hookah.platform.backend.telegram.db.VenueAccessRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.sql.Connection
import java.sql.ResultSet
import java.sql.SQLException
import java.sql.Statement
import java.sql.Timestamp
import java.sql.Types
import java.time.Instant
import javax.sql.DataSource

const val MAX_BOOKING_THREAD_LOOKUP_IDS = 100

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
    VENUE_CHAT,
}

enum class GuestThreadSurface(
    internal val expectedThreadTypes: Set<SupportThreadType>,
) {
    CONVERSATIONS(setOf(SupportThreadType.BOOKING_THREAD, SupportThreadType.VENUE_CHAT)),
    SUPPORT(setOf(SupportThreadType.SUPPORT_TICKET)),
    ;

    init {
        require(expectedThreadTypes.isNotEmpty())
    }
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

enum class BookingConversationCheckpoint {
    AFTER_INITIAL_THREAD_LOOKUP,
    AFTER_BOOKING_LOCK,
    AFTER_THREAD_RESOLVE,
    AFTER_MESSAGE_WRITE,
}

enum class SupportThreadReadCheckpoint {
    AFTER_THREAD_LOCK,
    AFTER_MARKER_WRITE,
}

sealed interface SupportThreadReadAccess {
    val userId: Long

    data class Guest(
        override val userId: Long,
    ) : SupportThreadReadAccess

    data class Venue(
        override val userId: Long,
        val venueId: Long,
    ) : SupportThreadReadAccess

    data class Platform(
        override val userId: Long,
        val platformOwnerUserId: Long?,
    ) : SupportThreadReadAccess
}

enum class SupportThreadReadResult {
    MARKED,
    NOT_FOUND,
    FORBIDDEN,
}

enum class BookingMessageNotificationKind(
    private val dedupeSuffix: String,
) {
    GUEST_NOTIFICATION("guest-notification"),
    GUEST_ACK("guest-ack"),
    ;

    fun dedupeKey(messageId: Long): String = "booking-thread-message:$messageId:$dedupeSuffix"
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
    val venueTimezone: String? = null,
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
    val clientMessageId: String? = null,
)

data class SupportThreadDetailRecord(
    val thread: SupportThreadRecord,
    val messages: List<SupportMessageRecord>,
)

data class GuestThreadOpenContextRecord(
    val threadId: Long,
    val venueId: Long?,
    val tableId: Long?,
    val tableSessionId: Long?,
    val threadType: SupportThreadType,
)

data class BookingThreadLookupRecord(
    val bookingId: Long,
    val thread: SupportThreadRecord?,
)

data class BookingThreadMessageRecord(
    val thread: SupportThreadRecord,
    val message: SupportMessageRecord,
    val created: Boolean = true,
)

data class BookingMessageNotificationContext(
    val kind: BookingMessageNotificationKind,
    val recipientChatId: Long,
    val thread: SupportThreadRecord,
    val message: SupportMessageRecord,
) {
    val dedupeKey: String = kind.dedupeKey(message.id)
}

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

private data class BookingThreadContext(
    val id: Long,
    val venueId: Long,
    val guestUserId: Long,
)

private enum class BookingThreadLookupScope(
    val columnName: String,
) {
    GUEST("user_id"),
    VENUE("venue_id"),
}

private data class SupportThreadWriteContext(
    val id: Long,
    val bookingId: Long?,
    val venueId: Long?,
    val tableId: Long?,
    val tableSessionId: Long?,
    val guestUserId: Long,
    val threadType: String?,
    val assigneeScope: String?,
    val status: String,
)

private data class SupportThreadReadPointer(
    val bookingId: Long?,
    val threadType: String?,
)

open class SupportThreadRepository(
    private val dataSource: DataSource?,
    private val venueAccessRepository: VenueAccessRepository = VenueAccessRepository(dataSource),
    private val supportThreadReadCheckpoint: (SupportThreadReadCheckpoint) -> Unit = {},
    private val bookingConversationCheckpoint: (BookingConversationCheckpoint) -> Unit = {},
) {
    open suspend fun lookupGuestBookingThreads(
        userId: Long,
        bookingIds: List<Long>,
    ): List<BookingThreadLookupRecord>? =
        lookupBookingThreads(
            bookingIds = bookingIds,
            viewerUserId = userId,
            scope = BookingThreadLookupScope.GUEST,
            scopeId = userId,
        )

    open suspend fun lookupVenueBookingThreads(
        venueId: Long,
        viewerUserId: Long,
        bookingIds: List<Long>,
    ): List<BookingThreadLookupRecord>? =
        lookupBookingThreads(
            bookingIds = bookingIds,
            viewerUserId = viewerUserId,
            scope = BookingThreadLookupScope.VENUE,
            scopeId = venueId,
        )

    private suspend fun lookupBookingThreads(
        bookingIds: List<Long>,
        viewerUserId: Long,
        scope: BookingThreadLookupScope,
        scopeId: Long,
    ): List<BookingThreadLookupRecord>? {
        require(bookingIds.size in 1..MAX_BOOKING_THREAD_LOOKUP_IDS)
        require(bookingIds.all { it > 0 })
        require(bookingIds.distinct().size == bookingIds.size)
        val ds = dataSource ?: throw DatabaseUnavailableException()
        try {
            return withContext(Dispatchers.IO) {
                ds.connection.use { connection ->
                    val bookings = selectBookingLookupContexts(connection, bookingIds, scope, scopeId)
                    if (bookings.size != bookingIds.size) return@withContext null
                    val threads = selectBookingThreadsForLookup(connection, bookingIds, viewerUserId)
                    val threadsByBookingId = mutableMapOf<Long, SupportThreadRecord>()
                    for (thread in threads) {
                        val bookingId = thread.bookingId ?: return@withContext null
                        val booking = bookings[bookingId] ?: return@withContext null
                        if (
                            thread.threadType != SupportThreadType.BOOKING_THREAD ||
                            thread.venueId != booking.venueId ||
                            thread.guestUserId != booking.guestUserId ||
                            threadsByBookingId.put(bookingId, thread) != null
                        ) {
                            return@withContext null
                        }
                    }
                    bookingIds.map { bookingId ->
                        BookingThreadLookupRecord(
                            bookingId = bookingId,
                            thread = threadsByBookingId[bookingId],
                        )
                    }
                }
            }
        } catch (_: SQLException) {
            throw DatabaseUnavailableException()
        }
    }

    open suspend fun createOrFindBookingThread(
        bookingId: Long,
        title: String? = null,
    ): SupportThreadRecord? {
        val ds = dataSource ?: throw DatabaseUnavailableException()
        try {
            return withContext(Dispatchers.IO) {
                ds.connection.use { connection ->
                    inTransaction(connection) {
                        val booking = lockBookingContext(connection, bookingId) ?: return@inTransaction null
                        bookingConversationCheckpoint(BookingConversationCheckpoint.AFTER_BOOKING_LOCK)
                        resolveBookingThread(connection, booking, title).also {
                            bookingConversationCheckpoint(BookingConversationCheckpoint.AFTER_THREAD_RESOLVE)
                        }
                    }
                }
            }
        } catch (_: SQLException) {
            throw DatabaseUnavailableException()
        }
    }

    open suspend fun addBookingMessage(
        bookingId: Long,
        authorUserId: Long?,
        authorRole: SupportMessageAuthorRole,
        source: SupportMessageSource,
        text: String,
        telegramMessageId: Long? = null,
        title: String? = null,
        statusAfterInsert: SupportThreadStatus? = statusAfterMessage(authorRole),
        expectedThreadId: Long? = null,
        expectedGuestUserId: Long? = null,
        expectedVenueId: Long? = null,
        clientMessageId: String? = null,
        beforeInsert: (() -> Unit)? = null,
        notificationWriter: ((Connection, BookingMessageNotificationContext) -> Unit)? = null,
        guestBotNotificationWriter: ((Connection, BookingThreadMessageRecord) -> Unit)? = null,
    ): BookingThreadMessageRecord? {
        val miniAppNotificationKind = miniAppNotificationKind(source)
        require((clientMessageId != null) == (miniAppNotificationKind != null))
        require(miniAppNotificationKind == null || authorUserId != null)
        require(miniAppNotificationKind == null || notificationWriter != null)
        require(guestBotNotificationWriter == null || source == SupportMessageSource.GUEST_BOT)
        val ds = dataSource ?: throw DatabaseUnavailableException()
        try {
            return withContext(Dispatchers.IO) {
                ds.connection.use { connection ->
                    inTransaction(connection) {
                        val initiallyObservedThread =
                            expectedThreadId?.let { selectThreadWriteContext(connection, it) }
                                ?: if (expectedThreadId == null) {
                                    selectBookingThreadWriteContext(connection, bookingId)
                                } else {
                                    return@inTransaction null
                                }
                        bookingConversationCheckpoint(BookingConversationCheckpoint.AFTER_INITIAL_THREAD_LOOKUP)
                        val authoritativeBookingId = initiallyObservedThread?.bookingId ?: bookingId
                        if (authoritativeBookingId != bookingId) return@inTransaction null

                        val booking =
                            lockBookingContext(connection, authoritativeBookingId)
                                ?: return@inTransaction null
                        bookingConversationCheckpoint(BookingConversationCheckpoint.AFTER_BOOKING_LOCK)
                        if (
                            !matchesExpectedBookingActor(
                                booking = booking,
                                authorUserId = authorUserId,
                                authorRole = authorRole,
                                expectedGuestUserId = expectedGuestUserId,
                                expectedVenueId = expectedVenueId,
                            )
                        ) {
                            return@inTransaction null
                        }

                        val currentThread =
                            expectedThreadId?.let { selectThreadWriteContext(connection, it) }
                                ?: if (expectedThreadId == null) {
                                    selectBookingThreadWriteContext(connection, booking.id)
                                } else {
                                    return@inTransaction null
                                }
                        val thread =
                            if (currentThread == null) {
                                resolveBookingThread(connection, booking, title)
                            } else {
                                if (!matchesBookingThread(currentThread, booking, expectedThreadId)) {
                                    return@inTransaction null
                                }
                                selectBookingThread(connection, booking.id)
                                    ?.takeIf { it.id == currentThread.id }
                                    ?: return@inTransaction null
                            }
                        bookingConversationCheckpoint(BookingConversationCheckpoint.AFTER_THREAD_RESOLVE)
                        val lockedThread = lockThread(connection, thread.id)
                        if (!matchesBookingThread(lockedThread, booking, expectedThreadId)) {
                            return@inTransaction null
                        }
                        if (telegramMessageId != null) {
                            selectMessageByTelegramDelivery(
                                connection = connection,
                                threadId = thread.id,
                                authorUserId = authorUserId,
                                source = source,
                                telegramMessageId = telegramMessageId,
                            )?.let { existing ->
                                return@inTransaction BookingThreadMessageRecord(
                                    thread = selectBookingThread(connection, booking.id) ?: thread,
                                    message = existing,
                                    created = false,
                                )
                            }
                        }
                        if (clientMessageId != null) {
                            selectMessageByClientDelivery(
                                connection = connection,
                                threadId = thread.id,
                                authorUserId = authorUserId ?: error("Mini App booking message author is required"),
                                source = source,
                                clientMessageId = clientMessageId,
                            )?.let { existing ->
                                if (existing.text != text) {
                                    throw BookingMessageIdempotencyPayloadMismatchException()
                                }
                                return@inTransaction BookingThreadMessageRecord(
                                    thread = selectBookingThread(connection, booking.id) ?: thread,
                                    message = existing,
                                    created = false,
                                )
                            }
                        }
                        if (lockedThread.status == SupportThreadStatus.CLOSED.name) {
                            if (miniAppNotificationKind != null) {
                                throw InvalidInputException("closed thread cannot be changed")
                            }
                            return@inTransaction null
                        }
                        beforeInsert?.invoke()
                        val message =
                            addMessageAfterThreadLock(
                                connection = connection,
                                threadId = thread.id,
                                authorUserId = authorUserId,
                                authorRole = authorRole,
                                source = source,
                                text = text,
                                telegramMessageId = telegramMessageId,
                                clientMessageId = clientMessageId,
                                statusAfterInsert = statusAfterInsert,
                            )
                        bookingConversationCheckpoint(BookingConversationCheckpoint.AFTER_MESSAGE_WRITE)
                        val write =
                            BookingThreadMessageRecord(
                                thread = selectBookingThread(connection, booking.id) ?: thread,
                                message = message,
                            )
                        if (miniAppNotificationKind != null) {
                            notificationWriter?.invoke(
                                connection,
                                BookingMessageNotificationContext(
                                    kind = miniAppNotificationKind,
                                    recipientChatId = booking.guestUserId,
                                    thread = write.thread,
                                    message = write.message,
                                ),
                            )
                        }
                        guestBotNotificationWriter?.invoke(connection, write)
                        write
                    }
                }
            }
        } catch (_: SQLException) {
            throw DatabaseUnavailableException()
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
        try {
            return withContext(Dispatchers.IO) {
                ds.connection.use { connection ->
                    inTransaction(connection) {
                        addMessage(
                            connection = connection,
                            threadId = threadId,
                            authorUserId = authorUserId,
                            authorRole = authorRole,
                            source = source,
                            text = text,
                            telegramMessageId = telegramMessageId,
                            statusAfterInsert = statusAfterInsert,
                        )
                    }
                }
            }
        } catch (_: SQLException) {
            throw DatabaseUnavailableException()
        }
    }

    open fun addMessage(
        connection: Connection,
        threadId: Long,
        authorUserId: Long?,
        authorRole: SupportMessageAuthorRole,
        source: SupportMessageSource,
        text: String,
        telegramMessageId: Long? = null,
        statusAfterInsert: SupportThreadStatus? = statusAfterMessage(authorRole),
    ): SupportMessageRecord {
        val thread = lockThread(connection, threadId)
        when (thread.threadType) {
            SupportThreadType.SUPPORT_TICKET.name,
            SupportThreadType.VENUE_CHAT.name,
            -> Unit
            SupportThreadType.BOOKING_THREAD.name -> error("BOOKING_THREAD messages require addBookingMessage")
            else -> error("unsupported thread type for addMessage")
        }
        return addMessageAfterThreadLock(
            connection = connection,
            threadId = threadId,
            authorUserId = authorUserId,
            authorRole = authorRole,
            source = source,
            text = text,
            telegramMessageId = telegramMessageId,
            clientMessageId = null,
            statusAfterInsert = statusAfterInsert,
        )
    }

    private fun addMessageAfterThreadLock(
        connection: Connection,
        threadId: Long,
        authorUserId: Long?,
        authorRole: SupportMessageAuthorRole,
        source: SupportMessageSource,
        text: String,
        telegramMessageId: Long?,
        clientMessageId: String?,
        statusAfterInsert: SupportThreadStatus?,
    ): SupportMessageRecord {
        if (telegramMessageId != null) {
            selectMessageByTelegramDelivery(
                connection = connection,
                threadId = threadId,
                authorUserId = authorUserId,
                source = source,
                telegramMessageId = telegramMessageId,
            )?.let { return it }
        }
        val messageId =
            insertMessage(
                connection = connection,
                threadId = threadId,
                authorUserId = authorUserId,
                authorRole = authorRole,
                source = source,
                text = text,
                telegramMessageId = telegramMessageId,
                clientMessageId = clientMessageId,
            )
        updateThreadAfterMessage(connection, threadId, statusAfterInsert)
        return selectMessage(connection, messageId) ?: error("support message was not found after insert")
    }

    open suspend fun createTicket(input: SupportTicketCreateInput): SupportThreadDetailRecord {
        val ds = dataSource ?: throw DatabaseUnavailableException()
        return withContext(Dispatchers.IO) {
            ds.connection.use { connection ->
                inTransaction(connection) {
                    createTicket(connection, input)
                }
            }
        }
    }

    open fun createTicket(
        connection: Connection,
        input: SupportTicketCreateInput,
    ): SupportThreadDetailRecord {
        val threadId =
            insertTicketThread(
                connection = connection,
                input = input,
            )
        val lockedThread = lockThread(connection, threadId)
        check(
            lockedThread.threadType == SupportThreadType.SUPPORT_TICKET.name &&
                lockedThread.guestUserId == input.guestUserId &&
                lockedThread.assigneeScope == input.assigneeScope.name,
        ) {
            "support ticket parent identity changed before its first message"
        }
        insertMessage(
            connection = connection,
            threadId = threadId,
            authorUserId = input.guestUserId,
            authorRole = SupportMessageAuthorRole.GUEST,
            source = input.messageSource,
            text = input.message,
            telegramMessageId = null,
            clientMessageId = null,
        )
        updateThreadAfterMessage(connection, threadId, SupportThreadStatus.NEW)
        check(
            markThreadReadInTransaction(
                connection = connection,
                threadId = threadId,
                access = SupportThreadReadAccess.Guest(input.guestUserId),
                bookingReadAllowed = false,
            ) == SupportThreadReadResult.MARKED,
        ) {
            "support ticket creator could not mark the initial message snapshot as read"
        }
        val thread =
            selectGuestThread(connection, input.guestUserId, threadId)
                ?: error("support ticket was not found after insert")
        return SupportThreadDetailRecord(thread = thread, messages = listMessages(connection, threadId))
    }

    open suspend fun createOrFindVenueChat(
        venueId: Long,
        guestUserId: Long,
        title: String,
    ): SupportThreadDetailRecord {
        val ds = dataSource ?: throw DatabaseUnavailableException()
        return withContext(Dispatchers.IO) {
            ds.connection.use { connection ->
                val thread =
                    selectVenueChat(
                        connection = connection,
                        venueId = venueId,
                        guestUserId = guestUserId,
                    ) ?: insertVenueChatThread(
                        connection = connection,
                        venueId = venueId,
                        guestUserId = guestUserId,
                        title = title,
                    )
                SupportThreadDetailRecord(thread = thread, messages = listMessages(connection, thread.id))
            }
        }
    }

    open suspend fun findVenueChat(
        venueId: Long,
        guestUserId: Long,
    ): SupportThreadDetailRecord? {
        val ds = dataSource ?: throw DatabaseUnavailableException()
        return withContext(Dispatchers.IO) {
            ds.connection.use { connection ->
                selectVenueChat(
                    connection = connection,
                    venueId = venueId,
                    guestUserId = guestUserId,
                )?.let { thread ->
                    SupportThreadDetailRecord(thread = thread, messages = listMessages(connection, thread.id))
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
                findOrderContextForGuest(connection, orderId, userId, venueId, tableSessionId)
            }
        }
    }

    open fun findOrderContextForGuest(
        connection: Connection,
        orderId: Long,
        userId: Long,
        venueId: Long? = null,
        tableSessionId: Long? = null,
    ): SupportOrderContextRecord? {
        val venueFilter = if (venueId == null) "" else "AND o.venue_id = ?"
        val sessionFilter = if (tableSessionId == null) "" else "AND o.table_session_id = ?"
        return connection.prepareStatement(
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

    open suspend fun listVenueThreads(
        venueId: Long,
        viewerUserId: Long,
        bookingId: Long? = null,
        filter: SupportInboxFilter? = null,
        threadType: SupportThreadType? = null,
        threadTypes: Set<SupportThreadType>? = threadType?.let { setOf(it) },
    ): List<SupportThreadRecord> {
        val ds = dataSource ?: throw DatabaseUnavailableException()
        return withContext(Dispatchers.IO) {
            ds.connection.use { connection ->
                val bookingFilter = if (bookingId == null) "" else "AND st.booking_id = ?"
                val typeFilter = threadTypesFilterCondition(threadTypes)
                val statusFilter = statusFilterCondition(filter)
                connection.prepareStatement(
                    """
                    ${threadSelect(unreadCountExpression())}
                    WHERE st.venue_id = ?
                      AND (
                          st.thread_type <> 'BOOKING_THREAD'
                          OR (
                              b.id IS NOT NULL
                              AND b.venue_id = st.venue_id
                              AND b.user_id = st.guest_user_id
                          )
                      )
                      $bookingFilter
                      $typeFilter
                      $statusFilter
                    ORDER BY CASE WHEN ${unreadCountExpression()} > 0 THEN 0 ELSE 1 END ASC,
                             st.last_message_at DESC NULLS LAST,
                             st.id DESC
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
                    threadTypes.orEmpty().forEach { type ->
                        statement.setString(index++, type.name)
                    }
                    statement.setLong(index++, viewerUserId)
                    statement.setLong(index, viewerUserId)
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

    open suspend fun countVenueConversationUnread(
        venueId: Long,
        viewerUserId: Long,
    ): Int {
        val ds = dataSource ?: throw DatabaseUnavailableException()
        return withContext(Dispatchers.IO) {
            ds.connection.use { connection ->
                if (!venueAccessRepository.hasVenueAdminOrOwner(connection, viewerUserId, venueId)) {
                    return@withContext 0
                }
                connection.prepareStatement(
                    """
                    SELECT COUNT(*) AS unread_count
                    FROM support_messages sm
                    JOIN support_threads st ON st.id = sm.thread_id
                    LEFT JOIN support_thread_reads sr
                        ON sr.thread_id = st.id
                       AND sr.user_id = ?
                    WHERE st.venue_id = ?
                      AND st.thread_type IN ('BOOKING_THREAD', 'VENUE_CHAT')
                      AND sm.author_user_id IS DISTINCT FROM ?
                      AND (
                          sr.last_read_message_id IS NULL
                          OR sm.id > sr.last_read_message_id
                      )
                      AND (
                          st.thread_type <> 'BOOKING_THREAD'
                          OR EXISTS (
                              SELECT 1
                              FROM bookings b
                              WHERE b.id = st.booking_id
                                AND b.venue_id = st.venue_id
                                AND b.user_id = st.guest_user_id
                          )
                      )
                    """.trimIndent(),
                ).use { statement ->
                    statement.setLong(1, viewerUserId)
                    statement.setLong(2, venueId)
                    statement.setLong(3, viewerUserId)
                    statement.executeQuery().use { rs ->
                        if (rs.next()) rs.getInt("unread_count").coerceAtLeast(0) else 0
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
        threadTypes: Set<SupportThreadType>? = threadType?.let { setOf(it) },
    ): List<SupportThreadRecord> {
        val ds = dataSource ?: throw DatabaseUnavailableException()
        return withContext(Dispatchers.IO) {
            ds.connection.use { connection ->
                val statusFilter = statusFilterCondition(filter)
                val scopeFilter = if (assigneeScope == null) "" else "AND st.assignee_scope = ?"
                val venueFilter = if (venueId == null) "" else "AND st.venue_id = ?"
                val typeFilter = threadTypesFilterCondition(threadTypes)
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
                    threadTypes.orEmpty().forEach { type ->
                        statement.setString(index++, type.name)
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

    open suspend fun getVenueThreadAndMarkRead(
        venueId: Long,
        threadId: Long,
        viewerUserId: Long,
        allowedThreadTypes: Set<SupportThreadType>? = null,
    ): SupportThreadDetailRecord? =
        getThreadAndMarkRead(
            threadId = threadId,
            access = SupportThreadReadAccess.Venue(userId = viewerUserId, venueId = venueId),
            allowedThreadTypes = allowedThreadTypes,
        )

    open suspend fun getGuestThreadAndMarkRead(
        userId: Long,
        threadId: Long,
        surface: GuestThreadSurface,
    ): SupportThreadDetailRecord? =
        getThreadAndMarkRead(
            threadId = threadId,
            access = SupportThreadReadAccess.Guest(userId),
            allowedThreadTypes = surface.expectedThreadTypes,
        )

    open fun getGuestThreadAndMarkRead(
        connection: Connection,
        userId: Long,
        threadId: Long,
        surface: GuestThreadSurface,
        lockedThreadValidator: (GuestThreadOpenContextRecord) -> Unit = {},
    ): SupportThreadDetailRecord? {
        check(!connection.autoCommit) {
            "getGuestThreadAndMarkRead(connection, ...) requires an active transaction"
        }
        return getThreadAndMarkReadInTransaction(
            connection = connection,
            threadId = threadId,
            access = SupportThreadReadAccess.Guest(userId),
            allowedThreadTypes = surface.expectedThreadTypes,
            lockedThreadValidator = { thread -> lockedThreadValidator(thread.toGuestThreadOpenContext()) },
        )
    }

    open suspend fun getPlatformThreadAndMarkRead(
        threadId: Long,
        viewerUserId: Long,
        platformOwnerUserId: Long?,
    ): SupportThreadDetailRecord? =
        getThreadAndMarkRead(
            threadId = threadId,
            access =
                SupportThreadReadAccess.Platform(
                    userId = viewerUserId,
                    platformOwnerUserId = platformOwnerUserId,
                ),
            allowedThreadTypes = setOf(SupportThreadType.SUPPORT_TICKET),
        )

    private suspend fun getThreadAndMarkRead(
        threadId: Long,
        access: SupportThreadReadAccess,
        allowedThreadTypes: Set<SupportThreadType>?,
    ): SupportThreadDetailRecord? {
        val ds = dataSource ?: throw DatabaseUnavailableException()
        return withContext(Dispatchers.IO) {
            ds.connection.use { connection ->
                inTransaction(connection) {
                    getThreadAndMarkReadInTransaction(
                        connection = connection,
                        threadId = threadId,
                        access = access,
                        allowedThreadTypes = allowedThreadTypes,
                    )
                }
            }
        }
    }

    private fun getThreadAndMarkReadInTransaction(
        connection: Connection,
        threadId: Long,
        access: SupportThreadReadAccess,
        allowedThreadTypes: Set<SupportThreadType>?,
        lockedThreadValidator: (SupportThreadWriteContext) -> Unit = {},
    ): SupportThreadDetailRecord? {
        if (access is SupportThreadReadAccess.Guest) {
            require(!allowedThreadTypes.isNullOrEmpty())
        }
        val readResult =
            markThreadReadInTransaction(
                connection = connection,
                threadId = threadId,
                access = access,
                bookingReadAllowed = true,
                allowedThreadTypes = allowedThreadTypes,
                lockedThreadValidator = lockedThreadValidator,
            )
        if (readResult != SupportThreadReadResult.MARKED) return null
        return selectThreadDetailForAccess(connection, threadId, access)
            ?: error("authorized support thread disappeared from its locked read snapshot")
    }

    open suspend fun listGuestThreads(userId: Long): List<SupportThreadRecord> {
        return listGuestThreads(userId = userId, filter = null)
    }

    open suspend fun listGuestThreads(
        userId: Long,
        filter: SupportInboxFilter?,
        threadType: SupportThreadType? = null,
        threadTypes: Set<SupportThreadType>? = threadType?.let { setOf(it) },
    ): List<SupportThreadRecord> {
        val ds = dataSource ?: throw DatabaseUnavailableException()
        return withContext(Dispatchers.IO) {
            ds.connection.use { connection ->
                val statusFilter = statusFilterCondition(filter)
                val typeFilter = threadTypesFilterCondition(threadTypes)
                connection.prepareStatement(
                    """
                    ${threadSelect(unreadCountExpression())}
                    WHERE st.guest_user_id = ?
                      AND (
                          st.thread_type <> 'BOOKING_THREAD'
                          OR (
                              b.id IS NOT NULL
                              AND b.venue_id = st.venue_id
                              AND b.user_id = st.guest_user_id
                          )
                      )
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
                    threadTypes.orEmpty().forEach { type ->
                        statement.setString(index++, type.name)
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
        access: SupportThreadReadAccess,
    ): SupportThreadReadResult {
        val ds = dataSource ?: throw DatabaseUnavailableException()
        return withContext(Dispatchers.IO) {
            ds.connection.use { connection ->
                inTransaction(connection) {
                    markThreadReadInTransaction(
                        connection = connection,
                        threadId = threadId,
                        access = access,
                        bookingReadAllowed = true,
                    )
                }
            }
        }
    }

    /**
     * Marks a non-booking thread inside a caller-owned transaction after that caller has locked or
     * inserted the thread row. Booking reads must use the repository-owned suspend method so their
     * booking -> thread lock order cannot be inverted by a caller that already holds the thread.
     */
    open fun markNonBookingThreadReadAfterThreadLock(
        connection: Connection,
        threadId: Long,
        access: SupportThreadReadAccess,
    ): SupportThreadReadResult {
        check(!connection.autoCommit) {
            "markNonBookingThreadReadAfterThreadLock(connection, ...) requires an active transaction"
        }
        return markThreadReadInTransaction(
            connection = connection,
            threadId = threadId,
            access = access,
            bookingReadAllowed = false,
        )
    }

    private fun markThreadReadInTransaction(
        connection: Connection,
        threadId: Long,
        access: SupportThreadReadAccess,
        bookingReadAllowed: Boolean,
        allowedThreadTypes: Set<SupportThreadType>? = null,
        lockedThreadValidator: (SupportThreadWriteContext) -> Unit = {},
    ): SupportThreadReadResult {
        val preauthorization = preauthorizeThreadRead(connection, access)
        if (preauthorization != SupportThreadReadResult.MARKED) return preauthorization
        val pointer =
            selectThreadReadPointer(connection, threadId, access)
                ?: return SupportThreadReadResult.NOT_FOUND
        if (pointer.threadType == SupportThreadType.BOOKING_THREAD.name && !bookingReadAllowed) {
            return SupportThreadReadResult.NOT_FOUND
        }
        val booking =
            if (pointer.threadType == SupportThreadType.BOOKING_THREAD.name) {
                val bookingId = pointer.bookingId ?: return SupportThreadReadResult.NOT_FOUND
                lockBookingContext(connection, bookingId) ?: return SupportThreadReadResult.NOT_FOUND
            } else {
                null
            }
        val thread = lockThreadIfPresent(connection, threadId) ?: return SupportThreadReadResult.NOT_FOUND
        if (thread.bookingId != pointer.bookingId || thread.threadType != pointer.threadType) {
            return SupportThreadReadResult.NOT_FOUND
        }
        val authorization = authorizeThreadRead(connection, thread, access)
        if (authorization != SupportThreadReadResult.MARKED) return authorization
        val lockedThreadType =
            thread.threadType?.let { runCatching { SupportThreadType.valueOf(it) }.getOrNull() }
                ?: return SupportThreadReadResult.NOT_FOUND
        if (allowedThreadTypes != null && lockedThreadType !in allowedThreadTypes) {
            return SupportThreadReadResult.NOT_FOUND
        }
        if (!hasCanonicalThreadIdentity(thread, booking)) return SupportThreadReadResult.NOT_FOUND
        lockedThreadValidator(thread)
        supportThreadReadCheckpoint(SupportThreadReadCheckpoint.AFTER_THREAD_LOCK)
        val snapshotMessageId = selectMaxMessageId(connection, threadId)
        writeThreadReadMarker(connection, threadId, access.userId, snapshotMessageId)
        supportThreadReadCheckpoint(SupportThreadReadCheckpoint.AFTER_MARKER_WRITE)
        return SupportThreadReadResult.MARKED
    }

    private fun selectMaxMessageId(
        connection: Connection,
        threadId: Long,
    ): Long? =
        connection.prepareStatement(
            "SELECT MAX(id) FROM support_messages WHERE thread_id = ?",
        ).use { statement ->
            statement.setLong(1, threadId)
            statement.executeQuery().use { rows ->
                check(rows.next())
                rows.getLong(1).takeUnless { rows.wasNull() }
            }
        }

    private fun writeThreadReadMarker(
        connection: Connection,
        threadId: Long,
        userId: Long,
        snapshotMessageId: Long?,
    ) {
        val readAt = Timestamp.from(Instant.now())
        val updated =
            connection.prepareStatement(
                """
                UPDATE support_thread_reads
                SET last_read_message_id = CASE
                        WHEN ? IS NULL THEN last_read_message_id
                        WHEN last_read_message_id IS NULL OR last_read_message_id < ? THEN ?
                        ELSE last_read_message_id
                    END,
                    last_read_at = CASE
                        WHEN last_read_at > ? THEN last_read_at
                        ELSE ?
                    END
                WHERE thread_id = ?
                  AND user_id = ?
                """.trimIndent(),
            ).use { statement ->
                statement.setNullableLong(1, snapshotMessageId)
                statement.setNullableLong(2, snapshotMessageId)
                statement.setNullableLong(3, snapshotMessageId)
                statement.setTimestamp(4, readAt)
                statement.setTimestamp(5, readAt)
                statement.setLong(6, threadId)
                statement.setLong(7, userId)
                statement.executeUpdate()
            }
        if (updated > 0) return
        connection.prepareStatement(
            """
            INSERT INTO support_thread_reads (thread_id, user_id, last_read_at, last_read_message_id)
            VALUES (?, ?, ?, ?)
            """.trimIndent(),
        ).use { statement ->
            statement.setLong(1, threadId)
            statement.setLong(2, userId)
            statement.setTimestamp(3, readAt)
            statement.setNullableLong(4, snapshotMessageId)
            statement.executeUpdate()
        }
    }

    open suspend fun updateThreadStatus(
        threadId: Long,
        status: SupportThreadStatus,
    ) {
        val ds = dataSource ?: throw DatabaseUnavailableException()
        withContext(Dispatchers.IO) {
            ds.connection.use { connection ->
                updateThreadStatus(connection, threadId, status)
            }
        }
    }

    open fun updateThreadStatus(
        connection: Connection,
        threadId: Long,
        status: SupportThreadStatus,
    ) {
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
                getGuestThread(connection, userId, threadId)
            }
        }
    }

    open suspend fun getGuestThreadOpenContext(
        userId: Long,
        threadId: Long,
    ): GuestThreadOpenContextRecord? {
        val ds = dataSource ?: throw DatabaseUnavailableException()
        return withContext(Dispatchers.IO) {
            ds.connection.use { connection ->
                connection.prepareStatement(
                    """
                    SELECT id, venue_id, table_id, table_session_id, thread_type
                    FROM support_threads
                    WHERE id = ?
                      AND guest_user_id = ?
                    """.trimIndent(),
                ).use { statement ->
                    statement.setLong(1, threadId)
                    statement.setLong(2, userId)
                    statement.executeQuery().use { rows ->
                        if (!rows.next()) {
                            null
                        } else {
                            val threadType =
                                rows.getString("thread_type")
                                    ?.let { runCatching { SupportThreadType.valueOf(it) }.getOrNull() }
                                    ?: return@withContext null
                            GuestThreadOpenContextRecord(
                                threadId = rows.getLong("id"),
                                venueId = rows.getNullableLong("venue_id"),
                                tableId = rows.getNullableLong("table_id"),
                                tableSessionId = rows.getNullableLong("table_session_id"),
                                threadType = threadType,
                            )
                        }
                    }
                }
            }
        }
    }

    open fun getGuestThread(
        connection: Connection,
        userId: Long,
        threadId: Long,
    ): SupportThreadDetailRecord? {
        val thread = selectGuestThread(connection, userId, threadId) ?: return null
        return SupportThreadDetailRecord(thread = thread, messages = listMessages(connection, threadId))
    }

    open fun lockGuestThread(
        connection: Connection,
        userId: Long,
        threadId: Long,
    ): SupportThreadDetailRecord? {
        val owned =
            connection.prepareStatement(
                """
                SELECT id
                FROM support_threads
                WHERE guest_user_id = ?
                  AND id = ?
                FOR UPDATE
                """.trimIndent(),
            ).use { statement ->
                statement.setLong(1, userId)
                statement.setLong(2, threadId)
                statement.executeQuery().use { it.next() }
            }
        return if (owned) getGuestThread(connection, userId, threadId) else null
    }

    open suspend fun getPlatformThread(threadId: Long): SupportThreadDetailRecord? {
        val ds = dataSource ?: throw DatabaseUnavailableException()
        return withContext(Dispatchers.IO) {
            ds.connection.use { connection ->
                val thread = selectPlatformThread(connection, threadId) ?: return@withContext null
                SupportThreadDetailRecord(thread = thread, messages = listMessages(connection, threadId))
            }
        }
    }

    private fun lockBookingContext(
        connection: Connection,
        bookingId: Long,
    ): BookingThreadContext? =
        connection.prepareStatement(
            """
            SELECT id, venue_id, user_id
            FROM bookings
            WHERE id = ?
            FOR UPDATE
            """.trimIndent(),
        ).use { statement ->
            statement.setLong(1, bookingId)
            statement.executeQuery().use { resultSet ->
                if (!resultSet.next()) {
                    null
                } else {
                    BookingThreadContext(
                        id = resultSet.getLong("id"),
                        venueId = resultSet.getLong("venue_id"),
                        guestUserId = resultSet.getLong("user_id"),
                    )
                }
            }
        }

    private fun selectBookingLookupContexts(
        connection: Connection,
        bookingIds: List<Long>,
        scope: BookingThreadLookupScope,
        scopeId: Long,
    ): Map<Long, BookingThreadContext> {
        val placeholders = bookingIds.joinToString(", ") { "?" }
        return connection.prepareStatement(
            """
            SELECT id, venue_id, user_id
            FROM bookings
            WHERE id IN ($placeholders)
              AND ${scope.columnName} = ?
            ORDER BY id
            """.trimIndent(),
        ).use { statement ->
            var index = 1
            bookingIds.forEach { bookingId -> statement.setLong(index++, bookingId) }
            statement.setLong(index, scopeId)
            statement.executeQuery().use { resultSet ->
                buildMap {
                    while (resultSet.next()) {
                        val booking =
                            BookingThreadContext(
                                id = resultSet.getLong("id"),
                                venueId = resultSet.getLong("venue_id"),
                                guestUserId = resultSet.getLong("user_id"),
                            )
                        put(booking.id, booking)
                    }
                }
            }
        }
    }

    private fun selectBookingThreadsForLookup(
        connection: Connection,
        bookingIds: List<Long>,
        viewerUserId: Long,
    ): List<SupportThreadRecord> {
        val placeholders = bookingIds.joinToString(", ") { "?" }
        return connection.prepareStatement(
            """
            ${threadSelect(unreadCountExpression())}
            WHERE st.booking_id IN ($placeholders)
              AND st.thread_type = 'BOOKING_THREAD'
            ORDER BY st.booking_id, st.id
            """.trimIndent(),
        ).use { statement ->
            var index = 1
            statement.setLong(index++, viewerUserId)
            statement.setLong(index++, viewerUserId)
            bookingIds.forEach { bookingId -> statement.setLong(index++, bookingId) }
            statement.executeQuery().use { resultSet ->
                buildList {
                    while (resultSet.next()) {
                        add(resultSet.toThreadRecord())
                    }
                }
            }
        }
    }

    private fun matchesExpectedBookingActor(
        booking: BookingThreadContext,
        authorUserId: Long?,
        authorRole: SupportMessageAuthorRole,
        expectedGuestUserId: Long?,
        expectedVenueId: Long?,
    ): Boolean {
        if (expectedGuestUserId != null && expectedGuestUserId != booking.guestUserId) return false
        if (expectedVenueId != null && expectedVenueId != booking.venueId) return false
        return when (authorRole) {
            SupportMessageAuthorRole.GUEST ->
                authorUserId != null &&
                    expectedGuestUserId != null &&
                    authorUserId == expectedGuestUserId
            SupportMessageAuthorRole.VENUE -> authorUserId != null && expectedVenueId != null
            SupportMessageAuthorRole.PLATFORM -> false
            SupportMessageAuthorRole.SYSTEM -> expectedGuestUserId != null || expectedVenueId != null
        }
    }

    private fun matchesBookingThread(
        thread: SupportThreadWriteContext,
        booking: BookingThreadContext,
        expectedThreadId: Long?,
    ): Boolean =
        (expectedThreadId == null || thread.id == expectedThreadId) &&
            thread.threadType == SupportThreadType.BOOKING_THREAD.name &&
            thread.bookingId == booking.id &&
            thread.venueId == booking.venueId &&
            thread.guestUserId == booking.guestUserId

    private fun selectBookingThreadWriteContext(
        connection: Connection,
        bookingId: Long,
    ): SupportThreadWriteContext? =
        connection.prepareStatement(
            """
            SELECT id, booking_id, venue_id, table_id, table_session_id,
                   guest_user_id, thread_type, assignee_scope, status
            FROM support_threads
            WHERE booking_id = ?
              AND thread_type = 'BOOKING_THREAD'
            ORDER BY id ASC
            LIMIT 1
            """.trimIndent(),
        ).use { statement ->
            statement.setLong(1, bookingId)
            statement.executeQuery().use { resultSet ->
                if (resultSet.next()) resultSet.toThreadWriteContext() else null
            }
        }

    private fun selectThreadWriteContext(
        connection: Connection,
        threadId: Long,
    ): SupportThreadWriteContext? =
        connection.prepareStatement(
            """
            SELECT id, booking_id, venue_id, table_id, table_session_id,
                   guest_user_id, thread_type, assignee_scope, status
            FROM support_threads
            WHERE id = ?
            """.trimIndent(),
        ).use { statement ->
            statement.setLong(1, threadId)
            statement.executeQuery().use { resultSet ->
                if (resultSet.next()) resultSet.toThreadWriteContext() else null
            }
        }

    private fun resolveBookingThread(
        connection: Connection,
        booking: BookingThreadContext,
        title: String?,
    ): SupportThreadRecord {
        selectBookingThread(connection, booking.id)?.let { return it }
        val savepoint = connection.setSavepoint()
        return try {
            insertBookingThread(
                connection = connection,
                venueId = booking.venueId,
                bookingId = booking.id,
                guestUserId = booking.guestUserId,
                title = title?.takeIf { it.isNotBlank() } ?: "Бронь #${booking.id}",
            )
        } catch (exception: SQLException) {
            if (!isUniqueViolation(exception)) throw exception
            connection.rollback(savepoint)
            selectBookingThread(connection, booking.id) ?: throw exception
        } finally {
            runCatching { connection.releaseSavepoint(savepoint) }
        }
    }

    private fun selectBookingThread(
        connection: Connection,
        bookingId: Long,
    ): SupportThreadRecord? =
        connection.prepareStatement(
            """
            ${threadSelect()}
            WHERE st.booking_id = ?
              AND st.thread_type = 'BOOKING_THREAD'
              AND b.id IS NOT NULL
              AND b.venue_id = st.venue_id
              AND b.user_id = st.guest_user_id
            ORDER BY st.id ASC
            LIMIT 1
            """.trimIndent(),
        ).use { statement ->
            statement.setLong(1, bookingId)
            statement.executeQuery().use { rs -> if (rs.next()) rs.toThreadRecord() else null }
        }

    private fun selectVenueChat(
        connection: Connection,
        venueId: Long,
        guestUserId: Long,
    ): SupportThreadRecord? =
        connection.prepareStatement(
            """
            ${threadSelect()}
            WHERE st.venue_id = ?
              AND st.guest_user_id = ?
              AND st.booking_id IS NULL
              AND st.thread_type = 'VENUE_CHAT'
              AND st.status IN ('OPEN', 'NEW', 'IN_PROGRESS', 'WAITING_USER')
            ORDER BY COALESCE(st.last_message_at, st.created_at) DESC, st.id DESC
            LIMIT 1
            """.trimIndent(),
        ).use { statement ->
            statement.setLong(1, venueId)
            statement.setLong(2, guestUserId)
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

    private fun insertVenueChatThread(
        connection: Connection,
        venueId: Long,
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
                    thread_type,
                    assignee_scope,
                    created_source,
                    title
                )
                VALUES (?, ?, 'OTHER', 'IN_PROGRESS', 'VENUE_CHAT', 'VENUE', 'GUEST_MINIAPP', ?)
                """.trimIndent(),
                Statement.RETURN_GENERATED_KEYS,
            ).use { statement ->
                statement.setLong(1, venueId)
                statement.setLong(2, guestUserId)
                statement.setString(3, title)
                statement.executeUpdate()
                statement.generatedKeys.use { keys ->
                    if (keys.next()) keys.getLong(1) else error("venue chat thread id was not generated")
                }
            }
        return selectVenueThread(connection, venueId, threadId) ?: error("venue chat thread was not found after insert")
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
              AND (
                  st.thread_type <> 'BOOKING_THREAD'
                  OR (
                      b.id IS NOT NULL
                      AND b.venue_id = st.venue_id
                      AND b.user_id = st.guest_user_id
                  )
              )
            """.trimIndent(),
        ).use { statement ->
            statement.setLong(1, venueId)
            statement.setLong(2, threadId)
            statement.executeQuery().use { rs -> if (rs.next()) rs.toThreadRecord() else null }
        }

    private fun selectPlatformThread(
        connection: Connection,
        threadId: Long,
    ): SupportThreadRecord? =
        connection.prepareStatement(
            """
            ${threadSelect()}
            WHERE st.id = ?
              AND st.thread_type = 'SUPPORT_TICKET'
            """.trimIndent(),
        ).use { statement ->
            statement.setLong(1, threadId)
            statement.executeQuery().use { rs -> if (rs.next()) rs.toThreadRecord() else null }
        }

    private fun selectThreadDetailForAccess(
        connection: Connection,
        threadId: Long,
        access: SupportThreadReadAccess,
    ): SupportThreadDetailRecord? {
        val thread =
            when (access) {
                is SupportThreadReadAccess.Guest -> selectGuestThread(connection, access.userId, threadId)
                is SupportThreadReadAccess.Venue -> selectVenueThread(connection, access.venueId, threadId)
                is SupportThreadReadAccess.Platform -> selectPlatformThread(connection, threadId)
            } ?: return null
        return SupportThreadDetailRecord(
            thread = thread,
            messages = listMessages(connection, threadId),
        )
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
              AND (
                  st.thread_type <> 'BOOKING_THREAD'
                  OR (
                      b.id IS NOT NULL
                      AND b.venue_id = st.venue_id
                      AND b.user_id = st.guest_user_id
                  )
              )
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
        clientMessageId: String?,
    ): Long =
        connection.prepareStatement(
            """
            INSERT INTO support_messages (
                thread_id,
                author_user_id,
                author_role,
                source,
                text,
                telegram_message_id,
                client_message_id
            )
            VALUES (?, ?, ?, ?, ?, ?, ?)
            """.trimIndent(),
            Statement.RETURN_GENERATED_KEYS,
        ).use { statement ->
            statement.setLong(1, threadId)
            statement.setNullableLong(2, authorUserId)
            statement.setString(3, authorRole.name)
            statement.setString(4, source.name)
            statement.setString(5, text)
            statement.setNullableLong(6, telegramMessageId)
            statement.setNullableString(7, clientMessageId)
            statement.executeUpdate()
            statement.generatedKeys.use { keys ->
                if (keys.next()) keys.getLong(1) else error("support message id was not generated")
            }
        }

    private fun lockThread(
        connection: Connection,
        threadId: Long,
    ): SupportThreadWriteContext = lockThreadIfPresent(connection, threadId) ?: error("support thread was not found")

    private fun lockThreadIfPresent(
        connection: Connection,
        threadId: Long,
    ): SupportThreadWriteContext? =
        connection.prepareStatement(
            """
            SELECT id, booking_id, venue_id, table_id, table_session_id,
                   guest_user_id, thread_type, assignee_scope, status
            FROM support_threads
            WHERE id = ?
            FOR UPDATE
            """.trimIndent(),
        ).use { statement ->
            statement.setLong(1, threadId)
            statement.executeQuery().use { resultSet ->
                if (resultSet.next()) {
                    resultSet.toThreadWriteContext()
                } else {
                    null
                }
            }
        }

    private fun selectThreadReadPointer(
        connection: Connection,
        threadId: Long,
        access: SupportThreadReadAccess,
    ): SupportThreadReadPointer? {
        val actorScope =
            when (access) {
                is SupportThreadReadAccess.Guest -> "AND guest_user_id = ?"
                is SupportThreadReadAccess.Venue -> "AND venue_id = ?"
                is SupportThreadReadAccess.Platform -> "AND thread_type = 'SUPPORT_TICKET'"
            }
        return connection.prepareStatement(
            """
            SELECT booking_id, thread_type
            FROM support_threads
            WHERE id = ?
              $actorScope
            """.trimIndent(),
        ).use { statement ->
            statement.setLong(1, threadId)
            when (access) {
                is SupportThreadReadAccess.Guest -> statement.setLong(2, access.userId)
                is SupportThreadReadAccess.Venue -> statement.setLong(2, access.venueId)
                is SupportThreadReadAccess.Platform -> Unit
            }
            statement.executeQuery().use { resultSet ->
                if (resultSet.next()) {
                    SupportThreadReadPointer(
                        bookingId = resultSet.getLong("booking_id").takeUnless { resultSet.wasNull() },
                        threadType = resultSet.getString("thread_type"),
                    )
                } else {
                    null
                }
            }
        }
    }

    private fun preauthorizeThreadRead(
        connection: Connection,
        access: SupportThreadReadAccess,
    ): SupportThreadReadResult =
        when (access) {
            is SupportThreadReadAccess.Guest -> SupportThreadReadResult.MARKED
            is SupportThreadReadAccess.Venue ->
                if (venueAccessRepository.hasVenueAdminOrOwner(connection, access.userId, access.venueId)) {
                    SupportThreadReadResult.MARKED
                } else {
                    SupportThreadReadResult.FORBIDDEN
                }
            is SupportThreadReadAccess.Platform ->
                if (access.platformOwnerUserId != null && access.userId == access.platformOwnerUserId) {
                    SupportThreadReadResult.MARKED
                } else {
                    SupportThreadReadResult.FORBIDDEN
                }
        }

    private fun hasCanonicalThreadIdentity(
        thread: SupportThreadWriteContext,
        booking: BookingThreadContext?,
    ): Boolean =
        when (thread.threadType) {
            SupportThreadType.BOOKING_THREAD.name ->
                booking != null && matchesBookingThread(thread, booking, expectedThreadId = thread.id)
            SupportThreadType.SUPPORT_TICKET.name,
            SupportThreadType.VENUE_CHAT.name,
            -> true
            else -> false
        }

    private fun authorizeThreadRead(
        connection: Connection,
        thread: SupportThreadWriteContext,
        access: SupportThreadReadAccess,
    ): SupportThreadReadResult {
        if (
            thread.assigneeScope != SupportAssigneeScope.VENUE.name &&
            thread.assigneeScope != SupportAssigneeScope.PLATFORM.name
        ) {
            return SupportThreadReadResult.NOT_FOUND
        }
        return when (access) {
            is SupportThreadReadAccess.Guest ->
                if (thread.guestUserId == access.userId) {
                    SupportThreadReadResult.MARKED
                } else {
                    SupportThreadReadResult.NOT_FOUND
                }
            is SupportThreadReadAccess.Venue -> {
                if (!venueAccessRepository.hasVenueAdminOrOwner(connection, access.userId, access.venueId)) {
                    SupportThreadReadResult.FORBIDDEN
                } else if (thread.venueId != access.venueId) {
                    SupportThreadReadResult.NOT_FOUND
                } else {
                    SupportThreadReadResult.MARKED
                }
            }
            is SupportThreadReadAccess.Platform -> {
                if (access.platformOwnerUserId == null || access.userId != access.platformOwnerUserId) {
                    SupportThreadReadResult.FORBIDDEN
                } else if (thread.threadType != SupportThreadType.SUPPORT_TICKET.name) {
                    SupportThreadReadResult.NOT_FOUND
                } else {
                    SupportThreadReadResult.MARKED
                }
            }
        }
    }

    private fun selectMessageByTelegramDelivery(
        connection: Connection,
        threadId: Long,
        authorUserId: Long?,
        source: SupportMessageSource,
        telegramMessageId: Long,
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
                   client_message_id,
                   created_at
            FROM support_messages
            WHERE thread_id = ?
              AND source = ?
              AND telegram_message_id = ?
              AND (
                  author_user_id = ?
                  OR (author_user_id IS NULL AND ? IS NULL)
              )
            ORDER BY id ASC
            LIMIT 1
            """.trimIndent(),
        ).use { statement ->
            statement.setLong(1, threadId)
            statement.setString(2, source.name)
            statement.setLong(3, telegramMessageId)
            statement.setNullableLong(4, authorUserId)
            statement.setNullableLong(5, authorUserId)
            statement.executeQuery().use { resultSet ->
                if (resultSet.next()) resultSet.toMessageRecord() else null
            }
        }

    private fun selectMessageByClientDelivery(
        connection: Connection,
        threadId: Long,
        authorUserId: Long,
        source: SupportMessageSource,
        clientMessageId: String,
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
                   client_message_id,
                   created_at
            FROM support_messages
            WHERE thread_id = ?
              AND source = ?
              AND author_user_id = ?
              AND client_message_id = ?
            ORDER BY id ASC
            LIMIT 1
            """.trimIndent(),
        ).use { statement ->
            statement.setLong(1, threadId)
            statement.setString(2, source.name)
            statement.setLong(3, authorUserId)
            statement.setString(4, clientMessageId)
            statement.executeQuery().use { resultSet ->
                if (resultSet.next()) resultSet.toMessageRecord() else null
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
                   client_message_id,
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
                   client_message_id,
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
            venueTimezone = getString("venue_timezone"),
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
            clientMessageId = getString("client_message_id"),
        )

    private fun ResultSet.toThreadWriteContext(): SupportThreadWriteContext =
        SupportThreadWriteContext(
            id = getLong("id"),
            bookingId = getLong("booking_id").takeUnless { wasNull() },
            venueId = getLong("venue_id").takeUnless { wasNull() },
            tableId = getLong("table_id").takeUnless { wasNull() },
            tableSessionId = getLong("table_session_id").takeUnless { wasNull() },
            guestUserId = getLong("guest_user_id"),
            threadType = getString("thread_type"),
            assigneeScope = getString("assignee_scope"),
            status = getString("status"),
        )

    private fun SupportThreadWriteContext.toGuestThreadOpenContext(): GuestThreadOpenContextRecord =
        GuestThreadOpenContextRecord(
            threadId = id,
            venueId = venueId,
            tableId = tableId,
            tableSessionId = tableSessionId,
            threadType = SupportThreadType.valueOf(requireNotNull(threadType)),
        )

    private fun <T> inTransaction(
        connection: Connection,
        block: () -> T,
    ): T {
        val previousAutoCommit = connection.autoCommit
        connection.autoCommit = false
        return try {
            block().also { connection.commit() }
        } catch (e: Throwable) {
            runCatching { connection.rollback() }
            throw e
        } finally {
            connection.autoCommit = previousAutoCommit
        }
    }

    private fun isUniqueViolation(exception: SQLException): Boolean {
        var current: Throwable? = exception
        while (current != null) {
            val sqlException = current as? SQLException
            if (sqlException?.sqlState == "23505") return true
            current =
                when (current) {
                    is SQLException -> current.nextException ?: current.cause
                    else -> current.cause
                }
        }
        return false
    }

    private fun miniAppNotificationKind(source: SupportMessageSource): BookingMessageNotificationKind? =
        when (source) {
            SupportMessageSource.GUEST_MINIAPP -> BookingMessageNotificationKind.GUEST_ACK
            SupportMessageSource.VENUE_MINIAPP -> BookingMessageNotificationKind.GUEST_NOTIFICATION
            else -> null
        }

    private companion object {
        private fun statusFilterCondition(filter: SupportInboxFilter?): String =
            when (filter) {
                SupportInboxFilter.ACTIVE -> "AND st.status IN ('OPEN', 'NEW', 'IN_PROGRESS', 'WAITING_USER')"
                SupportInboxFilter.RESOLVED -> "AND st.status IN ('RESOLVED', 'CLOSED')"
                null -> ""
            }

        private fun threadTypesFilterCondition(threadTypes: Set<SupportThreadType>?): String {
            if (threadTypes.isNullOrEmpty()) return ""
            val placeholders = threadTypes.joinToString(", ") { "?" }
            return "AND st.thread_type IN ($placeholders)"
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
                  AND sm_unread.author_user_id IS DISTINCT FROM ?
                  AND (
                      sr.last_read_message_id IS NULL
                      OR sm_unread.id > sr.last_read_message_id
                  )
            )
            """.trimIndent()

        private fun threadSelect(unreadExpression: String = "0"): String =
            """
            SELECT st.id AS thread_id,
                   st.venue_id AS venue_id,
                   v.name AS venue_name,
                   vs.timezone AS venue_timezone,
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
            LEFT JOIN venue_settings vs ON vs.venue_id = st.venue_id
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
