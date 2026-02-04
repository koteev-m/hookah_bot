package com.hookah.platform.backend.telegram

import com.hookah.platform.backend.miniapp.subscription.db.SubscriptionRepository
import com.hookah.platform.backend.telegram.db.ChatContextRepository
import com.hookah.platform.backend.telegram.db.DialogStateRepository
import com.hookah.platform.backend.telegram.db.IdempotencyRepository
import com.hookah.platform.backend.telegram.db.OrdersRepository
import com.hookah.platform.backend.telegram.db.StaffChatLinkCodeRepository
import com.hookah.platform.backend.telegram.db.StaffCallRepository
import com.hookah.platform.backend.telegram.db.TableTokenRepository
import com.hookah.platform.backend.telegram.db.UserRepository
import com.hookah.platform.backend.telegram.db.VenueAccessRepository
import com.hookah.platform.backend.telegram.db.VenueRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Test

class TelegramBotRouterIdempotencyTest {
    @Test
    fun `duplicate update id does not repeat side effects`() = runBlocking {
        val apiClient: TelegramApiClient = mockk(relaxed = true)
        val idempotencyRepository: IdempotencyRepository = mockk()
        val userRepository: UserRepository = mockk(relaxed = true)
        val tableTokenRepository: TableTokenRepository = mockk()
        val chatContextRepository: ChatContextRepository = mockk()
        val dialogStateRepository: DialogStateRepository = mockk(relaxed = true)
        val ordersRepository: OrdersRepository = mockk()
        val staffCallRepository: StaffCallRepository = mockk()
        val staffChatLinkCodeRepository: StaffChatLinkCodeRepository = mockk()
        val venueRepository: VenueRepository = mockk()
        val venueAccessRepository: VenueAccessRepository = mockk()
        val subscriptionRepository: SubscriptionRepository = mockk()

        coEvery { idempotencyRepository.tryAcquire(any(), any(), any()) } returnsMany listOf(true, false)

        val router = TelegramBotRouter(
            config = TelegramBotConfig(
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
                requireStaffChatAdmin = false
            ),
            apiClient = apiClient,
            idempotencyRepository = idempotencyRepository,
            userRepository = userRepository,
            tableTokenRepository = tableTokenRepository,
            chatContextRepository = chatContextRepository,
            dialogStateRepository = dialogStateRepository,
            ordersRepository = ordersRepository,
            staffCallRepository = staffCallRepository,
            staffChatLinkCodeRepository = staffChatLinkCodeRepository,
            venueRepository = venueRepository,
            venueAccessRepository = venueAccessRepository,
            subscriptionRepository = subscriptionRepository,
            json = Json { ignoreUnknownKeys = true },
            scope = CoroutineScope(Dispatchers.Unconfined)
        )

        val update = TelegramUpdate(
            updateId = 101,
            message = Message(
                messageId = 11,
                chat = Chat(id = 200, type = "private"),
                fromUser = User(id = 300),
                text = "/link ABC"
            )
        )

        router.process(update)
        router.process(update)

        coVerify(exactly = 1) {
            apiClient.sendMessage(200, "Эту команду нужно отправить в групповом чате персонала.")
        }
    }
}
