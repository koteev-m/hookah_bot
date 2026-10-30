package com.hookah.platform.backend.miniapp.venue.staff

import com.hookah.platform.backend.api.StaffPhotoUnavailableException
import io.ktor.server.config.ApplicationConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider
import software.amazon.awssdk.core.checksums.RequestChecksumCalculation
import software.amazon.awssdk.core.checksums.ResponseChecksumValidation
import software.amazon.awssdk.core.sync.RequestBody
import software.amazon.awssdk.core.sync.ResponseTransformer
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.S3Configuration
import software.amazon.awssdk.services.s3.model.NoSuchKeyException
import software.amazon.awssdk.services.s3.model.S3Exception
import software.amazon.awssdk.services.s3.model.StorageClass
import java.net.URI
import java.time.Duration

const val STAFF_PHOTO_MAX_INPUT_BYTES = 5 * 1024 * 1024
const val STAFF_PHOTO_MAX_OUTPUT_BYTES = 2 * 1024 * 1024
const val STAFF_PHOTO_STAGING_BUCKET = "hookah-tootah-media-staging-eu"

interface StaffPhotoStorage : AutoCloseable {
    val available: Boolean

    suspend fun put(
        key: String,
        bytes: ByteArray,
    )

    suspend fun get(key: String): ByteArray?

    suspend fun exists(key: String): Boolean

    suspend fun delete(key: String)

    override fun close() = Unit
}

object DisabledStaffPhotoStorage : StaffPhotoStorage {
    override val available = false

    override suspend fun put(
        key: String,
        bytes: ByteArray,
    ): Unit = throw StaffPhotoUnavailableException()

    override suspend fun get(key: String): ByteArray? = throw StaffPhotoUnavailableException()

    override suspend fun exists(key: String): Boolean = throw StaffPhotoUnavailableException()

    override suspend fun delete(key: String): Unit = throw StaffPhotoUnavailableException()
}

class StaffPhotoStorageConfig private constructor(
    val enabled: Boolean,
    private val endpoint: URI?,
    private val bucket: String?,
    private val accessKey: String?,
    private val secretKey: String?,
    val maxInputBytes: Int,
) {
    fun createVenueInfoStorage(): com.hookah.platform.backend.miniapp.venue.media.VenueInfoMediaStorage =
        if (!enabled) {
            com.hookah.platform.backend.miniapp.venue.media.DisabledVenueInfoMediaStorage
        } else {
            com.hookah.platform.backend.miniapp.venue.media.R2VenueInfoMediaStorage(
                privateR2Client(checkNotNull(endpoint), checkNotNull(accessKey), checkNotNull(secretKey)),
                checkNotNull(bucket),
            )
        }

    fun createStorage(): StaffPhotoStorage =
        if (!enabled) {
            DisabledStaffPhotoStorage
        } else {
            R2StaffPhotoStorage(
                endpoint = checkNotNull(endpoint),
                bucket = checkNotNull(bucket),
                accessKey = checkNotNull(accessKey),
                secretKey = checkNotNull(secretKey),
            )
        }

    override fun toString() = "StaffPhotoStorageConfig(enabled=$enabled)"

    companion object {
        fun from(
            config: ApplicationConfig,
            appEnv: String,
            enabledPath: String = "venue.staffPhotos.enabled",
        ): StaffPhotoStorageConfig {
            fun value(key: String) =
                config.propertyOrNull("venue.staffPhotos.$key")?.getString()?.trim()?.takeIf { it.isNotEmpty() }
            val endpoint = runCatching { URI(value("endpoint") ?: "") }.getOrNull()
            val jurisdiction = value("jurisdiction")
            val suffix =
                when (jurisdiction) {
                    "default" -> ""
                    "eu" -> "eu."
                    else -> null
                }
            val validEndpoint =
                suffix != null && endpoint?.scheme == "https" && endpoint.userInfo == null &&
                    endpoint.port == -1 &&
                    endpoint.query == null &&
                    endpoint.fragment == null &&
                    endpoint.path.isNullOrEmpty() &&
                    Regex("[a-f0-9]{32}\\.${Regex.escape(suffix)}r2\\.cloudflarestorage\\.com")
                        .matches(endpoint.host ?: "")
            val bucket = value("bucket")
            val accessKey = value("accessKey")
            val secretKey = value("secretKey")
            val enabled =
                config.propertyOrNull(enabledPath)?.getString() == "true" &&
                    appEnv.lowercase() !in setOf("prod", "production") &&
                    validEndpoint && value("region") == "auto" && bucket == STAFF_PHOTO_STAGING_BUCKET &&
                    accessKey != null && secretKey != null
            return StaffPhotoStorageConfig(
                enabled,
                endpoint,
                bucket,
                accessKey,
                secretKey,
                value("maxInputBytes")?.toIntOrNull()?.coerceIn(1024, STAFF_PHOTO_MAX_INPUT_BYTES)
                    ?: STAFF_PHOTO_MAX_INPUT_BYTES,
            )
        }
    }
}

