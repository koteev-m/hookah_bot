package com.hookah.platform.backend.miniapp.venue.staff

import com.hookah.platform.backend.api.DatabaseUnavailableException
import com.hookah.platform.backend.api.NotFoundException
import com.hookah.platform.backend.api.StaffPhotoActionRetiredException
import com.hookah.platform.backend.api.StaffPhotoConflictException
import com.hookah.platform.backend.miniapp.venue.AuditLogRepository
import com.hookah.platform.backend.miniapp.venue.VenueRole
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.sql.Connection
import java.sql.ResultSet
import java.sql.SQLException
import java.sql.Timestamp
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.UUID
import javax.sql.DataSource

const val STAFF_PHOTO_CONSENT_POLICY = "staff-photo-v1"
const val STAFF_PHOTO_DIRECT_POLICY = "staff-photo-direct-v2"

internal data class StaffPhotoRecord(
    val id: String,
    val venueId: Long,
    val profileId: Long,
    val actorId: Long,
    val inputHash: String,
    val key: String,
    val checksum: String,
    val size: Int,
    val width: Int,
    val height: Int,
    val reservedRevision: Long,
    val linkedUserId: Long?,
    val state: String,
    val consentKind: String?,
    val consentAt: Instant?,
    val consentActorId: Long?,
) {
    override fun toString() = "StaffPhotoRecord(id=$id,state=$state)"
}

internal data class StaffPhotoPointers(val revision: Long, val currentId: String?, val candidateId: String?)

internal data class StaffPhotoSnapshot(
    val revision: Long,
    val current: StaffPhotoRecord?,
    val candidate: StaffPhotoRecord?,
    val canManage: Boolean,
    val isSelf: Boolean,
    val displayOnly: Boolean,
    val owner: Boolean,
    val cleanupPending: Boolean,
    val uploading: Boolean,
)

internal data class StaffPhotoReservation(val photo: StaffPhotoRecord, val fresh: Boolean)

