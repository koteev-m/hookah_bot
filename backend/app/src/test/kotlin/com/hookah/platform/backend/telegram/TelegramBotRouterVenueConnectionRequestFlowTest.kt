package com.hookah.platform.backend.telegram

import com.hookah.platform.backend.miniapp.guest.db.GuestBookingRepository
import com.hookah.platform.backend.miniapp.guest.db.GuestMenuRepository
import com.hookah.platform.backend.miniapp.guest.db.GuestTabsRepository
import com.hookah.platform.backend.miniapp.guest.db.MenuCategoryModel
import com.hookah.platform.backend.miniapp.guest.db.MenuItemModel
import com.hookah.platform.backend.miniapp.guest.db.MenuModel
import com.hookah.platform.backend.miniapp.guest.db.TableSessionRepository
import com.hookah.platform.backend.miniapp.subscription.db.SubscriptionRepository
import com.hookah.platform.backend.miniapp.venue.VenueStatus
import com.hookah.platform.backend.platform.PlatformOwnerAssignmentResult
import com.hookah.platform.backend.platform.PlatformVenueDetail
import com.hookah.platform.backend.platform.PlatformVenueMember
import com.hookah.platform.backend.platform.PlatformVenueMemberRepository
import com.hookah.platform.backend.platform.PlatformVenueRepository
import com.hookah.platform.backend.platform.VenueStatusAction
import com.hookah.platform.backend.platform.VenueStatusChangeResult
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
import com.hookah.platform.backend.telegram.db.VenueConnectionRequestRecord
import com.hookah.platform.backend.telegram.db.VenueConnectionRequestRepository
import com.hookah.platform.backend.telegram.db.VenueMenuSectionImagesRepository
import com.hookah.platform.backend.telegram.db.VenueRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant

