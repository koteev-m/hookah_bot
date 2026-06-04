package com.hookah.platform.backend.miniapp.guest

import com.hookah.platform.backend.miniapp.guest.db.VisitFeedbackRepository
import com.hookah.platform.backend.miniapp.guest.db.VisitFeedbackRequestDelivery
import com.hookah.platform.backend.telegram.InlineKeyboardMarkup
import com.hookah.platform.backend.telegram.TelegramOutboxEnqueuer
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import java.time.Duration
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals

class VisitFeedbackWorkerTest {
    @Test
    fun `runOnce sends due feedback request once`() =
        runBlocking {
            val repository = mockk<VisitFeedbackRepository>()
            val outboxEnqueuer = mockk<TelegramOutboxEnqueuer>()
            val now = Instant.parse("2030-05-10T12:00:00Z")
            val request =
                VisitFeedbackRequestDelivery(
                    requestId = 11L,
                    visitId = 44L,
                    venueId = 10L,
                    userId = 200L,
                    venueName = "Mix",
                    attempts = 1,
                )
            coEvery { repository.pickDueRequests(now, 100) } returnsMany listOf(listOf(request), emptyList())
            coEvery { outboxEnqueuer.enqueueSendMessage(any(), any(), any(), any()) } returns Unit
            coEvery { repository.markRequestSent(any(), any()) } returns true
            val worker =
                VisitFeedbackWorker(
                    repository = repository,
                    outboxEnqueuer = outboxEnqueuer,
                    interval = Duration.ofSeconds(60),
                    batchSize = 100,
                    scope = CoroutineScope(Dispatchers.Unconfined),
                    nowProvider = { now },
                )

            val first = worker.runOnce()
            val second = worker.runOnce()

            assertEquals(1, first.sentCount)
            assertEquals(0, first.failedCount)
            assertEquals(0, second.sentCount)
            coVerify(exactly = 1) {
                outboxEnqueuer.enqueueSendMessage(
                    200L,
                    "Спасибо за визит в Mix.\nКак всё прошло?",
                    match { markup ->
                        markup is InlineKeyboardMarkup &&
                            markup.inlineKeyboard.flatten().any { it.callbackData == "fb_r:44:1" } &&
                            markup.inlineKeyboard.flatten().any { it.callbackData == "fb_r:44:5" } &&
                            markup.inlineKeyboard.flatten().any { it.callbackData == "fb_c:44" } &&
                            markup.inlineKeyboard.flatten().any { it.callbackData == "fb_skip:44" }
                    },
                    null,
                )
            }
            coVerify(exactly = 1) { repository.markRequestSent(11L, now) }
        }

    @Test
    fun `runOnce marks failed request when enqueue fails`() =
        runBlocking {
            val repository = mockk<VisitFeedbackRepository>()
            val outboxEnqueuer = mockk<TelegramOutboxEnqueuer>()
            val now = Instant.parse("2030-05-10T12:00:00Z")
            val request =
                VisitFeedbackRequestDelivery(
                    requestId = 12L,
                    visitId = 45L,
                    venueId = 10L,
                    userId = 201L,
                    venueName = "Mix",
                    attempts = 1,
                )
            coEvery { repository.pickDueRequests(now, 100) } returns listOf(request)
            coEvery {
                outboxEnqueuer.enqueueSendMessage(any(), any(), any(), any())
            } throws RuntimeException("telegram unavailable")
            coEvery { repository.markRequestFailed(any(), any()) } returns true
            val worker =
                VisitFeedbackWorker(
                    repository = repository,
                    outboxEnqueuer = outboxEnqueuer,
                    interval = Duration.ofSeconds(60),
                    batchSize = 100,
                    scope = CoroutineScope(Dispatchers.Unconfined),
                    nowProvider = { now },
                )

            val result = worker.runOnce()

            assertEquals(0, result.sentCount)
            assertEquals(1, result.failedCount)
            coVerify(exactly = 0) { repository.markRequestSent(any(), any()) }
            coVerify(exactly = 1) { repository.markRequestFailed(12L, "telegram unavailable") }
        }
}
