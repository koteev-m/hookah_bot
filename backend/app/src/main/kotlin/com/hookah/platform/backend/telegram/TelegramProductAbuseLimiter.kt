package com.hookah.platform.backend.telegram

import java.security.SecureRandom
import java.time.Duration
import java.util.ArrayDeque
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

class TelegramProductAbuseLimiter internal constructor(
    private val nowMillis: () -> Long = System::currentTimeMillis,
    private val limits: Map<Category, Limit> = defaultLimits,
    private val globalLimits: Map<Category, Limit> = defaultGlobalLimits,
    private val inviteTokenLimit: Limit = DEFAULT_INVITE_TOKEN_LIMIT,
    private val maxTrackedKeys: Int = DEFAULT_MAX_TRACKED_KEYS,
    inviteDigestKey: ByteArray = newDigestKey(),
) {
    internal enum class Category {
        PRIVATE_TRAFFIC,
        STAFF_INVITE,
        GROUP_LINK,
        GROUP_OPERATION,
    }

    internal data class Limit(
        val maxAttempts: Int,
        val window: Duration,
    ) {
        init {
            require(maxAttempts > 0) { "maxAttempts must be positive" }
            require(!window.isNegative && !window.isZero) { "window must be positive" }
        }
    }

    internal sealed interface Decision {
        object Allowed : Decision

        data class Denied(val category: Category) : Decision
    }

    private enum class Scope {
        PRIVATE_ACTOR,
        GROUP_CHAT,
        INVITE_TOKEN_DIGEST,
    }

    private sealed interface Subject {
        val scope: Scope

        data class Numeric(
            override val scope: Scope,
            val value: Long,
        ) : Subject

        data class Digest(
            val value: String,
        ) : Subject {
            override val scope: Scope = Scope.INVITE_TOKEN_DIGEST
        }
    }

    private data class Attempt(
        val category: Category,
        val subjects: List<Subject>,
    )

    private data class ScopedBucketKey(
        val category: Category,
        val subject: Subject,
    )

    private val lock = Any()
    private val globalBuckets = mutableMapOf<Category, ArrayDeque<Long>>()
    private val scopedBuckets = mutableMapOf<ScopedBucketKey, ArrayDeque<Long>>()
    private val inviteDigestKey = inviteDigestKey.copyOf()

    init {
        require(maxTrackedKeys > 0) { "maxTrackedKeys must be positive" }
        require(inviteDigestKey.isNotEmpty()) { "inviteDigestKey must not be empty" }
        require(limits.keys.containsAll(Category.entries)) { "limits must cover every category" }
        require(globalLimits.keys.containsAll(Category.entries)) { "globalLimits must cover every category" }
    }

    internal fun evaluate(update: TelegramUpdate): Decision = evaluate(update, includeScopedBuckets = true)

    internal fun evaluateCoarse(update: TelegramUpdate): Decision = evaluate(update, includeScopedBuckets = false)

    internal fun trackedScopedBucketCount(): Int = synchronized(lock) { scopedBuckets.size }

    private fun evaluate(
        update: TelegramUpdate,
        includeScopedBuckets: Boolean,
    ): Decision {
        val attempts = attemptsFor(update)
        if (attempts.isEmpty()) return Decision.Allowed
        val now = nowMillis()
        return synchronized(lock) {
            attempts.forEach { attempt ->
                val globalBucket = globalBuckets.getOrPut(attempt.category) { ArrayDeque() }
                if (!acquire(globalBucket, globalLimits.getValue(attempt.category), now)) {
                    return@synchronized Decision.Denied(attempt.category)
                }
                if (includeScopedBuckets) {
                    attempt.subjects.forEach { subject ->
                        val key = ScopedBucketKey(attempt.category, subject)
                        if (!acquireScoped(key, limitFor(key), now)) {
                            return@synchronized Decision.Denied(attempt.category)
                        }
                    }
                }
            }
            Decision.Allowed
        }
    }

    private fun attemptsFor(update: TelegramUpdate): List<Attempt> {
        val actorId = update.message?.fromUser?.id ?: update.callbackQuery?.from?.id ?: return emptyList()
        val chat = update.message?.chat ?: update.callbackQuery?.message?.chat ?: return emptyList()
        if (chat.type == "private") {
            val actor = Subject.Numeric(Scope.PRIVATE_ACTOR, actorId)
            val inviteAttempt = inviteAttempt(update)
            return if (inviteAttempt == null) {
                listOf(Attempt(Category.PRIVATE_TRAFFIC, listOf(actor)))
            } else {
                val inviteSubjects =
                    buildList<Subject> {
                        add(actor)
                        inviteAttempt.token?.let { token -> add(Subject.Digest(digestToken(token))) }
                    }
                listOf(
                    Attempt(Category.PRIVATE_TRAFFIC, listOf(actor)),
                    Attempt(Category.STAFF_INVITE, inviteSubjects),
                )
            }
        }
        if (chat.type == "group" || chat.type == "supergroup") {
            val group = Subject.Numeric(Scope.GROUP_CHAT, chat.id)
            val command =
                update.message
                    ?.text
                    ?.trim()
                    ?.substringBefore(' ')
                    ?.substringBefore('@')
                    ?.lowercase()
            return if (command == "/link") {
                listOf(
                    Attempt(Category.GROUP_OPERATION, listOf(group)),
                    Attempt(Category.GROUP_LINK, listOf(group)),
                )
            } else {
                listOf(Attempt(Category.GROUP_OPERATION, listOf(group)))
            }
        }
        return emptyList()
    }

    private fun inviteAttempt(update: TelegramUpdate): InviteAttempt? {
        update.callbackQuery?.data?.let { data ->
            INVITE_CALLBACK_PREFIXES.firstOrNull(data::startsWith)?.let { prefix ->
                return InviteAttempt(data.removePrefix(prefix).takeIf(String::isNotEmpty))
            }
        }
        val text = update.message?.text?.trim() ?: return null
        val parts = text.split(Regex("\\s+"), limit = 2)
        val command = parts.firstOrNull()?.substringBefore('@')?.lowercase()
        if (command != "/start") return null
        val payload = parts.getOrNull(1)?.trim() ?: return null
        if (!payload.startsWith(INVITE_START_PREFIX)) return null
        return InviteAttempt(payload.removePrefix(INVITE_START_PREFIX).takeIf(String::isNotEmpty))
    }

    private fun acquireScoped(
        key: ScopedBucketKey,
        limit: Limit,
        now: Long,
    ): Boolean {
        val existing = scopedBuckets[key]
        val bucket =
            if (existing != null) {
                existing
            } else {
                pruneExpiredScopedBuckets(now)
                if (scopedBuckets.size >= maxTrackedKeys) return false
                ArrayDeque<Long>().also { scopedBuckets[key] = it }
            }
        return acquire(bucket, limit, now)
    }

    private fun acquire(
        bucket: ArrayDeque<Long>,
        limit: Limit,
        now: Long,
    ): Boolean {
        pruneBucket(bucket, limit, now)
        if (bucket.size >= limit.maxAttempts) return false
        bucket.addLast(now)
        return true
    }

    private fun pruneExpiredScopedBuckets(now: Long) {
        scopedBuckets.entries.removeIf { (key, bucket) ->
            pruneBucket(bucket, limitFor(key), now)
            bucket.isEmpty()
        }
    }

    private fun pruneBucket(
        bucket: ArrayDeque<Long>,
        limit: Limit,
        now: Long,
    ) {
        val oldestAllowed = now - limit.window.toMillis()
        while (bucket.peekFirst()?.let { it <= oldestAllowed } == true) {
            bucket.removeFirst()
        }
    }

    private fun limitFor(key: ScopedBucketKey): Limit =
        if (key.subject.scope == Scope.INVITE_TOKEN_DIGEST) {
            inviteTokenLimit
        } else {
            limits.getValue(key.category)
        }

    private fun digestToken(token: String): String {
        val mac = Mac.getInstance(HMAC_ALGORITHM)
        mac.init(SecretKeySpec(inviteDigestKey, HMAC_ALGORITHM))
        return Base64.getUrlEncoder().withoutPadding().encodeToString(mac.doFinal(token.toByteArray(Charsets.UTF_8)))
    }

    private data class InviteAttempt(val token: String?)

    private companion object {
        const val DEFAULT_MAX_TRACKED_KEYS = 20_000
        const val HMAC_ALGORITHM = "HmacSHA256"
        const val INVITE_START_PREFIX = "staff_invite_"

        val INVITE_CALLBACK_PREFIXES = listOf("staff_invite_accept:", "staff_invite_decline:")

        val defaultLimits =
            mapOf(
                Category.PRIVATE_TRAFFIC to Limit(120, Duration.ofMinutes(1)),
                Category.STAFF_INVITE to Limit(12, Duration.ofMinutes(5)),
                Category.GROUP_LINK to Limit(6, Duration.ofMinutes(5)),
                Category.GROUP_OPERATION to Limit(120, Duration.ofMinutes(1)),
            )

        val defaultGlobalLimits =
            mapOf(
                Category.PRIVATE_TRAFFIC to Limit(12_000, Duration.ofMinutes(1)),
                Category.STAFF_INVITE to Limit(2_000, Duration.ofMinutes(5)),
                Category.GROUP_LINK to Limit(1_000, Duration.ofMinutes(5)),
                Category.GROUP_OPERATION to Limit(12_000, Duration.ofMinutes(1)),
            )

        val DEFAULT_INVITE_TOKEN_LIMIT = Limit(30, Duration.ofMinutes(5))

        fun newDigestKey(): ByteArray = ByteArray(32).also(SecureRandom()::nextBytes)
    }
}
