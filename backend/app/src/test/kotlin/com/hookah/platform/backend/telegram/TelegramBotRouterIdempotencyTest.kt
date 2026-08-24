package com.hookah.platform.backend.telegram

import com.hookah.platform.backend.api.DatabaseUnavailableException
import com.hookah.platform.backend.miniapp.guest.db.GuestBookingRepository
import com.hookah.platform.backend.miniapp.guest.db.GuestMenuRepository
import com.hookah.platform.backend.miniapp.guest.db.GuestTabsRepository
import com.hookah.platform.backend.miniapp.guest.db.TableSessionRepository
import com.hookah.platform.backend.miniapp.subscription.db.SubscriptionRepository
import com.hookah.platform.backend.telegram.db.ChatContextRepository
import com.hookah.platform.backend.telegram.db.DialogStateRepository
import com.hookah.platform.backend.telegram.db.IdempotencyRepository
import com.hookah.platform.backend.telegram.db.OrdersRepository
import com.hookah.platform.backend.telegram.db.StaffCallRepository
import com.hookah.platform.backend.telegram.db.StaffChatLinkCodeRepository
import com.hookah.platform.backend.telegram.db.TableTokenRepository
import com.hookah.platform.backend.telegram.db.UserRepository
import com.hookah.platform.backend.telegram.db.VenueAccessRepository
import com.hookah.platform.backend.telegram.db.VenueBookingHoursRepository
import com.hookah.platform.backend.telegram.db.VenueMenuSectionImagesRepository
import com.hookah.platform.backend.telegram.db.VenueRepository
import io.ktor.server.config.MapApplicationConfig
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Test
import java.time.Duration

class TelegramBotRouterIdempotencyTest {
    @Test
    fun `same token worker restart and redelivery do not repeat side effects`() =
        runBlocking {
            val apiClient: TelegramApiClient = mockk(relaxed = true)
            val outboxEnqueuer: TelegramOutboxEnqueuer = mockk(relaxed = true)
            val idempotencyRepository: IdempotencyRepository = mockk()
            val userRepository: UserRepository = mockk(relaxed = true)
            val tableTokenRepository: TableTokenRepository = mockk()
            val chatContextRepository: ChatContextRepository = mockk()
            val dialogStateRepository: DialogStateRepository = mockk(relaxed = true)
            val ordersRepository: OrdersRepository = mockk()
            val staffCallRepository: StaffCallRepository = mockk()
            val staffChatLinkCodeRepository: StaffChatLinkCodeRepository = mockk()
            val guestBookingRepository: GuestBookingRepository = mockk(relaxed = true)
            val venueRepository: VenueRepository = mockk()
            val venueBookingHoursRepository: VenueBookingHoursRepository = mockk(relaxed = true)
            val venueMenuSectionImagesRepository: VenueMenuSectionImagesRepository = mockk(relaxed = true)
            val venueAccessRepository: VenueAccessRepository = mockk()
            val subscriptionRepository: SubscriptionRepository = mockk()
            val guestMenuRepository: GuestMenuRepository = mockk()
            val tableSessionRepository: TableSessionRepository = mockk(relaxed = true)
            val guestTabsRepository: GuestTabsRepository = mockk(relaxed = true)

            coEvery { idempotencyRepository.tryAcquire(any(), any(), any()) } returnsMany listOf(true, false)

            val trafficPolicy =
                TelegramTrafficPolicy.from(
                    MapApplicationConfig(
                        "telegram.trafficPolicy" to "ALLOWLIST",
                        "telegram.allowedUserIds" to "200",
                        "telegram.allowedChatIds" to "200",
                    ),
                    "staging",
                )
            val router =
                TelegramBotRouter(
                    trafficPolicy = trafficPolicy,
                    config =
                        TelegramBotConfig(
                            enabled = true,
                            token = "test",
                            mode = TelegramBotConfig.Mode.LONG_POLLING,
                            webhookPath = "/",
                            webhookSecretToken = null,
                            webAppPublicUrl = null,
                            platformOwnerId = null,
                            longPollingTimeoutSeconds = 25,
                            staffChatLinkTtlSeconds = 900,
                            staffChatLinkSecretPepper = "pepper",
                            requireStaffChatAdmin = false,
                        ),
                    apiClient = apiClient,
                    outboxEnqueuer = outboxEnqueuer,
                    idempotencyRepository = idempotencyRepository,
                    userRepository = userRepository,
                    tableTokenRepository = tableTokenRepository,
                    chatContextRepository = chatContextRepository,
                    dialogStateRepository = dialogStateRepository,
                    ordersRepository = ordersRepository,
                    staffCallRepository = staffCallRepository,
                    staffChatLinkCodeRepository = staffChatLinkCodeRepository,
                    guestBookingRepository = guestBookingRepository,
                    venueRepository = venueRepository,
                    venueBookingHoursRepository = venueBookingHoursRepository,
                    venueMenuSectionImagesRepository = venueMenuSectionImagesRepository,
                    venueAccessRepository = venueAccessRepository,
                    subscriptionRepository = subscriptionRepository,
                    guestMenuRepository = guestMenuRepository,
                    tableSessionRepository = tableSessionRepository,
                    guestTabsRepository = guestTabsRepository,
                    tableSessionTtl = Duration.ofHours(2),
                    json = Json { ignoreUnknownKeys = true },
                    scope = CoroutineScope(Dispatchers.Unconfined),
                )

            val deniedUpdate =
                TelegramUpdate(
                    updateId = 100,
                    message =
                        Message(
                            messageId = 10,
                            chat = Chat(id = 300, type = "private"),
                            fromUser = User(id = 300),
                            text = "/start",
                        ),
                )

            val update =
                TelegramUpdate(
                    updateId = 101,
                    message =
                        Message(
                            messageId = 11,
                            chat = Chat(id = 200, type = "private"),
                            fromUser = User(id = 200),
                            text = "/link ABC",
                        ),
                )

            suspend fun runWorkerUntilRedeliveryIsHandled() {
                val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
                val processed = CompletableDeferred<Unit>()
                var delivered = false
                val worker =
                    TelegramLongPollingWorker(
                        getUpdates = { _, _ ->
                            if (delivered) {
                                awaitCancellation()
                            } else {
                                delivered = true
                                listOf(deniedUpdate, update)
                            }
                        },
                        getWebhookUrl = { "" },
                        processUpdate = { incoming ->
                            router.process(incoming)
                            processed.complete(Unit)
                        },
                        trafficPolicy = trafficPolicy,
                        timeoutSeconds = 1,
                        scope = scope,
                    )
                val job = worker.start()
                try {
                    withTimeout(5_000) { processed.await() }
                } finally {
                    job.cancelAndJoin()
                    scope.cancel()
                }
            }

            runWorkerUntilRedeliveryIsHandled()
            runWorkerUntilRedeliveryIsHandled()

            coVerify(exactly = 0) { idempotencyRepository.tryAcquire(100, any(), any()) }
            coVerify(exactly = 2) { idempotencyRepository.tryAcquire(101, 200, 11) }
            coVerify(exactly = 1) {
                outboxEnqueuer.enqueueSendMessage(200, "Эту команду нужно отправить в групповом чате персонала.", any())
            }
        }

