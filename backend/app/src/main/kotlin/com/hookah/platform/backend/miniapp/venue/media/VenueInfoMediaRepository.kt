package com.hookah.platform.backend.miniapp.venue.media

import com.hookah.platform.backend.api.DatabaseUnavailableException
import com.hookah.platform.backend.api.ForbiddenException
import com.hookah.platform.backend.api.NotFoundException
import com.hookah.platform.backend.api.VenueInfoMediaConflictException
import com.hookah.platform.backend.api.VenueInfoMediaInvalidException
import com.hookah.platform.backend.miniapp.venue.AuditLogRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import java.sql.Connection
import java.sql.SQLException
import java.sql.Timestamp
import java.time.Clock
import java.util.UUID
import javax.sql.DataSource

@Serializable
data class VenueInfoMediaItemDto(
    val id: String,
    val type: String,
    val visible: Boolean,
    val status: String,
    val sortOrder: Int,
    val size: Int?,
    val source: String,
)

@Serializable
data class VenueInfoMediaSectionDto(
    val id: Long,
    val title: String,
    val type: String,
    val visible: Boolean,
    val revision: Long,
    val media: List<VenueInfoMediaItemDto>,
)

@Serializable
data class VenueInfoMediaStateDto(val enabled: Boolean, val sections: List<VenueInfoMediaSectionDto>)

@Serializable
data class VenueInfoMediaOperationDto(val status: String)

internal data class MediaReservation(val id: String, val key: String, val fresh: Boolean)

