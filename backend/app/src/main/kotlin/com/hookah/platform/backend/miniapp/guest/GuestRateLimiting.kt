package com.hookah.platform.backend.miniapp.guest

import com.hookah.platform.backend.api.NotFoundException
import com.hookah.platform.backend.api.TooManyRequestsException
import com.hookah.platform.backend.miniapp.guest.api.AddBatchRequest
import com.hookah.platform.backend.miniapp.guest.api.StaffCallRequest
import com.hookah.platform.backend.miniapp.venue.requireUserId
import com.hookah.platform.backend.telegram.TableContext
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.createRouteScopedPlugin
import io.ktor.server.config.ApplicationConfig
import io.ktor.util.AttributeKey
import java.time.Duration
import java.time.Instant
import java.util.ArrayDeque
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

interface RateLimiter {
    fun tryAcquire(
        key: GuestRateLimitKey,
        limit: Int,
        window: Duration,
        now: Instant = Instant.now(),
    ): Boolean
}

data class GuestRateLimitKey(
    val venueId: Long,
    val userId: Long,
    val tableSessionId: Long,
    val endpoint: String,
)

class InMemoryRateLimiter(
    private val cleanupEvery: Int = DEFAULT_CLEANUP_EVERY,
    private val maxBuckets: Int = DEFAULT_MAX_BUCKETS,
) : RateLimiter {
    private data class Bucket(
        val requests: ArrayDeque<Instant> = ArrayDeque(),
        var expiresAt: Instant = Instant.EPOCH,
    )

    private val buckets = ConcurrentHashMap<GuestRateLimitKey, Bucket>()
    private val tryAcquireCalls = AtomicLong(0)

    init {
        require(cleanupEvery > 0) { "cleanupEvery must be positive" }
        require(maxBuckets > 0) { "maxBuckets must be positive" }
    }

    override fun tryAcquire(
        key: GuestRateLimitKey,
        limit: Int,
        window: Duration,
        now: Instant,
    ): Boolean {
        require(limit > 0) { "limit must be positive" }
        require(!window.isZero && !window.isNegative) { "window must be positive" }

        var allowed = false
        buckets.compute(key) { _, existingBucket ->
            val bucket = existingBucket ?: Bucket()
            synchronized(bucket) {
                removeExpiredRequests(bucket = bucket, now = now, window = window)
                if (bucket.requests.size < limit) {
                    bucket.requests.addLast(now)
                    allowed = true
                }
                bucket.expiresAt = now.plus(window)
                bucket
            }
        }
        runCleanupIfNeeded(now)
        return allowed
    }

    internal fun bucketCountForTesting(): Int = buckets.size

    private fun removeExpiredRequests(
        bucket: Bucket,
        now: Instant,
        window: Duration,
    ) {
        val threshold = now.minus(window)
        while (bucket.requests.isNotEmpty() && bucket.requests.first().isBefore(threshold)) {
            bucket.requests.removeFirst()
        }
    }

    private fun runCleanupIfNeeded(now: Instant) {
        if (!shouldCleanup()) {
            return
        }
        for (key in buckets.keys) {
            buckets.computeIfPresent(key) { _, bucket ->
                synchronized(bucket) {
                    if (!bucket.expiresAt.isAfter(now)) {
                        null
                    } else {
                        bucket
                    }
                }
            }
        }
    }

    private fun shouldCleanup(): Boolean {
        if (buckets.size > maxBuckets) {
            return true
        }
        val calls = tryAcquireCalls.incrementAndGet()
        return calls % cleanupEvery.toLong() == 0L
    }

    private companion object {
        const val DEFAULT_CLEANUP_EVERY: Int = 256
        const val DEFAULT_MAX_BUCKETS: Int = 10_000
    }
}

data class GuestRateLimitPolicy(
    val maxRequests: Int,
    val window: Duration,
)

data class GuestRateLimitConfig(
    val staffCall: GuestRateLimitPolicy,
    val addBatch: GuestRateLimitPolicy,
) {
    companion object {
        fun from(config: ApplicationConfig): GuestRateLimitConfig =
            GuestRateLimitConfig(
                staffCall =
                    config.readPolicy(
                        "guest.rateLimit.staffCall",
                        defaultMaxRequests = 5,
                        defaultWindowSeconds = 30,
                    ),
                addBatch =
                    config.readPolicy(
                        "guest.rateLimit.addBatch",
                        defaultMaxRequests = 5,
                        defaultWindowSeconds = 10,
                    ),
            )

        private fun ApplicationConfig.readPolicy(
            path: String,
            defaultMaxRequests: Int,
            defaultWindowSeconds: Long,
        ): GuestRateLimitPolicy {
            val maxRequests =
                propertyOrNull("$path.maxRequests")?.getString()?.trim()?.toIntOrNull() ?: defaultMaxRequests
            val windowSeconds =
                propertyOrNull("$path.windowSeconds")?.getString()?.trim()?.toLongOrNull() ?: defaultWindowSeconds
            require(maxRequests > 0) { "$path.maxRequests must be > 0" }
            require(windowSeconds > 0) { "$path.windowSeconds must be > 0" }
            return GuestRateLimitPolicy(
                maxRequests = maxRequests,
                window = Duration.ofSeconds(windowSeconds),
            )
        }
    }
}

