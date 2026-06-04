package com.hookah.platform.backend.platform

import com.hookah.platform.backend.api.DatabaseUnavailableException
import com.hookah.platform.backend.miniapp.venue.VenueStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.sql.Connection
import java.sql.ResultSet
import java.sql.SQLException
import java.sql.Statement
import java.sql.Timestamp
import java.time.Instant
import java.util.Locale
import javax.sql.DataSource

class PlatformVenueRepository(private val dataSource: DataSource?) {
    suspend fun createVenue(
        name: String,
        city: String?,
        address: String?,
        ownerAccountId: Long? = null,
    ): PlatformVenueDetail {
        val ds = dataSource ?: throw DatabaseUnavailableException()
        return withContext(Dispatchers.IO) {
            try {
                ds.connection.use { connection ->
                    val venueId = insertVenue(connection, name, city, address, ownerAccountId)
                    loadVenueDetail(connection, venueId) ?: throw DatabaseUnavailableException()
                }
            } catch (e: SQLException) {
                throw DatabaseUnavailableException()
            }
        }
    }

    suspend fun listVenues(filter: PlatformVenueFilter): List<PlatformVenueSummary> {
        val ds = dataSource ?: throw DatabaseUnavailableException()
        return withContext(Dispatchers.IO) {
            try {
                ds.connection.use { connection ->
                    val query =
                        StringBuilder(
                            """
                            SELECT v.id,
                                   v.name,
                                   v.city,
                                   v.status,
                                   v.created_at,
                                   (
                                       SELECT COUNT(*)
                                       FROM venue_members vm
                                       WHERE vm.venue_id = v.id
                                         AND UPPER(vm.role) = 'OWNER'
                                   ) AS owners_count,
                                   vs.status AS subscription_status,
                                   vs.trial_end,
                                   vs.paid_start
                            FROM venues v
                            LEFT JOIN venue_subscriptions vs ON vs.venue_id = v.id
                            """.trimIndent(),
                        )
                    val conditions = mutableListOf<String>()
                    val params = mutableListOf<Any>()

                    conditions.add("v.status <> ?")
                    params.add(VenueStatus.DELETED.dbValue)
                    if (filter.status != null) {
                        conditions.add("v.status = ?")
                        params.add(filter.status.dbValue)
                    }
                    if (!filter.query.isNullOrBlank()) {
                        conditions.add("LOWER(v.name) LIKE ?")
                        params.add("%${filter.query.trim().lowercase(Locale.ROOT)}%")
                    }
                    when (filter.subscriptionFilter) {
                        SubscriptionFilter.TRIAL_ACTIVE -> {
                            conditions.add("UPPER(vs.status) = ?")
                            params.add("TRIAL")
                            conditions.add("vs.trial_end IS NOT NULL AND vs.trial_end >= CURRENT_TIMESTAMP")
                        }
                        SubscriptionFilter.PAID -> {
                            conditions.add("UPPER(vs.status) = ?")
                            params.add("ACTIVE")
                            conditions.add("vs.paid_start IS NOT NULL")
                        }
                        SubscriptionFilter.NONE -> conditions.add("vs.venue_id IS NULL")
                        null -> Unit
                    }

                    if (conditions.isNotEmpty()) {
                        query.append(" WHERE ").append(conditions.joinToString(" AND "))
                    }
                    query.append(" ORDER BY v.id ASC LIMIT ? OFFSET ?")
                    params.add(filter.limit)
                    params.add(filter.offset)

                    connection.prepareStatement(query.toString()).use { statement ->
                        params.forEachIndexed { index, value ->
                            when (value) {
                                is String -> statement.setString(index + 1, value)
                                is Int -> statement.setInt(index + 1, value)
                                is Long -> statement.setLong(index + 1, value)
                                else -> statement.setObject(index + 1, value)
                            }
                        }
                        statement.executeQuery().use { rs ->
                            val result = mutableListOf<PlatformVenueSummary>()
                            while (rs.next()) {
                                mapVenueSummary(rs)?.let { result.add(it) }
                            }
                            result
                        }
                    }
                }
            } catch (e: SQLException) {
                throw DatabaseUnavailableException()
            }
        }
    }