class TelegramBotRouterVenueConnectionRequestFlowTest {
    @Test
    fun `add venue flow creates connection request and sends confirmation`() =
        runBlocking {
            val apiClient: TelegramApiClient = mockk(relaxed = true)
            val outboxEnqueuer: TelegramOutboxEnqueuer = mockk(relaxed = true)
            val idempotencyRepository: IdempotencyRepository = mockk()
            val userRepository: UserRepository = mockk(relaxed = true)
            val tableTokenRepository: TableTokenRepository = mockk()
            val chatContextRepository: ChatContextRepository = mockk(relaxed = true)
            val dialogStateRepository: DialogStateRepository = mockk()
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
            val venueConnectionRequestRepository: VenueConnectionRequestRepository = mockk()

            coEvery { idempotencyRepository.tryAcquire(any(), any(), any()) } returns true
            val states = mutableMapOf<Long, DialogState>()
            coEvery { dialogStateRepository.get(any()) } answers {
                states[firstArg<Long>()] ?: DialogState(DialogStateType.NONE)
            }
            coEvery { dialogStateRepository.set(any(), any()) } answers {
                states[firstArg()] = secondArg()
                Unit
            }
            coEvery { dialogStateRepository.clear(any()) } answers {
                states.remove(firstArg<Long>())
                Unit
            }
            coEvery { venueConnectionRequestRepository.findPendingByUser(any()) } returns null
            coEvery {
                venueConnectionRequestRepository.createRequest(
                    telegramUserId = 501L,
                    venueName = "Дым и Лёд",
                    city = "Москва",
                    contact = "@smoke_owner",
                    comment = null,
                )
            } returns 1001L

            val router =
                TelegramBotRouter(
                    config =
                        TelegramBotConfig(
                            enabled = true,
                            token = "test",
                            mode = TelegramBotConfig.Mode.LONG_POLLING,
                            webhookPath = "/",
                            webhookSecretToken = null,
                            webAppPublicUrl = null,
                            platformOwnerId = 999L,
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
                    venueConnectionRequestRepository = venueConnectionRequestRepository,
                    scope = CoroutineScope(Dispatchers.Unconfined),
                )

            val chatId = 700L
            val from = User(id = 501L)

            router.process(
                TelegramUpdate(
                    updateId = 1,
                    message = Message(messageId = 1, chat = Chat(chatId, "private"), fromUser = from, text = "🤝 Добавить свою кальянную"),
                ),
            )
            router.process(
                TelegramUpdate(
                    updateId = 2,
                    message = Message(messageId = 2, chat = Chat(chatId, "private"), fromUser = from, text = "Дым и Лёд"),
                ),
            )
            router.process(
                TelegramUpdate(
                    updateId = 3,
                    message = Message(messageId = 3, chat = Chat(chatId, "private"), fromUser = from, text = "Москва"),
                ),
            )
            router.process(
                TelegramUpdate(
                    updateId = 4,
                    message = Message(messageId = 4, chat = Chat(chatId, "private"), fromUser = from, text = "@smoke_owner"),
                ),
            )
            router.process(
                TelegramUpdate(
                    updateId = 5,
                    message = Message(messageId = 5, chat = Chat(chatId, "private"), fromUser = from, text = "-"),
                ),
            )

            coVerify {
                venueConnectionRequestRepository.createRequest(
                    telegramUserId = 501L,
                    venueName = "Дым и Лёд",
                    city = "Москва",
                    contact = "@smoke_owner",
                    comment = null,
                )
            }
            coVerify {
                outboxEnqueuer.enqueueSendMessage(chatId, "✅ Заявка отправлена. Мы свяжемся с вами.", any())
            }
        }

    @Test
    fun `add venue flow with username can keep telegram contact only`() =
        runBlocking {
            val apiClient: TelegramApiClient = mockk(relaxed = true)
            val outboxEnqueuer: TelegramOutboxEnqueuer = mockk(relaxed = true)
            val idempotencyRepository: IdempotencyRepository = mockk()
            val userRepository: UserRepository = mockk(relaxed = true)
            val tableTokenRepository: TableTokenRepository = mockk()
            val chatContextRepository: ChatContextRepository = mockk(relaxed = true)
            val dialogStateRepository: DialogStateRepository = mockk()
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
            val venueConnectionRequestRepository: VenueConnectionRequestRepository = mockk()

            coEvery { idempotencyRepository.tryAcquire(any(), any(), any()) } returns true
            val states = mutableMapOf<Long, DialogState>()
            coEvery { dialogStateRepository.get(any()) } answers {
                states[firstArg<Long>()] ?: DialogState(DialogStateType.NONE)
            }
            coEvery { dialogStateRepository.set(any(), any()) } answers {
                states[firstArg()] = secondArg()
                Unit
            }
            coEvery { dialogStateRepository.clear(any()) } answers {
                states.remove(firstArg<Long>())
                Unit
            }
            coEvery { venueConnectionRequestRepository.findPendingByUser(any()) } returns null
            coEvery {
                venueConnectionRequestRepository.createRequest(
                    telegramUserId = 601L,
                    venueName = "Lounge X",
                    city = "Москва",
                    contact = "@ownerx",
                    comment = null,
                )
            } returns 2001L

            val router =
                TelegramBotRouter(
                    config =
                        TelegramBotConfig(
                            enabled = true,
                            token = "test",
                            mode = TelegramBotConfig.Mode.LONG_POLLING,
                            webhookPath = "/",
                            webhookSecretToken = null,
                            webAppPublicUrl = null,
                            platformOwnerId = 999L,
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
                    venueConnectionRequestRepository = venueConnectionRequestRepository,
                    scope = CoroutineScope(Dispatchers.Unconfined),
                )

            val chatId = 702L
            val from = User(id = 601L, username = "ownerx")

            router.process(
                TelegramUpdate(
                    updateId = 20,
                    message = Message(messageId = 20, chat = Chat(chatId, "private"), fromUser = from, text = "🤝 Добавить свою кальянную"),
                ),
            )
            router.process(
                TelegramUpdate(
                    updateId = 21,
                    message = Message(messageId = 21, chat = Chat(chatId, "private"), fromUser = from, text = "Lounge X"),
                ),
            )
            router.process(
                TelegramUpdate(
                    updateId = 22,
                    message = Message(messageId = 22, chat = Chat(chatId, "private"), fromUser = from, text = "Москва"),
                ),
            )

            coVerify {
                outboxEnqueuer.enqueueSendMessage(
                    chatId,
                    "Контакт для связи: @ownerx\nХотите добавить дополнительный контакт?",
                    match {
                        it is InlineKeyboardMarkup &&
                            it.inlineKeyboard.flatten().any { button ->
                                button.callbackData == "venue_connect_contact_use_username"
                            }
                    },
                )
            }

            router.process(
                TelegramUpdate(
                    updateId = 23,
                    callbackQuery =
                        CallbackQuery(
                            id = "cb-use-username",
                            from = from,
                            message = Message(messageId = 23, chat = Chat(chatId, "private")),
                            data = "venue_connect_contact_use_username",
                        ),
                ),
            )
            router.process(
                TelegramUpdate(
                    updateId = 24,
                    message = Message(messageId = 24, chat = Chat(chatId, "private"), fromUser = from, text = "-"),
                ),
            )

            coVerify {
                venueConnectionRequestRepository.createRequest(
                    telegramUserId = 601L,
                    venueName = "Lounge X",
                    city = "Москва",
                    contact = "@ownerx",
                    comment = null,
                )
            }
        }

    @Test
    fun `add venue flow with username can append manual contact`() =
        runBlocking {
            val apiClient: TelegramApiClient = mockk(relaxed = true)
            val outboxEnqueuer: TelegramOutboxEnqueuer = mockk(relaxed = true)
            val idempotencyRepository: IdempotencyRepository = mockk()
            val userRepository: UserRepository = mockk(relaxed = true)
            val tableTokenRepository: TableTokenRepository = mockk()
            val chatContextRepository: ChatContextRepository = mockk(relaxed = true)
            val dialogStateRepository: DialogStateRepository = mockk()
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
            val venueConnectionRequestRepository: VenueConnectionRequestRepository = mockk()

            coEvery { idempotencyRepository.tryAcquire(any(), any(), any()) } returns true
            val states = mutableMapOf<Long, DialogState>()
            coEvery { dialogStateRepository.get(any()) } answers {
                states[firstArg<Long>()] ?: DialogState(DialogStateType.NONE)
            }
            coEvery { dialogStateRepository.set(any(), any()) } answers {
                states[firstArg()] = secondArg()
                Unit
            }
            coEvery { dialogStateRepository.clear(any()) } answers {
                states.remove(firstArg<Long>())
                Unit
            }
            coEvery { venueConnectionRequestRepository.findPendingByUser(any()) } returns null
            coEvery {
                venueConnectionRequestRepository.createRequest(
                    telegramUserId = 602L,
                    venueName = "Lounge Y",
                    city = "Сочи",
                    contact = "@owner_y | +7 999 000-00-00",
                    comment = null,
                )
            } returns 2002L

            val router =
                TelegramBotRouter(
                    config =
                        TelegramBotConfig(
                            enabled = true,
                            token = "test",
                            mode = TelegramBotConfig.Mode.LONG_POLLING,
                            webhookPath = "/",
                            webhookSecretToken = null,
                            webAppPublicUrl = null,
                            platformOwnerId = 999L,
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
                    venueConnectionRequestRepository = venueConnectionRequestRepository,
                    scope = CoroutineScope(Dispatchers.Unconfined),
                )

            val chatId = 703L
            val from = User(id = 602L, username = "owner_y")

            router.process(
                TelegramUpdate(
                    updateId = 30,
                    message = Message(messageId = 30, chat = Chat(chatId, "private"), fromUser = from, text = "🤝 Добавить свою кальянную"),
                ),
            )
            router.process(
                TelegramUpdate(
                    updateId = 31,
                    message = Message(messageId = 31, chat = Chat(chatId, "private"), fromUser = from, text = "Lounge Y"),
                ),
            )
            router.process(
                TelegramUpdate(
                    updateId = 32,
                    message = Message(messageId = 32, chat = Chat(chatId, "private"), fromUser = from, text = "Сочи"),
                ),
            )
            router.process(
                TelegramUpdate(
                    updateId = 33,
                    callbackQuery =
                        CallbackQuery(
                            id = "cb-add-contact",
                            from = from,
                            message = Message(messageId = 33, chat = Chat(chatId, "private")),
                            data = "venue_connect_contact_additional",
                        ),
                ),
            )
            router.process(
                TelegramUpdate(
                    updateId = 34,
                    message =
                        Message(
                            messageId = 34,
                            chat = Chat(chatId, "private"),
                            fromUser = from,
                            text = "+7 999 000-00-00",
                        ),
                ),
            )
            router.process(
                TelegramUpdate(
                    updateId = 35,
                    message = Message(messageId = 35, chat = Chat(chatId, "private"), fromUser = from, text = "-"),
                ),
            )

            coVerify {
                venueConnectionRequestRepository.createRequest(
                    telegramUserId = 602L,
                    venueName = "Lounge Y",
                    city = "Сочи",
                    contact = "@owner_y | +7 999 000-00-00",
                    comment = null,
                )
            }
        }

    @Test
    fun `add venue flow shows existing pending request and blocks new one`() =
        runBlocking {
            val apiClient: TelegramApiClient = mockk(relaxed = true)
            val outboxEnqueuer: TelegramOutboxEnqueuer = mockk(relaxed = true)
            val idempotencyRepository: IdempotencyRepository = mockk()
            val userRepository: UserRepository = mockk(relaxed = true)
            val tableTokenRepository: TableTokenRepository = mockk()
            val chatContextRepository: ChatContextRepository = mockk(relaxed = true)
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
            val venueConnectionRequestRepository: VenueConnectionRequestRepository = mockk()

            coEvery { idempotencyRepository.tryAcquire(any(), any(), any()) } returns true
            coEvery { dialogStateRepository.get(any()) } returns DialogState(DialogStateType.NONE)
            coEvery {
                venueConnectionRequestRepository.findPendingByUser(7010L)
            } returns
                VenueConnectionRequestRecord(
                    id = 4001L,
                    telegramUserId = 7010L,
                    venueName = "Smoke Place",
                    city = "Москва",
                    contact = "@owner7010",
                    comment = null,
                    status = "PENDING",
                    createdAt = Instant.parse("2026-04-03T12:30:00Z"),
                )

            val router =
                TelegramBotRouter(
                    config =
                        TelegramBotConfig(
                            enabled = true,
                            token = "test",
                            mode = TelegramBotConfig.Mode.LONG_POLLING,
                            webhookPath = "/",
                            webhookSecretToken = null,
                            webAppPublicUrl = null,
                            platformOwnerId = 999L,
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
                    venueConnectionRequestRepository = venueConnectionRequestRepository,
                    scope = CoroutineScope(Dispatchers.Unconfined),
                )

            val chatId = 704L
            val from = User(id = 7010L, username = "owner7010")
            router.process(
                TelegramUpdate(
                    updateId = 40,
                    message =
                        Message(
                            messageId = 40,
                            chat = Chat(chatId, "private"),
                            fromUser = from,
                            text = "🤝 Добавить свою кальянную",
                        ),
                ),
            )

            coVerify(exactly = 0) {
                venueConnectionRequestRepository.createRequest(any(), any(), any(), any(), any())
            }
            coVerify {
                outboxEnqueuer.enqueueSendMessage(
                    chatId,
                    match { it.contains("У вас уже есть активная заявка.") && it.contains("Статус: На рассмотрении") },
                    match {
                        it is InlineKeyboardMarkup &&
                            it.inlineKeyboard.flatten().any { button ->
                                button.callbackData == "venue_connect_pending_edit:4001"
                            }
                    },
                )
            }
        }

    @Test
    fun `applicant can cancel pending request`() =
        runBlocking {
            val apiClient: TelegramApiClient = mockk(relaxed = true)
            val outboxEnqueuer: TelegramOutboxEnqueuer = mockk(relaxed = true)
            val idempotencyRepository: IdempotencyRepository = mockk()
            val userRepository: UserRepository = mockk(relaxed = true)
            val tableTokenRepository: TableTokenRepository = mockk()
            val chatContextRepository: ChatContextRepository = mockk(relaxed = true)
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
            val venueConnectionRequestRepository: VenueConnectionRequestRepository = mockk()

            coEvery { idempotencyRepository.tryAcquire(any(), any(), any()) } returns true
            coEvery {
                venueConnectionRequestRepository.cancelPendingRequest(5001L, 7011L)
            } returns true

            val router =
                TelegramBotRouter(
                    config =
                        TelegramBotConfig(
                            enabled = true,
                            token = "test",
                            mode = TelegramBotConfig.Mode.LONG_POLLING,
                            webhookPath = "/",
                            webhookSecretToken = null,
                            webAppPublicUrl = null,
                            platformOwnerId = 999L,
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
                    venueConnectionRequestRepository = venueConnectionRequestRepository,
                    scope = CoroutineScope(Dispatchers.Unconfined),
                )

            val chatId = 705L
            val from = User(id = 7011L)
            router.process(
                TelegramUpdate(
                    updateId = 41,
                    callbackQuery =
                        CallbackQuery(
                            id = "cb-cancel",
                            from = from,
                            message = Message(messageId = 41, chat = Chat(chatId, "private")),
                            data = "venue_connect_pending_cancel:5001",
                        ),
                ),
            )

            coVerify { venueConnectionRequestRepository.cancelPendingRequest(5001L, 7011L) }
            coVerify { outboxEnqueuer.enqueueSendMessage(chatId, "✅ Заявка отменена.", any()) }
        }

    @Test
    fun `platform owner can approve pending request`() =
        runBlocking {
            val apiClient: TelegramApiClient = mockk(relaxed = true)
            val outboxEnqueuer: TelegramOutboxEnqueuer = mockk(relaxed = true)
            val idempotencyRepository: IdempotencyRepository = mockk()
            val userRepository: UserRepository = mockk(relaxed = true)
            val tableTokenRepository: TableTokenRepository = mockk()
            val chatContextRepository: ChatContextRepository = mockk(relaxed = true)
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
            val venueConnectionRequestRepository: VenueConnectionRequestRepository = mockk()

            coEvery { idempotencyRepository.tryAcquire(any(), any(), any()) } returns true
            coEvery {
                venueConnectionRequestRepository.setStatusByOwner(
                    6001L,
                    VenueConnectionRequestRepository.STATUS_APPROVED,
                )
            } returns true

            val router =
                TelegramBotRouter(
                    config =
                        TelegramBotConfig(
                            enabled = true,
                            token = "test",
                            mode = TelegramBotConfig.Mode.LONG_POLLING,
                            webhookPath = "/",
                            webhookSecretToken = null,
                            webAppPublicUrl = null,
                            platformOwnerId = 999L,
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
                    venueConnectionRequestRepository = venueConnectionRequestRepository,
                    scope = CoroutineScope(Dispatchers.Unconfined),
                )

            val chatId = 706L
            val owner = User(id = 999L)
            router.process(
                TelegramUpdate(
                    updateId = 42,
                    callbackQuery =
                        CallbackQuery(
                            id = "cb-approve",
                            from = owner,
                            message = Message(messageId = 42, chat = Chat(chatId, "private")),
                            data = "owner_venue_connect_approve:6001",
                        ),
                ),
            )

            coVerify {
                venueConnectionRequestRepository.setStatusByOwner(
                    6001L,
                    VenueConnectionRequestRepository.STATUS_APPROVED,
                )
            }
            coVerify {
                outboxEnqueuer.enqueueSendMessage(
                    chatId,
                    "✅ Заявка принята.\nСледующий шаг: коммерческие условия.",
                    match {
                        it is InlineKeyboardMarkup &&
                            it.inlineKeyboard.flatten().any { button ->
                                button.callbackData == "owner_venue_terms_open:6001"
                            } &&
                            it.inlineKeyboard.flatten().any { button ->
                                button.callbackData == "owner_venue_requests_back"
                            }
                    },
                )
            }
        }

    @Test
    fun `platform owner can open create venue entry from approved request action`() =
        runBlocking {
            val apiClient: TelegramApiClient = mockk(relaxed = true)
            val outboxEnqueuer: TelegramOutboxEnqueuer = mockk(relaxed = true)
            val idempotencyRepository: IdempotencyRepository = mockk()
            val userRepository: UserRepository = mockk(relaxed = true)
            val tableTokenRepository: TableTokenRepository = mockk()
            val chatContextRepository: ChatContextRepository = mockk(relaxed = true)
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
            val venueConnectionRequestRepository: VenueConnectionRequestRepository = mockk(relaxed = true)
            val platformVenueRepository: PlatformVenueRepository = mockk()
            val platformVenueMemberRepository: PlatformVenueMemberRepository = mockk()

            coEvery { idempotencyRepository.tryAcquire(any(), any(), any()) } returns true
            coEvery { venueConnectionRequestRepository.findById(7001L) } returns
                VenueConnectionRequestRecord(
                    id = 7001L,
                    telegramUserId = 7011L,
                    venueName = "Новая кальянная",
                    city = "Москва",
                    contact = "@new_owner",
                    comment = "Комментарий",
                    status = VenueConnectionRequestRepository.STATUS_APPROVED,
                    createdAt = Instant.parse("2026-04-03T12:30:00Z"),
                    linkedVenueId = null,
                    trialConfigured = true,
                    trialEndsOn = null,
                    currentPriceRub = 10000L,
                    futurePriceRub = null,
                    futurePriceEffectiveOn = null,
                    commercialNote = null,
                )
            coEvery {
                platformVenueRepository.createVenue(
                    name = "Новая кальянная",
                    city = "Москва",
                    address = null,
                )
            } returns
                PlatformVenueDetail(
                    id = 9001L,
                    name = "Новая кальянная",
                    city = "Москва",
                    address = null,
                    status = VenueStatus.DRAFT,
                    createdAt = Instant.parse("2026-04-03T12:31:00Z"),
                    deletedAt = null,
                )
            coEvery {
                platformVenueMemberRepository.assignOwner(
                    venueId = 9001L,
                    userId = 7011L,
                    role = "OWNER",
                    invitedByUserId = 999L,
                )
            } returns
                PlatformOwnerAssignmentResult.Success(
                    member =
                        PlatformVenueMember(
                            venueId = 9001L,
                            userId = 7011L,
                            role = "OWNER",
                            createdAt = Instant.parse("2026-04-03T12:31:00Z"),
                            invitedByUserId = 999L,
                        ),
                    alreadyMember = false,
                )
            coEvery {
                venueConnectionRequestRepository.linkApprovedRequestToVenue(
                    requestId = 7001L,
                    venueId = 9001L,
                )
            } returns true

            val router =
                TelegramBotRouter(
                    config =
                        TelegramBotConfig(
                            enabled = true,
                            token = "test",
                            mode = TelegramBotConfig.Mode.LONG_POLLING,
                            webhookPath = "/",
                            webhookSecretToken = null,
                            webAppPublicUrl = null,
                            platformOwnerId = 999L,
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
                    platformVenueRepository = platformVenueRepository,
                    platformVenueMemberRepository = platformVenueMemberRepository,
                    tableSessionTtl = Duration.ofHours(2),
                    json = Json { ignoreUnknownKeys = true },
                    venueConnectionRequestRepository = venueConnectionRequestRepository,
                    scope = CoroutineScope(Dispatchers.Unconfined),
                )

            val chatId = 707L
            val owner = User(id = 999L)
            router.process(
                TelegramUpdate(
                    updateId = 43,
                    callbackQuery =
                        CallbackQuery(
                            id = "cb-owner-create-venue",
                            from = owner,
                            message = Message(messageId = 43, chat = Chat(chatId, "private")),
                            data = "owner_venue_create_from_request:7001",
                        ),
                ),
            )

            coVerify {
                platformVenueRepository.createVenue(
                    name = "Новая кальянная",
                    city = "Москва",
                    address = null,
                )
            }
            coVerify {
                platformVenueMemberRepository.assignOwner(
                    venueId = 9001L,
                    userId = 7011L,
                    role = "OWNER",
                    invitedByUserId = 999L,
                )
            }
            coVerify {
                venueConnectionRequestRepository.linkApprovedRequestToVenue(
                    requestId = 7001L,
                    venueId = 9001L,
                )
            }
            coVerify {
                outboxEnqueuer.enqueueSendMessage(
                    7011L,
                    "✅ Ваша заявка одобрена.\nТеперь вы можете настроить свою кальянную.",
                    match {
                        it is InlineKeyboardMarkup &&
                            it.inlineKeyboard.flatten().any { button ->
                                button.callbackData == "owner_venue_onboarding_entry"
                            }
                    },
                )
            }
            coVerify {
                outboxEnqueuer.enqueueSendMessage(
                    chatId,
                    "✅ Черновик кальянной создан: #9001\nВладелец назначен: 7011\nДоступ передан заявителю.",
                    null,
                )
            }
        }

    @Test
    fun `venue owner can open publish readiness screen and publish venue when checklist is complete`() =
        runBlocking {
            val apiClient: TelegramApiClient = mockk(relaxed = true)
            val outboxEnqueuer: TelegramOutboxEnqueuer = mockk(relaxed = true)
            val idempotencyRepository: IdempotencyRepository = mockk()
            val userRepository: UserRepository = mockk(relaxed = true)
            val tableTokenRepository: TableTokenRepository = mockk()
            val chatContextRepository: ChatContextRepository = mockk(relaxed = true)
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
            val venueConnectionRequestRepository: VenueConnectionRequestRepository = mockk(relaxed = true)
            val platformVenueRepository: PlatformVenueRepository = mockk()

            coEvery { idempotencyRepository.tryAcquire(any(), any(), any()) } returns true
            coEvery { dialogStateRepository.get(any()) } returns DialogState(DialogStateType.NONE)
            coEvery { dialogStateRepository.clear(any()) } returns Unit
            coEvery { dialogStateRepository.set(any(), any()) } returns Unit
            coEvery { venueAccessRepository.hasVenueAdminOrOwner(8001L, 9101L) } returns true

            val draftDetail =
                PlatformVenueDetail(
                    id = 9101L,
                    name = "Owner Lounge",
                    city = "Москва",
                    address = "Арбат, 10",
                    status = VenueStatus.DRAFT,
                    createdAt = Instant.parse("2026-04-05T10:00:00Z"),
                    deletedAt = null,
                )
            val publishedDetail = draftDetail.copy(status = VenueStatus.PUBLISHED)
            coEvery { platformVenueRepository.getVenueDetail(9101L) } returnsMany
                listOf(
                    draftDetail,
                    draftDetail,
                    publishedDetail,
                    publishedDetail,
                )

            coEvery { venueConnectionRequestRepository.findApprovedByLinkedVenue(9101L) } returns
                VenueConnectionRequestRecord(
                    id = 8101L,
                    telegramUserId = 8001L,
                    venueName = "Owner Lounge",
                    city = "Москва",
                    contact = "@owner_lounge",
                    comment = "Уютная атмосфера и авторские миксы.",
                    status = VenueConnectionRequestRepository.STATUS_APPROVED,
                    createdAt = Instant.parse("2026-04-05T09:50:00Z"),
                    linkedVenueId = 9101L,
                    trialConfigured = true,
                    trialEndsOn = null,
                    currentPriceRub = 10000L,
                    futurePriceRub = null,
                    futurePriceEffectiveOn = null,
                    commercialNote = null,
                )
            coEvery { venueBookingHoursRepository.hasConfiguredHours(9101L) } returns true
            coEvery { guestMenuRepository.getMenu(9101L) } returns
                MenuModel(
                    venueId = 9101L,
                    categories =
                        listOf(
                            MenuCategoryModel(
                                id = 1L,
                                name = "Кальяны",
                                sortOrder = 0,
                                items =
                                    listOf(
                                        MenuItemModel(
                                            id = 11L,
                                            name = "Премиум кальян",
                                            priceMinor = 250000L,
                                            currency = "RUB",
                                            isAvailable = true,
                                            sortOrder = 0,
                                        ),
                                    ),
                            ),
                        ),
                )
            coEvery {
                platformVenueRepository.updateStatus(9101L, VenueStatusAction.PUBLISH)
            } returns
                VenueStatusChangeResult.Success(
                    venue = publishedDetail,
                    fromStatus = VenueStatus.DRAFT,
                    toStatus = VenueStatus.PUBLISHED,
                )

            val router =
                TelegramBotRouter(
                    config =
                        TelegramBotConfig(
                            enabled = true,
                            token = "test",
                            mode = TelegramBotConfig.Mode.LONG_POLLING,
                            webhookPath = "/",
                            webhookSecretToken = null,
                            webAppPublicUrl = null,
                            platformOwnerId = 999L,
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
                    platformVenueRepository = platformVenueRepository,
                    tableSessionTtl = Duration.ofHours(2),
                    json = Json { ignoreUnknownKeys = true },
                    venueConnectionRequestRepository = venueConnectionRequestRepository,
                    scope = CoroutineScope(Dispatchers.Unconfined),
                )

            val chatId = 1701L
            val owner = User(id = 8001L)

            router.process(
                TelegramUpdate(
                    updateId = 91,
                    callbackQuery =
                        CallbackQuery(
                            id = "cb-readiness",
                            from = owner,
                            message = Message(messageId = 91, chat = Chat(chatId, "private")),
                            data = "owner_venue_publish_readiness:9101",
                        ),
                ),
            )

            coVerify {
                outboxEnqueuer.enqueueSendMessage(
                    chatId,
                    match { text ->
                        text.contains("✅ Готовность к публикации") &&
                            text.contains("Статус: Draft") &&
                            text.contains("✅ Название") &&
                            text.contains("✅ Адрес")
                    },
                    match {
                        it is InlineKeyboardMarkup &&
                            it.inlineKeyboard.flatten().any { button ->
                                button.callbackData == "owner_venue_publish_confirm:9101"
                            } &&
                            it.inlineKeyboard.flatten().any { button ->
                                button.callbackData == "owner_venue_publish_preview:9101"
                            }
                    },
                )
            }

            router.process(
                TelegramUpdate(
                    updateId = 92,
                    callbackQuery =
                        CallbackQuery(
                            id = "cb-publish",
                            from = owner,
                            message = Message(messageId = 92, chat = Chat(chatId, "private")),
                            data = "owner_venue_publish_confirm:9101",
                        ),
                ),
            )

            coVerify { platformVenueRepository.updateStatus(9101L, VenueStatusAction.PUBLISH) }
            coVerify { outboxEnqueuer.enqueueSendMessage(chatId, "✅ Кальянная опубликована.", null) }
        }

    @Test
    fun `owner can view new venue connection requests`() =
        runBlocking {
            val apiClient: TelegramApiClient = mockk(relaxed = true)
            val outboxEnqueuer: TelegramOutboxEnqueuer = mockk(relaxed = true)
            val idempotencyRepository: IdempotencyRepository = mockk()
            val userRepository: UserRepository = mockk(relaxed = true)
            val tableTokenRepository: TableTokenRepository = mockk()
            val chatContextRepository: ChatContextRepository = mockk(relaxed = true)
            val dialogStateRepository: DialogStateRepository = mockk()
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
            val venueConnectionRequestRepository: VenueConnectionRequestRepository = mockk()

            coEvery { idempotencyRepository.tryAcquire(any(), any(), any()) } returns true
            coEvery { dialogStateRepository.get(any()) } returns DialogState(DialogStateType.NONE)
            coEvery { dialogStateRepository.set(any(), any()) } returns Unit
            coEvery { dialogStateRepository.clear(any()) } returns Unit
            coEvery {
                venueConnectionRequestRepository.listPendingRequests(limit = 20)
            } returns
                listOf(
                    VenueConnectionRequestRecord(
                        id = 101L,
                        telegramUserId = 501L,
                        venueName = "Дым и Лёд",
                        city = "Москва",
                        contact = "@smoke_owner",
                        comment = "Есть летняя веранда",
                        status = "PENDING",
                        createdAt = Instant.parse("2026-04-03T12:30:00Z"),
                    ),
                )

            val router =
                TelegramBotRouter(
                    config =
                        TelegramBotConfig(
                            enabled = true,
                            token = "test",
                            mode = TelegramBotConfig.Mode.LONG_POLLING,
                            webhookPath = "/",
                            webhookSecretToken = null,
                            webAppPublicUrl = null,
                            platformOwnerId = 999L,
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
                    venueConnectionRequestRepository = venueConnectionRequestRepository,
                    scope = CoroutineScope(Dispatchers.Unconfined),
                )

            val chatId = 701L
            router.process(
                TelegramUpdate(
                    updateId = 10,
                    message =
                        Message(
                            messageId = 11,
                            chat = Chat(chatId, "private"),
                            fromUser = User(id = 999L),
                            text = "📨 Заявки на подключение",
                        ),
                ),
            )

            coVerify { outboxEnqueuer.enqueueSendMessage(chatId, "📨 Заявки на подключение", any()) }
            coVerify {
                outboxEnqueuer.enqueueSendMessage(
                    chatId,
                    match { text ->
                        text.contains("Заявка #101") &&
                            text.contains("Название: Дым и Лёд") &&
                            text.contains("Город: Москва") &&
                            text.contains("Контакт: @smoke_owner") &&
                            text.contains("Комментарий: Есть летняя веранда") &&
                            text.contains("Статус: На рассмотрении") &&
                            text.contains("Дата:")
                    },
                    any(),
                )
            }
        }
}
