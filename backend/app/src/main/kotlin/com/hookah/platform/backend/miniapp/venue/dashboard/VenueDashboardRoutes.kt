package com.hookah.platform.backend.miniapp.venue.dashboard

import com.hookah.platform.backend.api.DatabaseUnavailableException
import com.hookah.platform.backend.api.ForbiddenException
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
        "(SELECT first_batch.id FROM order_batches first_batch WHERE first_batch.order_id >= o.id " +
        "ORDER BY first_batch.order_id, first_batch.id LIMIT 1) " +
        "FROM order_batches b JOIN orders o ON o.id = b.order_id " +
        "JOIN venue_tables t ON t.id = o.table_id AND t.venue_id = o.venue_id " +
        "WHERE b.status = 'NEW' AND o.status = 'ACTIVE' AND o.venue_id = ? LIMIT 257"

internal const val PENDING_CALLS_SQL =
    "SELECT sc.id, sc.created_at, t.table_number FROM staff_calls sc " +
        "JOIN venue_tables t ON t.id = sc.table_id AND t.venue_id = sc.venue_id " +
        "WHERE sc.venue_id = ? AND sc.status = 'NEW' LIMIT 257"

@Serializable
data class VenueAttentionEvent(
    val id: String,
    val kind: String,
    val createdAt: String,
    val orderId: Long? = null,
    val callId: Long? = null,
    val tableNumber: Int,
)

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
                                kind = if (rs.getLong(1) == rs.getLong(5)) "ORDER_CREATED" else "BATCH_ADDED",
                                createdAt = rs.getTimestamp(3).toInstant().toString(),
                                orderId = rs.getLong(2),
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
                                tableNumber = rs.getInt(3),
                            ),
                        )
                    }
                    val tablesAllowed = VenuePermission.TABLE_VIEW in permissions
                    val menuAllowed = VenuePermission.MENU_VIEW in permissions
                    val snapshot =
                        VenueDashboardSnapshot(
                            venueId = venueId,
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
        val body = json.encodeToJsonElement(repository.snapshot(call.requireVenueId(), call.requireUserId()))
        check(body.toString().toByteArray(Charsets.UTF_8).size <= VENUE_DASHBOARD_MAX_BYTES)
        call.respond(body)
    }
}
