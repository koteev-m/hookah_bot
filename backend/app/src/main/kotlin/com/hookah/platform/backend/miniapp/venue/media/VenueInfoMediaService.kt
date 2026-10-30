package com.hookah.platform.backend.miniapp.venue.media

import com.hookah.platform.backend.api.ConfigException
import com.hookah.platform.backend.api.NotFoundException
import com.hookah.platform.backend.api.TooManyRequestsException
import com.hookah.platform.backend.api.VenueInfoMediaInvalidException
import com.hookah.platform.backend.api.VenueInfoMediaUnavailableException
import com.hookah.platform.backend.miniapp.security.MiniAppAbuseProtection
import com.hookah.platform.backend.miniapp.security.MiniAppRateLimitDecision
import com.hookah.platform.backend.telegram.TelegramDownloadedFile
import com.hookah.platform.backend.telegram.db.VenueInfoSectionMediaAttachment
import com.hookah.platform.backend.telegram.db.VenueInfoSectionsRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID

class VenueInfoMediaService(
    private val repository: VenueInfoMediaRepository,
    private val sections: VenueInfoSectionsRepository,
    private val storage: VenueInfoMediaStorage,
    private val pdfValidator: (
        ByteArray,
    ) -> Unit = com.hookah.platform.backend.miniapp.venue.media.pdf.PdfWorkerClient()::validate,
) {
    private val uploads = Semaphore(2)
    private val pdfUploads = Semaphore(1)
    private val reads = Semaphore(16)
    private val readQuota = MiniAppAbuseProtection()

    suspend fun state(
        venue: Long,
        actor: Long,
    ): VenueInfoMediaStateDto {
        repository.authorize(venue, actor)
        sections.ensureDefaultSections(venue, actor)
        return VenueInfoMediaStateDto(storage.available, repository.list(venue, actor))
    }

    suspend fun upload(
        venue: Long,
        section: Long,
        actor: Long,
        revision: Long,
        request: String,
        mime: String?,
        replace: String?,
        receive: suspend () -> ByteArray,
    ): VenueInfoMediaStateDto {
        repository.authorize(venue, actor)
        if (!storage.available) throw VenueInfoMediaUnavailableException()
        if (runCatching {
                UUID.fromString(request).toString()
            }.getOrNull() != request || revision < 0
        ) {
            throw VenueInfoMediaInvalidException()
        }
        if (!uploads.tryAcquire()) throw TooManyRequestsException()
        var pdfSlot = false
        try {
            val input = withTimeoutOrNull(30000) { receive() } ?: throw VenueInfoMediaUnavailableException()
            if (input.size >= 5 && input.copyOfRange(0, 5).contentEquals("%PDF-".toByteArray())) {
                if (!pdfUploads.tryAcquire()) throw TooManyRequestsException()
                pdfSlot = true
                uploads.release()
            }
            val file = withContext(Dispatchers.IO) { VenueInfoMediaValidation.validate(input, mime, pdfValidator) }
            val reservation =
                repository.reserve(
                    venue,
                    section,
                    actor,
                    revision,
                    request,
                    mediaDigest(input),
                    file,
                    replace,
                )
            if (reservation.fresh) {
                try {
                    storage.put(reservation.key, file)
                    repository.finalizeUpload(venue, section, actor, reservation.id)
                } catch (
                    e: Exception,
                ) {
                    withContext(NonCancellable) { runCatching { repository.fail(reservation.id) } }
                    throw e
                }
            }
            return state(venue, actor)
        } finally {
            if (pdfSlot) pdfUploads.release() else uploads.release()
        }
    }

    suspend fun mutate(
        venue: Long,
        section: Long,
        actor: Long,
        revision: Long,
        reference: String?,
        action: String,
    ): VenueInfoMediaStateDto {
        repository.mutate(venue, section, actor, revision, reference, action)
        return state(venue, actor)
    }

    suspend fun bytes(
        media: VenueInfoSectionMediaAttachment,
        telegram: (suspend (String) -> TelegramDownloadedFile?)?,
    ): TelegramDownloadedFile? {
        if (readQuota.tryInfoMediaPublicRead() is MiniAppRateLimitDecision.Denied) {
            throw TooManyRequestsException()
        }
        if (media.sourceType == "TELEGRAM_FILE") {
            val downloader = telegram ?: throw ConfigException("Media proxy is not configured")
            return downloader(media.telegramFileId)
        }
        if (!reads.tryAcquire()) throw TooManyRequestsException()
        try {
            val bytes = storage.get(media.objectKey ?: throw NotFoundException()) ?: throw NotFoundException()
            if (
                bytes.size != media.sizeBytes ||
                mediaDigest(bytes) != media.checksum
            ) {
                throw VenueInfoMediaUnavailableException()
            }
            return TelegramDownloadedFile(
                bytes,
                io.ktor.http.ContentType.parse(media.mimeType ?: throw NotFoundException()),
            )
        } finally {
            reads.release()
        }
    }

    suspend fun cleanup() {
        if (!storage.available) return
        repository.cleanupBatch().forEach { (key, id) ->
            try {
                storage.delete(key)
                if (!storage.exists(key)) repository.completeCleanup(key) else repository.cleanupFailed(id)
            } catch (
                e: CancellationException,
            ) {
                throw e
            } catch (_: Exception) {
                runCatching { repository.cleanupFailed(id) }
            }
        }
    }
}
