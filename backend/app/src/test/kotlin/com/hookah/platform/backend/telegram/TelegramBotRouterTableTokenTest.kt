package com.hookah.platform.backend.telegram

import com.hookah.platform.backend.api.DatabaseUnavailableException
import com.hookah.platform.backend.miniapp.guest.db.CreateInviteResult
import com.hookah.platform.backend.miniapp.guest.db.BookingStatus
import com.hookah.platform.backend.miniapp.guest.db.GuestBookingRepository
import com.hookah.platform.backend.miniapp.guest.db.GuestMenuRepository
import com.hookah.platform.backend.miniapp.guest.db.GuestTabModel
import com.hookah.platform.backend.miniapp.guest.db.GuestTabsRepository
import com.hookah.platform.backend.miniapp.guest.db.MenuCategoryModel
import com.hookah.platform.backend.miniapp.guest.db.MenuItemModel
import com.hookah.platform.backend.miniapp.guest.db.MenuModel
import com.hookah.platform.backend.miniapp.guest.db.UserBookingSummaryRecord
import com.hookah.platform.backend.miniapp.guest.db.TableSessionRecord
import com.hookah.platform.backend.miniapp.guest.db.TableSessionRepository
import com.hookah.platform.backend.miniapp.guest.db.TableSessionStatus
import com.hookah.platform.backend.miniapp.subscription.SubscriptionStatus
import com.hookah.platform.backend.miniapp.subscription.db.SubscriptionRepository
import com.hookah.platform.backend.miniapp.venue.menu.VenueMenuCategory
import com.hookah.platform.backend.miniapp.venue.menu.VenueMenuItem
import com.hookah.platform.backend.miniapp.venue.menu.VenueMenuRepository
import com.hookah.platform.backend.miniapp.venue.VenueStatus
import com.hookah.platform.backend.platform.PlatformVenueDetail
import com.hookah.platform.backend.platform.PlatformVenueRepository
import com.hookah.platform.backend.telegram.db.ChatContextRepository
import com.hookah.platform.backend.telegram.db.ActiveOrderDetails
import com.hookah.platform.backend.telegram.db.CreatedOrderBatch
import com.hookah.platform.backend.telegram.db.DialogStateRepository
import com.hookah.platform.backend.telegram.db.IdempotencyRepository
import com.hookah.platform.backend.telegram.db.OrderBatchDetails
import com.hookah.platform.backend.telegram.db.OrderBatchItemDetails
import com.hookah.platform.backend.telegram.db.OrdersRepository
import com.hookah.platform.backend.telegram.db.CatalogVenueShort
import com.hookah.platform.backend.telegram.db.StaffCallRepository
import com.hookah.platform.backend.telegram.db.StaffChatLinkCodeRepository
import com.hookah.platform.backend.telegram.db.StoredChatContext
import com.hookah.platform.backend.telegram.db.TableTokenRepository
import com.hookah.platform.backend.telegram.db.UserActiveOrderItemSummary
import com.hookah.platform.backend.telegram.db.UserActiveOrderSummary
import com.hookah.platform.backend.telegram.db.UserRepository
import com.hookah.platform.backend.telegram.db.VenueAccessRepository
import com.hookah.platform.backend.telegram.db.VenueBookingHoursRepository
import com.hookah.platform.backend.telegram.db.VenueBookingHours
import com.hookah.platform.backend.telegram.db.VenueConnectionRequestRepository
import com.hookah.platform.backend.telegram.db.VenueInfoSection
import com.hookah.platform.backend.telegram.db.VenueInfoSectionMediaAttachment
import com.hookah.platform.backend.telegram.db.VenueInfoSectionMediaRepository
import com.hookah.platform.backend.telegram.db.VenueInfoSectionsRepository
import com.hookah.platform.backend.telegram.db.VenueMenuSectionImagesRepository
import com.hookah.platform.backend.telegram.db.VenueRepository
import com.hookah.platform.backend.telegram.db.VenueShort
import com.hookah.platform.backend.miniapp.guest.db.BookingRecord
import io.mockk.coEvery
import io.mockk.coVerifyOrder
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant
import java.time.LocalTime

