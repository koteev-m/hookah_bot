package com.hookah.platform.backend.miniapp.venue.dashboard

import com.hookah.platform.backend.api.DatabaseUnavailableException
import com.hookah.platform.backend.api.ForbiddenException
import com.hookah.platform.backend.api.InvalidInputException
import com.hookah.platform.backend.miniapp.venue.VenuePermission
import com.hookah.platform.backend.miniapp.venue.VenuePermissions
import com.hookah.platform.backend.miniapp.venue.VenueRole
import com.hookah.platform.backend.miniapp.venue.VenueRoleMapping
import com.hookah.platform.backend.miniapp.venue.requireUserId
import com.hookah.platform.backend.miniapp.venue.requireVenueId
import io.ktor.http.HttpHeaders
import io.ktor.server.application.call
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.encodeToJsonElement
import java.sql.Connection
import java.sql.ResultSet
import java.time.Instant
import javax.sql.DataSource

const val VENUE_EVENT_CAPACITY = 256
const val VENUE_DASHBOARD_MAX_BYTES = 131072

// The joined pending batch guarantees this order prefix exists. Seek both index keys;
// MIN(id) can choose a primary-key history walk for a rare order under skewed statistics.
internal const val PENDING_BATCHES_SQL =
    "SELECT b.id, b.order_id, b.created_at, t.table_number, " +
        "CASE WHEN b.tab_id IS NULL THEN (SELECT first_batch.id FROM order_batches first_batch WHERE " +
        "first_batch.order_id=o.id AND first_batch.tab_id IS NULL ORDER BY " +
        "first_batch.order_id,first_batch.tab_id,first_batch.id LIMIT 1) " +
        "ELSE (SELECT first_batch.id FROM order_batches first_batch WHERE first_batch.order_id=o.id " +
        "AND first_batch.tab_id=b.tab_id ORDER BY first_batch.order_id,first_batch.tab_id," +
        "first_batch.id LIMIT 1) END, b.tab_id " +
        "FROM order_batches b JOIN orders o ON o.id = b.order_id " +
        "JOIN venue_tables t ON t.id = o.table_id AND t.venue_id = o.venue_id " +
        "WHERE b.status = 'NEW' AND o.status = 'ACTIVE' AND o.venue_id = ? LIMIT 257"

internal const val PENDING_CALLS_SQL =
    "SELECT sc.id, sc.created_at, t.table_number, sc.reason FROM staff_calls sc " +
        "JOIN venue_tables t ON t.id = sc.table_id AND t.venue_id = sc.venue_id " +
        "WHERE sc.venue_id = ? AND sc.status = 'NEW' LIMIT 257"

@OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
@Serializable
data class VenueAttentionEvent(
    val id: String,
    val kind: String,
    val createdAt: String,
    val orderId: Long? = null,
    val callId: Long? = null,
    val tableNumber: Int,
    @kotlinx.serialization.EncodeDefault(kotlinx.serialization.EncodeDefault.Mode.NEVER)
    val batchId: Long? = null,
    @kotlinx.serialization.EncodeDefault(kotlinx.serialization.EncodeDefault.Mode.NEVER)
    val bookingId: Long? = null,
    @kotlinx.serialization.EncodeDefault(kotlinx.serialization.EncodeDefault.Mode.NEVER)
    val scheduledAt: String? = null,
    @kotlinx.serialization.EncodeDefault(kotlinx.serialization.EncodeDefault.Mode.NEVER)
    val billRequest: Boolean = false,
    @kotlinx.serialization.EncodeDefault(kotlinx.serialization.EncodeDefault.Mode.NEVER)
    val threadId: Long? = null,
)

@OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
@Serializable
data class VenueDashboardSnapshot(
    val venueId: Long,
    val userId: Long,
    val role: String,
    val permissions: List<String>,
    val attention: List<VenueAttentionEvent>,
    val monitoringVersion: Int = 2,
    val eventCapacity: Int = VENUE_EVENT_CAPACITY,
    val attentionComplete: Boolean,
    val attentionTotalAtLeast: Int,
    val activeOrders: Long,
    val callsInWork: Long,
    val bookingsPending: Long? = null,
    @kotlinx.serialization.EncodeDefault(kotlinx.serialization.EncodeDefault.Mode.NEVER)
    val bookingsUpcoming: Long? = null,
    val occupiedTables: Long? = null,
    val activeTables: Long? = null,
    val unavailableItems: Long? = null,
    val unavailableOptions: Long? = null,
    val accountingOpenedAt: String? = null,
    val staffChatLinked: Boolean? = null,
    val venueStatus: String,
    val subscriptionStatus: String? = null,
    val refreshedAt: String,
)

/** Each complete snapshot contains all current unhandled events, without a history cursor.
 * Lower IDs committed later remain discoverable while NEW. The extra row is an explicit
 * capacity sentinel, never proof that omitted events were handled.
 */
class VenueDashboardRepository(private val dataSource: DataSource?) {
    suspend fun snapshot(
        venueId: Long,
        userId: Long,
        monitoringVersion: Int = 2,
    ): VenueDashboardSnapshot =
        withContext(Dispatchers.IO) {
            val ds = dataSource ?: throw DatabaseUnavailableException()
            ds.connection.use { c ->
                c.transactionIsolation = Connection.TRANSACTION_REPEATABLE_READ
                c.isReadOnly = true
                c.autoCommit = false
                try {
                    val scope =
                        c.read(
                            "SELECT vm.role, v.status, v.staff_chat_id FROM venue_members vm " +
                                "JOIN venues v ON v.id = vm.venue_id " +
                                "WHERE vm.venue_id = ? AND vm.user_id = ? AND v.status <> 'DELETED'",
                            venueId,
                            userId,
                        ) { Triple(VenueRoleMapping.fromDb(it.getString(1)), it.getString(2), it.getObject(3) != null) }
                            .singleOrNull() ?: throw ForbiddenException()
                    val role = scope.first ?: throw ForbiddenException()
                    val permissions = VenuePermissions.forRole(role)
                    if (VenuePermission.ORDER_QUEUE_VIEW !in permissions) throw ForbiddenException()
                    val attention = mutableListOf<VenueAttentionEvent>()
                    c.read(
                        PENDING_BATCHES_SQL,
                        venueId,
                    ) { rs ->
                        val id = "batch:${rs.getLong(1)}"
                        attention.add(
                            VenueAttentionEvent(
                                id = id,
                                kind =
                                    if (monitoringVersion == 3 && rs.getObject(6) == null) {
                                        "ORDER_SEND_UNKNOWN"
                                    } else if (rs.getLong(1) == rs.getLong(5)) {
                                        "ORDER_CREATED"
                                    } else {
                                        "BATCH_ADDED"
                                    },
                                createdAt = rs.getTimestamp(3).toInstant().toString(),
                                orderId = rs.getLong(2),
                                batchId = if (monitoringVersion == 3) rs.getLong(1) else null,
                                tableNumber = rs.getInt(4),
                            ),
                        )
                    }
                    c.read(
                        PENDING_CALLS_SQL,
                        venueId,
                    ) { rs ->
                        val id = "call:${rs.getLong(1)}"
                        attention.add(
                            VenueAttentionEvent(
                                id = id,
                                kind = "STAFF_CALL_CREATED",
                                createdAt = rs.getTimestamp(2).toInstant().toString(),
                                callId = rs.getLong(1),
                                billRequest = monitoringVersion == 3 && rs.getString(4) == "BILL",
                                tableNumber = rs.getInt(3),
                            ),
                        )
                    }
                    if (monitoringVersion == 3 && VenuePermission.BOOKING_MANAGE in permissions) {
                        c.read(
                            "SELECT b.id, b.created_at, b.scheduled_at, t.table_number FROM bookings b " +
                                "LEFT JOIN venue_tables t ON t.id = b.table_id AND t.venue_id = b.venue_id " +
                                "WHERE b.venue_id = ? AND b.status = 'PENDING' ORDER BY b.created_at, b.id LIMIT 257",
                            venueId,
                        ) { rs ->
                            attention.add(
                                VenueAttentionEvent(
                                    id = "booking:${rs.getLong(1)}",
                                    kind = "BOOKING_PENDING",
                                    createdAt = rs.getTimestamp(2).toInstant().toString(),
                                    scheduledAt = rs.getTimestamp(3).toInstant().toString(),
                                    bookingId = rs.getLong(1),
                                    tableNumber = rs.getInt(4),
                                ),
                            )
                        }
                    }
                    if (monitoringVersion == 3 && VenuePermission.BOOKING_MANAGE in permissions) {
                        c.read(
                            "SELECT b.id,b.scheduled_at,t.table_number,MAX(m.id),MIN(m.created_at),st.id " +
                                "FROM bookings b JOIN support_threads st ON st.booking_id=b.id AND " +
                                "st.venue_id=b.venue_id " +
                                "AND st.thread_type='BOOKING_THREAD' AND st.status NOT IN ('CLOSED','RESOLVED') " +
                                "AND st.guest_user_id=b.user_id JOIN support_messages m ON m.thread_id=st.id " +
                                "LEFT JOIN support_thread_reads sr ON sr.thread_id=st.id AND sr.user_id=? " +
                                "LEFT JOIN venue_tables t ON t.id=b.table_id AND t.venue_id=b.venue_id " +
                                "WHERE b.venue_id=? AND b.status <> 'PENDING' AND " +
                                "m.author_role='GUEST' AND m.author_user_id=b.user_id " +
                                "AND m.author_user_id IS DISTINCT FROM ? AND (sr.last_read_message_id IS " +
                                "NULL OR m.id>sr.last_read_message_id) " +
                                "GROUP BY b.id,b.scheduled_at,t.table_number,st.id " +
                                "ORDER BY MIN(m.created_at),b.id LIMIT " +
                                "257",
                            userId,
                            venueId,
                            userId,
                        ) { rs ->
                            attention.add(
                                VenueAttentionEvent(
                                    id = "bookingMessage:${rs.getLong(4)}",
                                    kind = "BOOKING_MESSAGE",
                                    bookingId = rs.getLong(1),
                                    threadId = rs.getLong(6),
                                    scheduledAt = rs.getTimestamp(2).toInstant().toString(),
                                    tableNumber = rs.getInt(3),
                                    createdAt = rs.getTimestamp(5).toInstant().toString(),
                                ),
                            )
                        }
                    }
                    val tablesAllowed = VenuePermission.TABLE_VIEW in permissions
                    val menuAllowed = VenuePermission.MENU_VIEW in permissions
                    val snapshot =
                        VenueDashboardSnapshot(
                            venueId = venueId,
                            monitoringVersion = monitoringVersion,
                            userId = userId,
                            role = role.name,
                            permissions = permissions.map { it.name }.sorted(),
                            attention =
                                attention.sortedWith(
                                    compareBy({ it.createdAt }, { it.id }),
                                ).take(VENUE_EVENT_CAPACITY),
                            attentionComplete = attention.size <= VENUE_EVENT_CAPACITY,
                            attentionTotalAtLeast = attention.size.coerceAtMost(VENUE_EVENT_CAPACITY + 1),
                            activeOrders = c.count("orders", "venue_id = ? AND status = 'ACTIVE'", venueId),
                            callsInWork = c.count("staff_calls", "venue_id = ? AND status = 'ACK'", venueId),
                            bookingsPending =
                                if (VenuePermission.BOOKING_VIEW in permissions) {
                                    c.count("bookings", "venue_id = ? AND status = 'PENDING'", venueId)
                                } else {
                                    null
                                },
                            bookingsUpcoming =
                                if (monitoringVersion == 3 && VenuePermission.BOOKING_VIEW in permissions) {
                                    c.count(
                                        "bookings",
                                        "venue_id = ? AND status = 'CONFIRMED' AND scheduled_at >= CURRENT_TIMESTAMP",
                                        venueId,
                                    )
                                } else {
                                    null
                                },
                            occupiedTables =
                                if (tablesAllowed) {
                                    c.read(
                                        "SELECT COUNT(DISTINCT table_id) FROM table_seatings " +
                                            "WHERE venue_id = ? AND released_at IS NULL",
                                        venueId,
                                    ) { it.getLong(1) }.single()
                                } else {
                                    null
                                },
                            activeTables =
                                if (tablesAllowed) {
                                    c.count(
                                        "venue_tables",
                                        "venue_id = ? AND is_active = TRUE",
                                        venueId,
                                    )
                                } else {
                                    null
                                },
                            unavailableItems =
                                if (menuAllowed) {
                                    c.count(
                                        "menu_items",
                                        "venue_id = ? AND is_available = FALSE",
                                        venueId,
                                    )
                                } else {
                                    null
                                },
                            unavailableOptions =
                                if (menuAllowed) {
                                    c.count("menu_item_options", "venue_id = ? AND is_available = FALSE", venueId)
                                } else {
                                    null
                                },
                            accountingOpenedAt =
                                c.read(
                                    "SELECT opened_at FROM venue_accounting_shifts " +
                                        "WHERE venue_id = ? AND status = 'OPEN'",
                                    venueId,
                                ) { it.getTimestamp(1).toInstant().toString() }.singleOrNull(),
                            staffChatLinked = scope.third.takeIf { VenuePermission.STAFF_CHAT_LINK in permissions },
                            venueStatus = scope.second,
                            subscriptionStatus =
                                if (role == VenueRole.OWNER) {
                                    c.read("SELECT status FROM venue_subscriptions WHERE venue_id = ?", venueId) {
                                        it.getString(1)
                                    }.singleOrNull()
                                } else {
                                    null
                                },
                            refreshedAt = Instant.now().toString(),
                        )
                    c.commit()
                    snapshot
                } catch (e: Exception) {
                    c.rollback()
                    throw e
                }
            }
        }
}

