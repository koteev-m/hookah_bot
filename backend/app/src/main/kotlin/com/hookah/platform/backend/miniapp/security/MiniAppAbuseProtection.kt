package com.hookah.platform.backend.miniapp.security

import com.hookah.platform.backend.api.TooManyRequestsException
import io.ktor.http.HttpHeaders
import io.ktor.server.application.ApplicationCall
import java.security.SecureRandom
import java.time.Duration
import java.util.ArrayDeque
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

internal data class MiniAppRateLimitPolicy(
    val maxAttempts: Int,
    val window: Duration,
) {
    init {
        require(maxAttempts > 0) { "maxAttempts must be positive" }
        require(!window.isNegative && !window.isZero) { "window must be positive" }
    }
}

internal data class MiniAppAbuseConfig(
    val authPreGlobal: MiniAppRateLimitPolicy = MiniAppRateLimitPolicy(600, Duration.ofMinutes(1)),
    val authPreSource: MiniAppRateLimitPolicy = MiniAppRateLimitPolicy(300, Duration.ofMinutes(1)),
    val authPostGlobal: MiniAppRateLimitPolicy = MiniAppRateLimitPolicy(300, Duration.ofMinutes(1)),
    val authPostSubject: MiniAppRateLimitPolicy = MiniAppRateLimitPolicy(12, Duration.ofMinutes(5)),
    val inviteAcceptGlobal: MiniAppRateLimitPolicy = MiniAppRateLimitPolicy(300, Duration.ofMinutes(5)),
    val inviteAcceptSubject: MiniAppRateLimitPolicy = MiniAppRateLimitPolicy(12, Duration.ofMinutes(5)),
    val inviteAcceptDigest: MiniAppRateLimitPolicy = MiniAppRateLimitPolicy(30, Duration.ofMinutes(5)),
    val inviteCreateGlobal: MiniAppRateLimitPolicy = MiniAppRateLimitPolicy(120, Duration.ofMinutes(10)),
    val inviteCreateActorVenue: MiniAppRateLimitPolicy = MiniAppRateLimitPolicy(12, Duration.ofMinutes(10)),
    val maxActivePendingInvitesPerVenueRole: Int = 10,
    val maxTrackedBuckets: Int = 20_000,
    val cleanupEvery: Int = 256,
) {
    init {
        require(maxActivePendingInvitesPerVenueRole > 0) {
            "maxActivePendingInvitesPerVenueRole must be positive"
        }
        require(maxTrackedBuckets > 0) { "maxTrackedBuckets must be positive" }
        require(cleanupEvery > 0) { "cleanupEvery must be positive" }
    }
}

internal sealed interface MiniAppRateLimitDecision {
    data object Allowed : MiniAppRateLimitDecision

    data class Denied(val retryAfterSeconds: Long) : MiniAppRateLimitDecision
}

