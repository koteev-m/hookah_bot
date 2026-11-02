package com.hookah.platform.backend.venue.links

import com.hookah.platform.backend.api.DatabaseUnavailableException
import com.hookah.platform.backend.api.ForbiddenException
import com.hookah.platform.backend.api.InvalidInputException
import com.hookah.platform.backend.api.NotFoundException
import com.hookah.platform.backend.miniapp.venue.AuditLogRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import java.sql.Connection
import javax.sql.DataSource

@Serializable
data class VenueLinkSettings(
    val venueId: Long,
    val name: String,
    val address: String,
    val slug: String?,
    val catalogListed: Boolean,
    val canManage: Boolean,
    val guestAvailable: Boolean = false,
    val url: String? = null,
)

class VenuePublicLinksRepository(private val dataSource: DataSource?, private val platformOwnerId: Long?) {
    suspend fun resolveSlug(slug: String): Long? {
        if (!VenueSlugs.valid(slug)) return null
        return transaction { connection ->
            connection.prepareStatement("SELECT venue_id FROM venue_public_addresses WHERE slug = ?").use {
                it.setString(1, slug)
                it.executeQuery().use { rows -> if (rows.next()) rows.getLong(1) else null }
            }
        }
    }

    suspend fun settings(
        venueId: Long,
        actor: Long,
    ): VenueLinkSettings = transaction { read(it, venueId, actor) }

    suspend fun update(
        venueId: Long,
        actor: Long,
        request: VenueLinkUpdate,
    ): VenueLinkSettings =
        transaction { connection ->
            connection.prepareStatement("SELECT id FROM venues WHERE id = ? FOR UPDATE").use {
                it.setLong(1, venueId)
                it.executeQuery().use { rows -> if (!rows.next()) throw NotFoundException() }
            }
            val before = read(connection, venueId, actor)
            if (!before.canManage) throw ForbiddenException()
            if (!request.confirmed || request.expectedSlug != before.slug) {
                throw InvalidInputException("Подтвердите изменение актуальной ссылки заведения.")
            }
            val slug = request.slug ?: before.slug
            if (slug == null || !VenueSlugs.valid(slug)) {
                throw InvalidInputException(
                    "Адрес: 1–64 латинских букв, цифр и дефисов.",
                )
            }
            if (slug != before.slug && !VenueSlugs.reserve(connection, venueId, slug)) {
                throw InvalidInputException("Этот адрес уже использовался. Выберите другой.")
            }
            val listed = request.catalogListed ?: before.catalogListed
            if (slug != before.slug || listed != before.catalogListed) {
                connection.prepareStatement("UPDATE venues SET public_slug = ?, catalog_listed = ? WHERE id = ?").use {
                    it.setString(1, slug)
                    it.setBoolean(2, listed)
                    it.setLong(3, venueId)
                    it.executeUpdate()
                }
                AuditLogRepository(dataSource).append(
                    connection,
                    actor,
                    "VENUE_PUBLIC_LINK_UPDATED",
                    "venue",
                    venueId,
                    """{"oldSlug":"${before.slug.orEmpty()}","slug":"$slug","catalogListed":$listed}""",
                )
            }
            read(connection, venueId, actor)
        }

    private fun read(
        connection: Connection,
        venueId: Long,
        actor: Long,
    ): VenueLinkSettings {
        val role =
            connection.prepareStatement("SELECT role FROM venue_members WHERE venue_id = ? AND user_id = ?").use {
                it.setLong(1, venueId)
                it.setLong(2, actor)
                it.executeQuery().use { rows -> if (rows.next()) rows.getString(1) else null }
            }
        val platform = actor == platformOwnerId
        if (!platform && role !in setOf("OWNER", "MANAGER", "ADMIN")) throw ForbiddenException()
        return connection.prepareStatement(
            """
            SELECT name, city, address, formatted_address, public_slug, catalog_listed
            FROM venues WHERE id = ? AND status <> 'DELETED'
            """.trimIndent(),
        ).use {
            it.setLong(1, venueId)
            it.executeQuery().use { rows ->
                if (!rows.next()) throw NotFoundException()
                VenueLinkSettings(
                    venueId,
                    rows.getString(1),
                    rows.getString(4)?.takeIf(String::isNotBlank)
                        ?: listOfNotNull(rows.getString(2), rows.getString(3))
                            .filter(String::isNotBlank).joinToString(", "),
                    rows.getString(5),
                    rows.getBoolean(6),
                    platform || role == "OWNER",
                )
            }
        }
    }

    private suspend fun <T> transaction(block: (Connection) -> T): T =
        withContext(Dispatchers.IO) {
            val ds = dataSource ?: throw DatabaseUnavailableException()
            ds.connection.use { connection ->
                connection.autoCommit = false
                try {
                    val result = block(connection)
                    connection.commit()
                    result
                } catch (error: Exception) {
                    connection.rollback()
                    throw error
                }
            }
        }
}

@Serializable
data class VenueLinkUpdate(
    val expectedSlug: String?,
    val confirmed: Boolean = false,
    val slug: String? = null,
    val catalogListed: Boolean? = null,
)