private fun Connection.count(
    table: String,
    predicate: String,
    venueId: Long,
): Long = read("SELECT COUNT(*) FROM $table WHERE $predicate", venueId) { it.getLong(1) }.single()

private fun <T> Connection.read(
    sql: String,
    vararg params: Long,
    map: (ResultSet) -> T,
): List<T> =
    prepareStatement(sql).use { statement ->
        statement.queryTimeout = 8
        params.forEachIndexed { index, value -> statement.setLong(index + 1, value) }
        statement.executeQuery().use { rs -> buildList { while (rs.next()) add(map(rs)) } }
    }

fun Route.venueDashboardRoutes(repository: VenueDashboardRepository) {
    val json = Json { encodeDefaults = true }
    get("/venue/{venueId}/dashboard") {
        call.response.headers.append(HttpHeaders.CacheControl, "no-store")
        val requestedVersion = call.request.queryParameters["monitoringVersion"]
        if (requestedVersion != null && requestedVersion !in setOf("2", "3")) {
            throw InvalidInputException("MONITORING_VERSION_UNSUPPORTED")
        }
        val body =
            json.encodeToJsonElement(
                repository.snapshot(
                    call.requireVenueId(),
                    call.requireUserId(),
                    if (call.request.queryParameters["monitoringVersion"] == "3") 3 else 2,
                ),
            )
        check(body.toString().toByteArray(Charsets.UTF_8).size <= VENUE_DASHBOARD_MAX_BYTES)
        call.respond(body)
    }
}
