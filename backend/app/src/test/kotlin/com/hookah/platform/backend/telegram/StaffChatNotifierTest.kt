package com.hookah.platform.backend.telegram

import com.hookah.platform.backend.telegram.db.StaffChatNotificationClaim
import com.hookah.platform.backend.telegram.db.StaffChatNotificationRepository
import com.hookah.platform.backend.telegram.db.VenueRepository
import com.hookah.platform.backend.telegram.db.VenueShort
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.jupiter.api.Test

class StaffChatNotifierTest {
    private val venueRepository: VenueRepository = mockk()
    private val notificationRepository: StaffChatNotificationRepository = mockk()
    private val outboxEnqueuer: TelegramOutboxEnqueuer = mockk(relaxed = true)
    private val notifier =
        StaffChatNotifier(
            venueRepository = venueRepository,
            notificationRepository = notificationRepository,
            outboxEnqueuer = outboxEnqueuer,
            isTelegramActive = { true },
            scope = CoroutineScope(Dispatchers.Unconfined),
        )

    @Test
    fun `notifyNewBatch sends only once for same batch`() =
        runBlocking {
            coEvery { venueRepository.findVenueById(1L) } returns
                VenueShort(
                    id = 1L,
                    name = "Venue",
                    staffChatId = 777L,
                )
            coEvery { notificationRepository.tryClaim(10L, 777L) } returnsMany
                listOf(
                    StaffChatNotificationClaim.CLAIMED,
                    StaffChatNotificationClaim.ALREADY,
                )

            val event =
                NewBatchNotification(
                    venueId = 1L,
                    orderId = 2L,
                    batchId = 10L,
                    tableLabel = "1",
                    itemsSummary = "Tea x1",
                    comment = "Позвоните, пожалуйста",
                )

            notifier.notifyNewBatch(event)
            notifier.notifyNewBatch(event)
            yield()

            coVerify(exactly = 1) { outboxEnqueuer.enqueueSendMessage(777L, any(), any()) }
            coVerify(exactly = 2) { notificationRepository.tryClaim(10L, 777L) }
        }

    @Test
    fun `notifyNewBatch returns early when telegram inactive`() =
        runBlocking {
            val inactiveNotifier =
                StaffChatNotifier(
                    venueRepository = venueRepository,
                    notificationRepository = notificationRepository,
                    outboxEnqueuer = outboxEnqueuer,
                    isTelegramActive = { false },
                    scope = CoroutineScope(Dispatchers.Unconfined),
                )

            inactiveNotifier.notifyNewBatch(
                NewBatchNotification(
                    venueId = 1L,
                    orderId = 2L,
                    batchId = 10L,
                    tableLabel = "1",
                    itemsSummary = "Tea x1",
                    comment = null,
                ),
            )
            yield()

            coVerify(exactly = 0) { notificationRepository.tryClaim(any(), any()) }
            coVerify(exactly = 0) { outboxEnqueuer.enqueueSendMessage(any(), any(), any()) }
        }
}
