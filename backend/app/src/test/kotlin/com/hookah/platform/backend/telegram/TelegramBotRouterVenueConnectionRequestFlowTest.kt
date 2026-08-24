package com.hookah.platform.backend.telegram

import com.hookah.platform.backend.miniapp.guest.db.GuestBookingRepository
import com.hookah.platform.backend.miniapp.guest.db.GuestMenuRepository
import com.hookah.platform.backend.miniapp.guest.db.GuestTabsRepository
import com.hookah.platform.backend.miniapp.guest.db.MenuCategoryModel
import com.hookah.platform.backend.miniapp.guest.db.MenuItemModel
import com.hookah.platform.backend.miniapp.guest.db.MenuModel
import com.hookah.platform.backend.miniapp.guest.db.TableSessionRepository
import com.hookah.platform.backend.miniapp.subscription.SubscriptionStatus
import com.hookah.platform.backend.miniapp.subscription.db.SubscriptionRepository
import com.hookah.platform.backend.miniapp.venue.VenueStatus
import com.hookah.platform.backend.miniapp.venue.menu.VenueMenuRepository
import com.hookah.platform.backend.platform.PlatformEffectivePrice
import com.hookah.platform.backend.platform.PlatformPriceScheduleItem
import com.hookah.platform.backend.platform.PlatformSubscriptionSettings
import com.hookah.platform.backend.platform.PlatformSubscriptionSettingsRepository
import com.hookah.platform.backend.platform.PlatformSubscriptionSnapshot
import com.hookah.platform.backend.platform.PlatformVenueDetail
import com.hookah.platform.backend.platform.PlatformVenueMemberRepository
import com.hookah.platform.backend.platform.PlatformVenueRepository
import com.hookah.platform.backend.platform.VenueOwnerAccountRepository
import com.hookah.platform.backend.platform.VenueStatusAction
import com.hookah.platform.backend.platform.VenueStatusChangeResult
import com.hookah.platform.backend.telegram.db.ChatContextRepository
import com.hookah.platform.backend.telegram.db.DialogStateRepository
import com.hookah.platform.backend.telegram.db.IdempotencyRepository
import com.hookah.platform.backend.telegram.db.OrdersRepository
import com.hookah.platform.backend.telegram.db.StaffCallRepository
import com.hookah.platform.backend.telegram.db.StaffChatLinkCodeRepository
import com.hookah.platform.backend.telegram.db.TableTokenRepository
import com.hookah.platform.backend.telegram.db.TelegramUserContact
import com.hookah.platform.backend.telegram.db.TelegramVenueContextRepository
import com.hookah.platform.backend.telegram.db.UserRepository
import com.hookah.platform.backend.telegram.db.VenueAccessRepository
import com.hookah.platform.backend.telegram.db.VenueBookingHoursRepository
import com.hookah.platform.backend.telegram.db.VenueConnectionRequestCreateLinkResult
import com.hookah.platform.backend.telegram.db.VenueConnectionRequestMutationResult
import com.hookah.platform.backend.telegram.db.VenueConnectionRequestRecord
import com.hookah.platform.backend.telegram.db.VenueConnectionRequestRepository
import com.hookah.platform.backend.telegram.db.VenueConnectionRequestSubmitResult
import com.hookah.platform.backend.telegram.db.VenueMenuSectionImagesRepository
import com.hookah.platform.backend.telegram.db.VenueOnboardingSource
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
import java.time.LocalDate