    @Test
    fun `database unavailable during idempotency acquire sends safe message`() =
        runBlocking {
            val apiClient: TelegramApiClient = mockk(relaxed = true)
            val outboxEnqueuer: TelegramOutboxEnqueuer = mockk(relaxed = true)
            val idempotencyRepository: IdempotencyRepository = mockk()
            val userRepository: UserRepository = mockk(relaxed = true)
            val tableTokenRepository: TableTokenRepository = mockk()
            val chatContextRepository: ChatContextRepository = mockk()
            val dialogStateRepository: DialogStateRepository = mockk(relaxed = true)
            val ordersRepository: OrdersRepository = mockk()
            val staffCallRepository: StaffCallRepository = mockk()
            val staffChatLinkCodeRepository: StaffChatLinkCodeRepository = mockk()
            val guestBookingRepository: GuestBookingRepository = mockk(relaxed = true)
            val venueRepository: VenueRepository = mockk()
            val venueBookingHoursRepository: VenueBookingHoursRepository = mockk(relaxed = true)
            val venueMenuSectionImagesRepository: VenueMenuSectionImagesRepository = mockk(relaxed = true)
            val venueAccessRepository: VenueAccessRepository = mockk()
            val subscriptionRepository: SubscriptionRepository = mockk()
            val guestMenuRepository: GuestMenuRepository = mockk()
            val tableSessionRepository: TableSessionRepository = mockk(relaxed = true)
            val guestTabsRepository: GuestTabsRepository = mockk(relaxed = true)

            coEvery { idempotencyRepository.tryAcquire(any(), any(), any()) } throws DatabaseUnavailableException()

            val router =
                TelegramBotRouter(
                    trafficPolicy = TelegramTrafficPolicy.unrestricted(),
                    config =
                        TelegramBotConfig(
                            enabled = true,
                            token = "test",
                            mode = TelegramBotConfig.Mode.LONG_POLLING,
                            webhookPath = "/",
                            webhookSecretToken = null,
                            webAppPublicUrl = null,
                            platformOwnerId = null,
                            longPollingTimeoutSeconds = 25,
                            staffChatLinkTtlSeconds = 900,
                            staffChatLinkSecretPepper = "pepper",
                            requireStaffChatAdmin = false,
                        ),
                    apiClient = apiClient,
                    outboxEnqueuer = outboxEnqueuer,
                    idempotencyRepository = idempotencyRepository,
                    userRepository = userRepository,
                    tableTokenRepository = tableTokenRepository,
                    chatContextRepository = chatContextRepository,
                    dialogStateRepository = dialogStateRepository,
                    ordersRepository = ordersRepository,
                    staffCallRepository = staffCallRepository,
                    staffChatLinkCodeRepository = staffChatLinkCodeRepository,
                    guestBookingRepository = guestBookingRepository,
                    venueRepository = venueRepository,
                    venueBookingHoursRepository = venueBookingHoursRepository,
                    venueMenuSectionImagesRepository = venueMenuSectionImagesRepository,
                    venueAccessRepository = venueAccessRepository,
                    subscriptionRepository = subscriptionRepository,
                    guestMenuRepository = guestMenuRepository,
                    tableSessionRepository = tableSessionRepository,
                    guestTabsRepository = guestTabsRepository,
                    tableSessionTtl = Duration.ofHours(2),
                    json = Json { ignoreUnknownKeys = true },
                    scope = CoroutineScope(Dispatchers.Unconfined),
                )

            val update =
                TelegramUpdate(
                    updateId = 202,
                    message =
                        Message(
                            messageId = 12,
                            chat = Chat(id = 201, type = "private"),
                            fromUser = User(id = 301),
                            text = "/start",
                        ),
                )

            router.process(update)

            coVerify { outboxEnqueuer.enqueueSendMessage(201, "База недоступна, попробуйте позже.", any()) }
            coVerify(exactly = 0) { userRepository.upsert(any()) }
        }
}
