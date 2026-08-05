package com.hookah.platform.backend.telegram

import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PlatformGuestQrPendingConfirmationStoreTest {
    private val now = Instant.parse("2026-08-05T10:00:00Z")

    @Test
    fun `replace keeps only the newest pending confirmation for exact actor and chat`() {
        val store = PlatformGuestQrPendingConfirmationStore()
        val key = key()
        val first = pending(reference = "first")
        val second = pending(reference = "second")

        assertNull(store.replace(key, first, now))
        assertEquals(first, store.replace(key, second, now))

        assertNull(store.consume(key, reference = first.reference, now = now))
        assertEquals(second, store.consume(key, reference = second.reference, now = now))
        assertNull(store.consume(key, reference = second.reference, now = now))
    }

    @Test
    fun `wrong reference fails closed without consuming current pending confirmation`() {
        val store = PlatformGuestQrPendingConfirmationStore()
        val key = key()
        val pending = pending()
        store.replace(key, pending, now)

        assertNull(store.consume(key, reference = "wrong-reference", now = now))
        assertEquals(pending, store.consume(key, reference = pending.reference, now = now))
    }

    @Test
    fun `expired confirmation fails closed and is removed`() {
        val store = PlatformGuestQrPendingConfirmationStore(expiredSweepBatchSize = 1)
        val key = key()
        val expired = pending(expiresAt = now)
        store.replace(key, expired, now.minusSeconds(1))

        assertNull(store.consume(key, reference = expired.reference, now = now))
        assertNull(store.consume(key, reference = expired.reference, now = now))
    }

    @Test
    fun `clear removes only exact actor and chat pending confirmation`() {
        val store = PlatformGuestQrPendingConfirmationStore()
        val firstKey = key(chatId = 100L)
        val secondKey = key(chatId = 200L)
        val first = pending(reference = "first")
        val second = pending(reference = "second")
        store.replace(firstKey, first, now)
        store.replace(secondKey, second, now)

        assertEquals(first, store.clear(firstKey))
        assertNull(store.consume(firstKey, reference = first.reference, now = now))
        assertEquals(second, store.consume(secondKey, reference = second.reference, now = now))
    }

    @Test
    fun `new process-local store has no pending confirmation`() {
        val firstStore = PlatformGuestQrPendingConfirmationStore()
        val restartedStore = PlatformGuestQrPendingConfirmationStore()
        val key = key()
        val pending = pending()
        firstStore.replace(key, pending, now)

        assertNull(restartedStore.consume(key, reference = pending.reference, now = now))
        assertEquals(pending, firstStore.consume(key, reference = pending.reference, now = now))
    }

    @Test
    fun `concurrent confirm and cancel consume the same reference exactly once`() {
        val store = PlatformGuestQrPendingConfirmationStore()
        val key = key()
        val pending = pending()
        store.replace(key, pending, now)

        val results = concurrentConsumes(store, key, pending.reference)

        assertEquals(1, results.count { it == pending })
        assertEquals(1, results.count { it == null })
    }

    @Test
    fun `concurrent double confirm consumes the same reference exactly once`() {
        val store = PlatformGuestQrPendingConfirmationStore()
        val key = key()
        val pending = pending()
        store.replace(key, pending, now)

        val results = concurrentConsumes(store, key, pending.reference)

        assertEquals(listOf(null, pending), results.sortedBy { it != null })
        assertNull(store.consume(key, reference = pending.reference, now = now))
    }

    private fun concurrentConsumes(
        store: PlatformGuestQrPendingConfirmationStore,
        key: PlatformGuestQrPendingKey,
        reference: String,
    ): List<PlatformGuestQrPendingConfirmation?> {
        val executor = Executors.newFixedThreadPool(2)
        val ready = CountDownLatch(2)
        val start = CountDownLatch(1)
        return try {
            val futures =
                List(2) {
                    executor.submit<PlatformGuestQrPendingConfirmation?> {
                        ready.countDown()
                        check(start.await(5, TimeUnit.SECONDS))
                        store.consume(key, reference = reference, now = now)
                    }
                }
            check(ready.await(5, TimeUnit.SECONDS))
            start.countDown()
            futures.map { it.get(5, TimeUnit.SECONDS) }
        } finally {
            executor.shutdownNow()
        }
    }

    private fun key(chatId: Long = 100L) =
        PlatformGuestQrPendingKey(
            actorUserId = 42L,
            chatId = chatId,
        )

    private fun pending(
        reference: String = "pending-reference",
        expiresAt: Instant = now.plusSeconds(300),
    ) = PlatformGuestQrPendingConfirmation(
        reference = reference,
        tableToken = "TABLE_TOKEN",
        venueId = 10L,
        tableId = 20L,
        expiresAt = expiresAt,
    )
}
