package com.hookah.platform.backend.miniapp.venue.staff

import com.hookah.platform.backend.api.ForbiddenException
import com.hookah.platform.backend.api.NotFoundException
import com.hookah.platform.backend.api.StaffPhotoConflictException
import com.hookah.platform.backend.api.StaffPhotoUnavailableException
import com.hookah.platform.backend.api.TooManyRequestsException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import java.util.UUID

@Serializable
data class StaffPhotoDto(
    val id: String,
    val state: String,
    val consent: String,
    val consentAt: String?,
    val previewUrl: String,
    val width: Int,
    val height: Int,
)

@Serializable
data class StaffPhotoStateDto(
    val enabled: Boolean,
    val revision: Long,
    val current: StaffPhotoDto?,
    val candidate: StaffPhotoDto?,
    val canManage: Boolean,
    val canConsent: Boolean,
    val isSelf: Boolean,
    val displayOnly: Boolean,
    val guestVisible: Boolean,
    val cleanupPending: Boolean,
    val uploading: Boolean,
    val consentPolicy: String = STAFF_PHOTO_DIRECT_POLICY,
)

@Serializable
data class OwnStaffPhotoDto(val profileId: Long, val displayName: String, val photo: StaffPhotoStateDto)

class StaffPhotoService(
    private val repository: StaffPhotoRepository,
    private val storage: StaffPhotoStorage,
    private val guestProfileIds: suspend (Long) -> Set<Long>,
    val maxInputBytes: Int = STAFF_PHOTO_MAX_INPUT_BYTES,
    private val uploadReadTimeoutMillis: Long = 30_000,
) {
    private val uploads = Semaphore(2)
    private val reads = Semaphore(16)

    suspend fun state(
        venueId: Long,
        profileId: Long,
        actorId: Long,
    ): StaffPhotoStateDto {
        val snapshot = repository.snapshot(venueId, profileId, actorId)
        val public = repository.publicPhoto(venueId, profileId)
        val guestVisible = public != null && storage.available && visible(venueId, profileId)

        fun StaffPhotoRecord.dto() =
            StaffPhotoDto(
                id,
                state,
                consentKind ?: "NONE",
                consentAt?.toString(),
                "/api/venue/$venueId/staff/profiles/$profileId/photos/$id/content",
                width,
                height,
            )
        return StaffPhotoStateDto(
            storage.available, snapshot.revision, snapshot.current?.dto(), snapshot.candidate?.dto(),
            snapshot.canManage, false, snapshot.isSelf,
            snapshot.displayOnly, guestVisible, snapshot.cleanupPending, snapshot.uploading,
        )
    }

    suspend fun own(
        venueId: Long,
        actorId: Long,
    ): List<OwnStaffPhotoDto> =
        repository.ownProfiles(venueId, actorId).map {
                (id, name) ->
            OwnStaffPhotoDto(id, name, state(venueId, id, actorId))
        }

    suspend fun upload(
        venueId: Long,
        profileId: Long,
        actorId: Long,
        revision: Long,
        requestId: String,
        contentType: String?,
        receive: suspend () -> ByteArray,
    ): StaffPhotoStateDto {
        if (!repository.snapshot(venueId, profileId, actorId).canManage) throw ForbiddenException()
        if (!storage.available) throw StaffPhotoUnavailableException()
        if (runCatching { UUID.fromString(requestId).toString() }.getOrNull() != requestId) {
            throw StaffPhotoConflictException()
        }
        if (!uploads.tryAcquire()) throw TooManyRequestsException()
        try {
            val input =
                withTimeoutOrNull(uploadReadTimeoutMillis) { receive() } ?: throw StaffPhotoUnavailableException()
            val image = withContext(Dispatchers.IO) { StaffPhotoImage.normalize(input, contentType, maxInputBytes) }
            val reservation =
                repository.reserve(
                    venueId,
                    profileId,
                    actorId,
                    revision,
                    requestId,
                    photoSha256(input),
                    image,
                )
            if (reservation.fresh) {
                try {
                    storage.put(reservation.photo.key, image.bytes)
                    repository.finalizeUpload(reservation.photo)
                } catch (e: Exception) {
                    withContext(NonCancellable) { runCatching { repository.failUpload(reservation.photo.id) } }
                    throw e
                }
            }
            return state(venueId, profileId, actorId)
        } finally {
            uploads.release()
        }
    }

    suspend fun mutate(
        venueId: Long,
        profileId: Long,
        actorId: Long,
        photoId: String?,
        revision: Long,
        action: String,
    ): StaffPhotoStateDto {
        repository.mutate(venueId, profileId, actorId, photoId, revision, action)
        return state(venueId, profileId, actorId)
    }

    suspend fun privateBytes(
        venueId: Long,
        profileId: Long,
        actorId: Long,
        photoId: String,
    ): ByteArray {
        fun StaffPhotoSnapshot.selected(): StaffPhotoRecord =
            listOfNotNull(current, candidate).find {
                it.id == photoId
            } ?: throw NotFoundException()
        val snapshot = repository.snapshot(venueId, profileId, actorId)
        val selected = snapshot.selected()
        val bytes = bytes(selected)
        val fresh = repository.snapshot(venueId, profileId, actorId)
        if (fresh.revision != snapshot.revision || fresh.selected().id != selected.id) throw NotFoundException()
        return bytes
    }

    suspend fun guestBytes(
        venueId: Long,
        profileId: Long,
        photoId: String,
    ): ByteArray {
        if (!visible(venueId, profileId)) throw NotFoundException()
        val photo = repository.publicPhoto(venueId, profileId)?.takeIf { it.id == photoId } ?: throw NotFoundException()
        val bytes = bytes(photo)
        if (!visible(venueId, profileId) ||
            repository.publicPhoto(venueId, profileId)?.id != photoId
        ) {
            throw NotFoundException()
        }
        return bytes
    }

    suspend fun cleanup() {
        if (!storage.available) return
        repository.claimCleanup().forEach { photo ->
            try {
                storage.delete(photo.key)
                repository.completeCleanup(photo.id)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // durable retry remains pending
            }
        }
    }

    private suspend fun visible(
        venueId: Long,
        profileId: Long,
    ): Boolean =
        try {
            profileId in guestProfileIds(venueId)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            false
        }

    private suspend fun bytes(photo: StaffPhotoRecord): ByteArray {
        if (!reads.tryAcquire()) throw TooManyRequestsException()
        try {
            val bytes = storage.get(photo.key) ?: throw NotFoundException()
            if (bytes.size != photo.size || photoSha256(bytes) != photo.checksum) throw StaffPhotoUnavailableException()
            return bytes
        } finally {
            reads.release()
        }
    }
}
