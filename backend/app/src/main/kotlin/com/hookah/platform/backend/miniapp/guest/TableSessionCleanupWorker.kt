package com.hookah.platform.backend.miniapp.guest

import com.hookah.platform.backend.miniapp.guest.db.TableSessionRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory

class TableSessionCleanupWorker(
    private val repository: TableSessionRepository,
    private val intervalMillis: Long,
    private val scope: CoroutineScope,
) {
    private val logger = LoggerFactory.getLogger(TableSessionCleanupWorker::class.java)

    fun start(): Job =
        scope.launch {
            while (isActive) {
                try {
                    val closed = repository.closeExpiredSessions()
                    if (closed > 0) {
                        logger.info("Closed {} expired table sessions", closed)
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    logger.warn("Table session cleanup worker failed: {}", e.message)
                }
                delay(intervalMillis)
            }
        }
}
