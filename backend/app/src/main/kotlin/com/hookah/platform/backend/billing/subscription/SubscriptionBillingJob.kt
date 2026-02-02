package com.hookah.platform.backend.billing.subscription

import com.hookah.platform.backend.telegram.sanitizeTelegramForLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory

class SubscriptionBillingJob(
    private val engine: SubscriptionBillingEngine,
    private val intervalSeconds: Long
) {
    private val logger = LoggerFactory.getLogger(SubscriptionBillingJob::class.java)
    private var job: Job? = null

    fun start(scope: CoroutineScope) {
        if (job != null) {
            return
        }
        job = scope.launch {
            while (isActive) {
                try {
                    engine.tick()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    logger.warn("Subscription billing tick failed: {}", sanitizeTelegramForLog(e.message))
                }
                delay(intervalSeconds * 1000)
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
    }
}