class StaffPhotoRepository(
    private val dataSource: DataSource?,
    private val profiles: VenueStaffProfileRepository,
    private val audit: AuditLogRepository,
    private val clock: Clock = Clock.systemUTC(),
) {
    internal suspend fun snapshot(
        venueId: Long,
        profileId: Long,
        actorId: Long,
    ): StaffPhotoSnapshot =
        profiles.photoTransaction(venueId, profileId, actorId, false) { c, p, a, role ->
            ensurePointers(c, venueId, profileId)
            val pointers = pointers(c, profileId)
            val photos = records(c, venueId, profileId)
            StaffPhotoSnapshot(
                pointers.revision,
                photos.find { it.id == pointers.currentId },
                photos.find { it.id == pointers.candidateId },
                role == VenueRole.OWNER || role == VenueRole.MANAGER,
                a.isSelf, p.linkedUserId == null, role == VenueRole.OWNER,
                photos.any { it.state == "DELETE_PENDING" }, photos.any { it.state == "UPLOADING" },
            )
        }

    internal suspend fun reserve(
        venueId: Long,
        profileId: Long,
        actorId: Long,
        expectedRevision: Long,
        requestId: String,
        inputHash: String,
        image: SanitizedStaffPhoto,
    ): StaffPhotoReservation =
        profiles.photoTransaction(venueId, profileId, actorId, true, true) { c, p, _, _ ->
            val previous =
                c.prepareStatement(
                    """
                    SELECT * FROM staff_profile_photos WHERE venue_id=? AND profile_id=? AND actor_id=? AND
                    request_id=?
                    """.trimIndent(),
                ).use {
                        s ->
                    s.setLong(1, venueId)
                    s.setLong(2, profileId)
                    s.setLong(3, actorId)
                    s.setString(4, requestId)
                    s.executeQuery().use { if (it.next()) it.photo() else null }
                }
            val pointers = pointers(c, profileId)
            if (previous != null) {
                if (previous.inputHash != inputHash || previous.reservedRevision != expectedRevision + 1 ||
                    previous.state !in setOf("PENDING", "APPROVED", "HIDDEN") ||
                    previous.id !in listOf(pointers.currentId, pointers.candidateId)
                ) {
                    throw StaffPhotoConflictException()
                }
                return@photoTransaction StaffPhotoReservation(previous, false)
            }
            if (pointers.revision != expectedRevision) throw StaffPhotoConflictException()
            if (records(c, venueId, profileId).any { it.state == "UPLOADING" }) throw StaffPhotoConflictException()
            ensurePointers(c, venueId, profileId)
            val id = UUID.randomUUID().toString()
            val now = clock.instant()
            c.prepareStatement(
                """
                INSERT INTO staff_profile_photos
                (id,venue_id,profile_id,actor_id,request_id,input_hash,object_key,checksum,byte_count,width,height,
                 reserved_revision,linked_user_id,state,created_at,updated_at,cleanup_after)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,'UPLOADING',?,?,?)
                """.trimIndent(),
            ).use { s ->
                s.setString(1, id)
                s.setLong(2, venueId)
                s.setLong(3, profileId)
                s.setLong(4, actorId)
                s.setString(5, requestId)
                s.setString(6, inputHash)
                s.setString(7, "staff-photos/$id.jpg")
                s.setString(8, image.checksum)
                s.setInt(9, image.bytes.size)
                s.setInt(10, image.width)
                s.setInt(11, image.height)
                s.setLong(12, expectedRevision + 1)
                s.setObject(13, p.linkedUserId)
                s.setTimestamp(14, Timestamp.from(now))
                s.setTimestamp(15, Timestamp.from(now))
                s.setTimestamp(16, Timestamp.from(now.plus(Duration.ofMinutes(10))))
                s.executeUpdate()
            }
            setPointers(c, profileId, pointers.copy(revision = expectedRevision + 1))
            appendAudit(c, actorId, venueId, profileId, id, "STAFF_PHOTO_UPLOAD_RESERVED")
            StaffPhotoReservation(checkNotNull(record(c, id)), true)
        }

    internal suspend fun finalizeUpload(photo: StaffPhotoRecord) {
        profiles.photoTransaction(photo.venueId, photo.profileId, photo.actorId, true, true) { c, p, _, _ ->
            val pointers = pointers(c, p.id)
            val fresh = record(c, photo.id) ?: throw StaffPhotoConflictException()
            if (fresh.state != "UPLOADING" ||
                pointers.revision != photo.reservedRevision || p.linkedUserId != photo.linkedUserId
            ) {
                throw StaffPhotoConflictException()
            }
            pointers.candidateId?.let { deleteRecord(c, it) }
            pointers.currentId?.let { deleteRecord(c, it) }
            setState(c, photo.id, "APPROVED")
            setPointers(c, p.id, StaffPhotoPointers(pointers.revision + 1, photo.id, null))
            appendAudit(c, photo.actorId, photo.venueId, p.id, photo.id, "STAFF_PHOTO_UPLOADED")
        }
    }

    internal suspend fun failUpload(id: String) =
        transaction { c ->
            c.prepareStatement(
                """
                UPDATE staff_profile_photos SET state='DELETE_PENDING',  cleanup_after=?,  updated_at=?
                WHERE id=? AND state IN ('UPLOADING', 'DELETED', 'DELETE_PENDING')
                """.trimIndent(),
            ).use {
                    s ->
                s.setTimestamp(1, Timestamp.from(clock.instant()))
                s.setTimestamp(2, Timestamp.from(clock.instant()))
                s.setString(3, id)
                s.executeUpdate()
            }
            Unit
        }

    internal suspend fun mutate(
        venueId: Long,
        profileId: Long,
        actorId: Long,
        photoId: String?,
        expectedRevision: Long,
        action: String,
    ) {
        profiles.photoTransaction(venueId, profileId, actorId, true) { c, p, _, _ ->
            // Keep legacy routes explicit, but never record consent or activate old candidates.
            if (action in setOf("consent", "publish", "revoke")) throw StaffPhotoActionRetiredException()
            val pointers = pointers(c, profileId)
            if (pointers.revision != expectedRevision) throw StaffPhotoConflictException()
            ensurePointers(c, venueId, profileId)
            if (action == "delete") {
                invalidateStaffPhotoIdentity(c, venueId, profileId)
                appendAudit(c, actorId, venueId, profileId, null, "STAFF_PHOTO_DELETED")
                return@photoTransaction
            }
            val photo = photoId?.let { record(c, it) } ?: throw NotFoundException()
            if (photo.venueId != venueId ||
                photo.profileId != profileId ||
                photoId !in listOf(pointers.currentId, pointers.candidateId) ||
                photo.linkedUserId != p.linkedUserId
            ) {
                throw NotFoundException()
            }
            if (photo.state !in setOf("PENDING", "APPROVED", "HIDDEN")) throw StaffPhotoConflictException()
            val next = pointers.copy(revision = pointers.revision + 1)
            when (action) {
                "hide" -> setState(c, photoId, "HIDDEN")
                else -> throw StaffPhotoConflictException()
            }
            setPointers(c, profileId, next)
            appendAudit(c, actorId, venueId, profileId, photoId, "STAFF_PHOTO_${action.uppercase()}")
        }
    }

    internal suspend fun publicPhoto(
        venueId: Long,
        profileId: Long,
    ): StaffPhotoRecord? =
        read { c ->
            c.prepareStatement(
                """
                SELECT f.*, p.linked_user_id AS current_link FROM staff_profile_photo_state s
                JOIN staff_profile_photos f ON f.id=s.current_id AND f.profile_id=s.profile_id AND f.venue_id=s.venue_id
                JOIN staff_profiles p ON p.id=s.profile_id AND p.venue_id=s.venue_id
                WHERE s.venue_id=? AND s.profile_id=? AND f.state='APPROVED'
                  AND p.is_guest_visible=TRUE AND p.published_at IS NOT NULL AND p.disabled_at IS NULL
                """.trimIndent(),
            ).use { s ->
                s.setLong(1, venueId)
                s.setLong(2, profileId)
                s.executeQuery().use { rs ->
                    if (!rs.next()) {
                        null
                    } else {
                        rs.photo().takeIf {
                            it.linkedUserId == rs.getObject("current_link", java.lang.Long::class.java)?.toLong()
                        }
                    }
                }
            }
        }

    internal suspend fun ownProfiles(
        venueId: Long,
        actorId: Long,
    ): List<Pair<Long, String>> =
        read { c ->
            c.prepareStatement(
                """
                SELECT p.id, p.display_name FROM staff_profiles p JOIN venue_members m ON
                m.venue_id=p.venue_id AND m.user_id=p.linked_user_id WHERE p.venue_id=? AND
                p.linked_user_id=? ORDER BY p.id
                """.trimIndent(),
            ).use {
                    s ->
                s.setLong(1, venueId)
                s.setLong(2, actorId)
                s.executeQuery().use {
                        rs ->
                    buildList { while (rs.next()) add(rs.getLong(1) to rs.getString(2)) }
                }
            }
        }

    internal suspend fun claimCleanup(limit: Int = 10): List<StaffPhotoRecord> =
        transaction { c ->
            val now = clock.instant()
            c.prepareStatement(
                """
                                SELECT f.* FROM staff_profile_photos f
                                WHERE (f.state IN ('UPLOADING','DELETE_PENDING','DELETED') AND f.cleanup_after<=?)
                                   OR (f.state NOT IN ('DELETE_PENDING','DELETED') AND NOT EXISTS (
                SELECT 1 FROM staff_profiles p WHERE p.venue_id=f.venue_id AND p.id=f.profile_id))
                                ORDER BY f.cleanup_after,f.id LIMIT ? FOR UPDATE
                """.trimIndent(),
            ).use { s ->
                s.setTimestamp(1, Timestamp.from(now))
                s.setInt(2, limit)
                s.executeQuery().use { rs -> buildList { while (rs.next()) add(rs.photo()) } }
            }.onEach { photo ->
                c.prepareStatement(
                    """
                    UPDATE staff_profile_photos SET state='DELETE_PENDING', cleanup_after=?,
                    cleanup_attempts=cleanup_attempts+1, updated_at=? WHERE id=?
                    """.trimIndent(),
                ).use {
                        s ->
                    s.setTimestamp(1, Timestamp.from(now.plusSeconds(60)))
                    s.setTimestamp(2, Timestamp.from(now))
                    s.setString(3, photo.id)
                    s.executeUpdate()
                }
            }
        }

    internal suspend fun completeCleanup(id: String) =
        transaction { c ->
            c.prepareStatement(
                """
                UPDATE staff_profile_photos SET state='DELETED', deleted_at=?, cleanup_after=?,
                updated_at=? WHERE id=? AND state='DELETE_PENDING'
                """.trimIndent(),
            ).use {
                    s ->
                val now = clock.instant()
                s.setTimestamp(1, Timestamp.from(now))
                s.setTimestamp(2, Timestamp.from(now.plus(Duration.ofDays(1))))
                s.setTimestamp(3, Timestamp.from(now))
                s.setString(4, id)
                s.executeUpdate()
            }
            Unit
        }

    private fun pointers(
        c: Connection,
        profileId: Long,
    ): StaffPhotoPointers =
        c.prepareStatement(
            """
            SELECT revision, current_id, candidate_id FROM staff_profile_photo_state WHERE
            profile_id=?
            """.trimIndent(),
        ).use {
                s ->
            s.setLong(1, profileId)
            s.executeQuery().use {
                if (it.next()) {
                    StaffPhotoPointers(
                        it.getLong(1),
                        it.getString(2),
                        it.getString(3),
                    )
                } else {
                    StaffPhotoPointers(0, null, null)
                }
            }
        }

    private fun ensurePointers(
        c: Connection,
        venueId: Long,
        profileId: Long,
    ) {
        c.prepareStatement(
            """
            INSERT INTO staff_profile_photo_state (profile_id, venue_id) SELECT ?, ? WHERE NOT EXISTS
            (SELECT 1 FROM staff_profile_photo_state WHERE profile_id=?)
            """.trimIndent(),
        ).use {
                s ->
            s.setLong(1, profileId)
            s.setLong(2, venueId)
            s.setLong(3, profileId)
            s.executeUpdate()
        }
    }

    private fun setPointers(
        c: Connection,
        profileId: Long,
        value: StaffPhotoPointers,
    ) {
        c.prepareStatement(
            """
            UPDATE staff_profile_photo_state SET revision=?, current_id=?, candidate_id=? WHERE
            profile_id=?
            """.trimIndent(),
        ).use {
                s ->
            s.setLong(1, value.revision)
            s.setString(2, value.currentId)
            s.setString(3, value.candidateId)
            s.setLong(4, profileId)
            check(s.executeUpdate() == 1)
        }
    }

    private fun records(
        c: Connection,
        venueId: Long,
        profileId: Long,
    ): List<StaffPhotoRecord> =
        c.prepareStatement(
            """
            SELECT * FROM staff_profile_photos WHERE venue_id=? AND profile_id=? AND state<>'DELETED'
            """.trimIndent(),
        ).use {
                s ->
            s.setLong(1, venueId)
            s.setLong(2, profileId)
            s.executeQuery().use {
                    rs ->
                buildList { while (rs.next()) add(rs.photo()) }
            }
        }

    private fun record(
        c: Connection,
        id: String,
    ): StaffPhotoRecord? =
        c.prepareStatement("SELECT * FROM staff_profile_photos WHERE id=? FOR UPDATE").use { s ->
            s.setString(1, id)
            s.executeQuery().use { if (it.next()) it.photo() else null }
        }

    private fun setState(
        c: Connection,
        id: String,
        state: String,
    ) {
        c.prepareStatement("UPDATE staff_profile_photos SET state=?,updated_at=? WHERE id=?").use { s ->
            s.setString(1, state)
            s.setTimestamp(2, Timestamp.from(clock.instant()))
            s.setString(3, id)
            s.executeUpdate()
        }
    }

    private fun deleteRecord(
        c: Connection,
        id: String,
    ) {
        c.prepareStatement(
            """
            UPDATE staff_profile_photos SET state='DELETE_PENDING', consent_kind=NULL,
            consent_at=NULL, consent_actor_id=NULL, cleanup_after=?, updated_at=? WHERE id=?
            """.trimIndent(),
        ).use {
                s ->
            s.setTimestamp(1, Timestamp.from(clock.instant()))
            s.setTimestamp(2, Timestamp.from(clock.instant()))
            s.setString(3, id)
            s.executeUpdate()
        }
    }

    private fun appendAudit(
        c: Connection,
        actorId: Long,
        venueId: Long,
        profileId: Long,
        photoId: String?,
        action: String,
    ) {
        audit.appendJson(
            c,
            actorId,
            action,
            "staff_profile",
            profileId,
            buildJsonObject {
                put("venueId", venueId)
                put("photoId", photoId)
                put("policy", STAFF_PHOTO_CONSENT_POLICY)
            },
        )
    }

    private suspend fun <T> read(block: (Connection) -> T): T =
        withContext(Dispatchers.IO) {
            try {
                (dataSource ?: throw DatabaseUnavailableException()).connection.use(block)
            } catch (
                _: SQLException,
            ) {
                throw DatabaseUnavailableException()
            }
        }

    private suspend fun <T> transaction(block: (Connection) -> T): T =
        read { c ->
            c.autoCommit = false
            try {
                block(c).also { c.commit() }
            } catch (e: Exception) {
                runCatching { c.rollback() }
                throw e
            }
        }
}