class TelegramBotRouterTableTokenTest {
    private val apiClient: TelegramApiClient = mockk(relaxed = true)
    private val outboxEnqueuer: TelegramOutboxEnqueuer = mockk(relaxed = true)
    private val idempotencyRepository: IdempotencyRepository = mockk()
    private val userRepository: UserRepository = mockk(relaxed = true)
    private val tableTokenRepository: TableTokenRepository = mockk()
    private val chatContextRepository: ChatContextRepository = mockk()
    private val dialogStateRepository: DialogStateRepository = mockk()
    private val ordersRepository: OrdersRepository = mockk()
    private val staffCallRepository: StaffCallRepository = mockk()
    private val staffChatLinkCodeRepository: StaffChatLinkCodeRepository = mockk()
    private val guestBookingRepository: GuestBookingRepository = mockk(relaxed = true)
    private val venueRepository: VenueRepository = mockk()
    private val venueBookingHoursRepository: VenueBookingHoursRepository = mockk(relaxed = true)
    private val venueConnectionRequestRepository: VenueConnectionRequestRepository = mockk(relaxed = true)
    private val venueInfoSectionsRepository: VenueInfoSectionsRepository = mockk(relaxed = true)
    private val venueInfoSectionMediaRepository: VenueInfoSectionMediaRepository = mockk(relaxed = true)
    private val venueMenuSectionImagesRepository: VenueMenuSectionImagesRepository = mockk(relaxed = true)
    private val venueMenuRepository: VenueMenuRepository = mockk(relaxed = true)
    private val venueAccessRepository: VenueAccessRepository = mockk()
    private val subscriptionRepository: SubscriptionRepository = mockk()
    private val guestMenuRepository: GuestMenuRepository = mockk()
    private val tableSessionRepository: TableSessionRepository = mockk()
    private val guestTabsRepository: GuestTabsRepository = mockk()
    private val platformVenueRepository: PlatformVenueRepository = mockk(relaxed = true)
    private val router =
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
            venueMenuRepository = venueMenuRepository,
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
            venueInfoSectionsRepository = venueInfoSectionsRepository,
            venueInfoSectionMediaRepository = venueInfoSectionMediaRepository,
        )

    @BeforeEach
    fun setup() {
        coEvery { idempotencyRepository.tryAcquire(any(), any(), any()) } returns true
        coEvery { dialogStateRepository.get(any()) } returns DialogState(DialogStateType.NONE)
        coEvery { dialogStateRepository.set(any(), any()) } returns Unit
        coEvery { dialogStateRepository.clear(any()) } returns Unit
        coEvery { chatContextRepository.saveContext(any(), any(), any()) } returns Unit
        coEvery { venueMenuSectionImagesRepository.ensureBotTestVenueMenuSectionImages() } returns Unit
        coEvery { venueMenuRepository.getMenu(any()) } returns emptyList()
        coEvery { venueMenuRepository.createCategory(any(), any()) } answers
            {
                val venueId = invocation.args[0] as Long
                val name = invocation.args[1] as String
                VenueMenuCategory(
                    id = 1000L,
                    venueId = venueId,
                    name = name,
                    sortOrder = 10,
                    items = emptyList(),
                )
            }
        coEvery { guestBookingRepository.listActiveByUser(any(), any()) } returns emptyList()
        coEvery { ordersRepository.listActiveOrderSummariesForUser(any(), any()) } returns emptyList<UserActiveOrderSummary>()
        coEvery { platformVenueRepository.getVenueDetail(any()) } returns null
        coEvery { venueConnectionRequestRepository.findPendingByUser(any()) } returns null
        coEvery { venueInfoSectionsRepository.ensureDefaultSections(any()) } returns true
        coEvery { venueInfoSectionsRepository.listSections(any()) } returns emptyList()
        coEvery { venueInfoSectionsRepository.findSectionById(any(), any()) } returns null
        coEvery { venueInfoSectionMediaRepository.countBySectionIds(any()) } returns emptyMap()
        coEvery { venueInfoSectionMediaRepository.countBySectionId(any()) } returns 0
        coEvery { venueInfoSectionMediaRepository.listBySectionId(any()) } returns emptyList()
        coEvery { venueInfoSectionMediaRepository.deleteAttachment(any(), any()) } returns false
        coEvery { apiClient.sendMessage(any(), any(), any()) } returns null
        coEvery {
            tableSessionRepository.resolveActiveSession(
                venueId = any(),
                tableId = any(),
                ttl = any(),
                now = any(),
            )
        } returns
            TableSessionRecord(
                id = 55L,
                venueId = 10L,
                tableId = 11L,
                startedAt = Instant.parse("2026-03-30T10:00:00Z").minusSeconds(60),
                lastActivityAt = Instant.parse("2026-03-30T10:00:00Z"),
                expiresAt = Instant.parse("2026-03-30T10:00:00Z").plusSeconds(7200),
                endedAt = null,
                status = TableSessionStatus.ACTIVE,
            )
        coEvery {
            guestTabsRepository.ensurePersonalTab(
                venueId = any(),
                tableSessionId = any(),
                userId = any(),
            )
        } answers
            {
                GuestTabModel(
                    id = 1L,
                    venueId = 10L,
                    tableSessionId = 55L,
                    type = "PERSONAL",
                    ownerUserId = 200L,
                    status = "ACTIVE",
                )
            }
        coEvery {
            guestTabsRepository.listTabsForUser(
                venueId = any(),
                tableSessionId = any(),
                userId = any(),
            )
        } answers
            {
                listOf(
                    GuestTabModel(
                        id = 1L,
                        venueId = 10L,
                        tableSessionId = 55L,
                        type = "PERSONAL",
                        ownerUserId = 200L,
                        status = "ACTIVE",
                    ),
                )
            }
        coEvery {
            guestTabsRepository.isTabMember(
                tabId = any(),
                venueId = any(),
                tableSessionId = any(),
                userId = any(),
            )
        } returns true
        coEvery {
            guestTabsRepository.joinByInvite(
                venueId = any(),
                tableSessionId = any(),
                userId = any(),
                token = any(),
            )
        } returns null
        coEvery { guestTabsRepository.deleteExpiredInvites() } returns Unit
        coEvery {
            guestTabsRepository.createInvite(
                tabId = any(),
                venueId = any(),
                tableSessionId = any(),
                createdBy = any(),
                token = any(),
                expiresAt = any(),
            )
        } returns CreateInviteResult.CREATED
        coEvery { ordersRepository.findActiveOrderSummaryForTab(any(), any()) } returns null
        coEvery { ordersRepository.findActiveOrderDetailsForTab(any(), any()) } returns null
    }

    @Test
    fun `start with valid table token removes old reply keyboard and shows channel choice`() =
        runBlocking {
            val context =
                TableContext(
                    venueId = 10L,
                    venueName = "Venue",
                    tableId = 11L,
                    tableNumber = 5,
                    tableToken = "TOKEN",
                    staffChatId = null,
                )
            coEvery { tableTokenRepository.resolve("TOKEN") } returns context
            coEvery { subscriptionRepository.getSubscriptionStatus(10L) } returns SubscriptionStatus.ACTIVE

            val update =
                TelegramUpdate(
                    updateId = 9,
                    message =
                        Message(
                            messageId = 19,
                            chat = Chat(id = 100, type = "private"),
                            fromUser = User(id = 200),
                            text = "/start TOKEN",
                        ),
                )

            router.process(update)

            coVerifyOrder {
                outboxEnqueuer.enqueueSendMessage(
                    100,
                    "\u2060",
                    match { it is ReplyKeyboardRemove },
                )
                outboxEnqueuer.enqueueSendMessage(
                    100,
                    "Вы за столом №5 в Venue. Выберите удобный способ заказа.",
                    match { it is InlineKeyboardMarkup },
                )
            }
            coVerify(exactly = 2) { outboxEnqueuer.enqueueSendMessage(100, any(), any()) }
        }

    @Test
    fun `start without token opens owner menu for platform owner`() =
        runBlocking {
            router.process(
                TelegramUpdate(
                    updateId = 10_000,
                    message =
                        Message(
                            messageId = 18,
                            chat = Chat(id = 100, type = "private"),
                            fromUser = User(id = 999L),
                            text = "/start",
                        ),
                ),
            )

            coVerify {
                outboxEnqueuer.enqueueSendMessage(
                    100,
                    "Панель владельца платформы. Выберите раздел.",
                    match {
                        it is ReplyKeyboardMarkup &&
                            it.keyboard.flatten().any { button -> button.text == "📨 Заявки на подключение" }
                    },
                )
            }
        }

    @Test
    fun `start without token opens venue owner entry for venue owner`() =
        runBlocking {
            coEvery { venueAccessRepository.listVenueMemberships(200L) } returns
                listOf(
                    VenueAccessRepository.VenueMembership(
                        venueId = 10L,
                        role = "OWNER",
                    ),
                )
            coEvery { venueRepository.findVenueById(10L) } returns
                VenueShort(
                    id = 10L,
                    name = "Тестовая кальянная",
                    staffChatId = null,
                )

            router.process(
                TelegramUpdate(
                    updateId = 10_003,
                    message =
                        Message(
                            messageId = 20_003,
                            chat = Chat(id = 100, type = "private"),
                            fromUser = User(id = 200L),
                            text = "/start",
                        ),
                ),
            )

            coVerify {
                outboxEnqueuer.enqueueSendMessage(
                    100,
                    match {
                        it.contains("💬 Режим настройки в боте") &&
                            it.contains("Тестовая кальянная (OWNER)")
                    },
                    match {
                        it is ReplyKeyboardMarkup &&
                            it.keyboard.map { row -> row.map { button -> button.text } } ==
                            listOf(
                                listOf("🏢 Моё заведение", "🍽 Меню заведения"),
                                listOf("🪑 Столы и QR", "👥 Персонал"),
                                listOf("📦 Заказы", "⚙️ Настройки"),
                            )
                    },
                )
            }
            coVerify(exactly = 0) {
                outboxEnqueuer.enqueueSendMessage(
                    100,
                    "Добро пожаловать! Выберите действие.",
                    any(),
                )
            }
        }

    @Test
    fun `menu command opens main menu`() =
        runBlocking {
            coEvery { venueAccessRepository.listVenueMemberships(200L) } returns emptyList()
            coEvery { venueAccessRepository.hasVenueRole(200L) } returns false

            router.process(
                TelegramUpdate(
                    updateId = 10_001,
                    message =
                        Message(
                            messageId = 20_001,
                            chat = Chat(id = 100, type = "private"),
                            fromUser = User(id = 200),
                            text = "/menu",
                        ),
                ),
            )

            coVerify {
                outboxEnqueuer.enqueueSendMessage(
                    100,
                    "Добро пожаловать! Выберите действие.",
                    match {
                        it is ReplyKeyboardMarkup &&
                            it.keyboard.map { row -> row.map { button -> button.text } } ==
                            listOf(
                                listOf("🍽 Каталог кальянных", "📱 Открыть Mini App"),
                                listOf("🎁 Акции", "🪑 Я за столом / У меня QR"),
                                listOf("📄 Мои заказы и брони", "🤝 Добавить свою кальянную"),
                            )
                    },
                )
            }
        }

    @Test
    fun `fallback keeps venue owner keyboard for owner user`() =
        runBlocking {
            coEvery { venueAccessRepository.listVenueMemberships(200L) } returns
                listOf(
                    VenueAccessRepository.VenueMembership(
                        venueId = 10L,
                        role = "OWNER",
                    ),
                )

            router.process(
                TelegramUpdate(
                    updateId = 10_001_05,
                    message =
                        Message(
                            messageId = 20_001_05,
                            chat = Chat(id = 100, type = "private"),
                            fromUser = User(id = 200L),
                            text = "неизвестная команда",
                        ),
                ),
            )

            coVerify {
                outboxEnqueuer.enqueueSendMessage(
                    100,
                    "Используйте меню ниже.",
                    match {
                        it is ReplyKeyboardMarkup &&
                            it.keyboard.map { row -> row.map { button -> button.text } } ==
                            listOf(
                                listOf("🏢 Моё заведение", "🍽 Меню заведения"),
                                listOf("🪑 Столы и QR", "👥 Персонал"),
                                listOf("📦 Заказы", "⚙️ Настройки"),
                            )
                    },
                )
            }
            coVerify(exactly = 0) { chatContextRepository.get(100) }
        }

    @Test
    fun `menu command opens venue owner entry for venue owner`() =
        runBlocking {
            coEvery { venueAccessRepository.listVenueMemberships(200L) } returns
                listOf(
                    VenueAccessRepository.VenueMembership(
                        venueId = 10L,
                        role = "OWNER",
                    ),
                )
            coEvery { venueRepository.findVenueById(10L) } returns
                VenueShort(
                    id = 10L,
                    name = "Тестовая кальянная",
                    staffChatId = null,
                )

            router.process(
                TelegramUpdate(
                    updateId = 10_001_1,
                    message =
                        Message(
                            messageId = 20_001_1,
                            chat = Chat(id = 100, type = "private"),
                            fromUser = User(id = 200L),
                            text = "/menu",
                        ),
                ),
            )

            coVerify {
                outboxEnqueuer.enqueueSendMessage(
                    100,
                    match {
                        it.contains("💬 Режим настройки в боте") &&
                            it.contains("Тестовая кальянная (OWNER)")
                    },
                    match {
                        it is ReplyKeyboardMarkup &&
                            it.keyboard.map { row -> row.map { button -> button.text } } ==
                            listOf(
                                listOf("🏢 Моё заведение", "🍽 Меню заведения"),
                                listOf("🪑 Столы и QR", "👥 Персонал"),
                                listOf("📦 Заказы", "⚙️ Настройки"),
                            )
                    },
                )
            }
        }

    @Test
    fun `venue owner button shows venue card with inline field actions`() =
        runBlocking {
            coEvery { venueAccessRepository.listVenueMemberships(200L) } returns
                listOf(
                    VenueAccessRepository.VenueMembership(
                        venueId = 10L,
                        role = "OWNER",
                    ),
                )
            coEvery { venueRepository.findVenueById(10L) } returns
                VenueShort(
                    id = 10L,
                    name = "Тестовая кальянная",
                    staffChatId = null,
                )

            router.process(
                TelegramUpdate(
                    updateId = 10_004,
                    message =
                        Message(
                            messageId = 20_004,
                            chat = Chat(id = 100, type = "private"),
                            fromUser = User(id = 200L),
                            text = "🏢 Моё заведение",
                        ),
                ),
            )

            coVerify {
                outboxEnqueuer.enqueueSendMessage(
                    100,
                    match {
                        it.contains("🏢 Моё заведение") &&
                            it.contains("Название: Тестовая кальянная") &&
                            it.contains("Город: —") &&
                            it.contains("Адрес: —") &&
                            it.contains("Статус: —") &&
                            it.contains("Описание: —")
                    },
                    match {
                        it is InlineKeyboardMarkup &&
                            it.inlineKeyboard.map { row -> row.map { button -> button.text } } ==
                            listOf(
                                listOf("✏️ Название", "📍 Адрес"),
                                listOf("🕒 Часы работы", "📝 Описание"),
                                listOf("👁 Предпросмотр"),
                                listOf("✅ Готовность к публикации"),
                            )
                    },
                )
            }
        }

    @Test
    fun `venue owner menu button opens order menu root screen with default sections`() =
        runBlocking {
            coEvery { venueAccessRepository.listVenueMemberships(200L) } returns
                listOf(
                    VenueAccessRepository.VenueMembership(
                        venueId = 10L,
                        role = "OWNER",
                    ),
                )
            coEvery { venueRepository.findVenueById(10L) } returns
                VenueShort(
                    id = 10L,
                    name = "Тестовая кальянная",
                    staffChatId = null,
                )
            coEvery { venueAccessRepository.hasVenueAdminOrOwner(200L, 10L) } returns true
            coEvery { venueMenuRepository.getMenu(10L) } returnsMany
                listOf(
                    emptyList(),
                    listOf(
                        VenueMenuCategory(
                            id = 501L,
                            venueId = 10L,
                            name = "Кальянное меню",
                            sortOrder = 10,
                            items = emptyList(),
                        ),
                        VenueMenuCategory(
                            id = 502L,
                            venueId = 10L,
                            name = "Напитки",
                            sortOrder = 20,
                            items = emptyList(),
                        ),
                        VenueMenuCategory(
                            id = 503L,
                            venueId = 10L,
                            name = "Кухня",
                            sortOrder = 30,
                            items = emptyList(),
                        ),
                    ),
                )

            router.process(
                TelegramUpdate(
                    updateId = 10_004_1,
                    message =
                        Message(
                            messageId = 20_004_1,
                            chat = Chat(id = 100, type = "private"),
                            fromUser = User(id = 200L),
                            text = "🍽 Меню заведения",
                        ),
                ),
            )

            coVerify {
                outboxEnqueuer.enqueueSendMessage(
                    100,
                    "🍽 Заказное меню\nВыберите раздел.",
                    match {
                        it is InlineKeyboardMarkup &&
                            it.inlineKeyboard.map { row -> row.map { button -> button.text } } ==
                            listOf(
                                listOf("Кальянное меню"),
                                listOf("Напитки"),
                                listOf("Кухня"),
                                listOf("➕ Добавить раздел"),
                                listOf("🚫 Стоп-лист"),
                            ) &&
                            it.inlineKeyboard[0][0].callbackData == "owner_venue_order_menu_section:10:501" &&
                            it.inlineKeyboard[1][0].callbackData == "owner_venue_order_menu_section:10:502" &&
                            it.inlineKeyboard[2][0].callbackData == "owner_venue_order_menu_section:10:503" &&
                            it.inlineKeyboard[3][0].callbackData == "owner_venue_order_menu_add:10" &&
                            it.inlineKeyboard[4][0].callbackData == "owner_venue_order_menu_stoplist:10"
                    },
                )
            }
        }

    @Test
    fun `owner order menu add section callback prompts for section title`() =
        runBlocking {
            coEvery { venueAccessRepository.hasVenueAdminOrOwner(200L, 10L) } returns true

            router.process(
                TelegramUpdate(
                    updateId = 10_004_2,
                    callbackQuery =
                        CallbackQuery(
                            id = "cb-owner-order-menu-add",
                            from = User(id = 200L),
                            message = Message(messageId = 30_004_2, chat = Chat(id = 100, type = "private")),
                            data = "owner_venue_order_menu_add:10",
                        ),
                ),
            )

            coVerify {
                dialogStateRepository.set(
                    100,
                    match {
                        it.state == DialogStateType.OWNER_VENUE_ORDER_MENU_WAIT_SECTION_TITLE &&
                            it.payload["venue_id"] == "10" &&
                            it.payload["owner_user_id"] == "200"
                    },
                )
            }
            coVerify {
                outboxEnqueuer.enqueueSendMessage(
                    100,
                    "Введите название нового раздела.\nОтправьте «—», чтобы отменить.",
                    null,
                )
            }
        }

    @Test
    fun `owner order menu add item callback prompts for item name`() =
        runBlocking {
            coEvery { venueAccessRepository.hasVenueAdminOrOwner(200L, 10L) } returns true
            coEvery { venueMenuRepository.getMenu(10L) } returns
                listOf(
                    VenueMenuCategory(
                        id = 501L,
                        venueId = 10L,
                        name = "Кальянное меню",
                        sortOrder = 10,
                        items = emptyList(),
                    ),
                )

            router.process(
                TelegramUpdate(
                    updateId = 10_004_25,
                    callbackQuery =
                        CallbackQuery(
                            id = "cb-owner-order-menu-item-add",
                            from = User(id = 200L),
                            message = Message(messageId = 30_004_25, chat = Chat(id = 100, type = "private")),
                            data = "owner_venue_order_menu_item_add:10:501",
                        ),
                ),
            )

            coVerify {
                dialogStateRepository.set(
                    100,
                    match {
                        it.state == DialogStateType.OWNER_VENUE_ORDER_MENU_WAIT_ITEM_NAME &&
                            it.payload["venue_id"] == "10" &&
                            it.payload["section_id"] == "501" &&
                            it.payload["owner_user_id"] == "200"
                    },
                )
            }
            coVerify {
                outboxEnqueuer.enqueueSendMessage(
                    100,
                    "Введите название позиции.\nОтправьте «—», чтобы отменить.",
                    null,
                )
            }
        }

    @Test
    fun `owner order menu add item flow saves item and returns to section`() =
        runBlocking {
            coEvery { venueAccessRepository.hasVenueAdminOrOwner(200L, 10L) } returns true
            coEvery { venueMenuRepository.createItem(10L, 501L, "Авторский кальян", 85_000L, "RUB", true) } returns
                VenueMenuItem(
                    id = 7001L,
                    venueId = 10L,
                    categoryId = 501L,
                    name = "Авторский кальян",
                    priceMinor = 85_000L,
                    currency = "RUB",
                    isAvailable = true,
                    sortOrder = 10,
                    options = emptyList(),
                )
            coEvery { venueMenuRepository.getMenu(10L) } returns
                listOf(
                    VenueMenuCategory(
                        id = 501L,
                        venueId = 10L,
                        name = "Кальянное меню",
                        sortOrder = 10,
                        items =
                            listOf(
                                VenueMenuItem(
                                    id = 7001L,
                                    venueId = 10L,
                                    categoryId = 501L,
                                    name = "Авторский кальян",
                                    priceMinor = 85_000L,
                                    currency = "RUB",
                                    isAvailable = true,
                                    sortOrder = 10,
                                    options = emptyList(),
                                ),
                            ),
                    ),
                )
            coEvery { dialogStateRepository.get(100) } returnsMany
                listOf(
                    DialogState(
                        state = DialogStateType.OWNER_VENUE_ORDER_MENU_WAIT_ITEM_NAME,
                        payload =
                            mapOf(
                                "venue_id" to "10",
                                "section_id" to "501",
                                "owner_user_id" to "200",
                            ),
                    ),
                    DialogState(
                        state = DialogStateType.OWNER_VENUE_ORDER_MENU_WAIT_ITEM_PRICE,
                        payload =
                            mapOf(
                                "venue_id" to "10",
                                "section_id" to "501",
                                "owner_user_id" to "200",
                                "item_name" to "Авторский кальян",
                            ),
                    ),
                )

            router.process(
                TelegramUpdate(
                    updateId = 10_004_26,
                    message =
                        Message(
                            messageId = 20_004_26,
                            chat = Chat(id = 100, type = "private"),
                            fromUser = User(id = 200L),
                            text = "Авторский кальян",
                        ),
                ),
            )
            router.process(
                TelegramUpdate(
                    updateId = 10_004_27,
                    message =
                        Message(
                            messageId = 20_004_27,
                            chat = Chat(id = 100, type = "private"),
                            fromUser = User(id = 200L),
                            text = "850",
                        ),
                ),
            )

            coVerify {
                dialogStateRepository.set(
                    100,
                    match {
                        it.state == DialogStateType.OWNER_VENUE_ORDER_MENU_WAIT_ITEM_PRICE &&
                            it.payload["item_name"] == "Авторский кальян"
                    },
                )
            }
            coVerify {
                venueMenuRepository.createItem(10L, 501L, "Авторский кальян", 85_000L, "RUB", true)
            }
            coVerify {
                outboxEnqueuer.enqueueSendMessage(
                    100,
                    "✅ Позиция добавлена.",
                    null,
                )
            }
            coVerify {
                outboxEnqueuer.enqueueSendMessage(
                    100,
                    match {
                        it.contains("🍽 Раздел: Кальянное меню") &&
                            it.contains("Позиции: 1") &&
                            it.contains("• Авторский кальян — 850 ₽")
                    },
                    any(),
                )
            }
        }

    @Test
    fun `owner order menu section shows clickable items`() =
        runBlocking {
            coEvery { venueAccessRepository.hasVenueAdminOrOwner(200L, 10L) } returns true
            coEvery { venueMenuRepository.getMenu(10L) } returns
                listOf(
                    VenueMenuCategory(
                        id = 501L,
                        venueId = 10L,
                        name = "Кальянное меню",
                        sortOrder = 10,
                        items =
                            listOf(
                                VenueMenuItem(
                                    id = 7001L,
                                    venueId = 10L,
                                    categoryId = 501L,
                                    name = "Авторский кальян",
                                    priceMinor = 85_000L,
                                    currency = "RUB",
                                    isAvailable = true,
                                    sortOrder = 10,
                                    options = emptyList(),
                                ),
                            ),
                    ),
                )

            router.process(
                TelegramUpdate(
                    updateId = 10_004_28,
                    callbackQuery =
                        CallbackQuery(
                            id = "cb-owner-order-menu-section",
                            from = User(id = 200L),
                            message = Message(messageId = 30_004_28, chat = Chat(id = 100, type = "private")),
                            data = "owner_venue_order_menu_section:10:501",
                        ),
                ),
            )

            coVerify {
                outboxEnqueuer.enqueueSendMessage(
                    100,
                    match {
                        it.contains("🍽 Раздел: Кальянное меню") &&
                            it.contains("Позиции: 1") &&
                            it.contains("• Авторский кальян — 850 ₽")
                    },
                    match {
                        it is InlineKeyboardMarkup &&
                            it.inlineKeyboard[0][0].text == "Авторский кальян — 850 ₽" &&
                            it.inlineKeyboard[0][0].callbackData == "owner_venue_order_menu_item:10:501:7001"
                    },
                )
            }
        }

    @Test
    fun `owner order menu item stop action updates status to stoplist`() =
        runBlocking {
            coEvery { venueAccessRepository.hasVenueAdminOrOwner(200L, 10L) } returns true
            coEvery { venueMenuRepository.setItemAvailability(10L, 7001L, false) } returns
                VenueMenuItem(
                    id = 7001L,
                    venueId = 10L,
                    categoryId = 501L,
                    name = "Авторский кальян",
                    priceMinor = 85_000L,
                    currency = "RUB",
                    isAvailable = false,
                    sortOrder = 10,
                    options = emptyList(),
                )
            coEvery { venueMenuRepository.getMenu(10L) } returnsMany
                listOf(
                    listOf(
                        VenueMenuCategory(
                            id = 501L,
                            venueId = 10L,
                            name = "Кальянное меню",
                            sortOrder = 10,
                            items =
                                listOf(
                                    VenueMenuItem(
                                        id = 7001L,
                                        venueId = 10L,
                                        categoryId = 501L,
                                        name = "Авторский кальян",
                                        priceMinor = 85_000L,
                                        currency = "RUB",
                                        isAvailable = true,
                                        sortOrder = 10,
                                        options = emptyList(),
                                    ),
                                ),
                        ),
                    ),
                    listOf(
                        VenueMenuCategory(
                            id = 501L,
                            venueId = 10L,
                            name = "Кальянное меню",
                            sortOrder = 10,
                            items =
                                listOf(
                                    VenueMenuItem(
                                        id = 7001L,
                                        venueId = 10L,
                                        categoryId = 501L,
                                        name = "Авторский кальян",
                                        priceMinor = 85_000L,
                                        currency = "RUB",
                                        isAvailable = false,
                                        sortOrder = 10,
                                        options = emptyList(),
                                    ),
                                ),
                        ),
                    ),
                )

            router.process(
                TelegramUpdate(
                    updateId = 10_004_29,
                    callbackQuery =
                        CallbackQuery(
                            id = "cb-owner-order-menu-item-stop",
                            from = User(id = 200L),
                            message = Message(messageId = 30_004_29, chat = Chat(id = 100, type = "private")),
                            data = "owner_venue_order_menu_item_stop:10:501:7001",
                        ),
                ),
            )

            coVerify { venueMenuRepository.setItemAvailability(10L, 7001L, false) }
            coVerify {
                outboxEnqueuer.enqueueSendMessage(
                    100,
                    "✅ Позиция добавлена в стоп-лист.",
                    null,
                )
            }
            coVerify {
                outboxEnqueuer.enqueueSendMessage(
                    100,
                    match {
                        it.contains("Авторский кальян") &&
                            it.contains("Цена: 850 ₽") &&
                            it.contains("Статус: В стоп-листе")
                    },
                    match {
                        it is InlineKeyboardMarkup &&
                            it.inlineKeyboard.flatten().any { button ->
                                button.text == "✅ Убрать из стоп-листа" &&
                                    button.callbackData == "owner_venue_order_menu_item_unstop:10:501:7001"
                            }
                    },
                )
            }
        }

    @Test
    fun `owner order menu stoplist callback opens entry screen`() =
        runBlocking {
            coEvery { venueAccessRepository.hasVenueAdminOrOwner(200L, 10L) } returns true

            router.process(
                TelegramUpdate(
                    updateId = 10_004_3,
                    callbackQuery =
                        CallbackQuery(
                            id = "cb-owner-order-menu-stoplist",
                            from = User(id = 200L),
                            message = Message(messageId = 30_004_3, chat = Chat(id = 100, type = "private")),
                            data = "owner_venue_order_menu_stoplist:10",
                        ),
                ),
            )

            coVerify {
                outboxEnqueuer.enqueueSendMessage(
                    100,
                    "🚫 Стоп-лист\nЗдесь будут позиции, скрытые для гостей.\n\nСтоп-лист пока пуст.",
                    match {
                        it is InlineKeyboardMarkup &&
                            it.inlineKeyboard.size == 1 &&
                            it.inlineKeyboard[0][0].text == "⬅️ Назад к меню" &&
                            it.inlineKeyboard[0][0].callbackData == "owner_venue_order_menu_root:10"
                    },
                )
            }
        }

    @Test
    fun `owner order menu stoplist callback shows real stoplist items`() =
        runBlocking {
            coEvery { venueAccessRepository.hasVenueAdminOrOwner(200L, 10L) } returns true
            coEvery { venueMenuRepository.getMenu(10L) } returns
                listOf(
                    VenueMenuCategory(
                        id = 501L,
                        venueId = 10L,
                        name = "Кальянное меню",
                        sortOrder = 10,
                        items =
                            listOf(
                                VenueMenuItem(
                                    id = 7001L,
                                    venueId = 10L,
                                    categoryId = 501L,
                                    name = "Авторский кальян",
                                    priceMinor = 85_000L,
                                    currency = "RUB",
                                    isAvailable = false,
                                    sortOrder = 10,
                                    options = emptyList(),
                                ),
                            ),
                    ),
                )

            router.process(
                TelegramUpdate(
                    updateId = 10_004_31,
                    callbackQuery =
                        CallbackQuery(
                            id = "cb-owner-order-menu-stoplist-filled",
                            from = User(id = 200L),
                            message = Message(messageId = 30_004_31, chat = Chat(id = 100, type = "private")),
                            data = "owner_venue_order_menu_stoplist:10",
                        ),
                ),
            )

            coVerify {
                outboxEnqueuer.enqueueSendMessage(
                    100,
                    match {
                        it.contains("🚫 Стоп-лист") &&
                            it.contains("Кальянное меню") &&
                            it.contains("• Авторский кальян — 850 ₽")
                    },
                    match {
                        it is InlineKeyboardMarkup &&
                            it.inlineKeyboard[0][0].text == "✅ Убрать из стоп-листа: Авторский кальян" &&
                            it.inlineKeyboard[0][0].callbackData == "owner_venue_order_menu_stoplist_unstop:10:501:7001"
                    },
                )
            }
        }

    @Test
    fun `owner order menu stoplist unstop action refreshes root stoplist screen`() =
        runBlocking {
            coEvery { venueAccessRepository.hasVenueAdminOrOwner(200L, 10L) } returns true
            coEvery { venueMenuRepository.setItemAvailability(10L, 7001L, true) } returns
                VenueMenuItem(
                    id = 7001L,
                    venueId = 10L,
                    categoryId = 501L,
                    name = "Авторский кальян",
                    priceMinor = 85_000L,
                    currency = "RUB",
                    isAvailable = true,
                    sortOrder = 10,
                    options = emptyList(),
                )
            coEvery { venueMenuRepository.getMenu(10L) } returns emptyList()

            router.process(
                TelegramUpdate(
                    updateId = 10_004_32,
                    callbackQuery =
                        CallbackQuery(
                            id = "cb-owner-order-menu-stoplist-unstop",
                            from = User(id = 200L),
                            message = Message(messageId = 30_004_32, chat = Chat(id = 100, type = "private")),
                            data = "owner_venue_order_menu_stoplist_unstop:10:501:7001",
                        ),
                ),
            )

            coVerify {
                venueMenuRepository.setItemAvailability(10L, 7001L, true)
            }
            coVerifyOrder {
                outboxEnqueuer.enqueueSendMessage(
                    100,
                    "✅ Позиция убрана из стоп-листа.",
                    null,
                )
                outboxEnqueuer.enqueueSendMessage(
                    100,
                    "🚫 Стоп-лист\nЗдесь будут позиции, скрытые для гостей.\n\nСтоп-лист пока пуст.",
                    any(),
                )
            }
        }

    @Test
    fun `owner preview opens guest venue card for draft venue`() =
        runBlocking {
            coEvery { venueAccessRepository.hasVenueAdminOrOwner(200L, 10L) } returns true
            coEvery { venueRepository.findCatalogVenueByIdForGuest(10L) } returns null
            coEvery { platformVenueRepository.getVenueDetail(10L) } returns
                PlatformVenueDetail(
                    id = 10L,
                    name = "Тестовая кальянная",
                    city = "Москва",
                    address = "Тверская, 1",
                    status = VenueStatus.DRAFT,
                    createdAt = Instant.parse("2026-04-01T12:00:00Z"),
                    deletedAt = null,
                )

            router.process(
                TelegramUpdate(
                    updateId = 10_004_1,
                    callbackQuery =
                        CallbackQuery(
                            id = "cb-owner-venue-preview",
                            from = User(id = 200L),
                            message = Message(messageId = 30_004_1, chat = Chat(id = 100, type = "private")),
                            data = "owner_venue_publish_preview:10",
                        ),
                ),
            )

            coVerify {
                outboxEnqueuer.enqueueSendMessage(
                    100,
                    match {
                        it.contains("Это предпросмотр. Заведение пока не опубликовано.") &&
                            it.contains("Тестовая кальянная") &&
                            it.contains("📍 Тверская, 1") &&
                            it.contains("Что хотите сделать?")
                    },
                    match {
                        it is InlineKeyboardMarkup &&
                            it.inlineKeyboard.flatten().any { button ->
                                button.text == "🪑 Забронировать стол" &&
                                    button.callbackData == "bot_catalog_venue_book:10"
                            } &&
                            it.inlineKeyboard.flatten().any { button ->
                                button.text == "🍽 Меню" &&
                                    button.callbackData == "bot_catalog_venue_menu:10"
                            }
                    },
                )
            }
        }

    @Test
    fun `venue about opens sections picker with visible sections only`() =
        runBlocking {
            coEvery { venueRepository.findCatalogVenueByIdForGuest(10L) } returns
                CatalogVenueShort(
                    id = 10L,
                    name = "Тестовая кальянная",
                    city = "Москва",
                    address = "Тверская, 1",
                )
            coEvery { venueInfoSectionsRepository.listSections(10L) } returns
                listOf(
                    VenueInfoSection(
                        id = 101L,
                        venueId = 10L,
                        title = "О заведении",
                        sectionType = "about",
                        sortOrder = 10,
                        isVisible = true,
                        textContent = "Текст",
                    ),
                    VenueInfoSection(
                        id = 102L,
                        venueId = 10L,
                        title = "Скрытый раздел",
                        sectionType = "custom",
                        sortOrder = 20,
                        isVisible = false,
                        textContent = "Секрет",
                    ),
                )

            router.process(
                TelegramUpdate(
                    updateId = 10_004_2,
                    callbackQuery =
                        CallbackQuery(
                            id = "cb-venue-about",
                            from = User(id = 200L),
                            message = Message(messageId = 30_004_2, chat = Chat(id = 100, type = "private")),
                            data = "bot_catalog_venue_about:10",
                        ),
                ),
            )

            coVerify {
                outboxEnqueuer.enqueueSendMessage(
                    100,
                    "ℹ️ О заведении\nВыберите раздел.",
                    match {
                        it is InlineKeyboardMarkup &&
                            it.inlineKeyboard.flatten().any { button ->
                                button.text == "О заведении" &&
                                    button.callbackData == "bot_catalog_venue_about_section:10:101"
                            } &&
                            it.inlineKeyboard.flatten().none { button -> button.text == "Скрытый раздел" }
                    },
                )
            }
        }

    @Test
    fun `venue about section shows section content and media attachments`() =
        runBlocking {
            coEvery { venueRepository.findCatalogVenueByIdForGuest(10L) } returns
                CatalogVenueShort(
                    id = 10L,
                    name = "Тестовая кальянная",
                    city = "Москва",
                    address = "Тверская, 1",
                )
            coEvery { venueInfoSectionsRepository.findSectionById(10L, 201L) } returns
                VenueInfoSection(
                    id = 201L,
                    venueId = 10L,
                    title = "Правила посещения",
                    sectionType = "rules",
                    sortOrder = 20,
                    isVisible = true,
                    textContent = "Без собственной еды.",
                )
            coEvery { venueInfoSectionMediaRepository.listBySectionId(201L) } returns
                listOf(
                    VenueInfoSectionMediaAttachment(
                        id = 1L,
                        sectionId = 201L,
                        mediaType = "image",
                        telegramFileId = "photo-file-id",
                        sortOrder = 0,
                    ),
                    VenueInfoSectionMediaAttachment(
                        id = 2L,
                        sectionId = 201L,
                        mediaType = "pdf",
                        telegramFileId = "pdf-file-id",
                        sortOrder = 1,
                    ),
                )

            router.process(
                TelegramUpdate(
                    updateId = 10_004_3,
                    callbackQuery =
                        CallbackQuery(
                            id = "cb-venue-about-section",
                            from = User(id = 200L),
                            message = Message(messageId = 30_004_3, chat = Chat(id = 100, type = "private")),
                            data = "bot_catalog_venue_about_section:10:201",
                        ),
                ),
            )

            coVerify {
                outboxEnqueuer.enqueueSendMessage(
                    100,
                    "ℹ️ Правила посещения\n\nБез собственной еды.",
                    null,
                )
            }
            coVerify {
                outboxEnqueuer.enqueueSendPhoto(
                    100,
                    "photo-file-id",
                    null,
                    null,
                )
            }
            coVerify {
                outboxEnqueuer.enqueueSendDocument(
                    100,
                    "pdf-file-id",
                    null,
                    match {
                        it is InlineKeyboardMarkup &&
                            it.inlineKeyboard.flatten().any { button ->
                                button.text == "⬅️ К разделам" &&
                                    button.callbackData == "bot_catalog_venue_about:10"
                            }
                    },
                )
            }
        }

    @Test
    fun `owner venue field callback opens dedicated field entry`() =
        runBlocking {
            coEvery { venueAccessRepository.hasVenueAdminOrOwner(200L, 10L) } returns true

            router.process(
                TelegramUpdate(
                    updateId = 10_005,
                    callbackQuery =
                        CallbackQuery(
                            id = "cb-owner-venue-field",
                            from = User(id = 200L),
                            message = Message(messageId = 30_005, chat = Chat(id = 100, type = "private")),
                            data = "owner_venue_field:10:name",
                        ),
                ),
            )

            coVerify {
                outboxEnqueuer.enqueueSendMessage(
                    100,
                    "Введите новое название заведения.\nОтправьте «—», чтобы отменить.",
                    null,
                )
            }
        }

    @Test
    fun `owner venue name input updates venue and returns updated card`() =
        runBlocking {
            coEvery { dialogStateRepository.get(100) } returns
                DialogState(
                    state = DialogStateType.OWNER_VENUE_PROFILE_WAIT_NAME,
                    payload =
                        mapOf(
                            "venue_id" to "10",
                            "owner_user_id" to "200",
                        ),
                )
            coEvery { venueAccessRepository.hasVenueAdminOrOwner(200L, 10L) } returns true
            coEvery {
                platformVenueRepository.updateVenueName(venueId = 10L, name = "Новая кальянная")
            } returns
                PlatformVenueDetail(
                    id = 10L,
                    name = "Новая кальянная",
                    city = "Москва",
                    address = "Тверская, 1",
                    status = VenueStatus.DRAFT,
                    createdAt = Instant.parse("2026-04-13T10:00:00Z"),
                    deletedAt = null,
                )
            coEvery { venueAccessRepository.listVenueMemberships(200L) } returns
                listOf(
                    VenueAccessRepository.VenueMembership(
                        venueId = 10L,
                        role = "OWNER",
                    ),
                )
            coEvery { platformVenueRepository.getVenueDetail(10L) } returns
                PlatformVenueDetail(
                    id = 10L,
                    name = "Новая кальянная",
                    city = "Москва",
                    address = "Тверская, 1",
                    status = VenueStatus.DRAFT,
                    createdAt = Instant.parse("2026-04-13T10:00:00Z"),
                    deletedAt = null,
                )

            router.process(
                TelegramUpdate(
                    updateId = 10_006,
                    message =
                        Message(
                            messageId = 20_006,
                            chat = Chat(id = 100, type = "private"),
                            fromUser = User(id = 200L),
                            text = "Новая кальянная",
                        ),
                ),
            )

            coVerifyOrder {
                platformVenueRepository.updateVenueName(venueId = 10L, name = "Новая кальянная")
                outboxEnqueuer.enqueueSendMessage(100, "✅ Название обновлено.", null)
                outboxEnqueuer.enqueueSendMessage(
                    100,
                    match { it.contains("🏢 Моё заведение") && it.contains("Название: Новая кальянная") },
                    match { it is InlineKeyboardMarkup },
                )
            }
        }

    @Test
    fun `owner venue address callback opens address input prompt`() =
        runBlocking {
            coEvery { venueAccessRepository.hasVenueAdminOrOwner(200L, 10L) } returns true

            router.process(
                TelegramUpdate(
                    updateId = 10_007,
                    callbackQuery =
                        CallbackQuery(
                            id = "cb-owner-venue-address",
                            from = User(id = 200L),
                            message = Message(messageId = 30_007, chat = Chat(id = 100, type = "private")),
                            data = "owner_venue_field:10:address",
                        ),
                ),
            )

            coVerify {
                outboxEnqueuer.enqueueSendMessage(
                    100,
                    "Введите адрес заведения.\nОтправьте «—», чтобы отменить.",
                    null,
                )
            }
        }

    @Test
    fun `owner venue address input updates venue and returns updated card`() =
        runBlocking {
            coEvery { dialogStateRepository.get(100) } returns
                DialogState(
                    state = DialogStateType.OWNER_VENUE_PROFILE_WAIT_ADDRESS,
                    payload =
                        mapOf(
                            "venue_id" to "10",
                            "owner_user_id" to "200",
                        ),
                )
            coEvery { venueAccessRepository.hasVenueAdminOrOwner(200L, 10L) } returns true
            coEvery {
                platformVenueRepository.updateVenueAddress(venueId = 10L, address = "Новый Арбат, 24")
            } returns
                PlatformVenueDetail(
                    id = 10L,
                    name = "Тестовая кальянная",
                    city = "Москва",
                    address = "Новый Арбат, 24",
                    status = VenueStatus.DRAFT,
                    createdAt = Instant.parse("2026-04-13T10:00:00Z"),
                    deletedAt = null,
                )
            coEvery { venueAccessRepository.listVenueMemberships(200L) } returns
                listOf(
                    VenueAccessRepository.VenueMembership(
                        venueId = 10L,
                        role = "OWNER",
                    ),
                )
            coEvery { platformVenueRepository.getVenueDetail(10L) } returns
                PlatformVenueDetail(
                    id = 10L,
                    name = "Тестовая кальянная",
                    city = "Москва",
                    address = "Новый Арбат, 24",
                    status = VenueStatus.DRAFT,
                    createdAt = Instant.parse("2026-04-13T10:00:00Z"),
                    deletedAt = null,
                )

            router.process(
                TelegramUpdate(
                    updateId = 10_008,
                    message =
                        Message(
                            messageId = 20_008,
                            chat = Chat(id = 100, type = "private"),
                            fromUser = User(id = 200L),
                            text = "Новый Арбат, 24",
                        ),
                ),
            )

            coVerifyOrder {
                platformVenueRepository.updateVenueAddress(venueId = 10L, address = "Новый Арбат, 24")
                outboxEnqueuer.enqueueSendMessage(100, "✅ Адрес обновлён.", null)
                outboxEnqueuer.enqueueSendMessage(
                    100,
                    match { it.contains("🏢 Моё заведение") && it.contains("Адрес: Новый Арбат, 24") },
                    match { it is InlineKeyboardMarkup },
                )
            }
        }

    @Test
    fun `owner venue hours callback opens weekly schedule screen`() =
        runBlocking {
            coEvery { venueAccessRepository.hasVenueAdminOrOwner(200L, 10L) } returns true

            router.process(
                TelegramUpdate(
                    updateId = 10_009,
                    callbackQuery =
                        CallbackQuery(
                            id = "cb-owner-venue-hours",
                            from = User(id = 200L),
                            message = Message(messageId = 30_009, chat = Chat(id = 100, type = "private")),
                            data = "owner_venue_field:10:hours",
                        ),
                ),
            )

            coVerify {
                outboxEnqueuer.enqueueSendMessage(
                    100,
                    match { it.contains("🕒 Часы работы") && it.contains("Пн:") && it.contains("Вс:") },
                    match {
                        it is InlineKeyboardMarkup &&
                            it.inlineKeyboard.flatten().any { button ->
                                button.text.contains("Пн") && button.callbackData == "owner_venue_hours_day:10:1"
                            } &&
                            it.inlineKeyboard.flatten().any { button ->
                                button.text == "📅 Исключения" && button.callbackData == "owner_venue_hours_overrides:10"
                            }
                    },
                )
            }
        }

    @Test
    fun `owner venue description callback opens sections constructor screen`() =
        runBlocking {
            coEvery { venueAccessRepository.hasVenueAdminOrOwner(200L, 10L) } returns true
            coEvery { venueInfoSectionsRepository.ensureDefaultSections(10L) } returns true
            coEvery { venueInfoSectionsRepository.listSections(10L) } returns
                listOf(
                    VenueInfoSection(
                        id = 101L,
                        venueId = 10L,
                        title = "О заведении",
                        sectionType = "about",
                        sortOrder = 10,
                        isVisible = true,
                        textContent = "Текст",
                    ),
                    VenueInfoSection(
                        id = 102L,
                        venueId = 10L,
                        title = "FAQ",
                        sectionType = "faq",
                        sortOrder = 40,
                        isVisible = true,
                        textContent = null,
                    ),
                )

            router.process(
                TelegramUpdate(
                    updateId = 10_010_1,
                    callbackQuery =
                        CallbackQuery(
                            id = "cb-owner-venue-description",
                            from = User(id = 200L),
                            message = Message(messageId = 30_010_1, chat = Chat(id = 100, type = "private")),
                            data = "owner_venue_field:10:description",
                        ),
                ),
            )

            coVerify {
                venueInfoSectionsRepository.ensureDefaultSections(10L)
                outboxEnqueuer.enqueueSendMessage(
                    100,
                    match { it.contains("📝 Описание заведения") && it.contains("Выберите раздел для настройки.") },
                    match {
                        it is InlineKeyboardMarkup &&
                            it.inlineKeyboard.flatten().any { button ->
                                button.text.contains("О заведении") &&
                                    button.callbackData == "owner_venue_description_section:10:101"
                            } &&
                            it.inlineKeyboard.flatten().any { button ->
                                button.text == "➕ Добавить свой раздел" &&
                                    button.callbackData == "owner_venue_description_add:10"
                            }
                    },
                )
            }
        }

    @Test
    fun `owner venue description can create custom section`() =
        runBlocking {
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

            coEvery { venueAccessRepository.hasVenueAdminOrOwner(200L, 10L) } returns true
            coEvery {
                venueInfoSectionsRepository.createCustomSection(
                    venueId = 10L,
                    title = "Летняя веранда",
                )
            } returns
                VenueInfoSection(
                    id = 103L,
                    venueId = 10L,
                    title = "Летняя веранда",
                    sectionType = "custom",
                    sortOrder = 50,
                    isVisible = true,
                    textContent = null,
                )
            coEvery { venueInfoSectionsRepository.ensureDefaultSections(10L) } returns true
            coEvery { venueInfoSectionsRepository.listSections(10L) } returns
                listOf(
                    VenueInfoSection(
                        id = 103L,
                        venueId = 10L,
                        title = "Летняя веранда",
                        sectionType = "custom",
                        sortOrder = 50,
                        isVisible = true,
                        textContent = null,
                    ),
                )

            router.process(
                TelegramUpdate(
                    updateId = 10_010_2,
                    callbackQuery =
                        CallbackQuery(
                            id = "cb-owner-venue-description-add",
                            from = User(id = 200L),
                            message = Message(messageId = 30_010_2, chat = Chat(id = 100, type = "private")),
                            data = "owner_venue_description_add:10",
                        ),
                ),
            )
            router.process(
                TelegramUpdate(
                    updateId = 10_010_3,
                    message =
                        Message(
                            messageId = 20_010_3,
                            chat = Chat(id = 100, type = "private"),
                            fromUser = User(id = 200L),
                            text = "Летняя веранда",
                        ),
                ),
            )

            coVerifyOrder {
                outboxEnqueuer.enqueueSendMessage(
                    100,
                    "Введите название нового раздела.\nОтправьте «—», чтобы отменить.",
                    null,
                )
                venueInfoSectionsRepository.createCustomSection(venueId = 10L, title = "Летняя веранда")
                outboxEnqueuer.enqueueSendMessage(100, "✅ Раздел «Летняя веранда» добавлен.", null)
                outboxEnqueuer.enqueueSendMessage(
                    100,
                    match { it.contains("📝 Описание заведения") },
                    match { it is InlineKeyboardMarkup },
                )
            }
        }

    @Test
    fun `owner venue description media callback accepts photo and saves attachment`() =
        runBlocking {
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

            coEvery { venueAccessRepository.hasVenueAdminOrOwner(200L, 10L) } returns true
            coEvery {
                venueInfoSectionsRepository.findSectionById(10L, 101L)
            } returns
                VenueInfoSection(
                    id = 101L,
                    venueId = 10L,
                    title = "О заведении",
                    sectionType = "about",
                    sortOrder = 10,
                    isVisible = true,
                    textContent = "Текст",
                )
            coEvery {
                venueInfoSectionMediaRepository.addMediaAttachment(
                    sectionId = 101L,
                    mediaType = "image",
                    telegramFileId = "photo-file-id",
                )
            } returns true
            coEvery { venueInfoSectionMediaRepository.countBySectionId(101L) } returns 1

            router.process(
                TelegramUpdate(
                    updateId = 10_010_4,
                    callbackQuery =
                        CallbackQuery(
                            id = "cb-owner-venue-description-media",
                            from = User(id = 200L),
                            message = Message(messageId = 30_010_4, chat = Chat(id = 100, type = "private")),
                            data = "owner_venue_description_media:10:101",
                        ),
                ),
            )
            router.process(
                TelegramUpdate(
                    updateId = 10_010_5,
                    message =
                        Message(
                            messageId = 20_010_5,
                            chat = Chat(id = 100, type = "private"),
                            fromUser = User(id = 200L),
                            photo =
                                listOf(
                                    PhotoSize(
                                        fileId = "photo-file-id",
                                        width = 1080,
                                        height = 1350,
                                        fileSize = 150_000,
                                    ),
                                ),
                        ),
                ),
            )

            coVerifyOrder {
                outboxEnqueuer.enqueueSendMessage(
                    100,
                    "Отправьте изображение или PDF для раздела «О заведении».\nОтправьте «—», чтобы отменить.",
                    null,
                )
                venueInfoSectionMediaRepository.addMediaAttachment(
                    sectionId = 101L,
                    mediaType = "image",
                    telegramFileId = "photo-file-id",
                )
                outboxEnqueuer.enqueueSendMessage(100, "✅ Медиа добавлено.", null)
                outboxEnqueuer.enqueueSendMessage(
                    100,
                    match { it.contains("📝 Описание заведения") && it.contains("Выберите раздел для настройки.") },
                    match { it is InlineKeyboardMarkup },
                )
            }
        }

    @Test
    fun `owner venue description section screen shows media list and delete actions`() =
        runBlocking {
            coEvery { venueAccessRepository.hasVenueAdminOrOwner(200L, 10L) } returns true
            coEvery { venueInfoSectionsRepository.findSectionById(10L, 101L) } returns
                VenueInfoSection(
                    id = 101L,
                    venueId = 10L,
                    title = "О заведении",
                    sectionType = "about",
                    sortOrder = 10,
                    isVisible = true,
                    textContent = "Текст раздела",
                )
            coEvery { venueInfoSectionMediaRepository.listBySectionId(101L) } returns
                listOf(
                    VenueInfoSectionMediaAttachment(
                        id = 7001L,
                        sectionId = 101L,
                        mediaType = "image",
                        telegramFileId = "img-1",
                        sortOrder = 0,
                    ),
                    VenueInfoSectionMediaAttachment(
                        id = 7002L,
                        sectionId = 101L,
                        mediaType = "pdf",
                        telegramFileId = "pdf-1",
                        sortOrder = 1,
                    ),
                )

            router.process(
                TelegramUpdate(
                    updateId = 10_010_6,
                    callbackQuery =
                        CallbackQuery(
                            id = "cb-owner-venue-description-section",
                            from = User(id = 200L),
                            message = Message(messageId = 30_010_6, chat = Chat(id = 100, type = "private")),
                            data = "owner_venue_description_section:10:101",
                        ),
                ),
            )

            coVerify {
                outboxEnqueuer.enqueueSendMessage(
                    100,
                    match { text ->
                        text.contains("📝 Раздел: О заведении") &&
                            text.contains("Текст:\nТекст раздела") &&
                            text.contains("• Изображение 1") &&
                            text.contains("• PDF 1")
                    },
                    match {
                            it is InlineKeyboardMarkup &&
                            it.inlineKeyboard.flatten().any { button ->
                                button.text == "🗑 Удалить изображение 1" &&
                                    button.callbackData == "owner_venue_description_media_delete:10:101:7001"
                            } &&
                            it.inlineKeyboard.flatten().any { button ->
                                button.text == "🗑 Удалить PDF 1" &&
                                    button.callbackData == "owner_venue_description_media_delete:10:101:7002"
                            }
                    },
                )
            }
        }

    @Test
    fun `owner venue description media delete removes attachment and returns section screen`() =
        runBlocking {
            coEvery { venueAccessRepository.hasVenueAdminOrOwner(200L, 10L) } returns true
            coEvery { venueInfoSectionsRepository.findSectionById(10L, 101L) } returns
                VenueInfoSection(
                    id = 101L,
                    venueId = 10L,
                    title = "О заведении",
                    sectionType = "about",
                    sortOrder = 10,
                    isVisible = true,
                    textContent = "Текст раздела",
                )
            coEvery { venueInfoSectionMediaRepository.deleteAttachment(101L, 7001L) } returns true
            coEvery { venueInfoSectionMediaRepository.listBySectionId(101L) } returns emptyList()

            router.process(
                TelegramUpdate(
                    updateId = 10_010_7,
                    callbackQuery =
                        CallbackQuery(
                            id = "cb-owner-venue-description-media-delete",
                            from = User(id = 200L),
                            message = Message(messageId = 30_010_7, chat = Chat(id = 100, type = "private")),
                            data = "owner_venue_description_media_delete:10:101:7001",
                        ),
                ),
            )

            coVerifyOrder {
                venueInfoSectionMediaRepository.deleteAttachment(101L, 7001L)
                outboxEnqueuer.enqueueSendMessage(100, "✅ Медиа удалено.", null)
                outboxEnqueuer.enqueueSendMessage(
                    100,
                    match { text ->
                        text.contains("📝 Раздел: О заведении") &&
                            text.contains("Медиа:\n—")
                    },
                    match { it is InlineKeyboardMarkup },
                )
            }
        }

    @Test
    fun `owner venue hours close input saves weekday hours and returns weekday screen`() =
        runBlocking {
            coEvery { dialogStateRepository.get(100) } returns
                DialogState(
                    state = DialogStateType.OWNER_VENUE_PROFILE_WAIT_HOURS_CLOSE,
                    payload =
                        mapOf(
                            "venue_id" to "10",
                            "owner_user_id" to "200",
                            "mode" to "weekday",
                            "weekday" to "1",
                            "opens_at" to "12:00",
                        ),
                )
            coEvery { venueAccessRepository.hasVenueAdminOrOwner(200L, 10L) } returns true
            coEvery {
                venueBookingHoursRepository.upsertWeekdayHours(
                    venueId = 10L,
                    weekday = 1,
                    opensAt = LocalTime.of(12, 0),
                    closesAt = LocalTime.of(23, 0),
                )
            } returns true
            coEvery { venueBookingHoursRepository.listWeeklyHours(10L) } returns
                listOf(
                    VenueBookingHours(
                        venueId = 10L,
                        weekday = 1,
                        opensAt = LocalTime.of(12, 0),
                        closesAt = LocalTime.of(23, 0),
                    ),
                )

            router.process(
                TelegramUpdate(
                    updateId = 10_010,
                    message =
                        Message(
                            messageId = 20_010,
                            chat = Chat(id = 100, type = "private"),
                            fromUser = User(id = 200L),
                            text = "23:00",
                        ),
                ),
            )

            coVerifyOrder {
                venueBookingHoursRepository.upsertWeekdayHours(
                    venueId = 10L,
                    weekday = 1,
                    opensAt = LocalTime.of(12, 0),
                    closesAt = LocalTime.of(23, 0),
                )
                outboxEnqueuer.enqueueSendMessage(100, "✅ Пн: часы обновлены.", null)
                outboxEnqueuer.enqueueSendMessage(
                    100,
                    match { it.contains("🕒 Часы работы · Пн") && it.contains("Текущий статус: 12:00–23:00") },
                    match { it is InlineKeyboardMarkup },
                )
            }
        }

    @Test
    fun `my command opens my orders and bookings section`() =
        runBlocking {
            router.process(
                TelegramUpdate(
                    updateId = 10_002,
                    message =
                        Message(
                            messageId = 20_002,
                            chat = Chat(id = 100, type = "private"),
                            fromUser = User(id = 200),
                            text = "/my",
                        ),
                ),
            )

            coVerify {
                outboxEnqueuer.enqueueSendMessage(
                    100,
                    "У вас пока нет активных заказов и броней.",
                    any(),
                )
            }
        }

    @Test
    fun `my command renders active booking with edit and cancel actions`() =
        runBlocking {
            coEvery {
                guestBookingRepository.listActiveByUser(userId = 200, limit = 5)
            } returns
                listOf(
                    UserBookingSummaryRecord(
                        id = 77L,
                        venueId = 10L,
                        venueName = "Тестовая кальянная",
                        scheduledAt = Instant.parse("2026-04-03T18:00:00Z"),
                        partySize = 3,
                        status = BookingStatus.PENDING,
                    ),
                )
            coEvery {
                ordersRepository.listActiveOrderSummariesForUser(userId = 200, limit = 5)
            } returns emptyList()

            router.process(
                TelegramUpdate(
                    updateId = 10_003,
                    message =
                        Message(
                            messageId = 20_003,
                            chat = Chat(id = 100, type = "private"),
                            fromUser = User(id = 200),
                            text = "/my",
                        ),
                ),
            )

            coVerify {
                outboxEnqueuer.enqueueSendMessage(
                    100,
                    "📄 Мои заказы и брони",
                    any(),
                )
            }
            coVerify {
                outboxEnqueuer.enqueueSendMessage(
                    100,
                    match { text ->
                        text.contains("Бронь #77") &&
                            text.contains("Кальянная: Тестовая кальянная") &&
                            text.contains("Гостей: 3")
                    },
                    match {
                        it is InlineKeyboardMarkup &&
                            it.inlineKeyboard.flatten().any { button ->
                                button.text == "✏️ Изменить" && button.callbackData == "bot_my_booking_edit:77:10"
                            } &&
                            it.inlineKeyboard.flatten().any { button ->
                                button.text == "❌ Отменить" && button.callbackData == "bot_my_booking_cancel:77:10"
                            }
                    },
                )
            }
        }

    @Test
    fun `my command renders informative active order block`() =
        runBlocking {
            coEvery {
                guestBookingRepository.listActiveByUser(userId = 200, limit = 5)
            } returns emptyList()
            coEvery {
                ordersRepository.listActiveOrderSummariesForUser(userId = 200, limit = 5)
            } returns
                listOf(
                    UserActiveOrderSummary(
                        orderId = 4L,
                        venueId = 10L,
                        venueName = "Тестовая кальянная",
                        status = "ACTIVE",
                        tabType = "SHARED",
                        items =
                            listOf(
                                UserActiveOrderItemSummary(itemId = 101L, itemName = "Премиум кальян", qty = 1),
                                UserActiveOrderItemSummary(itemId = 102L, itemName = "Облепиховый чай", qty = 1),
                            ),
                    ),
                )

            router.process(
                TelegramUpdate(
                    updateId = 10_005,
                    message =
                        Message(
                            messageId = 20_005,
                            chat = Chat(id = 100, type = "private"),
                            fromUser = User(id = 200),
                            text = "/my",
                        ),
                ),
            )

            coVerify(exactly = 0) {
                outboxEnqueuer.enqueueSendMessage(
                    100,
                    "📄 Мои заказы",
                    any(),
                )
            }
            coVerify {
                outboxEnqueuer.enqueueSendMessage(
                    100,
                    match { text ->
                        text.contains("Заказ #4") &&
                            text.contains("Кальянная: Тестовая кальянная") &&
                            text.contains("Статус: Принят") &&
                            text.contains("Счёт: Общий") &&
                            text.contains("• Премиум кальян ×1") &&
                            text.contains("• Облепиховый чай ×1")
                    },
                    any(),
                )
            }
        }

    @Test
    fun `my command renders active orders before future bookings`() =
        runBlocking {
            coEvery {
                guestBookingRepository.listActiveByUser(userId = 200, limit = 5)
            } returns
                listOf(
                    UserBookingSummaryRecord(
                        id = 88L,
                        venueId = 10L,
                        venueName = "Тестовая кальянная",
                        scheduledAt = Instant.parse("2026-04-03T18:00:00Z"),
                        partySize = 2,
                        status = BookingStatus.CONFIRMED,
                    ),
                )
            coEvery {
                ordersRepository.listActiveOrderSummariesForUser(userId = 200, limit = 5)
            } returns
                listOf(
                    UserActiveOrderSummary(
                        orderId = 9L,
                        venueId = 10L,
                        venueName = "Тестовая кальянная",
                        status = "ACTIVE",
                        tabType = "PERSONAL",
                        items = listOf(UserActiveOrderItemSummary(itemId = 201L, itemName = "Кальян классический", qty = 1)),
                    ),
                )

            router.process(
                TelegramUpdate(
                    updateId = 10_006,
                    message =
                        Message(
                            messageId = 20_006,
                            chat = Chat(id = 100, type = "private"),
                            fromUser = User(id = 200),
                            text = "/my",
                        ),
                ),
            )

            coVerifyOrder {
                outboxEnqueuer.enqueueSendMessage(100, "📄 Мои заказы и брони", any())
                outboxEnqueuer.enqueueSendMessage(
                    100,
                    match { text -> text.contains("Заказ #9") },
                    any(),
                )
                outboxEnqueuer.enqueueSendMessage(
                    100,
                    match { text -> text.contains("Бронь #88") },
                    any(),
                )
            }
        }

    @Test
    fun `booking edit callback opens booking date picker flow`() =
        runBlocking {
            coEvery {
                guestBookingRepository.findActiveByGuest(
                    bookingId = 77L,
                    venueId = 10L,
                    userId = 200L,
                )
            } returns
                BookingRecord(
                    id = 77L,
                    venueId = 10L,
                    userId = 200L,
                    scheduledAt = Instant.parse("2026-04-03T18:00:00Z"),
                    partySize = 3,
                    comment = "У окна",
                    status = BookingStatus.PENDING,
                )
            coEvery { venueRepository.findCatalogVenueByIdForGuest(10L) } returns
                CatalogVenueShort(
                    id = 10L,
                    name = "Тестовая кальянная",
                    city = "Москва",
                    address = "Тестовая улица, 1",
                )

            router.process(
                TelegramUpdate(
                    updateId = 10_004,
                    callbackQuery =
                        CallbackQuery(
                            id = "cb-booking-edit",
                            from = User(id = 200),
                            message =
                                Message(
                                    messageId = 20_004,
                                    chat = Chat(id = 100, type = "private"),
                                    fromUser = User(id = 200),
                                ),
                            data = "bot_my_booking_edit:77:10",
                        ),
                ),
            )

            coVerify {
                outboxEnqueuer.enqueueSendMessage(
                    100,
                    match { text ->
                        text.contains("Тестовая кальянная") &&
                            text.contains("Выберите новую дату брони #77.")
                    },
                    match { it is InlineKeyboardMarkup },
                )
            }
        }

    @Test
    fun `booking cancel callback cancels booking and confirms to user`() =
        runBlocking {
            coEvery {
                guestBookingRepository.cancelByGuest(
                    bookingId = 77L,
                    venueId = 10L,
                    userId = 200L,
                )
            } returns
                com.hookah.platform.backend.miniapp.guest.db.BookingRecord(
                    id = 77L,
                    venueId = 10L,
                    userId = 200L,
                    scheduledAt = Instant.parse("2026-04-03T18:00:00Z"),
                    partySize = 3,
                    comment = null,
                    status = BookingStatus.CANCELED,
                )

            router.process(
                TelegramUpdate(
                    updateId = 10_004,
                    callbackQuery =
                        CallbackQuery(
                            id = "cb-booking-cancel",
                            from = User(id = 200),
                            message =
                                Message(
                                    messageId = 20_004,
                                    chat = Chat(id = 100, type = "private"),
                                    fromUser = User(id = 200),
                                ),
                            data = "bot_my_booking_cancel:77:10",
                        ),
                ),
            )

            coVerify {
                guestBookingRepository.cancelByGuest(
                    bookingId = 77L,
                    venueId = 10L,
                    userId = 200L,
                )
            }
            coVerify {
                outboxEnqueuer.enqueueSendMessage(
                    100,
                    "✅ Бронь #77 отменена.",
                    any(),
                )
            }
        }

    @Test
    fun `help command shows command list`() =
        runBlocking {
            router.process(
                TelegramUpdate(
                    updateId = 10_003,
                    message =
                        Message(
                            messageId = 20_003,
                            chat = Chat(id = 100, type = "private"),
                            fromUser = User(id = 200),
                            text = "/help",
                        ),
                ),
            )

            coVerify {
                outboxEnqueuer.enqueueSendMessage(
                    100,
                    match { text ->
                        text.contains("/start — начать заново") &&
                            text.contains("/menu — главное меню") &&
                            text.contains("/my — мои заказы и брони") &&
                            text.contains("/help — помощь")
                    },
                    any(),
                )
            }
        }

    @Test
    fun `start menu promotions button opens placeholder entry`() =
        runBlocking {
            router.process(
                TelegramUpdate(
                    updateId = 10_004,
                    message =
                        Message(
                            messageId = 20_004,
                            chat = Chat(id = 100, type = "private"),
                            fromUser = User(id = 200),
                            text = "🎁 Акции",
                        ),
                ),
            )

            coVerify {
                outboxEnqueuer.enqueueSendMessage(
                    100,
                    "Раздел «Акции» — следующий шаг.",
                    any(),
                )
            }
        }

    @Test
    fun `start menu add venue button opens connection request flow`() =
        runBlocking {
            router.process(
                TelegramUpdate(
                    updateId = 10_005,
                    message =
                        Message(
                            messageId = 20_005,
                            chat = Chat(id = 100, type = "private"),
                            fromUser = User(id = 200),
                            text = "🤝 Добавить свою кальянную",
                        ),
                ),
            )

            coVerify {
                outboxEnqueuer.enqueueSendMessage(
                    100,
                    "Заявка на подключение кальянной.\nВведите название кальянной.",
                    any(),
                )
            }
        }

    @Test
    fun `start menu mini app button reports unavailable when url missing`() =
        runBlocking {
            router.process(
                TelegramUpdate(
                    updateId = 10_006,
                    message =
                        Message(
                            messageId = 20_006,
                            chat = Chat(id = 100, type = "private"),
                            fromUser = User(id = 200),
                            text = "📱 Открыть Mini App",
                        ),
                ),
            )

            coVerify {
                outboxEnqueuer.enqueueSendMessage(
                    100,
                    "Mini App временно недоступен. Попробуйте позже.",
                    any(),
                )
            }
        }

    @Test
    fun `catalog venue menu callback shows dynamic sections`() =
        runBlocking {
            coEvery { venueRepository.findCatalogVenueByIdForGuest(10L) } returns
                CatalogVenueShort(
                    id = 10L,
                    name = "Тестовая кальянная",
                    city = "Москва",
                    address = "Тверская, 1",
                )
            coEvery { guestMenuRepository.getMenu(10L) } returns
                MenuModel(
                    venueId = 10L,
                    categories =
                        listOf(
                            MenuCategoryModel(
                                id = 500L,
                                name = "Кальяны",
                                sortOrder = 0,
                                items = emptyList(),
                            ),
                            MenuCategoryModel(
                                id = 501L,
                                name = "Напитки",
                                sortOrder = 1,
                                items = emptyList(),
                            ),
                        ),
                )

            router.process(
                TelegramUpdate(
                    updateId = 10_007,
                    callbackQuery =
                        CallbackQuery(
                            id = "cb-venue-menu",
                            from = User(id = 200),
                            message =
                                Message(
                                    messageId = 20_007,
                                    chat = Chat(id = 100, type = "private"),
                                    fromUser = User(id = 200),
                                ),
                            data = "bot_catalog_venue_menu:10",
                        ),
                ),
            )

            coVerify {
                outboxEnqueuer.enqueueSendMessage(
                    100,
                    "🍽 Меню «Тестовая кальянная»\nВыберите раздел.",
                    match {
                        it is InlineKeyboardMarkup &&
                            it.inlineKeyboard.flatten().any { button ->
                                button.text == "Кальяны" &&
                                    button.callbackData == "bot_catalog_venue_menu_section:10:500"
                            } &&
                            it.inlineKeyboard.flatten().any { button ->
                                button.text == "Напитки" &&
                                    button.callbackData == "bot_catalog_venue_menu_section:10:501"
                            }
                    },
                )
            }
        }

    @Test
    fun `catalog venue menu section callback sends section images`() =
        runBlocking {
            coEvery { venueRepository.findCatalogVenueByIdForGuest(10L) } returns
                CatalogVenueShort(
                    id = 10L,
                    name = "Тестовая кальянная",
                    city = "Москва",
                    address = "Тверская, 1",
                )
            coEvery { guestMenuRepository.getMenu(10L) } returns
                MenuModel(
                    venueId = 10L,
                    categories =
                        listOf(
                            MenuCategoryModel(
                                id = 500L,
                                name = "Кальяны",
                                sortOrder = 0,
                                items = emptyList(),
                            ),
                        ),
                )
            coEvery {
                venueMenuSectionImagesRepository.listImageUrlsForCategory(venueId = 10L, categoryId = 500L)
            } returns
                listOf(
                    "https://example.com/menu-1.png",
                    "https://example.com/menu-2.png",
                )

            router.process(
                TelegramUpdate(
                    updateId = 10_008,
                    callbackQuery =
                        CallbackQuery(
                            id = "cb-venue-menu-section",
                            from = User(id = 200),
                            message =
                                Message(
                                    messageId = 20_008,
                                    chat = Chat(id = 100, type = "private"),
                                    fromUser = User(id = 200),
                                ),
                            data = "bot_catalog_venue_menu_section:10:500",
                        ),
                ),
            )

            coVerify {
                outboxEnqueuer.enqueueSendPhoto(
                    100,
                    "https://example.com/menu-1.png",
                    "🍽 Тестовая кальянная\nРаздел: Кальяны",
                    null,
                )
            }
            coVerify {
                outboxEnqueuer.enqueueSendPhoto(
                    100,
                    "https://example.com/menu-2.png",
                    null,
                    match {
                        it is InlineKeyboardMarkup &&
                            it.inlineKeyboard.flatten().any { button ->
                                button.text == "⬅️ К разделам меню" &&
                                    button.callbackData == "bot_catalog_venue_menu:10"
                            }
                    },
                )
            }
        }

    @Test
    fun `continue in bot sends entry text and restores table keyboard`() =
        runBlocking {
            val context =
                TableContext(
                    venueId = 10L,
                    venueName = "Venue",
                    tableId = 11L,
                    tableNumber = 5,
                    tableToken = "TOKEN",
                    staffChatId = null,
                )
            coEvery { chatContextRepository.get(100) } returns StoredChatContext(userId = 200, tableToken = "TOKEN")
            coEvery { tableTokenRepository.resolve("TOKEN") } returns context
            coEvery { subscriptionRepository.getSubscriptionStatus(10L) } returns SubscriptionStatus.ACTIVE

            val update =
                TelegramUpdate(
                    updateId = 10,
                    callbackQuery =
                        CallbackQuery(
                            id = "cb1",
                            from = User(id = 200),
                            message =
                                Message(
                                    messageId = 20,
                                    chat = Chat(id = 100, type = "private"),
                                    text = null,
                                ),
                            data = "continue_in_bot",
                        ),
                )

            router.process(update)

            coVerifyOrder {
                outboxEnqueuer.enqueueSendMessage(
                    100,
                    "Продолжаем в боте. Выберите действие.",
                    any(),
                )
                outboxEnqueuer.enqueueSendMessage(
                    100,
                    "\u2060",
                    match {
                        it is ReplyKeyboardMarkup &&
                            it.keyboard == TelegramKeyboards.tableContextBotFlow(context).keyboard
                    },
                )
            }
        }

    @Test
    fun `open menu button in bot flow triggers bot placeholder message`() =
        runBlocking {
            val context =
                TableContext(
                    venueId = 10L,
                    venueName = "Venue",
                    tableId = 11L,
                    tableNumber = 5,
                    tableToken = "TOKEN",
                    staffChatId = null,
                )
            coEvery { chatContextRepository.get(100) } returns StoredChatContext(userId = 200, tableToken = "TOKEN")
            coEvery { tableTokenRepository.resolve("TOKEN") } returns context
            coEvery { subscriptionRepository.getSubscriptionStatus(10L) } returns SubscriptionStatus.ACTIVE
            coEvery { guestMenuRepository.getMenu(10L) } returns
                MenuModel(
                    venueId = 10L,
                    categories =
                        listOf(
                            MenuCategoryModel(
                                id = 500L,
                                name = "Кальяны",
                                sortOrder = 0,
                                items = emptyList(),
                            ),
                        ),
                )

            val update =
                TelegramUpdate(
                    updateId = 11,
                    message =
                        Message(
                            messageId = 21,
                            chat = Chat(id = 100, type = "private"),
                            fromUser = User(id = 200),
                            text = "🍽️ Меню",
                        ),
                )

            router.process(update)

            coVerify {
                outboxEnqueuer.enqueueSendMessage(
                    100,
                    "Venue\nВыберите категорию.",
                    match {
                        it is InlineKeyboardMarkup &&
                            it.inlineKeyboard.flatten().any { button ->
                                button.text == "Кальяны" && button.callbackData == "bot_menu_category:500"
                            }
                    },
                )
            }
        }

    @Test
    fun `split bill entry from bot keyboard shows create and join actions in personal tab`() =
        runBlocking {
            val context =
                TableContext(
                    venueId = 10L,
                    venueName = "Venue",
                    tableId = 11L,
                    tableNumber = 5,
                    tableToken = "TOKEN",
                    staffChatId = null,
                )
            coEvery { chatContextRepository.get(100) } returns StoredChatContext(userId = 200, tableToken = "TOKEN")
            coEvery { tableTokenRepository.resolve("TOKEN") } returns context
            coEvery { subscriptionRepository.getSubscriptionStatus(10L) } returns SubscriptionStatus.ACTIVE

            val update =
                TelegramUpdate(
                    updateId = 11_001,
                    message =
                        Message(
                            messageId = 21_001,
                            chat = Chat(id = 100, type = "private"),
                            fromUser = User(id = 200),
                            text = "👥 Общий счёт",
                        ),
                )

            router.process(update)

            coVerify {
                outboxEnqueuer.enqueueSendMessage(
                    100,
                    "Общий счёт\nВыберите действие.",
                    match {
                        it is InlineKeyboardMarkup &&
                            it.inlineKeyboard.flatten().any { button ->
                                button.text == "👥 Создать общий счёт" &&
                                    button.callbackData == "bot_tabs_create_shared"
                            } &&
                            it.inlineKeyboard.flatten().any { button ->
                                button.text == "🔑 Присоединиться к общему счёту" &&
                                    button.callbackData == "bot_tabs_join_prompt"
                            }
                    },
                )
            }
        }

    @Test
    fun `split bill entry in shared owner tab shows shared status and invite code`() =
        runBlocking {
            val context =
                TableContext(
                    venueId = 10L,
                    venueName = "Venue",
                    tableId = 11L,
                    tableNumber = 5,
                    tableToken = "TOKEN",
                    staffChatId = null,
                )
            coEvery { chatContextRepository.get(100) } returns StoredChatContext(userId = 200, tableToken = "TOKEN")
            coEvery { tableTokenRepository.resolve("TOKEN") } returns context
            coEvery { subscriptionRepository.getSubscriptionStatus(10L) } returns SubscriptionStatus.ACTIVE
            coEvery {
                guestTabsRepository.listTabsForUser(
                    venueId = 10L,
                    tableSessionId = 55L,
                    userId = 200L,
                )
            } returns
                listOf(
                    GuestTabModel(
                        id = 1L,
                        venueId = 10L,
                        tableSessionId = 55L,
                        type = "PERSONAL",
                        ownerUserId = 200L,
                        status = "ACTIVE",
                    ),
                    GuestTabModel(
                        id = 9L,
                        venueId = 10L,
                        tableSessionId = 55L,
                        type = "SHARED",
                        ownerUserId = 200L,
                        status = "ACTIVE",
                    ),
                )

            val update =
                TelegramUpdate(
                    updateId = 11_002,
                    message =
                        Message(
                            messageId = 21_002,
                            chat = Chat(id = 100, type = "private"),
                            fromUser = User(id = 200),
                            text = "👥 Общий счёт",
                        ),
                )

            router.process(update)

            coVerify {
                outboxEnqueuer.enqueueSendMessage(
                    100,
                    match { text ->
                        text.startsWith("Текущий счёт: Общий.\nКод приглашения: ") &&
                            text.substringAfter("Текущий счёт: Общий.\nКод приглашения: ").length == 4
                    },
                    any(),
                )
            }
        }

    @Test
    fun `category callback shows real items for selected category`() =
        runBlocking {
            val context =
                TableContext(
                    venueId = 10L,
                    venueName = "Venue",
                    tableId = 11L,
                    tableNumber = 5,
                    tableToken = "TOKEN",
                    staffChatId = null,
                )
            coEvery { chatContextRepository.get(100) } returns StoredChatContext(userId = 200, tableToken = "TOKEN")
            coEvery { tableTokenRepository.resolve("TOKEN") } returns context
            coEvery { subscriptionRepository.getSubscriptionStatus(10L) } returns SubscriptionStatus.ACTIVE
            coEvery { guestMenuRepository.getMenu(10L) } returns
                MenuModel(
                    venueId = 10L,
                    categories =
                        listOf(
                            MenuCategoryModel(
                                id = 500L,
                                name = "Основное меню",
                                sortOrder = 0,
                                items =
                                    listOf(
                                        MenuItemModel(
                                            id = 1000L,
                                            name = "Кальян классический",
                                            priceMinor = 25000L,
                                            currency = "RUB",
                                            isAvailable = true,
                                            sortOrder = 0,
                                        ),
                                        MenuItemModel(
                                            id = 1001L,
                                            name = "Лимонад",
                                            priceMinor = 4500L,
                                            currency = "RUB",
                                            isAvailable = true,
                                            sortOrder = 1,
                                        ),
                                    ),
                            ),
                        ),
                )

            val update =
                TelegramUpdate(
                    updateId = 12,
                    callbackQuery =
                        CallbackQuery(
                            id = "cb-category",
                            from = User(id = 200),
                            message =
                                Message(
                                    messageId = 22,
                                    chat = Chat(id = 100, type = "private"),
                                    text = null,
                                ),
                            data = "bot_menu_category:500",
                        ),
                )

            router.process(update)

            coVerify {
                outboxEnqueuer.enqueueSendMessage(
                    100,
                    "Основное меню\nВыберите позицию.",
                    match {
                        it is InlineKeyboardMarkup &&
                            it.inlineKeyboard.flatten().any { button ->
                                button.text == "Кальян классический — 250.00 ₽" &&
                                    button.callbackData == "bot_menu_item:500:1000"
                            } &&
                            it.inlineKeyboard.flatten().any { button ->
                                button.text == "Лимонад — 45.00 ₽" &&
                                    button.callbackData == "bot_menu_item:500:1001"
                            }
                    },
                )
            }
        }

    @Test
    fun `invalid category callback data shows fallback instead of silent no-op`() =
        runBlocking {
            val update =
                TelegramUpdate(
                    updateId = 12_100,
                    callbackQuery =
                        CallbackQuery(
                            id = "cb-category-invalid",
                            from = User(id = 200),
                            message =
                                Message(
                                    messageId = 22_100,
                                    chat = Chat(id = 100, type = "private"),
                                    text = null,
                                ),
                            data = "bot_menu_category:abc",
                        ),
                )

            router.process(update)

            coVerify {
                outboxEnqueuer.enqueueSendMessage(
                    100,
                    "Не удалось открыть категорию. Попробуйте ещё раз.",
                    any(),
                )
            }
        }

    @Test
    fun `category callback paginates items when category has many positions`() =
        runBlocking {
            val context =
                TableContext(
                    venueId = 10L,
                    venueName = "Venue",
                    tableId = 11L,
                    tableNumber = 5,
                    tableToken = "TOKEN",
                    staffChatId = null,
                )
            coEvery { chatContextRepository.get(100) } returns StoredChatContext(userId = 200, tableToken = "TOKEN")
            coEvery { tableTokenRepository.resolve("TOKEN") } returns context
            coEvery { subscriptionRepository.getSubscriptionStatus(10L) } returns SubscriptionStatus.ACTIVE
            coEvery { guestMenuRepository.getMenu(10L) } returns
                MenuModel(
                    venueId = 10L,
                    categories =
                        listOf(
                            MenuCategoryModel(
                                id = 500L,
                                name = "Основное меню",
                                sortOrder = 0,
                                items =
                                    listOf(
                                        MenuItemModel(
                                            id = 1000L,
                                            name = "Позиция 1",
                                            priceMinor = 1000L,
                                            currency = "RUB",
                                            isAvailable = true,
                                            sortOrder = 0,
                                        ),
                                        MenuItemModel(
                                            id = 1001L,
                                            name = "Позиция 2",
                                            priceMinor = 2000L,
                                            currency = "RUB",
                                            isAvailable = true,
                                            sortOrder = 1,
                                        ),
                                        MenuItemModel(
                                            id = 1002L,
                                            name = "Позиция 3",
                                            priceMinor = 3000L,
                                            currency = "RUB",
                                            isAvailable = true,
                                            sortOrder = 2,
                                        ),
                                        MenuItemModel(
                                            id = 1003L,
                                            name = "Позиция 4",
                                            priceMinor = 4000L,
                                            currency = "RUB",
                                            isAvailable = true,
                                            sortOrder = 3,
                                        ),
                                        MenuItemModel(
                                            id = 1004L,
                                            name = "Позиция 5",
                                            priceMinor = 5000L,
                                            currency = "RUB",
                                            isAvailable = true,
                                            sortOrder = 4,
                                        ),
                                        MenuItemModel(
                                            id = 1005L,
                                            name = "Позиция 6",
                                            priceMinor = 6000L,
                                            currency = "RUB",
                                            isAvailable = true,
                                            sortOrder = 5,
                                        ),
                                    ),
                            ),
                        ),
                )

            val update =
                TelegramUpdate(
                    updateId = 14,
                    callbackQuery =
                        CallbackQuery(
                            id = "cb-category-paged",
                            from = User(id = 200),
                            message =
                                Message(
                                    messageId = 24,
                                    chat = Chat(id = 100, type = "private"),
                                    text = null,
                                ),
                            data = "bot_menu_category:500",
                        ),
                )

            router.process(update)

            coVerify {
                outboxEnqueuer.enqueueSendMessage(
                    100,
                    "Основное меню\nВыберите позицию.\nСтраница 1/2",
                    match {
                        it is InlineKeyboardMarkup &&
                            it.inlineKeyboard.flatten().any { button ->
                                button.text == "Позиция 1 — 10.00 ₽" &&
                                    button.callbackData == "bot_menu_item:500:1000"
                            } &&
                            it.inlineKeyboard.flatten().none { button ->
                                button.callbackData == "bot_menu_item:500:1005"
                            } &&
                            it.inlineKeyboard.flatten().any { button ->
                                button.text == "▶️" &&
                                    button.callbackData == "bot_menu_category_page:500:1"
                            }
                    },
                )
            }
        }

    @Test
    fun `category page callback opens requested page`() =
        runBlocking {
            val context =
                TableContext(
                    venueId = 10L,
                    venueName = "Venue",
                    tableId = 11L,
                    tableNumber = 5,
                    tableToken = "TOKEN",
                    staffChatId = null,
                )
            coEvery { chatContextRepository.get(100) } returns StoredChatContext(userId = 200, tableToken = "TOKEN")
            coEvery { tableTokenRepository.resolve("TOKEN") } returns context
            coEvery { subscriptionRepository.getSubscriptionStatus(10L) } returns SubscriptionStatus.ACTIVE
            coEvery { guestMenuRepository.getMenu(10L) } returns
                MenuModel(
                    venueId = 10L,
                    categories =
                        listOf(
                            MenuCategoryModel(
                                id = 500L,
                                name = "Основное меню",
                                sortOrder = 0,
                                items =
                                    listOf(
                                        MenuItemModel(1000L, "Позиция 1", 1000L, "RUB", true, 0),
                                        MenuItemModel(1001L, "Позиция 2", 2000L, "RUB", true, 1),
                                        MenuItemModel(1002L, "Позиция 3", 3000L, "RUB", true, 2),
                                        MenuItemModel(1003L, "Позиция 4", 4000L, "RUB", true, 3),
                                        MenuItemModel(1004L, "Позиция 5", 5000L, "RUB", true, 4),
                                        MenuItemModel(1005L, "Позиция 6", 6000L, "RUB", true, 5),
                                    ),
                            ),
                        ),
                )

            val update =
                TelegramUpdate(
                    updateId = 15,
                    callbackQuery =
                        CallbackQuery(
                            id = "cb-category-page-2",
                            from = User(id = 200),
                            message =
                                Message(
                                    messageId = 25,
                                    chat = Chat(id = 100, type = "private"),
                                    text = null,
                                ),
                            data = "bot_menu_category_page:500:1",
                        ),
                )

            router.process(update)

            coVerify {
                outboxEnqueuer.enqueueSendMessage(
                    100,
                    "Основное меню\nВыберите позицию.\nСтраница 2/2",
                    match {
                        it is InlineKeyboardMarkup &&
                            it.inlineKeyboard.flatten().any { button ->
                                button.text == "Позиция 6 — 60.00 ₽" &&
                                    button.callbackData == "bot_menu_item:500:1005"
                            } &&
                            it.inlineKeyboard.flatten().any { button ->
                                button.text == "◀️" &&
                                    button.callbackData == "bot_menu_category_page:500:0"
                            } &&
                            it.inlineKeyboard.flatten().none { button ->
                                button.callbackData == "bot_menu_category_page:500:2"
                            }
                    },
                )
            }
        }

    @Test
    fun `item callback shows selected item details`() =
        runBlocking {
            val context =
                TableContext(
                    venueId = 10L,
                    venueName = "Venue",
                    tableId = 11L,
                    tableNumber = 5,
                    tableToken = "TOKEN",
                    staffChatId = null,
                )
            coEvery { chatContextRepository.get(100) } returns StoredChatContext(userId = 200, tableToken = "TOKEN")
            coEvery { tableTokenRepository.resolve("TOKEN") } returns context
            coEvery { subscriptionRepository.getSubscriptionStatus(10L) } returns SubscriptionStatus.ACTIVE
            coEvery { ordersRepository.findActiveOrderSummary(11L) } returns null
            coEvery { guestMenuRepository.getMenu(10L) } returns
                MenuModel(
                    venueId = 10L,
                    categories =
                        listOf(
                            MenuCategoryModel(
                                id = 500L,
                                name = "Основное меню",
                                sortOrder = 0,
                                items =
                                    listOf(
                                        MenuItemModel(
                                            id = 1000L,
                                            name = "Кальян классический",
                                            priceMinor = 25000L,
                                            currency = "RUB",
                                            isAvailable = true,
                                            sortOrder = 0,
                                        ),
                                    ),
                            ),
                        ),
                )

            val update =
                TelegramUpdate(
                    updateId = 13,
                    callbackQuery =
                        CallbackQuery(
                            id = "cb-item",
                            from = User(id = 200),
                            message =
                                Message(
                                    messageId = 23,
                                    chat = Chat(id = 100, type = "private"),
                                    text = null,
                                ),
                            data = "bot_menu_item:500:1000",
                        ),
                )

            router.process(update)

            coVerify {
                outboxEnqueuer.enqueueSendMessage(
                    100,
                    "Кальян классический\nЦена: 250.00 ₽\n\nПозиция добавлена в корзину.",
                    match {
                        it is InlineKeyboardMarkup &&
                            it.inlineKeyboard.flatten().any { button ->
                                button.text == "⬅️ К позициям категории" &&
                                    button.callbackData == "bot_menu_back_category:500"
                            } &&
                            it.inlineKeyboard.flatten().any { button ->
                                button.text == "⬅️ К категориям" &&
                                    button.callbackData == "bot_menu_back_categories"
                            }
                    },
                )
            }
        }

    @Test
    fun `item callback with active order still adds item to draft cart`() =
        runBlocking {
            val context =
                TableContext(
                    venueId = 10L,
                    venueName = "Venue",
                    tableId = 11L,
                    tableNumber = 5,
                    tableToken = "TOKEN",
                    staffChatId = null,
                )
            coEvery { chatContextRepository.get(100) } returns StoredChatContext(userId = 200, tableToken = "TOKEN")
            coEvery { tableTokenRepository.resolve("TOKEN") } returns context
            coEvery { subscriptionRepository.getSubscriptionStatus(10L) } returns SubscriptionStatus.ACTIVE
            coEvery { ordersRepository.findActiveOrderSummaryForTab(11L, any()) } returns ActiveOrderSummary(900L, "ACTIVE")
            coEvery { guestMenuRepository.getMenu(10L) } returns
                MenuModel(
                    venueId = 10L,
                    categories =
                        listOf(
                            MenuCategoryModel(
                                id = 500L,
                                name = "Основное меню",
                                sortOrder = 0,
                                items =
                                    listOf(
                                        MenuItemModel(
                                            id = 1000L,
                                            name = "Кальян классический",
                                            priceMinor = 25000L,
                                            currency = "RUB",
                                            isAvailable = true,
                                            sortOrder = 0,
                                        ),
                                    ),
                            ),
                        ),
                )

            val update =
                TelegramUpdate(
                    updateId = 22,
                    callbackQuery =
                        CallbackQuery(
                            id = "cb-item-active",
                            from = User(id = 200),
                            message =
                                Message(
                                    messageId = 32,
                                    chat = Chat(id = 100, type = "private"),
                                    text = null,
                                ),
                            data = "bot_menu_item:500:1000",
                        ),
                )

            router.process(update)

            coVerify {
                outboxEnqueuer.enqueueSendMessage(
                    100,
                    "Кальян классический\nЦена: 250.00 ₽\n\nПозиция добавлена в корзину.",
                    match {
                        it is InlineKeyboardMarkup &&
                            it.inlineKeyboard.flatten().any { button ->
                                button.text == "⬅️ К позициям категории" &&
                                    button.callbackData == "bot_menu_back_category:500"
                            }
                    },
                )
            }
        }

    @Test
    fun `active order reorder entry opens categories menu`() =
        runBlocking {
            val context =
                TableContext(
                    venueId = 10L,
                    venueName = "Venue",
                    tableId = 11L,
                    tableNumber = 5,
                    tableToken = "TOKEN",
                    staffChatId = null,
                )
            coEvery { chatContextRepository.get(100) } returns StoredChatContext(userId = 200, tableToken = "TOKEN")
            coEvery { tableTokenRepository.resolve("TOKEN") } returns context
            coEvery { subscriptionRepository.getSubscriptionStatus(10L) } returns SubscriptionStatus.ACTIVE
            coEvery { guestMenuRepository.getMenu(10L) } returns
                MenuModel(
                    venueId = 10L,
                    categories =
                        listOf(
                            MenuCategoryModel(
                                id = 500L,
                                name = "Основное меню",
                                sortOrder = 0,
                                items = emptyList(),
                            ),
                        ),
                )

            router.process(
                TelegramUpdate(
                    updateId = 220,
                    callbackQuery =
                        CallbackQuery(
                            id = "cb-active-order-reorder-entry",
                            from = User(id = 200),
                            message = Message(messageId = 322, chat = Chat(id = 100, type = "private"), text = null),
                            data = "bot_menu_reorder_entry",
                        ),
                ),
            )

            coVerify(exactly = 0) {
                outboxEnqueuer.enqueueSendMessage(
                    100,
                    "Режим дозаказа: выберите позиции в меню, затем откройте корзину и нажмите «✅ Оформить заказ».",
                    any(),
                )
            }
            coVerify(exactly = 0) {
                outboxEnqueuer.enqueueSendMessage(
                    100,
                    "Venue\nВыберите категорию.",
                    any(),
                )
            }
            coVerify {
                outboxEnqueuer.enqueueSendMessage(
                    100,
                    "Выберите позицию в меню, затем откройте корзину и нажмите «Оформить заказ».",
                    match { it is InlineKeyboardMarkup },
                )
            }
        }

    @Test
    fun `item add action callback adds item to draft cart`() =
        runBlocking {
            val context =
                TableContext(
                    venueId = 10L,
                    venueName = "Venue",
                    tableId = 11L,
                    tableNumber = 5,
                    tableToken = "TOKEN",
                    staffChatId = null,
                )
            coEvery { chatContextRepository.get(100) } returns StoredChatContext(userId = 200, tableToken = "TOKEN")
            coEvery { tableTokenRepository.resolve("TOKEN") } returns context
            coEvery { subscriptionRepository.getSubscriptionStatus(10L) } returns SubscriptionStatus.ACTIVE
            coEvery { ordersRepository.findActiveOrderSummary(11L) } returns null
            coEvery { guestMenuRepository.getMenu(10L) } returns
                MenuModel(
                    venueId = 10L,
                    categories =
                        listOf(
                            MenuCategoryModel(
                                id = 500L,
                                name = "Основное меню",
                                sortOrder = 0,
                                items =
                                    listOf(
                                        MenuItemModel(
                                            id = 1000L,
                                            name = "Кальян классический",
                                            priceMinor = 25000L,
                                            currency = "RUB",
                                            isAvailable = true,
                                            sortOrder = 0,
                                        ),
                                    ),
                            ),
                        ),
                )

            val update =
                TelegramUpdate(
                    updateId = 18,
                    callbackQuery =
                        CallbackQuery(
                            id = "cb-item-add",
                            from = User(id = 200),
                            message =
                                Message(
                            messageId = 28,
                                    chat = Chat(id = 100, type = "private"),
                                    text = null,
                                ),
                            data = "bot_menu_item_add_to_cart:500:1000",
                        ),
                )

            router.process(update)

            coVerify {
                outboxEnqueuer.enqueueSendMessage(
                    100,
                    "Кальян классический\nЦена: 250.00 ₽\n\nПозиция добавлена в корзину.",
                    match { it is InlineKeyboardMarkup },
                )
            }
        }

    @Test
    fun `item reorder action submits new batch to active order`() =
        runBlocking {
            val now = Instant.parse("2026-03-30T10:00:00Z")
            val context =
                TableContext(
                    venueId = 10L,
                    venueName = "Venue",
                    tableId = 11L,
                    tableNumber = 5,
                    tableToken = "TOKEN",
                    staffChatId = null,
                )
            coEvery { chatContextRepository.get(100) } returns StoredChatContext(userId = 200, tableToken = "TOKEN")
            coEvery { tableTokenRepository.resolve("TOKEN") } returns context
            coEvery { subscriptionRepository.getSubscriptionStatus(10L) } returns SubscriptionStatus.ACTIVE
            coEvery { ordersRepository.findActiveOrderSummaryForTab(11L, any()) } returns ActiveOrderSummary(900L, "ACTIVE")
            coEvery { guestMenuRepository.getMenu(10L) } returns
                MenuModel(
                    venueId = 10L,
                    categories =
                        listOf(
                            MenuCategoryModel(
                                id = 500L,
                                name = "Основное меню",
                                sortOrder = 0,
                                items =
                                    listOf(
                                        MenuItemModel(
                                            id = 1000L,
                                            name = "Кальян классический",
                                            priceMinor = 25000L,
                                            currency = "RUB",
                                            isAvailable = true,
                                            sortOrder = 0,
                                        ),
                                    ),
                            ),
                        ),
                )
            coEvery {
                tableSessionRepository.resolveActiveSession(
                    venueId = any(),
                    tableId = any(),
                    ttl = any(),
                    now = any(),
                )
            } returns
                TableSessionRecord(
                    id = 55L,
                    venueId = 10L,
                    tableId = 11L,
                    startedAt = now.minusSeconds(60),
                    lastActivityAt = now,
                    expiresAt = now.plusSeconds(7200),
                    endedAt = null,
                    status = TableSessionStatus.ACTIVE,
                )
            coEvery {
                guestTabsRepository.ensurePersonalTab(
                    venueId = 10L,
                    tableSessionId = 55L,
                    userId = 200L,
                )
            } returns
                GuestTabModel(
                    id = 77L,
                    venueId = 10L,
                    tableSessionId = 55L,
                    type = "PERSONAL",
                    ownerUserId = 200L,
                    status = "ACTIVE",
                )
            coEvery {
                ordersRepository.createGuestOrderBatch(
                    tableId = 11L,
                    venueId = 10L,
                    tableSessionId = 55L,
                    userId = 200L,
                    idempotencyKey = "bot-reorder:cb-item-reorder",
                    tabId = 77L,
                    comment = null,
                    items = match { it.size == 1 && it[0].itemId == 1000L && it[0].qty == 1 },
                )
            } returns
                CreatedOrderBatch(
                    orderId = 900L,
                    batchId = 901L,
                    idempotencyReplay = false,
                )

            val update =
                TelegramUpdate(
                    updateId = 21,
                    callbackQuery =
                        CallbackQuery(
                            id = "cb-item-reorder",
                            from = User(id = 200),
                            message =
                                Message(
                                    messageId = 31,
                                    chat = Chat(id = 100, type = "private"),
                                    text = null,
                                ),
                            data = "bot_menu_item_reorder:500:1000",
                        ),
                )

            router.process(update)

            coVerify {
                ordersRepository.createGuestOrderBatch(
                    tableId = 11L,
                    venueId = 10L,
                    tableSessionId = 55L,
                    userId = 200L,
                    idempotencyKey = "bot-reorder:cb-item-reorder",
                    tabId = 77L,
                    comment = null,
                    items = match { it.size == 1 && it[0].itemId == 1000L && it[0].qty == 1 },
                )
            }
            coVerify {
                outboxEnqueuer.enqueueSendMessage(
                    100,
                    "Кальян классический\nЦена: 250.00 ₽\n\n✅ Дозаказ отправлен в активный заказ #900.",
                    match { it is InlineKeyboardMarkup },
                )
            }
        }

    @Test
    fun `item cart action shows empty draft cart`() =
        runBlocking {
            val context =
                TableContext(
                    venueId = 10L,
                    venueName = "Venue",
                    tableId = 11L,
                    tableNumber = 5,
                    tableToken = "TOKEN",
                    staffChatId = null,
                )
            coEvery { chatContextRepository.get(100) } returns StoredChatContext(userId = 200, tableToken = "TOKEN")
            coEvery { tableTokenRepository.resolve("TOKEN") } returns context
            coEvery { subscriptionRepository.getSubscriptionStatus(10L) } returns SubscriptionStatus.ACTIVE

            val update =
                TelegramUpdate(
                    updateId = 19,
                    callbackQuery =
                        CallbackQuery(
                            id = "cb-item-cart",
                            from = User(id = 200),
                            message =
                                Message(
                                    messageId = 29,
                                    chat = Chat(id = 100, type = "private"),
                                    text = null,
                                ),
                            data = "bot_menu_item_cart",
                        ),
                )

            router.process(update)

            coVerify {
                outboxEnqueuer.enqueueSendMessage(
                    100,
                    "Корзина пуста.",
                    match { it is InlineKeyboardMarkup },
                )
            }
        }

    @Test
    fun `basket command from bot keyboard opens cart`() =
        runBlocking {
            val context =
                TableContext(
                    venueId = 10L,
                    venueName = "Venue",
                    tableId = 11L,
                    tableNumber = 5,
                    tableToken = "TOKEN",
                    staffChatId = null,
                )
            coEvery { chatContextRepository.get(100) } returns StoredChatContext(userId = 200, tableToken = "TOKEN")
            coEvery { tableTokenRepository.resolve("TOKEN") } returns context
            coEvery { subscriptionRepository.getSubscriptionStatus(10L) } returns SubscriptionStatus.ACTIVE

            router.process(
                TelegramUpdate(
                    updateId = 1901,
                    message =
                        Message(
                            messageId = 2901,
                            chat = Chat(id = 100, type = "private"),
                            fromUser = User(id = 200),
                            text = "🧺 Корзина",
                        ),
                ),
            )

            coVerify {
                outboxEnqueuer.enqueueSendMessage(
                    100,
                    "Корзина пуста.",
                    match { it is InlineKeyboardMarkup },
                )
            }
        }

    @Test
    fun `cart shows real draft items quantity and total`() =
        runBlocking {
            val context =
                TableContext(
                    venueId = 10L,
                    venueName = "Venue",
                    tableId = 11L,
                    tableNumber = 5,
                    tableToken = "TOKEN",
                    staffChatId = null,
                )
            coEvery { chatContextRepository.get(100) } returns StoredChatContext(userId = 200, tableToken = "TOKEN")
            coEvery { tableTokenRepository.resolve("TOKEN") } returns context
            coEvery { subscriptionRepository.getSubscriptionStatus(10L) } returns SubscriptionStatus.ACTIVE
            coEvery { ordersRepository.findActiveOrderSummary(11L) } returns null
            coEvery { guestMenuRepository.getMenu(10L) } returns
                MenuModel(
                    venueId = 10L,
                    categories =
                        listOf(
                            MenuCategoryModel(
                                id = 500L,
                                name = "Основное меню",
                                sortOrder = 0,
                                items =
                                    listOf(
                                        MenuItemModel(
                                            id = 1000L,
                                            name = "Кальян классический",
                                            priceMinor = 25000L,
                                            currency = "RUB",
                                            isAvailable = true,
                                            sortOrder = 0,
                                        ),
                                    ),
                            ),
                        ),
                )

            val selectItemUpdate =
                TelegramUpdate(
                    updateId = 23,
                    callbackQuery =
                        CallbackQuery(
                            id = "cb-item-first",
                            from = User(id = 200),
                            message =
                                Message(
                                    messageId = 33,
                                    chat = Chat(id = 100, type = "private"),
                                    text = null,
                                ),
                            data = "bot_menu_item:500:1000",
                        ),
                )
            val selectItemAgainUpdate =
                TelegramUpdate(
                    updateId = 24,
                    callbackQuery =
                        CallbackQuery(
                            id = "cb-item-second",
                            from = User(id = 200),
                            message =
                                Message(
                                    messageId = 34,
                                    chat = Chat(id = 100, type = "private"),
                                    text = null,
                                ),
                            data = "bot_menu_item:500:1000",
                        ),
                )
            val openCartUpdate =
                TelegramUpdate(
                    updateId = 25,
                    callbackQuery =
                        CallbackQuery(
                            id = "cb-open-cart",
                            from = User(id = 200),
                            message =
                                Message(
                                    messageId = 35,
                                    chat = Chat(id = 100, type = "private"),
                                    text = null,
                                ),
                            data = "bot_menu_item_cart",
                        ),
                )

            router.process(selectItemUpdate)
            router.process(selectItemAgainUpdate)
            router.process(openCartUpdate)

            coVerify {
                outboxEnqueuer.enqueueSendMessage(
                    100,
                    "Кальян классический\nКоличество: 2\nСумма: 500.00 ₽",
                    match { it is InlineKeyboardMarkup },
                )
            }
            coVerify {
                outboxEnqueuer.enqueueSendMessage(
                    100,
                    "🧺 Корзина\nИтого: 500.00 ₽",
                    match { it is InlineKeyboardMarkup },
                )
            }
        }

    @Test
    fun `cart increase action increments item quantity`() =
        runBlocking {
            val context =
                TableContext(
                    venueId = 10L,
                    venueName = "Venue",
                    tableId = 11L,
                    tableNumber = 5,
                    tableToken = "TOKEN",
                    staffChatId = null,
                )
            coEvery { chatContextRepository.get(100) } returns StoredChatContext(userId = 200, tableToken = "TOKEN")
            coEvery { tableTokenRepository.resolve("TOKEN") } returns context
            coEvery { subscriptionRepository.getSubscriptionStatus(10L) } returns SubscriptionStatus.ACTIVE
            coEvery { ordersRepository.findActiveOrderSummary(11L) } returns null
            coEvery { guestMenuRepository.getMenu(10L) } returns
                MenuModel(
                    venueId = 10L,
                    categories =
                        listOf(
                            MenuCategoryModel(
                                id = 500L,
                                name = "Основное меню",
                                sortOrder = 0,
                                items =
                                    listOf(
                                        MenuItemModel(
                                            id = 1000L,
                                            name = "Кальян классический",
                                            priceMinor = 25000L,
                                            currency = "RUB",
                                            isAvailable = true,
                                            sortOrder = 0,
                                        ),
                                    ),
                            ),
                        ),
                )

            router.process(
                TelegramUpdate(
                    updateId = 28,
                    callbackQuery =
                        CallbackQuery(
                            id = "cb-add-before-inc",
                            from = User(id = 200),
                            message = Message(messageId = 38, chat = Chat(id = 100, type = "private"), text = null),
                            data = "bot_menu_item:500:1000",
                        ),
                ),
            )
            router.process(
                TelegramUpdate(
                    updateId = 29,
                    callbackQuery =
                        CallbackQuery(
                            id = "cb-inc",
                            from = User(id = 200),
                            message = Message(messageId = 39, chat = Chat(id = 100, type = "private"), text = null),
                            data = "bot_menu_cart_inc:1000",
                        ),
                ),
            )

            coVerify {
                outboxEnqueuer.enqueueSendMessage(
                    100,
                    "Кальян классический\nКоличество: 2\nСумма: 500.00 ₽",
                    match { it is InlineKeyboardMarkup },
                )
            }
            coVerify {
                outboxEnqueuer.enqueueSendMessage(
                    100,
                    "🧺 Корзина\nИтого: 500.00 ₽",
                    match { it is InlineKeyboardMarkup },
                )
            }
        }

    @Test
    fun `cart decrease action removes item when quantity reaches zero`() =
        runBlocking {
            val context =
                TableContext(
                    venueId = 10L,
                    venueName = "Venue",
                    tableId = 11L,
                    tableNumber = 5,
                    tableToken = "TOKEN",
                    staffChatId = null,
                )
            coEvery { chatContextRepository.get(100) } returns StoredChatContext(userId = 200, tableToken = "TOKEN")
            coEvery { tableTokenRepository.resolve("TOKEN") } returns context
            coEvery { subscriptionRepository.getSubscriptionStatus(10L) } returns SubscriptionStatus.ACTIVE
            coEvery { ordersRepository.findActiveOrderSummary(11L) } returns null
            coEvery { guestMenuRepository.getMenu(10L) } returns
                MenuModel(
                    venueId = 10L,
                    categories =
                        listOf(
                            MenuCategoryModel(
                                id = 500L,
                                name = "Основное меню",
                                sortOrder = 0,
                                items =
                                    listOf(
                                        MenuItemModel(
                                            id = 1000L,
                                            name = "Кальян классический",
                                            priceMinor = 25000L,
                                            currency = "RUB",
                                            isAvailable = true,
                                            sortOrder = 0,
                                        ),
                                    ),
                            ),
                        ),
                )

            router.process(
                TelegramUpdate(
                    updateId = 30,
                    callbackQuery =
                        CallbackQuery(
                            id = "cb-add-before-dec",
                            from = User(id = 200),
                            message = Message(messageId = 40, chat = Chat(id = 100, type = "private"), text = null),
                            data = "bot_menu_item:500:1000",
                        ),
                ),
            )
            router.process(
                TelegramUpdate(
                    updateId = 31,
                    callbackQuery =
                        CallbackQuery(
                            id = "cb-dec",
                            from = User(id = 200),
                            message = Message(messageId = 41, chat = Chat(id = 100, type = "private"), text = null),
                            data = "bot_menu_cart_dec:1000",
                        ),
                ),
            )

            coVerify {
                outboxEnqueuer.enqueueSendMessage(
                    100,
                    "Корзина пуста.",
                    match { it is InlineKeyboardMarkup },
                )
            }
        }

    @Test
    fun `cart remove action deletes selected item`() =
        runBlocking {
            val context =
                TableContext(
                    venueId = 10L,
                    venueName = "Venue",
                    tableId = 11L,
                    tableNumber = 5,
                    tableToken = "TOKEN",
                    staffChatId = null,
                )
            coEvery { chatContextRepository.get(100) } returns StoredChatContext(userId = 200, tableToken = "TOKEN")
            coEvery { tableTokenRepository.resolve("TOKEN") } returns context
            coEvery { subscriptionRepository.getSubscriptionStatus(10L) } returns SubscriptionStatus.ACTIVE
            coEvery { ordersRepository.findActiveOrderSummary(11L) } returns null
            coEvery { guestMenuRepository.getMenu(10L) } returns
                MenuModel(
                    venueId = 10L,
                    categories =
                        listOf(
                            MenuCategoryModel(
                                id = 500L,
                                name = "Основное меню",
                                sortOrder = 0,
                                items =
                                    listOf(
                                        MenuItemModel(
                                            id = 1000L,
                                            name = "Кальян классический",
                                            priceMinor = 25000L,
                                            currency = "RUB",
                                            isAvailable = true,
                                            sortOrder = 0,
                                        ),
                                        MenuItemModel(
                                            id = 1001L,
                                            name = "Лимонад",
                                            priceMinor = 4500L,
                                            currency = "RUB",
                                            isAvailable = true,
                                            sortOrder = 1,
                                        ),
                                    ),
                            ),
                        ),
                )

            router.process(
                TelegramUpdate(
                    updateId = 32,
                    callbackQuery =
                        CallbackQuery(
                            id = "cb-add-item-1",
                            from = User(id = 200),
                            message = Message(messageId = 42, chat = Chat(id = 100, type = "private"), text = null),
                            data = "bot_menu_item:500:1000",
                        ),
                ),
            )
            router.process(
                TelegramUpdate(
                    updateId = 33,
                    callbackQuery =
                        CallbackQuery(
                            id = "cb-add-item-2",
                            from = User(id = 200),
                            message = Message(messageId = 43, chat = Chat(id = 100, type = "private"), text = null),
                            data = "bot_menu_item:500:1001",
                        ),
                ),
            )
            router.process(
                TelegramUpdate(
                    updateId = 34,
                    callbackQuery =
                        CallbackQuery(
                            id = "cb-remove-item-1",
                            from = User(id = 200),
                            message = Message(messageId = 44, chat = Chat(id = 100, type = "private"), text = null),
                            data = "bot_menu_cart_remove:1000",
                        ),
                ),
            )

            coVerify {
                outboxEnqueuer.enqueueSendMessage(
                    100,
                    "Лимонад\nКоличество: 1\nСумма: 45.00 ₽",
                    match { it is InlineKeyboardMarkup },
                )
            }
            coVerify {
                outboxEnqueuer.enqueueSendMessage(
                    100,
                    "🧺 Корзина\nИтого: 45.00 ₽",
                    match { it is InlineKeyboardMarkup },
                )
            }
        }

    @Test
    fun `cart clear action removes draft items`() =
        runBlocking {
            val context =
                TableContext(
                    venueId = 10L,
                    venueName = "Venue",
                    tableId = 11L,
                    tableNumber = 5,
                    tableToken = "TOKEN",
                    staffChatId = null,
                )
            coEvery { chatContextRepository.get(100) } returns StoredChatContext(userId = 200, tableToken = "TOKEN")
            coEvery { tableTokenRepository.resolve("TOKEN") } returns context
            coEvery { subscriptionRepository.getSubscriptionStatus(10L) } returns SubscriptionStatus.ACTIVE
            coEvery { ordersRepository.findActiveOrderSummary(11L) } returns null
            coEvery { guestMenuRepository.getMenu(10L) } returns
                MenuModel(
                    venueId = 10L,
                    categories =
                        listOf(
                            MenuCategoryModel(
                                id = 500L,
                                name = "Основное меню",
                                sortOrder = 0,
                                items =
                                    listOf(
                                        MenuItemModel(
                                            id = 1000L,
                                            name = "Кальян классический",
                                            priceMinor = 25000L,
                                            currency = "RUB",
                                            isAvailable = true,
                                            sortOrder = 0,
                                        ),
                                    ),
                            ),
                        ),
                )

            val addItemUpdate =
                TelegramUpdate(
                    updateId = 26,
                    callbackQuery =
                        CallbackQuery(
                            id = "cb-add-before-clear",
                            from = User(id = 200),
                            message =
                                Message(
                                    messageId = 36,
                                    chat = Chat(id = 100, type = "private"),
                                    text = null,
                                ),
                            data = "bot_menu_item:500:1000",
                        ),
                )
            val clearCartUpdate =
                TelegramUpdate(
                    updateId = 27,
                    callbackQuery =
                        CallbackQuery(
                            id = "cb-clear-cart",
                            from = User(id = 200),
                            message =
                                Message(
                                    messageId = 37,
                                    chat = Chat(id = 100, type = "private"),
                                    text = null,
                                ),
                            data = "bot_menu_cart_clear",
                        ),
                )

            router.process(addItemUpdate)
            router.process(clearCartUpdate)

            coVerify {
                outboxEnqueuer.enqueueSendMessage(
                    100,
                    "Корзина пуста.",
                    match { it is InlineKeyboardMarkup },
                )
            }
        }

    @Test
    fun `cart checkout with empty draft shows empty cart message`() =
        runBlocking {
            val context =
                TableContext(
                    venueId = 10L,
                    venueName = "Venue",
                    tableId = 11L,
                    tableNumber = 5,
                    tableToken = "TOKEN",
                    staffChatId = null,
                )
            coEvery { chatContextRepository.get(100) } returns StoredChatContext(userId = 200, tableToken = "TOKEN")
            coEvery { tableTokenRepository.resolve("TOKEN") } returns context
            coEvery { subscriptionRepository.getSubscriptionStatus(10L) } returns SubscriptionStatus.ACTIVE

            router.process(
                TelegramUpdate(
                    updateId = 100,
                    callbackQuery =
                        CallbackQuery(
                            id = "cb-empty-checkout",
                            from = User(id = 200),
                            message = Message(messageId = 200, chat = Chat(id = 100, type = "private"), text = null),
                            data = "bot_menu_cart_checkout",
                        ),
                ),
            )

            coVerify {
                outboxEnqueuer.enqueueSendMessage(
                    100,
                    "Корзина пуста.",
                    match { it is InlineKeyboardMarkup },
                )
            }
            coVerify(exactly = 0) {
                ordersRepository.createGuestOrderBatch(
                    any(),
                    any(),
                    any(),
                    any(),
                    any(),
                    any(),
                    any(),
                    any(),
                )
            }
        }

    @Test
    fun `cart checkout submits guest order batch and clears draft cart`() =
        runBlocking {
            val now = Instant.parse("2026-03-30T10:00:00Z")
            val context =
                TableContext(
                    venueId = 10L,
                    venueName = "Venue",
                    tableId = 11L,
                    tableNumber = 5,
                    tableToken = "TOKEN",
                    staffChatId = null,
                )
            coEvery { chatContextRepository.get(100) } returns StoredChatContext(userId = 200, tableToken = "TOKEN")
            coEvery { tableTokenRepository.resolve("TOKEN") } returns context
            coEvery { subscriptionRepository.getSubscriptionStatus(10L) } returns SubscriptionStatus.ACTIVE
            coEvery { ordersRepository.findActiveOrderSummary(11L) } returns null
            coEvery { guestMenuRepository.getMenu(10L) } returns
                MenuModel(
                    venueId = 10L,
                    categories =
                        listOf(
                            MenuCategoryModel(
                                id = 500L,
                                name = "Основное меню",
                                sortOrder = 0,
                                items =
                                    listOf(
                                        MenuItemModel(
                                            id = 1000L,
                                            name = "Кальян классический",
                                            priceMinor = 25000L,
                                            currency = "RUB",
                                            isAvailable = true,
                                            sortOrder = 0,
                                        ),
                                    ),
                            ),
                        ),
                )
            coEvery {
                tableSessionRepository.resolveActiveSession(
                    venueId = any(),
                    tableId = any(),
                    ttl = any(),
                    now = any(),
                )
            } returns
                TableSessionRecord(
                    id = 55L,
                    venueId = 10L,
                    tableId = 11L,
                    startedAt = now.minusSeconds(60),
                    lastActivityAt = now,
                    expiresAt = now.plusSeconds(7200),
                    endedAt = null,
                    status = TableSessionStatus.ACTIVE,
                )
            coEvery {
                guestTabsRepository.ensurePersonalTab(
                    venueId = 10L,
                    tableSessionId = 55L,
                    userId = 200L,
                )
            } returns
                GuestTabModel(
                    id = 77L,
                    venueId = 10L,
                    tableSessionId = 55L,
                    type = "PERSONAL",
                    ownerUserId = 200L,
                    status = "ACTIVE",
                )
            coEvery {
                ordersRepository.createGuestOrderBatch(
                    tableId = 11L,
                    venueId = 10L,
                    tableSessionId = 55L,
                    userId = 200L,
                    idempotencyKey = "bot-cart-checkout:cb-checkout",
                    tabId = 77L,
                    comment = null,
                    items = match { it.size == 1 && it[0].itemId == 1000L && it[0].qty == 1 },
                )
            } returns
                CreatedOrderBatch(
                    orderId = 900L,
                    batchId = 901L,
                    idempotencyReplay = false,
                )

            router.process(
                TelegramUpdate(
                    updateId = 101,
                    callbackQuery =
                        CallbackQuery(
                            id = "cb-add-item-for-checkout",
                            from = User(id = 200),
                            message = Message(messageId = 201, chat = Chat(id = 100, type = "private"), text = null),
                            data = "bot_menu_item:500:1000",
                        ),
                ),
            )
            router.process(
                TelegramUpdate(
                    updateId = 102,
                    callbackQuery =
                        CallbackQuery(
                            id = "cb-checkout",
                            from = User(id = 200),
                            message = Message(messageId = 202, chat = Chat(id = 100, type = "private"), text = null),
                            data = "bot_menu_cart_checkout",
                        ),
                ),
            )
            router.process(
                TelegramUpdate(
                    updateId = 103,
                    callbackQuery =
                        CallbackQuery(
                            id = "cb-open-cart-after-checkout",
                            from = User(id = 200),
                            message = Message(messageId = 203, chat = Chat(id = 100, type = "private"), text = null),
                            data = "bot_menu_item_cart",
                        ),
                ),
            )

            coVerify {
                ordersRepository.createGuestOrderBatch(
                    tableId = 11L,
                    venueId = 10L,
                    tableSessionId = 55L,
                    userId = 200L,
                    idempotencyKey = "bot-cart-checkout:cb-checkout",
                    tabId = 77L,
                    comment = null,
                    items = match { it.size == 1 && it[0].itemId == 1000L && it[0].qty == 1 },
                )
            }
            coVerify {
                outboxEnqueuer.enqueueSendMessage(
                    100,
                    "✅ Заказ отправлен.\nНомер заказа: #900.\nЧто дальше?",
                    match {
                        it is InlineKeyboardMarkup &&
                            it.inlineKeyboard.flatten().size == 1 &&
                            it.inlineKeyboard.flatten().single().text == "➕ Дозаказать" &&
                            it.inlineKeyboard.flatten().single().callbackData == "bot_menu_reorder_entry"
                    },
                )
            }
            coVerify {
                outboxEnqueuer.enqueueSendMessage(
                    100,
                    "Корзина пуста.",
                    match { it is InlineKeyboardMarkup },
                )
            }
        }

    @Test
    fun `start with same table token keeps draft cart for same active table context`() =
        runBlocking {
            val context =
                TableContext(
                    venueId = 10L,
                    venueName = "Venue",
                    tableId = 11L,
                    tableNumber = 5,
                    tableToken = "TOKEN",
                    staffChatId = null,
                )
            coEvery { chatContextRepository.get(100) } returns StoredChatContext(userId = 200, tableToken = "TOKEN")
            coEvery { tableTokenRepository.resolve("TOKEN") } returns context
            coEvery { subscriptionRepository.getSubscriptionStatus(10L) } returns SubscriptionStatus.ACTIVE
            coEvery { ordersRepository.findActiveOrderSummary(11L) } returns null
            coEvery { guestMenuRepository.getMenu(10L) } returns
                MenuModel(
                    venueId = 10L,
                    categories =
                        listOf(
                            MenuCategoryModel(
                                id = 500L,
                                name = "Основное меню",
                                sortOrder = 0,
                                items =
                                    listOf(
                                        MenuItemModel(
                                            id = 1000L,
                                            name = "Кальян классический",
                                            priceMinor = 25000L,
                                            currency = "RUB",
                                            isAvailable = true,
                                            sortOrder = 0,
                                        ),
                                    ),
                            ),
                        ),
                )

            router.process(
                TelegramUpdate(
                    updateId = 52,
                    callbackQuery =
                        CallbackQuery(
                            id = "cb-add-before-start-reset",
                            from = User(id = 200),
                            message = Message(messageId = 62, chat = Chat(id = 100, type = "private"), text = null),
                            data = "bot_menu_item:500:1000",
                        ),
                ),
            )
            router.process(
                TelegramUpdate(
                    updateId = 53,
                    message =
                        Message(
                            messageId = 63,
                            chat = Chat(id = 100, type = "private"),
                            fromUser = User(id = 200),
                            text = "/start TOKEN",
                        ),
                ),
            )
            router.process(
                TelegramUpdate(
                    updateId = 54,
                    callbackQuery =
                        CallbackQuery(
                            id = "cb-open-cart-after-start-reset",
                            from = User(id = 200),
                            message = Message(messageId = 64, chat = Chat(id = 100, type = "private"), text = null),
                            data = "bot_menu_item_cart",
                        ),
                ),
                )

            coVerify {
                outboxEnqueuer.enqueueSendMessage(
                    100,
                    "Кальян классический\nКоличество: 1\nСумма: 250.00 ₽",
                    match { it is InlineKeyboardMarkup },
                )
            }
        }

    @Test
    fun `start with different table token clears previous table draft cart`() =
        runBlocking {
            val contextA =
                TableContext(
                    venueId = 10L,
                    venueName = "Venue",
                    tableId = 11L,
                    tableNumber = 5,
                    tableToken = "TOKEN_A",
                    staffChatId = null,
                )
            val contextB =
                TableContext(
                    venueId = 10L,
                    venueName = "Venue",
                    tableId = 12L,
                    tableNumber = 6,
                    tableToken = "TOKEN_B",
                    staffChatId = null,
                )
            var storedContext = StoredChatContext(userId = 200, tableToken = "TOKEN_A")
            coEvery { chatContextRepository.get(100) } answers { storedContext }
            coEvery { chatContextRepository.saveContext(any(), any(), any()) } answers {
                storedContext =
                    StoredChatContext(
                        userId = args[1] as Long,
                        tableToken = (args[2] as TableContext).tableToken,
                    )
                Unit
            }
            coEvery { tableTokenRepository.resolve("TOKEN_A") } returns contextA
            coEvery { tableTokenRepository.resolve("TOKEN_B") } returns contextB
            coEvery { subscriptionRepository.getSubscriptionStatus(10L) } returns SubscriptionStatus.ACTIVE
            coEvery { guestMenuRepository.getMenu(10L) } returns
                MenuModel(
                    venueId = 10L,
                    categories =
                        listOf(
                            MenuCategoryModel(
                                id = 500L,
                                name = "Основное меню",
                                sortOrder = 0,
                                items =
                                    listOf(
                                        MenuItemModel(
                                            id = 1000L,
                                            name = "Кальян классический",
                                            priceMinor = 25000L,
                                            currency = "RUB",
                                            isAvailable = true,
                                            sortOrder = 0,
                                        ),
                                    ),
                            ),
                        ),
                )

            router.process(
                TelegramUpdate(
                    updateId = 55,
                    callbackQuery =
                        CallbackQuery(
                            id = "cb-add-before-switch-table",
                            from = User(id = 200),
                            message = Message(messageId = 65, chat = Chat(id = 100, type = "private"), text = null),
                            data = "bot_menu_item:500:1000",
                        ),
                ),
            )
            router.process(
                TelegramUpdate(
                    updateId = 56,
                    message =
                        Message(
                            messageId = 66,
                            chat = Chat(id = 100, type = "private"),
                            fromUser = User(id = 200),
                            text = "/start TOKEN_B",
                        ),
                ),
            )
            router.process(
                TelegramUpdate(
                    updateId = 57,
                    message =
                        Message(
                            messageId = 67,
                            chat = Chat(id = 100, type = "private"),
                            fromUser = User(id = 200),
                            text = "/start TOKEN_A",
                        ),
                ),
            )
            router.process(
                TelegramUpdate(
                    updateId = 58,
                    callbackQuery =
                        CallbackQuery(
                            id = "cb-open-cart-after-switch-table",
                            from = User(id = 200),
                            message = Message(messageId = 68, chat = Chat(id = 100, type = "private"), text = null),
                            data = "bot_menu_item_cart",
                        ),
                ),
            )

            coVerify {
                outboxEnqueuer.enqueueSendMessage(
                    100,
                    "Корзина пуста.",
                    match { it is InlineKeyboardMarkup },
                )
            }
            coVerify(exactly = 0) {
                outboxEnqueuer.enqueueSendMessage(
                    100,
                    "Кальян классический\nКоличество: 1\nСумма: 250.00 ₽",
                    any(),
                )
            }
        }

    @Test
    fun `start with same table token clears draft cart when active table session changed`() =
        runBlocking {
            val context =
                TableContext(
                    venueId = 10L,
                    venueName = "Venue",
                    tableId = 11L,
                    tableNumber = 5,
                    tableToken = "TOKEN",
                    staffChatId = null,
                )
            val session55 =
                TableSessionRecord(
                    id = 55L,
                    venueId = 10L,
                    tableId = 11L,
                    startedAt = Instant.parse("2026-03-30T10:00:00Z").minusSeconds(60),
                    lastActivityAt = Instant.parse("2026-03-30T10:00:00Z"),
                    expiresAt = Instant.parse("2026-03-30T10:00:00Z").plusSeconds(7200),
                    endedAt = null,
                    status = TableSessionStatus.ACTIVE,
                )
            val session66 =
                TableSessionRecord(
                    id = 66L,
                    venueId = 10L,
                    tableId = 11L,
                    startedAt = Instant.parse("2026-03-30T12:00:00Z").minusSeconds(60),
                    lastActivityAt = Instant.parse("2026-03-30T12:00:00Z"),
                    expiresAt = Instant.parse("2026-03-30T12:00:00Z").plusSeconds(7200),
                    endedAt = null,
                    status = TableSessionStatus.ACTIVE,
                )
            coEvery {
                tableSessionRepository.resolveActiveSession(
                    venueId = any(),
                    tableId = any(),
                    ttl = any(),
                    now = any(),
                )
            } returnsMany listOf(session55, session66, session66)
            coEvery { chatContextRepository.get(100) } returns StoredChatContext(userId = 200, tableToken = "TOKEN")
            coEvery { tableTokenRepository.resolve("TOKEN") } returns context
            coEvery { subscriptionRepository.getSubscriptionStatus(10L) } returns SubscriptionStatus.ACTIVE
            coEvery { guestMenuRepository.getMenu(10L) } returns
                MenuModel(
                    venueId = 10L,
                    categories =
                        listOf(
                            MenuCategoryModel(
                                id = 500L,
                                name = "Основное меню",
                                sortOrder = 0,
                                items =
                                    listOf(
                                        MenuItemModel(
                                            id = 1000L,
                                            name = "Кальян классический",
                                            priceMinor = 25000L,
                                            currency = "RUB",
                                            isAvailable = true,
                                            sortOrder = 0,
                                        ),
                                    ),
                            ),
                        ),
                )

            router.process(
                TelegramUpdate(
                    updateId = 59,
                    callbackQuery =
                        CallbackQuery(
                            id = "cb-add-before-session-change",
                            from = User(id = 200),
                            message = Message(messageId = 69, chat = Chat(id = 100, type = "private"), text = null),
                            data = "bot_menu_item:500:1000",
                        ),
                ),
            )
            router.process(
                TelegramUpdate(
                    updateId = 60,
                    message =
                        Message(
                            messageId = 70,
                            chat = Chat(id = 100, type = "private"),
                            fromUser = User(id = 200),
                            text = "/start TOKEN",
                        ),
                ),
            )
            router.process(
                TelegramUpdate(
                    updateId = 61,
                    message =
                        Message(
                            messageId = 71,
                            chat = Chat(id = 100, type = "private"),
                            fromUser = User(id = 200),
                            text = "/start TOKEN",
                        ),
                ),
            )
            router.process(
                TelegramUpdate(
                    updateId = 62,
                    callbackQuery =
                        CallbackQuery(
                            id = "cb-open-cart-after-session-change",
                            from = User(id = 200),
                            message = Message(messageId = 72, chat = Chat(id = 100, type = "private"), text = null),
                            data = "bot_menu_item_cart",
                        ),
                ),
            )

            coVerify {
                outboxEnqueuer.enqueueSendMessage(
                    100,
                    "Корзина пуста.",
                    match { it is InlineKeyboardMarkup },
                )
            }
            coVerify(exactly = 0) {
                outboxEnqueuer.enqueueSendMessage(
                    100,
                    "Кальян классический\nКоличество: 1\nСумма: 250.00 ₽",
                    any(),
                )
            }
        }

    @Test
    fun `item consultation action opens staff reasons entry`() =
        runBlocking {
            val context =
                TableContext(
                    venueId = 10L,
                    venueName = "Venue",
                    tableId = 11L,
                    tableNumber = 5,
                    tableToken = "TOKEN",
                    staffChatId = null,
                )
            coEvery { chatContextRepository.get(100) } returns StoredChatContext(userId = 200, tableToken = "TOKEN")
            coEvery { tableTokenRepository.resolve("TOKEN") } returns context
            coEvery { subscriptionRepository.getSubscriptionStatus(10L) } returns SubscriptionStatus.ACTIVE

            val update =
                TelegramUpdate(
                    updateId = 20,
                    callbackQuery =
                        CallbackQuery(
                            id = "cb-item-staff",
                            from = User(id = 200),
                            message =
                                Message(
                                    messageId = 30,
                                    chat = Chat(id = 100, type = "private"),
                                    text = null,
                                ),
                            data = "bot_menu_item_staff",
                        ),
                )

            router.process(update)

            coVerify {
                outboxEnqueuer.enqueueSendMessage(
                    100,
                    "Чем помочь?",
                    match { it is InlineKeyboardMarkup },
                )
            }
        }

    @Test
    fun `bill reason asks payment method and card selection creates staff call`() =
        runBlocking {
            val context =
                TableContext(
                    venueId = 10L,
                    venueName = "Venue",
                    tableId = 11L,
                    tableNumber = 5,
                    tableToken = "TOKEN",
                    staffChatId = null,
                )
            coEvery { chatContextRepository.get(100) } returns StoredChatContext(userId = 200, tableToken = "TOKEN")
            coEvery { tableTokenRepository.resolve("TOKEN") } returns context
            coEvery { subscriptionRepository.getSubscriptionStatus(10L) } returns SubscriptionStatus.ACTIVE
            coEvery {
                staffCallRepository.createStaffCall(
                    venueId = 10L,
                    tableId = 11L,
                    createdByUserId = 200L,
                    reason = StaffCallReason.BILL,
                    comment = "Способ оплаты: Картой",
                )
            } returns 1L

            router.process(
                TelegramUpdate(
                    updateId = 20_101,
                    callbackQuery =
                        CallbackQuery(
                            id = "cb-bill-reason",
                            from = User(id = 200),
                            message =
                                Message(
                                    messageId = 30_101,
                                    chat = Chat(id = 100, type = "private"),
                                    text = null,
                                ),
                            data = "staff_call_reason:BILL",
                        ),
                ),
            )

            router.process(
                TelegramUpdate(
                    updateId = 20_102,
                    callbackQuery =
                        CallbackQuery(
                            id = "cb-bill-payment-card",
                            from = User(id = 200),
                            message =
                                Message(
                                    messageId = 30_102,
                                    chat = Chat(id = 100, type = "private"),
                                    text = null,
                                ),
                            data = "staff_call_bill_payment:CARD",
                        ),
                ),
            )

            coVerifyOrder {
                outboxEnqueuer.enqueueSendMessage(
                    100,
                    "Как оплатите счёт?",
                    match {
                        it is InlineKeyboardMarkup &&
                            it.inlineKeyboard.flatten().any { button ->
                                button.text == "💳 Картой" && button.callbackData == "staff_call_bill_payment:CARD"
                            } &&
                            it.inlineKeyboard.flatten().any { button ->
                                button.text == "💵 Наличными" && button.callbackData == "staff_call_bill_payment:CASH"
                            }
                    },
                )
                outboxEnqueuer.enqueueSendMessage(
                    100,
                    "Персонал уведомлён, ожидайте.",
                    match {
                        it is ReplyKeyboardMarkup &&
                            it.keyboard.flatten().any { button -> button.text == "🍽️ Меню" } &&
                            it.keyboard.flatten().any { button -> button.text == "🧺 Корзина" } &&
                            it.keyboard.flatten().any { button -> button.text == "👥 Общий счёт" } &&
                            it.keyboard.flatten().any { button -> button.text == "📄 Мой заказ" }
                    },
                )
            }
            coVerify {
                staffCallRepository.createStaffCall(
                    venueId = 10L,
                    tableId = 11L,
                    createdByUserId = 200L,
                    reason = StaffCallReason.BILL,
                    comment = "Способ оплаты: Картой",
                )
            }
        }

    @Test
    fun `consultation reason creates staff call and restores table keyboard`() =
        runBlocking {
            val context =
                TableContext(
                    venueId = 10L,
                    venueName = "Venue",
                    tableId = 11L,
                    tableNumber = 5,
                    tableToken = "TOKEN",
                    staffChatId = null,
                )
            coEvery { chatContextRepository.get(100) } returns StoredChatContext(userId = 200, tableToken = "TOKEN")
            coEvery { tableTokenRepository.resolve("TOKEN") } returns context
            coEvery { subscriptionRepository.getSubscriptionStatus(10L) } returns SubscriptionStatus.ACTIVE
            coEvery {
                staffCallRepository.createStaffCall(
                    venueId = 10L,
                    tableId = 11L,
                    createdByUserId = 200L,
                    reason = StaffCallReason.COME,
                    comment = null,
                )
            } returns 1L

            router.process(
                TelegramUpdate(
                    updateId = 20_201,
                    callbackQuery =
                        CallbackQuery(
                            id = "cb-consultation-reason",
                            from = User(id = 200),
                            message =
                                Message(
                                    messageId = 30_201,
                                    chat = Chat(id = 100, type = "private"),
                                    text = null,
                                ),
                            data = "staff_call_reason:COME",
                        ),
                ),
            )

            coVerify {
                staffCallRepository.createStaffCall(
                    venueId = 10L,
                    tableId = 11L,
                    createdByUserId = 200L,
                    reason = StaffCallReason.COME,
                    comment = null,
                )
            }
            coVerify {
                outboxEnqueuer.enqueueSendMessage(
                    100,
                    "Персонал уведомлён, ожидайте.",
                    match {
                        it is ReplyKeyboardMarkup &&
                            it.keyboard.flatten().any { button -> button.text == "🍽️ Меню" } &&
                            it.keyboard.flatten().any { button -> button.text == "🧺 Корзина" } &&
                            it.keyboard.flatten().any { button -> button.text == "👥 Общий счёт" } &&
                            it.keyboard.flatten().any { button -> button.text == "📄 Мой заказ" }
                    },
                )
            }
        }

    @Test
    fun `item details back to categories opens categories list`() =
        runBlocking {
            val context =
                TableContext(
                    venueId = 10L,
                    venueName = "Venue",
                    tableId = 11L,
                    tableNumber = 5,
                    tableToken = "TOKEN",
                    staffChatId = null,
                )
            coEvery { chatContextRepository.get(100) } returns StoredChatContext(userId = 200, tableToken = "TOKEN")
            coEvery { tableTokenRepository.resolve("TOKEN") } returns context
            coEvery { subscriptionRepository.getSubscriptionStatus(10L) } returns SubscriptionStatus.ACTIVE
            coEvery { guestMenuRepository.getMenu(10L) } returns
                MenuModel(
                    venueId = 10L,
                    categories =
                        listOf(
                            MenuCategoryModel(
                                id = 500L,
                                name = "Основное меню",
                                sortOrder = 0,
                                items = emptyList(),
                            ),
                            MenuCategoryModel(
                                id = 501L,
                                name = "Напитки",
                                sortOrder = 1,
                                items = emptyList(),
                            ),
                        ),
                )

            val update =
                TelegramUpdate(
                    updateId = 16,
                    callbackQuery =
                        CallbackQuery(
                            id = "cb-back-categories",
                            from = User(id = 200),
                            message =
                                Message(
                                    messageId = 26,
                                    chat = Chat(id = 100, type = "private"),
                                    text = null,
                                ),
                            data = "bot_menu_back_categories",
                        ),
                )

            router.process(update)

            coVerify {
                outboxEnqueuer.enqueueSendMessage(
                    100,
                    "Venue\nВыберите категорию.",
                    match {
                        it is InlineKeyboardMarkup &&
                            it.inlineKeyboard.flatten().any { button ->
                                button.text == "Основное меню" && button.callbackData == "bot_menu_category:500"
                            }
                    },
                )
            }
        }

    @Test
    fun `item details back to category opens category items list`() =
        runBlocking {
            val context =
                TableContext(
                    venueId = 10L,
                    venueName = "Venue",
                    tableId = 11L,
                    tableNumber = 5,
                    tableToken = "TOKEN",
                    staffChatId = null,
                )
            coEvery { chatContextRepository.get(100) } returns StoredChatContext(userId = 200, tableToken = "TOKEN")
            coEvery { tableTokenRepository.resolve("TOKEN") } returns context
            coEvery { subscriptionRepository.getSubscriptionStatus(10L) } returns SubscriptionStatus.ACTIVE
            coEvery { guestMenuRepository.getMenu(10L) } returns
                MenuModel(
                    venueId = 10L,
                    categories =
                        listOf(
                            MenuCategoryModel(
                                id = 500L,
                                name = "Основное меню",
                                sortOrder = 0,
                                items =
                                    listOf(
                                        MenuItemModel(
                                            id = 1000L,
                                            name = "Кальян классический",
                                            priceMinor = 25000L,
                                            currency = "RUB",
                                            isAvailable = true,
                                            sortOrder = 0,
                                        ),
                                    ),
                            ),
                        ),
                )

            val update =
                TelegramUpdate(
                    updateId = 17,
                    callbackQuery =
                        CallbackQuery(
                            id = "cb-back-category",
                            from = User(id = 200),
                            message =
                                Message(
                                    messageId = 27,
                                    chat = Chat(id = 100, type = "private"),
                                    text = null,
                                ),
                            data = "bot_menu_back_category:500",
                        ),
                )

            router.process(update)

            coVerify {
                outboxEnqueuer.enqueueSendMessage(
                    100,
                    "Основное меню\nВыберите позицию.",
                    match {
                        it is InlineKeyboardMarkup &&
                            it.inlineKeyboard.flatten().any { button ->
                                button.text == "Кальян классический — 250.00 ₽" &&
                                    button.callbackData == "bot_menu_item:500:1000"
                            }
                    },
                )
            }
        }

    @Test
    fun `apply table token does not save context when subscription blocked`() =
        runBlocking {
            val context =
                TableContext(
                    venueId = 10L,
                    venueName = "Venue",
                    tableId = 11L,
                    tableNumber = 5,
                    tableToken = "TOKEN",
                    staffChatId = null,
                )
            coEvery { tableTokenRepository.resolve("TOKEN") } returns context
            coEvery {
                subscriptionRepository.getSubscriptionStatus(10L)
            } returns SubscriptionStatus.SUSPENDED_BY_PLATFORM

            val update =
                TelegramUpdate(
                    updateId = 1,
                    message =
                        Message(
                            messageId = 11,
                            chat = Chat(id = 100, type = "private"),
                            fromUser = User(id = 200),
                            text = "/start TOKEN",
                        ),
                )

            router.process(update)

            coVerify {
                outboxEnqueuer.enqueueSendMessage(
                    100,
                    "Подписка заведения заблокирована. Заказы недоступны.",
                    any(),
                )
            }
            coVerify(exactly = 0) { chatContextRepository.saveContext(any(), any(), any()) }
        }

    @Test
    fun `apply table token reports database unavailable when resolve fails`() =
        runBlocking {
            coEvery { tableTokenRepository.resolve("TOKEN") } throws DatabaseUnavailableException()

            val update =
                TelegramUpdate(
                    updateId = 2,
                    message =
                        Message(
                            messageId = 12,
                            chat = Chat(id = 101, type = "private"),
                            fromUser = User(id = 201),
                            text = "/start TOKEN",
                        ),
                )

            router.process(update)

            coVerify {
                outboxEnqueuer.enqueueSendMessage(101, "База недоступна, попробуйте позже.", any())
            }
            coVerify(exactly = 0) { chatContextRepository.saveContext(any(), any(), any()) }
        }

    @Test
    fun `resolve guest context reports database unavailable when context load fails`() =
        runBlocking {
            coEvery { chatContextRepository.get(102) } throws DatabaseUnavailableException()

            val update =
                TelegramUpdate(
                    updateId = 3,
                    message =
                        Message(
                            messageId = 13,
                            chat = Chat(id = 102, type = "private"),
                            fromUser = User(id = 202),
                            text = "📄 Мой заказ",
                        ),
                )

            router.process(update)

            coVerify {
                outboxEnqueuer.enqueueSendMessage(102, "База недоступна, попробуйте позже.", any())
            }
            coVerify(exactly = 0) { tableTokenRepository.resolve(any()) }
        }

    @Test
    fun `join shared code from bot flow joins tab and sends confirmation`() =
        runBlocking {
            val context =
                TableContext(
                    venueId = 10L,
                    venueName = "Venue",
                    tableId = 11L,
                    tableNumber = 5,
                    tableToken = "TOKEN",
                    staffChatId = null,
                )
            coEvery { chatContextRepository.get(100) } returns StoredChatContext(userId = 200, tableToken = "TOKEN")
            coEvery { tableTokenRepository.resolve("TOKEN") } returns context
            coEvery { subscriptionRepository.getSubscriptionStatus(10L) } returns SubscriptionStatus.ACTIVE
            coEvery {
                guestTabsRepository.joinByInvite(
                    venueId = 10L,
                    tableSessionId = 55L,
                    userId = 200L,
                    token = "1234",
                )
            } returns
                GuestTabModel(
                    id = 9L,
                    venueId = 10L,
                    tableSessionId = 55L,
                    type = "SHARED",
                    ownerUserId = 201L,
                    status = "ACTIVE",
                )

            router.process(
                TelegramUpdate(
                    updateId = 210,
                    callbackQuery =
                        CallbackQuery(
                            id = "cb-join-prompt",
                            from = User(id = 200),
                            message =
                                Message(
                                    messageId = 410,
                                    chat = Chat(id = 100, type = "private"),
                                    text = null,
                                ),
                            data = "bot_tabs_join_prompt",
                        ),
                ),
            )

            router.process(
                TelegramUpdate(
                    updateId = 211,
                    message =
                        Message(
                            messageId = 411,
                            chat = Chat(id = 100, type = "private"),
                            fromUser = User(id = 200),
                            text = "1234",
                        ),
                ),
            )

            coVerifyOrder {
                outboxEnqueuer.enqueueSendMessage(100, "Введите 4-значный код общего счёта.", any())
                outboxEnqueuer.enqueueSendMessage(
                    100,
                    "✅ Вы присоединились к общему счёту.",
                    any(),
                )
            }
            coVerify {
                guestTabsRepository.joinByInvite(
                    venueId = 10L,
                    tableSessionId = 55L,
                    userId = 200L,
                    token = "1234",
                )
            }
        }

    @Test
    fun `create shared from bot flow returns code and next actions`() =
        runBlocking {
            val context =
                TableContext(
                    venueId = 10L,
                    venueName = "Venue",
                    tableId = 11L,
                    tableNumber = 5,
                    tableToken = "TOKEN",
                    staffChatId = null,
                )
            coEvery { chatContextRepository.get(100) } returns StoredChatContext(userId = 200, tableToken = "TOKEN")
            coEvery { tableTokenRepository.resolve("TOKEN") } returns context
            coEvery { subscriptionRepository.getSubscriptionStatus(10L) } returns SubscriptionStatus.ACTIVE
            coEvery {
                guestTabsRepository.createSharedTab(
                    venueId = 10L,
                    tableSessionId = 55L,
                    ownerUserId = 200L,
                )
            } returns
                GuestTabModel(
                    id = 9L,
                    venueId = 10L,
                    tableSessionId = 55L,
                    type = "SHARED",
                    ownerUserId = 200L,
                    status = "ACTIVE",
                )

            router.process(
                TelegramUpdate(
                    updateId = 214,
                    callbackQuery =
                        CallbackQuery(
                            id = "cb-create-shared",
                            from = User(id = 200),
                            message =
                                Message(
                                    messageId = 414,
                                    chat = Chat(id = 100, type = "private"),
                                    text = null,
                                ),
                            data = "bot_tabs_create_shared",
                        ),
                ),
            )

            coVerify {
                outboxEnqueuer.enqueueSendMessage(
                    100,
                    match { text ->
                        text.startsWith("✅ Общий счёт создан.\nКод приглашения: ") &&
                            text.substringAfter("✅ Общий счёт создан.\nКод приглашения: ").length == 4
                    },
                    any(),
                )
            }
        }

    @Test
    fun `join shared code reports invalid invite when code is expired`() =
        runBlocking {
            val context =
                TableContext(
                    venueId = 10L,
                    venueName = "Venue",
                    tableId = 11L,
                    tableNumber = 5,
                    tableToken = "TOKEN",
                    staffChatId = null,
                )
            coEvery { chatContextRepository.get(100) } returns StoredChatContext(userId = 200, tableToken = "TOKEN")
            coEvery { tableTokenRepository.resolve("TOKEN") } returns context
            coEvery { subscriptionRepository.getSubscriptionStatus(10L) } returns SubscriptionStatus.ACTIVE
            coEvery {
                guestTabsRepository.joinByInvite(
                    venueId = 10L,
                    tableSessionId = 55L,
                    userId = 200L,
                    token = "9999",
                )
            } returns null

            router.process(
                TelegramUpdate(
                    updateId = 212,
                    callbackQuery =
                        CallbackQuery(
                            id = "cb-join-prompt-invalid",
                            from = User(id = 200),
                            message =
                                Message(
                                    messageId = 412,
                                    chat = Chat(id = 100, type = "private"),
                                    text = null,
                                ),
                            data = "bot_tabs_join_prompt",
                        ),
                ),
            )

            router.process(
                TelegramUpdate(
                    updateId = 213,
                    message =
                        Message(
                            messageId = 413,
                            chat = Chat(id = 100, type = "private"),
                            fromUser = User(id = 200),
                            text = "9999",
                        ),
                ),
            )

            coVerify {
                outboxEnqueuer.enqueueSendMessage(100, "Код недействителен или истёк. Попробуйте ещё раз.", any())
            }
            coVerify {
                guestTabsRepository.joinByInvite(
                    venueId = 10L,
                    tableSessionId = 55L,
                    userId = 200L,
                    token = "9999",
                )
            }
        }

    @Test
    fun `active order resolves to shared tab when selected tab state is missing`() =
        runBlocking {
            val context =
                TableContext(
                    venueId = 10L,
                    venueName = "Venue",
                    tableId = 11L,
                    tableNumber = 5,
                    tableToken = "TOKEN",
                    staffChatId = null,
                )
            coEvery { chatContextRepository.get(100) } returns StoredChatContext(userId = 200, tableToken = "TOKEN")
            coEvery { tableTokenRepository.resolve("TOKEN") } returns context
            coEvery { subscriptionRepository.getSubscriptionStatus(10L) } returns SubscriptionStatus.ACTIVE
            coEvery {
                guestTabsRepository.ensurePersonalTab(
                    venueId = 10L,
                    tableSessionId = 55L,
                    userId = 200L,
                )
            } returns
                GuestTabModel(
                    id = 24L,
                    venueId = 10L,
                    tableSessionId = 55L,
                    type = "PERSONAL",
                    ownerUserId = 200L,
                    status = "ACTIVE",
                )
            coEvery {
                guestTabsRepository.listTabsForUser(
                    venueId = 10L,
                    tableSessionId = 55L,
                    userId = 200L,
                )
            } returns
                listOf(
                    GuestTabModel(
                        id = 24L,
                        venueId = 10L,
                        tableSessionId = 55L,
                        type = "PERSONAL",
                        ownerUserId = 200L,
                        status = "ACTIVE",
                    ),
                    GuestTabModel(
                        id = 23L,
                        venueId = 10L,
                        tableSessionId = 55L,
                        type = "SHARED",
                        ownerUserId = 101L,
                        status = "ACTIVE",
                    ),
                )
            coEvery { ordersRepository.findActiveOrderSummaryForTab(11L, 24L) } returns null
            coEvery { ordersRepository.findActiveOrderSummaryForTab(11L, 23L) } returns
                com.hookah.platform.backend.telegram.ActiveOrderSummary(
                    id = 900L,
                    status = "ACTIVE",
                )
            coEvery { ordersRepository.findActiveOrderDetailsForTab(11L, 23L) } returns
                ActiveOrderDetails(
                    orderId = 900L,
                    status = "ACTIVE",
                    batches =
                        listOf(
                            OrderBatchDetails(
                                batchId = 901L,
                                comment = null,
                                items =
                                    listOf(
                                        OrderBatchItemDetails(
                                            itemId = 1000L,
                                            qty = 1,
                                        ),
                                    ),
                            ),
                        ),
                )
            coEvery { guestMenuRepository.findItemNames(10L, setOf(1000L)) } returns
                mapOf(
                    1000L to "Кальян классический",
                )

            router.process(
                TelegramUpdate(
                    updateId = 3001,
                    message =
                        Message(
                            messageId = 4001,
                            chat = Chat(id = 100, type = "private"),
                            fromUser = User(id = 200),
                            text = "📄 Мой заказ",
                        ),
                ),
            )

            coVerify {
                ordersRepository.findActiveOrderDetailsForTab(11L, 23L)
            }
            coVerify(exactly = 0) {
                outboxEnqueuer.enqueueSendMessage(100, "Активных заказов нет.", any())
            }
            coVerify {
                outboxEnqueuer.enqueueSendMessage(
                    100,
                    match { text ->
                        text.contains("Ваш заказ") &&
                            text.contains("Статус: Принят") &&
                            text.contains("- Кальян классический ×1")
                    },
                    match {
                        it is InlineKeyboardMarkup &&
                            it.inlineKeyboard.flatten().size == 1 &&
                            it.inlineKeyboard.flatten().single().text == "➕ Дозаказать" &&
                            it.inlineKeyboard.flatten().single().callbackData == "bot_menu_reorder_entry"
                    },
                )
            }
        }

    @Test
    fun `active order screen shows real active order contents`() =
        runBlocking {
            val context =
                TableContext(
                    venueId = 10L,
                    venueName = "Venue",
                    tableId = 11L,
                    tableNumber = 5,
                    tableToken = "TOKEN",
                    staffChatId = null,
                )
            coEvery { chatContextRepository.get(100) } returns StoredChatContext(userId = 200, tableToken = "TOKEN")
            coEvery { tableTokenRepository.resolve("TOKEN") } returns context
            coEvery { subscriptionRepository.getSubscriptionStatus(10L) } returns SubscriptionStatus.ACTIVE
            coEvery { ordersRepository.findActiveOrderDetailsForTab(11L, any()) } returns
                ActiveOrderDetails(
                    orderId = 900L,
                    status = "ACTIVE",
                    batches =
                        listOf(
                            OrderBatchDetails(
                                batchId = 901L,
                                comment = null,
                                items =
                                    listOf(
                                        OrderBatchItemDetails(
                                            itemId = 1000L,
                                            qty = 2,
                                        ),
                                    ),
                            ),
                            OrderBatchDetails(
                                batchId = 902L,
                                comment = "Без льда",
                                items =
                                    listOf(
                                        OrderBatchItemDetails(
                                            itemId = 1001L,
                                            qty = 1,
                                        ),
                                    ),
                            ),
                        ),
                )
            coEvery { guestMenuRepository.findItemNames(10L, setOf(1000L, 1001L)) } returns
                mapOf(
                    1000L to "Кальян классический",
                    1001L to "Лимонад",
                )

            router.process(
                TelegramUpdate(
                    updateId = 200,
                    message =
                        Message(
                            messageId = 300,
                            chat = Chat(id = 100, type = "private"),
                            fromUser = User(id = 200),
                            text = "📄 Мой заказ",
                        ),
                ),
            )

            coVerify {
                outboxEnqueuer.enqueueSendMessage(
                    100,
                    match { text ->
                        text.contains("Ваш заказ") &&
                            text.contains("Статус: Принят") &&
                            text.contains("- Кальян классический ×2") &&
                            text.contains("- Лимонад ×1") &&
                            !text.contains("ACTIVE") &&
                            !text.contains("Батч")
                    },
                    match {
                        it is InlineKeyboardMarkup &&
                            it.inlineKeyboard.flatten().size == 1 &&
                            it.inlineKeyboard.flatten().single().text == "➕ Дозаказать" &&
                            it.inlineKeyboard.flatten().single().callbackData == "bot_menu_reorder_entry"
                    },
                )
            }
        }
}