class R2StaffPhotoStorage(
    endpoint: URI,
    private val bucket: String,
    accessKey: String,
    secretKey: String,
) : StaffPhotoStorage {
    private val client = privateR2Client(endpoint, accessKey, secretKey)

    override val available = true

    override suspend fun put(
        key: String,
        bytes: ByteArray,
    ) = safe(key) {
        require(bytes.size <= STAFF_PHOTO_MAX_OUTPUT_BYTES)
        client.putObject({
            it.bucket(
                bucket,
            ).key(key).contentType("image/jpeg").cacheControl("no-store").storageClass(StorageClass.STANDARD)
        }, RequestBody.fromBytes(bytes))
        Unit
    }

    override suspend fun get(key: String): ByteArray? =
        safe(key) {
            try {
                client.getObject(
                    { it.bucket(bucket).key(key) },
                    ResponseTransformer { response, stream ->
                        if (response.contentLength() > STAFF_PHOTO_MAX_OUTPUT_BYTES) {
                            throw StaffPhotoUnavailableException()
                        }
                        stream.readNBytes(STAFF_PHOTO_MAX_OUTPUT_BYTES + 1).also {
                            if (it.size > STAFF_PHOTO_MAX_OUTPUT_BYTES) throw StaffPhotoUnavailableException()
                        }
                    },
                )
            } catch (_: NoSuchKeyException) {
                null
            }
        }

    override suspend fun exists(key: String): Boolean =
        safe(key) {
            try {
                client.headObject { it.bucket(bucket).key(key) }
                true
            } catch (e: S3Exception) {
                if (e.statusCode() == 404) false else throw StaffPhotoUnavailableException()
            }
        }

    override suspend fun delete(key: String) =
        safe(key) {
            client.deleteObject { it.bucket(bucket).key(key) }
            Unit
        }

    override fun close() = client.close()

    private suspend fun <T> safe(
        key: String,
        action: () -> T,
    ): T =
        withContext(Dispatchers.IO) {
            require(Regex("staff-photos/[a-f0-9-]{36}\\.jpg").matches(key))
            try {
                action()
            } catch (_: Exception) {
                throw StaffPhotoUnavailableException()
            }
        }
}

internal fun privateR2Client(
    endpoint: URI,
    accessKey: String,
    secretKey: String,
): S3Client =
    S3Client.builder()
        .endpointOverride(endpoint)
        .region(Region.of("auto"))
        .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(accessKey, secretKey)))
        .serviceConfiguration(
            S3Configuration.builder().pathStyleAccessEnabled(true).chunkedEncodingEnabled(false).build(),
        )
        .requestChecksumCalculation(RequestChecksumCalculation.WHEN_REQUIRED)
        .responseChecksumValidation(ResponseChecksumValidation.WHEN_REQUIRED)
        .httpClientBuilder(
            UrlConnectionHttpClient.builder().connectionTimeout(
                Duration.ofSeconds(3),
            ).socketTimeout(Duration.ofSeconds(10)),
        )
        .overrideConfiguration {
            it.apiCallAttemptTimeout(
                Duration.ofSeconds(15),
            ).apiCallTimeout(Duration.ofSeconds(20))
        }
        .build()