class VenueInfoMediaRepository(
    private val dataSource: DataSource?,
    private val audit: AuditLogRepository,
    private val clock: Clock = Clock.systemUTC(),
) {
    suspend fun authorize(
        venue: Long,
        actor: Long,
    ) = tx { c -> authorize(c, venue, actor) }

    suspend fun list(
        venue: Long,
        actor: Long,
    ): List<VenueInfoMediaSectionDto> =
        tx { c ->
            authorize(c, venue, actor)
            c.prepareStatement("SELECT * FROM venue_info_sections WHERE venue_id=? ORDER BY sort_order,id").use { s ->
                s.setLong(1, venue)
                s.executeQuery().use { rs ->
                    buildList {
                        while (rs.next()) {
                            val section = rs.getLong("id")
                            val items =
                                c.prepareStatement(
                                    """
                                    SELECT *
                                    FROM venue_info_section_media
                                    WHERE section_id=?
                                    AND status IN ('READY','UPLOADING')
                                    ORDER BY sort_order,id
                                    """,
                                ).use {
                                        m ->
                                    m.setLong(1, section)
                                    m.executeQuery().use { rows ->
                                        buildList {
                                            while (rows.next()) add(
                                                VenueInfoMediaItemDto(
                                                    rows.getString("public_id") ?: rows.getLong("id").toString(),
                                                    rows.getString(
                                                        "media_type",
                                                    ),
                                                    rows.getBoolean(
                                                        "is_visible",
                                                    ),
                                                    rows.getString(
                                                        "status",
                                                    ),
                                                    rows.getInt("sort_order"),
                                                    rows.getObject("size_bytes")?.let {
                                                        rows.getInt("size_bytes")
                                                    },
                                                    rows.getString("source_type"),
                                                ),
                                            )
                                        }
                                    }
                                }
                            add(
                                VenueInfoMediaSectionDto(
                                    section,
                                    rs.getString("title"),
                                    rs.getString("section_type"),
                                    rs.getBoolean("is_visible"),
                                    rs.getLong("media_revision"),
                                    items,
                                ),
                            )
                        }
                    }
                }
            }
        }

    suspend fun operation(
        venue: Long,
        section: Long,
        actor: Long,
        request: String,
    ): VenueInfoMediaOperationDto =
        tx { c ->
            authorize(c, venue, actor)
            lockSection(c, venue, section)
            if (runCatching { UUID.fromString(request).toString() }.getOrNull() != request) {
                throw VenueInfoMediaInvalidException()
            }
            c.prepareStatement(
                """SELECT status FROM venue_info_section_media
                WHERE section_id=? AND created_by_user_id=? AND request_id=?""",
            ).use {
                    s ->
                s.setLong(1, section)
                s.setLong(2, actor)
                s.setString(3, request)
                s.executeQuery().use { r ->
                    val status = if (r.next()) r.getString(1) else null
                    VenueInfoMediaOperationDto(
                        when (status) {
                            "READY", "DELETED" -> "SUCCESS"
                            "FAILED" -> "FAILED"
                            else -> "UNKNOWN"
                        },
                    )
                }
            }
        }

    internal suspend fun reserve(
        venue: Long,
        section: Long,
        actor: Long,
        revision: Long,
        request: String,
        inputHash: String,
        file: ValidatedVenueInfoMedia,
        replace: String?,
    ): MediaReservation =
        tx { c ->
            authorize(c, venue, actor)
            val current = lockSection(c, venue, section)
            val replacementId = replace?.let { mediaId(c, section, it) }
            c.prepareStatement(
                "SELECT * FROM venue_info_section_media WHERE section_id=? AND created_by_user_id=? AND request_id=?",
            ).use {
                    s ->
                s.setLong(1, section)
                s.setLong(2, actor)
                s.setString(3, request)
                s.executeQuery().use { rs ->
                    if (rs.next()) {
                        if (rs.getString("input_sha256") != inputHash ||
                            rs.getLong("expected_revision") != revision ||
                            rs.getString("mime_type") != file.mime ||
                            rs.getObject("replaces_id")?.let { rs.getLong("replaces_id") } != replacementId
                        ) {
                            throw VenueInfoMediaConflictException()
                        }
                        if (rs.getString("status") != "READY") throw VenueInfoMediaConflictException()
                        return@tx MediaReservation(rs.getString("public_id"), rs.getString("object_key"), false)
                    }
                }
            }
            if (current != revision) throw VenueInfoMediaConflictException()
            val oldId = replace?.let { reference -> mediaId(c, section, reference) ?: throw NotFoundException() }
            val old =
                oldId?.let { id ->
                    c.prepareStatement(
                        """
                        SELECT sort_order,is_visible
                        FROM venue_info_section_media
                        WHERE id=?
                        AND section_id=?
                        AND status='READY'
                        """,
                    ).use {
                            s ->
                        s.setLong(1, id)
                        s.setLong(2, section)
                        s.executeQuery().use {
                                rs ->
                            if (!rs.next()) throw NotFoundException() else rs.getInt(1) to rs.getBoolean(2)
                        }
                    }
                }
            val count =
                c.prepareStatement(
                    """
                    SELECT COUNT(*)
                    FROM venue_info_section_media
                    WHERE section_id=?
                    AND status IN ('READY','UPLOADING')
                    """,
                ).use {
                        s ->
                    s.setLong(1, section)
                    s.executeQuery().use {
                        it.next()
                        it.getInt(1)
                    }
                }
            if (old == null && count >= 10) {
                throw VenueInfoMediaInvalidException(
                    "В разделе максимум 10 вложений. Сначала удалите лишние.",
                )
            }
            val order =
                old?.first ?: c.prepareStatement(
                    """SELECT COALESCE(MAX(sort_order),-1)+1 FROM venue_info_section_media
                    WHERE section_id=?""",
                ).use {
                        s ->
                    s.setLong(1, section)
                    s.executeQuery().use {
                        it.next()
                        it.getInt(1)
                    }
                }
            val id = UUID.randomUUID().toString()
            val extension =
                when (file.mime) {
                    "image/jpeg" -> "jpg"
                    "image/png" -> "png"
                    else -> "pdf"
                }
            val key = "venue-info/${UUID.randomUUID()}.$extension"
            c.prepareStatement(
                """INSERT INTO venue_info_section_media
            (section_id,media_type,telegram_file_id,sort_order,public_id,source_type,object_key,status,is_visible,
            mime_type,size_bytes,checksum_sha256,input_sha256,created_by_user_id,request_id,expected_revision,replaces_id)
            VALUES (?,?,'',?,?,'OBJECT_STORAGE',?,'UPLOADING',?,?,?,?,?,?,?,?,?)""",
            ).use { s ->
                s.setLong(1, section)
                s.setString(2, file.type)
                s.setInt(3, order)
                s.setString(4, id)
                s.setString(5, key)
                s.setBoolean(6, old?.second ?: true)
                s.setString(7, file.mime)
                s.setInt(8, file.bytes.size)
                s.setString(9, file.checksum)
                s.setString(10, inputHash)
                s.setLong(11, actor)
                s.setString(12, request)
                s.setLong(13, revision)
                s.setObject(14, oldId)
                s.executeUpdate()
            }
            c.prepareStatement(
                """INSERT INTO venue_info_media_cleanup(object_key,public_id,due_at,actor_id,venue_id,section_id)
                VALUES (?,?,?,?,?,?)""",
            ).use {
                    s ->
                s.setString(1, key)
                s.setString(2, id)
                s.setTimestamp(3, Timestamp.from(clock.instant().plusSeconds(600)))
                s.setLong(4, actor)
                s.setLong(5, venue)
                s.setLong(6, section)
                s.executeUpdate()
            }
            bump(c, section)
            audit(c, actor, venue, section, "UPLOAD_STARTED")
            MediaReservation(id, key, true)
        }

    internal suspend fun finalizeUpload(
        venue: Long,
        section: Long,
        actor: Long,
        id: String,
    ) = tx { c ->
        authorize(c, venue, actor)
        val revision = lockSection(c, venue, section)
        // The same lock order as cleanup prevents a sweep from deleting a newly committed object.
        val row =
            c.prepareStatement(
                "SELECT * FROM venue_info_section_media WHERE public_id=? AND section_id=? FOR UPDATE",
            ).use {
                    s ->
                s.setString(1, id)
                s.setLong(2, section)
                s.executeQuery().use { rs ->
                    if (
                        !rs.next() ||
                        rs.getString("status") != "UPLOADING" ||
                        rs.getLong("expected_revision") + 1 != revision
                    ) {
                        throw VenueInfoMediaConflictException()
                    }
                    rs.getObject("replaces_id")?.let { rs.getLong("replaces_id") }
                }
            }
        if (row != null) tombstone(c, section, row)
        c.prepareStatement("UPDATE venue_info_section_media SET status='READY' WHERE public_id=?").use {
                s ->
            s.setString(1, id)
            s.executeUpdate()
        }
        c.prepareStatement("UPDATE venue_info_media_cleanup SET due_at=NULL WHERE public_id=?").use {
                s ->
            s.setString(1, id)
            s.executeUpdate()
        }
        audit(c, actor, venue, section, if (row == null) "UPLOADED" else "REPLACED")
    }

    internal suspend fun fail(id: String) =
        tx { c ->
            val changed =
                c.prepareStatement(
                    "UPDATE venue_info_section_media SET status='FAILED' WHERE public_id=? AND status='UPLOADING'",
                ).use {
                        s ->
                    s.setString(1, id)
                    s.executeUpdate() > 0
                }
            if (changed) journalAudit(c, id, "UPLOAD_FAILED")
            c.prepareStatement(
                """UPDATE venue_info_media_cleanup SET due_at=? WHERE public_id=?
            AND NOT EXISTS (SELECT 1 FROM venue_info_section_media m
            WHERE m.public_id=venue_info_media_cleanup.public_id AND m.status='READY')""",
            ).use { s ->
                s.setTimestamp(1, Timestamp.from(clock.instant()))
                s.setString(2, id)
                s.executeUpdate()
            }
        }

    suspend fun mutate(
        venue: Long,
        section: Long,
        actor: Long,
        revision: Long,
        reference: String?,
        action: String,
    ) = tx { c ->
        authorize(c, venue, actor)
        val current = lockSection(c, venue, section)
        val desired = action == "show"
        if (action !in setOf("show", "hide", "delete")) throw VenueInfoMediaInvalidException()
        if (reference == null) {
            if (action == "delete") throw VenueInfoMediaInvalidException()
            val visible =
                c.prepareStatement("SELECT is_visible FROM venue_info_sections WHERE id=?").use { st ->
                    st.setLong(1, section)
                    st.executeQuery().use {
                        it.next()
                        it.getBoolean(1)
                    }
                }
            if (visible == desired) return@tx
            if (current != revision) throw VenueInfoMediaConflictException()
            c.prepareStatement("UPDATE venue_info_sections SET is_visible=? WHERE id=?").use {
                    s ->
                s.setBoolean(1, desired)
                s.setLong(2, section)
                s.executeUpdate()
            }
        } else {
            val id = mediaId(c, section, reference) ?: throw NotFoundException()
            val state =
                c.prepareStatement("SELECT status,is_visible FROM venue_info_section_media WHERE id=?").use { s ->
                    s.setLong(1, id)
                    s.executeQuery().use {
                        it.next()
                        it.getString(1) to it.getBoolean(2)
                    }
                }
            if (action == "delete" && state.first == "DELETED") return@tx
            if (action != "delete" && state.first == "READY" && state.second == desired) return@tx
            if (current != revision || state.first != "READY") throw VenueInfoMediaConflictException()
            if (action == "delete") {
                tombstone(c, section, id)
            } else {
                c.prepareStatement("UPDATE venue_info_section_media SET is_visible=? WHERE id=?").use {
                        s ->
                    s.setBoolean(1, desired)
                    s.setLong(2, id)
                    s.executeUpdate()
                }
            }
        }
        bump(c, section)
        audit(c, actor, venue, section, action.uppercase())
    }

    internal suspend fun cleanupBatch(limit: Int = 10): List<Pair<String, String>> =
        tx { c ->
            val now = Timestamp.from(clock.instant())
            val candidates =
                c.prepareStatement(
                    """
                    SELECT object_key,public_id
                    FROM venue_info_media_cleanup
                    WHERE due_at<=? OR (due_at IS NULL
                    AND NOT EXISTS (SELECT 1
                    FROM venue_info_section_media m
                    WHERE m.public_id=venue_info_media_cleanup.public_id))
                    ORDER BY due_at NULLS FIRST LIMIT ?
                    """,
                ).use {
                        s ->
                    s.setTimestamp(1, now)
                    s.setInt(2, limit)
                    s.executeQuery().use {
                            rs ->
                        buildList { while (rs.next()) add(rs.getString(1) to rs.getString(2)) }
                    }
                }
            candidates.filter { (key, id) ->
                val state =
                    c.prepareStatement("SELECT status FROM venue_info_section_media WHERE public_id=? FOR UPDATE").use {
                            s ->
                        s.setString(1, id)
                        s.executeQuery().use { if (it.next()) it.getString(1) else null }
                    }
                if (state == "READY") {
                    c.prepareStatement("UPDATE venue_info_media_cleanup SET due_at=NULL WHERE object_key=?").use {
                        it.setString(1, key)
                        it.executeUpdate()
                    }
                    false
                } else {
                    c.prepareStatement(
                        "UPDATE venue_info_section_media SET status='FAILED' WHERE public_id=? AND status='UPLOADING'",
                    ).use {
                            s ->
                        s.setString(1, id)
                        s.executeUpdate()
                    }
                    c.prepareStatement(
                        """
                        UPDATE venue_info_media_cleanup SET due_at=?
                        WHERE object_key=?
                        AND (due_at<=? OR due_at IS NULL)
                        """,
                    ).use {
                            s ->
                        s.setTimestamp(1, Timestamp.from(clock.instant().plusSeconds(60)))
                        s.setString(2, key)
                        s.setTimestamp(3, now)
                        s.executeUpdate() == 1
                    }
                }
            }
        }

    internal suspend fun completeCleanup(key: String) =
        tx { c ->
            val id =
                c.prepareStatement(
                    """SELECT public_id FROM venue_info_media_cleanup
                    WHERE object_key=? AND deleted_at IS NULL""",
                ).use {
                        s ->
                    s.setString(1, key)
                    s.executeQuery().use { if (it.next()) it.getString(1) else null }
                }
            c.prepareStatement("UPDATE venue_info_media_cleanup SET deleted_at=?,due_at=? WHERE object_key=?").use {
                    s ->
                s.setTimestamp(1, Timestamp.from(clock.instant()))
                s.setTimestamp(2, Timestamp.from(clock.instant().plusSeconds(86400)))
                s.setString(3, key)
                s.executeUpdate()
            }
            if (id != null) journalAudit(c, id, "CLEANUP_CONFIRMED")
        }

    internal suspend fun cleanupFailed(id: String) = tx { c -> journalAudit(c, id, "CLEANUP_DEFERRED") }

    private fun journalAudit(
        c: Connection,
        id: String,
        action: String,
    ) {
        c.prepareStatement("SELECT actor_id,venue_id,section_id FROM venue_info_media_cleanup WHERE public_id=?").use {
                s ->
            s.setString(1, id)
            s.executeQuery().use { r ->
                if (r.next()) audit(c, r.getLong(1), r.getLong(2), r.getLong(3), action)
            }
        }
    }

    private fun authorize(
        c: Connection,
        venue: Long,
        actor: Long,
    ) {
        val status =
            c.prepareStatement("SELECT status FROM venues WHERE id=? FOR UPDATE").use {
                    s ->
                s.setLong(1, venue)
                s.executeQuery().use { if (it.next()) it.getString(1) else null }
            }
        if (status !in setOf("DRAFT", "PUBLISHED", "HIDDEN", "PAUSED", "SUSPENDED")) throw ForbiddenException()
        val allowed =
            c.prepareStatement("SELECT role FROM venue_members WHERE venue_id=? AND user_id=? FOR UPDATE").use { s ->
                s.setLong(1, venue)
                s.setLong(2, actor)
                s.executeQuery().use { it.next() && it.getString(1) in setOf("OWNER", "MANAGER", "ADMIN") }
            }
        if (!allowed) throw ForbiddenException()
    }

    private fun lockSection(
        c: Connection,
        venue: Long,
        section: Long,
    ): Long =
        c.prepareStatement("SELECT media_revision FROM venue_info_sections WHERE id=? AND venue_id=? FOR UPDATE").use {
                s ->
            s.setLong(1, section)
            s.setLong(2, venue)
            s.executeQuery().use { if (!it.next()) throw NotFoundException() else it.getLong(1) }
        }

    private fun mediaId(
        c: Connection,
        section: Long,
        ref: String,
    ): Long? =
        c.prepareStatement(
            """
            SELECT id
            FROM venue_info_section_media
            WHERE section_id=?
            AND ${if (ref.toLongOrNull() != null) "id" else "public_id"}=?
            """,
        ).use {
                s ->
            s.setLong(1, section)
            if (ref.toLongOrNull() != null) s.setLong(2, ref.toLong()) else s.setString(2, ref)
            s.executeQuery().use { if (it.next()) it.getLong(1) else null }
        }

    private fun tombstone(
        c: Connection,
        section: Long,
        id: Long,
    ) {
        c.prepareStatement(
            """
            UPDATE venue_info_section_media SET status='DELETED',deleted_at=?
            WHERE section_id=?
            AND id=?
            AND status='READY'
            """,
        ).use {
                s ->
            s.setTimestamp(1, Timestamp.from(clock.instant()))
            s.setLong(2, section)
            s.setLong(3, id)
            if (s.executeUpdate() != 1) throw VenueInfoMediaConflictException()
        }
        c.prepareStatement(
            """
            UPDATE venue_info_media_cleanup SET due_at=?
            WHERE public_id IN (SELECT public_id
            FROM venue_info_section_media
            WHERE id=?)
            """,
        ).use {
                s ->
            s.setTimestamp(1, Timestamp.from(clock.instant().plusSeconds(7 * 86400)))
            s.setLong(2, id)
            s.executeUpdate()
        }
    }

    private fun bump(
        c: Connection,
        section: Long,
    ) {
        c.prepareStatement("UPDATE venue_info_sections SET media_revision=media_revision+1 WHERE id=?").use { s ->
            s.setLong(1, section)
            s.executeUpdate()
        }
    }

    private fun audit(
        c: Connection,
        actor: Long,
        venue: Long,
        section: Long,
        action: String,
    ) = audit.append(
        c,
        actor,
        "VENUE_INFO_MEDIA_$action",
        "venue_info_section",
        section,
        "{\"venueId\":$venue,\"sectionId\":$section}",
    )

    private suspend fun <T> tx(block: (Connection) -> T): T =
        withContext(Dispatchers.IO) {
            try {
                (dataSource ?: throw DatabaseUnavailableException()).connection.use { c ->
                    c.autoCommit = false
                    try {
                        block(c).also { c.commit() }
                    } catch (e: Exception) {
                        c.rollback()
                        throw e
                    }
                }
            } catch (_: SQLException) {
                throw DatabaseUnavailableException()
            }
        }
}
