package com.hookah.platform.backend.telegram

import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue

internal data class PlatformGuestQrPendingKey(
    val actorUserId: Long,
    val chatId: Long,
)

internal data class PlatformGuestQrPendingConfirmation(
    val reference: String,
    val tableToken: String,
    val venueId: Long,
    val tableId: Long,
    val expiresAt: Instant,
)

/**
 * Deliberately process-local: a restart or callback routed to another instance has no pending
 * confirmation and therefore fails closed.
 */
internal class PlatformGuestQrPendingConfirmationStore(
    private val expiredSweepBatchSize: Int = DEFAULT_EXPIRED_SWEEP_BATCH_SIZE,
) {
    private data class SweepEntry(
        val key: PlatformGuestQrPendingKey,
        val pending: PlatformGuestQrPendingConfirmation,
    )

    private val pendingByKey = ConcurrentHashMap<PlatformGuestQrPendingKey, PlatformGuestQrPendingConfirmation>()
    private val sweepQueue = ConcurrentLinkedQueue<SweepEntry>()

    init {
        require(expiredSweepBatchSize > 0) { "expiredSweepBatchSize must be positive" }
    }

    fun replace(
        key: PlatformGuestQrPendingKey,
        pending: PlatformGuestQrPendingConfirmation,
        now: Instant,
    ): PlatformGuestQrPendingConfirmation? {
        sweepExpired(now)
        val previous = pendingByKey.put(key, pending)
        sweepQueue.offer(SweepEntry(key = key, pending = pending))
        return previous
    }

    fun consume(
        key: PlatformGuestQrPendingKey,
        reference: String,
        now: Instant,
    ): PlatformGuestQrPendingConfirmation? {
        sweepExpired(now)
        var consumed: PlatformGuestQrPendingConfirmation? = null
        pendingByKey.compute(key) { _, current ->
            when {
                current == null -> null
                !current.expiresAt.isAfter(now) -> null
                current.reference != reference -> current
                else -> {
                    consumed = current
                    null
                }
            }
        }
        return consumed
    }

    fun clear(key: PlatformGuestQrPendingKey): PlatformGuestQrPendingConfirmation? = pendingByKey.remove(key)

    private fun sweepExpired(now: Instant) {
        repeat(expiredSweepBatchSize) {
            val sweepEntry = sweepQueue.poll() ?: return
            val current = pendingByKey[sweepEntry.key]
            if (current !== sweepEntry.pending) {
                return@repeat
            }
            if (current.expiresAt.isAfter(now)) {
                sweepQueue.offer(sweepEntry)
            } else {
                pendingByKey.remove(sweepEntry.key, current)
            }
        }
    }

    private companion object {
        const val DEFAULT_EXPIRED_SWEEP_BATCH_SIZE = 32
    }
}
