package com.hookah.platform.backend.telegram

import com.hookah.platform.backend.miniapp.subscription.db.SubscriptionRepository
import com.hookah.platform.backend.miniapp.guest.db.GuestMenuRepository
import com.hookah.platform.backend.miniapp.guest.db.GuestTabsRepository
import com.hookah.platform.backend.miniapp.guest.db.TableSessionRepository
import com.hookah.platform.backend.miniapp.guest.db.GuestBookingRepository
import com.hookah.platform.backend.telegram.db.ChatContextRepository
import com.hookah.platform.backend.telegram.db.DialogStateRepository
import com.hookah.platform.backend.telegram.db.IdempotencyRepository
import com.hookah.platform.backend.telegram.db.LinkCodeResult
import com.hookah.platform.backend.telegram.db.LinkAndBindResult
import com.hookah.platform.backend.telegram.db.OrdersRepository
import com.hookah.platform.backend.telegram.db.StaffCallRepository
import com.hookah.platform.backend.telegram.db.StaffChatLinkCodeRepository
import com.hookah.platform.backend.telegram.db.StaffChatStatus
import com.hookah.platform.backend.telegram.db.TableTokenRepository
import com.hookah.platform.backend.telegram.db.UserRepository
import com.hookah.platform.backend.telegram.db.VenueAccessRepository
import com.hookah.platform.backend.telegram.db.VenueBookingHoursRepository
import com.hookah.platform.backend.telegram.db.VenueMenuSectionImagesRepository
import com.hookah.platform.backend.telegram.db.VenueRepository
import com.hookah.platform.backend.telegram.db.VenueShort
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
import java.time.Duration
import java.time.Instant
import java.sql.Connection

class TelegramBotRouterLinkCommandTest {
    private val apiClient: TelegramApiClient = mockk(relaxed = true)
    private val outboxEnqueuer: TelegramOutboxEnqueuer = mockk(relaxed = true)
    private val idempotencyRepository: IdempotencyRepository = mockk()
    private val userRepository: UserRepository = mockk(relaxed = true)
    private val tableTokenRepository: TableTokenRepository = mockk()
    private val chatContextRepository: ChatContextRepository = mockk()
    private val dialogStateRepository: DialogStateRepository = mockk(relaxed = true)
    private val ordersRepository: OrdersRepository = mockk()
    private val staffCallRepository: StaffCallRepository = mockk()
    private val staffChatLinkCodeRepository: StaffChatLinkCodeRepository = mockk()
    private val guestBookingRepository: GuestBookingRepository = mockk(relaxed = true)
    private val venueRepository: VenueRepository = mockk()
    private val venueBookingHoursRepository: VenueBookingHoursRepository = mockk(relaxed = true)
    private val venueMenuSectionImagesRepository: VenueMenuSectionImagesRepository = mockk(relaxed = true)
    private val venueAccessRepository: VenueAccessRepository = mockk()
    private val subscriptionRepository: SubscriptionRepository = mockk()
    private val guestMenuRepository: GuestMenuRepository = mockk()
    private val tableSessionRepository: TableSessionRepository = mockk(relaxed = true)
    private val guestTabsRepository: GuestTabsRepository = mockk(relaxed = true)
    private val router = createRouter()

