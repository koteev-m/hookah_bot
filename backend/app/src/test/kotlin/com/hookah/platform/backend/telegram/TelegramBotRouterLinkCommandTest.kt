package com.hookah.platform.backend.telegram

import com.hookah.platform.backend.telegram.db.ChatContextRepository
import com.hookah.platform.backend.telegram.db.DialogStateRepository
import com.hookah.platform.backend.telegram.db.IdempotencyRepository
import com.hookah.platform.backend.telegram.db.LinkAndBindResult
import com.hookah.platform.backend.telegram.db.OrdersRepository
import com.hookah.platform.backend.telegram.db.StaffChatLinkCodeRepository
import com.hookah.platform.backend.telegram.db.StaffCallRepository
import com.hookah.platform.backend.telegram.db.TableTokenRepository
import com.hookah.platform.backend.telegram.db.UserRepository
import com.hookah.platform.backend.telegram.db.VenueAccessRepository
import com.hookah.platform.backend.telegram.db.VenueRepository
import com.hookah.platform.backend.miniapp.subscription.db.SubscriptionRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class TelegramBotRouterLinkCommandTest {
    private val apiClient: TelegramApiClient = mockk(relaxed = true)
    private val idempotencyRepository: IdempotencyRepository = mockk()
    private val userRepository: UserRepository = mockk(relaxed = true)
    private val tableTokenRepository: TableTokenRepository = mockk()
    private val chatContextRepository: ChatContextRepository = mockk()
    private val dialogStateRepository: DialogStateRepository = mockk(relaxed = true)
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
    }

    @Test
    fun `link command requires group chat`() = runBlocking {
        val update = TelegramUpdate(
            updateId = 1,
            message = Message(
                messageId = 10,
                chat = Chat(id = 200, type = "private"),
                fromUser = User(id = 300),
                text = "/link ABC"
            )
        )

        router.process(update)

        coVerify {
            apiClient.sendMessage(200, "Эту команду нужно отправить в групповом чате персонала.")
        }
    }

    @Test
    fun `link command without code returns usage`() = runBlocking {
        val update = TelegramUpdate(
            updateId = 2,
            message = Message(
                messageId = 11,
                chat = Chat(id = -500, type = "group"),
                fromUser = User(id = 300),
                text = "/link"
            )
        )

        router.process(update)

        coVerify {
            apiClient.sendMessage(-500, "Использование: /link <код>. Код генерируется в режиме заведения.")
        }
    }

    @Test
    fun `link command with bot mention is parsed`() = runBlocking {
        coEvery {
            staffChatLinkCodeRepository.linkAndBindWithCode(
                "GHJK234",
                any(),
                any(),
                any(),
                any(),
                any()
            )
        } returns LinkAndBindResult.InvalidOrExpired

        val update = TelegramUpdate(
            updateId = 3,
            message = Message(
                messageId = 12,
                chat = Chat(id = -700, type = "supergroup"),
                fromUser = User(id = 300),
                text = "/link@TestBot ghjk234"
            )
        )

        router.process(update)

        coVerify {
            apiClient.sendMessage(-700, "Код недействителен или истёк. Сгенерируйте новый в режиме заведения.")
        }
        coVerify { staffChatLinkCodeRepository.linkAndBindWithCode("GHJK234", any(), any(), any(), any(), any()) }
    }

    @Test
    fun `link command is idempotent for same venue`() = runBlocking {
        coEvery {
            staffChatLinkCodeRepository.linkAndBindWithCode(any(), any(), any(), any(), any(), any())
        } returns LinkAndBindResult.AlreadyBoundSameChat(42L, "Venue")

        val update = TelegramUpdate(
            updateId = 4,
            message = Message(
                messageId = 13,
                chat = Chat(id = -700, type = "supergroup"),
                fromUser = User(id = 300),
                text = "/link SAMEVENUE"
            )
        )

        router.process(update)

        coVerify { apiClient.sendMessage(-700, "Этот чат уже привязан к заведению Venue.") }
    }

    @Test
    fun `link command uppercases code`() = runBlocking {
        coEvery {
            staffChatLinkCodeRepository.linkAndBindWithCode("GHJK234", any(), any(), any(), any(), any())
        } returns LinkAndBindResult.Success(venueId = 1L, venueName = "Venue")

        val update = TelegramUpdate(
            updateId = 5,
            message = Message(
                messageId = 14,
                chat = Chat(id = -700, type = "supergroup"),
                fromUser = User(id = 300),
                text = "/link ghjk234"
            )
        )

        router.process(update)

        coVerify {
            apiClient.sendMessage(
                -700,
                "✅ Чат привязан к заведению Venue. Уведомления о заказах будут приходить сюда."
            )
        }
    }

    @Test
    fun `link command rejects overly long code`() = runBlocking {
        val longCode = "A".repeat(80)
        val update = TelegramUpdate(
            updateId = 6,
            message = Message(
                messageId = 15,
                chat = Chat(id = -700, type = "supergroup"),
                fromUser = User(id = 300),
                text = "/link $longCode"
            )
        )

        router.process(update)

        coVerify { apiClient.sendMessage(-700, "Код недействителен или истёк. Сгенерируйте новый в режиме заведения.") }
        coVerify(exactly = 0) { staffChatLinkCodeRepository.linkAndBindWithCode(any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `link command rejects code with invalid characters`() = runBlocking {
        val update = TelegramUpdate(
            updateId = 7,
            message = Message(
                messageId = 16,
                chat = Chat(id = -701, type = "group"),
                fromUser = User(id = 301),
                text = "/link ABCO1"
            )
        )

        router.process(update)

        coVerify { apiClient.sendMessage(-701, "Код недействителен или истёк. Сгенерируйте новый в режиме заведения.") }
        coVerify(exactly = 0) { staffChatLinkCodeRepository.linkAndBindWithCode(any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `link command rejects unicode code`() = runBlocking {
        val update = TelegramUpdate(
            updateId = 8,
            message = Message(
                messageId = 17,
                chat = Chat(id = -702, type = "group"),
                fromUser = User(id = 302),
                text = "/link ßßß"
            )
        )

        router.process(update)

        coVerify { apiClient.sendMessage(-702, "Код недействителен или истёк. Сгенерируйте новый в режиме заведения.") }
        coVerify(exactly = 0) { staffChatLinkCodeRepository.linkAndBindWithCode(any(), any(), any(), any(), any(), any()) }
    }
}