    suspend fun getVenueDetail(venueId: Long): PlatformVenueDetail? {
        val ds = dataSource ?: throw DatabaseUnavailableException()
        return withContext(Dispatchers.IO) {
            try {
                ds.connection.use { connection ->
                    loadVenueDetail(connection, venueId)
                }
            } catch (e: SQLException) {
                throw DatabaseUnavailableException()
            }
        }
    }

    suspend fun updateVenueName(
        venueId: Long,
        name: String,
    ): PlatformVenueDetail? {
        val ds = dataSource ?: throw DatabaseUnavailableException()
        return withContext(Dispatchers.IO) {
            try {
                ds.connection.use { connection ->
                    connection.autoCommit = false
                    try {
                        val updatedRows =
                            connection.prepareStatement(
                                """
                                UPDATE venues
                                SET name = ?
                                WHERE id = ?
                                """.trimIndent(),
                            ).use { statement ->
                                statement.setString(1, name)
                                statement.setLong(2, venueId)
                                statement.executeUpdate()
                            }
                        if (updatedRows <= 0) {
                            connection.rollback()
                            return@withContext null
                        }
                        val updated = loadVenueDetail(connection, venueId)
                        connection.commit()
                        updated
                    } catch (e: SQLException) {
                        runCatching { connection.rollback() }
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

    suspend fun updateVenueAddress(
        venueId: Long,
        address: String,
    ): PlatformVenueDetail? {
        val ds = dataSource ?: throw DatabaseUnavailableException()
        return withContext(Dispatchers.IO) {
            try {
                ds.connection.use { connection ->
                    connection.autoCommit = false
                    try {
                        val updatedRows =
                            connection.prepareStatement(
                                """
                                UPDATE venues
                                SET address = ?
                                WHERE id = ?
                                """.trimIndent(),
                            ).use { statement ->
                                statement.setString(1, address)
                                statement.setLong(2, venueId)
                                statement.executeUpdate()
                            }
                        if (updatedRows <= 0) {
                            connection.rollback()
                            return@withContext null
                        }
                        val updated = loadVenueDetail(connection, venueId)
                        connection.commit()
                        updated
                    } catch (e: SQLException) {
                        runCatching { connection.rollback() }
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

    suspend fun updateVenueCity(
        venueId: Long,
        city: String,
    ): PlatformVenueDetail? =
        updateVenueTextField(
            venueId = venueId,
            columnName = "city",
            value = city,
        )

    suspend fun updateVenueGuestContact(
        venueId: Long,
        guestContact: String,
    ): PlatformVenueDetail? =
        updateVenueTextField(
            venueId = venueId,
            columnName = "guest_contact",
            value = guestContact,
        )

    suspend fun updateVenueCardDescription(
        venueId: Long,
        cardDescription: String,
    ): PlatformVenueDetail? =
        updateVenueTextField(
            venueId = venueId,
            columnName = "card_description",
            value = cardDescription,
        )

    private suspend fun updateVenueTextField(
        venueId: Long,
        columnName: String,
        value: String,
    ): PlatformVenueDetail? {
        val ds = dataSource ?: throw DatabaseUnavailableException()
        return withContext(Dispatchers.IO) {
            try {
                ds.connection.use { connection ->
                    connection.autoCommit = false
                    try {
                        val updatedRows =
                            connection.prepareStatement(
                                """
                                UPDATE venues
                                SET $columnName = ?
                                WHERE id = ?
                                """.trimIndent(),
                            ).use { statement ->
                                statement.setString(1, value)
                                statement.setLong(2, venueId)
                                statement.executeUpdate()
                            }
                        if (updatedRows <= 0) {
                            connection.rollback()
                            return@withContext null
                        }
                        val updated = loadVenueDetail(connection, venueId)
                        connection.commit()
                        updated
                    } catch (e: SQLException) {
                        runCatching { connection.rollback() }
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

    suspend fun listOwners(venueId: Long): List<PlatformVenueOwner> {
        val ds = dataSource ?: throw DatabaseUnavailableException()
        return withContext(Dispatchers.IO) {
            try {
                ds.connection.use { connection ->
                    connection.prepareStatement(
                        """
                        SELECT vm.user_id,
                               vm.role,
                               u.username,
                               u.first_name,
                               u.last_name
                        FROM venue_members vm
                        LEFT JOIN users u ON u.telegram_user_id = vm.user_id
                        WHERE vm.venue_id = ?
                          AND UPPER(vm.role) = 'OWNER'
                        ORDER BY vm.created_at, vm.user_id
                        """.trimIndent(),
                    ).use { statement ->
                        statement.setLong(1, venueId)
                        statement.executeQuery().use { rs ->
                            val owners = mutableListOf<PlatformVenueOwner>()
                            while (rs.next()) {
                                owners.add(
                                    PlatformVenueOwner(
                                        userId = rs.getLong("user_id"),
                                        role = rs.getString("role"),
                                        username = rs.getString("username"),
                                        firstName = rs.getString("first_name"),
                                        lastName = rs.getString("last_name"),
                                    ),
                                )
                            }
                            owners
                        }
                    }
                }
            } catch (e: SQLException) {
                throw DatabaseUnavailableException()
            }
        }
    }

    suspend fun getSubscriptionSummary(venueId: Long): PlatformSubscriptionSummary? {
        val ds = dataSource ?: throw DatabaseUnavailableException()
        return withContext(Dispatchers.IO) {
            try {
                ds.connection.use { connection ->
                    connection.prepareStatement(
                        """
                        SELECT status, trial_end, paid_start
                        FROM venue_subscriptions
                        WHERE venue_id = ?
                        """.trimIndent(),
                    ).use { statement ->
                        statement.setLong(1, venueId)
                        statement.executeQuery().use { rs ->
                            if (rs.next()) {
                                PlatformSubscriptionSummary(
                                    trialEndDate = rs.getTimestamp("trial_end")?.toInstant(),
                                    paidStartDate = rs.getTimestamp("paid_start")?.toInstant(),
                                    isPaid = isPaidStatus(rs.getString("status")),
                                )
                            } else {
                                null
                            }
                        }
                    }
                }
            } catch (e: SQLException) {
                throw DatabaseUnavailableException()
            }
        }
    }

    suspend fun updateStatus(
        venueId: Long,
        action: VenueStatusAction,
    ): VenueStatusChangeResult {
        val ds = dataSource ?: return VenueStatusChangeResult.DatabaseError
        return withContext(Dispatchers.IO) {
            try {
                ds.connection.use { connection ->
                    val initialAutoCommit = connection.autoCommit
                    connection.autoCommit = false
                    try {
                        val current =
                            selectVenueStatus(connection, venueId)
                                ?: return@use rollbackAndReturn(connection) { VenueStatusChangeResult.NotFound }
                        if (current == VenueStatus.DELETED && action == VenueStatusAction.DELETE) {
                            return@use rollbackAndReturn(connection) { VenueStatusChangeResult.AlreadyDeleted }
                        }
                        if (action == VenueStatusAction.PUBLISH && countOwnerLike(connection, venueId) == 0) {
                            return@use rollbackAndReturn(connection) { VenueStatusChangeResult.MissingOwner }
                        }
                        val targetStatus =
                            resolveTransition(current, action)
                                ?: return@use rollbackAndReturn(connection) {
                                    VenueStatusChangeResult.InvalidTransition
                                }
                        val now = Timestamp.from(Instant.now())
                        connection.prepareStatement(
                            """
                            UPDATE venues
                            SET status = ?,
                                deleted_at = ?,
                                updated_at = CURRENT_TIMESTAMP
                            WHERE id = ?
                            """.trimIndent(),
                        ).use { statement ->
                            statement.setString(1, targetStatus.dbValue)
                            if (action == VenueStatusAction.DELETE) {
                                statement.setTimestamp(2, now)
                            } else {
                                statement.setTimestamp(2, null)
                            }
                            statement.setLong(3, venueId)
                            if (statement.executeUpdate() == 0) {
                                return@use rollbackAndReturn(connection) { VenueStatusChangeResult.NotFound }
                            }
                        }
                        val updated =
                            loadVenueDetail(connection, venueId)
                                ?: return@use rollbackAndReturn(connection) { VenueStatusChangeResult.DatabaseError }
                        connection.commit()
                        VenueStatusChangeResult.Success(updated, current, targetStatus)
                    } catch (e: SQLException) {
                        rollbackBestEffort(connection)
                        VenueStatusChangeResult.DatabaseError
                    } finally {
                        runCatching { connection.autoCommit = initialAutoCommit }
                    }
                }
            } catch (e: SQLException) {
                VenueStatusChangeResult.DatabaseError
            }
        }
    }

    suspend fun findLatestStatusAudit(venueId: Long): PlatformVenueStatusAudit? {
        val ds = dataSource ?: throw DatabaseUnavailableException()
        return withContext(Dispatchers.IO) {
            try {
                ds.connection.use { connection ->
                    connection.prepareStatement(
                        """
                        SELECT actor_user_id, payload_json, created_at
                        FROM audit_log
                        WHERE entity_type = ? AND entity_id = ? AND action = ?
                        ORDER BY created_at DESC
                        LIMIT 1
                        """.trimIndent(),
                    ).use { statement ->
                        statement.setString(1, "venue")
                        statement.setLong(2, venueId)
                        statement.setString(3, "VENUE_STATUS_CHANGE")
                        statement.executeQuery().use { rs ->
                            if (!rs.next()) return@withContext null
                            mapStatusAudit(rs)
                        }
                    }
                }
            } catch (e: SQLException) {
                throw DatabaseUnavailableException()
            }
        }
    }

    private fun insertVenue(
        connection: Connection,
        name: String,
        city: String?,
        address: String?,
        ownerAccountId: Long?,
    ): Long {
        if (ownerAccountId == null) {
            connection.prepareStatement(
                """
                INSERT INTO venues (name, city, address, status)
                VALUES (?, ?, ?, ?)
                """.trimIndent(),
                Statement.RETURN_GENERATED_KEYS,
            ).use { statement ->
                statement.setString(1, name)
                statement.setString(2, city)
                statement.setString(3, address)
                statement.setString(4, VenueStatus.DRAFT.dbValue)
                statement.executeUpdate()
                statement.generatedKeys.use { rs ->
                    if (rs.next()) {
                        return rs.getLong(1)
                    }
                }
            }
        } else {
            connection.prepareStatement(
                """
                INSERT INTO venues (name, city, address, status, owner_account_id)
                VALUES (?, ?, ?, ?, ?)
                """.trimIndent(),
                Statement.RETURN_GENERATED_KEYS,
            ).use { statement ->
                statement.setString(1, name)
                statement.setString(2, city)
                statement.setString(3, address)
                statement.setString(4, VenueStatus.DRAFT.dbValue)
                statement.setLong(5, ownerAccountId)
                statement.executeUpdate()
                statement.generatedKeys.use { rs ->
                    if (rs.next()) {
                        return rs.getLong(1)
                    }
                }
            }
        }
        throw SQLException("Failed to insert venue")
    }

    private fun loadVenueDetail(
        connection: Connection,
        venueId: Long,
    ): PlatformVenueDetail? {
        return connection.prepareStatement(
            """
            SELECT id, name, city, address, guest_contact, card_description, status, created_at, deleted_at
            FROM venues
            WHERE id = ?
            """.trimIndent(),
        ).use { statement ->
            statement.setLong(1, venueId)
            statement.executeQuery().use { rs ->
                if (rs.next()) {
                    mapVenueDetail(rs)
                } else {
                    null
                }
            }
        }
    }

    private fun mapVenueDetail(rs: ResultSet): PlatformVenueDetail? {
        val status = VenueStatus.fromDb(rs.getString("status")) ?: return null
        return PlatformVenueDetail(
            id = rs.getLong("id"),
            name = rs.getString("name"),
            city = rs.getString("city"),
            address = rs.getString("address"),
            guestContact = rs.getString("guest_contact"),
            cardDescription = rs.getString("card_description"),
            status = status,
            createdAt = rs.getTimestamp("created_at")?.toInstant() ?: Instant.EPOCH,
            deletedAt = rs.getTimestamp("deleted_at")?.toInstant(),
        )
    }

    private fun mapVenueSummary(rs: ResultSet): PlatformVenueSummary? {
        val status = VenueStatus.fromDb(rs.getString("status")) ?: return null
        val subscription =
            PlatformSubscriptionSummary(
                trialEndDate = rs.getTimestamp("trial_end")?.toInstant(),
                paidStartDate = rs.getTimestamp("paid_start")?.toInstant(),
                isPaid = isPaidStatus(rs.getString("subscription_status")),
            )
        return PlatformVenueSummary(
            id = rs.getLong("id"),
            name = rs.getString("name"),
            city = rs.getString("city"),
            status = status,
            createdAt = rs.getTimestamp("created_at")?.toInstant() ?: Instant.EPOCH,
            ownersCount = rs.getInt("owners_count"),
            subscriptionSummary = subscription,
        )
    }

    private fun selectVenueStatus(
        connection: Connection,
        venueId: Long,
    ): VenueStatus? {
        return connection.prepareStatement(
            """
            SELECT status
            FROM venues
            WHERE id = ?
            FOR UPDATE
            """.trimIndent(),
        ).use { statement ->
            statement.setLong(1, venueId)
            statement.executeQuery().use { rs ->
                if (rs.next()) VenueStatus.fromDb(rs.getString("status")) else null
            }
        }
    }

    private fun countOwnerLike(
        connection: Connection,
        venueId: Long,
    ): Int {
        return connection.prepareStatement(
            """
            SELECT COUNT(*)
            FROM venue_members
            WHERE venue_id = ? AND UPPER(role) = 'OWNER'
            """.trimIndent(),
        ).use { statement ->
            statement.setLong(1, venueId)
            statement.executeQuery().use { rs ->
                if (rs.next()) rs.getInt(1) else 0
            }
        }
    }

    private fun resolveTransition(
        current: VenueStatus,
        action: VenueStatusAction,
    ): VenueStatus? {
        return when (action) {
            VenueStatusAction.PUBLISH ->
                when (current) {
                    VenueStatus.DRAFT,
                    VenueStatus.HIDDEN,
                    VenueStatus.PAUSED,
                    VenueStatus.SUSPENDED,
                    VenueStatus.ARCHIVED,
                    -> VenueStatus.PUBLISHED
                    else -> null
                }
            VenueStatusAction.HIDE -> if (current == VenueStatus.PUBLISHED) VenueStatus.HIDDEN else null
            VenueStatusAction.PAUSE -> if (current == VenueStatus.PUBLISHED) VenueStatus.PAUSED else null
            VenueStatusAction.SUSPEND -> if (current != VenueStatus.DELETED) VenueStatus.SUSPENDED else null
            VenueStatusAction.ARCHIVE ->
                when (current) {
                    VenueStatus.HIDDEN,
                    VenueStatus.PAUSED,
                    VenueStatus.SUSPENDED,
                    VenueStatus.DRAFT,
                    VenueStatus.PUBLISHED,
                    -> VenueStatus.ARCHIVED
                    else -> null
                }
            VenueStatusAction.DELETE -> if (current != VenueStatus.DELETED) VenueStatus.DELETED else null
        }
    }

    private fun isPaidStatus(status: String?): Boolean {
        return status?.trim()?.uppercase(Locale.ROOT) == "ACTIVE"
    }

    private fun mapStatusAudit(rs: ResultSet): PlatformVenueStatusAudit {
        val payloadJson = rs.getString("payload_json")
        val payload =
            runCatching {
                Json.parseToJsonElement(payloadJson).jsonObject
            }.getOrNull()
        val actorUserId =
            rs.getLong("actor_user_id").let { value ->
                if (rs.wasNull()) null else value
            }
        return PlatformVenueStatusAudit(
            actorUserId = actorUserId,
            action = payload?.get("action")?.jsonPrimitive?.contentOrNull,
            fromStatus = payload?.get("fromStatus")?.jsonPrimitive?.contentOrNull,
            toStatus = payload?.get("toStatus")?.jsonPrimitive?.contentOrNull,
            reason = payload?.get("reason")?.jsonPrimitive?.contentOrNull,
            createdAt = rs.getTimestamp("created_at")?.toInstant(),
        )
    }

    private fun rollbackBestEffort(connection: Connection) {
        runCatching { connection.rollback() }
    }

    private fun <T> rollbackAndReturn(
        connection: Connection,
        block: () -> T,
    ): T {
        runCatching { connection.rollback() }
        return block()
    }
}

data class PlatformVenueFilter(
    val status: VenueStatus?,
    val subscriptionFilter: SubscriptionFilter?,
    val query: String?,
    val limit: Int,
    val offset: Int,
)

data class PlatformVenueSummary(
    val id: Long,
    val name: String,
    val city: String?,
    val status: VenueStatus,
    val createdAt: Instant,
    val ownersCount: Int,
    val subscriptionSummary: PlatformSubscriptionSummary,
)

data class PlatformVenueDetail(
    val id: Long,
    val name: String,
    val city: String?,
    val address: String?,
    val status: VenueStatus,
    val createdAt: Instant,
    val deletedAt: Instant?,
    val guestContact: String? = null,
    val cardDescription: String? = null,
)

data class PlatformVenueStatusAudit(
    val actorUserId: Long?,
    val action: String?,
    val fromStatus: String?,
    val toStatus: String?,
    val reason: String?,
    val createdAt: Instant?,
)

data class PlatformVenueOwner(
    val userId: Long,
    val role: String,
    val username: String?,
    val firstName: String?,
    val lastName: String?,
)

data class PlatformSubscriptionSummary(
    val trialEndDate: Instant?,
    val paidStartDate: Instant?,
    val isPaid: Boolean?,
)

enum class SubscriptionFilter {
    TRIAL_ACTIVE,
    PAID,
    NONE,
}

enum class VenueStatusAction(val wire: String) {
    PUBLISH("publish"),
    HIDE("hide"),
    PAUSE("pause"),
    SUSPEND("suspend"),
    ARCHIVE("archive"),
    DELETE("delete"),
    ;

    companion object {
        fun fromWire(value: String?): VenueStatusAction? {
            val normalized = value?.trim()?.lowercase()
            return entries.firstOrNull { it.wire == normalized }
        }
    }
}

sealed interface VenueStatusChangeResult {
    data class Success(
        val venue: PlatformVenueDetail,
        val fromStatus: VenueStatus,
        val toStatus: VenueStatus,
    ) : VenueStatusChangeResult

    data object NotFound : VenueStatusChangeResult

    data object InvalidTransition : VenueStatusChangeResult

    data object MissingOwner : VenueStatusChangeResult

    data object AlreadyDeleted : VenueStatusChangeResult

    data object DatabaseError : VenueStatusChangeResult
}
