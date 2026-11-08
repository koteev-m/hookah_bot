package com.hookah.platform.backend.miniapp.venue.seating

import com.hookah.platform.backend.api.NotFoundException
import com.hookah.platform.backend.api.TooManyRequestsException
import com.hookah.platform.backend.api.VenueInfoMediaInvalidException
import com.hookah.platform.backend.api.VenueInfoMediaUnavailableException
import com.hookah.platform.backend.miniapp.venue.media.VenueInfoMediaStorage
import com.hookah.platform.backend.miniapp.venue.media.VenueInfoMediaValidation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.ByteArrayInputStream
import java.util.UUID
import javax.imageio.ImageIO

class HallPlanService(private val repository: SeatingRepository, private val storage: VenueInfoMediaStorage) {
    val available get() = storage.available
    private val uploads = Semaphore(2)
    private val reads = Semaphore(16)

    suspend fun upload(
        venue: Long,
        hall: Long,
        actor: Long,
        request: String,
        mime: String?,
        receive: suspend () -> ByteArray,
    ): HallPlanUpload {
        repository.transaction { c -> authorizeSeating(c, venue, actor, true) }
        if (!available) throw VenueInfoMediaUnavailableException()
        if (runCatching { UUID.fromString(request).toString() }.getOrNull() != request) invalid("Нужен ключ загрузки.")
        if (!uploads.tryAcquire()) throw TooManyRequestsException()
        try {
            val input = withTimeout(30000) { receive() }
            if (input.take(
                    5,
                ).toByteArray().contentEquals(
                    "%PDF-".toByteArray(),
                )
            ) {
                throw VenueInfoMediaInvalidException("Для схемы выберите JPEG или PNG.")
            }
            val file = withContext(Dispatchers.IO) { VenueInfoMediaValidation.validate(input, mime) }
            val dimensions =
                withContext(Dispatchers.IO) {
                    ImageIO.read(ByteArrayInputStream(file.bytes)).let {
                        it.width to
                            it.height.also {
                                    _ ->
                                it.flush()
                            }
                    }
                }
            val id =
                repository.transaction { c ->
                    lockSeatingRules(c, venue)
                    authorizeSeating(c, venue, actor, true)
                    if (repository.halls(c, venue).none { it.id == hall }) throw NotFoundException()
                    val old =
                        c.rows(
                            """
                            SELECT id, input_sha256, hall_id FROM hall_plan_media WHERE venue_id = ? AND actor_user_id = ? AND
                            request_id = ?
                            """.trimIndent(),
                            venue,
                            actor,
                            request,
                        ) {
                            Triple(it.getString(1), it.getString(2), it.getLong(3))
                        }.singleOrNull()
                    if (old != null) {
                        if (old.second != seatingDigest(input) || old.third != hall) {
                            conflict(
                                "Ключ загрузки относится к другому файлу или залу.",
                            )
                        }
                        old.first
                    } else {
                        val id = UUID.randomUUID().toString()
                        c.write(
                            """
                            INSERT INTO hall_plan_media(id, venue_id, hall_id, actor_user_id, request_id, object_key,
                            checksum_sha256, input_sha256, mime_type, size_bytes, width, height, status) VALUES (?, ?, ?, ?, ?,
                            ?, ?, ?, ?, ?, ?, ?,'UPLOADING')
                            """.trimIndent(),
                            id, venue, hall, actor, request,
                            "venue-info/$id.${if (file.mime == "image/png") "png" else "jpg"}", file.checksum,
                            seatingDigest(
                                input,
                            ),
                            file.mime, file.bytes.size, dimensions.first, dimensions.second,
                        )
                        id
                    }
                }
            storage.put("venue-info/$id.${if (file.mime == "image/png") "png" else "jpg"}", file)
            repository.transaction { c ->
                lockSeatingRules(c, venue)
                authorizeSeating(c, venue, actor, true)
                c.write("UPDATE hall_plan_media SET status = 'READY' WHERE id = ? AND venue_id = ?", id, venue)
            }
            return HallPlanUpload(id, dimensions.first, dimensions.second)
        } finally {
            uploads.release()
        }
    }

    suspend fun bytes(
        venue: Long,
        hall: Long,
        id: String,
        actor: Long?,
    ): Pair<ByteArray, String> {
        suspend fun permitted() =
            repository.transaction { c ->
                if (actor != null) authorizeSeating(c, venue, actor)
                val rules = readSeatingRules(c, venue)
                val h = repository.halls(c, venue).singleOrNull { it.id == hall } ?: throw NotFoundException()
                if (actor == null && (!rules.usePlan || !rules.guestTableChoice || !h.active || h.planId != id)) {
                    throw NotFoundException()
                }
                c.rows(
                    """
                    SELECT object_key, checksum_sha256, mime_type FROM hall_plan_media WHERE id = ? AND venue_id = ? AND
                    hall_id = ? AND status = 'READY'
                    """.trimIndent(),
                    id, venue, hall,
                ) {
                    Triple(it.getString(1), it.getString(2), it.getString(3))
                }.singleOrNull() ?: throw NotFoundException()
            }
        val record = permitted()
        if (!reads.tryAcquire()) throw TooManyRequestsException()
        try {
            val data = storage.get(record.first) ?: throw NotFoundException()
            if (seatingDigest(data) != record.second) throw VenueInfoMediaUnavailableException()
            if (permitted() != record) throw NotFoundException()
            return data to record.third
        } finally {
            reads.release()
        }
    }
}