private fun ResultSet.photo() =
    StaffPhotoRecord(
        getString("id"), getLong("venue_id"), getLong("profile_id"), getLong("actor_id"), getString("input_hash"),
        getString("object_key"), getString("checksum"), getInt("byte_count"), getInt("width"), getInt("height"),
        getLong("reserved_revision"), getObject("linked_user_id", java.lang.Long::class.java)?.toLong(),
        getString("state"), getString("consent_kind"), getTimestamp("consent_at")?.toInstant(),
        getObject("consent_actor_id", java.lang.Long::class.java)?.toLong(),
    )

internal fun invalidateStaffPhotoIdentity(
    c: Connection,
    venueId: Long,
    profileId: Long,
) {
    c.prepareStatement(
        """
        UPDATE staff_profile_photo_state SET revision=revision+1, current_id=NULL,
        candidate_id=NULL WHERE venue_id=? AND profile_id=?
        """.trimIndent(),
    ).use {
            s ->
        s.setLong(1, venueId)
        s.setLong(2, profileId)
        s.executeUpdate()
    }
    c.prepareStatement(
        """
        UPDATE staff_profile_photos SET state='DELETE_PENDING', consent_kind=NULL,
        consent_at=NULL, consent_actor_id=NULL, cleanup_after=CURRENT_TIMESTAMP,
        updated_at=CURRENT_TIMESTAMP WHERE venue_id=? AND profile_id=? AND state<>'DELETED'
        """.trimIndent(),
    ).use {
            s ->
        s.setLong(1, venueId)
        s.setLong(2, profileId)
        s.executeUpdate()
    }
}

internal fun invalidateStaffPhotosForMember(
    c: Connection,
    venueId: Long,
    userId: Long,
) {
    val ids =
        c.prepareStatement(
            """
            SELECT id FROM staff_profiles WHERE venue_id=? AND linked_user_id=? ORDER BY id FOR UPDATE
            """.trimIndent(),
        ).use {
                s ->
            s.setLong(1, venueId)
            s.setLong(2, userId)
            s.executeQuery().use {
                    rs ->
                buildList { while (rs.next()) add(rs.getLong(1)) }
            }
        }
    ids.forEach { invalidateStaffPhotoIdentity(c, venueId, it) }
}
