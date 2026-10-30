package com.hookah.platform.backend.miniapp.venue.media

import com.hookah.platform.backend.api.VenueInfoMediaUnavailableException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import software.amazon.awssdk.core.sync.RequestBody
import software.amazon.awssdk.core.sync.ResponseTransformer
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.model.S3Exception
import software.amazon.awssdk.services.s3.model.StorageClass

interface VenueInfoMediaStorage : AutoCloseable {
    val available: Boolean

    suspend fun put(
        key: String,
        file: ValidatedVenueInfoMedia,
    )

    suspend fun get(key: String): ByteArray?

    suspend fun delete(key: String)

    suspend fun exists(key: String): Boolean

    override fun close() = Unit
}

object DisabledVenueInfoMediaStorage : VenueInfoMediaStorage {
    override val available = false

    override suspend fun put(
        key: String,
        file: ValidatedVenueInfoMedia,
    ): Unit = throw VenueInfoMediaUnavailableException()

    override suspend fun get(key: String): ByteArray? = throw VenueInfoMediaUnavailableException()

    override suspend fun delete(key: String): Unit = throw VenueInfoMediaUnavailableException()

    override suspend fun exists(key: String): Boolean = throw VenueInfoMediaUnavailableException()
}

class R2VenueInfoMediaStorage(private val client: S3Client, private val bucket: String) : VenueInfoMediaStorage {
    override val available = true

    override suspend fun put(
        key: String,
        file: ValidatedVenueInfoMedia,
    ) = safe(key) {
        require(file.bytes.size <= VENUE_INFO_MEDIA_MAX_BYTES)
        client.putObject({
            it.bucket(
                bucket,
            ).key(key).contentType(file.mime).cacheControl("no-store").storageClass(StorageClass.STANDARD)
        }, RequestBody.fromBytes(file.bytes))
        Unit
    }

    override suspend fun get(key: String): ByteArray? =
        safe(key) {
            try {
                client.getObject(
                    { it.bucket(bucket).key(key) },
                    ResponseTransformer { response, stream ->
                        if (response.contentLength() > VENUE_INFO_MEDIA_MAX_BYTES) {
                            throw VenueInfoMediaUnavailableException()
                        }
                        stream.readNBytes(VENUE_INFO_MEDIA_MAX_BYTES + 1).also {
                            if (it.size > VENUE_INFO_MEDIA_MAX_BYTES) throw VenueInfoMediaUnavailableException()
                        }
                    },
                )
            } catch (e: S3Exception) {
                if (e.statusCode() == 404) null else throw e
            }
        }

    override suspend fun delete(key: String) =
        safe(key) {
            client.deleteObject { it.bucket(bucket).key(key) }
            Unit
        }

    override suspend fun exists(key: String): Boolean =
        safe(key) {
            try {
                client.headObject { it.bucket(bucket).key(key) }
                true
            } catch (
                e: S3Exception,
            ) {
                if (e.statusCode() == 404) false else throw e
            }
        }

    override fun close() = client.close()

    private suspend fun <T> safe(
        key: String,
        action: () -> T,
    ): T =
        withContext(Dispatchers.IO) {
            require(Regex("venue-info/[a-f0-9-]{36}\\.(jpg|png|pdf)").matches(key))
            try {
                action()
            } catch (_: Exception) {
                throw VenueInfoMediaUnavailableException()
            }
        }
}
