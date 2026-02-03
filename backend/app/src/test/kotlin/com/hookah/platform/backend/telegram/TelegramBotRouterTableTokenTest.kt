package com.hookah.platform.backend.telegram

import com.hookah.platform.backend.api.DatabaseUnavailableException
import com.hookah.platform.backend.miniapp.subscription.SubscriptionStatus
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
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class TelegramBotRouterTableTokenTest {
    private val apiClient: TelegramApiClient = mockk(relaxed = true)
    private val idempotencyRepository: IdempotencyRepository = mockk()
    private val userRepository: UserRepository = mockk(relaxed = true)
    private val tableTokenRepository: TableTokenRepository = mockk()
    private val chatContextRepository: ChatContextRepository = mockk()
    private val dialogStateRepository: DialogStateRepository = mockk()
    private val ordersRepository: OrdersRepository = mockk()
    private val staffCallRepository: StaffCallRepository = mockk()
    private val staffChatLinkCodeRepository: StaffChatLinkCodeRepository = mockk()
    private val venueRepository: VenueRepository = mockk()
    private val venueAccessRepository: VenueAccessRepository = mockk()
    private val subscriptionRepository: SubscriptionRepository = mockk()
    private val router = TelegramBotRouter(
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

    @BeforeEach
    fun setup() {
        coEvery { idempotencyRepository.tryAcquire(any(), any(), any()) } returns true
        coEvery { dialogStateRepository.get(any()) } returns DialogState(DialogStateType.NONE)
    }

    @Test
    fun `apply table token does not save context when subscription blocked`() = runBlocking {
        val context = TableContext(
            venueId = 10L,
            venueName = "Venue",
            tableId = 11L,
            tableNumber = 5,
            tableToken = "TOKEN",
            staffChatId = null
        )
        coEvery { tableTokenRepository.resolve("TOKEN") } returns context
        coEvery { subscriptionRepository.getSubscriptionStatus(10L) } returns SubscriptionStatus.SUSPENDED_BY_PLATFORM

        val update = TelegramUpdate(
            updateId = 1,
            message = Message(
                messageId = 11,
                chat = Chat(id = 100, type = "private"),
                fromUser = User(id = 200),
                text = "/start TOKEN"
            )
        )

        router.process(update)

        coVerify { apiClient.sendMessage(100, "Подписка заведения заблокирована. Заказы недоступны.") }
        coVerify(exactly = 0) { chatContextRepository.saveContext(any(), any(), any()) }
    }

    @Test
    fun `apply table token reports database unavailable when resolve fails`() = runBlocking {
        coEvery { tableTokenRepository.resolve("TOKEN") } throws DatabaseUnavailableException()

        val update = TelegramUpdate(
            updateId = 2,
            message = Message(
                messageId = 12,
                chat = Chat(id = 101, type = "private"),
                fromUser = User(id = 201),
                text = "/start TOKEN"
            )
        )

        router.process(update)

        coVerify { apiClient.sendMessage(101, "База недоступна, попробуйте позже.") }
        coVerify(exactly = 0) { chatContextRepository.saveContext(any(), any(), any()) }
    }

    @Test
    fun `resolve guest context reports database unavailable when context load fails`() = runBlocking {
        coEvery { chatContextRepository.get(102) } throws DatabaseUnavailableException()

        val update = TelegramUpdate(
            updateId = 3,
            message = Message(
                messageId = 13,
                chat = Chat(id = 102, type = "private"),
                fromUser = User(id = 202),
                text = "🧾 Активный заказ"
            )
        )

        router.process(update)

        coVerify { apiClient.sendMessage(102, "База недоступна, попробуйте позже.") }
        coVerify(exactly = 0) { tableTokenRepository.resolve(any()) }
    }
}