internal class MiniAppAbuseProtection(
    private val config: MiniAppAbuseConfig = MiniAppAbuseConfig(),
    private val nowMillis: () -> Long = System::currentTimeMillis,
    digestKey: ByteArray = randomDigestKey(),
) {
    private sealed interface BucketKey {
        data object AuthPreGlobal : BucketKey

        data class AuthPreSource(val digest: String) : BucketKey

        data object AuthPostGlobal : BucketKey

        data class AuthPostSubject(val userId: Long) : BucketKey

        data object InviteAcceptGlobal : BucketKey

        data class InviteAcceptSubject(val userId: Long) : BucketKey

        data class InviteAcceptDigest(val digest: String) : BucketKey

        data object InviteCreateGlobal : BucketKey

        data class InviteCreateActorVenue(
            val actorUserId: Long,
            val venueId: Long,
        ) : BucketKey
    }

    private data class Requirement(
        val key: BucketKey,
        val policy: MiniAppRateLimitPolicy,
    )

    private data class Bucket(
        val policy: MiniAppRateLimitPolicy,
        val attempts: ArrayDeque<Long> = ArrayDeque(),
    )

    private val digestKey = digestKey.copyOf()
    private val buckets = HashMap<BucketKey, Bucket>()
    private var evaluations = 0L

    init {
        require(this.digestKey.size >= MIN_DIGEST_KEY_BYTES) { "digestKey must be at least 16 bytes" }
    }

    val maxActivePendingInvitesPerVenueRole: Int
        get() = config.maxActivePendingInvitesPerVenueRole

    fun tryAuthPreValidation(source: String): MiniAppRateLimitDecision =
        acquire(
            listOf(
                Requirement(BucketKey.AuthPreGlobal, config.authPreGlobal),
                Requirement(BucketKey.AuthPreSource(digest("auth-source", source)), config.authPreSource),
            ),
        )

    fun tryAuthPostValidation(userId: Long): MiniAppRateLimitDecision {
        if (userId <= 0L) return MiniAppRateLimitDecision.Denied(MIN_RETRY_AFTER_SECONDS)
        return acquire(
            listOf(
                Requirement(BucketKey.AuthPostGlobal, config.authPostGlobal),
                Requirement(BucketKey.AuthPostSubject(userId), config.authPostSubject),
            ),
        )
    }

    fun tryInviteAccept(
        userId: Long,
        inviteCode: String,
    ): MiniAppRateLimitDecision {
        if (userId <= 0L) return MiniAppRateLimitDecision.Denied(MIN_RETRY_AFTER_SECONDS)
        return acquire(
            listOf(
                Requirement(BucketKey.InviteAcceptGlobal, config.inviteAcceptGlobal),
                Requirement(BucketKey.InviteAcceptSubject(userId), config.inviteAcceptSubject),
                Requirement(
                    BucketKey.InviteAcceptDigest(digest("invite-accept", boundedDigestMaterial(inviteCode))),
                    config.inviteAcceptDigest,
                ),
            ),
        )
    }

    fun tryInviteCreate(
        actorUserId: Long,
        venueId: Long,
    ): MiniAppRateLimitDecision {
        if (actorUserId <= 0L || venueId <= 0L) {
            return MiniAppRateLimitDecision.Denied(MIN_RETRY_AFTER_SECONDS)
        }
        return acquire(
            listOf(
                Requirement(BucketKey.InviteCreateGlobal, config.inviteCreateGlobal),
                Requirement(
                    BucketKey.InviteCreateActorVenue(actorUserId, venueId),
                    config.inviteCreateActorVenue,
                ),
            ),
        )
    }

    internal fun bucketCountForTesting(): Int = synchronized(buckets) { buckets.size }

    private fun acquire(requirements: List<Requirement>): MiniAppRateLimitDecision =
        synchronized(buckets) {
            val now = nowMillis()
            evaluations += 1
            requirements.forEach { requirement -> pruneRequiredBucket(requirement, now) }
            if (evaluations % config.cleanupEvery.toLong() == 0L) {
                cleanupExpired(now)
            }

            val retryAfterMillis =
                requirements.maxOfOrNull { requirement ->
                    val bucket = buckets[requirement.key] ?: return@maxOfOrNull 0L
                    if (bucket.attempts.size < requirement.policy.maxAttempts) {
                        0L
                    } else {
                        (bucket.attempts.first + requirement.policy.window.toMillis() - now).coerceAtLeast(1L)
                    }
                } ?: 0L
            if (retryAfterMillis > 0L) {
                return@synchronized MiniAppRateLimitDecision.Denied(toRetryAfterSeconds(retryAfterMillis))
            }

            val missingKeys = requirements.map { it.key }.distinct().count { it !in buckets }
            if (buckets.size + missingKeys > config.maxTrackedBuckets) {
                cleanupExpired(now)
            }
            if (buckets.size + missingKeys > config.maxTrackedBuckets) {
                return@synchronized MiniAppRateLimitDecision.Denied(capacityRetryAfterSeconds(now))
            }

            requirements.forEach { requirement ->
                val bucket =
                    buckets.getOrPut(requirement.key) {
                        Bucket(policy = requirement.policy)
                    }
                check(bucket.policy == requirement.policy) { "rate-limit policy changed for active bucket" }
                bucket.attempts.addLast(now)
            }
            MiniAppRateLimitDecision.Allowed
        }

    private fun pruneRequiredBucket(
        requirement: Requirement,
        now: Long,
    ) {
        val bucket = buckets[requirement.key] ?: return
        prune(bucket, now)
        if (bucket.attempts.isEmpty()) {
            buckets.remove(requirement.key)
        }
    }

    private fun cleanupExpired(now: Long) {
        val iterator = buckets.entries.iterator()
        while (iterator.hasNext()) {
            val bucket = iterator.next().value
            prune(bucket, now)
            if (bucket.attempts.isEmpty()) {
                iterator.remove()
            }
        }
    }

    private fun prune(
        bucket: Bucket,
        now: Long,
    ) {
        val oldestAllowed = now - bucket.policy.window.toMillis()
        while (bucket.attempts.firstOrNull()?.let { it <= oldestAllowed } == true) {
            bucket.attempts.removeFirst()
        }
    }

    private fun capacityRetryAfterSeconds(now: Long): Long {
        val earliestExpiry =
            buckets.values.minOfOrNull { bucket ->
                bucket.attempts.first + bucket.policy.window.toMillis()
            } ?: return MIN_RETRY_AFTER_SECONDS
        return toRetryAfterSeconds((earliestExpiry - now).coerceAtLeast(1L))
    }

    private fun digest(
        scope: String,
        value: String,
    ): String {
        val mac = Mac.getInstance(HMAC_ALGORITHM)
        mac.init(SecretKeySpec(digestKey, HMAC_ALGORITHM))
        val bytes = mac.doFinal("$scope:$value".toByteArray(Charsets.UTF_8))
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes.copyOf(DIGEST_BYTES))
    }

    private fun boundedDigestMaterial(value: String): String = "${value.length}:${value.take(MAX_DIGEST_INPUT_CHARS)}"

    private fun toRetryAfterSeconds(retryAfterMillis: Long): Long =
        ((retryAfterMillis + 999L) / 1_000L).coerceAtLeast(MIN_RETRY_AFTER_SECONDS)

    private companion object {
        const val HMAC_ALGORITHM = "HmacSHA256"
        const val MIN_DIGEST_KEY_BYTES = 16
        const val DIGEST_BYTES = 18
        const val MAX_DIGEST_INPUT_CHARS = 256
        const val MIN_RETRY_AFTER_SECONDS = 1L

        fun randomDigestKey(): ByteArray = ByteArray(32).also { bytes -> SecureRandom().nextBytes(bytes) }
    }
}

internal fun enforceMiniAppRateLimit(
    call: ApplicationCall,
    decision: MiniAppRateLimitDecision,
) {
    if (decision is MiniAppRateLimitDecision.Denied) {
        throwMiniAppRateLimited(call, decision.retryAfterSeconds)
    }
}

internal fun throwMiniAppRateLimited(
    call: ApplicationCall,
    retryAfterSeconds: Long,
): Nothing {
    call.response.headers.append(
        HttpHeaders.RetryAfter,
        retryAfterSeconds.coerceAtLeast(1L).toString(),
        safeOnly = false,
    )
    throw TooManyRequestsException()
}
