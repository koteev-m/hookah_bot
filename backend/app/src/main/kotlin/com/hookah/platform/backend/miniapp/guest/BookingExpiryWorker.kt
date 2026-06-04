package com.hookah.platform.backend.miniapp.guest

import com.hookah.platform.backend.miniapp.guest.db.ExpireBookingsResult
import com.hookah.platform.backend.miniapp.guest.db.GuestBookingRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import java.time.Duration
import java.time.Instant

class BookingExpiryWorker(
    private val repository: GuestBookingRepository,
    private val interval: Duration,
    private val batchSize: Int,
    private val scope: CoroutineScope,
    private val nowProvider: () -> Instant = Instant::now,
) {
    private val logger = LoggerFactory.getLogger(BookingExpiryWorker::class.java)

    fun start(): Job =
        scope.launch {
            while (isActive) {
                try {
                    runOnce()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    logger.warn("Booking expiry worker failed: {}", e.message)
                    logger.debug("Booking expiry worker failure details", e)
                }
                delay(interval.toMillis())
            }
        }

    suspend fun runOnce(now: Instant = nowProvider()): ExpireBookingsResult {
        val result = repository.expireOverdueBookings(now = now, limit = batchSize)
        if (result.expiredCount > 0) {
            logger.info("Expired {} overdue bookings", result.expiredCount)
        }
        return result
    }
}