    private fun createRouter(botUsername: String? = "TestBot"): TelegramBotRouter =
        TelegramBotRouter(
            config =
                TelegramBotConfig(
                    enabled = true,
                    token = "test",
                    mode = TelegramBotConfig.Mode.LONG_POLLING,
                    webhookPath = "/",
                    webhookSecretToken = null,
                    webAppPublicUrl = null,
                    botUsername = botUsername,
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

    @BeforeEach
    fun setup() {
        coEvery { idempotencyRepository.tryAcquire(any(), any(), any()) } returns true
    }

    @Test
    fun `link command requires group chat`() =
        runBlocking {
            val update =
                TelegramUpdate(
                    updateId = 1,
                    message =
                        Message(
                            messageId = 10,
                            chat = Chat(id = 200, type = "private"),
                            fromUser = User(id = 300),
                            text = "/link ABC",
                        ),
                )

            router.process(update)

            coVerify {
                outboxEnqueuer.enqueueSendMessage(200, "Эту команду нужно отправить в групповом чате персонала.", any())
            }
        }

    @Test
    fun `link command without code returns usage`() =
        runBlocking {
            val update =
                TelegramUpdate(
                    updateId = 2,
                    message =
                        Message(
                            messageId = 11,
                            chat = Chat(id = -500, type = "group"),
                            fromUser = User(id = 300),
                            text = "/link",
                        ),
                )

            router.process(update)

            coVerify {
                outboxEnqueuer.enqueueSendMessage(
                    -500,
                    "Использование: /link <код>. Код генерируется в режиме заведения.",
                    any(),
                )
            }
        }

    @Test
    fun `link command with bot mention is parsed`() =
        runBlocking {
            coEvery {
                staffChatLinkCodeRepository.linkAndBindWithCode(
                    "GHJK234",
                    any(),
                    any(),
                    any(),
                    any(),
                    any(),
                )
            } returns LinkAndBindResult.InvalidOrExpired

            val update =
                TelegramUpdate(
                    updateId = 3,
                    message =
                        Message(
                            messageId = 12,
                            chat = Chat(id = -700, type = "supergroup"),
                            fromUser = User(id = 300),
                            text = "/link@TestBot ghjk234",
                        ),
                )

            router.process(update)

            coVerify {
                outboxEnqueuer.enqueueSendMessage(
                    -700,
                    "Код недействителен или истёк. Сгенерируйте новый в режиме заведения.",
                    any(),
                )
            }
            coVerify { staffChatLinkCodeRepository.linkAndBindWithCode("GHJK234", any(), any(), any(), any(), any()) }
        }

    @Test
    fun `link command is idempotent for same venue`() =
        runBlocking {
            coEvery {
                staffChatLinkCodeRepository.linkAndBindWithCode(any(), any(), any(), any(), any(), any())
            } returns LinkAndBindResult.AlreadyBoundSameChat(42L, "Venue")

            val update =
                TelegramUpdate(
                    updateId = 4,
                    message =
                        Message(
                            messageId = 13,
                            chat = Chat(id = -700, type = "supergroup"),
                            fromUser = User(id = 300),
                            text = "/link SAMEVENUE",
                        ),
                )

            router.process(update)

            coVerify { outboxEnqueuer.enqueueSendMessage(-700, "Этот чат уже привязан к заведению Venue.", any()) }
        }

    @Test
    fun `link command uppercases code`() =
        runBlocking {
            coEvery {
                staffChatLinkCodeRepository.linkAndBindWithCode("GHJK234", any(), any(), any(), any(), any())
            } returns LinkAndBindResult.Success(venueId = 1L, venueName = "Venue")

            val update =
                TelegramUpdate(
                    updateId = 5,
                    message =
                        Message(
                            messageId = 14,
                            chat = Chat(id = -700, type = "supergroup"),
                            fromUser = User(id = 300),
                            text = "/link ghjk234",
                        ),
                )

            router.process(update)

            coVerify {
                outboxEnqueuer.enqueueSendMessage(
                    -700,
                    "✅ Чат привязан к заведению Venue. Уведомления о заказах будут приходить сюда.",
                    any(),
                )
            }
        }

    @Test
    fun `link command authorizes manager role`() =
        runBlocking {
            val connection: Connection = mockk()
            every { venueAccessRepository.hasVenueAdminOrOwner(connection, 300L, 10L) } returns true
            coEvery {
                staffChatLinkCodeRepository.linkAndBindWithCode(
                    "GHJK234",
                    300L,
                    -700L,
                    18L,
                    any(),
                    any(),
                )
            } coAnswers {
                @Suppress("UNCHECKED_CAST")
                val authorize = invocation.args[4] as suspend (Connection, Long) -> Boolean
                if (authorize(connection, 10L)) {
                    LinkAndBindResult.Success(venueId = 10L, venueName = "Venue")
                } else {
                    LinkAndBindResult.Unauthorized(venueId = 10L)
                }
            }

            val update =
                TelegramUpdate(
                    updateId = 9,
                    message =
                        Message(
                            messageId = 18,
                            chat = Chat(id = -700, type = "supergroup"),
                            fromUser = User(id = 300),
                            text = "/link ghjk234",
                        ),
                )

            router.process(update)

            coVerify {
                outboxEnqueuer.enqueueSendMessage(
                    -700,
                    "✅ Чат привязан к заведению Venue. Уведомления о заказах будут приходить сюда.",
                    any(),
                )
            }
        }

    @Test
    fun `link command rejects overly long code`() =
        runBlocking {
            val longCode = "A".repeat(80)
            val update =
                TelegramUpdate(
                    updateId = 6,
                    message =
                        Message(
                            messageId = 15,
                            chat = Chat(id = -700, type = "supergroup"),
                            fromUser = User(id = 300),
                            text = "/link $longCode",
                        ),
                )

            router.process(update)

            coVerify {
                outboxEnqueuer.enqueueSendMessage(
                    -700,
                    "Код недействителен или истёк. Сгенерируйте новый в режиме заведения.",
                    any(),
                )
            }
            coVerify(
                exactly = 0,
            ) { staffChatLinkCodeRepository.linkAndBindWithCode(any(), any(), any(), any(), any(), any()) }
        }

    @Test
    fun `link command rejects code with invalid characters`() =
        runBlocking {
            val update =
                TelegramUpdate(
                    updateId = 7,
                    message =
                        Message(
                            messageId = 16,
                            chat = Chat(id = -701, type = "group"),
                            fromUser = User(id = 301),
                            text = "/link ABCO1",
                        ),
                )

            router.process(update)

            coVerify {
                outboxEnqueuer.enqueueSendMessage(
                    -701,
                    "Код недействителен или истёк. Сгенерируйте новый в режиме заведения.",
                    any(),
                )
            }
            coVerify(
                exactly = 0,
            ) { staffChatLinkCodeRepository.linkAndBindWithCode(any(), any(), any(), any(), any(), any()) }
        }

    @Test
    fun `link command rejects unicode code`() =
        runBlocking {
            val update =
                TelegramUpdate(
                    updateId = 8,
                    message =
                        Message(
                            messageId = 17,
                            chat = Chat(id = -702, type = "group"),
                            fromUser = User(id = 302),
                            text = "/link ßßß",
                        ),
                )

            router.process(update)

            coVerify {
                outboxEnqueuer.enqueueSendMessage(
                    -702,
                    "Код недействителен или истёк. Сгенерируйте новый в режиме заведения.",
                    any(),
                )
            }
            coVerify(
                exactly = 0,
            ) { staffChatLinkCodeRepository.linkAndBindWithCode(any(), any(), any(), any(), any(), any()) }
        }

    @Test
    fun `ordinary group text does not render private role menu`() =
        runBlocking {
            val update =
                TelegramUpdate(
                    updateId = 80,
                    message =
                        Message(
                            messageId = 180,
                            chat = Chat(id = -708, type = "supergroup"),
                            fromUser = User(id = 308),
                            text = "что делать дальше",
                        ),
                )

            router.process(update)

            coVerify {
                outboxEnqueuer.enqueueSendMessage(
                    -708,
                    "Команды управления доступны в личном чате с ботом.",
                    match { it is ReplyKeyboardRemove },
                )
            }
            coVerify(exactly = 0) {
                outboxEnqueuer.enqueueSendMessage(-708, "Используйте меню ниже.", any())
            }
        }

    @Test
    fun `owner can open staff chat section from bot menu`() =
        runBlocking {
            coEvery { venueAccessRepository.listVenueMemberships(300L) } returns
                listOf(VenueAccessRepository.VenueMembership(venueId = 10L, role = "OWNER"))
            coEvery { venueAccessRepository.findVenueMembership(300L, 10L) } returns
                VenueAccessRepository.VenueMembership(venueId = 10L, role = "OWNER")
            coEvery { venueRepository.findVenueById(10L) } returns VenueShort(10L, "Venue", staffChatId = null)
            coEvery { venueRepository.findStaffChatStatus(10L) } returns
                StaffChatStatus(
                    venueId = 10L,
                    staffChatId = null,
                    linkedAt = null,
                    linkedByUserId = null,
                    unlinkedAt = null,
                    unlinkedByUserId = null,
                )

            router.process(
                TelegramUpdate(
                    updateId = 20,
                    message =
                        Message(
                            messageId = 30,
                            chat = Chat(id = 200, type = "private"),
                            fromUser = User(id = 300),
                            text = "💬 Чат персонала",
                        ),
                ),
            )

            coVerify {
                outboxEnqueuer.enqueueSendMessage(
                    200,
                    match {
                        it.contains("💬 Чат персонала") &&
                            it.contains("Статус: ❌ чат не подключён") &&
                            it.contains("Сотрудники могут пропускать заказы")
                    },
                    match {
                        it is InlineKeyboardMarkup &&
                            it.inlineKeyboard.flatten().any { button ->
                                button.text == "🔗 Сгенерировать код подключения"
                            }
                    },
                )
            }
        }

    @Test
    fun `manager can open staff chat section from bot menu`() =
        runBlocking {
            coEvery { venueAccessRepository.listVenueMemberships(300L) } returns
                listOf(VenueAccessRepository.VenueMembership(venueId = 10L, role = "MANAGER"))
            coEvery { venueAccessRepository.findVenueMembership(300L, 10L) } returns
                VenueAccessRepository.VenueMembership(venueId = 10L, role = "MANAGER")
            coEvery { venueRepository.findVenueById(10L) } returns VenueShort(10L, "Venue", staffChatId = null)
            coEvery { venueRepository.findStaffChatStatus(10L) } returns
                StaffChatStatus(
                    venueId = 10L,
                    staffChatId = null,
                    linkedAt = null,
                    linkedByUserId = null,
                    unlinkedAt = null,
                    unlinkedByUserId = null,
                )

            router.process(
                TelegramUpdate(
                    updateId = 21,
                    message =
                        Message(
                            messageId = 31,
                            chat = Chat(id = 200, type = "private"),
                            fromUser = User(id = 300),
                            text = "💬 Чат персонала",
                        ),
                ),
            )

            coVerify {
                outboxEnqueuer.enqueueSendMessage(
                    200,
                    match { it.contains("💬 Чат персонала") && it.contains("Статус: ❌ чат не подключён") },
                    match {
                        it is InlineKeyboardMarkup &&
                            it.inlineKeyboard.flatten().any { button ->
                                button.text == "🔗 Сгенерировать код подключения"
                            }
                    },
                )
            }
        }

    @Test
    fun `staff cannot open staff chat section from bot menu`() =
        runBlocking {
            coEvery { venueAccessRepository.listVenueMemberships(300L) } returns
                listOf(VenueAccessRepository.VenueMembership(venueId = 10L, role = "STAFF"))

            router.process(
                TelegramUpdate(
                    updateId = 22,
                    message =
                        Message(
                            messageId = 32,
                            chat = Chat(id = 200, type = "private"),
                            fromUser = User(id = 300),
                            text = "💬 Чат персонала",
                        ),
                ),
            )

            coVerify {
                outboxEnqueuer.enqueueSendMessage(
                    200,
                    "Раздел «Чат персонала» доступен владельцу или менеджеру.",
                    any(),
                )
            }
        }

    @Test
    fun `owner can generate staff chat link code from bot flow`() =
        runBlocking {
            coEvery { venueAccessRepository.findVenueMembership(300L, 10L) } returns
                VenueAccessRepository.VenueMembership(venueId = 10L, role = "OWNER")
            coEvery { venueRepository.findVenueById(10L) } returns VenueShort(10L, "Venue", staffChatId = null)
            coEvery { staffChatLinkCodeRepository.createLinkCode(10L, 300L) } returns
                LinkCodeResult(
                    code = "ABCDEFGHJK",
                    expiresAt = Instant.parse("2026-04-29T12:00:00Z"),
                    ttlSeconds = 900,
                )

            router.process(
                TelegramUpdate(
                    updateId = 23,
                    callbackQuery =
                        CallbackQuery(
                            id = "cb-generate",
                            from = User(id = 300),
                            message = Message(messageId = 33, chat = Chat(id = 200, type = "private")),
                            data = "venue_staff_chat_generate:10",
                        ),
                ),
            )

            coVerify { staffChatLinkCodeRepository.createLinkCode(10L, 300L) }
            coVerify {
                outboxEnqueuer.enqueueSendMessage(
                    200,
                    match {
                        it.contains("🔗 Код подключения чата персонала") &&
                            it.contains("Код подключения:") &&
                            it.contains("<code>ABCDEFGHJK</code>") &&
                            it.contains("Команда для группы — скопируйте целиком:") &&
                            it.contains("<code>/link@TestBot ABCDEFGHJK</code>") &&
                            it.contains("Команда проверки:") &&
                            it.contains("<code>/link_test@TestBot</code>")
                    },
                    any(),
                    "HTML",
                )
            }
            coVerify { outboxEnqueuer.enqueueSendMessage(200, "/link@TestBot ABCDEFGHJK", null, null) }
        }

    @Test
    fun `manager can generate staff chat link code from bot flow`() =
        runBlocking {
            coEvery { venueAccessRepository.findVenueMembership(300L, 10L) } returns
                VenueAccessRepository.VenueMembership(venueId = 10L, role = "MANAGER")
            coEvery { venueRepository.findVenueById(10L) } returns VenueShort(10L, "Venue", staffChatId = null)
            coEvery { staffChatLinkCodeRepository.createLinkCode(10L, 300L) } returns
                LinkCodeResult(
                    code = "ABCDEFGHJK",
                    expiresAt = Instant.parse("2026-04-29T12:00:00Z"),
                    ttlSeconds = 900,
                )

            router.process(
                TelegramUpdate(
                    updateId = 24,
                    callbackQuery =
                        CallbackQuery(
                            id = "cb-manager-generate",
                            from = User(id = 300),
                            message = Message(messageId = 34, chat = Chat(id = 200, type = "private")),
                            data = "venue_staff_chat_generate:10",
                        ),
                ),
            )

            coVerify { staffChatLinkCodeRepository.createLinkCode(10L, 300L) }
            coVerify {
                outboxEnqueuer.enqueueSendMessage(
                    200,
                    match {
                        it.contains("🔗 Код подключения чата персонала") &&
                            it.contains("<code>/link@TestBot ABCDEFGHJK</code>") &&
                            it.contains("<code>/link_test@TestBot</code>")
                    },
                    any(),
                    "HTML",
                )
            }
            coVerify { outboxEnqueuer.enqueueSendMessage(200, "/link@TestBot ABCDEFGHJK", null, null) }
        }

    @Test
    fun `staff chat link instruction falls back to plain commands without bot username`() =
        runBlocking {
            val routerWithoutUsername = createRouter(botUsername = null)
            coEvery { venueAccessRepository.findVenueMembership(300L, 10L) } returns
                VenueAccessRepository.VenueMembership(venueId = 10L, role = "OWNER")
            coEvery { venueRepository.findVenueById(10L) } returns VenueShort(10L, "Venue", staffChatId = null)
            coEvery { staffChatLinkCodeRepository.createLinkCode(10L, 300L) } returns
                LinkCodeResult(
                    code = "ABCDEFGHJK",
                    expiresAt = Instant.parse("2026-04-29T12:00:00Z"),
                    ttlSeconds = 900,
                )

            routerWithoutUsername.process(
                TelegramUpdate(
                    updateId = 240,
                    callbackQuery =
                        CallbackQuery(
                            id = "cb-generate-no-username",
                            from = User(id = 300),
                            message = Message(messageId = 340, chat = Chat(id = 200, type = "private")),
                            data = "venue_staff_chat_generate:10",
                        ),
                ),
            )

            coVerify {
                outboxEnqueuer.enqueueSendMessage(
                    200,
                    match {
                        it.contains("Команда для группы — скопируйте целиком:") &&
                            it.contains("<code>/link ABCDEFGHJK</code>") &&
                            it.contains("Команда проверки:") &&
                            it.contains("<code>/link_test</code>") &&
                            !it.contains("/link@TestBot")
                    },
                    any(),
                    "HTML",
                )
            }
            coVerify { outboxEnqueuer.enqueueSendMessage(200, "/link ABCDEFGHJK", null, null) }
        }

    @Test
    fun `legacy admin can generate staff chat link code from bot flow`() =
        runBlocking {
            coEvery { venueAccessRepository.findVenueMembership(300L, 10L) } returns
                VenueAccessRepository.VenueMembership(venueId = 10L, role = "ADMIN")
            coEvery { venueRepository.findVenueById(10L) } returns VenueShort(10L, "Venue", staffChatId = null)
            coEvery { staffChatLinkCodeRepository.createLinkCode(10L, 300L) } returns
                LinkCodeResult(
                    code = "ABCDEFGHJK",
                    expiresAt = Instant.parse("2026-04-29T12:00:00Z"),
                    ttlSeconds = 900,
                )

            router.process(
                TelegramUpdate(
                    updateId = 25,
                    callbackQuery =
                        CallbackQuery(
                            id = "cb-admin-generate",
                            from = User(id = 300),
                            message = Message(messageId = 35, chat = Chat(id = 200, type = "private")),
                            data = "venue_staff_chat_generate:10",
                        ),
                ),
            )

            coVerify { staffChatLinkCodeRepository.createLinkCode(10L, 300L) }
        }

    @Test
    fun `staff cannot generate staff chat link code from direct callback`() =
        runBlocking {
            coEvery { venueAccessRepository.findVenueMembership(300L, 10L) } returns
                VenueAccessRepository.VenueMembership(venueId = 10L, role = "STAFF")

            router.process(
                TelegramUpdate(
                    updateId = 26,
                    callbackQuery =
                        CallbackQuery(
                            id = "cb-staff-generate",
                            from = User(id = 300),
                            message = Message(messageId = 36, chat = Chat(id = 200, type = "private")),
                            data = "venue_staff_chat_generate:10",
                        ),
                ),
            )

            coVerify {
                outboxEnqueuer.enqueueSendMessage(200, "Раздел «Чат персонала» доступен владельцу или менеджеру.", any())
            }
            coVerify(exactly = 0) { staffChatLinkCodeRepository.createLinkCode(any(), any()) }
        }

    @Test
    fun `manager staff chat status shows linked chat title and metadata`() =
        runBlocking {
            coEvery { venueAccessRepository.findVenueMembership(300L, 10L) } returns
                VenueAccessRepository.VenueMembership(venueId = 10L, role = "MANAGER")
            coEvery { venueRepository.findVenueById(10L) } returns VenueShort(10L, "Venue", staffChatId = -100L)
            coEvery { apiClient.getChat(-100L) } returns Chat(id = -100L, type = "supergroup", title = "Venue Staff")
            coEvery { venueRepository.findStaffChatStatus(10L) } returns
                StaffChatStatus(
                    venueId = 10L,
                    staffChatId = -100L,
                    linkedAt = Instant.parse("2026-04-29T10:00:00Z"),
                    linkedByUserId = 300L,
                    unlinkedAt = null,
                    unlinkedByUserId = null,
                )

            router.process(
                TelegramUpdate(
                    updateId = 23,
                    callbackQuery =
                        CallbackQuery(
                            id = "cb-status",
                            from = User(id = 300),
                            message = Message(messageId = 33, chat = Chat(id = 200, type = "private")),
                            data = "venue_staff_chat_status:10",
                        ),
                ),
            )

            coVerify {
                outboxEnqueuer.enqueueSendMessage(
                    200,
                    match {
                        it.contains("Статус: ✅ чат подключён") &&
                            it.contains("Чат: Venue Staff") &&
                            it.contains("Chat ID: -100") &&
                            it.contains("Подключён: 2026-04-29T10:00:00Z") &&
                            it.contains("Кем: Telegram user id 300")
                    },
                    match {
                        it is InlineKeyboardMarkup &&
                            it.inlineKeyboard.flatten().any { button -> button.text == "🧪 Отправить тест" }
                    },
                )
            }
        }

    @Test
    fun `owner can send staff chat test notification from bot flow`() =
        runBlocking {
            coEvery { venueAccessRepository.findVenueMembership(300L, 10L) } returns
                VenueAccessRepository.VenueMembership(venueId = 10L, role = "OWNER")
            coEvery { venueRepository.findVenueById(10L) } returns VenueShort(10L, "Venue", staffChatId = -100L)
            coEvery { venueRepository.findStaffChatStatus(10L) } returns
                StaffChatStatus(
                    venueId = 10L,
                    staffChatId = -100L,
                    linkedAt = Instant.parse("2026-04-29T10:00:00Z"),
                    linkedByUserId = 300L,
                    unlinkedAt = null,
                    unlinkedByUserId = null,
                )

            router.process(
                TelegramUpdate(
                    updateId = 24,
                    callbackQuery =
                        CallbackQuery(
                            id = "cb-test",
                            from = User(id = 300),
                            message = Message(messageId = 34, chat = Chat(id = 200, type = "private")),
                            data = "venue_staff_chat_test:10",
                        ),
                ),
            )

            coVerify {
                outboxEnqueuer.enqueueSendMessage(
                    -100L,
                    match { it.contains("✅ Тестовое уведомление. Чат привязан к Venue.") },
                    any(),
                )
            }
            coVerify { outboxEnqueuer.enqueueSendMessage(200, "✅ Тест отправлен в чат персонала.", any()) }
        }
}