val addBatchResolvedTableAttribute = AttributeKey<TableContext>("guest-rate-limit-add-batch-table")
val staffCallResolvedTableAttribute = AttributeKey<TableContext>("guest-rate-limit-staff-call-table")

private class GuestEndpointRateLimitPluginConfig {
    var endpoint: String = ""
    lateinit var policy: GuestRateLimitPolicy
    lateinit var rateLimiter: RateLimiter
    lateinit var tableTokenResolver: suspend (String) -> TableContext?
    lateinit var resolvedTableAttribute: AttributeKey<TableContext>
}

private val AddBatchRateLimitPlugin =
    createRouteScopedPlugin(
        name = "AddBatchRateLimitPlugin",
        createConfiguration = ::GuestEndpointRateLimitPluginConfig,
    ) {
        val endpoint = pluginConfig.endpoint
        val policy = pluginConfig.policy
        val rateLimiter = pluginConfig.rateLimiter
        val tableTokenResolver = pluginConfig.tableTokenResolver
        val resolvedTableAttribute = pluginConfig.resolvedTableAttribute

        onCallReceive { call, body ->
            if (body !is AddBatchRequest) {
                return@onCallReceive
            }
            enforceGuestRateLimit(
                call = call,
                endpoint = endpoint,
                policy = policy,
                rateLimiter = rateLimiter,
                tableToken = body.tableToken,
                tableTokenResolver = tableTokenResolver,
                resolvedTableAttribute = resolvedTableAttribute,
            )
        }
    }

private val StaffCallRateLimitPlugin =
    createRouteScopedPlugin(
        name = "StaffCallRateLimitPlugin",
        createConfiguration = ::GuestEndpointRateLimitPluginConfig,
    ) {
        val endpoint = pluginConfig.endpoint
        val policy = pluginConfig.policy
        val rateLimiter = pluginConfig.rateLimiter
        val tableTokenResolver = pluginConfig.tableTokenResolver
        val resolvedTableAttribute = pluginConfig.resolvedTableAttribute

        onCallReceive { call, body ->
            if (body !is StaffCallRequest) {
                return@onCallReceive
            }
            normalizeStaffCallReason(body.reason)
            normalizeStaffCallComment(body.comment)
            enforceGuestRateLimit(
                call = call,
                endpoint = endpoint,
                policy = policy,
                rateLimiter = rateLimiter,
                tableToken = body.tableToken,
                tableTokenResolver = tableTokenResolver,
                resolvedTableAttribute = resolvedTableAttribute,
            )
        }
    }

private suspend fun enforceGuestRateLimit(
    call: ApplicationCall,
    endpoint: String,
    policy: GuestRateLimitPolicy,
    rateLimiter: RateLimiter,
    tableToken: String,
    tableTokenResolver: suspend (String) -> TableContext?,
    resolvedTableAttribute: AttributeKey<TableContext>,
) {
    val token = validateTableToken(tableToken)
    val table = tableTokenResolver(token) ?: throw NotFoundException()
    call.attributes.put(resolvedTableAttribute, table)

    val userId = call.requireUserId()
    val key =
        GuestRateLimitKey(
            venueId = table.venueId,
            userId = userId,
            tableSessionId = table.tableId,
            endpoint = endpoint,
        )
    val allowed = rateLimiter.tryAcquire(key = key, limit = policy.maxRequests, window = policy.window)
    if (!allowed) {
        throw TooManyRequestsException(message = "Too many requests. Please try again later.")
    }
}

fun io.ktor.server.routing.Route.installGuestAddBatchRateLimit(
    endpoint: String,
    policy: GuestRateLimitPolicy,
    rateLimiter: RateLimiter,
    tableTokenResolver: suspend (String) -> TableContext?,
    resolvedTableAttribute: AttributeKey<TableContext>,
) {
    install(AddBatchRateLimitPlugin) {
        this.endpoint = endpoint
        this.policy = policy
        this.rateLimiter = rateLimiter
        this.tableTokenResolver = tableTokenResolver
        this.resolvedTableAttribute = resolvedTableAttribute
    }
}

fun io.ktor.server.routing.Route.installGuestStaffCallRateLimit(
    endpoint: String,
    policy: GuestRateLimitPolicy,
    rateLimiter: RateLimiter,
    tableTokenResolver: suspend (String) -> TableContext?,
    resolvedTableAttribute: AttributeKey<TableContext>,
) {
    install(StaffCallRateLimitPlugin) {
        this.endpoint = endpoint
        this.policy = policy
        this.rateLimiter = rateLimiter
        this.tableTokenResolver = tableTokenResolver
        this.resolvedTableAttribute = resolvedTableAttribute
    }
}

fun ApplicationCall.rateLimitResolvedTableOrNull(attributeKey: AttributeKey<TableContext>): TableContext? =
    if (attributes.contains(attributeKey)) {
        attributes[attributeKey]
    } else {
        null
    }
