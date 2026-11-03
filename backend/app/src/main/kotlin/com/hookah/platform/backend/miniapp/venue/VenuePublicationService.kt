package com.hookah.platform.backend.miniapp.venue

import com.hookah.platform.backend.api.DatabaseUnavailableException
import com.hookah.platform.backend.api.ForbiddenException
import com.hookah.platform.backend.api.NotFoundException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.sql.Connection
import java.sql.SQLException
import javax.sql.DataSource

@Serializable
enum class VenuePublicationRequirement(val label: String) {
    NAME("Название"),
    ADDRESS("Адрес"),
    HOURS("Часы работы"),
    DESCRIPTION("Краткое описание карточки"),
    MENU("Меню"),
}

@Serializable
enum class VenuePublicationOutcome {
    PUBLISHED,
    ALREADY_PUBLISHED,
    NOT_READY,
    INVALID_STATUS,
}

data class VenuePublicationState(
    val name: String,
    val status: VenueStatus?,
    val ownerCanPublish: Boolean,
    val missing: List<VenuePublicationRequirement>? = null,
)

data class VenuePublicationResult(val outcome: VenuePublicationOutcome, val state: VenuePublicationState)

enum class VenuePublicationSource {
    OWNER_BOT,
    VENUE_MINI_APP,
}

class VenuePublicationService(
    private val dataSource: DataSource?,
    private val audit: TransactionalAuditLogWriter = AuditLogRepository(dataSource),
) {
    suspend fun inspect(
        venueId: Long,
        actor: Long,
        checkReadiness: Boolean,
    ): VenuePublicationState =
        database { connection ->
            val role = role(connection, venueId, actor)
            if (role !in setOf("OWNER", "MANAGER", "ADMIN")) throw ForbiddenException()
            val card = card(connection, venueId, lock = false)
            state(card, role == "OWNER", if (checkReadiness) missing(connection, venueId, card) else null)
        }

    suspend fun publish(
        venueId: Long,
        actor: Long,
        source: VenuePublicationSource,
    ): VenuePublicationResult =
        database { connection ->
            if (role(connection, venueId, actor) != "OWNER") throw ForbiddenException()
            connection.autoCommit = false
            try {
                // Platform lifecycle changes and owner membership changes use the same venue lock.
                val card = card(connection, venueId, lock = true)
                if (role(connection, venueId, actor, lock = true) != "OWNER") throw ForbiddenException()
                val before = state(card, true)
                val result =
                    when (card.status) {
                        VenueStatus.PUBLISHED ->
                            VenuePublicationResult(
                                VenuePublicationOutcome.ALREADY_PUBLISHED,
                                before,
                            )
                        VenueStatus.DRAFT -> publishDraft(connection, venueId, actor, source, card, before)
                        else -> VenuePublicationResult(VenuePublicationOutcome.INVALID_STATUS, before)
                    }
                connection.commit()
                result
            } catch (error: Exception) {
                connection.rollback()
                throw error
            }
        }

    private fun publishDraft(
        connection: Connection,
        venueId: Long,
        actor: Long,
        source: VenuePublicationSource,
        card: Card,
        before: VenuePublicationState,
    ): VenuePublicationResult {
        val missing = missing(connection, venueId, card)
        if (missing.isNotEmpty()) {
            return VenuePublicationResult(VenuePublicationOutcome.NOT_READY, before.copy(missing = missing))
        }
        connection.prepareStatement(
            "UPDATE venues SET status = 'PUBLISHED', updated_at = CURRENT_TIMESTAMP WHERE id = ? AND status = 'DRAFT'",
        ).use {
            it.setLong(1, venueId)
            if (it.executeUpdate() != 1) throw DatabaseUnavailableException()
        }
        audit.appendJson(
            connection,
            actor,
            "VENUE_STATUS_CHANGE",
            "venue",
            venueId,
            buildJsonObject {
                put("action", "publish")
                put("fromStatus", "DRAFT")
                put("toStatus", "PUBLISHED")
                put("source", source.name)
            },
        )
        return VenuePublicationResult(
            VenuePublicationOutcome.PUBLISHED,
            before.copy(status = VenueStatus.PUBLISHED, ownerCanPublish = false),
        )
    }

    private fun role(
        connection: Connection,
        venueId: Long,
        actor: Long,
        lock: Boolean = false,
    ): String? {
        val lockClause = if (lock) " FOR UPDATE" else ""
        return connection.prepareStatement(
            "SELECT role FROM venue_members WHERE venue_id = ? AND user_id = ?$lockClause",
        ).use {
            it.setLong(1, venueId)
            it.setLong(2, actor)
            it.executeQuery().use { rows -> if (rows.next()) rows.getString(1) else null }
        }
    }

    private data class Card(val name: String, val address: String?, val description: String?, val status: VenueStatus?)

    private fun card(
        connection: Connection,
        venueId: Long,
        lock: Boolean,
    ): Card {
        val lockClause = if (lock) " FOR UPDATE" else ""
        return connection.prepareStatement(
            "SELECT name, address, card_description, status FROM venues WHERE id = ?$lockClause",
        ).use {
            it.setLong(1, venueId)
            it.executeQuery().use { rows ->
                if (!rows.next()) throw NotFoundException()
                Card(rows.getString(1), rows.getString(2), rows.getString(3), VenueStatus.fromDb(rows.getString(4)))
            }
        }
    }

    private fun state(
        card: Card,
        isOwner: Boolean,
        missing: List<VenuePublicationRequirement>? = null,
    ) = VenuePublicationState(card.name, card.status, isOwner && card.status == VenueStatus.DRAFT, missing)

    private fun missing(
        connection: Connection,
        venueId: Long,
        card: Card,
    ): List<VenuePublicationRequirement> =
        connection.prepareStatement(
            """
            SELECT
                (SELECT COUNT(DISTINCT weekday) FROM venue_booking_hours WHERE venue_id = ?) = 7,
                EXISTS (
                    SELECT 1
                    FROM menu_items mi JOIN menu_categories mc ON mc.id = mi.category_id AND mc.venue_id = mi.venue_id
                    WHERE mi.venue_id = ? AND mi.is_available = true AND mc.is_active = true
                )
            """.trimIndent(),
        ).use {
            it.setLong(1, venueId)
            it.setLong(2, venueId)
            it.executeQuery().use { rows ->
                if (!rows.next()) throw DatabaseUnavailableException()
                buildList {
                    if (card.name.isBlank()) add(VenuePublicationRequirement.NAME)
                    if (card.address.isNullOrBlank()) add(VenuePublicationRequirement.ADDRESS)
                    if (!rows.getBoolean(1)) add(VenuePublicationRequirement.HOURS)
                    if (card.description.isNullOrBlank()) add(VenuePublicationRequirement.DESCRIPTION)
                    if (!rows.getBoolean(2)) add(VenuePublicationRequirement.MENU)
                }
            }
        }

    private suspend fun <T> database(block: (Connection) -> T): T =
        withContext(Dispatchers.IO) {
            val ds = dataSource ?: throw DatabaseUnavailableException()
            try {
                ds.connection.use(block)
            } catch (_: SQLException) {
                throw DatabaseUnavailableException()
            }
        }
}