class TelegramBotRouterVenueConnectionRequestFlowTest {
    @Test
    fun `authenticated user without owner membership submits first venue application`() =
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
            coEvery { venueConnectionRequestRepository.findActiveUnlinkedByUser(any()) } returns null
            coEvery {
                venueConnectionRequestRepository.createOrReturnActive(
                    telegramUserId = 501L,
                    venueName = "Дым и Лёд",
                    city = "Москва",
                    contact = "@smoke_owner",
                    comment = null,
                    source = VenueOnboardingSource.TELEGRAM_BOT,
                )
            } returns
                VenueConnectionRequestSubmitResult.Success(
                    connectionRequest(1001L, 501L, "Дым и Лёд", contact = "@smoke_owner"),
                    created = true,
                )

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
                    message =
                        Message(
                            messageId = 1,
                            chat = Chat(chatId, "private"),
                            fromUser = from,
                            text = "🤝 Добавить свою кальянную",
                        ),
                ),
            )
            router.process(
                TelegramUpdate(
                    updateId = 2,
                    message =
                        Message(
                            messageId = 2,
                            chat = Chat(chatId, "private"),
                            fromUser = from,
                            text = "Дым и Лёд",
                        ),
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
                    message =
                        Message(
                            messageId = 4,
                            chat = Chat(chatId, "private"),
                            fromUser = from,
                            text = "@smoke_owner",
                        ),
                ),
            )
            router.process(
                TelegramUpdate(
                    updateId = 5,
                    message = Message(messageId = 5, chat = Chat(chatId, "private"), fromUser = from, text = "-"),
                ),
            )

            coVerify {
                venueConnectionRequestRepository.createOrReturnActive(
                    telegramUserId = 501L,
                    venueName = "Дым и Лёд",
                    city = "Москва",
                    contact = "@smoke_owner",
                    comment = null,
                    source = VenueOnboardingSource.TELEGRAM_BOT,
                )
            }
            coVerify(exactly = 0) { venueAccessRepository.listVenueMemberships(501L) }
            coVerify {
                outboxEnqueuer.enqueueSendMessage(chatId, "✅ Заявка отправлена. Мы свяжемся с вами.", any())
            }
            coVerify {
                outboxEnqueuer.enqueueSendMessage(
                    chatId,
                    match {
                        it.contains("Заявка #1001") &&
                            it.contains("Название: Дым и Лёд") &&
                            it.contains("Город: Москва") &&
                            it.contains("Контакт: @smoke_owner") &&
                            it.contains("Статус: На рассмотрении")
                    },
                    match {
                        it is InlineKeyboardMarkup &&
                            it.inlineKeyboard.flatten().any { button ->
                                button.callbackData == "venue_connect_pending_edit:1001"
                            }
                    },
                )
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
            coEvery { venueConnectionRequestRepository.findActiveUnlinkedByUser(any()) } returns null
            coEvery { venueAccessRepository.listVenueMemberships(601L) } returns
                listOf(VenueAccessRepository.VenueMembership(venueId = 10L, role = "OWNER"))
            coEvery {
                venueConnectionRequestRepository.createOrReturnActive(
                    telegramUserId = 601L,
                    venueName = "Lounge X",
                    city = "Москва",
                    contact = "@ownerx",
                    comment = null,
                    source = VenueOnboardingSource.TELEGRAM_BOT,
                )
            } returns
                VenueConnectionRequestSubmitResult.Success(
                    connectionRequest(2001L, 601L, "Lounge X"),
                    created = true,
                )

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
                    message =
                        Message(
                            messageId = 20,
                            chat = Chat(chatId, "private"),
                            fromUser = from,
                            text = "🤝 Добавить свою кальянную",
                        ),
                ),
            )
            router.process(
                TelegramUpdate(
                    updateId = 21,
                    message =
                        Message(
                            messageId = 21,
                            chat = Chat(chatId, "private"),
                            fromUser = from,
                            text = "Lounge X",
                        ),
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
                venueConnectionRequestRepository.createOrReturnActive(
                    telegramUserId = 601L,
                    venueName = "Lounge X",
                    city = "Москва",
                    contact = "@ownerx",
                    comment = null,
                    source = VenueOnboardingSource.TELEGRAM_BOT,
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
            coEvery { venueConnectionRequestRepository.findActiveUnlinkedByUser(any()) } returns null
            coEvery { venueAccessRepository.listVenueMemberships(602L) } returns
                listOf(VenueAccessRepository.VenueMembership(venueId = 10L, role = "OWNER"))
            coEvery {
                venueConnectionRequestRepository.createOrReturnActive(
                    telegramUserId = 602L,
                    venueName = "Lounge Y",
                    city = "Сочи",
                    contact = "@owner_y | +7 999 000-00-00",
                    comment = null,
                    source = VenueOnboardingSource.TELEGRAM_BOT,
                )
            } returns
                VenueConnectionRequestSubmitResult.Success(
                    connectionRequest(2002L, 602L, "Lounge Y", city = "Сочи"),
                    true,
                )

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
                    message =
                        Message(
                            messageId = 30,
                            chat = Chat(chatId, "private"),
                            fromUser = from,
                            text = "🤝 Добавить свою кальянную",
                        ),
                ),
            )
            router.process(
                TelegramUpdate(
                    updateId = 31,
                    message =
                        Message(
                            messageId = 31,
                            chat = Chat(chatId, "private"),
                            fromUser = from,
                            text = "Lounge Y",
                        ),
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
                venueConnectionRequestRepository.createOrReturnActive(
                    telegramUserId = 602L,
                    venueName = "Lounge Y",
                    city = "Сочи",
                    contact = "@owner_y | +7 999 000-00-00",
                    comment = null,
                    source = VenueOnboardingSource.TELEGRAM_BOT,
                )
            }
        }

    @Test
    fun `telegram exact retry returns authoritative request and distinct payload creates another`() =
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
            val existing =
                VenueConnectionRequestRecord(
                    id = 4001L,
                    telegramUserId = 7010L,
                    venueName = "Smoke Place",
                    city = "Москва",
                    contact = "@owner7010",
                    comment = null,
                    status = VenueConnectionRequestRepository.STATUS_PENDING,
                    createdAt = Instant.parse("2026-04-03T12:30:00Z"),
                )
            coEvery {
                venueConnectionRequestRepository.createOrReturnActive(
                    telegramUserId = 7010L,
                    venueName = "Smoke Place",
                    city = "Москва",
                    contact = "@owner7010",
                    comment = null,
                    source = VenueOnboardingSource.TELEGRAM_BOT,
                )
            } returns
                VenueConnectionRequestSubmitResult.Success(existing, created = false)
            coEvery {
                venueConnectionRequestRepository.createOrReturnActive(
                    telegramUserId = 7010L,
                    venueName = "Second Place",
                    city = "Казань",
                    contact = "+7 900 000-00-01",
                    comment = "Вторая точка",
                    source = VenueOnboardingSource.TELEGRAM_BOT,
                )
            } returns
                VenueConnectionRequestSubmitResult.Success(
                    connectionRequest(
                        id = 4002L,
                        userId = 7010L,
                        venueName = "Second Place",
                        city = "Казань",
                        contact = "+7 900 000-00-01",
                        comment = "Вторая точка",
                    ),
                    created = true,
                )

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
            val from = User(id = 7010L)

            suspend fun sendText(
                updateId: Long,
                text: String,
            ) {
                router.process(
                    TelegramUpdate(
                        updateId = updateId,
                        message =
                            Message(
                                messageId = updateId,
                                chat = Chat(chatId, "private"),
                                fromUser = from,
                                text = text,
                            ),
                    ),
                )
            }

            sendText(40, "🤝 Добавить свою кальянную")
            sendText(41, "  Smoke Place  ")
            sendText(42, " Москва ")
            sendText(43, "@owner7010")
            sendText(44, "-")

            coVerify(exactly = 0) { venueConnectionRequestRepository.findActiveUnlinkedByUser(any()) }
            coVerify {
                venueConnectionRequestRepository.createOrReturnActive(
                    telegramUserId = 7010L,
                    venueName = "Smoke Place",
                    city = "Москва",
                    contact = "@owner7010",
                    comment = null,
                    source = VenueOnboardingSource.TELEGRAM_BOT,
                )
            }
            coVerify {
                outboxEnqueuer.enqueueSendMessage(
                    chatId,
                    match {
                        it.contains("Заявка #4001") &&
                            it.contains("Название: Smoke Place") &&
                            it.contains("Город: Москва") &&
                            it.contains("Контакт: @owner7010") &&
                            it.contains("Статус: На рассмотрении")
                    },
                    match {
                        it is InlineKeyboardMarkup &&
                            it.inlineKeyboard.flatten().any { button ->
                                button.callbackData == "venue_connect_pending_edit:4001"
                            }
                    },
                )
            }

            sendText(45, "🤝 Добавить свою кальянную")
            sendText(46, "Second Place")
            sendText(47, "Казань")
            sendText(48, "+7 900 000-00-01")
            sendText(49, "Вторая точка")

            coVerify {
                venueConnectionRequestRepository.createOrReturnActive(
                    telegramUserId = 7010L,
                    venueName = "Second Place",
                    city = "Казань",
                    contact = "+7 900 000-00-01",
                    comment = "Вторая точка",
                    source = VenueOnboardingSource.TELEGRAM_BOT,
                )
            }
            coVerify { outboxEnqueuer.enqueueSendMessage(chatId, "✅ Заявка отправлена. Мы свяжемся с вами.", any()) }
            coVerify {
                outboxEnqueuer.enqueueSendMessage(
                    chatId,
                    match {
                        it.contains("Заявка #4002") &&
                            it.contains("Название: Second Place") &&
                            it.contains("Город: Казань") &&
                            it.contains("Контакт: +7 900 000-00-01") &&
                            it.contains("Комментарий: Вторая точка") &&
                            it.contains("Статус: На рассмотрении")
                    },
                    match {
                        it is InlineKeyboardMarkup &&
                            it.inlineKeyboard.flatten().any { button ->
                                button.callbackData == "venue_connect_pending_edit:4002"
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
                venueConnectionRequestRepository.cancel(5001L, 7011L, VenueOnboardingSource.TELEGRAM_BOT)
            } returns
                VenueConnectionRequestMutationResult.Success(
                    connectionRequest(
                        5001L,
                        7011L,
                        "Smoke",
                        status = VenueConnectionRequestRepository.STATUS_CANCELLED,
                    ),
                )

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

            coVerify { venueConnectionRequestRepository.cancel(5001L, 7011L, VenueOnboardingSource.TELEGRAM_BOT) }
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
            val venueMenuRepository: VenueMenuRepository = mockk(relaxed = true)
            val venueAccessRepository: VenueAccessRepository = mockk()
            val subscriptionRepository: SubscriptionRepository = mockk()
            val guestMenuRepository: GuestMenuRepository = mockk()
            val tableSessionRepository: TableSessionRepository = mockk(relaxed = true)
            val guestTabsRepository: GuestTabsRepository = mockk(relaxed = true)
            val venueConnectionRequestRepository: VenueConnectionRequestRepository = mockk()

            coEvery { idempotencyRepository.tryAcquire(any(), any(), any()) } returns true
            coEvery {
                venueConnectionRequestRepository.decide(
                    requestId = 6001L,
                    actorUserId = 999L,
                    targetStatus = VenueConnectionRequestRepository.STATUS_APPROVED,
                    source = VenueOnboardingSource.TELEGRAM_BOT,
                )
            } returns
                VenueConnectionRequestMutationResult.Success(
                    connectionRequest(6001L, 7011L, "Smoke", status = VenueConnectionRequestRepository.STATUS_APPROVED),
                )

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
                    venueMenuRepository = venueMenuRepository,
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
                venueConnectionRequestRepository.decide(
                    requestId = 6001L,
                    actorUserId = 999L,
                    targetStatus = VenueConnectionRequestRepository.STATUS_APPROVED,
                    source = VenueOnboardingSource.TELEGRAM_BOT,
                )
            }
            coVerify(exactly = 0) { venueMenuRepository.createMissingCategories(any(), any(), any(), any()) }
            coVerify(exactly = 0) {
                venueMenuRepository.createCategory(any(), any(), any(), any(), any())
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
            val venueMenuRepository: VenueMenuRepository = mockk(relaxed = true)
            val venueAccessRepository: VenueAccessRepository = mockk()
            val venueContextRepository: TelegramVenueContextRepository = mockk(relaxed = true)
            val subscriptionRepository: SubscriptionRepository = mockk()
            val guestMenuRepository: GuestMenuRepository = mockk()
            val tableSessionRepository: TableSessionRepository = mockk(relaxed = true)
            val guestTabsRepository: GuestTabsRepository = mockk(relaxed = true)
            val venueConnectionRequestRepository: VenueConnectionRequestRepository = mockk(relaxed = true)
            val platformVenueRepository: PlatformVenueRepository = mockk()
            val platformVenueMemberRepository: PlatformVenueMemberRepository = mockk()
            val platformSubscriptionSettingsRepository: PlatformSubscriptionSettingsRepository = mockk()
            val venueOwnerAccountRepository: VenueOwnerAccountRepository = mockk()

            coEvery { idempotencyRepository.tryAcquire(any(), any(), any()) } returns true
            val approvedRequest =
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
                    futurePriceRub = 15000L,
                    futurePriceEffectiveOn = LocalDate.parse("2026-09-01"),
                    commercialNote = "Индивидуальные условия",
                )
            coEvery {
                venueConnectionRequestRepository.createDraftAndLink(
                    requestId = 7001L,
                    actorUserId = 999L,
                    source = VenueOnboardingSource.TELEGRAM_BOT,
                )
            } returns
                VenueConnectionRequestCreateLinkResult.Success(
                    request = approvedRequest.copy(linkedVenueId = 9001L),
                    venueId = 9001L,
                    created = true,
                )
            coEvery { userRepository.findTelegramUserContact(7011L) } returns
                TelegramUserContact(
                    telegramUserId = 7011L,
                    username = "new_owner",
                    firstName = "New",
                    lastName = "Owner",
                )

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
                    venueMenuRepository = venueMenuRepository,
                    venueAccessRepository = venueAccessRepository,
                    venueContextRepository = venueContextRepository,
                    subscriptionRepository = subscriptionRepository,
                    guestMenuRepository = guestMenuRepository,
                    tableSessionRepository = tableSessionRepository,
                    guestTabsRepository = guestTabsRepository,
                    platformVenueRepository = platformVenueRepository,
                    platformVenueMemberRepository = platformVenueMemberRepository,
                    platformSubscriptionSettingsRepository = platformSubscriptionSettingsRepository,
                    venueOwnerAccountRepository = venueOwnerAccountRepository,
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
                venueConnectionRequestRepository.createDraftAndLink(
                    requestId = 7001L,
                    actorUserId = 999L,
                    source = VenueOnboardingSource.TELEGRAM_BOT,
                )
            }
            coVerify(exactly = 0) { platformVenueRepository.createVenue(any(), any(), any(), any()) }
            coVerify(exactly = 0) {
                platformVenueMemberRepository.assignOwner(any(), any(), any(), any(), any(), any())
            }
            coVerify(exactly = 0) { venueMenuRepository.createMissingCategories(any(), any(), any(), any()) }
            coVerify(exactly = 0) {
                venueMenuRepository.createCategory(any(), any(), any(), any(), any())
            }
            coVerify {
                outboxEnqueuer.enqueueSendMessage(
                    7011L,
                    "✅ Ваша заявка одобрена.\nТеперь вы можете настроить свою кальянную.",
                    match {
                        it is InlineKeyboardMarkup &&
                            it.inlineKeyboard.flatten().any { button ->
                                button.callbackData == "owner_venue_onboarding_entry:9001"
                            }
                    },
                )
            }
            coVerify {
                outboxEnqueuer.enqueueSendMessage(
                    chatId,
                    match { text ->
                        text.contains("✅ Черновик кальянной создан") &&
                            text.contains("ID заведения: 9001") &&
                            text.contains("Владелец: New Owner · @new_owner · Telegram ID 7011") &&
                            text.contains("Коммерческие условия применены") &&
                            text.contains("Trial: Без trial") &&
                            text.contains("Стоимость после trial: 10000 ₽/мес") &&
                            text.contains("Будущая стоимость: 15000 ₽/мес с 01.09.2026") &&
                            text.contains("Заметка: Индивидуальные условия")
                    },
                    null,
                )
            }
            coVerify(exactly = 0) { venueContextRepository.setSelectedVenue(any(), any(), any()) }

            coEvery { venueAccessRepository.listVenueMemberships(7011L) } returns
                listOf(
                    VenueAccessRepository.VenueMembership(venueId = 8001L, role = "OWNER"),
                    VenueAccessRepository.VenueMembership(venueId = 9001L, role = "OWNER"),
                )
            coEvery { venueContextRepository.getSelectedVenue(chatId, 7011L) } returns 8001L
            coEvery { platformVenueRepository.getVenueDetail(9001L) } returns platformVenueDetail()
            router.process(
                TelegramUpdate(
                    updateId = 44,
                    callbackQuery =
                        CallbackQuery(
                            id = "cb-owner-onboarding-entry",
                            from = User(id = 7011L),
                            message = Message(messageId = 44, chat = Chat(chatId, "private")),
                            data = "owner_venue_onboarding_entry:9001",
                        ),
                ),
            )

            coVerify {
                outboxEnqueuer.enqueueSendMessage(
                    chatId,
                    match { it.contains("🏢 Заведение") && it.contains("Название: Новая кальянная") },
                    any(),
                )
            }
            coVerify(exactly = 1) { venueContextRepository.setSelectedVenue(chatId, 7011L, 9001L) }
            coVerify(exactly = 0) { venueMenuRepository.createMissingCategories(any(), any(), any(), any()) }
            coVerify(exactly = 0) { venueMenuRepository.createCategory(any(), any(), any(), any(), any()) }
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
                    cardDescription = "Уютная атмосфера и авторские миксы.",
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
                    trafficPolicy = TelegramTrafficPolicy.unrestricted(),
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
                venueConnectionRequestRepository.listActionableRequests(limit = 20)
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
                    trafficPolicy = TelegramTrafficPolicy.unrestricted(),
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

    @Test
    fun `platform owner sees approved unlinked request and can continue processing`() =
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
            coEvery { dialogStateRepository.clear(any()) } returns Unit
            coEvery {
                venueConnectionRequestRepository.listActionableRequests(limit = 20)
            } returns
                listOf(
                    VenueConnectionRequestRecord(
                        id = 102L,
                        telegramUserId = 502L,
                        venueName = "Smoke Lab",
                        city = "Казань",
                        contact = "@smoke_lab",
                        comment = "Индивидуальные условия",
                        status = VenueConnectionRequestRepository.STATUS_APPROVED,
                        createdAt = Instant.parse("2026-04-04T12:30:00Z"),
                        linkedVenueId = null,
                        trialConfigured = true,
                        trialEndsOn = null,
                        currentPriceRub = 10000L,
                        futurePriceRub = 15000L,
                        futurePriceEffectiveOn = LocalDate.parse("2026-12-01"),
                        commercialNote = "Поднять через полгода",
                    ),
                )

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
            router.process(
                TelegramUpdate(
                    updateId = 11,
                    message =
                        Message(
                            messageId = 12,
                            chat = Chat(chatId, "private"),
                            fromUser = User(id = 999L),
                            text = "📨 Заявки на подключение",
                        ),
                ),
            )

            coVerify {
                outboxEnqueuer.enqueueSendMessage(
                    chatId,
                    match { text ->
                        text.contains("Заявка #102") &&
                            text.contains("Статус: Принята") &&
                            text.contains("Trial: без trial") &&
                            text.contains("Регулярная стоимость: 10000 ₽/мес") &&
                            text.contains("Будущая стоимость: 15000 ₽/мес с 01.12.2026") &&
                            text.contains("Заметки: Поднять через полгода")
                    },
                    match {
                        it is InlineKeyboardMarkup &&
                            it.inlineKeyboard.flatten().any { button ->
                                button.callbackData == "owner_venue_create_from_request:102"
                            } &&
                            it.inlineKeyboard.flatten().any { button ->
                                button.callbackData == "owner_venue_terms_open:102"
                            }
                    },
                )
            }
        }

    @Test
    fun `platform owner can close approved unlinked request without venue creation`() =
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
            val subscriptionRepository: SubscriptionRepository = mockk(relaxed = true)
            val guestMenuRepository: GuestMenuRepository = mockk()
            val tableSessionRepository: TableSessionRepository = mockk(relaxed = true)
            val guestTabsRepository: GuestTabsRepository = mockk(relaxed = true)
            val venueConnectionRequestRepository: VenueConnectionRequestRepository = mockk()
            val platformVenueRepository: PlatformVenueRepository = mockk(relaxed = true)
            val platformSubscriptionSettingsRepository: PlatformSubscriptionSettingsRepository = mockk(relaxed = true)

            val request =
                VenueConnectionRequestRecord(
                    id = 102L,
                    telegramUserId = 502L,
                    venueName = "Smoke Lab",
                    city = "Казань",
                    contact = "@smoke_lab",
                    comment = "Индивидуальные условия",
                    status = VenueConnectionRequestRepository.STATUS_APPROVED,
                    createdAt = Instant.parse("2026-04-04T12:30:00Z"),
                    linkedVenueId = null,
                    trialConfigured = true,
                    currentPriceRub = 0L,
                )
            coEvery { idempotencyRepository.tryAcquire(any(), any(), any()) } returns true
            coEvery { venueConnectionRequestRepository.findById(102L) } returns request
            coEvery {
                venueConnectionRequestRepository.closeApprovedUnlinked(
                    requestId = 102L,
                    actorUserId = 999L,
                    source = VenueOnboardingSource.TELEGRAM_BOT,
                )
            } returns
                VenueConnectionRequestMutationResult.Success(
                    request.copy(status = VenueConnectionRequestRepository.STATUS_CANCELLED),
                )
            coEvery { venueConnectionRequestRepository.listActionableRequests(limit = 20) } returns emptyList()

            val router =
                buildPlatformSubscriptionTestRouter(
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
                    venueConnectionRequestRepository = venueConnectionRequestRepository,
                    platformVenueRepository = platformVenueRepository,
                    platformSubscriptionSettingsRepository = platformSubscriptionSettingsRepository,
                )

            router.process(
                TelegramUpdate(
                    updateId = 18,
                    callbackQuery =
                        CallbackQuery(
                            id = "cb-close-approved-request",
                            from = User(id = 999L),
                            message = Message(messageId = 19, chat = Chat(709L, "private")),
                            data = "owner_venue_connect_close:102",
                        ),
                ),
            )

            coVerify {
                venueConnectionRequestRepository.closeApprovedUnlinked(
                    requestId = 102L,
                    actorUserId = 999L,
                    source = VenueOnboardingSource.TELEGRAM_BOT,
                )
            }
            coVerify {
                outboxEnqueuer.enqueueSendMessage(
                    709L,
                    "✅ Заявка #102 закрыта без создания заведения.",
                    null,
                )
            }
            coVerify {
                outboxEnqueuer.enqueueSendMessage(
                    502L,
                    "Заявка «Smoke Lab» закрыта без создания заведения. Если нужно, отправьте новую заявку.",
                    null,
                )
            }
            coVerify { outboxEnqueuer.enqueueSendMessage(709L, "Активных заявок на обработку пока нет.", null) }
        }

    @Test
    fun `platform owner trial custom input accepts zero as no trial`() =
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
            val venueConnectionRequestRepository: VenueConnectionRequestRepository = mockk(relaxed = true)

            coEvery { idempotencyRepository.tryAcquire(any(), any(), any()) } returns true
            coEvery { dialogStateRepository.get(any()) } returns
                DialogState(
                    DialogStateType.OWNER_VENUE_TERMS_WAIT_TRIAL_CUSTOM_DATE,
                    payload = mapOf("request_id" to "6001"),
                )
            coEvery { dialogStateRepository.set(any(), any()) } returns Unit
            coEvery { dialogStateRepository.clear(any()) } returns Unit

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
            router.process(
                TelegramUpdate(
                    updateId = 12,
                    message =
                        Message(
                            messageId = 13,
                            chat = Chat(chatId, "private"),
                            fromUser = User(id = 999L),
                            text = "0",
                        ),
                ),
            )

            coVerify {
                dialogStateRepository.set(
                    chatId,
                    match { state ->
                        state.state == DialogStateType.OWNER_VENUE_TERMS_WAIT_CURRENT_PRICE &&
                            state.payload["request_id"] == "6001" &&
                            state.payload["trial_configured"] == "true" &&
                            !state.payload.containsKey("trial_ends_on")
                    },
                )
            }
            coVerify {
                outboxEnqueuer.enqueueSendMessage(
                    chatId,
                    "Trial не используется. Укажите регулярную стоимость, ₽/мес (0 или положительное число).",
                    null,
                )
            }
        }

    @Test
    fun `platform owner trial custom input rejects negative value`() =
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
            val venueConnectionRequestRepository: VenueConnectionRequestRepository = mockk(relaxed = true)

            coEvery { idempotencyRepository.tryAcquire(any(), any(), any()) } returns true
            coEvery { dialogStateRepository.get(any()) } returns
                DialogState(
                    DialogStateType.OWNER_VENUE_TERMS_WAIT_TRIAL_CUSTOM_DATE,
                    payload = mapOf("request_id" to "6001"),
                )
            coEvery { dialogStateRepository.set(any(), any()) } returns Unit
            coEvery { dialogStateRepository.clear(any()) } returns Unit

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
            router.process(
                TelegramUpdate(
                    updateId = 13,
                    message =
                        Message(
                            messageId = 14,
                            chat = Chat(chatId, "private"),
                            fromUser = User(id = 999L),
                            text = "-1",
                        ),
                ),
            )

            coVerify(exactly = 0) { dialogStateRepository.set(any(), any()) }
            coVerify {
                outboxEnqueuer.enqueueSendMessage(
                    chatId,
                    "Некорректная дата. Введите в формате дд.мм.гггг, например 15.04.2026, или 0, если trial не нужен.",
                    null,
                )
            }
        }

    @Test
    fun `platform owner current monthly price zero requires confirmation and keeps terms state`() =
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
            val subscriptionRepository: SubscriptionRepository = mockk(relaxed = true)
            val guestMenuRepository: GuestMenuRepository = mockk()
            val tableSessionRepository: TableSessionRepository = mockk(relaxed = true)
            val guestTabsRepository: GuestTabsRepository = mockk(relaxed = true)
            val venueConnectionRequestRepository: VenueConnectionRequestRepository = mockk(relaxed = true)
            val platformVenueRepository: PlatformVenueRepository = mockk(relaxed = true)
            val platformSubscriptionSettingsRepository: PlatformSubscriptionSettingsRepository = mockk(relaxed = true)

            val chatId = 710L
            val states =
                mutableMapOf(
                    chatId to
                        DialogState(
                            DialogStateType.OWNER_VENUE_TERMS_WAIT_CURRENT_PRICE,
                            payload = mapOf("request_id" to "6001", "trial_configured" to "true"),
                        ),
                )
            coEvery { idempotencyRepository.tryAcquire(any(), any(), any()) } returns true
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

            val router =
                buildPlatformSubscriptionTestRouter(
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
                    venueConnectionRequestRepository = venueConnectionRequestRepository,
                    platformVenueRepository = platformVenueRepository,
                    platformSubscriptionSettingsRepository = platformSubscriptionSettingsRepository,
                )

            router.process(
                TelegramUpdate(
                    updateId = 19,
                    message =
                        Message(
                            messageId = 20,
                            chat = Chat(chatId, "private"),
                            fromUser = User(id = 999L),
                            text = "0",
                        ),
                ),
            )

            check(states.getValue(chatId).state == DialogStateType.OWNER_VENUE_TERMS_WAIT_ZERO_PRICE_CONFIRM)
            coVerify {
                outboxEnqueuer.enqueueSendMessage(
                    chatId,
                    "Регулярная стоимость 0 ₽/мес означает индивидуально бесплатные условия. Подтвердить?",
                    match {
                        it is InlineKeyboardMarkup &&
                            it.inlineKeyboard.flatten().any { button ->
                                button.callbackData == "owner_venue_terms_zero_price:6001:confirm"
                            }
                    },
                )
            }

            router.process(
                TelegramUpdate(
                    updateId = 20,
                    callbackQuery =
                        CallbackQuery(
                            id = "cb-confirm-zero-price",
                            from = User(id = 999L),
                            message = Message(messageId = 21, chat = Chat(chatId, "private")),
                            data = "owner_venue_terms_zero_price:6001:confirm",
                        ),
                ),
            )

            val state = states.getValue(chatId)
            check(state.state == DialogStateType.OWNER_VENUE_TERMS_WAIT_FUTURE_PRICE)
            check(state.payload["current_price_rub"] == "0")
            coVerify {
                outboxEnqueuer.enqueueSendMessage(
                    chatId,
                    "Будущую стоимость задаём?",
                    match {
                        it is InlineKeyboardMarkup &&
                            it.inlineKeyboard.flatten().any { button ->
                                button.callbackData == "owner_venue_terms_future:6001:skip"
                            }
                    },
                )
            }
        }

    @Test
    fun `platform owner can edit current monthly price after venue creation`() =
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
            val venueConnectionRequestRepository: VenueConnectionRequestRepository = mockk(relaxed = true)
            val platformVenueRepository: PlatformVenueRepository = mockk()
            val platformSubscriptionSettingsRepository: PlatformSubscriptionSettingsRepository = mockk()

            coEvery { idempotencyRepository.tryAcquire(any(), any(), any()) } returns true
            coEvery { dialogStateRepository.get(any()) } returns
                DialogState(
                    DialogStateType.PLATFORM_SUBSCRIPTION_WAIT_CURRENT_PRICE,
                    payload = mapOf("venue_id" to "9001"),
                )
            coEvery { dialogStateRepository.clear(any()) } returns Unit
            coEvery {
                platformSubscriptionSettingsRepository.updateSettings(
                    venueId = 9001L,
                    update =
                        match {
                            it.basePriceMinor == 1_500_000 &&
                                it.currency == PlatformSubscriptionSettingsRepository.DEFAULT_CURRENCY
                        },
                    actorUserId = 999L,
                )
            } returns
                PlatformSubscriptionSettings(
                    venueId = 9001L,
                    trialEndDate = null,
                    paidStartDate = null,
                    basePriceMinor = 1_500_000,
                    priceOverrideMinor = null,
                    currency = PlatformSubscriptionSettingsRepository.DEFAULT_CURRENCY,
                )
            coEvery { platformVenueRepository.getVenueDetail(9001L) } returns platformVenueDetail()
            coEvery { subscriptionRepository.getSubscriptionStatus(9001L) } returns SubscriptionStatus.ACTIVE
            coEvery { platformSubscriptionSettingsRepository.getSubscriptionSnapshot(9001L) } returns
                PlatformSubscriptionSnapshot(
                    settings =
                        PlatformSubscriptionSettings(
                            venueId = 9001L,
                            trialEndDate = null,
                            paidStartDate = null,
                            basePriceMinor = 1_500_000,
                            priceOverrideMinor = null,
                            currency = PlatformSubscriptionSettingsRepository.DEFAULT_CURRENCY,
                        ),
                    schedule = emptyList(),
                    effectivePriceToday =
                        PlatformEffectivePrice(
                            priceMinor = 1_500_000,
                            currency = PlatformSubscriptionSettingsRepository.DEFAULT_CURRENCY,
                        ),
                )

            val router =
                buildPlatformSubscriptionTestRouter(
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
                    venueConnectionRequestRepository = venueConnectionRequestRepository,
                    platformVenueRepository = platformVenueRepository,
                    platformSubscriptionSettingsRepository = platformSubscriptionSettingsRepository,
                )

            val chatId = 705L
            router.process(
                TelegramUpdate(
                    updateId = 14,
                    message =
                        Message(
                            messageId = 15,
                            chat = Chat(chatId, "private"),
                            fromUser = User(id = 999L),
                            text = "15000",
                        ),
                ),
            )

            coVerify {
                platformSubscriptionSettingsRepository.updateSettings(
                    venueId = 9001L,
                    update =
                        match {
                            it.basePriceMinor == 1_500_000 &&
                                it.currency == PlatformSubscriptionSettingsRepository.DEFAULT_CURRENCY
                        },
                    actorUserId = 999L,
                )
            }
            coVerify {
                outboxEnqueuer.enqueueSendMessage(chatId, "✅ Текущая цена обновлена: 15000 ₽/мес.", null)
            }
        }

    @Test
    fun `platform owner current monthly price zero confirms and saves free settings`() =
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
            val subscriptionRepository: SubscriptionRepository = mockk(relaxed = true)
            val guestMenuRepository: GuestMenuRepository = mockk()
            val tableSessionRepository: TableSessionRepository = mockk(relaxed = true)
            val guestTabsRepository: GuestTabsRepository = mockk(relaxed = true)
            val venueConnectionRequestRepository: VenueConnectionRequestRepository = mockk(relaxed = true)
            val platformVenueRepository: PlatformVenueRepository = mockk()
            val platformSubscriptionSettingsRepository: PlatformSubscriptionSettingsRepository = mockk()

            val chatId = 711L
            val states =
                mutableMapOf(
                    chatId to
                        DialogState(
                            DialogStateType.PLATFORM_SUBSCRIPTION_WAIT_CURRENT_PRICE,
                            payload = mapOf("venue_id" to "9001"),
                        ),
                )
            coEvery { idempotencyRepository.tryAcquire(any(), any(), any()) } returns true
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
            coEvery {
                platformSubscriptionSettingsRepository.updateSettings(
                    venueId = 9001L,
                    update =
                        match {
                            it.basePriceMinor == 0 &&
                                it.currency == PlatformSubscriptionSettingsRepository.DEFAULT_CURRENCY
                        },
                    actorUserId = 999L,
                )
            } returns
                PlatformSubscriptionSettings(
                    venueId = 9001L,
                    trialEndDate = null,
                    paidStartDate = null,
                    basePriceMinor = 0,
                    priceOverrideMinor = null,
                    currency = PlatformSubscriptionSettingsRepository.DEFAULT_CURRENCY,
                )
            coEvery { platformVenueRepository.getVenueDetail(9001L) } returns platformVenueDetail()
            coEvery { platformSubscriptionSettingsRepository.getSubscriptionSnapshot(9001L) } returns
                PlatformSubscriptionSnapshot(
                    settings =
                        PlatformSubscriptionSettings(
                            venueId = 9001L,
                            trialEndDate = null,
                            paidStartDate = null,
                            basePriceMinor = 0,
                            priceOverrideMinor = null,
                            currency = PlatformSubscriptionSettingsRepository.DEFAULT_CURRENCY,
                        ),
                    schedule = emptyList(),
                    effectivePriceToday =
                        PlatformEffectivePrice(
                            priceMinor = 0,
                            currency = PlatformSubscriptionSettingsRepository.DEFAULT_CURRENCY,
                        ),
                )

            val router =
                buildPlatformSubscriptionTestRouter(
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
                    venueConnectionRequestRepository = venueConnectionRequestRepository,
                    platformVenueRepository = platformVenueRepository,
                    platformSubscriptionSettingsRepository = platformSubscriptionSettingsRepository,
                )

            router.process(
                TelegramUpdate(
                    updateId = 21,
                    message =
                        Message(
                            messageId = 22,
                            chat = Chat(chatId, "private"),
                            fromUser = User(id = 999L),
                            text = "0",
                        ),
                ),
            )

            check(states.getValue(chatId).state == DialogStateType.PLATFORM_SUBSCRIPTION_WAIT_ZERO_PRICE_CONFIRM)
            router.process(
                TelegramUpdate(
                    updateId = 22,
                    callbackQuery =
                        CallbackQuery(
                            id = "cb-platform-confirm-zero-price",
                            from = User(id = 999L),
                            message = Message(messageId = 23, chat = Chat(chatId, "private")),
                            data = "platform_subscription_zero_price:9001:confirm",
                        ),
                ),
            )

            coVerify {
                platformSubscriptionSettingsRepository.updateSettings(
                    venueId = 9001L,
                    update =
                        match {
                            it.basePriceMinor == 0 &&
                                it.currency == PlatformSubscriptionSettingsRepository.DEFAULT_CURRENCY
                        },
                    actorUserId = 999L,
                )
            }
            coVerify {
                outboxEnqueuer.enqueueSendMessage(
                    chatId,
                    "✅ Текущая цена обновлена: 0 ₽/мес (индивидуально бесплатно).",
                    null,
                )
            }
        }

    @Test
    fun `platform owner can set future price with manual date`() =
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
            val venueConnectionRequestRepository: VenueConnectionRequestRepository = mockk(relaxed = true)
            val platformVenueRepository: PlatformVenueRepository = mockk()
            val platformSubscriptionSettingsRepository: PlatformSubscriptionSettingsRepository = mockk()

            coEvery { idempotencyRepository.tryAcquire(any(), any(), any()) } returns true
            coEvery { dialogStateRepository.get(any()) } returns
                DialogState(
                    DialogStateType.PLATFORM_SUBSCRIPTION_WAIT_FUTURE_PRICE_DATE,
                    payload =
                        mapOf(
                            "venue_id" to "9001",
                            "future_price_minor" to "1500000",
                            "future_price_rub" to "15000",
                        ),
                )
            coEvery { dialogStateRepository.clear(any()) } returns Unit
            coEvery {
                platformSubscriptionSettingsRepository.replaceSchedule(
                    venueId = 9001L,
                    items =
                        listOf(
                            PlatformPriceScheduleItem(
                                venueId = 9001L,
                                effectiveFrom = LocalDate.parse("2026-09-01"),
                                priceMinor = 1_500_000,
                                currency = PlatformSubscriptionSettingsRepository.DEFAULT_CURRENCY,
                            ),
                        ),
                    actorUserId = 999L,
                )
            } returns
                listOf(
                    PlatformPriceScheduleItem(
                        venueId = 9001L,
                        effectiveFrom = LocalDate.parse("2026-09-01"),
                        priceMinor = 1_500_000,
                        currency = PlatformSubscriptionSettingsRepository.DEFAULT_CURRENCY,
                    ),
                )
            coEvery { platformVenueRepository.getVenueDetail(9001L) } returns platformVenueDetail()
            coEvery { subscriptionRepository.getSubscriptionStatus(9001L) } returns SubscriptionStatus.ACTIVE
            coEvery { platformSubscriptionSettingsRepository.getSubscriptionSnapshot(9001L) } returns
                PlatformSubscriptionSnapshot(
                    settings =
                        PlatformSubscriptionSettings(
                            venueId = 9001L,
                            trialEndDate = null,
                            paidStartDate = null,
                            basePriceMinor = 1_000_000,
                            priceOverrideMinor = null,
                            currency = PlatformSubscriptionSettingsRepository.DEFAULT_CURRENCY,
                        ),
                    schedule =
                        listOf(
                            PlatformPriceScheduleItem(
                                venueId = 9001L,
                                effectiveFrom = LocalDate.parse("2026-09-01"),
                                priceMinor = 1_500_000,
                                currency = PlatformSubscriptionSettingsRepository.DEFAULT_CURRENCY,
                            ),
                        ),
                    effectivePriceToday =
                        PlatformEffectivePrice(
                            priceMinor = 1_000_000,
                            currency = PlatformSubscriptionSettingsRepository.DEFAULT_CURRENCY,
                        ),
                )

            val router =
                buildPlatformSubscriptionTestRouter(
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
                    venueConnectionRequestRepository = venueConnectionRequestRepository,
                    platformVenueRepository = platformVenueRepository,
                    platformSubscriptionSettingsRepository = platformSubscriptionSettingsRepository,
                )

            val chatId = 706L
            router.process(
                TelegramUpdate(
                    updateId = 15,
                    message =
                        Message(
                            messageId = 16,
                            chat = Chat(chatId, "private"),
                            fromUser = User(id = 999L),
                            text = "01.09.2026",
                        ),
                ),
            )

            coVerify {
                platformSubscriptionSettingsRepository.replaceSchedule(
                    venueId = 9001L,
                    items =
                        listOf(
                            PlatformPriceScheduleItem(
                                venueId = 9001L,
                                effectiveFrom = LocalDate.parse("2026-09-01"),
                                priceMinor = 1_500_000,
                                currency = PlatformSubscriptionSettingsRepository.DEFAULT_CURRENCY,
                            ),
                        ),
                    actorUserId = 999L,
                )
            }
            coVerify {
                outboxEnqueuer.enqueueSendMessage(
                    chatId,
                    "✅ Будущая цена сохранена: 15000 ₽/мес с 01.09.2026.",
                    null,
                )
            }
        }

    @Test
    fun `platform owner subscription edit rejects invalid date and negative price without losing state`() =
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
            val subscriptionRepository: SubscriptionRepository = mockk(relaxed = true)
            val guestMenuRepository: GuestMenuRepository = mockk()
            val tableSessionRepository: TableSessionRepository = mockk(relaxed = true)
            val guestTabsRepository: GuestTabsRepository = mockk(relaxed = true)
            val venueConnectionRequestRepository: VenueConnectionRequestRepository = mockk(relaxed = true)
            val platformVenueRepository: PlatformVenueRepository = mockk(relaxed = true)
            val platformSubscriptionSettingsRepository: PlatformSubscriptionSettingsRepository = mockk(relaxed = true)

            coEvery { idempotencyRepository.tryAcquire(any(), any(), any()) } returns true
            coEvery { dialogStateRepository.clear(any()) } returns Unit
            coEvery { dialogStateRepository.get(any()) } returns
                DialogState(
                    DialogStateType.PLATFORM_SUBSCRIPTION_WAIT_FUTURE_PRICE_DATE,
                    payload =
                        mapOf(
                            "venue_id" to "9001",
                            "future_price_minor" to "1500000",
                            "future_price_rub" to "15000",
                        ),
                )

            val router =
                buildPlatformSubscriptionTestRouter(
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
                    venueConnectionRequestRepository = venueConnectionRequestRepository,
                    platformVenueRepository = platformVenueRepository,
                    platformSubscriptionSettingsRepository = platformSubscriptionSettingsRepository,
                )

            val chatId = 708L
            router.process(
                TelegramUpdate(
                    updateId = 16,
                    message =
                        Message(
                            messageId = 17,
                            chat = Chat(chatId, "private"),
                            fromUser = User(id = 999L),
                            text = "32.13.2026",
                        ),
                ),
            )

            coVerify(exactly = 0) { platformSubscriptionSettingsRepository.replaceSchedule(any(), any(), any()) }
            coVerify(exactly = 0) { dialogStateRepository.clear(chatId) }
            coVerify {
                outboxEnqueuer.enqueueSendMessage(
                    chatId,
                    "Некорректная дата. Введите в формате дд.мм.гггг, например 01.09.2026.",
                    null,
                )
            }

            coEvery { dialogStateRepository.get(any()) } returns
                DialogState(
                    DialogStateType.PLATFORM_SUBSCRIPTION_WAIT_CURRENT_PRICE,
                    payload = mapOf("venue_id" to "9001"),
                )
            router.process(
                TelegramUpdate(
                    updateId = 17,
                    message =
                        Message(
                            messageId = 18,
                            chat = Chat(chatId, "private"),
                            fromUser = User(id = 999L),
                            text = "-1",
                        ),
                ),
            )

            coVerify(exactly = 0) { platformSubscriptionSettingsRepository.updateSettings(any(), any(), any()) }
            coVerify {
                outboxEnqueuer.enqueueSendMessage(
                    chatId,
                    "Введите корректную цену в ₽/мес: 0 или положительное число.",
                    null,
                )
            }
        }

    private fun buildPlatformSubscriptionTestRouter(
        apiClient: TelegramApiClient,
        outboxEnqueuer: TelegramOutboxEnqueuer,
        idempotencyRepository: IdempotencyRepository,
        userRepository: UserRepository,
        tableTokenRepository: TableTokenRepository,
        chatContextRepository: ChatContextRepository,
        dialogStateRepository: DialogStateRepository,
        ordersRepository: OrdersRepository,
        staffCallRepository: StaffCallRepository,
        staffChatLinkCodeRepository: StaffChatLinkCodeRepository,
        guestBookingRepository: GuestBookingRepository,
        venueRepository: VenueRepository,
        venueBookingHoursRepository: VenueBookingHoursRepository,
        venueMenuSectionImagesRepository: VenueMenuSectionImagesRepository,
        venueAccessRepository: VenueAccessRepository,
        subscriptionRepository: SubscriptionRepository,
        guestMenuRepository: GuestMenuRepository,
        tableSessionRepository: TableSessionRepository,
        guestTabsRepository: GuestTabsRepository,
        venueConnectionRequestRepository: VenueConnectionRequestRepository,
        platformVenueRepository: PlatformVenueRepository,
        platformSubscriptionSettingsRepository: PlatformSubscriptionSettingsRepository,
    ): TelegramBotRouter =
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
            platformSubscriptionSettingsRepository = platformSubscriptionSettingsRepository,
            tableSessionTtl = Duration.ofHours(2),
            json = Json { ignoreUnknownKeys = true },
            venueConnectionRequestRepository = venueConnectionRequestRepository,
            scope = CoroutineScope(Dispatchers.Unconfined),
        )

    private fun connectionRequest(
        id: Long,
        userId: Long,
        venueName: String,
        city: String = "Москва",
        contact: String = "@owner",
        comment: String? = null,
        status: String = VenueConnectionRequestRepository.STATUS_PENDING,
    ): VenueConnectionRequestRecord =
        VenueConnectionRequestRecord(
            id = id,
            telegramUserId = userId,
            venueName = venueName,
            city = city,
            contact = contact,
            comment = comment,
            status = status,
            createdAt = Instant.parse("2026-04-03T12:30:00Z"),
        )

    private fun platformVenueDetail(): PlatformVenueDetail =
        PlatformVenueDetail(
            id = 9001L,
            name = "Новая кальянная",
            city = "Москва",
            address = null,
            status = VenueStatus.DRAFT,
            createdAt = Instant.parse("2026-04-03T12:31:00Z"),
            deletedAt = null,
        )
}
