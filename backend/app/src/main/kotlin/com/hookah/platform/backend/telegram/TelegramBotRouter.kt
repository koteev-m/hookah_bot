package com.hookah.platform.backend.telegram

import com.hookah.platform.backend.api.DatabaseUnavailableException
import com.hookah.platform.backend.miniapp.guest.db.BookingStatus
import com.hookah.platform.backend.miniapp.guest.db.CreateInviteResult
import com.hookah.platform.backend.miniapp.guest.db.GuestBookingRepository
import com.hookah.platform.backend.miniapp.guest.db.GuestMenuRepository
import com.hookah.platform.backend.miniapp.guest.db.GuestTabsRepository
import com.hookah.platform.backend.miniapp.guest.db.TableSessionRepository
import com.hookah.platform.backend.miniapp.venue.VenueRole
import com.hookah.platform.backend.miniapp.venue.menu.VenueMenuCategory
import com.hookah.platform.backend.miniapp.venue.menu.VenueMenuRepository
import com.hookah.platform.backend.miniapp.venue.orders.OrderActionActor
import com.hookah.platform.backend.miniapp.venue.orders.OrderBatchDetail
import com.hookah.platform.backend.miniapp.venue.orders.OrderBatchItemDetail
import com.hookah.platform.backend.miniapp.venue.orders.OrderBatchStatus
import com.hookah.platform.backend.miniapp.venue.orders.OrderDetail
import com.hookah.platform.backend.miniapp.venue.orders.OrderQueueItem
import com.hookah.platform.backend.miniapp.venue.orders.OrderWorkflowStatus
import com.hookah.platform.backend.miniapp.venue.orders.VenueOrdersRepository
import com.hookah.platform.backend.miniapp.venue.orders.allowedNextStatuses
import com.hookah.platform.backend.miniapp.venue.staff.StaffInviteAcceptResult
import com.hookah.platform.backend.miniapp.venue.staff.StaffInviteConfig
import com.hookah.platform.backend.miniapp.venue.staff.StaffInviteDeclineResult
import com.hookah.platform.backend.miniapp.venue.staff.StaffInvitePreviewResult
import com.hookah.platform.backend.miniapp.venue.staff.StaffInviteRepository
import com.hookah.platform.backend.miniapp.venue.staff.VenueStaffRemoveResult
import com.hookah.platform.backend.miniapp.venue.staff.VenueStaffRepository
import com.hookah.platform.backend.miniapp.venue.tables.TableNumberConflictException
import com.hookah.platform.backend.miniapp.venue.tables.VenueTableOwnerSummary
import com.hookah.platform.backend.miniapp.venue.tables.VenueTableRepository
import com.google.zxing.BarcodeFormat
import com.google.zxing.client.j2se.MatrixToImageWriter
import com.google.zxing.qrcode.QRCodeWriter
import com.hookah.platform.backend.miniapp.subscription.db.SubscriptionRepository
import com.hookah.platform.backend.miniapp.venue.VenueStatus
import com.hookah.platform.backend.platform.PlatformOwnerAssignmentResult
import com.hookah.platform.backend.platform.PlatformVenueMemberRepository
import com.hookah.platform.backend.platform.PlatformVenueRepository
import com.hookah.platform.backend.platform.VenueStatusAction
import com.hookah.platform.backend.platform.VenueStatusChangeResult
import com.hookah.platform.backend.telegram.db.ChatContextRepository
import com.hookah.platform.backend.telegram.db.CatalogVenueShort
import com.hookah.platform.backend.telegram.db.DialogStateRepository
import com.hookah.platform.backend.telegram.db.IdempotencyRepository
import com.hookah.platform.backend.telegram.db.ActiveOrderDetails
import com.hookah.platform.backend.telegram.db.OrderBatchItemDetails
import com.hookah.platform.backend.telegram.db.OrderBatchItemInput
import com.hookah.platform.backend.telegram.db.LinkAndBindResult
import com.hookah.platform.backend.telegram.db.OrdersRepository
import com.hookah.platform.backend.telegram.db.StaffCallRepository
import com.hookah.platform.backend.telegram.db.StaffCallQueueItem
import com.hookah.platform.backend.telegram.db.StaffChatLinkCodeFormat
import com.hookah.platform.backend.telegram.db.StaffChatLinkCodeRepository
import com.hookah.platform.backend.telegram.db.TableTokenRepository
import com.hookah.platform.backend.telegram.db.UnlinkResult
import com.hookah.platform.backend.telegram.db.UserActiveOrderItemSummary
import com.hookah.platform.backend.telegram.db.UserRepository
import com.hookah.platform.backend.telegram.db.VenueAccessRepository
import com.hookah.platform.backend.telegram.db.VenueBookingHoursRepository
import com.hookah.platform.backend.telegram.db.VenueConnectionRequestRecord
import com.hookah.platform.backend.telegram.db.VenueConnectionRequestRepository
import com.hookah.platform.backend.telegram.db.VenueInfoSection
import com.hookah.platform.backend.telegram.db.VenueInfoSectionMediaRepository
import com.hookah.platform.backend.telegram.db.VenueInfoSectionsRepository
import com.hookah.platform.backend.telegram.db.VenueMenuSectionImagesRepository
import com.hookah.platform.backend.telegram.db.VenueNotificationSetting
import com.hookah.platform.backend.telegram.db.VenueRepository
import com.hookah.platform.backend.telegram.db.VenueSettings
import com.hookah.platform.backend.telegram.db.VenueSettingsRepository
import com.hookah.platform.backend.telegram.db.VenueStatsReport
import com.hookah.platform.backend.telegram.db.VenueStatsRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.slf4j.LoggerFactory
import java.time.Duration
import java.time.Instant
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import java.io.ByteArrayOutputStream

class TelegramBotRouter(
    private val config: TelegramBotConfig,
    private val apiClient: TelegramApiClient,
    private val outboxEnqueuer: TelegramOutboxEnqueuer,
    private val idempotencyRepository: IdempotencyRepository,
    private val userRepository: UserRepository,
    private val tableTokenRepository: TableTokenRepository,
    private val chatContextRepository: ChatContextRepository,
    private val dialogStateRepository: DialogStateRepository,
    private val ordersRepository: OrdersRepository,
    private val staffCallRepository: StaffCallRepository,
    private val staffChatLinkCodeRepository: StaffChatLinkCodeRepository,
    private val guestBookingRepository: GuestBookingRepository,
    private val venueRepository: VenueRepository,
    private val venueBookingHoursRepository: VenueBookingHoursRepository,
    private val venueMenuSectionImagesRepository: VenueMenuSectionImagesRepository,
    private val venueMenuRepository: VenueMenuRepository = VenueMenuRepository(null),
    private val venueTableRepository: VenueTableRepository = VenueTableRepository(null),
    private val venueAccessRepository: VenueAccessRepository,
    private val venueStaffRepository: VenueStaffRepository = VenueStaffRepository(null),
    private val staffInviteRepository: StaffInviteRepository = StaffInviteRepository(null, "dev-invite-pepper"),
    private val staffInviteConfig: StaffInviteConfig =
        StaffInviteConfig(
            defaultTtlSeconds = 7 * 24 * 3600L,
            maxTtlSeconds = 30 * 24 * 3600L,
            secretPepper = "dev-invite-pepper",
        ),
    private val subscriptionRepository: SubscriptionRepository,
    private val guestMenuRepository: GuestMenuRepository,
    private val tableSessionRepository: TableSessionRepository,
    private val guestTabsRepository: GuestTabsRepository,
    private val platformVenueRepository: PlatformVenueRepository = PlatformVenueRepository(null),
    private val platformVenueMemberRepository: PlatformVenueMemberRepository = PlatformVenueMemberRepository(null),
    private val tableSessionTtl: Duration,
    private val json: Json,
    private val venueConnectionRequestRepository: VenueConnectionRequestRepository = VenueConnectionRequestRepository(null),
    private val scope: CoroutineScope,
    private val venueInfoSectionsRepository: VenueInfoSectionsRepository = VenueInfoSectionsRepository(null),
    private val venueInfoSectionMediaRepository: VenueInfoSectionMediaRepository = VenueInfoSectionMediaRepository(null),
    private val venueOrdersRepository: VenueOrdersRepository = VenueOrdersRepository(null),
    private val venueStatsRepository: VenueStatsRepository = VenueStatsRepository(null),
    private val venueSettingsRepository: VenueSettingsRepository = VenueSettingsRepository(null),
) {
    private val logger = LoggerFactory.getLogger(TelegramBotRouter::class.java)
    private val subscriptionBlockedMessage = "Подписка заведения заблокирована. Заказы недоступны."
    private val keyboardRemoveMarker = "\u2060"
    private val botMenuItemsPerPage = 5
    private val botDraftCarts = ConcurrentHashMap<BotDraftCartKey, ConcurrentHashMap<Long, BotDraftCartItem>>()
    private val botDraftCartComments = ConcurrentHashMap<BotDraftCartKey, String>()
    private val botMenuMessageIds = ConcurrentHashMap<BotDraftCartKey, Long>()
    private val botCartMessageIds = ConcurrentHashMap<BotDraftCartKey, Long>()
    private val botCartScreenMessageIds = ConcurrentHashMap<BotDraftCartKey, List<Long>>()
    private val botSelectedTabIds = ConcurrentHashMap<BotDraftCartKey, Long>()
    private val botDraftCartSessionIds = ConcurrentHashMap<BotDraftCartKey, Long>()
    private val botJoinSharedAwaitingChats = ConcurrentHashMap.newKeySet<Long>()
    private val botBookingCommentDrafts = ConcurrentHashMap<Long, BotBookingDraft>()
    private val botBookingPendingConfirmations = ConcurrentHashMap<Long, BotBookingDraft>()
    private val botBookingEditContexts = ConcurrentHashMap<Long, BotBookingEditContext>()
    private val botBookingScreenMessageIds = ConcurrentHashMap<BotBookingScreenKey, Long>()
    private val botVenuePreviewContexts = ConcurrentHashMap<Long, BotVenuePreviewContext>()
    private val botDraftCartOrderSeq = AtomicLong(0L)

    private data class VenueStatsPeriod(
        val title: String,
        val periodStart: Instant,
    )

    private val inviteCodeRandom = SecureRandom()
    private val inviteCodeLength = 4
    private val inviteCodeRangeExclusive = 10_000
    private val inviteCodeMaxAttempts = 32
    private val inviteCodeTtl = Duration.ofMinutes(15)
    private val staffInviteStartPrefix = "staff_invite_"
    private val bookingDateFormatter = DateTimeFormatter.ofPattern("dd.MM")
    private val bookingDateConfirmFormatter = DateTimeFormatter.ofPattern("dd.MM.yyyy")
    private val bookingDateTimeFormatter = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm")
    private val bookingTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")
    private val bookingDateFirstPageSize = 3
    private val bookingDateMorePageSize = 6
    private val bookingDateMaxDaysAhead = 8
    private val hookahSectionName = "кальянное меню"
    private val defaultHookahFlavorProfiles =
        listOf(
            "Ягодный",
            "Фруктовый",
            "Цитрусовый",
            "Десертный",
            "Освежающий / мятный",
            "Напиточный",
            "Пряный",
            "Цветочный",
        )

    private data class BotDraftCartKey(
        val chatId: Long,
        val tableToken: String,
    )

    private data class BotDraftCartItem(
        val lineId: Long,
        val itemId: Long,
        val name: String,
        val optionId: Long?,
        val optionName: String?,
        val priceMinor: Long,
        val currency: String,
        val qty: Int,
        val orderSeq: Long,
    )

    private data class BotBookingDraft(
        val userId: Long,
        val venueId: Long,
        val date: LocalDate,
        val time: String,
        val guestsLabel: String,
        val comment: String? = null,
    )

    private data class BotBookingEditContext(
        val bookingId: Long,
        val venueId: Long,
        val userId: Long,
    )

    private data class BotBookingScreenKey(
        val chatId: Long,
        val venueId: Long,
    )

    private data class BotVenuePreviewContext(
        val venueId: Long,
        val ownerUserId: Long,
        val showDraftNotice: Boolean,
    )

    private data class OwnerVenueCard(
        val venueId: Long,
        val name: String,
        val city: String?,
        val address: String?,
        val status: String?,
        val description: String?,
    )

    private data class OwnerVenuePublishReadiness(
        val status: VenueStatus?,
        val nameReady: Boolean,
        val addressReady: Boolean,
        val hoursReady: Boolean,
        val descriptionReady: Boolean,
        val menuReady: Boolean,
    ) {
        val canPublish: Boolean
            get() = nameReady && addressReady && hoursReady && descriptionReady && menuReady
    }

    private data class OwnerVenueStopListEntry(
        val sectionId: Long,
        val sectionName: String,
        val itemId: Long,
        val itemName: String,
        val itemPriceMinor: Long,
        val currency: String,
        val optionId: Long? = null,
        val optionName: String? = null,
    )

    private enum class VenueBotRole {
        OWNER,
        MANAGER,
        STAFF,
    }

    private enum class VenueStaffNotificationKind {
        ORDERS,
        STAFF_CALLS,
        CANCELLATIONS,
    }

    private data class VenueBotAccess(
        val venueId: Long,
        val role: VenueBotRole,
    )

    suspend fun process(update: TelegramUpdate) {
        val chatId = update.message?.chat?.id ?: update.callbackQuery?.message?.chat?.id
        val messageId = update.message?.messageId ?: update.callbackQuery?.message?.messageId
        val acquired =
            try {
                idempotencyRepository.tryAcquire(update.updateId, chatId, messageId)
            } catch (e: CancellationException) {
                throw e
            } catch (e: DatabaseUnavailableException) {
                if (chatId != null) {
                    enqueueMessage(chatId, "База недоступна, попробуйте позже.")
                }
                return
            }
        if (!acquired) return

        when {
            update.message != null -> handleMessage(update.message)
            update.callbackQuery != null -> handleCallback(update.callbackQuery)
            else -> logger.debug("Ignored update without message or callback id={} ", update.updateId)
        }
    }

    private suspend fun handleMessage(message: Message) {
        val from = message.fromUser
        if (from != null) {
            safeUpsertUser(from)
        }
        val chatId = message.chat.id
        val text = message.text?.trim()
        val isPlatformOwnerUser = isPlatformOwner(from?.id)
        val state = dialogStateRepository.get(chatId)
        val command = parseCommand(text)
        if (command != null && isVenueConnectionDialogState(state.state)) {
            dialogStateRepository.clear(chatId)
        }
        if (command != null && isOwnerVenueTermsDialogState(state.state)) {
            dialogStateRepository.clear(chatId)
        }
        if (command != null && isOwnerVenueProfileDialogState(state.state)) {
            dialogStateRepository.clear(chatId)
        }
        if (command != null && state.state == DialogStateType.BOT_MENU_CART_WAIT_COMMENT) {
            dialogStateRepository.clear(chatId)
        }
        if (!text.isNullOrBlank() && isVenueConnectionDialogState(state.state) && isVenueConnectionInterruptAction(text)) {
            dialogStateRepository.clear(chatId)
        }
        if (!text.isNullOrBlank() && isOwnerVenueTermsDialogState(state.state) && isOwnerVenueTermsInterruptAction(text)) {
            dialogStateRepository.clear(chatId)
        }
        if (!text.isNullOrBlank() && isOwnerVenueProfileDialogState(state.state) && isOwnerVenueProfileInterruptAction(text)) {
            dialogStateRepository.clear(chatId)
        }
        if (!text.isNullOrBlank() && state.state == DialogStateType.BOT_MENU_CART_WAIT_COMMENT && isBotNavigationActionText(text)) {
            dialogStateRepository.clear(chatId)
        }
        if (command != null) {
            botBookingCommentDrafts.remove(chatId)
            botBookingPendingConfirmations.remove(chatId)
            botBookingEditContexts.remove(chatId)
        }
        if (!text.isNullOrBlank() && botBookingCommentDrafts.containsKey(chatId) && isBotNavigationActionText(text)) {
            botBookingCommentDrafts.remove(chatId)
            botBookingPendingConfirmations.remove(chatId)
            botBookingEditContexts.remove(chatId)
        }

        when {
            command?.name == "/start" -> handleStartCommand(chatId, from, text ?: "")
            command?.name == "/menu" -> showRoleAwareMainMenu(chatId, from)
            command?.name == "/my" -> showMyOrdersAndBookings(chatId, from)
            command?.name == "/help" -> showHelp(chatId)
            command?.name == "/link" -> handleLinkCommand(message, command.argument)
            command?.name == "/unlink" -> handleUnlinkCommand(message)
            command?.name == "/link_test" -> handleLinkTestCommand(message)
            isPlatformOwnerUser && text == "📨 Заявки на подключение" ->
                showOwnerVenueConnectionRequests(chatId)
            isPlatformOwnerUser && text == "🏢 Кальянные" ->
                showOwnerSectionPlaceholder(chatId, "Раздел «Кальянные»")
            isPlatformOwnerUser && text == "👤 Владельцы" ->
                showOwnerSectionPlaceholder(chatId, "Раздел «Владельцы»")
            isPlatformOwnerUser && text == "💳 Подписки" ->
                showOwnerSectionPlaceholder(chatId, "Раздел «Подписки»")
            isPlatformOwnerUser && text == "⚙️ Статусы" ->
                showOwnerSectionPlaceholder(chatId, "Раздел «Статусы»")
            text == "🍽 Каталог кальянных" ||
                text == "🍽️ Каталог кальянных" ||
                text == "🗺️ Каталог кальянных" ->
                showBotVenueCatalog(chatId)
            text == "📱 Открыть Mini App" -> showMiniAppEntry(chatId)
            text == "🎁 Акции" -> showPromotions(chatId)
            text == "🪑 Я за столом / У меня QR" -> showTableQrEntryHint(chatId)
            text == "📄 Мои заказы и брони" -> showMyOrdersAndBookings(chatId, from)
            text == "🤝 Добавить свою кальянную" -> showAddVenueEntry(chatId, from)
            text == "🏢 Моё заведение" -> showVenueOwnerVenueCard(chatId, from)
            text == "🍽 Меню заведения" -> showVenueOwnerOrderMenuRoot(chatId, from)
            text == "🏢 Заведение" -> showVenueManagerVenueCard(chatId, from)
            text == "🪑 Столы и QR" -> showVenueManagerTablesRoot(chatId, from)
            text == "👥 Персонал" -> showVenueOwnerStaffRoot(chatId, from)
            text == "📦 Заказы" -> showVenueStaffOrders(chatId, from)
            text == "📊 Статистика" -> showStatsEntry(chatId, from)
            text == "⚙️ Настройки" -> showVenueSettingsRoot(chatId, from)
            text == "🧾 Заказы" -> showVenueStaffOrders(chatId, from)
            text == "🛎 Вызовы" -> showVenueStaffCalls(chatId, from)
            text == "🚫 Стоп-лист" -> showVenueStaffStopListRoot(chatId, from)
            text == "📄 Брони" -> showVenueStaffBookings(chatId, from)
            text == "🍽 Меню" -> showVenueManagerOrderMenuRoot(chatId, from)
            text == "🍽️ Меню" || text == "🍽️ Открыть меню" -> showBotMenu(chatId)
            text == "🧺 Корзина" -> showBotMenuCart(chatId)
            text == "👥 Общий счёт" -> showBotSplitBillEntry(chatId)
            text == "📄 Мой заказ" || text == "📄 Заказ" || text == "🧾 Активный заказ" -> showActiveOrder(chatId)
            text == "✍️ Быстрый заказ" -> startQuickOrder(chatId)
            text == "🔔 Персонал" ||
                text == "🔔 Вызвать персонал" ||
                text == "🛎️ Вызов персонала" ||
                text == "🛎 Вызвать персонал" ->
                showStaffCallReasons(chatId)
            text == "🚪 Сменить стол" || text == "🔁 Сменить стол" -> clearContextAndAskRescan(chatId)
            text == "🏠 В каталог" -> showMainMenu(chatId, from)
            state.state == DialogStateType.VENUE_CONNECT_WAIT_NAME && !text.isNullOrBlank() ->
                proceedVenueConnectionName(chatId, text, state)
            state.state == DialogStateType.VENUE_CONNECT_WAIT_CITY && !text.isNullOrBlank() ->
                proceedVenueConnectionCity(chatId, from, text, state)
            state.state == DialogStateType.VENUE_CONNECT_WAIT_CONTACT && !text.isNullOrBlank() ->
                proceedVenueConnectionContact(chatId, text, state)
            state.state == DialogStateType.VENUE_CONNECT_WAIT_CONTACT_EXTRA && !text.isNullOrBlank() ->
                proceedVenueConnectionAdditionalContact(chatId, text, state)
            state.state == DialogStateType.VENUE_CONNECT_WAIT_COMMENT && !text.isNullOrBlank() ->
                proceedVenueConnectionComment(chatId, from, text, state)
            state.state == DialogStateType.OWNER_VENUE_TERMS_WAIT_TRIAL_CUSTOM_DATE && !text.isNullOrBlank() ->
                proceedOwnerVenueTermsCustomTrialDate(chatId, from, text, state)
            state.state == DialogStateType.OWNER_VENUE_TERMS_WAIT_CURRENT_PRICE && !text.isNullOrBlank() ->
                proceedOwnerVenueTermsCurrentPrice(chatId, from, text, state)
            state.state == DialogStateType.OWNER_VENUE_TERMS_WAIT_FUTURE_PRICE && !text.isNullOrBlank() ->
                proceedOwnerVenueTermsFuturePrice(chatId, from, text, state)
            state.state == DialogStateType.OWNER_VENUE_TERMS_WAIT_FUTURE_PRICE_DATE && !text.isNullOrBlank() ->
                proceedOwnerVenueTermsFuturePriceDate(chatId, from, text, state)
            state.state == DialogStateType.OWNER_VENUE_TERMS_WAIT_NOTE && !text.isNullOrBlank() ->
                proceedOwnerVenueTermsNote(chatId, from, text, state)
            state.state == DialogStateType.OWNER_VENUE_PROFILE_WAIT_NAME && !text.isNullOrBlank() ->
                proceedOwnerVenueName(chatId, from, text, state)
            state.state == DialogStateType.OWNER_VENUE_PROFILE_WAIT_ADDRESS && !text.isNullOrBlank() ->
                proceedOwnerVenueAddress(chatId, from, text, state)
            state.state == DialogStateType.OWNER_VENUE_PROFILE_WAIT_HOURS_OPEN && !text.isNullOrBlank() ->
                proceedOwnerVenueHoursOpen(chatId, from, text, state)
            state.state == DialogStateType.OWNER_VENUE_PROFILE_WAIT_HOURS_CLOSE && !text.isNullOrBlank() ->
                proceedOwnerVenueHoursClose(chatId, from, text, state)
            state.state == DialogStateType.OWNER_VENUE_DESCRIPTION_WAIT_SECTION_TITLE && !text.isNullOrBlank() ->
                proceedOwnerVenueDescriptionSectionTitle(chatId, from, text, state)
            state.state == DialogStateType.OWNER_VENUE_DESCRIPTION_WAIT_SECTION_TEXT && !text.isNullOrBlank() ->
                proceedOwnerVenueDescriptionSectionText(chatId, from, text, state)
            state.state == DialogStateType.OWNER_VENUE_DESCRIPTION_WAIT_SECTION_MEDIA ->
                proceedOwnerVenueDescriptionSectionMedia(chatId, from, message, state)
            state.state == DialogStateType.OWNER_VENUE_ORDER_MENU_WAIT_SECTION_TITLE && !text.isNullOrBlank() ->
                proceedOwnerVenueOrderMenuAddSection(chatId, from, text, state)
            state.state == DialogStateType.OWNER_VENUE_ORDER_MENU_WAIT_SECTION_RENAME && !text.isNullOrBlank() ->
                proceedOwnerVenueOrderMenuRenameSection(chatId, from, text, state)
            state.state == DialogStateType.OWNER_VENUE_ORDER_MENU_WAIT_ITEM_NAME && !text.isNullOrBlank() ->
                proceedOwnerVenueOrderMenuAddItemName(chatId, from, text, state)
            state.state == DialogStateType.OWNER_VENUE_ORDER_MENU_WAIT_ITEM_PRICE && !text.isNullOrBlank() ->
                proceedOwnerVenueOrderMenuAddItemPrice(chatId, from, text, state)
            state.state == DialogStateType.OWNER_VENUE_ORDER_MENU_WAIT_ITEM_RENAME && !text.isNullOrBlank() ->
                proceedOwnerVenueOrderMenuRenameItem(chatId, from, text, state)
            state.state == DialogStateType.OWNER_VENUE_ORDER_MENU_WAIT_ITEM_PRICE_EDIT && !text.isNullOrBlank() ->
                proceedOwnerVenueOrderMenuEditItemPrice(chatId, from, text, state)
            state.state == DialogStateType.OWNER_VENUE_ORDER_MENU_WAIT_OPTION_NAME && !text.isNullOrBlank() ->
                proceedOwnerVenueOrderMenuFlavorName(chatId, from, text, state)
            state.state == DialogStateType.OWNER_VENUE_TABLES_WAIT_NUMBER && !text.isNullOrBlank() ->
                proceedOwnerVenueAddTableNumber(chatId, from, text, state)
            state.state == DialogStateType.OWNER_VENUE_TABLES_WAIT_CAPACITY && !text.isNullOrBlank() ->
                proceedOwnerVenueAddTableCapacity(chatId, from, text, state)
            state.state == DialogStateType.VENUE_STAFF_ORDERS_WAIT_BATCH_CANCEL_REASON && !text.isNullOrBlank() ->
                proceedVenueStaffOrderCancelReason(chatId, from, text, state, cancelWholeOrder = false)
            state.state == DialogStateType.VENUE_STAFF_ORDERS_WAIT_ORDER_CANCEL_REASON && !text.isNullOrBlank() ->
                proceedVenueStaffOrderCancelReason(chatId, from, text, state, cancelWholeOrder = true)
            state.state == DialogStateType.VENUE_STAFF_ORDERS_WAIT_ITEM_EXCLUDE_REASON && !text.isNullOrBlank() ->
                proceedVenueStaffOrderBillItemExcludeReason(chatId, from, text, state)
            state.state == DialogStateType.VENUE_STAFF_ORDERS_WAIT_ITEM_DISCOUNT_PERCENT && !text.isNullOrBlank() ->
                proceedVenueStaffOrderBillItemDiscountPercent(chatId, from, text, state)
            state.state == DialogStateType.BOT_MENU_CART_WAIT_COMMENT && !text.isNullOrBlank() ->
                proceedBotMenuCartComment(chatId, text)
            state.state == DialogStateType.QUICK_ORDER_WAIT_TEXT && !text.isNullOrBlank() ->
                proceedQuickOrderText(chatId, text)
            (state.state == DialogStateType.BOT_JOIN_SHARED_WAIT_CODE || botJoinSharedAwaitingChats.contains(chatId)) &&
                !text.isNullOrBlank() ->
                proceedBotJoinSharedCode(chatId, text)
            botBookingCommentDrafts.containsKey(chatId) && !text.isNullOrBlank() ->
                proceedBotVenueBookingComment(chatId, text)
            state.state == DialogStateType.STAFF_CALL_WAIT_COMMENT && !text.isNullOrBlank() ->
                proceedStaffCallComment(chatId, text)
            message.webAppData != null -> handleWebAppData(chatId, from, message.webAppData)
            else -> sendFallback(chatId, from)
        }
    }

    private suspend fun handleWebAppData(
        chatId: Long,
        from: User?,
        webAppData: WebAppData,
    ) {
        val data = webAppData.data
        when (data) {
            "start_quick_order" -> startQuickOrder(chatId)
            "call_staff" -> showStaffCallReasons(chatId)
            "open_active_order" -> showActiveOrder(chatId)
            else -> handleJsonWebAppData(chatId, from, data)
        }
    }

    private suspend fun handleJsonWebAppData(
        chatId: Long,
        from: User?,
        data: String,
    ) {
        val parsed = runCatching { json.decodeFromString<JsonElement>(data) }.getOrNull()
        val obj = parsed as? JsonObject ?: return
        val cmd = obj["cmd"]?.jsonPrimitive?.content
        val token = obj["table_token"]?.jsonPrimitive?.content
        if (!token.isNullOrBlank()) {
            when (applyTableToken(chatId, from, token)) {
                ApplyTableTokenResult.Applied -> Unit
                ApplyTableTokenResult.Invalid -> {
                    sendFallback(chatId, from, "QR недействителен или база недоступна. Используйте меню ниже.")
                    return
                }
                ApplyTableTokenResult.Blocked -> {
                    enqueueMessage(chatId, subscriptionBlockedMessage)
                    return
                }
                ApplyTableTokenResult.VenueUnavailableForGuest -> {
                    enqueueMessage(chatId, "Заведение пока не доступно для гостей.")
                    return
                }
                ApplyTableTokenResult.DatabaseUnavailable -> {
                    enqueueMessage(chatId, "База недоступна, попробуйте позже.")
                    return
                }
            }
        }
        when (cmd) {
            "call_staff" -> {
                val reasonRaw = obj["reason"]?.jsonPrimitive?.content
                val reason = reasonRaw?.let { parseStaffCallReason(it) } ?: StaffCallReason.OTHER
                val comment = obj["comment"]?.jsonPrimitive?.content
                createStaffCall(chatId, reason, comment)
            }
            "start_quick_order" -> startQuickOrder(chatId)
            "open_active_order" -> showActiveOrder(chatId)
            null ->
                if (logger.isDebugEnabled) {
                    val keysSummary = summarizeJsonKeysForLog(obj)
                    logger.debug(
                        "web_app_data missing cmd keys_count={} keys={} raw_len={}",
                        obj.size,
                        keysSummary,
                        data.length,
                    )
                }
            else ->
                if (logger.isDebugEnabled) {
                    logger.debug("Unsupported web_app_data cmd: {}", sanitizeTelegramForLog(cmd))
                }
        }
    }

    private suspend fun handleCallback(callbackQuery: CallbackQuery) {
        val chatId = callbackQuery.message?.chat?.id
        if (chatId == null) {
            enqueueCallbackAnswer(null, callbackQuery.id)
            return
        }
        safeUpsertUser(callbackQuery.from)
        val data = callbackQuery.data
        val sourceMessageId = callbackQuery.message?.messageId
        if (
            logger.isDebugEnabled &&
            (
                data == "bot_menu_back_categories" ||
                    data?.startsWith("bot_menu_back_category:") == true ||
                    data?.startsWith("bot_menu_category_page:") == true ||
                    data?.startsWith("bot_menu_category:") == true
            )
        ) {
            logger.debug(
                "menu_callback chat_id={} message_id={} data={}",
                chatId,
                sourceMessageId,
                sanitizeTelegramForLog(data),
            )
        }
        when {
            data == "bot_active_order_view" -> showActiveOrder(chatId)
            data == "bot_active_order_reorder" -> startBotReorderFlow(chatId, sourceMessageId)
            data == "bot_open_split_bill_entry" -> showBotSplitBillEntry(chatId)
            data == "bot_catalog_open" -> showBotVenueCatalog(chatId)
            data?.startsWith("bot_catalog_venue:") == true -> showBotVenueCard(chatId, data)
            data?.startsWith("bot_catalog_venue_book:") == true -> showBotVenueBookingEntry(chatId, data)
            data?.startsWith("bot_catalog_venue_book_date:") == true -> confirmBotVenueBookingDate(chatId, data)
            data?.startsWith("bot_catalog_venue_book_time:") == true -> confirmBotVenueBookingTime(chatId, data)
            data?.startsWith("bot_catalog_venue_book_guests:") == true ->
                confirmBotVenueBookingGuests(chatId, callbackQuery.from.id, data)
            data?.startsWith("bot_catalog_venue_book_comment_prompt:") == true ->
                promptBotVenueBookingComment(chatId, callbackQuery.from.id, data)
            data?.startsWith("bot_catalog_venue_book_comment_skip:") == true ->
                confirmBotVenueBookingWithoutComment(chatId, callbackQuery.from.id, data)
            data == "bot_catalog_venue_book_confirm" -> confirmBotVenueBooking(chatId, callbackQuery.from.id)
            data == "bot_catalog_venue_book_back_comment" -> backToBotVenueBookingComment(chatId)
            data?.startsWith("bot_catalog_venue_book_more:") == true -> showBotVenueBookingMoreDates(chatId, data)
            data?.startsWith("bot_my_booking_edit:") == true ->
                editMyBooking(chatId, callbackQuery.from.id, data)
            data?.startsWith("bot_my_booking_cancel:") == true ->
                cancelMyBooking(chatId, callbackQuery.from.id, data)
            data?.startsWith("bot_catalog_venue_menu_section:") == true -> showBotVenueMenuSection(chatId, data)
            data?.startsWith("bot_catalog_venue_menu:") == true -> showBotVenueMenuEntry(chatId, data)
            data?.startsWith("bot_catalog_venue_about_section:") == true -> showBotVenueAboutSection(chatId, data)
            data?.startsWith("bot_catalog_venue_about:") == true -> showBotVenueAbout(chatId, data)
            data?.startsWith("bot_catalog_venue_address:") == true -> showBotVenueAddress(chatId, data)
            data == "venue_connect_contact_use_username" -> proceedVenueConnectionUseUsername(chatId)
            data == "venue_connect_contact_additional" -> promptVenueConnectionAdditionalContact(chatId)
            data?.startsWith("venue_connect_pending_edit:") == true ->
                startVenueConnectionEdit(chatId, callbackQuery.from.id, data)
            data?.startsWith("venue_connect_pending_cancel:") == true ->
                cancelVenueConnectionRequest(chatId, callbackQuery.from.id, data)
            data?.startsWith("owner_venue_connect_approve:") == true ->
                handleOwnerVenueConnectionDecision(chatId, callbackQuery.from.id, data, approved = true)
            data?.startsWith("owner_venue_connect_reject:") == true ->
                handleOwnerVenueConnectionDecision(chatId, callbackQuery.from.id, data, approved = false)
            data?.startsWith("owner_venue_terms_open:") == true ->
                startOwnerVenueCommercialTerms(chatId, callbackQuery.from.id, data)
            data?.startsWith("owner_venue_terms_trial:") == true ->
                proceedOwnerVenueTermsTrialChoice(chatId, callbackQuery.from.id, data)
            data?.startsWith("owner_venue_terms_future:") == true ->
                proceedOwnerVenueTermsFutureChoice(chatId, callbackQuery.from.id, data)
            data?.startsWith("owner_venue_create_from_request:") == true ->
                startOwnerCreateVenueFromApprovedRequest(chatId, callbackQuery.from.id, data)
            data == "owner_venue_requests_back" -> {
                if (isPlatformOwner(callbackQuery.from.id)) {
                    showOwnerVenueConnectionRequests(chatId)
                } else {
                    enqueueMessage(chatId, "Недостаточно прав.")
                }
            }
            data == "owner_venue_onboarding_entry" ->
                showVenueOwnerVenueCard(chatId, callbackQuery.from)
            data == "owner_venue_onboarding_miniapp_unavailable" ->
                enqueueMessage(chatId, "Mini App сейчас недоступен. Используйте «💬 Настраивать в боте».")
            data?.startsWith("owner_venue_publish_readiness:") == true ->
                showVenueOwnerPublishReadiness(chatId, callbackQuery.from.id, data)
            data?.startsWith("owner_venue_publish_preview:") == true ->
                showVenueOwnerPublishPreview(chatId, callbackQuery.from.id, data)
            data?.startsWith("owner_venue_publish_confirm:") == true ->
                publishVenueFromOwnerBot(chatId, callbackQuery.from.id, data)
            data?.startsWith("owner_venue_hours_open:") == true ->
                showOwnerVenueHoursScreen(chatId, callbackQuery.from.id, data)
            data?.startsWith("owner_venue_hours_day:") == true ->
                showOwnerVenueHoursDayScreen(chatId, callbackQuery.from.id, data)
            data?.startsWith("owner_venue_hours_day_edit:") == true ->
                startOwnerVenueHoursDayEdit(chatId, callbackQuery.from.id, data)
            data?.startsWith("owner_venue_hours_day_open:") == true ->
                startOwnerVenueHoursDayEdit(chatId, callbackQuery.from.id, data)
            data?.startsWith("owner_venue_hours_day_close:") == true ->
                closeOwnerVenueHoursDay(chatId, callbackQuery.from.id, data)
            data?.startsWith("owner_venue_hours_overrides:") == true ->
                showOwnerVenueHoursOverrides(chatId, callbackQuery.from.id, data)
            data?.startsWith("owner_venue_hours_override_add:") == true ->
                promptOwnerVenueHoursOverrideDate(chatId, callbackQuery.from.id, data)
            data?.startsWith("owner_venue_hours_override_mode:") == true ->
                chooseOwnerVenueHoursOverrideMode(chatId, callbackQuery.from.id, data)
            data?.startsWith("owner_venue_hours_override_edit:") == true ->
                showOwnerVenueHoursOverrideEdit(chatId, callbackQuery.from.id, data)
            data?.startsWith("owner_venue_hours_override_delete:") == true ->
                deleteOwnerVenueHoursOverride(chatId, callbackQuery.from.id, data)
            data?.startsWith("owner_venue_hours_back:") == true ->
                backFromOwnerVenueHours(chatId, callbackQuery.from.id, data)
            data?.startsWith("owner_venue_description_open:") == true ->
                showOwnerVenueDescriptionSections(chatId, callbackQuery.from.id, data)
            data?.startsWith("owner_venue_description_section:") == true ->
                showOwnerVenueDescriptionSection(chatId, callbackQuery.from.id, data)
            data?.startsWith("owner_venue_description_add:") == true ->
                promptOwnerVenueDescriptionCustomSectionTitle(chatId, callbackQuery.from.id, data)
            data?.startsWith("owner_venue_description_text:") == true ->
                promptOwnerVenueDescriptionSectionText(chatId, callbackQuery.from.id, data)
            data?.startsWith("owner_venue_description_media:") == true ->
                promptOwnerVenueDescriptionSectionMedia(chatId, callbackQuery.from.id, data)
            data?.startsWith("owner_venue_description_media_delete:") == true ->
                deleteOwnerVenueDescriptionSectionMedia(chatId, callbackQuery.from.id, data)
            data?.startsWith("owner_venue_description_visibility:") == true ->
                toggleOwnerVenueDescriptionSectionVisibility(chatId, callbackQuery.from.id, data)
            data?.startsWith("owner_venue_description_back:") == true ->
                backFromOwnerVenueDescription(chatId, callbackQuery.from.id, data)
            data?.startsWith("owner_venue_order_menu_root:") == true ->
                showVenueOwnerOrderMenuRootByCallback(chatId, callbackQuery.from.id, data)
            data?.startsWith("owner_venue_order_menu_section:") == true ->
                showVenueOwnerOrderMenuSection(chatId, callbackQuery.from.id, data)
            data?.startsWith("owner_venue_order_menu_add:") == true ->
                promptVenueOwnerOrderMenuAddSection(chatId, callbackQuery.from.id, data)
            data?.startsWith("owner_venue_order_menu_stoplist_unstop_option:") == true ->
                removeVenueOwnerOrderMenuOptionFromStopList(chatId, callbackQuery.from.id, data)
            data?.startsWith("owner_venue_order_menu_stoplist_unstop:") == true ->
                removeVenueOwnerOrderMenuItemFromStopList(chatId, callbackQuery.from.id, data)
            data?.startsWith("owner_venue_order_menu_stoplist:") == true ->
                showVenueOwnerOrderMenuStopList(chatId, callbackQuery.from.id, data)
            data?.startsWith("owner_venue_order_menu_item_add:") == true ->
                promptVenueOwnerOrderMenuAddItem(chatId, callbackQuery.from.id, data)
            data?.startsWith("owner_venue_order_menu_item:") == true ->
                showVenueOwnerOrderMenuItem(chatId, callbackQuery.from.id, data)
            data?.startsWith("owner_venue_order_menu_item_flavors:") == true ->
                showVenueOwnerOrderMenuItemFlavors(chatId, callbackQuery.from.id, data)
            data?.startsWith("owner_venue_order_menu_item_option_add:") == true ->
                promptVenueOwnerOrderMenuAddFlavor(chatId, callbackQuery.from.id, data)
            data?.startsWith("owner_venue_order_menu_item_option:") == true ->
                showVenueOwnerOrderMenuFlavorOption(chatId, callbackQuery.from.id, data)
            data?.startsWith("owner_venue_order_menu_item_option_rename:") == true ->
                promptVenueOwnerOrderMenuRenameFlavor(chatId, callbackQuery.from.id, data)
            data?.startsWith("owner_venue_order_menu_item_option_stop:") == true ->
                setVenueOwnerOrderMenuFlavorAvailability(chatId, callbackQuery.from.id, data, isAvailable = false)
            data?.startsWith("owner_venue_order_menu_item_option_unstop:") == true ->
                setVenueOwnerOrderMenuFlavorAvailability(chatId, callbackQuery.from.id, data, isAvailable = true)
            data?.startsWith("owner_venue_order_menu_item_option_delete:") == true ->
                deleteVenueOwnerOrderMenuFlavor(chatId, callbackQuery.from.id, data)
            data?.startsWith("owner_venue_order_menu_item_rename:") == true ->
                promptVenueOwnerOrderMenuRenameItem(chatId, callbackQuery.from.id, data)
            data?.startsWith("owner_venue_order_menu_item_price:") == true ->
                promptVenueOwnerOrderMenuEditItemPrice(chatId, callbackQuery.from.id, data)
            data?.startsWith("owner_venue_order_menu_item_stop:") == true ->
                setVenueOwnerOrderMenuItemAvailability(chatId, callbackQuery.from.id, data, isAvailable = false)
            data?.startsWith("owner_venue_order_menu_item_unstop:") == true ->
                setVenueOwnerOrderMenuItemAvailability(chatId, callbackQuery.from.id, data, isAvailable = true)
            data?.startsWith("owner_venue_order_menu_item_delete:") == true ->
                deleteVenueOwnerOrderMenuItem(chatId, callbackQuery.from.id, data)
            data?.startsWith("owner_venue_order_menu_section_rename:") == true ->
                promptVenueOwnerOrderMenuRenameSection(chatId, callbackQuery.from.id, data)
            data?.startsWith("owner_venue_order_menu_section_delete:") == true ->
                deleteVenueOwnerOrderMenuSection(chatId, callbackQuery.from.id, data)
            data?.startsWith("owner_venue_tables_root:") == true ->
                showVenueOwnerTablesRootByCallback(chatId, callbackQuery.from.id, data)
            data?.startsWith("owner_venue_tables_list:") == true ->
                showVenueOwnerTablesList(chatId, callbackQuery.from.id, data)
            data?.startsWith("owner_venue_tables_add:") == true ->
                promptVenueOwnerAddTable(chatId, callbackQuery.from.id, data)
            data?.startsWith("owner_venue_tables_table:") == true ->
                showVenueOwnerTable(chatId, callbackQuery.from.id, data)
            data?.startsWith("owner_venue_tables_table_rename:") == true ->
                promptVenueOwnerEditTableNumber(chatId, callbackQuery.from.id, data)
            data?.startsWith("owner_venue_tables_table_capacity:") == true ->
                promptVenueOwnerEditTableCapacity(chatId, callbackQuery.from.id, data)
            data?.startsWith("owner_venue_tables_qr:") == true ->
                showVenueOwnerTablesQr(chatId, callbackQuery.from.id, data)
            data?.startsWith("owner_venue_tables_qr_rotate:") == true ->
                rotateVenueOwnerTableQr(chatId, callbackQuery.from.id, data)
            data?.startsWith("owner_venue_tables_qr_table:") == true ->
                showVenueOwnerTableQr(chatId, callbackQuery.from.id, data)
            data?.startsWith("owner_venue_staff_root:") == true ->
                showVenueOwnerStaffRootByCallback(chatId, callbackQuery.from.id, data)
            data?.startsWith("owner_venue_staff_add:") == true ->
                showVenueOwnerStaffRoleChooser(chatId, callbackQuery.from.id, data)
            data?.startsWith("owner_venue_staff_role_info:") == true ->
                showVenueOwnerStaffRoleInfo(chatId, callbackQuery.from.id, data)
            data?.startsWith("owner_venue_staff_role_select:") == true ->
                selectVenueOwnerStaffRole(chatId, callbackQuery.from.id, data)
            data?.startsWith("owner_venue_staff_remove_prompt:") == true ->
                promptVenueOwnerStaffRemove(chatId, callbackQuery.from.id, data)
            data?.startsWith("owner_venue_staff_remove_confirm:") == true ->
                confirmVenueOwnerStaffRemove(chatId, callbackQuery.from.id, data)
            data?.startsWith("staff_invite_accept:") == true ->
                acceptStaffInvite(chatId, callbackQuery.from.id, data)
            data?.startsWith("staff_invite_decline:") == true ->
                declineStaffInvite(chatId, callbackQuery.from.id, data)
            data?.startsWith("owner_venue_menu_guest:") == true ->
                showVenueOwnerGuestMenuPlaceholder(chatId, callbackQuery.from.id, data)
            data?.startsWith("owner_venue_menu_order:") == true ->
                showVenueOwnerOrderMenuByLegacyCallback(chatId, callbackQuery.from.id, data)
            data?.startsWith("staff_venue_calls_root:") == true ->
                showVenueStaffCallsRootByCallback(chatId, callbackQuery.from.id, data)
            data?.startsWith("staff_venue_orders_root:") == true ->
                showVenueStaffOrdersRootByCallback(chatId, callbackQuery.from.id, data)
            data?.startsWith("staff_venue_orders_full:") == true ->
                showVenueStaffOrderFullDetails(chatId, callbackQuery.from.id, data)
            data?.startsWith("staff_venue_orders_edit_bill:") == true ->
                showVenueStaffOrderEditBill(chatId, callbackQuery.from.id, data)
            data?.startsWith("staff_order_bill_item:") == true ->
                showVenueStaffOrderBillItemAction(chatId, callbackQuery.from.id, data)
            data?.startsWith("staff_order_bill_exclude:") == true ->
                promptVenueStaffOrderBillItemExcludeReason(chatId, callbackQuery.from.id, data)
            data?.startsWith("staff_order_bill_discount:") == true ->
                promptVenueStaffOrderBillItemDiscountPercent(chatId, callbackQuery.from.id, data)
            data?.startsWith("staff_venue_orders_order:") == true ->
                showVenueStaffOrderDetails(chatId, callbackQuery.from.id, data)
            data?.startsWith("staff_venue_orders_cancel_batch:") == true ->
                promptVenueStaffBatchCancelReason(chatId, callbackQuery.from.id, data)
            data?.startsWith("staff_venue_orders_cancel_order:") == true ->
                promptVenueStaffOrderCancelReason(chatId, callbackQuery.from.id, data)
            data?.startsWith("staff_venue_orders_accept_all_new:") == true ->
                acceptAllNewVenueStaffOrderBatches(chatId, callbackQuery.from.id, data)
            data?.startsWith("staff_venue_orders_deliver_all_accepted:") == true ->
                deliverAllAcceptedVenueStaffOrderBatches(chatId, callbackQuery.from.id, data)
            data?.startsWith("staff_venue_orders_status:") == true ->
                updateVenueStaffOrderStatus(chatId, callbackQuery.from.id, data)
            data?.startsWith("staff_venue_stoplist_root:") == true ->
                showVenueStaffStopListRootByCallback(chatId, callbackQuery.from.id, data)
            data?.startsWith("staff_venue_stoplist_add:") == true ->
                showVenueStaffStopListSections(chatId, callbackQuery.from.id, data)
            data?.startsWith("staff_venue_stoplist_section:") == true ->
                showVenueStaffStopListSection(chatId, callbackQuery.from.id, data)
            data?.startsWith("staff_venue_stoplist_item_flavors:") == true ->
                showVenueStaffStopListItemFlavors(chatId, callbackQuery.from.id, data)
            data?.startsWith("staff_venue_stoplist_item_stop:") == true ->
                setVenueStaffStopListItemAvailability(chatId, callbackQuery.from.id, data, isAvailable = false)
            data?.startsWith("staff_venue_stoplist_item_unstop:") == true ->
                setVenueStaffStopListItemAvailability(chatId, callbackQuery.from.id, data, isAvailable = true)
            data?.startsWith("staff_venue_stoplist_item:") == true ->
                showVenueStaffStopListItem(chatId, callbackQuery.from.id, data)
            data?.startsWith("staff_venue_stoplist_option_stop:") == true ->
                setVenueStaffStopListOptionAvailability(chatId, callbackQuery.from.id, data, isAvailable = false)
            data?.startsWith("staff_venue_stoplist_option_unstop:") == true ->
                setVenueStaffStopListOptionAvailability(chatId, callbackQuery.from.id, data, isAvailable = true)
            data?.startsWith("staff_venue_stoplist_option:") == true ->
                showVenueStaffStopListOption(chatId, callbackQuery.from.id, data)
            data?.startsWith("staff_venue_stoplist_unstop_option:") == true ->
                removeVenueStaffStopListOption(chatId, callbackQuery.from.id, data)
            data?.startsWith("staff_venue_stoplist_unstop_item:") == true ->
                removeVenueStaffStopListItem(chatId, callbackQuery.from.id, data)
            data?.startsWith("stats_period_") == true ->
                showStatsPeriod(chatId, callbackQuery.from.id, data)
            data?.startsWith("venue_settings_root") == true ->
                showVenueSettingsRootByCallback(chatId, callbackQuery.from.id, data)
            data?.startsWith("venue_settings_notifications:") == true ->
                showVenueSettingsNotifications(chatId, callbackQuery.from.id, data)
            data?.startsWith("venue_settings_toggle_orders:") == true ->
                toggleVenueSettingsNotification(chatId, callbackQuery.from.id, data, VenueNotificationSetting.ORDERS)
            data?.startsWith("venue_settings_toggle_staff_calls:") == true ->
                toggleVenueSettingsNotification(chatId, callbackQuery.from.id, data, VenueNotificationSetting.STAFF_CALLS)
            data?.startsWith("venue_settings_toggle_cancellations:") == true ->
                toggleVenueSettingsNotification(chatId, callbackQuery.from.id, data, VenueNotificationSetting.CANCELLATIONS)
            data?.startsWith("venue_settings_timezone:") == true ->
                showVenueSettingsTimezone(chatId, callbackQuery.from.id, data)
            data?.startsWith("venue_settings_order_numbering:") == true ->
                showVenueSettingsOrderNumbering(chatId, callbackQuery.from.id, data)
            data?.startsWith("venue_settings_stats_reports:") == true ->
                showVenueSettingsStatsReports(chatId, callbackQuery.from.id, data)
            data?.startsWith("venue_settings_dev_reset_confirm:") == true ->
                showVenueSettingsDevResetConfirm(chatId, callbackQuery.from.id, data)
            data?.startsWith("venue_settings_dev_reset_execute:") == true ->
                executeVenueSettingsDevReset(chatId, callbackQuery.from.id, data)
            data?.startsWith("venue_settings_dev_reset:") == true ->
                showVenueSettingsDevReset(chatId, callbackQuery.from.id, data)
            data?.startsWith("venue_settings_about:") == true ->
                showVenueSettingsAbout(chatId, callbackQuery.from.id, data)
            data == "staff_venue_menu_back" ->
                showRoleAwareMainMenu(chatId, callbackQuery.from)
            data?.startsWith("owner_venue_field:") == true ->
                showVenueOwnerFieldEntry(chatId, callbackQuery.from.id, data)
            data == "entry_have_qr" -> showTableQrEntryHint(chatId)
            data == "continue_in_bot" -> continueInBot(chatId)
            data == "bot_menu_reorder_entry" -> showBotMenu(chatId, reorderMode = true, sourceMessageId = sourceMessageId)
            data == "bot_menu_back_categories" -> {
                logger.warn(
                    "CALLBACK back_categories data={}",
                    sanitizeTelegramForLog(data),
                )
                showBotMenu(chatId, sourceMessageId = sourceMessageId)
            }
            data?.startsWith("bot_menu_back_category:") == true ->
                showBotMenuCategoryItems(chatId, data, sourceMessageId = sourceMessageId)
            data?.startsWith("bot_menu_category_page:") == true -> {
                logger.warn(
                    "CALLBACK category_page data={}",
                    sanitizeTelegramForLog(data),
                )
                showBotMenuCategoryItems(chatId, data, sourceMessageId = sourceMessageId)
            }
            data?.startsWith("bot_menu_category:") == true -> {
                logger.warn(
                    "CALLBACK category data={}",
                    sanitizeTelegramForLog(data),
                )
                showBotMenuCategoryItems(chatId, data, sourceMessageId = sourceMessageId)
            }
            data?.startsWith("bot_menu_item_option_add_to_cart:") == true ->
                addBotMenuItemOptionToCart(chatId, data, sourceMessageId)
            data?.startsWith("bot_menu_item_add:") == true -> showBotMenuItemDetails(chatId, data, sourceMessageId)
            data?.startsWith("bot_menu_item_add_to_cart:") == true -> showBotMenuItemDetails(chatId, data, sourceMessageId)
            data?.startsWith("bot_menu_item_reorder:") == true ->
                reorderBotMenuItem(chatId, data, callbackQuery.id)
            data == "bot_menu_item_cart" -> showBotMenuCart(chatId, sourceMessageId)
            data?.startsWith("bot_menu_cart_inc:") == true ->
                changeBotMenuCartItemQty(chatId, data, delta = 1, sourceMessageId = sourceMessageId)
            data?.startsWith("bot_menu_cart_dec:") == true ->
                changeBotMenuCartItemQty(chatId, data, delta = -1, sourceMessageId = sourceMessageId)
            data?.startsWith("bot_menu_cart_remove:") == true ->
                removeBotMenuCartItem(chatId, data, sourceMessageId = sourceMessageId)
            data == "bot_menu_cart_back_menu" -> showBotMenu(chatId, sourceMessageId = sourceMessageId)
            data == "bot_menu_cart_comment" -> promptBotMenuCartComment(chatId)
            data == "bot_menu_cart_clear" -> clearBotMenuCart(chatId, sourceMessageId)
            data == "bot_menu_cart_checkout" -> checkoutBotMenuCart(chatId, callbackQuery.id)
            data == "bot_tabs_create_shared" -> createBotSharedTab(chatId)
            data == "bot_tabs_join_prompt" -> promptBotJoinShared(chatId)
            data == "bot_menu_item_staff" -> showStaffCallReasons(chatId)
            data?.startsWith("staff_call_bill_payment:") == true -> handleStaffCallBillPaymentCallback(chatId, data)
            data?.startsWith("bot_menu_item:") == true -> showBotMenuItemDetails(chatId, data, sourceMessageId)
            data == "quick_order_confirm" -> confirmQuickOrder(chatId)
            data == "quick_order_edit" -> {
                dialogStateRepository.set(
                    chatId,
                    DialogState(DialogStateType.QUICK_ORDER_WAIT_TEXT),
                )
                enqueueMessage(chatId, "Напишите детали заказа.")
            }
            data == "quick_order_cancel" -> {
                dialogStateRepository.clear(chatId)
                enqueueMessage(chatId, "Быстрый заказ отменён.")
            }
            data == null -> Unit
            else -> handleStaffCallCallback(chatId, data)
        }
        enqueueCallbackAnswer(chatId, callbackQuery.id)
    }

    private suspend fun handleStaffCallCallback(
        chatId: Long,
        data: String,
    ) {
        if (!data.startsWith("staff_call_reason:")) return
        val reason = parseStaffCallReason(data.removePrefix("staff_call_reason:"))
        if (reason == StaffCallReason.BILL) {
            enqueueMessage(chatId, "Как оплатите счёт?", TelegramKeyboards.inlineStaffCallBillPaymentMethods())
            return
        }
        if (reason == StaffCallReason.OTHER) {
            dialogStateRepository.set(chatId, DialogState(DialogStateType.STAFF_CALL_WAIT_COMMENT))
            enqueueMessage(chatId, "Опишите, что нужно сделать.")
        } else {
            createStaffCall(chatId, reason, null)
        }
    }

    private suspend fun handleStaffCallBillPaymentCallback(
        chatId: Long,
        data: String,
    ) {
        val paymentMethod =
            when (data.removePrefix("staff_call_bill_payment:").uppercase(Locale.ROOT)) {
                "CARD" -> "Картой"
                "CASH" -> "Наличными"
                else -> {
                    enqueueMessage(chatId, "Не удалось определить способ оплаты. Попробуйте ещё раз.")
                    showStaffCallReasons(chatId)
                    return
                }
            }
        createStaffCall(chatId, StaffCallReason.BILL, "Способ оплаты: $paymentMethod")
    }

    private suspend fun handleStartCommand(
        chatId: Long,
        from: User?,
        text: String,
    ) {
        if (isPlatformOwner(from?.id)) {
            showOwnerMainMenu(chatId)
            return
        }
        val parts = text.trim().split(Regex("\\s+"))
        val command = parts.firstOrNull()?.substringBefore("@") ?: ""
        val token = if (command == "/start") parts.getOrNull(1) else null
        if (token.isNullOrBlank()) {
            showRoleAwareMainMenu(chatId, from)
            return
        }
        parseStaffInviteStartCode(token)?.let { inviteCode ->
            showStaffInviteLanding(chatId, from, inviteCode)
            return
        }
        when (applyTableToken(chatId, from, token, announceChannelChoice = true)) {
            ApplyTableTokenResult.Applied -> Unit
            ApplyTableTokenResult.Invalid ->
                sendFallback(
                    chatId,
                    from,
                    "QR недействителен или база недоступна. Используйте меню ниже.",
                )
            ApplyTableTokenResult.Blocked -> enqueueMessage(chatId, subscriptionBlockedMessage)
            ApplyTableTokenResult.VenueUnavailableForGuest ->
                enqueueMessage(chatId, "Заведение пока не доступно для гостей.")
            ApplyTableTokenResult.DatabaseUnavailable ->
                enqueueMessage(
                    chatId,
                    "База недоступна, попробуйте позже.",
                )
        }
    }

    private suspend fun showRoleAwareMainMenu(
        chatId: Long,
        from: User?,
    ) {
        val userId = from?.id
        if (isPlatformOwner(userId)) {
            showOwnerMainMenu(chatId)
            return
        }
        when (val access = resolvePrimaryVenueBotAccess(userId)) {
            null -> showMainMenu(chatId, from)
            else ->
                when (access.role) {
                    VenueBotRole.OWNER -> showVenueOwnerBotEntry(chatId, from)
                    VenueBotRole.MANAGER -> showVenueManagerBotEntry(chatId, access.venueId)
                    VenueBotRole.STAFF -> showVenueStaffBotEntry(chatId, access.venueId)
                }
        }
    }

    private suspend fun showBotVenueCatalog(chatId: Long) {
        botVenuePreviewContexts.remove(chatId)
        val venues =
            try {
                if (isBotTestCatalogSeedingEnabled()) {
                    venueRepository.ensureBotTestCatalogVenues()
                    venueBookingHoursRepository.ensureBotTestVenueBookingHours()
                    venueMenuSectionImagesRepository.ensureBotTestVenueMenuSectionImages()
                }
                venueRepository.listCatalogVenuesForGuest()
            } catch (e: DatabaseUnavailableException) {
                enqueueMessage(chatId, "База недоступна, попробуйте позже.")
                return
            }
        if (venues.isEmpty()) {
            enqueueMessage(chatId, "Сейчас в каталоге нет доступных заведений.")
            return
        }
        val catalogText = "🍽 Каталог кальянных\nВыберите заведение."
        enqueueMessage(
            chatId,
            catalogText,
            TelegramKeyboards.inlineBotVenueCatalog(
                venues.map { venue ->
                    venue.id to "${venue.name} · ${formatVenueShortAddress(venue)}"
                },
            ),
        )
    }

    private suspend fun showBotVenueCard(
        chatId: Long,
        data: String,
    ) {
        val venue = loadCatalogVenueFromCallback(chatId, data, "bot_catalog_venue:") ?: return
        val previewContext = botVenuePreviewContexts[chatId]
        val showDraftPreviewNotice = previewContext?.venueId == venue.id && previewContext.showDraftNotice
        clearBotBookingScreenMessages(chatId)
        enqueueMessage(
            chatId,
            buildString {
                if (showDraftPreviewNotice) {
                    append("Это предпросмотр. Заведение пока не опубликовано.")
                    append("\n\n")
                }
                append(venue.name)
                append("\n")
                append("📍 ${formatVenueAddress(venue)}")
                append("\n\nЧто хотите сделать?")
            },
            TelegramKeyboards.inlineBotVenueCardActions(venue.id, buildVenueRouteUrl(venue)),
        )
    }

    private suspend fun showBotVenueBookingEntry(
        chatId: Long,
        data: String,
    ) {
        botBookingEditContexts.remove(chatId)
        val venue = loadCatalogVenueFromCallback(chatId, data, "bot_catalog_venue_book:") ?: return
        renderBotVenueBookingDatePicker(chatId, venue, offset = 0)
    }

    private suspend fun showBotVenueBookingMoreDates(
        chatId: Long,
        data: String,
    ) {
        val parsed = parseBotVenueBookingMoreData(data)
        if (parsed == null) {
            enqueueMessage(chatId, "Не удалось открыть даты бронирования. Попробуйте ещё раз.")
            return
        }
        val (venueId, offset) = parsed
        val venue = loadCatalogVenueById(chatId, venueId) ?: return
        renderBotVenueBookingDatePicker(
            chatId,
            venue,
            offset = offset,
            prompt = "Пока доступны ближайшие даты. Выберите дату бронирования.",
        )
    }

    private suspend fun confirmBotVenueBookingDate(
        chatId: Long,
        data: String,
    ) {
        val parsed = parseBotVenueBookingDateData(data)
        if (parsed == null) {
            enqueueMessage(chatId, "Не удалось определить дату бронирования. Попробуйте ещё раз.")
            return
        }
        val (venueId, date) = parsed
        if (!isBookingDateInAllowedRange(date)) {
            enqueueMessage(chatId, "Можно выбрать только ближайшие даты бронирования.")
            return
        }
        val venue = loadCatalogVenueById(chatId, venueId) ?: return
        renderBotVenueBookingTimePicker(chatId, venue, date)
    }

    private suspend fun confirmBotVenueBookingTime(
        chatId: Long,
        data: String,
    ) {
        val parsed = parseBotVenueBookingTimeData(data)
        if (parsed == null) {
            enqueueMessage(chatId, "Не удалось определить время бронирования. Попробуйте ещё раз.")
            return
        }
        val (venueId, date, time) = parsed
        if (!isBookingDateInAllowedRange(date)) {
            enqueueMessage(chatId, "Можно выбрать только ближайшие даты бронирования.")
            return
        }
        val venue = loadCatalogVenueById(chatId, venueId) ?: return
        val timeSlots = loadBookingTimeSlots(chatId, venue, date) ?: return
        if (time !in timeSlots) {
            enqueueMessage(chatId, "Не удалось определить время бронирования. Попробуйте ещё раз.")
            return
        }
        renderBotVenueBookingGuestCountPicker(
            chatId = chatId,
            venue = venue,
            date = date,
            time = time,
        )
    }

    private suspend fun confirmBotVenueBookingGuests(
        chatId: Long,
        userId: Long,
        data: String,
    ) {
        val parsed = parseBotVenueBookingGuestsData(data)
        if (parsed == null) {
            enqueueMessage(chatId, "Не удалось определить количество гостей. Попробуйте ещё раз.")
            return
        }
        val (venueId, date, time, guestsLabel) = parsed
        if (!isBookingDateInAllowedRange(date)) {
            enqueueMessage(chatId, "Можно выбрать только ближайшие даты бронирования.")
            return
        }
        val venue = loadCatalogVenueById(chatId, venueId) ?: return
        val timeSlots = loadBookingTimeSlots(chatId, venue, date) ?: return
        if (time !in timeSlots) {
            enqueueMessage(chatId, "Не удалось определить время бронирования. Попробуйте ещё раз.")
            return
        }
        renderBotVenueBookingCommentStep(
            chatId = chatId,
            venue = venue,
            draft =
                BotBookingDraft(
                    userId = userId,
                    venueId = venue.id,
                    date = date,
                    time = time,
                    guestsLabel = guestsLabel,
                ),
        )
    }

    private suspend fun promptBotVenueBookingComment(
        chatId: Long,
        userId: Long,
        data: String,
    ) {
        val draft = parseBotVenueBookingCommentData(data, "bot_catalog_venue_book_comment_prompt:") ?: run {
            enqueueMessage(chatId, "Не удалось продолжить бронирование. Попробуйте ещё раз.")
            return
        }
        val venue = loadCatalogVenueById(chatId, draft.venueId) ?: return
        val timeSlots = loadBookingTimeSlots(chatId, venue, draft.date) ?: return
        if (draft.time !in timeSlots) {
            enqueueMessage(chatId, "Не удалось определить время бронирования. Попробуйте ещё раз.")
            return
        }
        botBookingCommentDrafts[chatId] = draft.copy(userId = userId)
        botBookingPendingConfirmations.remove(chatId)
        sendBotBookingScreen(
            chatId = chatId,
            venueId = draft.venueId,
            text = "Напишите пожелания одним сообщением.",
        )
    }

    private suspend fun confirmBotVenueBookingWithoutComment(
        chatId: Long,
        userId: Long,
        data: String,
    ) {
        val draft = parseBotVenueBookingCommentData(data, "bot_catalog_venue_book_comment_skip:") ?: run {
            enqueueMessage(chatId, "Не удалось продолжить бронирование. Попробуйте ещё раз.")
            return
        }
        val venue = loadCatalogVenueById(chatId, draft.venueId) ?: return
        val timeSlots = loadBookingTimeSlots(chatId, venue, draft.date) ?: return
        if (draft.time !in timeSlots) {
            enqueueMessage(chatId, "Не удалось определить время бронирования. Попробуйте ещё раз.")
            return
        }
        botBookingCommentDrafts.remove(chatId)
        renderBotVenueBookingConfirmation(
            chatId = chatId,
            venue = venue,
            draft = draft.copy(userId = userId, comment = null),
        )
    }

    private suspend fun proceedBotVenueBookingComment(
        chatId: Long,
        comment: String,
    ) {
        val draft = botBookingCommentDrafts.remove(chatId) ?: return
        val normalizedComment = normalizeBotBookingComment(comment)
        if (comment.isNotBlank() && normalizedComment == null) {
            enqueueMessage(chatId, "Комментарий слишком длинный. Максимум 500 символов.")
            botBookingCommentDrafts[chatId] = draft
            return
        }
        val venue = loadCatalogVenueById(chatId, draft.venueId) ?: return
        renderBotVenueBookingConfirmation(
            chatId = chatId,
            venue = venue,
            draft = draft.copy(comment = normalizedComment),
        )
    }

    private suspend fun renderBotVenueBookingConfirmation(
        chatId: Long,
        venue: CatalogVenueShort,
        draft: BotBookingDraft,
    ) {
        botBookingPendingConfirmations[chatId] = draft
        sendBotBookingScreen(
            chatId = chatId,
            venueId = venue.id,
            text =
                "🪑 ${venue.name}\n" +
                    "🗓 Дата: ${draft.date.format(bookingDateConfirmFormatter)}\n" +
                    "🕒 Время: ${draft.time}\n" +
                    "👥 Гостей: ${draft.guestsLabel}\n" +
                    "💬 Комментарий: ${draft.comment?.takeIf { it.isNotBlank() } ?: "—"}\n\n" +
                    "Подтвердить бронь?",
            replyMarkup = TelegramKeyboards.inlineBotVenueBookingConfirmActions(),
        )
    }

    private suspend fun confirmBotVenueBooking(
        chatId: Long,
        callbackUserId: Long,
    ) {
        val draft = botBookingPendingConfirmations[chatId]
        if (draft == null) {
            enqueueMessage(chatId, "Срок подтверждения истёк. Выберите дату и время заново.")
            botBookingEditContexts.remove(chatId)
            return
        }
        if (draft.userId != callbackUserId) {
            enqueueMessage(chatId, "Недостаточно прав для подтверждения этой брони.")
            return
        }
        if (!isBookingDateInAllowedRange(draft.date)) {
            enqueueMessage(chatId, "Можно выбрать только ближайшие даты бронирования.")
            return
        }
        val venue = loadCatalogVenueById(chatId, draft.venueId) ?: return
        when (checkSubscription(draft.venueId)) {
            SubscriptionCheckResult.Available -> Unit
            SubscriptionCheckResult.Blocked -> {
                enqueueMessage(chatId, subscriptionBlockedMessage)
                return
            }
            SubscriptionCheckResult.DatabaseUnavailable -> {
                enqueueMessage(chatId, "База недоступна, попробуйте позже.")
                return
            }
        }
        val timeSlots = loadBookingTimeSlots(chatId, venue, draft.date) ?: return
        if (draft.time !in timeSlots) {
            enqueueMessage(chatId, "Выбранное время больше недоступно. Выберите время заново.")
            return
        }
        val localTime = runCatching { LocalTime.parse(draft.time, bookingTimeFormatter) }.getOrNull()
        if (localTime == null) {
            enqueueMessage(chatId, "Не удалось определить время бронирования. Попробуйте ещё раз.")
            return
        }
        val editContext = botBookingEditContexts[chatId]
        if (editContext != null && editContext.userId != callbackUserId) {
            enqueueMessage(chatId, "Недостаточно прав для подтверждения этой брони.")
            return
        }
        if (editContext != null && editContext.venueId != draft.venueId) {
            enqueueMessage(chatId, "Не удалось обновить бронь. Попробуйте начать заново.")
            botBookingEditContexts.remove(chatId)
            return
        }
        val partySize = guestsLabelToPartySize(draft.guestsLabel)
        val scheduledAt = LocalDateTime.of(draft.date, localTime).atZone(ZoneId.systemDefault()).toInstant()
        clearBotBookingScreenMessages(chatId)
        botBookingPendingConfirmations.remove(chatId)
        botBookingCommentDrafts.remove(chatId)
        if (editContext != null) {
            val updatedBooking =
                try {
                    guestBookingRepository.updateByGuest(
                        bookingId = editContext.bookingId,
                        venueId = draft.venueId,
                        userId = draft.userId,
                        scheduledAt = scheduledAt,
                        partySize = partySize,
                        comment = draft.comment,
                    )
                } catch (e: DatabaseUnavailableException) {
                    botBookingEditContexts.remove(chatId)
                    enqueueMessage(chatId, "База недоступна, попробуйте позже.")
                    return
                }
            botBookingEditContexts.remove(chatId)
            if (updatedBooking == null) {
                enqueueMessage(chatId, "Не удалось обновить бронь. Возможно, она уже неактуальна.")
                return
            }
            enqueueMessage(
                chatId,
                "✅ Бронь обновлена\n" +
                    "Номер брони: #${updatedBooking.id}\n\n" +
                    "Кальянная: ${venue.name}\n" +
                    "Дата: ${draft.date.format(bookingDateConfirmFormatter)}\n" +
                    "Время: ${draft.time}\n" +
                    "Гостей: ${draft.guestsLabel}\n" +
                    "Комментарий: ${draft.comment?.takeIf { it.isNotBlank() } ?: "—"}",
            )
            return
        }
        val createdBooking =
            try {
                guestBookingRepository.create(
                    venueId = draft.venueId,
                    userId = draft.userId,
                    scheduledAt = scheduledAt,
                    partySize = partySize,
                    comment = draft.comment,
                )
            } catch (e: DatabaseUnavailableException) {
                botBookingEditContexts.remove(chatId)
                enqueueMessage(chatId, "База недоступна, попробуйте позже.")
                return
            }
        botBookingEditContexts.remove(chatId)
        enqueueMessage(
            chatId,
            "✅ Бронь создана\n" +
                "Номер брони: #${createdBooking.id}\n\n" +
                "Кальянная: ${venue.name}\n" +
                "Дата: ${draft.date.format(bookingDateConfirmFormatter)}\n" +
                "Время: ${draft.time}\n" +
                "Гостей: ${draft.guestsLabel}\n" +
                "Комментарий: ${draft.comment?.takeIf { it.isNotBlank() } ?: "—"}\n\n" +
                "Изменить или отменить бронь можно в разделе «Мои заказы и брони».",
        )
    }

    private suspend fun backToBotVenueBookingComment(chatId: Long) {
        val draft = botBookingPendingConfirmations[chatId]
        if (draft == null) {
            enqueueMessage(chatId, "Срок подтверждения истёк. Выберите дату и время заново.")
            return
        }
        val venue = loadCatalogVenueById(chatId, draft.venueId) ?: return
        renderBotVenueBookingCommentStep(
            chatId = chatId,
            venue = venue,
            draft = draft,
        )
    }

    private suspend fun renderBotVenueBookingTimePicker(
        chatId: Long,
        venue: CatalogVenueShort,
        date: LocalDate,
    ) {
        val timeSlots = loadBookingTimeSlots(chatId, venue, date) ?: return
        sendBotBookingScreen(
            chatId = chatId,
            venueId = venue.id,
            text = "🪑 ${venue.name}\n🗓 ${date.format(bookingDateConfirmFormatter)}\nВыберите время бронирования.",
            replyMarkup =
                TelegramKeyboards.inlineBotVenueBookingTimeActions(
                    venueId = venue.id,
                    isoDate = date.toString(),
                    timeSlots = timeSlots,
                    buttonsPerRow = 2,
                    backCallbackData = "bot_catalog_venue_book:${venue.id}",
                ),
        )
    }

    private suspend fun renderBotVenueBookingGuestCountPicker(
        chatId: Long,
        venue: CatalogVenueShort,
        date: LocalDate,
        time: String,
    ) {
        sendBotBookingScreen(
            chatId = chatId,
            venueId = venue.id,
            text =
                "🪑 ${venue.name}\n" +
                    "🗓 ${date.format(bookingDateConfirmFormatter)}\n" +
                    "🕒 $time\n" +
                    "Сколько будет гостей?",
            replyMarkup =
                TelegramKeyboards.inlineBotVenueBookingGuestCountActions(
                    venueId = venue.id,
                    isoDate = date.toString(),
                    time = time,
                    backCallbackData = "bot_catalog_venue_book_date:${venue.id}:${date}",
                ),
        )
    }

    private suspend fun renderBotVenueBookingCommentStep(
        chatId: Long,
        venue: CatalogVenueShort,
        draft: BotBookingDraft,
    ) {
        botBookingPendingConfirmations.remove(chatId)
        sendBotBookingScreen(
            chatId = chatId,
            venueId = venue.id,
            text =
                "🪑 ${venue.name}\n" +
                    "🗓 ${draft.date.format(bookingDateConfirmFormatter)}\n" +
                    "🕒 ${draft.time}\n" +
                    "👥 Гостей: ${draft.guestsLabel}\n\n" +
                    (draft.comment?.takeIf { it.isNotBlank() }?.let { "Текущий комментарий: $it\n\n" } ?: "") +
                    "Если есть пожелания по столу, размещению или предзаказу — напишите их.\n" +
                    "Если пожеланий нет, можно продолжить без комментария.",
            replyMarkup =
                TelegramKeyboards.inlineBotVenueBookingCommentActions(
                    venueId = venue.id,
                    isoDate = draft.date.toString(),
                    time = draft.time,
                    guestsToken = guestsLabelToToken(draft.guestsLabel),
                ),
        )
    }

    private suspend fun renderBotVenueBookingDatePicker(
        chatId: Long,
        venue: CatalogVenueShort,
        offset: Int,
        prompt: String = "Выберите дату бронирования.",
    ) {
        val normalizedOffset = offset.coerceAtLeast(0)
        val isMoreDatesPage = normalizedOffset >= bookingDateFirstPageSize
        val pageSize = if (isMoreDatesPage) bookingDateMorePageSize else bookingDateFirstPageSize
        val buttonsPerRow = if (isMoreDatesPage) 2 else 1
        val today = currentBookingDateBase()
        val dates =
            (0..bookingDateMaxDaysAhead)
                .map { dayOffset -> today.plusDays(dayOffset.toLong()) }
                .drop(normalizedOffset)
                .take(pageSize)
        if (dates.isEmpty()) {
            enqueueMessage(chatId, "Ближайшие даты недоступны. Попробуйте начать заново.")
            enqueueMessage(
                chatId,
                "Выберите действие.",
                TelegramKeyboards.inlineBotVenueCardActions(venue.id, buildVenueRouteUrl(venue)),
            )
            return
        }
        val dateButtons =
            dates.map { date ->
                buildBookingDateButtonLabel(date, today) to date.toString()
            }
        val nextOffsetCandidate = normalizedOffset + dates.size
        val nextOffset =
            nextOffsetCandidate.takeIf { next ->
                next <= bookingDateMaxDaysAhead
            }
        val backCallbackData =
            if (isMoreDatesPage) {
                "bot_catalog_venue_book:${venue.id}"
            } else {
                "bot_catalog_venue:${venue.id}"
            }
        sendBotBookingScreen(
            chatId = chatId,
            venueId = venue.id,
            text = "🪑 ${venue.name}\n$prompt",
            replyMarkup =
                TelegramKeyboards.inlineBotVenueBookingDateActions(
                    venueId = venue.id,
                    dateButtons = dateButtons,
                    nextOffset = nextOffset,
                    buttonsPerRow = buttonsPerRow,
                    backCallbackData = backCallbackData,
                ),
        )
    }

    private suspend fun showBotVenueMenuEntry(
        chatId: Long,
        data: String,
    ) {
        val venue = loadCatalogVenueFromCallback(chatId, data, "bot_catalog_venue_menu:") ?: return
        val menu =
            try {
                guestMenuRepository.getMenu(venue.id)
            } catch (e: DatabaseUnavailableException) {
                enqueueMessage(chatId, "База недоступна, попробуйте позже.")
                return
            }
        val sections = menu.categories.map { it.id to it.name }
        if (sections.isEmpty()) {
            enqueueMessage(
                chatId,
                "Меню заведения пока не загружено.",
                TelegramKeyboards.inlineBotVenueCardActions(venue.id, buildVenueRouteUrl(venue)),
            )
            return
        }
        enqueueMessage(
            chatId,
            "🍽 Меню «${venue.name}»\nВыберите раздел.",
            TelegramKeyboards.inlineBotVenueMenuSections(venue.id, sections),
        )
    }

    private suspend fun showBotVenueMenuSection(
        chatId: Long,
        data: String,
    ) {
        val parsed = parseBotVenueMenuSectionData(data)
        if (parsed == null) {
            enqueueMessage(chatId, "Не удалось открыть раздел меню. Попробуйте ещё раз.")
            return
        }
        val (venueId, categoryId) = parsed
        val venue = loadCatalogVenueById(chatId, venueId) ?: return
        val menu =
            try {
                guestMenuRepository.getMenu(venue.id)
            } catch (e: DatabaseUnavailableException) {
                enqueueMessage(chatId, "База недоступна, попробуйте позже.")
                return
            }
        val category =
            menu.categories.firstOrNull { it.id == categoryId }
                ?: run {
                    enqueueMessage(chatId, "Раздел меню не найден. Выберите другой раздел.")
                    enqueueMessage(
                        chatId,
                        "🍽 Меню «${venue.name}»\nВыберите раздел.",
                        TelegramKeyboards.inlineBotVenueMenuSections(
                            venue.id,
                            menu.categories.map { it.id to it.name },
                        ),
                    )
                    return
                }
        val imageUrls =
            try {
                venueMenuSectionImagesRepository.listImageUrlsForCategory(venue.id, category.id)
            } catch (e: DatabaseUnavailableException) {
                enqueueMessage(chatId, "База недоступна, попробуйте позже.")
                return
            }
        if (imageUrls.isEmpty()) {
            enqueueMessage(
                chatId,
                "Меню раздела «${category.name}» пока не загружено.",
                TelegramKeyboards.inlineBotVenueMenuSectionActions(venue.id),
            )
            return
        }
        imageUrls.forEachIndexed { index, imageUrl ->
            val caption =
                if (index == 0) {
                    "🍽 ${venue.name}\nРаздел: ${category.name}"
                } else {
                    null
                }
            val replyMarkup =
                if (index == imageUrls.lastIndex) {
                    TelegramKeyboards.inlineBotVenueMenuSectionActions(venue.id)
                } else {
                    null
                }
            enqueuePhoto(chatId, imageUrl, caption, replyMarkup)
        }
    }

    private suspend fun showBotVenueAbout(
        chatId: Long,
        data: String,
    ) {
        val venue = loadCatalogVenueFromCallback(chatId, data, "bot_catalog_venue_about:") ?: return
        val visibleSections =
            try {
                venueInfoSectionsRepository
                    .listSections(venue.id)
                    .filter { it.isVisible }
            } catch (e: DatabaseUnavailableException) {
                enqueueMessage(chatId, "База недоступна, попробуйте позже.")
                return
            }
        enqueueMessage(
            chatId,
            buildString {
                append("ℹ️ О заведении")
                append("\nВыберите раздел.")
                if (visibleSections.isEmpty()) {
                    append("\n\nРазделы пока не заполнены.")
                }
            },
            TelegramKeyboards.inlineBotVenueAboutSections(
                venue.id,
                visibleSections.map { section -> section.id to section.title },
            ),
        )
    }

    private suspend fun showBotVenueAboutSection(
        chatId: Long,
        data: String,
    ) {
        val parsed = parseBotVenueAboutSectionData(data)
        if (parsed == null) {
            enqueueMessage(chatId, "Не удалось открыть раздел. Попробуйте ещё раз.")
            return
        }
        val (venueId, sectionId) = parsed
        val venue = loadCatalogVenueById(chatId, venueId) ?: return
        val section =
            try {
                venueInfoSectionsRepository.findSectionById(venue.id, sectionId)
            } catch (e: DatabaseUnavailableException) {
                enqueueMessage(chatId, "База недоступна, попробуйте позже.")
                return
            }
        if (section == null || !section.isVisible) {
            enqueueMessage(chatId, "Раздел недоступен. Выберите другой раздел.")
            showBotVenueAbout(chatId, "bot_catalog_venue_about:${venue.id}")
            return
        }
        val mediaAttachments =
            try {
                venueInfoSectionMediaRepository.listBySectionId(section.id)
            } catch (e: DatabaseUnavailableException) {
                enqueueMessage(chatId, "База недоступна, попробуйте позже.")
                return
            }
        val supportedMedia =
            mediaAttachments.filter { attachment ->
                attachment.telegramFileId.isNotBlank() &&
                    (attachment.mediaType.equals("image", ignoreCase = true) ||
                        attachment.mediaType.equals("pdf", ignoreCase = true))
            }
        val sectionText = section.textContent?.trim()?.takeIf { it.isNotBlank() } ?: "Раздел пока не заполнен."
        if (supportedMedia.isEmpty()) {
            enqueueMessage(
                chatId,
                "ℹ️ ${section.title}\n\n$sectionText",
                TelegramKeyboards.inlineBotVenueAboutSectionActions(venue.id),
            )
            return
        }
        enqueueMessage(chatId, "ℹ️ ${section.title}\n\n$sectionText")
        supportedMedia.forEachIndexed { index, attachment ->
            val replyMarkup =
                if (index == supportedMedia.lastIndex) {
                    TelegramKeyboards.inlineBotVenueAboutSectionActions(venue.id)
                } else {
                    null
                }
            if (attachment.mediaType.equals("image", ignoreCase = true)) {
                enqueuePhoto(chatId, attachment.telegramFileId, caption = null, replyMarkup = replyMarkup)
            } else {
                enqueueDocument(chatId, attachment.telegramFileId, caption = null, replyMarkup = replyMarkup)
            }
        }
    }

    private suspend fun showBotVenueAddress(
        chatId: Long,
        data: String,
    ) {
        val venue = loadCatalogVenueFromCallback(chatId, data, "bot_catalog_venue_address:") ?: return
        val routeUrl = buildVenueRouteUrl(venue)
        enqueueMessage(
            chatId,
            "🗺 Маршрут до «${venue.name}»:\n$routeUrl",
            TelegramKeyboards.inlineBotVenueCardActions(venue.id, routeUrl),
        )
    }

    private suspend fun showMyOrdersAndBookings(
        chatId: Long,
        from: User?,
    ) {
        val userId = from?.id ?: chatContextRepository.get(chatId)?.userId
        if (userId == null) {
            enqueueMessage(chatId, "Не удалось определить пользователя. Нажмите /start и попробуйте снова.")
            return
        }
        val bookings =
            try {
                guestBookingRepository.listActiveByUser(userId = userId, limit = 5)
            } catch (e: DatabaseUnavailableException) {
                enqueueMessage(chatId, "База недоступна, попробуйте позже.")
                return
            }
        val activeOrders =
            try {
                ordersRepository.listActiveOrderSummariesForUser(userId = userId, limit = 5)
            } catch (e: DatabaseUnavailableException) {
                logger.warn("failed to load my orders and bookings for user_id={}", userId, e)
                enqueueMessage(chatId, "База недоступна, попробуйте позже.")
                return
            }
        if (bookings.isEmpty() && activeOrders.isEmpty()) {
            enqueueMessage(chatId, "У вас пока нет активных заказов и броней.")
            return
        }
        enqueueMessage(chatId, "📄 Мои заказы и брони")
        if (activeOrders.isNotEmpty()) {
            activeOrders.forEach { order ->
                enqueueMessage(chatId, buildMyOrderText(order))
            }
        }
        if (bookings.isNotEmpty()) {
            bookings.forEach { booking ->
                enqueueMessage(
                    chatId,
                    buildMyBookingText(booking),
                    TelegramKeyboards.inlineBotMyBookingActions(
                        bookingId = booking.id,
                        venueId = booking.venueId,
                    ),
                )
            }
        }
    }

    private suspend fun editMyBooking(
        chatId: Long,
        userId: Long,
        data: String,
    ) {
        val parsed = parseBotMyBookingActionData(data, "bot_my_booking_edit:")
        if (parsed == null) {
            enqueueMessage(chatId, "Не удалось открыть изменение брони. Попробуйте ещё раз.")
            return
        }
        val (bookingId, venueId) = parsed
        val booking =
            try {
                guestBookingRepository.findActiveByGuest(
                    bookingId = bookingId,
                    venueId = venueId,
                    userId = userId,
                )
            } catch (e: DatabaseUnavailableException) {
                enqueueMessage(chatId, "База недоступна, попробуйте позже.")
                return
            }
        if (booking == null) {
            enqueueMessage(chatId, "Не удалось открыть изменение брони. Возможно, она уже неактуальна.")
            return
        }
        val venue = loadCatalogVenueById(chatId, venueId) ?: return
        botBookingEditContexts[chatId] =
            BotBookingEditContext(
                bookingId = bookingId,
                venueId = venueId,
                userId = userId,
            )
        renderBotVenueBookingDatePicker(
            chatId = chatId,
            venue = venue,
            offset = 0,
            prompt = "Выберите новую дату брони #$bookingId.",
        )
    }

    private suspend fun cancelMyBooking(
        chatId: Long,
        userId: Long,
        data: String,
    ) {
        val parsed = parseBotMyBookingActionData(data, "bot_my_booking_cancel:")
        if (parsed == null) {
            enqueueMessage(chatId, "Не удалось отменить бронь. Попробуйте ещё раз.")
            return
        }
        val (bookingId, venueId) = parsed
        val canceled =
            try {
                guestBookingRepository.cancelByGuest(
                    bookingId = bookingId,
                    venueId = venueId,
                    userId = userId,
                )
            } catch (e: DatabaseUnavailableException) {
                enqueueMessage(chatId, "База недоступна, попробуйте позже.")
                return
            }
        if (canceled == null) {
            enqueueMessage(chatId, "Не удалось отменить бронь. Возможно, она уже неактуальна.")
            return
        }
        val editing = botBookingEditContexts[chatId]
        if (editing?.bookingId == bookingId) {
            botBookingEditContexts.remove(chatId)
        }
        enqueueMessage(chatId, "✅ Бронь #$bookingId отменена.")
    }

    private fun buildMyBookingText(booking: com.hookah.platform.backend.miniapp.guest.db.UserBookingSummaryRecord): String {
        val scheduledAt =
            LocalDateTime.ofInstant(booking.scheduledAt, ZoneId.systemDefault())
                .format(bookingDateTimeFormatter)
        return buildString {
            append("Бронь #${booking.id}")
            append("\nКальянная: ${booking.venueName}")
            append("\nДата и время: $scheduledAt")
            append("\nГостей: ${booking.partySize ?: "—"}")
            append("\nСтатус: ${humanizeBookingStatus(booking.status)}")
        }
    }

    private fun buildMyOrderText(order: com.hookah.platform.backend.telegram.db.UserActiveOrderSummary): String =
        buildString {
            append(formatTelegramOrderDisplayLabel(order.orderId, order.displayNumber, order.displayDate))
            append("\nКальянная: ${order.venueName}")
            append("\nСтатус: ${humanizeOrderStatus(order.status)}")
            humanizeBillType(order.tabType)?.let { billType ->
                append("\nСчёт: $billType")
            }
            append("\n\nСостав:")
            if (order.items.isEmpty()) {
                append("\n• —")
            } else {
                order.items.forEach { item ->
                    append("\n• ${item.itemName} ×${item.qty}")
                    buildGuestOrderItemPriceText(
                        priceMinor = item.priceMinor,
                        currency = item.currency,
                        qty = item.qty,
                        discountPercent = item.discountPercent,
                    )?.let { priceText -> append(" — $priceText") }
                }
            }
            append("\n\nИтого: ")
            append(formatGuestOrderSummaryTotal(order.items))
        }

    private fun formatTelegramOrderDisplayLabel(
        orderId: Long,
        displayNumber: Int?,
        displayDate: LocalDate?,
    ): String {
        val number = displayNumber ?: return "Заказ #$orderId"
        val suffix =
            when (displayDate) {
                null -> ""
                LocalDate.now(ZoneId.systemDefault()) -> " · сегодня"
                else -> " · ${displayDate.format(bookingDateConfirmFormatter)}"
            }
        return "Заказ №$number$suffix"
    }

    private fun buildGuestActiveOrderText(
        order: ActiveOrderDetails,
        fallbackItemNames: Map<Long, String>,
    ): String =
        buildString {
            val items = order.batches.flatMap { batch -> batch.items }
            append("Ваш заказ")
            append("\n")
            append("Статус: ${humanizeOrderStatus(order.status)}")
            if (items.isEmpty()) {
                append("\n\nПока без позиций.")
            } else {
                append("\n\n")
                append(
                    items.joinToString(separator = "\n") { item ->
                        val itemName = item.itemName ?: fallbackItemNames[item.itemId] ?: "item#${item.itemId}"
                        buildString {
                            append("- $itemName ×${item.qty}")
                            buildGuestOrderItemPriceText(
                                priceMinor = item.priceMinor,
                                currency = item.currency,
                                qty = item.qty,
                                discountPercent = item.discountPercent,
                            )?.let { priceText -> append(" — $priceText") }
                        }
                    },
                )
            }
            append("\n\nИтого: ")
            append(formatGuestActiveOrderTotal(items))
        }

    private fun buildGuestOrderItemPriceText(
        priceMinor: Long?,
        currency: String?,
        qty: Int,
        discountPercent: Int?,
    ): String? {
        val price = priceMinor ?: return null
        val itemCurrency = currency?.takeIf { it.isNotBlank() } ?: return null
        val baseTotalMinor = price * qty
        val discount = discountPercent?.takeIf { it in 1..100 }
        return if (discount == null) {
            formatPrice(baseTotalMinor, itemCurrency)
        } else {
            val discountedTotalMinor = applyDiscountPercent(baseTotalMinor, discount)
            "${formatPrice(baseTotalMinor, itemCurrency)}, скидка $discount%, к оплате ${formatPrice(discountedTotalMinor, itemCurrency)}"
        }
    }

    private fun formatGuestActiveOrderTotal(items: List<OrderBatchItemDetails>): String {
        var totalMinor = 0L
        var totalCurrency: String? = null
        items.forEach { item ->
            val payableMinor =
                guestOrderItemPayableMinor(
                    priceMinor = item.priceMinor,
                    qty = item.qty,
                    discountPercent = item.discountPercent,
                )
            if (payableMinor != null && !item.currency.isNullOrBlank()) {
                totalMinor += payableMinor
                if (totalCurrency == null) {
                    totalCurrency = item.currency
                }
            }
        }
        val currency = totalCurrency ?: return "—"
        return formatPrice(totalMinor, currency)
    }

    private fun formatGuestOrderSummaryTotal(items: List<UserActiveOrderItemSummary>): String {
        var totalMinor = 0L
        var totalCurrency: String? = null
        items.forEach { item ->
            val payableMinor =
                guestOrderItemPayableMinor(
                    priceMinor = item.priceMinor,
                    qty = item.qty,
                    discountPercent = item.discountPercent,
                )
            if (payableMinor != null && !item.currency.isNullOrBlank()) {
                totalMinor += payableMinor
                if (totalCurrency == null) {
                    totalCurrency = item.currency
                }
            }
        }
        val currency = totalCurrency ?: return "—"
        return formatPrice(totalMinor, currency)
    }

    private fun guestOrderItemPayableMinor(
        priceMinor: Long?,
        qty: Int,
        discountPercent: Int?,
    ): Long? {
        val price = priceMinor ?: return null
        val baseTotalMinor = price * qty
        val discount = discountPercent?.takeIf { it in 1..100 }
        return if (discount == null) baseTotalMinor else applyDiscountPercent(baseTotalMinor, discount)
    }

    private suspend fun showMiniAppEntry(chatId: Long) {
        val url = config.webAppPublicUrl?.takeIf { it.isNotBlank() }
        if (url == null) {
            enqueueMessage(chatId, "Mini App временно недоступен. Попробуйте позже.")
            return
        }
        enqueueMessage(chatId, "Откройте Mini App: ${buildWebAppUrl(url, mapOf("screen" to "catalog"))}")
    }

    private suspend fun showPromotions(chatId: Long) {
        enqueueMessage(chatId, "Раздел «Акции» — следующий шаг.")
    }

    private suspend fun showAddVenueEntry(
        chatId: Long,
        from: User?,
    ) {
        val userId = from?.id ?: chatContextRepository.get(chatId)?.userId
        if (userId == null) {
            enqueueMessage(chatId, "Не удалось определить пользователя. Нажмите /start и попробуйте снова.")
            return
        }
        val pending =
            try {
                venueConnectionRequestRepository.findPendingByUser(userId)
            } catch (e: DatabaseUnavailableException) {
                enqueueMessage(chatId, "База недоступна, попробуйте позже.")
                return
            }
        if (pending != null) {
            showApplicantPendingVenueConnectionRequest(chatId, pending)
            return
        }
        dialogStateRepository.set(chatId, DialogState(DialogStateType.VENUE_CONNECT_WAIT_NAME))
        enqueueMessage(
            chatId,
            "Заявка на подключение кальянной.\nВведите название кальянной.",
        )
    }

    private suspend fun proceedVenueConnectionName(
        chatId: Long,
        text: String,
        state: DialogState,
    ) {
        val venueName = normalizeVenueConnectionRequiredField(text, maxLength = 200)
        if (venueName == null) {
            enqueueMessage(chatId, "Введите корректное название кальянной (до 200 символов).")
            return
        }
        val editingRequestId = state.payload["editing_request_id"]
        val payload =
            buildMap {
                put("venue_name", venueName)
                if (!editingRequestId.isNullOrBlank()) {
                    put("editing_request_id", editingRequestId)
                }
            }
        dialogStateRepository.set(
            chatId,
            DialogState(
                DialogStateType.VENUE_CONNECT_WAIT_CITY,
                payload = payload,
            ),
        )
        enqueueMessage(chatId, "Введите город.")
    }

    private suspend fun proceedVenueConnectionCity(
        chatId: Long,
        from: User?,
        text: String,
        state: DialogState,
    ) {
        val venueName = state.payload["venue_name"]
        val editingRequestId = state.payload["editing_request_id"]
        if (venueName.isNullOrBlank()) {
            dialogStateRepository.clear(chatId)
            enqueueMessage(chatId, "Не удалось продолжить заявку. Нажмите «🤝 Добавить свою кальянную» ещё раз.")
            return
        }
        val city = normalizeVenueConnectionRequiredField(text, maxLength = 120)
        if (city == null) {
            enqueueMessage(chatId, "Введите корректный город (до 120 символов).")
            return
        }
        val username = from?.username?.trim()?.removePrefix("@")?.takeIf { it.isNotBlank() }
        if (username != null) {
            val baseContact = "@$username"
            val payload =
                buildMap {
                    put("venue_name", venueName)
                    put("city", city)
                    put("base_contact", baseContact)
                    if (!editingRequestId.isNullOrBlank()) {
                        put("editing_request_id", editingRequestId)
                    }
                }
            dialogStateRepository.set(
                chatId,
                DialogState(
                    DialogStateType.VENUE_CONNECT_WAIT_CONTACT_CHOICE,
                    payload = payload,
                ),
            )
            enqueueMessage(
                chatId,
                "Контакт для связи: $baseContact\nХотите добавить дополнительный контакт?",
                TelegramKeyboards.inlineVenueConnectionContactChoice(),
            )
            return
        }
        dialogStateRepository.set(
            chatId,
            DialogState(
                DialogStateType.VENUE_CONNECT_WAIT_CONTACT,
                payload =
                    buildMap {
                        put("venue_name", venueName)
                        put("city", city)
                        if (!editingRequestId.isNullOrBlank()) {
                            put("editing_request_id", editingRequestId)
                        }
                    },
            ),
        )
        enqueueMessage(chatId, "Укажите контакт для связи: телефон, email или другой удобный способ связи.")
    }

    private suspend fun proceedVenueConnectionContact(
        chatId: Long,
        text: String,
        state: DialogState,
    ) {
        val venueName = state.payload["venue_name"]
        val city = state.payload["city"]
        val editingRequestId = state.payload["editing_request_id"]
        if (venueName.isNullOrBlank() || city.isNullOrBlank()) {
            dialogStateRepository.clear(chatId)
            enqueueMessage(chatId, "Не удалось продолжить заявку. Нажмите «🤝 Добавить свою кальянную» ещё раз.")
            return
        }
        val contact = normalizeVenueConnectionRequiredField(text, maxLength = 200)
        if (contact == null) {
            enqueueMessage(chatId, "Введите корректный контакт (до 200 символов).")
            return
        }
        dialogStateRepository.set(
            chatId,
            DialogState(
                DialogStateType.VENUE_CONNECT_WAIT_COMMENT,
                payload =
                    buildMap {
                        put("venue_name", venueName)
                        put("city", city)
                        put("contact", contact)
                        if (!editingRequestId.isNullOrBlank()) {
                            put("editing_request_id", editingRequestId)
                        }
                    },
            ),
        )
        enqueueMessage(chatId, "Комментарий (необязательно). Отправьте текст или «-», чтобы пропустить.")
    }

    private suspend fun proceedVenueConnectionUseUsername(chatId: Long) {
        val state = dialogStateRepository.get(chatId)
        if (state.state != DialogStateType.VENUE_CONNECT_WAIT_CONTACT_CHOICE) {
            enqueueMessage(chatId, "Не удалось продолжить заявку. Нажмите «🤝 Добавить свою кальянную» ещё раз.")
            return
        }
        val venueName = state.payload["venue_name"]
        val city = state.payload["city"]
        val baseContact = state.payload["base_contact"]
        val editingRequestId = state.payload["editing_request_id"]
        if (venueName.isNullOrBlank() || city.isNullOrBlank() || baseContact.isNullOrBlank()) {
            dialogStateRepository.clear(chatId)
            enqueueMessage(chatId, "Не удалось продолжить заявку. Нажмите «🤝 Добавить свою кальянную» ещё раз.")
            return
        }
        dialogStateRepository.set(
            chatId,
            DialogState(
                DialogStateType.VENUE_CONNECT_WAIT_COMMENT,
                payload =
                    buildMap {
                        put("venue_name", venueName)
                        put("city", city)
                        put("contact", baseContact)
                        if (!editingRequestId.isNullOrBlank()) {
                            put("editing_request_id", editingRequestId)
                        }
                    },
            ),
        )
        enqueueMessage(chatId, "Комментарий (необязательно). Отправьте текст или «-», чтобы пропустить.")
    }

    private suspend fun promptVenueConnectionAdditionalContact(chatId: Long) {
        val state = dialogStateRepository.get(chatId)
        if (state.state != DialogStateType.VENUE_CONNECT_WAIT_CONTACT_CHOICE) {
            enqueueMessage(chatId, "Не удалось продолжить заявку. Нажмите «🤝 Добавить свою кальянную» ещё раз.")
            return
        }
        val venueName = state.payload["venue_name"]
        val city = state.payload["city"]
        val baseContact = state.payload["base_contact"]
        val editingRequestId = state.payload["editing_request_id"]
        if (venueName.isNullOrBlank() || city.isNullOrBlank() || baseContact.isNullOrBlank()) {
            dialogStateRepository.clear(chatId)
            enqueueMessage(chatId, "Не удалось продолжить заявку. Нажмите «🤝 Добавить свою кальянную» ещё раз.")
            return
        }
        dialogStateRepository.set(
            chatId,
            DialogState(
                DialogStateType.VENUE_CONNECT_WAIT_CONTACT_EXTRA,
                payload =
                    buildMap {
                        put("venue_name", venueName)
                        put("city", city)
                        put("base_contact", baseContact)
                        if (!editingRequestId.isNullOrBlank()) {
                            put("editing_request_id", editingRequestId)
                        }
                    },
            ),
        )
        enqueueMessage(chatId, "Укажите дополнительный контакт (телефон или email).")
    }

    private suspend fun proceedVenueConnectionAdditionalContact(
        chatId: Long,
        text: String,
        state: DialogState,
    ) {
        val venueName = state.payload["venue_name"]
        val city = state.payload["city"]
        val baseContact = state.payload["base_contact"]
        val editingRequestId = state.payload["editing_request_id"]
        if (venueName.isNullOrBlank() || city.isNullOrBlank() || baseContact.isNullOrBlank()) {
            dialogStateRepository.clear(chatId)
            enqueueMessage(chatId, "Не удалось продолжить заявку. Нажмите «🤝 Добавить свою кальянную» ещё раз.")
            return
        }
        val extraContact = normalizeVenueConnectionRequiredField(text, maxLength = 200)
        if (extraContact == null) {
            enqueueMessage(chatId, "Введите корректный контакт (до 200 символов).")
            return
        }
        val combinedContact = "$baseContact | $extraContact"
        dialogStateRepository.set(
            chatId,
            DialogState(
                DialogStateType.VENUE_CONNECT_WAIT_COMMENT,
                payload =
                    buildMap {
                        put("venue_name", venueName)
                        put("city", city)
                        put("contact", combinedContact)
                        if (!editingRequestId.isNullOrBlank()) {
                            put("editing_request_id", editingRequestId)
                        }
                    },
            ),
        )
        enqueueMessage(chatId, "Комментарий (необязательно). Отправьте текст или «-», чтобы пропустить.")
    }

    private suspend fun proceedVenueConnectionComment(
        chatId: Long,
        from: User?,
        text: String,
        state: DialogState,
    ) {
        val venueName = state.payload["venue_name"]
        val city = state.payload["city"]
        val contact = state.payload["contact"]
        val editingRequestId = state.payload["editing_request_id"]?.toLongOrNull()
        if (venueName.isNullOrBlank() || city.isNullOrBlank() || contact.isNullOrBlank()) {
            dialogStateRepository.clear(chatId)
            enqueueMessage(chatId, "Не удалось продолжить заявку. Нажмите «🤝 Добавить свою кальянную» ещё раз.")
            return
        }
        val (comment, commentValid) = parseVenueConnectionOptionalComment(text)
        if (!commentValid) {
            enqueueMessage(chatId, "Комментарий слишком длинный. Максимум 500 символов.")
            return
        }
        val userId = from?.id ?: chatContextRepository.get(chatId)?.userId
        if (userId == null) {
            dialogStateRepository.clear(chatId)
            enqueueMessage(chatId, "Не удалось определить пользователя. Нажмите /start и попробуйте снова.")
            return
        }
        if (editingRequestId != null) {
            val updated =
                try {
                    venueConnectionRequestRepository.updatePendingRequest(
                        requestId = editingRequestId,
                        telegramUserId = userId,
                        venueName = venueName,
                        city = city,
                        contact = contact,
                        comment = comment,
                    )
                } catch (e: DatabaseUnavailableException) {
                    enqueueMessage(chatId, "База недоступна, попробуйте позже.")
                    return
                }
            if (!updated) {
                dialogStateRepository.clear(chatId)
                enqueueMessage(chatId, "Активная заявка не найдена. Нажмите «🤝 Добавить свою кальянную» ещё раз.")
                return
            }
            dialogStateRepository.clear(chatId)
            enqueueMessage(chatId, "✅ Заявка обновлена.")
            return
        }
        val existingPending =
            try {
                venueConnectionRequestRepository.findPendingByUser(userId)
            } catch (e: DatabaseUnavailableException) {
                enqueueMessage(chatId, "База недоступна, попробуйте позже.")
                return
            }
        if (existingPending != null) {
            dialogStateRepository.clear(chatId)
            showApplicantPendingVenueConnectionRequest(chatId, existingPending)
            return
        }
        val created =
            try {
                venueConnectionRequestRepository.createRequest(
                    telegramUserId = userId,
                    venueName = venueName,
                    city = city,
                    contact = contact,
                    comment = comment,
                )
            } catch (e: DatabaseUnavailableException) {
                enqueueMessage(chatId, "База недоступна, попробуйте позже.")
                return
            }
        if (created == null) {
            enqueueMessage(chatId, "Не удалось отправить заявку. Попробуйте ещё раз.")
            return
        }
        dialogStateRepository.clear(chatId)
        enqueueMessage(chatId, "✅ Заявка отправлена. Мы свяжемся с вами.")
    }

    private suspend fun startVenueConnectionEdit(
        chatId: Long,
        userId: Long,
        data: String,
    ) {
        val requestId = data.removePrefix("venue_connect_pending_edit:").toLongOrNull()
        if (requestId == null) {
            enqueueMessage(chatId, "Не удалось открыть заявку. Попробуйте ещё раз.")
            return
        }
        val request =
            try {
                venueConnectionRequestRepository.findPendingByIdForUser(requestId, userId)
            } catch (e: DatabaseUnavailableException) {
                enqueueMessage(chatId, "База недоступна, попробуйте позже.")
                return
            }
        if (request == null) {
            enqueueMessage(chatId, "Активная заявка не найдена.")
            return
        }
        dialogStateRepository.set(
            chatId,
            DialogState(
                DialogStateType.VENUE_CONNECT_WAIT_NAME,
                payload = mapOf("editing_request_id" to request.id.toString()),
            ),
        )
        enqueueMessage(
            chatId,
            "Изменение заявки #${request.id}.\nВведите новое название кальянной.",
        )
    }

    private suspend fun cancelVenueConnectionRequest(
        chatId: Long,
        userId: Long,
        data: String,
    ) {
        val requestId = data.removePrefix("venue_connect_pending_cancel:").toLongOrNull()
        if (requestId == null) {
            enqueueMessage(chatId, "Не удалось отменить заявку. Попробуйте ещё раз.")
            return
        }
        val cancelled =
            try {
                venueConnectionRequestRepository.cancelPendingRequest(requestId, userId)
            } catch (e: DatabaseUnavailableException) {
                enqueueMessage(chatId, "База недоступна, попробуйте позже.")
                return
            }
        if (!cancelled) {
            enqueueMessage(chatId, "Активная заявка не найдена.")
            return
        }
        dialogStateRepository.clear(chatId)
        enqueueMessage(chatId, "✅ Заявка отменена.")
    }

    private suspend fun handleOwnerVenueConnectionDecision(
        chatId: Long,
        userId: Long,
        data: String,
        approved: Boolean,
    ) {
        if (!isPlatformOwner(userId)) {
            enqueueMessage(chatId, "Недостаточно прав.")
            return
        }
        val prefix = if (approved) "owner_venue_connect_approve:" else "owner_venue_connect_reject:"
        val requestId = data.removePrefix(prefix).toLongOrNull()
        if (requestId == null) {
            enqueueMessage(chatId, "Не удалось обработать заявку. Попробуйте ещё раз.")
            return
        }
        val status =
            if (approved) {
                VenueConnectionRequestRepository.STATUS_APPROVED
            } else {
                VenueConnectionRequestRepository.STATUS_REJECTED
            }
        val changed =
            try {
                venueConnectionRequestRepository.setStatusByOwner(requestId, status)
            } catch (e: DatabaseUnavailableException) {
                enqueueMessage(chatId, "База недоступна, попробуйте позже.")
                return
            }
        if (!changed) {
            enqueueMessage(chatId, "Заявка уже обработана или не найдена.")
            return
        }
        if (approved) {
            enqueueMessage(
                chatId,
                "✅ Заявка принята.\nСледующий шаг: коммерческие условия.",
                TelegramKeyboards.inlineOwnerVenueConnectionApprovedActions(requestId),
            )
            return
        }
        enqueueMessage(chatId, "❌ Заявка #$requestId отклонена.")
        showOwnerVenueConnectionRequests(chatId)
    }

    private suspend fun startOwnerVenueCommercialTerms(
        chatId: Long,
        userId: Long,
        data: String,
    ) {
        if (!isPlatformOwner(userId)) {
            enqueueMessage(chatId, "Недостаточно прав.")
            return
        }
        val requestId = data.removePrefix("owner_venue_terms_open:").toLongOrNull()
        if (requestId == null) {
            enqueueMessage(chatId, "Не удалось открыть коммерческие условия. Попробуйте ещё раз.")
            return
        }
        val request =
            try {
                venueConnectionRequestRepository.findById(requestId)
            } catch (e: DatabaseUnavailableException) {
                enqueueMessage(chatId, "База недоступна, попробуйте позже.")
                return
            }
        if (request == null) {
            enqueueMessage(chatId, "Заявка не найдена.")
            return
        }
        if (!request.status.equals(VenueConnectionRequestRepository.STATUS_APPROVED, ignoreCase = true)) {
            enqueueMessage(chatId, "Коммерческие условия доступны только для принятых заявок.")
            return
        }
        if (request.linkedVenueId != null) {
            enqueueMessage(chatId, "Черновик уже создан: venue #${request.linkedVenueId}.")
            return
        }
        dialogStateRepository.clear(chatId)
        enqueueMessage(
            chatId,
            buildOwnerCommercialTermsPromptText(request),
            TelegramKeyboards.inlineOwnerVenueCommercialTrialActions(request.id),
        )
    }

    private suspend fun proceedOwnerVenueTermsTrialChoice(
        chatId: Long,
        userId: Long,
        data: String,
    ) {
        if (!isPlatformOwner(userId)) {
            enqueueMessage(chatId, "Недостаточно прав.")
            return
        }
        val payload = data.removePrefix("owner_venue_terms_trial:")
        val parts = payload.split(":", limit = 2)
        val requestId = parts.getOrNull(0)?.toLongOrNull()
        val trialToken = parts.getOrNull(1)
        if (requestId == null || trialToken.isNullOrBlank()) {
            enqueueMessage(chatId, "Не удалось сохранить пробный период. Попробуйте ещё раз.")
            return
        }
        if (trialToken == "custom") {
            dialogStateRepository.set(
                chatId,
                DialogState(
                    DialogStateType.OWNER_VENUE_TERMS_WAIT_TRIAL_CUSTOM_DATE,
                    payload = mapOf("request_id" to requestId.toString()),
                ),
            )
            enqueueMessage(chatId, "Введите дату в формате дд.мм.гггг, например 15.04.2026.")
            return
        }
        val trialEndsOn =
            when (trialToken) {
                "none" -> null
                "7d" -> currentBookingDateBase().plusDays(7)
                "14d" -> currentBookingDateBase().plusDays(14)
                "1m" -> currentBookingDateBase().plusMonths(1)
                "3m" -> currentBookingDateBase().plusMonths(3)
                else -> {
                    enqueueMessage(chatId, "Не удалось сохранить пробный период. Попробуйте ещё раз.")
                    return
                }
            }
        dialogStateRepository.set(
            chatId,
            DialogState(
                DialogStateType.OWNER_VENUE_TERMS_WAIT_CURRENT_PRICE,
                payload =
                    buildMap {
                        put("request_id", requestId.toString())
                        put("trial_configured", "true")
                        trialEndsOn?.let { put("trial_ends_on", it.toString()) }
                    },
            ),
        )
        enqueueMessage(chatId, "Укажите стоимость после trial, ₽/мес (только число).")
    }

    private suspend fun proceedOwnerVenueTermsCustomTrialDate(
        chatId: Long,
        from: User?,
        text: String,
        state: DialogState,
    ) {
        if (!isPlatformOwner(from?.id)) {
            enqueueMessage(chatId, "Недостаточно прав.")
            return
        }
        val requestId = state.payload["request_id"]?.toLongOrNull()
        if (requestId == null) {
            dialogStateRepository.clear(chatId)
            enqueueMessage(chatId, "Не удалось продолжить. Откройте «Коммерческие условия» заново.")
            return
        }
        val trialEndsOn = parseIsoLocalDate(text)
        if (trialEndsOn == null) {
            enqueueMessage(chatId, "Некорректная дата. Введите в формате дд.мм.гггг, например 15.04.2026.")
            return
        }
        dialogStateRepository.set(
            chatId,
            DialogState(
                DialogStateType.OWNER_VENUE_TERMS_WAIT_CURRENT_PRICE,
                payload =
                    mapOf(
                        "request_id" to requestId.toString(),
                        "trial_configured" to "true",
                        "trial_ends_on" to trialEndsOn.toString(),
                    ),
            ),
        )
        enqueueMessage(chatId, "Укажите стоимость после trial, ₽/мес (только число).")
    }

    private suspend fun proceedOwnerVenueTermsCurrentPrice(
        chatId: Long,
        from: User?,
        text: String,
        state: DialogState,
    ) {
        if (!isPlatformOwner(from?.id)) {
            enqueueMessage(chatId, "Недостаточно прав.")
            return
        }
        val requestId = state.payload["request_id"]?.toLongOrNull()
        if (requestId == null) {
            dialogStateRepository.clear(chatId)
            enqueueMessage(chatId, "Не удалось продолжить. Откройте «Коммерческие условия» заново.")
            return
        }
        val currentPriceRub = parsePositiveRubAmount(text)
        if (currentPriceRub == null) {
            enqueueMessage(chatId, "Введите корректную стоимость после trial в ₽/мес (только число).")
            return
        }
        val payload =
            buildMap {
                put("request_id", requestId.toString())
                put("trial_configured", state.payload["trial_configured"] ?: "true")
                state.payload["trial_ends_on"]?.let { put("trial_ends_on", it) }
                put("current_price_rub", currentPriceRub.toString())
            }
        dialogStateRepository.set(chatId, DialogState(DialogStateType.OWNER_VENUE_TERMS_WAIT_FUTURE_PRICE, payload = payload))
        enqueueMessage(
            chatId,
            "Будущую стоимость задаём?",
            TelegramKeyboards.inlineOwnerVenueCommercialFuturePriceActions(requestId),
        )
    }

    private suspend fun proceedOwnerVenueTermsFutureChoice(
        chatId: Long,
        userId: Long,
        data: String,
    ) {
        if (!isPlatformOwner(userId)) {
            enqueueMessage(chatId, "Недостаточно прав.")
            return
        }
        val payload = data.removePrefix("owner_venue_terms_future:")
        val parts = payload.split(":", limit = 2)
        val requestId = parts.getOrNull(0)?.toLongOrNull()
        val decision = parts.getOrNull(1)
        if (requestId == null || decision.isNullOrBlank()) {
            enqueueMessage(chatId, "Не удалось сохранить выбор. Попробуйте ещё раз.")
            return
        }
        val state = dialogStateRepository.get(chatId)
        if (state.state != DialogStateType.OWNER_VENUE_TERMS_WAIT_FUTURE_PRICE) {
            enqueueMessage(chatId, "Сессия условий устарела. Откройте «Коммерческие условия» заново.")
            return
        }
        val stateRequestId = state.payload["request_id"]?.toLongOrNull()
        if (stateRequestId == null || stateRequestId != requestId) {
            enqueueMessage(chatId, "Сессия условий устарела. Откройте «Коммерческие условия» заново.")
            return
        }
        if (decision == "skip") {
            dialogStateRepository.set(
                chatId,
                DialogState(
                    DialogStateType.OWNER_VENUE_TERMS_WAIT_NOTE,
                    payload =
                        buildMap {
                            put("request_id", requestId.toString())
                            put("trial_configured", state.payload["trial_configured"] ?: "true")
                            state.payload["trial_ends_on"]?.let { put("trial_ends_on", it) }
                            state.payload["current_price_rub"]?.let { put("current_price_rub", it) }
                        },
                ),
            )
            enqueueMessage(chatId, "Добавьте заметку (необязательно). Отправьте текст или «-», чтобы пропустить.")
            return
        }
        if (decision != "set") {
            enqueueMessage(chatId, "Не удалось сохранить выбор. Попробуйте ещё раз.")
            return
        }
        dialogStateRepository.set(
            chatId,
            DialogState(
                DialogStateType.OWNER_VENUE_TERMS_WAIT_FUTURE_PRICE,
                payload = state.payload,
            ),
        )
        enqueueMessage(chatId, "Введите будущую стоимость, ₽/мес (только число).")
    }

    private suspend fun proceedOwnerVenueTermsFuturePrice(
        chatId: Long,
        from: User?,
        text: String,
        state: DialogState,
    ) {
        if (!isPlatformOwner(from?.id)) {
            enqueueMessage(chatId, "Недостаточно прав.")
            return
        }
        val requestId = state.payload["request_id"]?.toLongOrNull()
        if (requestId == null) {
            dialogStateRepository.clear(chatId)
            enqueueMessage(chatId, "Не удалось продолжить. Откройте «Коммерческие условия» заново.")
            return
        }
        val futurePriceRub = parsePositiveRubAmount(text)
        if (futurePriceRub == null) {
            enqueueMessage(chatId, "Введите корректную сумму в ₽/мес (только число).")
            return
        }
        dialogStateRepository.set(
            chatId,
            DialogState(
                DialogStateType.OWNER_VENUE_TERMS_WAIT_FUTURE_PRICE_DATE,
                payload =
                    buildMap {
                        put("request_id", requestId.toString())
                        put("trial_configured", state.payload["trial_configured"] ?: "true")
                        state.payload["trial_ends_on"]?.let { put("trial_ends_on", it) }
                        state.payload["current_price_rub"]?.let { put("current_price_rub", it) }
                        put("future_price_rub", futurePriceRub.toString())
                    },
            ),
        )
        enqueueMessage(chatId, "Введите дату вступления новой стоимости в формате дд.мм.гггг, например 15.04.2026.")
    }

    private suspend fun proceedOwnerVenueTermsFuturePriceDate(
        chatId: Long,
        from: User?,
        text: String,
        state: DialogState,
    ) {
        if (!isPlatformOwner(from?.id)) {
            enqueueMessage(chatId, "Недостаточно прав.")
            return
        }
        val requestId = state.payload["request_id"]?.toLongOrNull()
        if (requestId == null) {
            dialogStateRepository.clear(chatId)
            enqueueMessage(chatId, "Не удалось продолжить. Откройте «Коммерческие условия» заново.")
            return
        }
        val futureEffectiveOn = parseIsoLocalDate(text)
        if (futureEffectiveOn == null) {
            enqueueMessage(chatId, "Некорректная дата. Введите в формате дд.мм.гггг, например 15.04.2026.")
            return
        }
        dialogStateRepository.set(
            chatId,
            DialogState(
                DialogStateType.OWNER_VENUE_TERMS_WAIT_NOTE,
                payload =
                    buildMap {
                        put("request_id", requestId.toString())
                        put("trial_configured", state.payload["trial_configured"] ?: "true")
                        state.payload["trial_ends_on"]?.let { put("trial_ends_on", it) }
                        state.payload["current_price_rub"]?.let { put("current_price_rub", it) }
                        state.payload["future_price_rub"]?.let { put("future_price_rub", it) }
                        put("future_price_effective_on", futureEffectiveOn.toString())
                    },
            ),
        )
        enqueueMessage(chatId, "Добавьте заметку (необязательно). Отправьте текст или «-», чтобы пропустить.")
    }

    private suspend fun proceedOwnerVenueTermsNote(
        chatId: Long,
        from: User?,
        text: String,
        state: DialogState,
    ) {
        if (!isPlatformOwner(from?.id)) {
            enqueueMessage(chatId, "Недостаточно прав.")
            return
        }
        val requestId = state.payload["request_id"]?.toLongOrNull()
        val trialConfigured = state.payload["trial_configured"]?.toBooleanStrictOrNull() ?: false
        val trialEndsOn = state.payload["trial_ends_on"]?.let { parseIsoLocalDate(it) }
        val currentPriceRub = state.payload["current_price_rub"]?.toLongOrNull()
        val futurePriceRub = state.payload["future_price_rub"]?.toLongOrNull()
        val futurePriceEffectiveOn = state.payload["future_price_effective_on"]?.let { parseIsoLocalDate(it) }
        if (requestId == null || currentPriceRub == null || !trialConfigured) {
            dialogStateRepository.clear(chatId)
            enqueueMessage(chatId, "Не удалось сохранить условия. Откройте «Коммерческие условия» заново.")
            return
        }
        if ((futurePriceRub == null) != (futurePriceEffectiveOn == null)) {
            dialogStateRepository.clear(chatId)
            enqueueMessage(chatId, "Не удалось сохранить условия. Заполните будущую стоимость и дату повторно.")
            return
        }
        val note = parseOwnerCommercialOptionalNote(text)
        if (note == null && text.trim() !in setOf("", "-", "—")) {
            enqueueMessage(chatId, "Заметка слишком длинная. Максимум 1000 символов.")
            return
        }
        val updated =
            try {
                venueConnectionRequestRepository.updateCommercialTerms(
                    requestId = requestId,
                    trialConfigured = trialConfigured,
                    trialEndsOn = trialEndsOn,
                    currentPriceRub = currentPriceRub,
                    futurePriceRub = futurePriceRub,
                    futurePriceEffectiveOn = futurePriceEffectiveOn,
                    commercialNote = note,
                )
            } catch (e: DatabaseUnavailableException) {
                enqueueMessage(chatId, "База недоступна, попробуйте позже.")
                return
            }
        if (!updated) {
            dialogStateRepository.clear(chatId)
            enqueueMessage(chatId, "Не удалось сохранить условия. Заявка не найдена или уже обработана.")
            return
        }
        val request =
            try {
                venueConnectionRequestRepository.findById(requestId)
            } catch (e: DatabaseUnavailableException) {
                enqueueMessage(chatId, "База недоступна, попробуйте позже.")
                return
            }
        dialogStateRepository.clear(chatId)
        if (request == null) {
            enqueueMessage(chatId, "Условия сохранены.")
            return
        }
        enqueueMessage(
            chatId,
            buildOwnerCommercialTermsSummaryText(request),
            TelegramKeyboards.inlineOwnerVenueCommercialSummaryActions(request.id),
        )
    }

    private suspend fun startOwnerCreateVenueFromApprovedRequest(
        chatId: Long,
        userId: Long,
        data: String,
    ) {
        if (!isPlatformOwner(userId)) {
            enqueueMessage(chatId, "Недостаточно прав.")
            return
        }
        val requestId = data.removePrefix("owner_venue_create_from_request:").toLongOrNull()
        if (requestId == null) {
            enqueueMessage(chatId, "Не удалось открыть создание кальянной. Попробуйте ещё раз.")
            return
        }
        val request =
            try {
                venueConnectionRequestRepository.findById(requestId)
            } catch (e: DatabaseUnavailableException) {
                enqueueMessage(chatId, "База недоступна, попробуйте позже.")
                return
            }
        if (request == null) {
            enqueueMessage(chatId, "Заявка не найдена.")
            return
        }
        if (!request.status.equals(VenueConnectionRequestRepository.STATUS_APPROVED, ignoreCase = true)) {
            enqueueMessage(chatId, "Для создания черновика заявка должна быть в статусе «Принята».")
            return
        }
        if (!isCommercialTermsReady(request)) {
            enqueueMessage(
                chatId,
                "Сначала заполните коммерческие условия.",
                TelegramKeyboards.inlineOwnerVenueConnectionApprovedActions(request.id),
            )
            return
        }
        if (request.linkedVenueId != null) {
            enqueueMessage(
                chatId,
                "Черновик уже создан: venue #${request.linkedVenueId}. Владелец уже назначен.",
            )
            return
        }

        val createdVenue =
            try {
                platformVenueRepository.createVenue(
                    name = request.venueName,
                    city = request.city,
                    address = null,
                )
            } catch (e: DatabaseUnavailableException) {
                enqueueMessage(chatId, "База недоступна, попробуйте позже.")
                return
            }
        val assignment =
            try {
                platformVenueMemberRepository.assignOwner(
                    venueId = createdVenue.id,
                    userId = request.telegramUserId,
                    role = "OWNER",
                    invitedByUserId = userId,
                )
            } catch (e: Exception) {
                logBestEffort("owner venue assign from request", e)
                PlatformOwnerAssignmentResult.DatabaseError
            }
        if (assignment !is PlatformOwnerAssignmentResult.Success) {
            enqueueMessage(
                chatId,
                "Черновик venue #${createdVenue.id} создан, но назначить владельца не удалось. Проверьте вручную.",
            )
            return
        }
        runCatching {
            venueConnectionRequestRepository.linkApprovedRequestToVenue(
                requestId = request.id,
                venueId = createdVenue.id,
            )
        }.onFailure { e ->
            logBestEffort("link venue_connection_request to venue", e)
        }

        enqueueMessage(
            request.telegramUserId,
            "✅ Ваша заявка одобрена.\nТеперь вы можете настроить свою кальянную.",
            TelegramKeyboards.inlineVenueOwnerOnboardingEntry(config.webAppPublicUrl),
        )
        enqueueMessage(
            chatId,
            "✅ Черновик кальянной создан: #${createdVenue.id}\nВладелец назначен: ${request.telegramUserId}\nДоступ передан заявителю.",
        )
    }

    private suspend fun showApplicantPendingVenueConnectionRequest(
        chatId: Long,
        request: VenueConnectionRequestRecord,
    ) {
        enqueueMessage(
            chatId,
            buildApplicantVenueConnectionRequestText(request),
            TelegramKeyboards.inlineVenueConnectionPendingActions(request.id),
        )
    }

    private suspend fun showVenueOwnerVenueCard(
        chatId: Long,
        from: User?,
    ) {
        showVenueOwnerVenueCardByUserId(chatId, from?.id)
    }

    private suspend fun showVenueOwnerVenueCardByUserId(
        chatId: Long,
        userId: Long?,
    ) {
        if (userId == null) {
            enqueueMessage(chatId, "Не удалось определить пользователя. Попробуйте /start.")
            return
        }
        botVenuePreviewContexts.remove(chatId)
        val access = resolvePrimaryVenueBotAccess(userId)
        if (access == null) {
            enqueueMessage(chatId, "Доступ к заведению пока не найден.")
            return
        }
        if (access.role == VenueBotRole.STAFF) {
            enqueueMessage(
                chatId,
                "Раздел «Заведение» доступен менеджеру.",
                TelegramKeyboards.venueStaffMenu(),
            )
            return
        }
        val venueCard = loadOwnerVenueCardByVenueId(chatId, access.venueId) ?: return
        enqueueMessage(
            chatId,
            buildOwnerVenueCardText(venueCard),
            when (access.role) {
                VenueBotRole.OWNER -> TelegramKeyboards.inlineVenueOwnerProfileActions(venueCard.venueId)
                VenueBotRole.MANAGER -> TelegramKeyboards.inlineVenueManagerProfileActions(venueCard.venueId)
                VenueBotRole.STAFF -> TelegramKeyboards.inlineVenueManagerProfileActions(venueCard.venueId)
            },
        )
    }

    private suspend fun showVenueOwnerFieldEntry(
        chatId: Long,
        userId: Long,
        data: String,
    ) {
        val payload = data.removePrefix("owner_venue_field:")
        val venueId = payload.substringBefore(':').toLongOrNull()
        val fieldKey = payload.substringAfter(':', missingDelimiterValue = "").trim()
        if (venueId == null || fieldKey.isBlank()) {
            enqueueMessage(chatId, "Не удалось открыть раздел. Попробуйте ещё раз.")
            return
        }
        val hasAccess =
            runCatching { venueAccessRepository.hasVenueAdminOrOwner(userId, venueId) }
                .onFailure { logBestEffort("check venue owner access", it) }
                .getOrDefault(false)
        if (!hasAccess) {
            enqueueMessage(chatId, "Нет доступа к заведению.")
            return
        }
        val fieldTitle =
            when (fieldKey.lowercase(Locale.ROOT)) {
                "name" -> "✏️ Название"
                "address" -> "📍 Адрес"
                "hours" -> "🕒 Часы работы"
                "description" -> "📝 Описание"
                else -> null
            }
        if (fieldTitle == null) {
            enqueueMessage(chatId, "Не удалось открыть раздел. Попробуйте ещё раз.")
            return
        }
        if (fieldKey.equals("name", ignoreCase = true)) {
            dialogStateRepository.set(
                chatId,
                DialogState(
                    state = DialogStateType.OWNER_VENUE_PROFILE_WAIT_NAME,
                    payload =
                        mapOf(
                            "venue_id" to venueId.toString(),
                            "owner_user_id" to userId.toString(),
                        ),
                ),
            )
            enqueueMessage(chatId, "Введите новое название заведения.\nОтправьте «—», чтобы отменить.")
            return
        }
        if (fieldKey.equals("address", ignoreCase = true)) {
            dialogStateRepository.set(
                chatId,
                DialogState(
                    state = DialogStateType.OWNER_VENUE_PROFILE_WAIT_ADDRESS,
                    payload =
                        mapOf(
                            "venue_id" to venueId.toString(),
                            "owner_user_id" to userId.toString(),
                        ),
                ),
            )
            enqueueMessage(chatId, "Введите адрес заведения.\nОтправьте «—», чтобы отменить.")
            return
        }
        if (fieldKey.equals("hours", ignoreCase = true)) {
            showOwnerVenueHoursScreenByVenueId(chatId, userId, venueId)
            return
        }
        if (fieldKey.equals("description", ignoreCase = true)) {
            showOwnerVenueDescriptionSectionsByVenueId(chatId, userId, venueId)
            return
        }
        enqueueMessage(chatId, "$fieldTitle — следующий шаг настройки.")
    }

    private suspend fun showOwnerVenueHoursScreen(
        chatId: Long,
        userId: Long,
        data: String,
    ) {
        val venueId = parseOwnerVenueIdFromCallback(data, "owner_venue_hours_open:")
        if (venueId == null) {
            enqueueMessage(chatId, "Не удалось открыть часы работы. Попробуйте ещё раз.")
            return
        }
        showOwnerVenueHoursScreenByVenueId(chatId, userId, venueId)
    }

    private suspend fun showOwnerVenueHoursScreenByVenueId(
        chatId: Long,
        userId: Long,
        venueId: Long,
    ) {
        val hasAccess =
            runCatching { venueAccessRepository.hasVenueAdminOrOwner(userId, venueId) }
                .onFailure { logBestEffort("check venue owner access on hours screen", it) }
                .getOrDefault(false)
        if (!hasAccess) {
            enqueueMessage(chatId, "Нет доступа к заведению.")
            return
        }
        val weeklyHours =
            try {
                venueBookingHoursRepository.listWeeklyHours(venueId).associateBy { it.weekday }
            } catch (e: DatabaseUnavailableException) {
                enqueueMessage(chatId, "База недоступна, попробуйте позже.")
                return
            }
        val text =
            buildString {
                append("🕒 Часы работы")
                (1..7).forEach { weekday ->
                    val dayLabel = formatWeekday(DayOfWeek.of(weekday))
                    append("\n")
                    append(dayLabel.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.ROOT) else it.toString() })
                    append(": ")
                    append(formatOwnerVenueHoursStatus(weeklyHours[weekday]?.opensAt, weeklyHours[weekday]?.closesAt))
                }
            }
        val dayButtons =
            (1..7).map { weekday ->
                val dayLabel = formatWeekday(DayOfWeek.of(weekday))
                val status = formatOwnerVenueHoursStatus(weeklyHours[weekday]?.opensAt, weeklyHours[weekday]?.closesAt)
                weekday to "${dayLabel.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.ROOT) else it.toString() }} · $status"
            }
        enqueueMessage(
            chatId,
            text,
            TelegramKeyboards.inlineOwnerVenueHoursMain(venueId = venueId, dayButtons = dayButtons),
        )
    }

    private suspend fun showOwnerVenueHoursDayScreen(
        chatId: Long,
        userId: Long,
        data: String,
    ) {
        val parsed = parseOwnerVenueHoursWeekdayData(data, "owner_venue_hours_day:")
        if (parsed == null) {
            enqueueMessage(chatId, "Не удалось открыть день недели. Попробуйте ещё раз.")
            return
        }
        showOwnerVenueHoursDayScreenByVenueId(chatId, userId, parsed.first, parsed.second)
    }

    private suspend fun showOwnerVenueHoursDayScreenByVenueId(
        chatId: Long,
        userId: Long,
        venueId: Long,
        weekday: Int,
    ) {
        if (weekday !in 1..7) {
            enqueueMessage(chatId, "Не удалось открыть день недели. Попробуйте ещё раз.")
            return
        }
        val hasAccess =
            runCatching { venueAccessRepository.hasVenueAdminOrOwner(userId, venueId) }
                .onFailure { logBestEffort("check venue owner access on weekday screen", it) }
                .getOrDefault(false)
        if (!hasAccess) {
            enqueueMessage(chatId, "Нет доступа к заведению.")
            return
        }
        val weekdayHours =
            try {
                venueBookingHoursRepository
                    .listWeeklyHours(venueId)
                    .firstOrNull { it.weekday == weekday }
            } catch (e: DatabaseUnavailableException) {
                enqueueMessage(chatId, "База недоступна, попробуйте позже.")
                return
            }
        val dayLabel = formatWeekday(DayOfWeek.of(weekday))
        val status = formatOwnerVenueHoursStatus(weekdayHours?.opensAt, weekdayHours?.closesAt)
        enqueueMessage(
            chatId,
            buildString {
                append("🕒 Часы работы · ")
                append(dayLabel.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.ROOT) else it.toString() })
                append("\nТекущий статус: ")
                append(status)
            },
            TelegramKeyboards.inlineOwnerVenueHoursDayActions(venueId = venueId, weekday = weekday),
        )
    }

    private suspend fun startOwnerVenueHoursDayEdit(
        chatId: Long,
        userId: Long,
        data: String,
    ) {
        val parsed =
            parseOwnerVenueHoursWeekdayData(data, "owner_venue_hours_day_edit:")
                ?: parseOwnerVenueHoursWeekdayData(data, "owner_venue_hours_day_open:")
        if (parsed == null) {
            enqueueMessage(chatId, "Не удалось начать настройку часов. Попробуйте ещё раз.")
            return
        }
        val (venueId, weekday) = parsed
        if (weekday !in 1..7) {
            enqueueMessage(chatId, "Не удалось начать настройку часов. Попробуйте ещё раз.")
            return
        }
        val hasAccess =
            runCatching { venueAccessRepository.hasVenueAdminOrOwner(userId, venueId) }
                .onFailure { logBestEffort("check venue owner access on weekday edit", it) }
                .getOrDefault(false)
        if (!hasAccess) {
            enqueueMessage(chatId, "Нет доступа к заведению.")
            return
        }
        dialogStateRepository.set(
            chatId,
            DialogState(
                state = DialogStateType.OWNER_VENUE_PROFILE_WAIT_HOURS_OPEN,
                payload =
                    mapOf(
                        "venue_id" to venueId.toString(),
                        "owner_user_id" to userId.toString(),
                        "mode" to "weekday",
                        "weekday" to weekday.toString(),
                    ),
            ),
        )
        val dayLabel = formatWeekday(DayOfWeek.of(weekday))
        enqueueMessage(
            chatId,
            "${dayLabel.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.ROOT) else it.toString() }}: введите время открытия в формате HH:mm (например, 12:00).\nОтправьте «—», чтобы отменить.",
        )
    }

    private suspend fun closeOwnerVenueHoursDay(
        chatId: Long,
        userId: Long,
        data: String,
    ) {
        val parsed = parseOwnerVenueHoursWeekdayData(data, "owner_venue_hours_day_close:")
        if (parsed == null) {
            enqueueMessage(chatId, "Не удалось закрыть день. Попробуйте ещё раз.")
            return
        }
        val (venueId, weekday) = parsed
        if (weekday !in 1..7) {
            enqueueMessage(chatId, "Не удалось закрыть день. Попробуйте ещё раз.")
            return
        }
        val hasAccess =
            runCatching { venueAccessRepository.hasVenueAdminOrOwner(userId, venueId) }
                .onFailure { logBestEffort("check venue owner access on close weekday", it) }
                .getOrDefault(false)
        if (!hasAccess) {
            enqueueMessage(chatId, "Нет доступа к заведению.")
            return
        }
        val updated =
            try {
                venueBookingHoursRepository.closeWeekday(venueId = venueId, weekday = weekday)
            } catch (e: DatabaseUnavailableException) {
                enqueueMessage(chatId, "База недоступна, попробуйте позже.")
                return
            }
        if (!updated) {
            enqueueMessage(chatId, "Заведение не найдено.")
            return
        }
        val dayLabel = formatWeekday(DayOfWeek.of(weekday))
        enqueueMessage(
            chatId,
            "✅ ${dayLabel.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.ROOT) else it.toString() }}: день закрыт.",
        )
        showOwnerVenueHoursDayScreenByVenueId(chatId, userId, venueId, weekday)
    }

    private suspend fun showOwnerVenueHoursOverrides(
        chatId: Long,
        userId: Long,
        data: String,
    ) {
        val venueId = parseOwnerVenueIdFromCallback(data, "owner_venue_hours_overrides:")
        if (venueId == null) {
            enqueueMessage(chatId, "Не удалось открыть исключения. Попробуйте ещё раз.")
            return
        }
        showOwnerVenueHoursOverridesByVenueId(chatId, userId, venueId)
    }

    private suspend fun showOwnerVenueHoursOverridesByVenueId(
        chatId: Long,
        userId: Long,
        venueId: Long,
    ) {
        val hasAccess =
            runCatching { venueAccessRepository.hasVenueAdminOrOwner(userId, venueId) }
                .onFailure { logBestEffort("check venue owner access on overrides screen", it) }
                .getOrDefault(false)
        if (!hasAccess) {
            enqueueMessage(chatId, "Нет доступа к заведению.")
            return
        }
        val overrides =
            try {
                venueBookingHoursRepository.listDateOverrides(venueId = venueId, limit = 30)
            } catch (e: DatabaseUnavailableException) {
                enqueueMessage(chatId, "База недоступна, попробуйте позже.")
                return
            }
        val text =
            buildString {
                append("📅 Исключения")
                if (overrides.isEmpty()) {
                    append("\n\nПока нет исключений.")
                } else {
                    overrides.forEach { override ->
                        append("\n")
                        append(override.serviceDate.format(bookingDateConfirmFormatter))
                        append(": ")
                        append(formatOwnerVenueHoursStatus(override.opensAt, override.closesAt))
                    }
                }
            }
        val buttons =
            overrides.map { override ->
                val label =
                    "${override.serviceDate.format(bookingDateConfirmFormatter)} · ${formatOwnerVenueHoursStatus(override.opensAt, override.closesAt)}"
                label to override.serviceDate.toString()
            }
        enqueueMessage(
            chatId,
            text,
            TelegramKeyboards.inlineOwnerVenueHoursOverrides(venueId = venueId, overrideButtons = buttons),
        )
    }

    private suspend fun promptOwnerVenueHoursOverrideDate(
        chatId: Long,
        userId: Long,
        data: String,
    ) {
        val venueId = parseOwnerVenueIdFromCallback(data, "owner_venue_hours_override_add:")
        if (venueId == null) {
            enqueueMessage(chatId, "Не удалось добавить исключение. Попробуйте ещё раз.")
            return
        }
        val hasAccess =
            runCatching { venueAccessRepository.hasVenueAdminOrOwner(userId, venueId) }
                .onFailure { logBestEffort("check venue owner access on override add", it) }
                .getOrDefault(false)
        if (!hasAccess) {
            enqueueMessage(chatId, "Нет доступа к заведению.")
            return
        }
        dialogStateRepository.set(
            chatId,
            DialogState(
                state = DialogStateType.OWNER_VENUE_PROFILE_WAIT_HOURS_OPEN,
                payload =
                    mapOf(
                        "venue_id" to venueId.toString(),
                        "owner_user_id" to userId.toString(),
                        "mode" to "override_date",
                    ),
            ),
        )
        enqueueMessage(chatId, "Введите дату исключения в формате дд.мм.гггг, например 15.04.2026.\nОтправьте «—», чтобы отменить.")
    }

    private suspend fun chooseOwnerVenueHoursOverrideMode(
        chatId: Long,
        userId: Long,
        data: String,
    ) {
        val parsed = parseOwnerVenueHoursOverrideModeData(data) ?: run {
            enqueueMessage(chatId, "Не удалось применить исключение. Попробуйте ещё раз.")
            return
        }
        val (venueId, serviceDate, mode) = parsed
        val hasAccess =
            runCatching { venueAccessRepository.hasVenueAdminOrOwner(userId, venueId) }
                .onFailure { logBestEffort("check venue owner access on override mode", it) }
                .getOrDefault(false)
        if (!hasAccess) {
            enqueueMessage(chatId, "Нет доступа к заведению.")
            return
        }
        if (mode != "open" && mode != "closed") {
            enqueueMessage(chatId, "Не удалось применить исключение. Попробуйте ещё раз.")
            return
        }
        if (mode == "closed") {
            val updated =
                try {
                    venueBookingHoursRepository.upsertDateOverride(
                        venueId = venueId,
                        serviceDate = serviceDate,
                        opensAt = LocalTime.MIDNIGHT,
                        closesAt = LocalTime.MIDNIGHT,
                    )
                } catch (e: DatabaseUnavailableException) {
                    enqueueMessage(chatId, "База недоступна, попробуйте позже.")
                    return
                }
            if (!updated) {
                enqueueMessage(chatId, "Заведение не найдено.")
                return
            }
            dialogStateRepository.clear(chatId)
            enqueueMessage(chatId, "✅ Исключение на ${serviceDate.format(bookingDateConfirmFormatter)} сохранено: закрыто.")
            showOwnerVenueHoursOverridesByVenueId(chatId, userId, venueId)
            return
        }
        dialogStateRepository.set(
            chatId,
            DialogState(
                state = DialogStateType.OWNER_VENUE_PROFILE_WAIT_HOURS_OPEN,
                payload =
                    mapOf(
                        "venue_id" to venueId.toString(),
                        "owner_user_id" to userId.toString(),
                        "mode" to "override_time",
                        "service_date" to serviceDate.toString(),
                    ),
            ),
        )
        enqueueMessage(
            chatId,
            "Введите время открытия для ${serviceDate.format(bookingDateConfirmFormatter)} в формате HH:mm (например, 12:00).\nОтправьте «—», чтобы отменить.",
        )
    }

    private suspend fun showOwnerVenueHoursOverrideEdit(
        chatId: Long,
        userId: Long,
        data: String,
    ) {
        val parsed = parseOwnerVenueHoursOverrideData(data, "owner_venue_hours_override_edit:")
        if (parsed == null) {
            enqueueMessage(chatId, "Не удалось открыть исключение. Попробуйте ещё раз.")
            return
        }
        val (venueId, serviceDate) = parsed
        val hasAccess =
            runCatching { venueAccessRepository.hasVenueAdminOrOwner(userId, venueId) }
                .onFailure { logBestEffort("check venue owner access on override edit", it) }
                .getOrDefault(false)
        if (!hasAccess) {
            enqueueMessage(chatId, "Нет доступа к заведению.")
            return
        }
        val override =
            try {
                venueBookingHoursRepository.findDateOverride(venueId = venueId, serviceDate = serviceDate)
            } catch (e: DatabaseUnavailableException) {
                enqueueMessage(chatId, "База недоступна, попробуйте позже.")
                return
            }
        if (override == null) {
            enqueueMessage(chatId, "Исключение не найдено.")
            showOwnerVenueHoursOverridesByVenueId(chatId, userId, venueId)
            return
        }
        enqueueMessage(
            chatId,
            buildString {
                append("📅 Исключение: ${serviceDate.format(bookingDateConfirmFormatter)}")
                append("\nТекущий статус: ${formatOwnerVenueHoursStatus(override.opensAt, override.closesAt)}")
            },
            TelegramKeyboards.inlineOwnerVenueHoursOverrideEditActions(venueId = venueId, dateIso = serviceDate.toString()),
        )
    }

    private suspend fun deleteOwnerVenueHoursOverride(
        chatId: Long,
        userId: Long,
        data: String,
    ) {
        val parsed = parseOwnerVenueHoursOverrideData(data, "owner_venue_hours_override_delete:")
        if (parsed == null) {
            enqueueMessage(chatId, "Не удалось удалить исключение. Попробуйте ещё раз.")
            return
        }
        val (venueId, serviceDate) = parsed
        val hasAccess =
            runCatching { venueAccessRepository.hasVenueAdminOrOwner(userId, venueId) }
                .onFailure { logBestEffort("check venue owner access on override delete", it) }
                .getOrDefault(false)
        if (!hasAccess) {
            enqueueMessage(chatId, "Нет доступа к заведению.")
            return
        }
        try {
            venueBookingHoursRepository.deleteDateOverride(venueId = venueId, serviceDate = serviceDate)
        } catch (e: DatabaseUnavailableException) {
            enqueueMessage(chatId, "База недоступна, попробуйте позже.")
            return
        }
        enqueueMessage(chatId, "✅ Исключение удалено.")
        showOwnerVenueHoursOverridesByVenueId(chatId, userId, venueId)
    }

    private suspend fun backFromOwnerVenueHours(
        chatId: Long,
        userId: Long,
        data: String,
    ) {
        val venueId = parseOwnerVenueIdFromCallback(data, "owner_venue_hours_back:")
        if (venueId == null) {
            enqueueMessage(chatId, "Не удалось вернуться к заведению. Попробуйте ещё раз.")
            return
        }
        val hasAccess =
            runCatching { venueAccessRepository.hasVenueAdminOrOwner(userId, venueId) }
                .onFailure { logBestEffort("check venue owner access on hours back", it) }
                .getOrDefault(false)
        if (!hasAccess) {
            enqueueMessage(chatId, "Нет доступа к заведению.")
            return
        }
        showVenueOwnerVenueCardByUserId(chatId, userId)
    }

    private suspend fun showOwnerVenueDescriptionSections(
        chatId: Long,
        userId: Long,
        data: String,
    ) {
        val venueId = parseOwnerVenueIdFromCallback(data, "owner_venue_description_open:")
        if (venueId == null) {
            enqueueMessage(chatId, "Не удалось открыть раздел «Описание». Попробуйте ещё раз.")
            return
        }
        showOwnerVenueDescriptionSectionsByVenueId(chatId, userId, venueId)
    }

    private suspend fun showOwnerVenueDescriptionSectionsByVenueId(
        chatId: Long,
        userId: Long,
        venueId: Long,
    ) {
        val hasAccess =
            runCatching { venueAccessRepository.hasVenueAdminOrOwner(userId, venueId) }
                .onFailure { logBestEffort("check venue owner access on description sections", it) }
                .getOrDefault(false)
        if (!hasAccess) {
            enqueueMessage(chatId, "Нет доступа к заведению.")
            return
        }
        try {
            venueInfoSectionsRepository.ensureDefaultSections(venueId)
        } catch (e: DatabaseUnavailableException) {
            enqueueMessage(chatId, "База недоступна, попробуйте позже.")
            return
        }
        val sections =
            try {
                venueInfoSectionsRepository.listSections(venueId)
            } catch (e: DatabaseUnavailableException) {
                enqueueMessage(chatId, "База недоступна, попробуйте позже.")
                return
            }
        val mediaCountsBySectionId =
            try {
                venueInfoSectionMediaRepository.countBySectionIds(sections.map { it.id })
            } catch (e: DatabaseUnavailableException) {
                enqueueMessage(chatId, "База недоступна, попробуйте позже.")
                return
            }
        val sectionButtons =
            sections.map { section ->
                val mediaCount = mediaCountsBySectionId[section.id] ?: 0
                section.id to "${section.title} · ${formatOwnerVenueDescriptionSectionStatus(section, mediaCount)}"
            }
        enqueueMessage(
            chatId,
            buildString {
                append("📝 Описание заведения")
                append("\n\nВыберите раздел для настройки.")
            },
            TelegramKeyboards.inlineOwnerVenueDescriptionSections(venueId = venueId, sectionButtons = sectionButtons),
        )
    }

    private suspend fun showOwnerVenueDescriptionSection(
        chatId: Long,
        userId: Long,
        data: String,
    ) {
        val parsed = parseOwnerVenueDescriptionSectionData(data, "owner_venue_description_section:")
        if (parsed == null) {
            enqueueMessage(chatId, "Не удалось открыть раздел. Попробуйте ещё раз.")
            return
        }
        showOwnerVenueDescriptionSectionById(chatId, userId, parsed.first, parsed.second)
    }

    private suspend fun showOwnerVenueDescriptionSectionById(
        chatId: Long,
        userId: Long,
        venueId: Long,
        sectionId: Long,
    ) {
        val hasAccess =
            runCatching { venueAccessRepository.hasVenueAdminOrOwner(userId, venueId) }
                .onFailure { logBestEffort("check venue owner access on description section", it) }
                .getOrDefault(false)
        if (!hasAccess) {
            enqueueMessage(chatId, "Нет доступа к заведению.")
            return
        }
        val section =
            try {
                venueInfoSectionsRepository.findSectionById(venueId, sectionId)
            } catch (e: DatabaseUnavailableException) {
                enqueueMessage(chatId, "База недоступна, попробуйте позже.")
                return
            }
        if (section == null) {
            enqueueMessage(chatId, "Раздел не найден.")
            showOwnerVenueDescriptionSectionsByVenueId(chatId, userId, venueId)
            return
        }
        val mediaAttachments =
            try {
                venueInfoSectionMediaRepository.listBySectionId(section.id)
            } catch (e: DatabaseUnavailableException) {
                enqueueMessage(chatId, "База недоступна, попробуйте позже.")
                return
            }
        val mediaLabels = buildOwnerVenueSectionMediaLabels(mediaAttachments)
        enqueueMessage(
            chatId,
            buildString {
                append("📝 Раздел: ${section.title}")
                append("\nТип: ${humanizeOwnerVenueSectionType(section.sectionType)}")
                append("\nСтатус: ${formatOwnerVenueDescriptionSectionStatus(section, mediaAttachments.size)}")
                append("\nВидимость: ${if (section.isVisible) "Показывается" else "Скрыт"}")
                append("\n\nТекст:")
                append("\n${section.textContent?.takeIf { it.isNotBlank() } ?: "—"}")
                append("\n\nМедиа:")
                if (mediaLabels.isEmpty()) {
                    append("\n—")
                } else {
                    mediaLabels.forEach { (_, label) ->
                        append("\n• $label")
                    }
                }
            },
            TelegramKeyboards.inlineOwnerVenueDescriptionSectionActions(
                venueId = venueId,
                sectionId = section.id,
                isVisible = section.isVisible,
                hasText = !section.textContent.isNullOrBlank(),
                mediaDeleteButtons = mediaLabels,
            ),
        )
    }

    private suspend fun promptOwnerVenueDescriptionCustomSectionTitle(
        chatId: Long,
        userId: Long,
        data: String,
    ) {
        val venueId = parseOwnerVenueIdFromCallback(data, "owner_venue_description_add:")
        if (venueId == null) {
            enqueueMessage(chatId, "Не удалось добавить раздел. Попробуйте ещё раз.")
            return
        }
        val hasAccess =
            runCatching { venueAccessRepository.hasVenueAdminOrOwner(userId, venueId) }
                .onFailure { logBestEffort("check venue owner access on add custom section", it) }
                .getOrDefault(false)
        if (!hasAccess) {
            enqueueMessage(chatId, "Нет доступа к заведению.")
            return
        }
        dialogStateRepository.set(
            chatId,
            DialogState(
                state = DialogStateType.OWNER_VENUE_DESCRIPTION_WAIT_SECTION_TITLE,
                payload =
                    mapOf(
                        "venue_id" to venueId.toString(),
                        "owner_user_id" to userId.toString(),
                    ),
            ),
        )
        enqueueMessage(chatId, "Введите название нового раздела.\nОтправьте «—», чтобы отменить.")
    }

    private suspend fun promptOwnerVenueDescriptionSectionText(
        chatId: Long,
        userId: Long,
        data: String,
    ) {
        val parsed = parseOwnerVenueDescriptionSectionData(data, "owner_venue_description_text:")
        if (parsed == null) {
            enqueueMessage(chatId, "Не удалось открыть редактирование текста. Попробуйте ещё раз.")
            return
        }
        val (venueId, sectionId) = parsed
        val hasAccess =
            runCatching { venueAccessRepository.hasVenueAdminOrOwner(userId, venueId) }
                .onFailure { logBestEffort("check venue owner access on section text prompt", it) }
                .getOrDefault(false)
        if (!hasAccess) {
            enqueueMessage(chatId, "Нет доступа к заведению.")
            return
        }
        val section =
            try {
                venueInfoSectionsRepository.findSectionById(venueId, sectionId)
            } catch (e: DatabaseUnavailableException) {
                enqueueMessage(chatId, "База недоступна, попробуйте позже.")
                return
            }
        if (section == null) {
            enqueueMessage(chatId, "Раздел не найден.")
            showOwnerVenueDescriptionSectionsByVenueId(chatId, userId, venueId)
            return
        }
        dialogStateRepository.set(
            chatId,
            DialogState(
                state = DialogStateType.OWNER_VENUE_DESCRIPTION_WAIT_SECTION_TEXT,
                payload =
                    mapOf(
                        "venue_id" to venueId.toString(),
                        "owner_user_id" to userId.toString(),
                        "section_id" to sectionId.toString(),
                    ),
            ),
        )
        enqueueMessage(
            chatId,
            buildString {
                append("Текущий текст раздела «${section.title}»:")
                append("\n${section.textContent?.takeIf { it.isNotBlank() } ?: "—"}")
                append("\n\nВведите новый текст для раздела.")
                append("\nОтправьте «—», чтобы отменить.")
            },
        )
    }

    private suspend fun deleteOwnerVenueDescriptionSectionMedia(
        chatId: Long,
        userId: Long,
        data: String,
    ) {
        val parsed = parseOwnerVenueDescriptionMediaDeleteData(data)
        if (parsed == null) {
            enqueueMessage(chatId, "Не удалось удалить медиа. Попробуйте ещё раз.")
            return
        }
        val (venueId, sectionId, mediaId) = parsed
        val hasAccess =
            runCatching { venueAccessRepository.hasVenueAdminOrOwner(userId, venueId) }
                .onFailure { logBestEffort("check venue owner access on delete section media", it) }
                .getOrDefault(false)
        if (!hasAccess) {
            enqueueMessage(chatId, "Нет доступа к заведению.")
            return
        }
        val section =
            try {
                venueInfoSectionsRepository.findSectionById(venueId, sectionId)
            } catch (e: DatabaseUnavailableException) {
                enqueueMessage(chatId, "База недоступна, попробуйте позже.")
                return
            }
        if (section == null) {
            enqueueMessage(chatId, "Раздел не найден.")
            showOwnerVenueDescriptionSectionsByVenueId(chatId, userId, venueId)
            return
        }
        val deleted =
            try {
                venueInfoSectionMediaRepository.deleteAttachment(sectionId = sectionId, mediaId = mediaId)
            } catch (e: DatabaseUnavailableException) {
                enqueueMessage(chatId, "База недоступна, попробуйте позже.")
                return
            }
        if (!deleted) {
            enqueueMessage(chatId, "Медиа не найдено.")
            showOwnerVenueDescriptionSectionById(chatId, userId, venueId, sectionId)
            return
        }
        enqueueMessage(chatId, "✅ Медиа удалено.")
        showOwnerVenueDescriptionSectionById(chatId, userId, venueId, sectionId)
    }

    private suspend fun promptOwnerVenueDescriptionSectionMedia(
        chatId: Long,
        userId: Long,
        data: String,
    ) {
        val parsed = parseOwnerVenueDescriptionSectionData(data, "owner_venue_description_media:")
        if (parsed == null) {
            enqueueMessage(chatId, "Не удалось открыть раздел медиа. Попробуйте ещё раз.")
            return
        }
        val (venueId, sectionId) = parsed
        val hasAccess =
            runCatching { venueAccessRepository.hasVenueAdminOrOwner(userId, venueId) }
                .onFailure { logBestEffort("check venue owner access on section media", it) }
                .getOrDefault(false)
        if (!hasAccess) {
            enqueueMessage(chatId, "Нет доступа к заведению.")
            return
        }
        val section =
            try {
                venueInfoSectionsRepository.findSectionById(venueId, sectionId)
            } catch (e: DatabaseUnavailableException) {
                enqueueMessage(chatId, "База недоступна, попробуйте позже.")
                return
            }
        if (section == null) {
            enqueueMessage(chatId, "Раздел не найден.")
            showOwnerVenueDescriptionSectionsByVenueId(chatId, userId, venueId)
            return
        }
        dialogStateRepository.set(
            chatId,
            DialogState(
                state = DialogStateType.OWNER_VENUE_DESCRIPTION_WAIT_SECTION_MEDIA,
                payload =
                    mapOf(
                        "venue_id" to venueId.toString(),
                        "owner_user_id" to userId.toString(),
                        "section_id" to section.id.toString(),
                    ),
            ),
        )
        enqueueMessage(
            chatId,
            "Отправьте изображение или PDF для раздела «${section.title}».\nОтправьте «—», чтобы отменить.",
        )
    }

    private suspend fun toggleOwnerVenueDescriptionSectionVisibility(
        chatId: Long,
        userId: Long,
        data: String,
    ) {
        val parsed = parseOwnerVenueDescriptionSectionData(data, "owner_venue_description_visibility:")
        if (parsed == null) {
            enqueueMessage(chatId, "Не удалось изменить видимость раздела. Попробуйте ещё раз.")
            return
        }
        val (venueId, sectionId) = parsed
        val hasAccess =
            runCatching { venueAccessRepository.hasVenueAdminOrOwner(userId, venueId) }
                .onFailure { logBestEffort("check venue owner access on section visibility", it) }
                .getOrDefault(false)
        if (!hasAccess) {
            enqueueMessage(chatId, "Нет доступа к заведению.")
            return
        }
        val section =
            try {
                venueInfoSectionsRepository.toggleSectionVisibility(venueId, sectionId)
            } catch (e: DatabaseUnavailableException) {
                enqueueMessage(chatId, "База недоступна, попробуйте позже.")
                return
            }
        if (section == null) {
            enqueueMessage(chatId, "Раздел не найден.")
            showOwnerVenueDescriptionSectionsByVenueId(chatId, userId, venueId)
            return
        }
        enqueueMessage(
            chatId,
            if (section.isVisible) "✅ Раздел «${section.title}» снова показывается." else "✅ Раздел «${section.title}» скрыт.",
        )
        showOwnerVenueDescriptionSectionById(chatId, userId, venueId, sectionId)
    }

    private suspend fun backFromOwnerVenueDescription(
        chatId: Long,
        userId: Long,
        data: String,
    ) {
        val venueId = parseOwnerVenueIdFromCallback(data, "owner_venue_description_back:")
        if (venueId == null) {
            enqueueMessage(chatId, "Не удалось вернуться к заведению. Попробуйте ещё раз.")
            return
        }
        val hasAccess =
            runCatching { venueAccessRepository.hasVenueAdminOrOwner(userId, venueId) }
                .onFailure { logBestEffort("check venue owner access on description back", it) }
                .getOrDefault(false)
        if (!hasAccess) {
            enqueueMessage(chatId, "Нет доступа к заведению.")
            return
        }
        showVenueOwnerVenueCardByUserId(chatId, userId)
    }

    private suspend fun showVenueOwnerPublishReadiness(
        chatId: Long,
        userId: Long,
        data: String,
    ) {
        val venueId = parseOwnerVenueIdFromCallback(data, "owner_venue_publish_readiness:")
        if (venueId == null) {
            enqueueMessage(chatId, "Не удалось открыть готовность к публикации. Попробуйте ещё раз.")
            return
        }
        showVenueOwnerPublishReadinessByVenueId(chatId, userId, venueId)
    }

    private suspend fun showVenueOwnerPublishReadinessByVenueId(
        chatId: Long,
        userId: Long,
        venueId: Long,
    ) {
        val hasAccess =
            runCatching { venueAccessRepository.hasVenueAdminOrOwner(userId, venueId) }
                .onFailure { logBestEffort("check venue owner access on publish readiness", it) }
                .getOrDefault(false)
        if (!hasAccess) {
            enqueueMessage(chatId, "Нет доступа к заведению.")
            return
        }
        botVenuePreviewContexts.remove(chatId)
        val venueCard = loadOwnerVenueCardByVenueId(chatId, venueId) ?: return
        val readiness = loadOwnerVenuePublishReadiness(chatId, venueCard) ?: return
        enqueueMessage(
            chatId,
            buildOwnerVenuePublishReadinessText(venueCard, readiness),
            TelegramKeyboards.inlineVenueOwnerPublishReadinessActions(venueId),
        )
    }

    private suspend fun showVenueOwnerPublishPreview(
        chatId: Long,
        userId: Long,
        data: String,
    ) {
        val venueId = parseOwnerVenueIdFromCallback(data, "owner_venue_publish_preview:")
        if (venueId == null) {
            enqueueMessage(chatId, "Не удалось открыть предпросмотр. Попробуйте ещё раз.")
            return
        }
        val hasAccess =
            runCatching { venueAccessRepository.hasVenueAdminOrOwner(userId, venueId) }
                .onFailure { logBestEffort("check venue owner access on publish preview", it) }
                .getOrDefault(false)
        if (!hasAccess) {
            enqueueMessage(chatId, "Нет доступа к заведению.")
            return
        }
        val venueCard = loadOwnerVenueCardByVenueId(chatId, venueId) ?: return
        val status = venueCard.status?.let { VenueStatus.fromDb(it) }
        botVenuePreviewContexts[chatId] =
            BotVenuePreviewContext(
                venueId = venueId,
                ownerUserId = userId,
                showDraftNotice = status != VenueStatus.PUBLISHED,
            )
        showBotVenueCard(
            chatId = chatId,
            data = "bot_catalog_venue:$venueId",
        )
    }

    private suspend fun publishVenueFromOwnerBot(
        chatId: Long,
        userId: Long,
        data: String,
    ) {
        val venueId = parseOwnerVenueIdFromCallback(data, "owner_venue_publish_confirm:")
        if (venueId == null) {
            enqueueMessage(chatId, "Не удалось опубликовать кальянную. Попробуйте ещё раз.")
            return
        }
        val hasAccess =
            runCatching { venueAccessRepository.hasVenueAdminOrOwner(userId, venueId) }
                .onFailure { logBestEffort("check venue owner access on publish", it) }
                .getOrDefault(false)
        if (!hasAccess) {
            enqueueMessage(chatId, "Нет доступа к заведению.")
            return
        }
        val venueCard = loadOwnerVenueCardByVenueId(chatId, venueId) ?: return
        val readiness = loadOwnerVenuePublishReadiness(chatId, venueCard) ?: return
        if (readiness.status == VenueStatus.PUBLISHED) {
            enqueueMessage(chatId, "Кальянная уже опубликована.")
            showVenueOwnerPublishReadinessByVenueId(chatId, userId, venueId)
            return
        }
        if (!readiness.canPublish) {
            val missing = ownerReadinessMissingLabels(readiness)
            enqueueMessage(
                chatId,
                buildString {
                    append("Пока нельзя опубликовать кальянную.")
                    if (missing.isNotEmpty()) {
                        append("\nНе заполнено:")
                        missing.forEach { append("\n• ").append(it) }
                    }
                },
                TelegramKeyboards.inlineVenueOwnerPublishReadinessActions(venueId),
            )
            return
        }
        when (val result = platformVenueRepository.updateStatus(venueId, VenueStatusAction.PUBLISH)) {
            is VenueStatusChangeResult.Success -> {
                enqueueMessage(chatId, "✅ Кальянная опубликована.")
                showVenueOwnerPublishReadinessByVenueId(chatId, userId, venueId)
            }
            VenueStatusChangeResult.NotFound -> enqueueMessage(chatId, "Заведение не найдено.")
            VenueStatusChangeResult.MissingOwner ->
                enqueueMessage(chatId, "Не удалось опубликовать: назначьте владельца или администратора.")
            VenueStatusChangeResult.InvalidTransition ->
                enqueueMessage(chatId, "Не удалось опубликовать из текущего статуса.")
            VenueStatusChangeResult.AlreadyDeleted ->
                enqueueMessage(chatId, "Заведение удалено и не может быть опубликовано.")
            VenueStatusChangeResult.DatabaseError ->
                enqueueMessage(chatId, "База недоступна, попробуйте позже.")
        }
    }

    private suspend fun loadOwnerVenuePublishReadiness(
        chatId: Long,
        card: OwnerVenueCard,
    ): OwnerVenuePublishReadiness? {
        val hoursReady =
            try {
                venueBookingHoursRepository.hasConfiguredHours(card.venueId)
            } catch (e: DatabaseUnavailableException) {
                enqueueMessage(chatId, "База недоступна, попробуйте позже.")
                return null
            }
        val menuReady =
            try {
                guestMenuRepository
                    .getMenu(card.venueId)
                    .categories
                    .any { category -> category.items.isNotEmpty() }
            } catch (e: DatabaseUnavailableException) {
                enqueueMessage(chatId, "База недоступна, попробуйте позже.")
                return null
            }
        return OwnerVenuePublishReadiness(
            status = card.status?.let { VenueStatus.fromDb(it) },
            nameReady = card.name.isNotBlank(),
            addressReady = !card.address.isNullOrBlank(),
            hoursReady = hoursReady,
            descriptionReady = !card.description.isNullOrBlank(),
            menuReady = menuReady,
        )
    }

    private fun buildOwnerVenuePublishReadinessText(
        card: OwnerVenueCard,
        readiness: OwnerVenuePublishReadiness,
    ): String =
        buildString {
            append("✅ Готовность к публикации")
            append("\n\nСтатус: ${humanizeOwnerVenuePublishStatus(readiness.status)}")
            append("\n")
            append(ownerReadinessLine("Название", readiness.nameReady))
            append("\n")
            append(ownerReadinessLine("Адрес", readiness.addressReady))
            append("\n")
            append(ownerReadinessLine("Часы работы", readiness.hoursReady))
            append("\n")
            append(ownerReadinessLine("Описание", readiness.descriptionReady))
            append("\n")
            append(ownerReadinessLine("Меню", readiness.menuReady))
            if (!readiness.canPublish) {
                val missing = ownerReadinessMissingLabels(readiness)
                if (missing.isNotEmpty()) {
                    append("\n\nНе заполнено:")
                    missing.forEach { append("\n• ").append(it) }
                }
            }
            append("\n\nКальянная: ${card.name}")
        }

    private fun ownerReadinessLine(
        label: String,
        ready: Boolean,
    ): String = if (ready) "✅ $label" else "❌ $label"

    private fun ownerReadinessMissingLabels(readiness: OwnerVenuePublishReadiness): List<String> =
        buildList {
            if (!readiness.nameReady) add("Название")
            if (!readiness.addressReady) add("Адрес")
            if (!readiness.hoursReady) add("Часы работы")
            if (!readiness.descriptionReady) add("Описание")
            if (!readiness.menuReady) add("Меню")
        }

    private fun humanizeOwnerVenuePublishStatus(status: VenueStatus?): String =
        when (status) {
            VenueStatus.DRAFT -> "Draft"
            VenueStatus.PUBLISHED -> "Published"
            VenueStatus.HIDDEN -> "Hidden"
            VenueStatus.PAUSED -> "Paused"
            VenueStatus.SUSPENDED -> "Suspended"
            VenueStatus.ARCHIVED -> "Archived"
            VenueStatus.DELETED -> "Deleted"
            null -> "—"
        }

    private fun parseOwnerVenueIdFromCallback(
        data: String,
        prefix: String,
    ): Long? = data.removePrefix(prefix).toLongOrNull()

    private suspend fun proceedOwnerVenueName(
        chatId: Long,
        from: User?,
        text: String,
        state: DialogState,
    ) {
        val venueId = state.payload["venue_id"]?.toLongOrNull()
        val ownerUserId = (from?.id ?: state.payload["owner_user_id"]?.toLongOrNull())
        if (venueId == null || ownerUserId == null) {
            dialogStateRepository.clear(chatId)
            enqueueMessage(chatId, "Не удалось продолжить изменение названия. Откройте «🏢 Моё заведение» снова.")
            return
        }
        val input = text.trim()
        if (input == "-" || input == "—") {
            dialogStateRepository.clear(chatId)
            enqueueMessage(chatId, "Изменение названия отменено.")
            showVenueOwnerVenueCardByUserId(chatId, ownerUserId)
            return
        }
        val normalized = normalizeVenueConnectionRequiredField(input, maxLength = 200)
        if (normalized == null) {
            enqueueMessage(chatId, "Введите корректное название (до 200 символов).")
            return
        }
        val hasAccess =
            runCatching { venueAccessRepository.hasVenueAdminOrOwner(ownerUserId, venueId) }
                .onFailure { logBestEffort("check venue owner access on name update", it) }
                .getOrDefault(false)
        if (!hasAccess) {
            dialogStateRepository.clear(chatId)
            enqueueMessage(chatId, "Нет доступа к заведению.")
            return
        }
        val updated =
            try {
                platformVenueRepository.updateVenueName(
                    venueId = venueId,
                    name = normalized,
                )
            } catch (e: DatabaseUnavailableException) {
                enqueueMessage(chatId, "База недоступна, попробуйте позже.")
                return
            }
        if (updated == null) {
            dialogStateRepository.clear(chatId)
            enqueueMessage(chatId, "Заведение не найдено.")
            return
        }
        dialogStateRepository.clear(chatId)
        enqueueMessage(chatId, "✅ Название обновлено.")
        showVenueOwnerVenueCardByUserId(chatId, ownerUserId)
    }

    private suspend fun proceedOwnerVenueAddress(
        chatId: Long,
        from: User?,
        text: String,
        state: DialogState,
    ) {
        val venueId = state.payload["venue_id"]?.toLongOrNull()
        val ownerUserId = (from?.id ?: state.payload["owner_user_id"]?.toLongOrNull())
        if (venueId == null || ownerUserId == null) {
            dialogStateRepository.clear(chatId)
            enqueueMessage(chatId, "Не удалось продолжить изменение адреса. Откройте «🏢 Моё заведение» снова.")
            return
        }
        val input = text.trim()
        if (input == "-" || input == "—") {
            dialogStateRepository.clear(chatId)
            enqueueMessage(chatId, "Изменение адреса отменено.")
            showVenueOwnerVenueCardByUserId(chatId, ownerUserId)
            return
        }
        val normalized = normalizeVenueConnectionRequiredField(input, maxLength = 300)
        if (normalized == null) {
            enqueueMessage(chatId, "Введите корректный адрес (до 300 символов).")
            return
        }
        val hasAccess =
            runCatching { venueAccessRepository.hasVenueAdminOrOwner(ownerUserId, venueId) }
                .onFailure { logBestEffort("check venue owner access on address update", it) }
                .getOrDefault(false)
        if (!hasAccess) {
            dialogStateRepository.clear(chatId)
            enqueueMessage(chatId, "Нет доступа к заведению.")
            return
        }
        val updated =
            try {
                platformVenueRepository.updateVenueAddress(
                    venueId = venueId,
                    address = normalized,
                )
            } catch (e: DatabaseUnavailableException) {
                enqueueMessage(chatId, "База недоступна, попробуйте позже.")
                return
            }
        if (updated == null) {
            dialogStateRepository.clear(chatId)
            enqueueMessage(chatId, "Заведение не найдено.")
            return
        }
        dialogStateRepository.clear(chatId)
        enqueueMessage(chatId, "✅ Адрес обновлён.")
        showVenueOwnerVenueCardByUserId(chatId, ownerUserId)
    }

    private suspend fun proceedOwnerVenueDescriptionSectionTitle(
        chatId: Long,
        from: User?,
        text: String,
        state: DialogState,
    ) {
        val venueId = state.payload["venue_id"]?.toLongOrNull()
        val ownerUserId = (from?.id ?: state.payload["owner_user_id"]?.toLongOrNull())
        if (venueId == null || ownerUserId == null) {
            dialogStateRepository.clear(chatId)
            enqueueMessage(chatId, "Не удалось продолжить настройку описания. Откройте «📝 Описание» снова.")
            return
        }
        val input = text.trim()
        if (input == "-" || input == "—") {
            dialogStateRepository.clear(chatId)
            enqueueMessage(chatId, "Добавление раздела отменено.")
            showOwnerVenueDescriptionSectionsByVenueId(chatId, ownerUserId, venueId)
            return
        }
        val normalized = normalizeVenueConnectionRequiredField(input, maxLength = 120)
        if (normalized == null) {
            enqueueMessage(chatId, "Введите корректное название раздела (до 120 символов).")
            return
        }
        val hasAccess =
            runCatching { venueAccessRepository.hasVenueAdminOrOwner(ownerUserId, venueId) }
                .onFailure { logBestEffort("check venue owner access on create section", it) }
                .getOrDefault(false)
        if (!hasAccess) {
            dialogStateRepository.clear(chatId)
            enqueueMessage(chatId, "Нет доступа к заведению.")
            return
        }
        val createdSection =
            try {
                venueInfoSectionsRepository.createCustomSection(venueId = venueId, title = normalized)
            } catch (e: DatabaseUnavailableException) {
                enqueueMessage(chatId, "База недоступна, попробуйте позже.")
                return
            }
        if (createdSection == null) {
            dialogStateRepository.clear(chatId)
            enqueueMessage(chatId, "Не удалось создать раздел. Попробуйте ещё раз.")
            return
        }
        dialogStateRepository.clear(chatId)
        enqueueMessage(chatId, "✅ Раздел «${createdSection.title}» добавлен.")
        showOwnerVenueDescriptionSectionsByVenueId(chatId, ownerUserId, venueId)
    }

    private suspend fun proceedOwnerVenueDescriptionSectionText(
        chatId: Long,
        from: User?,
        text: String,
        state: DialogState,
    ) {
        val venueId = state.payload["venue_id"]?.toLongOrNull()
        val sectionId = state.payload["section_id"]?.toLongOrNull()
        val ownerUserId = (from?.id ?: state.payload["owner_user_id"]?.toLongOrNull())
        if (venueId == null || sectionId == null || ownerUserId == null) {
            dialogStateRepository.clear(chatId)
            enqueueMessage(chatId, "Не удалось сохранить текст раздела. Откройте раздел снова.")
            return
        }
        val input = text.trim()
        if (input == "-" || input == "—") {
            dialogStateRepository.clear(chatId)
            enqueueMessage(chatId, "Изменение текста отменено.")
            showOwnerVenueDescriptionSectionById(chatId, ownerUserId, venueId, sectionId)
            return
        }
        if (input.length > 4000) {
            enqueueMessage(chatId, "Текст слишком длинный. Максимум 4000 символов.")
            return
        }
        val hasAccess =
            runCatching { venueAccessRepository.hasVenueAdminOrOwner(ownerUserId, venueId) }
                .onFailure { logBestEffort("check venue owner access on update section text", it) }
                .getOrDefault(false)
        if (!hasAccess) {
            dialogStateRepository.clear(chatId)
            enqueueMessage(chatId, "Нет доступа к заведению.")
            return
        }
        val updated =
            try {
                venueInfoSectionsRepository.updateSectionText(
                    venueId = venueId,
                    sectionId = sectionId,
                    textContent = input,
                )
            } catch (e: DatabaseUnavailableException) {
                enqueueMessage(chatId, "База недоступна, попробуйте позже.")
                return
            }
        if (!updated) {
            dialogStateRepository.clear(chatId)
            enqueueMessage(chatId, "Раздел не найден.")
            return
        }
        dialogStateRepository.clear(chatId)
        enqueueMessage(chatId, "✅ Текст обновлён.")
        showOwnerVenueDescriptionSectionById(chatId, ownerUserId, venueId, sectionId)
    }

    private suspend fun proceedOwnerVenueDescriptionSectionMedia(
        chatId: Long,
        from: User?,
        message: Message,
        state: DialogState,
    ) {
        val venueId = state.payload["venue_id"]?.toLongOrNull()
        val sectionId = state.payload["section_id"]?.toLongOrNull()
        val ownerUserId = (from?.id ?: state.payload["owner_user_id"]?.toLongOrNull())
        if (venueId == null || sectionId == null || ownerUserId == null) {
            dialogStateRepository.clear(chatId)
            enqueueMessage(chatId, "Не удалось сохранить медиа. Откройте раздел снова.")
            return
        }
        val cancelText = message.text?.trim()
        if (cancelText == "-" || cancelText == "—") {
            dialogStateRepository.clear(chatId)
            enqueueMessage(chatId, "Загрузка медиа отменена.")
            showOwnerVenueDescriptionSectionById(chatId, ownerUserId, venueId, sectionId)
            return
        }
        val hasAccess =
            runCatching { venueAccessRepository.hasVenueAdminOrOwner(ownerUserId, venueId) }
                .onFailure { logBestEffort("check venue owner access on upload section media", it) }
                .getOrDefault(false)
        if (!hasAccess) {
            dialogStateRepository.clear(chatId)
            enqueueMessage(chatId, "Нет доступа к заведению.")
            return
        }
        val photoFileId = message.photo?.maxByOrNull { it.fileSize ?: 0 }?.fileId
        if (photoFileId != null) {
            val saved =
                try {
                    venueInfoSectionMediaRepository.addMediaAttachment(
                        sectionId = sectionId,
                        mediaType = "image",
                        telegramFileId = photoFileId,
                    )
                } catch (e: DatabaseUnavailableException) {
                    enqueueMessage(chatId, "База недоступна, попробуйте позже.")
                    return
                }
            if (!saved) {
                dialogStateRepository.clear(chatId)
                enqueueMessage(chatId, "Раздел не найден.")
                showOwnerVenueDescriptionSectionsByVenueId(chatId, ownerUserId, venueId)
                return
            }
            dialogStateRepository.clear(chatId)
            enqueueMessage(chatId, "✅ Медиа добавлено.")
            showOwnerVenueDescriptionSectionsByVenueId(chatId, ownerUserId, venueId)
            return
        }
        val document = message.document
        if (document != null) {
            val isPdf =
                document.mimeType.equals("application/pdf", ignoreCase = true) ||
                    document.fileName?.endsWith(".pdf", ignoreCase = true) == true
            if (!isPdf) {
                enqueueMessage(chatId, "Поддерживаются только PDF-документы. Отправьте PDF или изображение.")
                return
            }
            val saved =
                try {
                    venueInfoSectionMediaRepository.addMediaAttachment(
                        sectionId = sectionId,
                        mediaType = "pdf",
                        telegramFileId = document.fileId,
                    )
                } catch (e: DatabaseUnavailableException) {
                    enqueueMessage(chatId, "База недоступна, попробуйте позже.")
                    return
                }
            if (!saved) {
                dialogStateRepository.clear(chatId)
                enqueueMessage(chatId, "Раздел не найден.")
                showOwnerVenueDescriptionSectionsByVenueId(chatId, ownerUserId, venueId)
                return
            }
            dialogStateRepository.clear(chatId)
            enqueueMessage(chatId, "✅ Медиа добавлено.")
            showOwnerVenueDescriptionSectionsByVenueId(chatId, ownerUserId, venueId)
            return
        }
        enqueueMessage(chatId, "Отправьте изображение или PDF для раздела.\nОтправьте «—», чтобы отменить.")
    }

    private suspend fun proceedOwnerVenueHoursOpen(
        chatId: Long,
        from: User?,
        text: String,
        state: DialogState,
    ) {
        val venueId = state.payload["venue_id"]?.toLongOrNull()
        val ownerUserId = (from?.id ?: state.payload["owner_user_id"]?.toLongOrNull())
        if (venueId == null || ownerUserId == null) {
            dialogStateRepository.clear(chatId)
            enqueueMessage(chatId, "Не удалось продолжить настройку часов. Откройте «🏢 Моё заведение» снова.")
            return
        }
        val mode = state.payload["mode"].orEmpty().ifBlank { "weekday" }
        val input = text.trim()
        if (input == "-" || input == "—") {
            dialogStateRepository.clear(chatId)
            enqueueMessage(chatId, "Настройка часов работы отменена.")
            if (mode == "override_time") {
                showOwnerVenueHoursOverridesByVenueId(chatId, ownerUserId, venueId)
            } else if (mode == "weekday") {
                val weekday = state.payload["weekday"]?.toIntOrNull()
                if (weekday != null) {
                    showOwnerVenueHoursDayScreenByVenueId(chatId, ownerUserId, venueId, weekday)
                } else {
                    showOwnerVenueHoursScreenByVenueId(chatId, ownerUserId, venueId)
                }
            } else {
                showOwnerVenueHoursScreenByVenueId(chatId, ownerUserId, venueId)
            }
            return
        }
        if (mode == "override_date") {
            val serviceDate = parseOwnerVenueDateInput(input)
            if (serviceDate == null) {
                enqueueMessage(chatId, "Некорректная дата. Введите в формате дд.мм.гггг, например 15.04.2026.")
                return
            }
            dialogStateRepository.clear(chatId)
            enqueueMessage(
                chatId,
                "Дата исключения: ${serviceDate.format(bookingDateConfirmFormatter)}.\nУкажите режим работы на эту дату.",
                TelegramKeyboards.inlineOwnerVenueHoursOverrideMode(venueId = venueId, dateIso = serviceDate.toString()),
            )
            return
        }
        val opensAt = parseOwnerVenueHoursTime(input)
        if (opensAt == null) {
            enqueueMessage(chatId, "Некорректное время. Введите в формате HH:mm, например 12:00.")
            return
        }
        val closePayload =
            buildMap<String, String> {
                put("venue_id", venueId.toString())
                put("owner_user_id", ownerUserId.toString())
                put("mode", mode)
                put("opens_at", opensAt.format(bookingTimeFormatter))
                state.payload["weekday"]?.let { put("weekday", it) }
                state.payload["service_date"]?.let { put("service_date", it) }
            }
        dialogStateRepository.set(
            chatId,
            DialogState(
                state = DialogStateType.OWNER_VENUE_PROFILE_WAIT_HOURS_CLOSE,
                payload = closePayload,
            ),
        )
        val closePrompt =
            when (mode) {
                "override_time" -> {
                    val serviceDate = state.payload["service_date"]?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
                    if (serviceDate != null) {
                        "Введите время закрытия для ${serviceDate.format(bookingDateConfirmFormatter)} в формате HH:mm (например, 23:00).\nОтправьте «—», чтобы отменить."
                    } else {
                        "Введите время закрытия в формате HH:mm, например 23:00.\nОтправьте «—», чтобы отменить."
                    }
                }
                "weekday" -> {
                    val weekday = state.payload["weekday"]?.toIntOrNull()
                    val dayLabel =
                        weekday
                            ?.takeIf { it in 1..7 }
                            ?.let { formatWeekday(DayOfWeek.of(it)) }
                            ?.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.ROOT) else it.toString() }
                    if (dayLabel != null) {
                        "$dayLabel: введите время закрытия в формате HH:mm (например, 23:00).\nОтправьте «—», чтобы отменить."
                    } else {
                        "Введите время закрытия в формате HH:mm, например 23:00.\nОтправьте «—», чтобы отменить."
                    }
                }
                else -> "Введите время закрытия в формате HH:mm, например 23:00.\nОтправьте «—», чтобы отменить."
            }
        enqueueMessage(chatId, closePrompt)
    }

    private suspend fun proceedOwnerVenueHoursClose(
        chatId: Long,
        from: User?,
        text: String,
        state: DialogState,
    ) {
        val venueId = state.payload["venue_id"]?.toLongOrNull()
        val ownerUserId = (from?.id ?: state.payload["owner_user_id"]?.toLongOrNull())
        val opensAt = state.payload["opens_at"]?.let { parseOwnerVenueHoursTime(it) }
        if (venueId == null || ownerUserId == null || opensAt == null) {
            dialogStateRepository.clear(chatId)
            enqueueMessage(chatId, "Не удалось продолжить настройку часов. Откройте «🏢 Моё заведение» снова.")
            return
        }
        val mode = state.payload["mode"].orEmpty().ifBlank { "weekday" }
        val input = text.trim()
        if (input == "-" || input == "—") {
            dialogStateRepository.clear(chatId)
            enqueueMessage(chatId, "Настройка часов работы отменена.")
            if (mode == "override_time") {
                showOwnerVenueHoursOverridesByVenueId(chatId, ownerUserId, venueId)
            } else {
                showOwnerVenueHoursScreenByVenueId(chatId, ownerUserId, venueId)
            }
            return
        }
        val closesAt = parseOwnerVenueHoursTime(input)
        if (closesAt == null) {
            enqueueMessage(chatId, "Некорректное время. Введите в формате HH:mm, например 23:00.")
            return
        }
        if (opensAt == closesAt) {
            enqueueMessage(chatId, "Время открытия и закрытия не должны совпадать. Укажите другое время закрытия.")
            return
        }
        val hasAccess =
            runCatching { venueAccessRepository.hasVenueAdminOrOwner(ownerUserId, venueId) }
                .onFailure { logBestEffort("check venue owner access on hours update", it) }
                .getOrDefault(false)
        if (!hasAccess) {
            dialogStateRepository.clear(chatId)
            enqueueMessage(chatId, "Нет доступа к заведению.")
            return
        }
        when (mode) {
            "weekday" -> {
                val weekday = state.payload["weekday"]?.toIntOrNull()
                if (weekday == null || weekday !in 1..7) {
                    dialogStateRepository.clear(chatId)
                    enqueueMessage(chatId, "Не удалось продолжить настройку часов. Откройте «🕒 Часы работы» снова.")
                    return
                }
                val updated =
                    try {
                        venueBookingHoursRepository.upsertWeekdayHours(
                            venueId = venueId,
                            weekday = weekday,
                            opensAt = opensAt,
                            closesAt = closesAt,
                        )
                    } catch (e: DatabaseUnavailableException) {
                        enqueueMessage(chatId, "База недоступна, попробуйте позже.")
                        return
                    }
                if (!updated) {
                    dialogStateRepository.clear(chatId)
                    enqueueMessage(chatId, "Заведение не найдено.")
                    return
                }
                dialogStateRepository.clear(chatId)
                val dayLabel = formatWeekday(DayOfWeek.of(weekday))
                enqueueMessage(chatId, "✅ ${dayLabel.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.ROOT) else it.toString() }}: часы обновлены.")
                showOwnerVenueHoursDayScreenByVenueId(chatId, ownerUserId, venueId, weekday)
            }
            "override_time" -> {
                val serviceDate = state.payload["service_date"]?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
                if (serviceDate == null) {
                    dialogStateRepository.clear(chatId)
                    enqueueMessage(chatId, "Не удалось продолжить настройку исключения. Откройте «📅 Исключения» снова.")
                    return
                }
                val updated =
                    try {
                        venueBookingHoursRepository.upsertDateOverride(
                            venueId = venueId,
                            serviceDate = serviceDate,
                            opensAt = opensAt,
                            closesAt = closesAt,
                        )
                    } catch (e: DatabaseUnavailableException) {
                        enqueueMessage(chatId, "База недоступна, попробуйте позже.")
                        return
                    }
                if (!updated) {
                    dialogStateRepository.clear(chatId)
                    enqueueMessage(chatId, "Заведение не найдено.")
                    return
                }
                dialogStateRepository.clear(chatId)
                enqueueMessage(chatId, "✅ Исключение на ${serviceDate.format(bookingDateConfirmFormatter)} обновлено.")
                showOwnerVenueHoursOverridesByVenueId(chatId, ownerUserId, venueId)
            }
            else -> {
                dialogStateRepository.clear(chatId)
                enqueueMessage(chatId, "Не удалось продолжить настройку часов. Откройте «🕒 Часы работы» снова.")
            }
        }
    }

    private suspend fun loadCurrentOwnerVenueCard(
        chatId: Long,
        userId: Long,
    ): OwnerVenueCard? {
        val memberships =
            try {
                venueAccessRepository.listVenueMemberships(userId)
            } catch (e: Exception) {
                logBestEffort("load venue memberships for owner venue card", e)
                enqueueMessage(chatId, "База недоступна, попробуйте позже.")
                return null
            }
        val ownerMembership =
            memberships.firstOrNull { it.role.equals("OWNER", ignoreCase = true) }
        if (ownerMembership == null) {
            enqueueMessage(chatId, "Доступ к заведению пока не найден.")
            return null
        }
        return loadOwnerVenueCardByVenueId(chatId, ownerMembership.venueId)
    }

    private suspend fun loadOwnerVenueCardByVenueId(
        chatId: Long,
        venueId: Long,
    ): OwnerVenueCard? {
        val description =
            runCatching {
                venueConnectionRequestRepository
                    .findApprovedByLinkedVenue(venueId)
                    ?.comment
                    ?.trim()
                    ?.takeIf { it.isNotBlank() }
            }.onFailure { logBestEffort("load owner venue description from request", it) }
                .getOrNull()
        val detail =
            runCatching { platformVenueRepository.getVenueDetail(venueId) }
                .getOrNull()
        if (detail != null) {
            return OwnerVenueCard(
                venueId = detail.id,
                name = detail.name,
                city = detail.city,
                address = detail.address,
                status = detail.status.dbValue,
                description = description,
            )
        }
        val venue =
            runCatching { venueRepository.findVenueById(venueId) }
                .onFailure { logBestEffort("load venue for owner venue card fallback", it) }
                .getOrNull()
        if (venue == null) {
            enqueueMessage(chatId, "Не удалось загрузить данные заведения.")
            return null
        }
        return OwnerVenueCard(
            venueId = venue.id,
            name = venue.name,
            city = null,
            address = null,
            status = null,
            description = description,
        )
    }

    private fun buildOwnerVenueCardText(card: OwnerVenueCard): String =
        buildString {
            append("🏢 Моё заведение")
            append("\n\nНазвание: ${card.name}")
            append("\nГород: ${card.city?.takeIf { it.isNotBlank() } ?: "—"}")
            append("\nАдрес: ${card.address?.takeIf { it.isNotBlank() } ?: "—"}")
            append("\nСтатус: ${card.status?.trim()?.lowercase(Locale.ROOT)?.ifBlank { "—" } ?: "—"}")
            append("\nОписание: ${card.description?.takeIf { it.isNotBlank() } ?: "—"}")
        }

    private suspend fun showVenueOwnerBotEntry(
        chatId: Long,
        from: User?,
    ) {
        botVenuePreviewContexts.remove(chatId)
        val userId = from?.id
        if (userId == null) {
            enqueueMessage(chatId, "Не удалось определить пользователя. Попробуйте /start.")
            return
        }
        val memberships =
            try {
                venueAccessRepository.listVenueMemberships(userId)
            } catch (e: Exception) {
                logBestEffort("load venue memberships for owner bot entry", e)
                enqueueMessage(chatId, "База недоступна, попробуйте позже.")
                return
            }
        val ownerMemberships = memberships.filter { it.role.equals("OWNER", ignoreCase = true) }
        if (ownerMemberships.isEmpty()) {
            enqueueMessage(chatId, "Доступ к настройке заведения пока не найден. Попробуйте позже.")
            return
        }
        val previewLines = mutableListOf<String>()
        ownerMemberships.take(3).forEach { membership ->
            val venueName =
                runCatching { venueRepository.findVenueById(membership.venueId)?.name }
                    .getOrNull()
                    ?.takeIf { it.isNotBlank() }
                    ?: "venue #${membership.venueId}"
            previewLines.add("• $venueName (${membership.role})")
        }
        val preview = previewLines.joinToString(separator = "\n")
        enqueueMessage(
            chatId,
            buildString {
                append("Режим владельца заведения.")
                append("\n\nВаши заведения:")
                append("\n$preview")
                if (ownerMemberships.size > 3) {
                    append("\n… и ещё ${ownerMemberships.size - 3}")
                }
                append("\n\nВыберите действие.")
            },
            TelegramKeyboards.venueOwnerMenu(),
        )
    }

    private suspend fun showVenueOwnerSectionPlaceholder(
        chatId: Long,
        from: User?,
        section: String,
    ) {
        if (!hasOwnedVenues(from?.id)) {
            showMainMenu(chatId, from)
            return
        }
        enqueueMessage(
            chatId,
            "Раздел «$section» — следующий шаг.",
            TelegramKeyboards.venueOwnerMenu(),
        )
    }

    private suspend fun showVenueOwnerStaffRoot(
        chatId: Long,
        from: User?,
    ) {
        val userId = from?.id
        if (userId == null) {
            enqueueMessage(chatId, "Не удалось определить пользователя. Попробуйте /start.")
            return
        }
        val venueCard = loadCurrentOwnerVenueCard(chatId, userId) ?: return
        showVenueOwnerStaffRootByVenueId(chatId, userId, venueCard.venueId)
    }

    private suspend fun showVenueOwnerStaffRootByCallback(
        chatId: Long,
        userId: Long,
        data: String,
    ) {
        val venueId = parseOwnerVenueIdFromCallback(data, "owner_venue_staff_root:")
        if (venueId == null) {
            enqueueMessage(chatId, "Не удалось открыть раздел. Попробуйте ещё раз.")
            return
        }
        showVenueOwnerStaffRootByVenueId(chatId, userId, venueId)
    }

    private suspend fun showVenueOwnerStaffRootByVenueId(
        chatId: Long,
        userId: Long,
        venueId: Long,
    ) {
        val hasAccess =
            runCatching { venueAccessRepository.hasVenueAdminOrOwner(userId, venueId) }
                .onFailure { logBestEffort("check venue owner access on staff root", it) }
                .getOrDefault(false)
        if (!hasAccess) {
            enqueueMessage(chatId, "Нет доступа к заведению.")
            return
        }
        val members =
            runCatching { venueAccessRepository.listVenueMembers(venueId) }
                .onFailure { logBestEffort("load venue members on staff root", it) }
                .getOrElse {
                    enqueueMessage(chatId, "База недоступна, попробуйте позже.")
                    return
                }
        val canRevokeAccess =
            runCatching {
                venueAccessRepository
                    .findVenueMembership(userId, venueId)
                    ?.role
                    ?.uppercase(Locale.ROOT) == "OWNER"
            }.onFailure { logBestEffort("load venue role on staff root", it) }
                .getOrDefault(false)
        val removableMembers =
            if (canRevokeAccess) {
                members
                    .filter { it.role.uppercase(Locale.ROOT) in setOf("ADMIN", "MANAGER", "STAFF") }
                    .map { it.userId to formatOwnerVenueStaffMember(it) }
            } else {
                emptyList()
            }
        enqueueMessage(
            chatId,
            buildOwnerVenueStaffRootText(members),
            TelegramKeyboards.inlineVenueOwnerStaffRootActions(venueId, removableMembers),
        )
    }

    private suspend fun showVenueOwnerStaffRoleChooser(
        chatId: Long,
        userId: Long,
        data: String,
    ) {
        val venueId = parseOwnerVenueIdFromCallback(data, "owner_venue_staff_add:")
        if (venueId == null) {
            enqueueMessage(chatId, "Не удалось открыть выбор роли. Попробуйте ещё раз.")
            return
        }
        val hasAccess =
            runCatching { venueAccessRepository.hasVenueAdminOrOwner(userId, venueId) }
                .onFailure { logBestEffort("check venue owner access on staff role chooser", it) }
                .getOrDefault(false)
        if (!hasAccess) {
            enqueueMessage(chatId, "Нет доступа к заведению.")
            return
        }
        enqueueMessage(
            chatId,
            "👥 Добавить сотрудника\n\nВыберите роль для нового сотрудника.\nНажмите кнопку ℹ️ рядом с ролью, чтобы посмотреть права.",
            TelegramKeyboards.inlineVenueOwnerStaffRoleChooserActions(venueId),
        )
    }

    private suspend fun showVenueOwnerStaffRoleInfo(
        chatId: Long,
        userId: Long,
        data: String,
    ) {
        val parsed = parseOwnerVenueStaffRoleData(data, "owner_venue_staff_role_info:")
        if (parsed == null) {
            enqueueMessage(chatId, "Не удалось открыть описание роли. Попробуйте ещё раз.")
            return
        }
        val (venueId, roleKey) = parsed
        val hasAccess =
            runCatching { venueAccessRepository.hasVenueAdminOrOwner(userId, venueId) }
                .onFailure { logBestEffort("check venue owner access on staff role info", it) }
                .getOrDefault(false)
        if (!hasAccess) {
            enqueueMessage(chatId, "Нет доступа к заведению.")
            return
        }
        val text = buildOwnerVenueStaffRoleInfoText(roleKey)
        if (text == null) {
            enqueueMessage(chatId, "Не удалось открыть описание роли. Попробуйте ещё раз.")
            return
        }
        enqueueMessage(
            chatId,
            text,
            TelegramKeyboards.inlineVenueOwnerStaffRoleInfoActions(venueId, roleKey),
        )
    }

    private suspend fun selectVenueOwnerStaffRole(
        chatId: Long,
        userId: Long,
        data: String,
    ) {
        val parsed = parseOwnerVenueStaffRoleData(data, "owner_venue_staff_role_select:")
        if (parsed == null) {
            enqueueMessage(chatId, "Не удалось выбрать роль. Попробуйте ещё раз.")
            return
        }
        val (venueId, roleKey) = parsed
        val hasAccess =
            runCatching { venueAccessRepository.hasVenueAdminOrOwner(userId, venueId) }
                .onFailure { logBestEffort("check venue owner access on staff role select", it) }
                .getOrDefault(false)
        if (!hasAccess) {
            enqueueMessage(chatId, "Нет доступа к заведению.")
            return
        }
        val roleDb =
            when (roleKey) {
                "owner" -> "OWNER"
                "manager" -> "MANAGER"
                "staff" -> "STAFF"
                else -> null
            }
        if (roleDb == null) {
            enqueueMessage(chatId, "Не удалось выбрать роль. Попробуйте ещё раз.")
            return
        }
        val invite =
            staffInviteRepository.createInvite(
                venueId = venueId,
                createdByUserId = userId,
                role = roleDb,
                ttlSeconds = staffInviteConfig.defaultTtlSeconds,
            )
        if (invite == null) {
            enqueueMessage(chatId, "База недоступна, попробуйте позже.")
            return
        }
        val roleLabel = humanizeOwnerVenueStaffRoleForChoice(roleKey)
        val botUsername = config.botUsername?.trim()?.removePrefix("@")?.takeIf { it.isNotBlank() }
        val inviteUrl =
            botUsername?.let { buildTelegramStartUrl(it, "$staffInviteStartPrefix${invite.code}") }
        val venueName = loadVenueNameForStaffInvite(venueId)
        enqueueMessage(
            chatId,
            buildOwnerStaffInviteCreatedText(
                venueName = venueName,
                roleLabel = roleLabel,
                inviteCode = invite.code,
                inviteUrl = inviteUrl,
                isStaffRole = roleKey == "staff",
            ),
            TelegramKeyboards.inlineVenueOwnerStaffInviteCreatedActions(venueId, roleKey),
        )
    }

    private suspend fun promptVenueOwnerStaffRemove(
        chatId: Long,
        userId: Long,
        data: String,
    ) {
        val parsed = parseOwnerVenueStaffMemberData(data, "owner_venue_staff_remove_prompt:")
        if (parsed == null) {
            enqueueMessage(chatId, "Не удалось открыть удаление сотрудника. Попробуйте ещё раз.")
            return
        }
        val (venueId, targetUserId) = parsed
        val role = resolveVenueRoleForVenue(chatId, userId, venueId) ?: return
        if (role != VenueBotRole.OWNER) {
            enqueueMessage(chatId, "Нет доступа к заведению.")
            return
        }
        if (targetUserId == userId) {
            enqueueMessage(chatId, "Нельзя удалить собственный доступ через этот раздел.")
            showVenueOwnerStaffRootByVenueId(chatId, userId, venueId)
            return
        }
        val members =
            runCatching { venueAccessRepository.listVenueMembers(venueId) }
                .onFailure { logBestEffort("load venue members for staff remove prompt", it) }
                .getOrElse {
                    enqueueMessage(chatId, "База недоступна, попробуйте позже.")
                    return
                }
        val targetMember = members.firstOrNull { it.userId == targetUserId }
        if (targetMember == null) {
            enqueueMessage(chatId, "Сотрудник не найден или уже удалён.")
            showVenueOwnerStaffRootByVenueId(chatId, userId, venueId)
            return
        }
        val targetRole = targetMember.role.uppercase(Locale.ROOT)
        if (targetRole !in setOf("ADMIN", "MANAGER", "STAFF")) {
            enqueueMessage(chatId, "Через этот экран можно удалять только Manager/Staff.")
            showVenueOwnerStaffRootByVenueId(chatId, userId, venueId)
            return
        }
        enqueueMessage(
            chatId,
            "Удалить сотрудника из заведения?\n" +
                "Он потеряет доступ к этому заведению.\n\n" +
                "${formatOwnerVenueStaffMember(targetMember)} — ${humanizeOwnerVenueStaffRole(targetMember.role)}",
            TelegramKeyboards.inlineVenueOwnerStaffRemoveConfirmActions(venueId, targetUserId),
        )
    }

    private suspend fun confirmVenueOwnerStaffRemove(
        chatId: Long,
        userId: Long,
        data: String,
    ) {
        val parsed = parseOwnerVenueStaffMemberData(data, "owner_venue_staff_remove_confirm:")
        if (parsed == null) {
            enqueueMessage(chatId, "Не удалось удалить доступ сотрудника. Попробуйте ещё раз.")
            return
        }
        val (venueId, targetUserId) = parsed
        val role = resolveVenueRoleForVenue(chatId, userId, venueId) ?: return
        if (role != VenueBotRole.OWNER) {
            enqueueMessage(chatId, "Нет доступа к заведению.")
            return
        }
        if (targetUserId == userId) {
            enqueueMessage(chatId, "Нельзя удалить собственный доступ через этот раздел.")
            showVenueOwnerStaffRootByVenueId(chatId, userId, venueId)
            return
        }
        val members =
            runCatching { venueAccessRepository.listVenueMembers(venueId) }
                .onFailure { logBestEffort("load venue members for staff remove confirm", it) }
                .getOrElse {
                    enqueueMessage(chatId, "База недоступна, попробуйте позже.")
                    return
                }
        val targetMember = members.firstOrNull { it.userId == targetUserId }
        if (targetMember == null) {
            enqueueMessage(chatId, "Сотрудник не найден или уже удалён.")
            showVenueOwnerStaffRootByVenueId(chatId, userId, venueId)
            return
        }
        val targetRole = targetMember.role.uppercase(Locale.ROOT)
        if (targetRole !in setOf("ADMIN", "MANAGER", "STAFF")) {
            enqueueMessage(chatId, "Через этот экран можно удалять только Manager/Staff.")
            showVenueOwnerStaffRootByVenueId(chatId, userId, venueId)
            return
        }
        val result =
            runCatching { venueStaffRepository.removeMemberWithOwnerGuard(venueId, targetUserId) }
                .onFailure { logBestEffort("remove venue member via owner staff flow", it) }
                .getOrElse {
                    enqueueMessage(chatId, "База недоступна, попробуйте позже.")
                    return
                }
        when (result) {
            VenueStaffRemoveResult.Success ->
                enqueueMessage(chatId, "✅ Доступ сотрудника удалён.")
            VenueStaffRemoveResult.NotFound ->
                enqueueMessage(chatId, "Сотрудник не найден или уже удалён.")
            VenueStaffRemoveResult.LastOwner ->
                enqueueMessage(chatId, "Нельзя удалить последнего owner.")
            VenueStaffRemoveResult.DatabaseError -> {
                enqueueMessage(chatId, "База недоступна, попробуйте позже.")
                return
            }
        }
        showVenueOwnerStaffRootByVenueId(chatId, userId, venueId)
    }

    private suspend fun showStaffInviteLanding(
        chatId: Long,
        from: User?,
        inviteCode: String,
    ) {
        val userId = from?.id
        if (userId == null) {
            enqueueMessage(chatId, "Не удалось определить пользователя. Попробуйте /start.")
            return
        }
        val preview =
            when (val result = staffInviteRepository.previewInvite(inviteCode)) {
                is StaffInvitePreviewResult.Success -> result.invite
                StaffInvitePreviewResult.InvalidOrExpired -> {
                    enqueueMessage(chatId, "Приглашение недействительно или истекло.")
                    return
                }
                StaffInvitePreviewResult.DatabaseError -> {
                    enqueueMessage(chatId, "База недоступна, попробуйте позже.")
                    return
                }
            }
        val venueName = loadVenueNameForStaffInvite(preview.venueId)
        val roleLabel = humanizeOwnerVenueStaffRole(preview.role)
        enqueueMessage(
            chatId,
            "Вас приглашают в заведение $venueName.\nРоль: $roleLabel",
            TelegramKeyboards.inlineStaffInviteDecisionActions(inviteCode),
        )
    }

    private suspend fun acceptStaffInvite(
        chatId: Long,
        userId: Long,
        data: String,
    ) {
        val inviteCode = parseStaffInviteCodeFromCallback(data, "staff_invite_accept:")
        if (inviteCode == null) {
            enqueueMessage(chatId, "Не удалось принять приглашение. Попробуйте ещё раз.")
            return
        }
        val result =
            staffInviteRepository.acceptInvite(
                code = inviteCode,
                userId = userId,
                createMember = { connection, venueId, role, invitedByUserId ->
                    venueStaffRepository.createMemberInTransaction(
                        connection = connection,
                        venueId = venueId,
                        userId = userId,
                        role = role,
                        invitedByUserId = invitedByUserId,
                    )
                },
            )
        when (result) {
            is StaffInviteAcceptResult.Success ->
                enqueueMessage(
                    chatId,
                    buildStaffInviteAcceptedText(result.alreadyMember),
                    resolvePostInviteReplyMarkup(userId),
                )
            StaffInviteAcceptResult.InvalidOrExpired ->
                enqueueMessage(chatId, "Приглашение недействительно или истекло.")
            StaffInviteAcceptResult.DatabaseError ->
                enqueueMessage(chatId, "База недоступна, попробуйте позже.")
        }
    }

    private suspend fun declineStaffInvite(
        chatId: Long,
        userId: Long,
        data: String,
    ) {
        val inviteCode = parseStaffInviteCodeFromCallback(data, "staff_invite_decline:")
        if (inviteCode == null) {
            enqueueMessage(chatId, "Не удалось отклонить приглашение. Попробуйте ещё раз.")
            return
        }
        when (staffInviteRepository.declineInvite(inviteCode, userId)) {
            StaffInviteDeclineResult.Success ->
                enqueueMessage(chatId, "Приглашение отклонено.", resolvePostInviteReplyMarkup(userId))
            StaffInviteDeclineResult.InvalidOrExpired ->
                enqueueMessage(chatId, "Приглашение недействительно или истекло.")
            StaffInviteDeclineResult.DatabaseError ->
                enqueueMessage(chatId, "База недоступна, попробуйте позже.")
        }
    }

    private suspend fun resolvePostInviteReplyMarkup(userId: Long): ReplyMarkup {
        if (isPlatformOwner(userId)) {
            return TelegramKeyboards.ownerMainMenu()
        }
        return when (val access = resolvePrimaryVenueBotAccess(userId)) {
            null -> {
                val hasVenueRole =
                    try {
                        venueAccessRepository.hasVenueRole(userId)
                    } catch (e: Exception) {
                        logBestEffort("resolve invite post-menu venue role", e)
                        false
                    }
                TelegramKeyboards.mainMenu(
                    hasVenueRole = hasVenueRole,
                    isPlatformOwner = false,
                    webAppUrl = config.webAppPublicUrl,
                )
            }
            else ->
                when (access.role) {
                    VenueBotRole.OWNER -> TelegramKeyboards.venueOwnerMenu()
                    VenueBotRole.MANAGER -> TelegramKeyboards.venueManagerMenu()
                    VenueBotRole.STAFF -> TelegramKeyboards.venueStaffMenu()
                }
        }
    }

    private suspend fun showVenueOwnerTablesRoot(
        chatId: Long,
        from: User?,
    ) {
        val userId = from?.id
        if (userId == null) {
            enqueueMessage(chatId, "Не удалось определить пользователя. Попробуйте /start.")
            return
        }
        val venueCard = loadCurrentOwnerVenueCard(chatId, userId) ?: return
        showVenueOwnerTablesRootByVenueId(chatId, userId, venueCard.venueId)
    }

    private suspend fun showVenueOwnerTablesRootByCallback(
        chatId: Long,
        userId: Long,
        data: String,
    ) {
        val venueId = parseOwnerVenueIdFromCallback(data, "owner_venue_tables_root:")
        if (venueId == null) {
            enqueueMessage(chatId, "Не удалось открыть раздел. Попробуйте ещё раз.")
            return
        }
        showVenueOwnerTablesRootByVenueId(chatId, userId, venueId)
    }

    private suspend fun showVenueOwnerTablesRootByVenueId(
        chatId: Long,
        userId: Long,
        venueId: Long,
    ) {
        val hasAccess =
            runCatching { venueAccessRepository.hasVenueAdminOrOwner(userId, venueId) }
                .onFailure { logBestEffort("check venue owner access on tables root", it) }
                .getOrDefault(false)
        if (!hasAccess) {
            enqueueMessage(chatId, "Нет доступа к заведению.")
            return
        }
        val tables = loadOwnerVenueTables(chatId, venueId, "load tables root") ?: return
        val totalCount = tables.size
        val activeCount = tables.count { it.isActive }
        val qrCreatedCount = tables.count { !it.token.isNullOrBlank() }
        enqueueMessage(
            chatId,
            buildString {
                append("🪑 Столы и QR")
                append("\n\nВсего столов: $totalCount")
                append("\nАктивных: $activeCount")
                append("\nQR создано: $qrCreatedCount")
            },
            TelegramKeyboards.inlineVenueOwnerTablesRootActions(venueId),
        )
    }

    private suspend fun showVenueOwnerTablesList(
        chatId: Long,
        userId: Long,
        data: String,
    ) {
        val venueId = parseOwnerVenueIdFromCallback(data, "owner_venue_tables_list:")
        if (venueId == null) {
            enqueueMessage(chatId, "Не удалось открыть список столов. Попробуйте ещё раз.")
            return
        }
        showVenueOwnerTablesListByVenueId(chatId, userId, venueId)
    }

    private suspend fun showVenueOwnerTablesListByVenueId(
        chatId: Long,
        userId: Long,
        venueId: Long,
    ) {
        val hasAccess =
            runCatching { venueAccessRepository.hasVenueAdminOrOwner(userId, venueId) }
                .onFailure { logBestEffort("check venue owner access on tables list", it) }
                .getOrDefault(false)
        if (!hasAccess) {
            enqueueMessage(chatId, "Нет доступа к заведению.")
            return
        }
        val tables = loadOwnerVenueTables(chatId, venueId, "load tables list") ?: return
        if (tables.isEmpty()) {
            enqueueMessage(
                chatId,
                "📋 Список столов\nСтолы пока не добавлены.",
                TelegramKeyboards.inlineVenueOwnerTablesListActions(venueId, tableButtons = emptyList()),
            )
            return
        }
        enqueueMessage(
            chatId,
            buildOwnerVenueTablesListText(tables),
            TelegramKeyboards.inlineVenueOwnerTablesListActions(
                venueId = venueId,
                tableButtons = tables.map { table -> table.tableId to "Стол ${table.tableNumber}" },
            ),
        )
    }

    private suspend fun promptVenueOwnerAddTable(
        chatId: Long,
        userId: Long,
        data: String,
    ) {
        val venueId = parseOwnerVenueIdFromCallback(data, "owner_venue_tables_add:")
        if (venueId == null) {
            enqueueMessage(chatId, "Не удалось начать добавление стола. Попробуйте ещё раз.")
            return
        }
        val hasAccess =
            runCatching { venueAccessRepository.hasVenueAdminOrOwner(userId, venueId) }
                .onFailure { logBestEffort("check venue owner access on add table", it) }
                .getOrDefault(false)
        if (!hasAccess) {
            enqueueMessage(chatId, "Нет доступа к заведению.")
            return
        }
        dialogStateRepository.set(
            chatId,
            DialogState(
                state = DialogStateType.OWNER_VENUE_TABLES_WAIT_NUMBER,
                payload =
                    mapOf(
                        "venue_id" to venueId.toString(),
                        "owner_user_id" to userId.toString(),
                    ),
            ),
        )
        enqueueMessage(chatId, "Введите номер стола.\nОтправьте «—», чтобы отменить.")
    }

    private suspend fun promptVenueOwnerEditTableNumber(
        chatId: Long,
        userId: Long,
        data: String,
    ) {
        val parsed = parseOwnerVenueTableData(data, "owner_venue_tables_table_rename:")
        if (parsed == null) {
            enqueueMessage(chatId, "Не удалось начать изменение номера. Попробуйте ещё раз.")
            return
        }
        val (venueId, tableId) = parsed
        val hasAccess =
            runCatching { venueAccessRepository.hasVenueAdminOrOwner(userId, venueId) }
                .onFailure { logBestEffort("check venue owner access on edit table number prompt", it) }
                .getOrDefault(false)
        if (!hasAccess) {
            enqueueMessage(chatId, "Нет доступа к заведению.")
            return
        }
        val table = loadOwnerVenueTable(chatId, venueId, tableId, "load table for number edit") ?: return
        dialogStateRepository.set(
            chatId,
            DialogState(
                state = DialogStateType.OWNER_VENUE_TABLES_WAIT_NUMBER,
                payload =
                    mapOf(
                        "venue_id" to venueId.toString(),
                        "owner_user_id" to userId.toString(),
                        "table_id" to tableId.toString(),
                        "mode" to "edit_number",
                    ),
            ),
        )
        enqueueMessage(
            chatId,
            "Введите новый номер для стола №${table.tableNumber}.\nОтправьте «—», чтобы отменить.",
        )
    }

    private suspend fun promptVenueOwnerEditTableCapacity(
        chatId: Long,
        userId: Long,
        data: String,
    ) {
        val parsed = parseOwnerVenueTableData(data, "owner_venue_tables_table_capacity:")
        if (parsed == null) {
            enqueueMessage(chatId, "Не удалось начать изменение вместимости. Попробуйте ещё раз.")
            return
        }
        val (venueId, tableId) = parsed
        val hasAccess =
            runCatching { venueAccessRepository.hasVenueAdminOrOwner(userId, venueId) }
                .onFailure { logBestEffort("check venue owner access on edit table capacity prompt", it) }
                .getOrDefault(false)
        if (!hasAccess) {
            enqueueMessage(chatId, "Нет доступа к заведению.")
            return
        }
        val table = loadOwnerVenueTable(chatId, venueId, tableId, "load table for capacity edit") ?: return
        dialogStateRepository.set(
            chatId,
            DialogState(
                state = DialogStateType.OWNER_VENUE_TABLES_WAIT_CAPACITY,
                payload =
                    mapOf(
                        "venue_id" to venueId.toString(),
                        "owner_user_id" to userId.toString(),
                        "table_id" to tableId.toString(),
                        "mode" to "edit_capacity",
                    ),
            ),
        )
        enqueueMessage(
            chatId,
            "Введите новую вместимость для стола №${table.tableNumber}.\nОтправьте «—», чтобы отменить.",
        )
    }

    private suspend fun proceedOwnerVenueAddTableNumber(
        chatId: Long,
        from: User?,
        text: String,
        state: DialogState,
    ) {
        val venueId = state.payload["venue_id"]?.toLongOrNull()
        val ownerUserId = state.payload["owner_user_id"]?.toLongOrNull() ?: from?.id
        val mode = state.payload["mode"]?.lowercase(Locale.ROOT).orEmpty()
        val tableId = state.payload["table_id"]?.toLongOrNull()
        if (venueId == null || ownerUserId == null) {
            dialogStateRepository.clear(chatId)
            enqueueMessage(chatId, "Не удалось продолжить добавление стола. Откройте раздел снова.")
            return
        }
        val input = text.trim()
        if (input == "-" || input == "—") {
            dialogStateRepository.clear(chatId)
            if (mode == "edit_number" && tableId != null) {
                showVenueOwnerTableByIds(chatId, ownerUserId, venueId, tableId)
            } else {
                showVenueOwnerTablesListByVenueId(chatId, ownerUserId, venueId)
            }
            return
        }
        val tableNumber = input.toIntOrNull()
        if (tableNumber == null || tableNumber <= 0) {
            enqueueMessage(chatId, "Номер стола должен быть положительным числом.")
            return
        }
        if (mode == "edit_number" && tableId != null) {
            val hasAccess =
                runCatching { venueAccessRepository.hasVenueAdminOrOwner(ownerUserId, venueId) }
                    .onFailure { logBestEffort("check venue owner access on edit table number submit", it) }
                    .getOrDefault(false)
            if (!hasAccess) {
                dialogStateRepository.clear(chatId)
                enqueueMessage(chatId, "Нет доступа к заведению.")
                return
            }
            val updated =
                try {
                    venueTableRepository.updateTableNumber(
                        venueId = venueId,
                        tableId = tableId,
                        tableNumber = tableNumber,
                    )
                } catch (_: TableNumberConflictException) {
                    enqueueMessage(chatId, "Стол с таким номером уже существует.\nВведите другой номер стола.")
                    return
                } catch (e: DatabaseUnavailableException) {
                    enqueueMessage(chatId, "База недоступна, попробуйте позже.")
                    return
                }
            if (!updated) {
                dialogStateRepository.clear(chatId)
                enqueueMessage(chatId, "Стол не найден.")
                return
            }
            dialogStateRepository.clear(chatId)
            enqueueMessage(chatId, "✅ Номер стола обновлён.")
            showVenueOwnerTableByIds(chatId, ownerUserId, venueId, tableId)
            return
        }
        dialogStateRepository.set(
            chatId,
            DialogState(
                state = DialogStateType.OWNER_VENUE_TABLES_WAIT_CAPACITY,
                payload =
                    mapOf(
                        "venue_id" to venueId.toString(),
                        "owner_user_id" to ownerUserId.toString(),
                        "table_number" to tableNumber.toString(),
                    ),
            ),
        )
        enqueueMessage(chatId, "Введите вместимость стола.\nТолько число, например 4.\nОтправьте «—», чтобы отменить.")
    }

    private suspend fun proceedOwnerVenueAddTableCapacity(
        chatId: Long,
        from: User?,
        text: String,
        state: DialogState,
    ) {
        val mode = state.payload["mode"]?.lowercase(Locale.ROOT).orEmpty()
        val tableId = state.payload["table_id"]?.toLongOrNull()
        val venueId = state.payload["venue_id"]?.toLongOrNull()
        val ownerUserId = state.payload["owner_user_id"]?.toLongOrNull() ?: from?.id
        if (venueId == null || ownerUserId == null) {
            dialogStateRepository.clear(chatId)
            enqueueMessage(chatId, "Не удалось продолжить добавление стола. Откройте раздел снова.")
            return
        }
        val input = text.trim()
        if (input == "-" || input == "—") {
            dialogStateRepository.clear(chatId)
            if (mode == "edit_capacity" && tableId != null) {
                showVenueOwnerTableByIds(chatId, ownerUserId, venueId, tableId)
            } else {
                showVenueOwnerTablesListByVenueId(chatId, ownerUserId, venueId)
            }
            return
        }
        val capacity = input.toIntOrNull()
        if (capacity == null || capacity <= 0) {
            enqueueMessage(chatId, "Вместимость должна быть положительным числом.")
            return
        }
        if (mode == "edit_capacity" && tableId != null) {
            val hasAccess =
                runCatching { venueAccessRepository.hasVenueAdminOrOwner(ownerUserId, venueId) }
                    .onFailure { logBestEffort("check venue owner access on edit table capacity submit", it) }
                    .getOrDefault(false)
            if (!hasAccess) {
                dialogStateRepository.clear(chatId)
                enqueueMessage(chatId, "Нет доступа к заведению.")
                return
            }
            val updated =
                try {
                    venueTableRepository.updateTableCapacity(
                        venueId = venueId,
                        tableId = tableId,
                        capacity = capacity,
                    )
                } catch (e: DatabaseUnavailableException) {
                    enqueueMessage(chatId, "База недоступна, попробуйте позже.")
                    return
                }
            if (!updated) {
                dialogStateRepository.clear(chatId)
                enqueueMessage(chatId, "Стол не найден.")
                return
            }
            dialogStateRepository.clear(chatId)
            enqueueMessage(chatId, "✅ Вместимость обновлена.")
            showVenueOwnerTableByIds(chatId, ownerUserId, venueId, tableId)
            return
        }
        val tableNumber = state.payload["table_number"]?.toIntOrNull()
        if (tableNumber == null) {
            dialogStateRepository.clear(chatId)
            enqueueMessage(chatId, "Не удалось продолжить добавление стола. Откройте раздел снова.")
            return
        }
        val hasAccess =
            runCatching { venueAccessRepository.hasVenueAdminOrOwner(ownerUserId, venueId) }
                .onFailure { logBestEffort("check venue owner access on add table submit", it) }
                .getOrDefault(false)
        if (!hasAccess) {
            dialogStateRepository.clear(chatId)
            enqueueMessage(chatId, "Нет доступа к заведению.")
            return
        }
        try {
            venueTableRepository.createTable(
                venueId = venueId,
                tableNumber = tableNumber,
                capacity = capacity,
            )
        } catch (_: TableNumberConflictException) {
            dialogStateRepository.set(
                chatId,
                DialogState(
                    state = DialogStateType.OWNER_VENUE_TABLES_WAIT_NUMBER,
                    payload =
                        mapOf(
                            "venue_id" to venueId.toString(),
                            "owner_user_id" to ownerUserId.toString(),
                        ),
                ),
            )
            enqueueMessage(chatId, "Стол с таким номером уже существует.\nВведите другой номер стола.")
            return
        } catch (e: DatabaseUnavailableException) {
            enqueueMessage(chatId, "База недоступна, попробуйте позже.")
            return
        }
        dialogStateRepository.clear(chatId)
        enqueueMessage(chatId, "✅ Стол добавлен.")
        showVenueOwnerTablesListByVenueId(chatId, ownerUserId, venueId)
    }

    private suspend fun showVenueOwnerTable(
        chatId: Long,
        userId: Long,
        data: String,
    ) {
        val parsed = parseOwnerVenueTableData(data, "owner_venue_tables_table:")
        if (parsed == null) {
            enqueueMessage(chatId, "Не удалось открыть стол. Попробуйте ещё раз.")
            return
        }
        val (venueId, tableId) = parsed
        val hasAccess =
            runCatching { venueAccessRepository.hasVenueAdminOrOwner(userId, venueId) }
                .onFailure { logBestEffort("check venue owner access on table details", it) }
                .getOrDefault(false)
        if (!hasAccess) {
            enqueueMessage(chatId, "Нет доступа к заведению.")
            return
        }
        showVenueOwnerTableByIds(chatId, userId, venueId, tableId)
    }

    private suspend fun showVenueOwnerTableByIds(
        chatId: Long,
        userId: Long,
        venueId: Long,
        tableId: Long,
    ) {
        val hasAccess =
            runCatching { venueAccessRepository.hasVenueAdminOrOwner(userId, venueId) }
                .onFailure { logBestEffort("check venue owner access on table details by ids", it) }
                .getOrDefault(false)
        if (!hasAccess) {
            enqueueMessage(chatId, "Нет доступа к заведению.")
            return
        }
        val table = loadOwnerVenueTable(chatId, venueId, tableId, "load table details") ?: return
        enqueueMessage(
            chatId,
            buildOwnerVenueTableDetailsText(table),
            TelegramKeyboards.inlineVenueOwnerTableActions(venueId = venueId, tableId = table.tableId),
        )
    }

    private suspend fun showVenueOwnerTablesQr(
        chatId: Long,
        userId: Long,
        data: String,
    ) {
        val venueId = parseOwnerVenueIdFromCallback(data, "owner_venue_tables_qr:")
        if (venueId == null) {
            enqueueMessage(chatId, "Не удалось открыть раздел QR. Попробуйте ещё раз.")
            return
        }
        val hasAccess =
            runCatching { venueAccessRepository.hasVenueAdminOrOwner(userId, venueId) }
                .onFailure { logBestEffort("check venue owner access on tables qr", it) }
                .getOrDefault(false)
        if (!hasAccess) {
            enqueueMessage(chatId, "Нет доступа к заведению.")
            return
        }
        val tables = loadOwnerVenueTables(chatId, venueId, "load tables qr") ?: return
        if (tables.isEmpty()) {
            enqueueMessage(
                chatId,
                "🧾 QR-коды\nСтолы пока не добавлены.",
                TelegramKeyboards.inlineVenueOwnerTablesQrActions(venueId = venueId, tableButtons = emptyList()),
            )
            return
        }
        enqueueMessage(
            chatId,
            "🧾 QR-коды\nВыберите стол.",
            TelegramKeyboards.inlineVenueOwnerTablesQrActions(
                venueId = venueId,
                tableButtons = tables.map { table -> table.tableId to "Стол ${table.tableNumber}" },
            ),
        )
    }

    private suspend fun showVenueOwnerTableQr(
        chatId: Long,
        userId: Long,
        data: String,
    ) {
        val parsed = parseOwnerVenueTableData(data, "owner_venue_tables_qr_table:")
        if (parsed == null) {
            enqueueMessage(chatId, "Не удалось открыть QR стола. Попробуйте ещё раз.")
            return
        }
        val (venueId, tableId) = parsed
        showVenueOwnerTableQrByIds(
            chatId = chatId,
            userId = userId,
            venueId = venueId,
            tableId = tableId,
            rotateToken = false,
        )
    }

    private suspend fun rotateVenueOwnerTableQr(
        chatId: Long,
        userId: Long,
        data: String,
    ) {
        val parsed = parseOwnerVenueTableData(data, "owner_venue_tables_qr_rotate:")
        if (parsed == null) {
            enqueueMessage(chatId, "Не удалось открыть QR стола. Попробуйте ещё раз.")
            return
        }
        val (venueId, tableId) = parsed
        showVenueOwnerTableQrByIds(
            chatId = chatId,
            userId = userId,
            venueId = venueId,
            tableId = tableId,
            rotateToken = true,
        )
    }

    private suspend fun showVenueOwnerTableQrByIds(
        chatId: Long,
        userId: Long,
        venueId: Long,
        tableId: Long,
        rotateToken: Boolean,
    ) {
        val hasAccess =
            runCatching { venueAccessRepository.hasVenueAdminOrOwner(userId, venueId) }
                .onFailure { logBestEffort("check venue owner access on table qr", it) }
                .getOrDefault(false)
        if (!hasAccess) {
            enqueueMessage(chatId, "Нет доступа к заведению.")
            return
        }
        val botUsername = config.botUsername?.trim()?.removePrefix("@")?.takeIf { it.isNotBlank() }
        if (botUsername == null) {
            enqueueMessage(
                chatId,
                "Не удалось сформировать QR: не задан TELEGRAM_BOT_USERNAME.",
                TelegramKeyboards.inlineVenueOwnerTableActions(venueId = venueId, tableId = tableId),
            )
            return
        }
        if (rotateToken) {
            val rotated =
                try {
                    venueTableRepository.rotateToken(venueId, tableId)
                } catch (e: DatabaseUnavailableException) {
                    enqueueMessage(chatId, "База недоступна, попробуйте позже.")
                    return
                } catch (e: Exception) {
                    logBestEffort("rotate table token from owner bot flow", e)
                    enqueueMessage(chatId, "Не удалось перевыпустить QR. Попробуйте ещё раз.")
                    return
                }
            if (rotated == null) {
                enqueueMessage(chatId, "Стол не найден.")
                return
            }
        }
        val tableWithToken = resolveOwnerTableForQr(chatId, venueId, tableId) ?: return
        val token = tableWithToken.token
        if (token.isNullOrBlank()) {
            enqueueMessage(chatId, "Не удалось сформировать QR: у стола отсутствует активный токен.")
            return
        }
        val startUrl = buildTelegramStartUrl(botUsername, token)
        val qrBytes =
            runCatching { generateQrPng(startUrl) }
                .onFailure { logBestEffort("generate table qr image", it) }
                .getOrNull()
        if (qrBytes == null) {
            sendOwnerTableQrFallback(
                chatId = chatId,
                venueId = venueId,
                tableId = tableWithToken.tableId,
                startUrl = startUrl,
                token = token,
            )
            return
        }
        val sent =
            runCatching {
                apiClient.sendPhotoBytes(
                    chatId = chatId,
                    photoBytes = qrBytes,
                    filename = "table-${tableWithToken.tableNumber}-qr.png",
                    caption = buildOwnerVenueTableQrText(tableWithToken, startUrl),
                    replyMarkup =
                        TelegramKeyboards.inlineVenueOwnerTableActions(
                            venueId = venueId,
                            tableId = tableWithToken.tableId,
                        ),
                )
            }.onFailure { logBestEffort("send table qr image", it) }
                .getOrDefault(false)
        if (!sent) {
            sendOwnerTableQrFallback(
                chatId = chatId,
                venueId = venueId,
                tableId = tableWithToken.tableId,
                startUrl = startUrl,
                token = token,
            )
            return
        }
        if (rotateToken) {
            enqueueMessage(
                chatId,
                "✅ QR перевыпущен.",
                TelegramKeyboards.inlineVenueOwnerTableActions(venueId = venueId, tableId = tableWithToken.tableId),
            )
        }
    }

    private suspend fun resolveOwnerTableForQr(
        chatId: Long,
        venueId: Long,
        tableId: Long,
    ): VenueTableOwnerSummary? {
        val table = loadOwnerVenueTable(chatId, venueId, tableId, "load table qr") ?: return null
        if (!table.token.isNullOrBlank()) {
            return table
        }
        val tokenProvisioned =
            try {
                venueTableRepository.rotateToken(venueId, tableId)
            } catch (e: DatabaseUnavailableException) {
                enqueueMessage(chatId, "База недоступна, попробуйте позже.")
                return null
            } catch (e: Exception) {
                logBestEffort("provision missing active table token from owner bot flow", e)
                null
            }
        if (tokenProvisioned == null) {
            return table
        }
        return loadOwnerVenueTable(chatId, venueId, tableId, "reload table qr after token provision") ?: table
    }

    private suspend fun loadOwnerVenueTables(
        chatId: Long,
        venueId: Long,
        opLabel: String,
    ): List<VenueTableOwnerSummary>? =
        try {
            venueTableRepository.listOwnerTables(venueId)
        } catch (e: DatabaseUnavailableException) {
            enqueueMessage(chatId, "База недоступна, попробуйте позже.")
            null
        } catch (e: Exception) {
            logBestEffort(opLabel, e)
            enqueueMessage(chatId, "База недоступна, попробуйте позже.")
            null
        }

    private suspend fun loadOwnerVenueTable(
        chatId: Long,
        venueId: Long,
        tableId: Long,
        opLabel: String,
    ): VenueTableOwnerSummary? {
        val table = loadOwnerVenueTables(chatId, venueId, opLabel)?.firstOrNull { it.tableId == tableId }
        if (table == null) {
            enqueueMessage(chatId, "Стол не найден.")
            return null
        }
        return table
    }

    private fun buildOwnerVenueTablesListText(tables: List<VenueTableOwnerSummary>): String =
        buildString {
            append("📋 Список столов")
            tables.forEach { table ->
                append("\n")
                append("• Стол ${table.tableNumber}")
                table.capacity?.let { capacity ->
                    append(" · $capacity мест")
                }
            }
        }

    private fun buildOwnerVenueTableDetailsText(table: VenueTableOwnerSummary): String =
        buildString {
            append("Стол №${table.tableNumber}")
            append("\nВместимость: ${table.capacity ?: "—"}")
            append("\nСтатус: ${if (table.isActive) "Активен" else "Неактивен"}")
        }

    private fun buildOwnerVenueTableQrText(
        table: VenueTableOwnerSummary,
        startUrl: String?,
    ): String =
        buildString {
            append("Стол №${table.tableNumber}")
            if (startUrl != null) {
                append("\nСсылка: $startUrl")
            }
        }

    private suspend fun sendOwnerTableQrFallback(
        chatId: Long,
        venueId: Long,
        tableId: Long,
        startUrl: String,
        token: String,
    ) {
        enqueueMessage(
            chatId,
            buildOwnerVenueTableQrFallbackText(startUrl = startUrl, token = token),
            TelegramKeyboards.inlineVenueOwnerTableQrFallbackActions(venueId = venueId, tableId = tableId),
        )
    }

    private fun buildOwnerVenueTableQrFallbackText(
        startUrl: String,
        token: String,
    ): String =
        buildString {
            append("QR-код не удалось сформировать автоматически.")
            append("\nСкопируйте ссылку ниже и вставьте её в генератор QR-кодов.")
            append("\n\nСсылка: $startUrl")
            append("\nТокен (резерв): $token")
        }

    private fun generateQrPng(
        content: String,
        size: Int = 768,
    ): ByteArray {
        val matrix = QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, size, size)
        val stream = ByteArrayOutputStream()
        MatrixToImageWriter.writeToStream(matrix, "PNG", stream)
        return stream.toByteArray()
    }

    private suspend fun showVenueOwnerOrderMenuRoot(
        chatId: Long,
        from: User?,
    ) {
        val userId = from?.id
        if (userId == null) {
            enqueueMessage(chatId, "Не удалось определить пользователя. Попробуйте /start.")
            return
        }
        val venueCard = loadCurrentOwnerVenueCard(chatId, userId) ?: return
        showVenueOwnerOrderMenuRootByVenueId(chatId, userId, venueCard.venueId)
    }

    private suspend fun showVenueOwnerOrderMenuRootByCallback(
        chatId: Long,
        userId: Long,
        data: String,
    ) {
        val venueId = parseOwnerVenueIdFromCallback(data, "owner_venue_order_menu_root:")
        if (venueId == null) {
            enqueueMessage(chatId, "Не удалось открыть раздел. Попробуйте ещё раз.")
            return
        }
        showVenueOwnerOrderMenuRootByVenueId(chatId, userId, venueId)
    }

    private suspend fun showVenueOwnerOrderMenuByLegacyCallback(
        chatId: Long,
        userId: Long,
        data: String,
    ) {
        val venueId = parseOwnerVenueIdFromCallback(data, "owner_venue_menu_order:")
        if (venueId == null) {
            enqueueMessage(chatId, "Не удалось открыть раздел. Попробуйте ещё раз.")
            return
        }
        showVenueOwnerOrderMenuRootByVenueId(chatId, userId, venueId)
    }

    private suspend fun showVenueOwnerOrderMenuRootByVenueId(
        chatId: Long,
        userId: Long,
        venueId: Long,
    ) {
        val hasAccess =
            runCatching { venueAccessRepository.hasVenueAdminOrOwner(userId, venueId) }
                .onFailure { logBestEffort("check venue owner access on order menu root", it) }
                .getOrDefault(false)
        if (!hasAccess) {
            enqueueMessage(chatId, "Нет доступа к заведению.")
            return
        }
        val categories = loadOrCreateOwnerOrderMenuCategories(chatId, venueId) ?: return
        enqueueMessage(
            chatId,
            "🍽 Заказное меню\nВыберите раздел.",
            TelegramKeyboards.inlineVenueOwnerOrderMenuRootActions(
                venueId = venueId,
                sectionButtons = categories.map { category -> category.id to category.name },
            ),
        )
    }

    private suspend fun loadOrCreateOwnerOrderMenuCategories(
        chatId: Long,
        venueId: Long,
    ): List<VenueMenuCategory>? {
        val categories =
            try {
                venueMenuRepository.getMenu(venueId)
            } catch (e: DatabaseUnavailableException) {
                enqueueMessage(chatId, "База недоступна, попробуйте позже.")
                return null
            }
        val requiredDefaults =
            listOf(
                "Кальянное меню",
                "Напитки",
                "Кухня",
            )
        val existingNames =
            categories
                .map { it.name.trim().lowercase(Locale.ROOT) }
                .toSet()
        var changed = false
        requiredDefaults.forEach { title ->
            if (!existingNames.contains(title.lowercase(Locale.ROOT))) {
                try {
                    venueMenuRepository.createCategory(venueId = venueId, name = title)
                    changed = true
                } catch (e: DatabaseUnavailableException) {
                    enqueueMessage(chatId, "База недоступна, попробуйте позже.")
                    return null
                }
            }
        }
        if (!changed) return categories
        return try {
            venueMenuRepository.getMenu(venueId)
        } catch (e: DatabaseUnavailableException) {
            enqueueMessage(chatId, "База недоступна, попробуйте позже.")
            null
        }
    }

    private suspend fun showVenueOwnerOrderMenuSection(
        chatId: Long,
        userId: Long,
        data: String,
    ) {
        val parsed = parseOwnerVenueMenuSectionData(data, "owner_venue_order_menu_section:")
        if (parsed == null) {
            enqueueMessage(chatId, "Не удалось открыть раздел. Попробуйте ещё раз.")
            return
        }
        showVenueOwnerOrderMenuSectionByIds(chatId, userId, parsed.first, parsed.second)
    }

    private suspend fun showVenueOwnerOrderMenuSectionByIds(
        chatId: Long,
        userId: Long,
        venueId: Long,
        sectionId: Long,
    ) {
        val hasAccess =
            runCatching { venueAccessRepository.hasVenueAdminOrOwner(userId, venueId) }
                .onFailure { logBestEffort("check venue owner access on order menu section", it) }
                .getOrDefault(false)
        if (!hasAccess) {
            enqueueMessage(chatId, "Нет доступа к заведению.")
            return
        }
        val category =
            loadOwnerOrderMenuCategory(chatId, venueId, sectionId)
                ?: run {
                    enqueueMessage(chatId, "Раздел не найден.")
                    showVenueOwnerOrderMenuRootByVenueId(chatId, userId, venueId)
                    return
                }
        enqueueMessage(
            chatId,
            buildOwnerVenueOrderMenuSectionText(category),
            TelegramKeyboards.inlineVenueOwnerOrderMenuSectionActions(
                venueId = venueId,
                sectionId = sectionId,
                itemButtons = category.items.map { item -> item.id to buildOwnerVenueOrderMenuItemButtonLabel(item) },
            ),
        )
    }

    private suspend fun promptVenueOwnerOrderMenuAddSection(
        chatId: Long,
        userId: Long,
        data: String,
    ) {
        val venueId = parseOwnerVenueIdFromCallback(data, "owner_venue_order_menu_add:")
        if (venueId == null) {
            enqueueMessage(chatId, "Не удалось добавить раздел. Попробуйте ещё раз.")
            return
        }
        val hasAccess =
            runCatching { venueAccessRepository.hasVenueAdminOrOwner(userId, venueId) }
                .onFailure { logBestEffort("check venue owner access on add order menu section", it) }
                .getOrDefault(false)
        if (!hasAccess) {
            enqueueMessage(chatId, "Нет доступа к заведению.")
            return
        }
        dialogStateRepository.set(
            chatId,
            DialogState(
                state = DialogStateType.OWNER_VENUE_ORDER_MENU_WAIT_SECTION_TITLE,
                payload =
                    mapOf(
                        "venue_id" to venueId.toString(),
                        "owner_user_id" to userId.toString(),
                    ),
            ),
        )
        enqueueMessage(chatId, "Введите название нового раздела.\nОтправьте «—», чтобы отменить.")
    }

    private suspend fun proceedOwnerVenueOrderMenuAddSection(
        chatId: Long,
        from: User?,
        text: String,
        state: DialogState,
    ) {
        val venueId = state.payload["venue_id"]?.toLongOrNull()
        val ownerUserId = (from?.id ?: state.payload["owner_user_id"]?.toLongOrNull())
        if (venueId == null || ownerUserId == null) {
            dialogStateRepository.clear(chatId)
            enqueueMessage(chatId, "Не удалось продолжить. Откройте «🍽 Меню заведения» снова.")
            return
        }
        val input = text.trim()
        if (input == "-" || input == "—") {
            dialogStateRepository.clear(chatId)
            enqueueMessage(chatId, "Добавление раздела отменено.")
            showVenueOwnerOrderMenuRootByVenueId(chatId, ownerUserId, venueId)
            return
        }
        val normalized = normalizeVenueConnectionRequiredField(input, maxLength = 120)
        if (normalized == null) {
            enqueueMessage(chatId, "Введите корректное название раздела (до 120 символов).")
            return
        }
        val hasAccess =
            runCatching { venueAccessRepository.hasVenueAdminOrOwner(ownerUserId, venueId) }
                .onFailure { logBestEffort("check venue owner access on add section submit", it) }
                .getOrDefault(false)
        if (!hasAccess) {
            dialogStateRepository.clear(chatId)
            enqueueMessage(chatId, "Нет доступа к заведению.")
            return
        }
        try {
            venueMenuRepository.createCategory(venueId = venueId, name = normalized)
        } catch (e: DatabaseUnavailableException) {
            enqueueMessage(chatId, "База недоступна, попробуйте позже.")
            return
        }
        dialogStateRepository.clear(chatId)
        enqueueMessage(chatId, "✅ Раздел добавлен.")
        showVenueOwnerOrderMenuRootByVenueId(chatId, ownerUserId, venueId)
    }

    private suspend fun promptVenueOwnerOrderMenuRenameSection(
        chatId: Long,
        userId: Long,
        data: String,
    ) {
        val parsed = parseOwnerVenueMenuSectionData(data, "owner_venue_order_menu_section_rename:")
        if (parsed == null) {
            enqueueMessage(chatId, "Не удалось открыть раздел. Попробуйте ещё раз.")
            return
        }
        val (venueId, sectionId) = parsed
        val hasAccess =
            runCatching { venueAccessRepository.hasVenueAdminOrOwner(userId, venueId) }
                .onFailure { logBestEffort("check venue owner access on rename order menu section", it) }
                .getOrDefault(false)
        if (!hasAccess) {
            enqueueMessage(chatId, "Нет доступа к заведению.")
            return
        }
        dialogStateRepository.set(
            chatId,
            DialogState(
                state = DialogStateType.OWNER_VENUE_ORDER_MENU_WAIT_SECTION_RENAME,
                payload =
                    mapOf(
                        "venue_id" to venueId.toString(),
                        "section_id" to sectionId.toString(),
                        "owner_user_id" to userId.toString(),
                    ),
            ),
        )
        enqueueMessage(chatId, "Введите новое название раздела.\nОтправьте «—», чтобы отменить.")
    }

    private suspend fun proceedOwnerVenueOrderMenuRenameSection(
        chatId: Long,
        from: User?,
        text: String,
        state: DialogState,
    ) {
        val venueId = state.payload["venue_id"]?.toLongOrNull()
        val sectionId = state.payload["section_id"]?.toLongOrNull()
        val ownerUserId = (from?.id ?: state.payload["owner_user_id"]?.toLongOrNull())
        if (venueId == null || sectionId == null || ownerUserId == null) {
            dialogStateRepository.clear(chatId)
            enqueueMessage(chatId, "Не удалось продолжить. Откройте «🍽 Меню заведения» снова.")
            return
        }
        val input = text.trim()
        if (input == "-" || input == "—") {
            dialogStateRepository.clear(chatId)
            enqueueMessage(chatId, "Переименование раздела отменено.")
            showVenueOwnerOrderMenuSectionByIds(chatId, ownerUserId, venueId, sectionId)
            return
        }
        val normalized = normalizeVenueConnectionRequiredField(input, maxLength = 120)
        if (normalized == null) {
            enqueueMessage(chatId, "Введите корректное название раздела (до 120 символов).")
            return
        }
        val hasAccess =
            runCatching { venueAccessRepository.hasVenueAdminOrOwner(ownerUserId, venueId) }
                .onFailure { logBestEffort("check venue owner access on rename section submit", it) }
                .getOrDefault(false)
        if (!hasAccess) {
            dialogStateRepository.clear(chatId)
            enqueueMessage(chatId, "Нет доступа к заведению.")
            return
        }
        val updated =
            try {
                venueMenuRepository.updateCategory(
                    venueId = venueId,
                    categoryId = sectionId,
                    name = normalized,
                )
            } catch (e: DatabaseUnavailableException) {
                enqueueMessage(chatId, "База недоступна, попробуйте позже.")
                return
            }
        if (updated == null) {
            dialogStateRepository.clear(chatId)
            enqueueMessage(chatId, "Раздел не найден.")
            showVenueOwnerOrderMenuRootByVenueId(chatId, ownerUserId, venueId)
            return
        }
        dialogStateRepository.clear(chatId)
        enqueueMessage(chatId, "✅ Раздел переименован.")
        showVenueOwnerOrderMenuSectionByIds(chatId, ownerUserId, venueId, sectionId)
    }

    private suspend fun deleteVenueOwnerOrderMenuSection(
        chatId: Long,
        userId: Long,
        data: String,
    ) {
        val parsed = parseOwnerVenueMenuSectionData(data, "owner_venue_order_menu_section_delete:")
        if (parsed == null) {
            enqueueMessage(chatId, "Не удалось удалить раздел. Попробуйте ещё раз.")
            return
        }
        val (venueId, sectionId) = parsed
        val hasAccess =
            runCatching { venueAccessRepository.hasVenueAdminOrOwner(userId, venueId) }
                .onFailure { logBestEffort("check venue owner access on delete order menu section", it) }
                .getOrDefault(false)
        if (!hasAccess) {
            enqueueMessage(chatId, "Нет доступа к заведению.")
            return
        }
        val deleted =
            try {
                venueMenuRepository.deleteCategory(venueId = venueId, categoryId = sectionId)
            } catch (e: DatabaseUnavailableException) {
                enqueueMessage(chatId, "База недоступна, попробуйте позже.")
                return
            }
        if (!deleted) {
            enqueueMessage(chatId, "Раздел нельзя удалить, пока в нём есть позиции.")
            showVenueOwnerOrderMenuSectionByIds(chatId, userId, venueId, sectionId)
            return
        }
        enqueueMessage(chatId, "✅ Раздел удалён.")
        showVenueOwnerOrderMenuRootByVenueId(chatId, userId, venueId)
    }

    private suspend fun promptVenueOwnerOrderMenuAddItem(
        chatId: Long,
        userId: Long,
        data: String,
    ) {
        val parsed = parseOwnerVenueMenuSectionData(data, "owner_venue_order_menu_item_add:")
        if (parsed == null) {
            enqueueMessage(chatId, "Не удалось открыть раздел. Попробуйте ещё раз.")
            return
        }
        val (venueId, sectionId) = parsed
        val category = loadOwnerOrderMenuCategory(chatId, venueId, sectionId) ?: run {
            enqueueMessage(chatId, "Раздел не найден.")
            return
        }
        val hasAccess =
            runCatching { venueAccessRepository.hasVenueAdminOrOwner(userId, venueId) }
                .onFailure { logBestEffort("check venue owner access on order menu item add", it) }
                .getOrDefault(false)
        if (!hasAccess) {
            enqueueMessage(chatId, "Нет доступа к заведению.")
            return
        }
        dialogStateRepository.set(
            chatId,
            DialogState(
                state = DialogStateType.OWNER_VENUE_ORDER_MENU_WAIT_ITEM_NAME,
                payload =
                    mapOf(
                        "venue_id" to venueId.toString(),
                        "section_id" to sectionId.toString(),
                        "owner_user_id" to userId.toString(),
                    ),
            ),
        )
        enqueueMessage(chatId, "Введите название позиции.\nОтправьте «—», чтобы отменить.")
    }

    private suspend fun proceedOwnerVenueOrderMenuAddItemName(
        chatId: Long,
        from: User?,
        text: String,
        state: DialogState,
    ) {
        val venueId = state.payload["venue_id"]?.toLongOrNull()
        val sectionId = state.payload["section_id"]?.toLongOrNull()
        val ownerUserId = (from?.id ?: state.payload["owner_user_id"]?.toLongOrNull())
        if (venueId == null || sectionId == null || ownerUserId == null) {
            dialogStateRepository.clear(chatId)
            enqueueMessage(chatId, "Не удалось продолжить. Откройте «🍽 Меню заведения» снова.")
            return
        }
        val input = text.trim()
        if (input == "-" || input == "—") {
            dialogStateRepository.clear(chatId)
            enqueueMessage(chatId, "Добавление позиции отменено.")
            showVenueOwnerOrderMenuSectionByIds(chatId, ownerUserId, venueId, sectionId)
            return
        }
        val normalized = normalizeVenueConnectionRequiredField(input, maxLength = 120)
        if (normalized == null) {
            enqueueMessage(chatId, "Введите корректное название позиции (до 120 символов).")
            return
        }
        val hasAccess =
            runCatching { venueAccessRepository.hasVenueAdminOrOwner(ownerUserId, venueId) }
                .onFailure { logBestEffort("check venue owner access on add item name", it) }
                .getOrDefault(false)
        if (!hasAccess) {
            dialogStateRepository.clear(chatId)
            enqueueMessage(chatId, "Нет доступа к заведению.")
            return
        }
        dialogStateRepository.set(
            chatId,
            DialogState(
                state = DialogStateType.OWNER_VENUE_ORDER_MENU_WAIT_ITEM_PRICE,
                payload =
                    mapOf(
                        "venue_id" to venueId.toString(),
                        "section_id" to sectionId.toString(),
                        "owner_user_id" to ownerUserId.toString(),
                        "item_name" to normalized,
                    ),
            ),
        )
        enqueueMessage(chatId, "Укажите цену позиции в рублях.\nТолько число, например 850")
    }

    private suspend fun proceedOwnerVenueOrderMenuAddItemPrice(
        chatId: Long,
        from: User?,
        text: String,
        state: DialogState,
    ) {
        val venueId = state.payload["venue_id"]?.toLongOrNull()
        val sectionId = state.payload["section_id"]?.toLongOrNull()
        val ownerUserId = (from?.id ?: state.payload["owner_user_id"]?.toLongOrNull())
        val itemName = state.payload["item_name"]?.trim()
        if (venueId == null || sectionId == null || ownerUserId == null || itemName.isNullOrBlank()) {
            dialogStateRepository.clear(chatId)
            enqueueMessage(chatId, "Не удалось продолжить. Откройте «🍽 Меню заведения» снова.")
            return
        }
        val priceRub = parsePositiveRubAmount(text)
        if (priceRub == null) {
            enqueueMessage(chatId, "Введите корректную цену в рублях (только число, например 850).")
            return
        }
        val hasAccess =
            runCatching { venueAccessRepository.hasVenueAdminOrOwner(ownerUserId, venueId) }
                .onFailure { logBestEffort("check venue owner access on add item price", it) }
                .getOrDefault(false)
        if (!hasAccess) {
            dialogStateRepository.clear(chatId)
            enqueueMessage(chatId, "Нет доступа к заведению.")
            return
        }
        val created =
            try {
                venueMenuRepository.createItem(
                    venueId = venueId,
                    categoryId = sectionId,
                    name = itemName,
                    priceMinor = priceRub * 100,
                    currency = "RUB",
                    isAvailable = true,
                )
            } catch (e: DatabaseUnavailableException) {
                enqueueMessage(chatId, "База недоступна, попробуйте позже.")
                return
            }
        if (created == null) {
            dialogStateRepository.clear(chatId)
            enqueueMessage(chatId, "Не удалось добавить позицию. Раздел не найден.")
            showVenueOwnerOrderMenuRootByVenueId(chatId, ownerUserId, venueId)
            return
        }
        dialogStateRepository.clear(chatId)
        enqueueMessage(chatId, "✅ Позиция добавлена.")
        showVenueOwnerOrderMenuSectionByIds(chatId, ownerUserId, venueId, sectionId)
    }

    private suspend fun showVenueOwnerOrderMenuItem(
        chatId: Long,
        userId: Long,
        data: String,
    ) {
        val parsed = parseOwnerVenueMenuItemData(data, "owner_venue_order_menu_item:")
        if (parsed == null) {
            enqueueMessage(chatId, "Не удалось открыть позицию. Попробуйте ещё раз.")
            return
        }
        val (venueId, sectionId, itemId) = parsed
        showVenueOwnerOrderMenuItemByIds(chatId, userId, venueId, sectionId, itemId)
    }

    private suspend fun showVenueOwnerOrderMenuItemByIds(
        chatId: Long,
        userId: Long,
        venueId: Long,
        sectionId: Long,
        itemId: Long,
    ) {
        val hasAccess =
            runCatching { venueAccessRepository.hasVenueAdminOrOwner(userId, venueId) }
                .onFailure { logBestEffort("check venue owner access on order menu item", it) }
                .getOrDefault(false)
        if (!hasAccess) {
            enqueueMessage(chatId, "Нет доступа к заведению.")
            return
        }
        var category =
            loadOwnerOrderMenuCategory(chatId, venueId, sectionId)
                ?: run {
                    enqueueMessage(chatId, "Раздел не найден.")
                    showVenueOwnerOrderMenuRootByVenueId(chatId, userId, venueId)
                    return
                }
        var item =
            category.items.firstOrNull { it.id == itemId }
                ?: run {
                    enqueueMessage(chatId, "Позиция не найдена.")
                    showVenueOwnerOrderMenuSectionByIds(chatId, userId, venueId, sectionId)
                    return
                }
        if (isHookahMenuSection(category)) {
            val defaultsEnsured = ensureDefaultHookahFlavorProfiles(chatId, venueId, item)
            if (defaultsEnsured == null) {
                return
            }
            if (defaultsEnsured) {
                category = loadOwnerOrderMenuCategory(chatId, venueId, sectionId) ?: return
                item =
                    category.items.firstOrNull { it.id == itemId }
                        ?: run {
                            enqueueMessage(chatId, "Позиция не найдена.")
                            showVenueOwnerOrderMenuSectionByIds(chatId, userId, venueId, sectionId)
                            return
                        }
            }
        }
        enqueueMessage(
            chatId,
            buildOwnerVenueOrderMenuItemText(item, showFlavorSummary = isHookahMenuSection(category)),
            TelegramKeyboards.inlineVenueOwnerOrderMenuItemActions(
                venueId = venueId,
                sectionId = sectionId,
                itemId = itemId,
                isAvailable = item.isAvailable,
                showFlavorProfile = isHookahMenuSection(category),
            ),
        )
    }

    private suspend fun showVenueOwnerOrderMenuItemFlavors(
        chatId: Long,
        userId: Long,
        data: String,
    ) {
        val parsed = parseOwnerVenueMenuItemData(data, "owner_venue_order_menu_item_flavors:")
        if (parsed == null) {
            enqueueMessage(chatId, "Не удалось открыть вкусы. Попробуйте ещё раз.")
            return
        }
        val (venueId, sectionId, itemId) = parsed
        showVenueOwnerOrderMenuItemFlavorsByIds(chatId, userId, venueId, sectionId, itemId)
    }

    private suspend fun showVenueOwnerOrderMenuItemFlavorsByIds(
        chatId: Long,
        userId: Long,
        venueId: Long,
        sectionId: Long,
        itemId: Long,
    ) {
        val hasAccess =
            runCatching { venueAccessRepository.hasVenueAdminOrOwner(userId, venueId) }
                .onFailure { logBestEffort("check venue owner access on order menu flavor list", it) }
                .getOrDefault(false)
        if (!hasAccess) {
            enqueueMessage(chatId, "Нет доступа к заведению.")
            return
        }
        val (category, item) = loadOwnerOrderMenuItem(chatId, venueId, sectionId, itemId) ?: return
        if (!isHookahMenuSection(category)) {
            enqueueMessage(chatId, "Профиль вкуса доступен только для раздела «Кальянное меню».")
            return
        }
        val defaultsEnsured = ensureDefaultHookahFlavorProfiles(chatId, venueId, item) ?: return
        val reloadedItem =
            if (defaultsEnsured) {
                loadOwnerOrderMenuItem(chatId, venueId, sectionId, itemId)?.second
            } else {
                item
            } ?: return
        enqueueMessage(
            chatId,
            buildOwnerVenueOrderMenuFlavorListText(reloadedItem),
            TelegramKeyboards.inlineVenueOwnerOrderMenuFlavorListActions(
                venueId = venueId,
                sectionId = sectionId,
                itemId = itemId,
                flavorButtons =
                    reloadedItem.options.map { option ->
                        option.id to buildOwnerVenueOrderMenuFlavorButtonLabel(option)
                    },
            ),
        )
    }

    private suspend fun showVenueOwnerOrderMenuFlavorOption(
        chatId: Long,
        userId: Long,
        data: String,
    ) {
        val parsed = parseOwnerVenueMenuOptionData(data, "owner_venue_order_menu_item_option:")
        if (parsed == null) {
            enqueueMessage(chatId, "Не удалось открыть вкус. Попробуйте ещё раз.")
            return
        }
        val (venueId, sectionId, itemId, optionId) = parsed
        showVenueOwnerOrderMenuFlavorOptionByIds(chatId, userId, venueId, sectionId, itemId, optionId)
    }

    private suspend fun showVenueOwnerOrderMenuFlavorOptionByIds(
        chatId: Long,
        userId: Long,
        venueId: Long,
        sectionId: Long,
        itemId: Long,
        optionId: Long,
    ) {
        val hasAccess =
            runCatching { venueAccessRepository.hasVenueAdminOrOwner(userId, venueId) }
                .onFailure { logBestEffort("check venue owner access on order menu flavor option", it) }
                .getOrDefault(false)
        if (!hasAccess) {
            enqueueMessage(chatId, "Нет доступа к заведению.")
            return
        }
        val (category, item) = loadOwnerOrderMenuItem(chatId, venueId, sectionId, itemId) ?: return
        if (!isHookahMenuSection(category)) {
            enqueueMessage(chatId, "Профиль вкуса доступен только для раздела «Кальянное меню».")
            return
        }
        val option =
            item.options.firstOrNull { it.id == optionId }
                ?: run {
                    enqueueMessage(chatId, "Вкус не найден.")
                    showVenueOwnerOrderMenuItemFlavorsByIds(chatId, userId, venueId, sectionId, itemId)
                    return
                }
        enqueueMessage(
            chatId,
            buildOwnerVenueOrderMenuFlavorOptionText(option),
            TelegramKeyboards.inlineVenueOwnerOrderMenuFlavorOptionActions(
                venueId = venueId,
                sectionId = sectionId,
                itemId = itemId,
                optionId = option.id,
                isAvailable = option.isAvailable,
            ),
        )
    }

    private suspend fun promptVenueOwnerOrderMenuAddFlavor(
        chatId: Long,
        userId: Long,
        data: String,
    ) {
        val parsed = parseOwnerVenueMenuItemData(data, "owner_venue_order_menu_item_option_add:")
        if (parsed == null) {
            enqueueMessage(chatId, "Не удалось начать добавление вкуса. Попробуйте ещё раз.")
            return
        }
        val (venueId, sectionId, itemId) = parsed
        val hasAccess =
            runCatching { venueAccessRepository.hasVenueAdminOrOwner(userId, venueId) }
                .onFailure { logBestEffort("check venue owner access on add flavor prompt", it) }
                .getOrDefault(false)
        if (!hasAccess) {
            enqueueMessage(chatId, "Нет доступа к заведению.")
            return
        }
        val (category, _) = loadOwnerOrderMenuItem(chatId, venueId, sectionId, itemId) ?: return
        if (!isHookahMenuSection(category)) {
            enqueueMessage(chatId, "Профиль вкуса доступен только для раздела «Кальянное меню».")
            return
        }
        dialogStateRepository.set(
            chatId,
            DialogState(
                state = DialogStateType.OWNER_VENUE_ORDER_MENU_WAIT_OPTION_NAME,
                payload =
                    mapOf(
                        "mode" to "add",
                        "venue_id" to venueId.toString(),
                        "section_id" to sectionId.toString(),
                        "item_id" to itemId.toString(),
                        "owner_user_id" to userId.toString(),
                    ),
            ),
        )
        enqueueMessage(chatId, "Введите название вкуса.\nОтправьте «—», чтобы отменить.")
    }

    private suspend fun promptVenueOwnerOrderMenuRenameFlavor(
        chatId: Long,
        userId: Long,
        data: String,
    ) {
        val parsed = parseOwnerVenueMenuOptionData(data, "owner_venue_order_menu_item_option_rename:")
        if (parsed == null) {
            enqueueMessage(chatId, "Не удалось открыть вкус. Попробуйте ещё раз.")
            return
        }
        val (venueId, sectionId, itemId, optionId) = parsed
        val hasAccess =
            runCatching { venueAccessRepository.hasVenueAdminOrOwner(userId, venueId) }
                .onFailure { logBestEffort("check venue owner access on rename flavor prompt", it) }
                .getOrDefault(false)
        if (!hasAccess) {
            enqueueMessage(chatId, "Нет доступа к заведению.")
            return
        }
        val (category, item) = loadOwnerOrderMenuItem(chatId, venueId, sectionId, itemId) ?: return
        if (!isHookahMenuSection(category)) {
            enqueueMessage(chatId, "Профиль вкуса доступен только для раздела «Кальянное меню».")
            return
        }
        val option =
            item.options.firstOrNull { it.id == optionId }
                ?: run {
                    enqueueMessage(chatId, "Вкус не найден.")
                    return
                }
        dialogStateRepository.set(
            chatId,
            DialogState(
                state = DialogStateType.OWNER_VENUE_ORDER_MENU_WAIT_OPTION_NAME,
                payload =
                    mapOf(
                        "mode" to "rename",
                        "venue_id" to venueId.toString(),
                        "section_id" to sectionId.toString(),
                        "item_id" to itemId.toString(),
                        "option_id" to optionId.toString(),
                        "owner_user_id" to userId.toString(),
                    ),
            ),
        )
        enqueueMessage(chatId, "Текущий вкус: ${option.name}\nВведите новое название вкуса.\nОтправьте «—», чтобы отменить.")
    }

    private suspend fun proceedOwnerVenueOrderMenuFlavorName(
        chatId: Long,
        from: User?,
        text: String,
        state: DialogState,
    ) {
        val mode = state.payload["mode"]?.lowercase(Locale.ROOT).orEmpty()
        val venueId = state.payload["venue_id"]?.toLongOrNull()
        val sectionId = state.payload["section_id"]?.toLongOrNull()
        val itemId = state.payload["item_id"]?.toLongOrNull()
        val optionId = state.payload["option_id"]?.toLongOrNull()
        val ownerUserId = (from?.id ?: state.payload["owner_user_id"]?.toLongOrNull())
        if (venueId == null || sectionId == null || itemId == null || ownerUserId == null) {
            dialogStateRepository.clear(chatId)
            enqueueMessage(chatId, "Не удалось продолжить. Откройте «🍽 Меню заведения» снова.")
            return
        }
        val input = text.trim()
        if (input == "-" || input == "—") {
            dialogStateRepository.clear(chatId)
            if (mode == "rename" && optionId != null) {
                showVenueOwnerOrderMenuFlavorOptionByIds(chatId, ownerUserId, venueId, sectionId, itemId, optionId)
            } else {
                showVenueOwnerOrderMenuItemFlavorsByIds(chatId, ownerUserId, venueId, sectionId, itemId)
            }
            return
        }
        val normalized = normalizeVenueConnectionRequiredField(input, maxLength = 120)
        if (normalized == null) {
            enqueueMessage(chatId, "Введите корректное название вкуса (до 120 символов).")
            return
        }
        val hasAccess =
            runCatching { venueAccessRepository.hasVenueAdminOrOwner(ownerUserId, venueId) }
                .onFailure { logBestEffort("check venue owner access on save flavor", it) }
                .getOrDefault(false)
        if (!hasAccess) {
            dialogStateRepository.clear(chatId)
            enqueueMessage(chatId, "Нет доступа к заведению.")
            return
        }
        if (mode == "rename" && optionId != null) {
            val updated =
                try {
                    venueMenuRepository.updateOption(
                        venueId = venueId,
                        optionId = optionId,
                        name = normalized,
                        priceDeltaMinor = null,
                        isAvailable = null,
                    )
                } catch (e: DatabaseUnavailableException) {
                    enqueueMessage(chatId, "База недоступна, попробуйте позже.")
                    return
                }
            if (updated == null || updated.itemId != itemId) {
                dialogStateRepository.clear(chatId)
                enqueueMessage(chatId, "Вкус не найден.")
                showVenueOwnerOrderMenuItemFlavorsByIds(chatId, ownerUserId, venueId, sectionId, itemId)
                return
            }
            dialogStateRepository.clear(chatId)
            enqueueMessage(chatId, "✅ Вкус переименован.")
            showVenueOwnerOrderMenuFlavorOptionByIds(chatId, ownerUserId, venueId, sectionId, itemId, optionId)
            return
        }
        val created =
            try {
                venueMenuRepository.createOption(
                    venueId = venueId,
                    itemId = itemId,
                    name = normalized,
                    priceDeltaMinor = 0,
                    isAvailable = true,
                )
            } catch (e: DatabaseUnavailableException) {
                enqueueMessage(chatId, "База недоступна, попробуйте позже.")
                return
            }
        if (created == null) {
            dialogStateRepository.clear(chatId)
            enqueueMessage(chatId, "Не удалось добавить вкус. Позиция не найдена.")
            showVenueOwnerOrderMenuSectionByIds(chatId, ownerUserId, venueId, sectionId)
            return
        }
        dialogStateRepository.clear(chatId)
        enqueueMessage(chatId, "✅ Вкус добавлен.")
        showVenueOwnerOrderMenuItemFlavorsByIds(chatId, ownerUserId, venueId, sectionId, itemId)
    }

    private suspend fun setVenueOwnerOrderMenuFlavorAvailability(
        chatId: Long,
        userId: Long,
        data: String,
        isAvailable: Boolean,
    ) {
        val prefix =
            if (isAvailable) {
                "owner_venue_order_menu_item_option_unstop:"
            } else {
                "owner_venue_order_menu_item_option_stop:"
            }
        val parsed = parseOwnerVenueMenuOptionData(data, prefix)
        if (parsed == null) {
            enqueueMessage(chatId, "Не удалось изменить статус вкуса. Попробуйте ещё раз.")
            return
        }
        val (venueId, sectionId, itemId, optionId) = parsed
        val hasAccess =
            runCatching { venueAccessRepository.hasVenueAdminOrOwner(userId, venueId) }
                .onFailure { logBestEffort("check venue owner access on toggle flavor availability", it) }
                .getOrDefault(false)
        if (!hasAccess) {
            enqueueMessage(chatId, "Нет доступа к заведению.")
            return
        }
        val (category, item) = loadOwnerOrderMenuItem(chatId, venueId, sectionId, itemId) ?: return
        if (!isHookahMenuSection(category)) {
            enqueueMessage(chatId, "Профиль вкуса доступен только для раздела «Кальянное меню».")
            return
        }
        if (item.options.none { it.id == optionId }) {
            enqueueMessage(chatId, "Вкус не найден.")
            return
        }
        val updated =
            try {
                venueMenuRepository.setOptionAvailability(
                    venueId = venueId,
                    optionId = optionId,
                    isAvailable = isAvailable,
                )
            } catch (e: DatabaseUnavailableException) {
                enqueueMessage(chatId, "База недоступна, попробуйте позже.")
                return
            }
        if (updated == null || updated.itemId != itemId) {
            enqueueMessage(chatId, "Вкус не найден.")
            showVenueOwnerOrderMenuItemFlavorsByIds(chatId, userId, venueId, sectionId, itemId)
            return
        }
        enqueueMessage(
            chatId,
            if (isAvailable) "✅ Вкус убран из стоп-листа." else "✅ Вкус добавлен в стоп-лист.",
        )
        showVenueOwnerOrderMenuFlavorOptionByIds(chatId, userId, venueId, sectionId, itemId, optionId)
    }

    private suspend fun deleteVenueOwnerOrderMenuFlavor(
        chatId: Long,
        userId: Long,
        data: String,
    ) {
        val parsed = parseOwnerVenueMenuOptionData(data, "owner_venue_order_menu_item_option_delete:")
        if (parsed == null) {
            enqueueMessage(chatId, "Не удалось удалить вкус. Попробуйте ещё раз.")
            return
        }
        val (venueId, sectionId, itemId, optionId) = parsed
        val hasAccess =
            runCatching { venueAccessRepository.hasVenueAdminOrOwner(userId, venueId) }
                .onFailure { logBestEffort("check venue owner access on delete flavor", it) }
                .getOrDefault(false)
        if (!hasAccess) {
            enqueueMessage(chatId, "Нет доступа к заведению.")
            return
        }
        val (category, item) = loadOwnerOrderMenuItem(chatId, venueId, sectionId, itemId) ?: return
        if (!isHookahMenuSection(category)) {
            enqueueMessage(chatId, "Профиль вкуса доступен только для раздела «Кальянное меню».")
            return
        }
        if (item.options.none { it.id == optionId }) {
            enqueueMessage(chatId, "Вкус не найден.")
            return
        }
        val deleted =
            try {
                venueMenuRepository.deleteOption(
                    venueId = venueId,
                    optionId = optionId,
                )
            } catch (e: DatabaseUnavailableException) {
                enqueueMessage(chatId, "База недоступна, попробуйте позже.")
                return
            }
        if (!deleted) {
            enqueueMessage(chatId, "Не удалось удалить вкус. Попробуйте ещё раз.")
            showVenueOwnerOrderMenuFlavorOptionByIds(chatId, userId, venueId, sectionId, itemId, optionId)
            return
        }
        enqueueMessage(chatId, "✅ Вкус удалён.")
        showVenueOwnerOrderMenuItemFlavorsByIds(chatId, userId, venueId, sectionId, itemId)
    }

    private suspend fun promptVenueOwnerOrderMenuRenameItem(
        chatId: Long,
        userId: Long,
        data: String,
    ) {
        val parsed = parseOwnerVenueMenuItemData(data, "owner_venue_order_menu_item_rename:")
        if (parsed == null) {
            enqueueMessage(chatId, "Не удалось открыть позицию. Попробуйте ещё раз.")
            return
        }
        val (venueId, sectionId, itemId) = parsed
        val hasAccess =
            runCatching { venueAccessRepository.hasVenueAdminOrOwner(userId, venueId) }
                .onFailure { logBestEffort("check venue owner access on rename order menu item", it) }
                .getOrDefault(false)
        if (!hasAccess) {
            enqueueMessage(chatId, "Нет доступа к заведению.")
            return
        }
        val category = loadOwnerOrderMenuCategory(chatId, venueId, sectionId) ?: run {
            enqueueMessage(chatId, "Раздел не найден.")
            return
        }
        if (category.items.none { it.id == itemId }) {
            enqueueMessage(chatId, "Позиция не найдена.")
            return
        }
        dialogStateRepository.set(
            chatId,
            DialogState(
                state = DialogStateType.OWNER_VENUE_ORDER_MENU_WAIT_ITEM_RENAME,
                payload =
                    mapOf(
                        "venue_id" to venueId.toString(),
                        "section_id" to sectionId.toString(),
                        "item_id" to itemId.toString(),
                        "owner_user_id" to userId.toString(),
                    ),
            ),
        )
        enqueueMessage(chatId, "Введите новое название позиции.\nОтправьте «—», чтобы отменить.")
    }

    private suspend fun proceedOwnerVenueOrderMenuRenameItem(
        chatId: Long,
        from: User?,
        text: String,
        state: DialogState,
    ) {
        val venueId = state.payload["venue_id"]?.toLongOrNull()
        val sectionId = state.payload["section_id"]?.toLongOrNull()
        val itemId = state.payload["item_id"]?.toLongOrNull()
        val ownerUserId = (from?.id ?: state.payload["owner_user_id"]?.toLongOrNull())
        if (venueId == null || sectionId == null || itemId == null || ownerUserId == null) {
            dialogStateRepository.clear(chatId)
            enqueueMessage(chatId, "Не удалось продолжить. Откройте «🍽 Меню заведения» снова.")
            return
        }
        val input = text.trim()
        if (input == "-" || input == "—") {
            dialogStateRepository.clear(chatId)
            enqueueMessage(chatId, "Изменение названия отменено.")
            showVenueOwnerOrderMenuItemByIds(chatId, ownerUserId, venueId, sectionId, itemId)
            return
        }
        val normalized = normalizeVenueConnectionRequiredField(input, maxLength = 120)
        if (normalized == null) {
            enqueueMessage(chatId, "Введите корректное название позиции (до 120 символов).")
            return
        }
        val hasAccess =
            runCatching { venueAccessRepository.hasVenueAdminOrOwner(ownerUserId, venueId) }
                .onFailure { logBestEffort("check venue owner access on rename item submit", it) }
                .getOrDefault(false)
        if (!hasAccess) {
            dialogStateRepository.clear(chatId)
            enqueueMessage(chatId, "Нет доступа к заведению.")
            return
        }
        val updated =
            try {
                venueMenuRepository.updateItem(
                    venueId = venueId,
                    itemId = itemId,
                    categoryId = sectionId,
                    name = normalized,
                    priceMinor = null,
                    currency = null,
                    isAvailable = null,
                )
            } catch (e: DatabaseUnavailableException) {
                enqueueMessage(chatId, "База недоступна, попробуйте позже.")
                return
            }
        if (updated == null) {
            dialogStateRepository.clear(chatId)
            enqueueMessage(chatId, "Позиция не найдена.")
            showVenueOwnerOrderMenuSectionByIds(chatId, ownerUserId, venueId, sectionId)
            return
        }
        dialogStateRepository.clear(chatId)
        enqueueMessage(chatId, "✅ Название позиции обновлено.")
        showVenueOwnerOrderMenuItemByIds(chatId, ownerUserId, venueId, sectionId, itemId)
    }

    private suspend fun promptVenueOwnerOrderMenuEditItemPrice(
        chatId: Long,
        userId: Long,
        data: String,
    ) {
        val parsed = parseOwnerVenueMenuItemData(data, "owner_venue_order_menu_item_price:")
        if (parsed == null) {
            enqueueMessage(chatId, "Не удалось открыть позицию. Попробуйте ещё раз.")
            return
        }
        val (venueId, sectionId, itemId) = parsed
        val hasAccess =
            runCatching { venueAccessRepository.hasVenueAdminOrOwner(userId, venueId) }
                .onFailure { logBestEffort("check venue owner access on edit order menu item price", it) }
                .getOrDefault(false)
        if (!hasAccess) {
            enqueueMessage(chatId, "Нет доступа к заведению.")
            return
        }
        val category = loadOwnerOrderMenuCategory(chatId, venueId, sectionId) ?: run {
            enqueueMessage(chatId, "Раздел не найден.")
            return
        }
        if (category.items.none { it.id == itemId }) {
            enqueueMessage(chatId, "Позиция не найдена.")
            return
        }
        dialogStateRepository.set(
            chatId,
            DialogState(
                state = DialogStateType.OWNER_VENUE_ORDER_MENU_WAIT_ITEM_PRICE_EDIT,
                payload =
                    mapOf(
                        "venue_id" to venueId.toString(),
                        "section_id" to sectionId.toString(),
                        "item_id" to itemId.toString(),
                        "owner_user_id" to userId.toString(),
                    ),
            ),
        )
        enqueueMessage(chatId, "Укажите новую цену позиции в рублях.\nТолько число, например 850")
    }

    private suspend fun proceedOwnerVenueOrderMenuEditItemPrice(
        chatId: Long,
        from: User?,
        text: String,
        state: DialogState,
    ) {
        val venueId = state.payload["venue_id"]?.toLongOrNull()
        val sectionId = state.payload["section_id"]?.toLongOrNull()
        val itemId = state.payload["item_id"]?.toLongOrNull()
        val ownerUserId = (from?.id ?: state.payload["owner_user_id"]?.toLongOrNull())
        if (venueId == null || sectionId == null || itemId == null || ownerUserId == null) {
            dialogStateRepository.clear(chatId)
            enqueueMessage(chatId, "Не удалось продолжить. Откройте «🍽 Меню заведения» снова.")
            return
        }
        val priceRub = parsePositiveRubAmount(text)
        if (priceRub == null) {
            enqueueMessage(chatId, "Введите корректную цену в рублях (только число, например 850).")
            return
        }
        val hasAccess =
            runCatching { venueAccessRepository.hasVenueAdminOrOwner(ownerUserId, venueId) }
                .onFailure { logBestEffort("check venue owner access on edit item price submit", it) }
                .getOrDefault(false)
        if (!hasAccess) {
            dialogStateRepository.clear(chatId)
            enqueueMessage(chatId, "Нет доступа к заведению.")
            return
        }
        val updated =
            try {
                venueMenuRepository.updateItem(
                    venueId = venueId,
                    itemId = itemId,
                    categoryId = sectionId,
                    name = null,
                    priceMinor = priceRub * 100,
                    currency = "RUB",
                    isAvailable = null,
                )
            } catch (e: DatabaseUnavailableException) {
                enqueueMessage(chatId, "База недоступна, попробуйте позже.")
                return
            }
        if (updated == null) {
            dialogStateRepository.clear(chatId)
            enqueueMessage(chatId, "Позиция не найдена.")
            showVenueOwnerOrderMenuSectionByIds(chatId, ownerUserId, venueId, sectionId)
            return
        }
        dialogStateRepository.clear(chatId)
        enqueueMessage(chatId, "✅ Цена позиции обновлена.")
        showVenueOwnerOrderMenuItemByIds(chatId, ownerUserId, venueId, sectionId, itemId)
    }

    private suspend fun setVenueOwnerOrderMenuItemAvailability(
        chatId: Long,
        userId: Long,
        data: String,
        isAvailable: Boolean,
    ) {
        val prefix = if (isAvailable) "owner_venue_order_menu_item_unstop:" else "owner_venue_order_menu_item_stop:"
        val parsed = parseOwnerVenueMenuItemData(data, prefix)
        if (parsed == null) {
            enqueueMessage(chatId, "Не удалось изменить статус позиции. Попробуйте ещё раз.")
            return
        }
        val (venueId, sectionId, itemId) = parsed
        val hasAccess =
            runCatching { venueAccessRepository.hasVenueAdminOrOwner(userId, venueId) }
                .onFailure { logBestEffort("check venue owner access on toggle order menu item availability", it) }
                .getOrDefault(false)
        if (!hasAccess) {
            enqueueMessage(chatId, "Нет доступа к заведению.")
            return
        }
        val category = loadOwnerOrderMenuCategory(chatId, venueId, sectionId) ?: run {
            enqueueMessage(chatId, "Раздел не найден.")
            return
        }
        if (category.items.none { it.id == itemId }) {
            enqueueMessage(chatId, "Позиция не найдена.")
            return
        }
        val updated =
            try {
                venueMenuRepository.setItemAvailability(
                    venueId = venueId,
                    itemId = itemId,
                    isAvailable = isAvailable,
                )
            } catch (e: DatabaseUnavailableException) {
                enqueueMessage(chatId, "База недоступна, попробуйте позже.")
                return
            }
        if (updated == null) {
            enqueueMessage(chatId, "Позиция не найдена.")
            showVenueOwnerOrderMenuSectionByIds(chatId, userId, venueId, sectionId)
            return
        }
        enqueueMessage(
            chatId,
            if (isAvailable) "✅ Позиция убрана из стоп-листа." else "✅ Позиция добавлена в стоп-лист.",
        )
        showVenueOwnerOrderMenuItemByIds(chatId, userId, venueId, sectionId, itemId)
    }

    private suspend fun deleteVenueOwnerOrderMenuItem(
        chatId: Long,
        userId: Long,
        data: String,
    ) {
        val parsed = parseOwnerVenueMenuItemData(data, "owner_venue_order_menu_item_delete:")
        if (parsed == null) {
            enqueueMessage(chatId, "Не удалось удалить позицию. Попробуйте ещё раз.")
            return
        }
        val (venueId, sectionId, itemId) = parsed
        val hasAccess =
            runCatching { venueAccessRepository.hasVenueAdminOrOwner(userId, venueId) }
                .onFailure { logBestEffort("check venue owner access on delete order menu item", it) }
                .getOrDefault(false)
        if (!hasAccess) {
            enqueueMessage(chatId, "Нет доступа к заведению.")
            return
        }
        val category = loadOwnerOrderMenuCategory(chatId, venueId, sectionId) ?: run {
            enqueueMessage(chatId, "Раздел не найден.")
            return
        }
        if (category.items.none { it.id == itemId }) {
            enqueueMessage(chatId, "Позиция не найдена.")
            return
        }
        val deleted =
            try {
                venueMenuRepository.deleteItem(
                    venueId = venueId,
                    itemId = itemId,
                )
            } catch (e: DatabaseUnavailableException) {
                enqueueMessage(chatId, "База недоступна, попробуйте позже.")
                return
            }
        if (!deleted) {
            enqueueMessage(chatId, "Не удалось удалить позицию. Попробуйте ещё раз.")
            showVenueOwnerOrderMenuItemByIds(chatId, userId, venueId, sectionId, itemId)
            return
        }
        enqueueMessage(chatId, "✅ Позиция удалена.")
        showVenueOwnerOrderMenuSectionByIds(chatId, userId, venueId, sectionId)
    }

    private suspend fun showVenueOwnerOrderMenuStopList(
        chatId: Long,
        userId: Long,
        data: String,
    ) {
        val venueId = parseOwnerVenueIdFromCallback(data, "owner_venue_order_menu_stoplist:")
        if (venueId == null) {
            enqueueMessage(chatId, "Не удалось открыть раздел. Попробуйте ещё раз.")
            return
        }
        showVenueOwnerOrderMenuStopListByVenueId(chatId, userId, venueId)
    }

    private suspend fun showVenueOwnerOrderMenuStopListByVenueId(
        chatId: Long,
        userId: Long,
        venueId: Long,
    ) {
        val hasAccess =
            runCatching { venueAccessRepository.hasVenueAdminOrOwner(userId, venueId) }
                .onFailure { logBestEffort("check venue owner access on order menu stoplist", it) }
                .getOrDefault(false)
        if (!hasAccess) {
            enqueueMessage(chatId, "Нет доступа к заведению.")
            return
        }
        val categories =
            try {
                venueMenuRepository.getMenu(venueId)
            } catch (e: DatabaseUnavailableException) {
                enqueueMessage(chatId, "База недоступна, попробуйте позже.")
                return
            }
        val stopListEntries =
            categories.flatMap { category ->
                category.items.flatMap { item ->
                    buildList {
                        if (!item.isAvailable) {
                            add(
                                OwnerVenueStopListEntry(
                                    sectionId = category.id,
                                    sectionName = category.name,
                                    itemId = item.id,
                                    itemName = item.name,
                                    itemPriceMinor = item.priceMinor,
                                    currency = item.currency,
                                ),
                            )
                        }
                        item.options
                            .filter { !it.isAvailable }
                            .forEach { option ->
                                add(
                                    OwnerVenueStopListEntry(
                                        sectionId = category.id,
                                        sectionName = category.name,
                                        itemId = item.id,
                                        itemName = item.name,
                                        itemPriceMinor = item.priceMinor,
                                        currency = item.currency,
                                        optionId = option.id,
                                        optionName = option.name,
                                    ),
                                )
                            }
                    }
                }
            }
        if (stopListEntries.isEmpty()) {
            enqueueMessage(
                chatId,
                "🚫 Стоп-лист\nЗдесь будут позиции, скрытые для гостей.\n\nСтоп-лист пока пуст.",
                TelegramKeyboards.inlineVenueOwnerOrderMenuBackToRoot(venueId),
            )
            return
        }
        enqueueMessage(
            chatId,
            buildOwnerVenueOrderMenuStopListText(stopListEntries),
            TelegramKeyboards.inlineVenueOwnerOrderMenuStopListActions(
                venueId = venueId,
                stopListButtons =
                    stopListEntries.map { entry ->
                        if (entry.optionId == null) {
                            "✅ Убрать из стоп-листа: ${entry.itemName}" to
                                "owner_venue_order_menu_stoplist_unstop:$venueId:${entry.sectionId}:${entry.itemId}"
                        } else {
                            "✅ Убрать из стоп-листа: ${entry.optionName}" to
                                "owner_venue_order_menu_stoplist_unstop_option:$venueId:${entry.sectionId}:${entry.itemId}:${entry.optionId}"
                        }
                    },
            ),
        )
    }

    private suspend fun removeVenueOwnerOrderMenuItemFromStopList(
        chatId: Long,
        userId: Long,
        data: String,
    ) {
        val parsed = parseOwnerVenueMenuItemData(data, "owner_venue_order_menu_stoplist_unstop:")
        if (parsed == null) {
            enqueueMessage(chatId, "Не удалось изменить статус позиции. Попробуйте ещё раз.")
            return
        }
        val (venueId, _, itemId) = parsed
        val hasAccess =
            runCatching { venueAccessRepository.hasVenueAdminOrOwner(userId, venueId) }
                .onFailure { logBestEffort("check venue owner access on root stoplist unstop", it) }
                .getOrDefault(false)
        if (!hasAccess) {
            enqueueMessage(chatId, "Нет доступа к заведению.")
            return
        }
        val updated =
            try {
                venueMenuRepository.setItemAvailability(
                    venueId = venueId,
                    itemId = itemId,
                    isAvailable = true,
                )
            } catch (e: DatabaseUnavailableException) {
                enqueueMessage(chatId, "База недоступна, попробуйте позже.")
                return
            }
        if (updated == null) {
            enqueueMessage(chatId, "Позиция не найдена.")
            showVenueOwnerOrderMenuStopListByVenueId(chatId, userId, venueId)
            return
        }
        enqueueMessage(chatId, "✅ Позиция убрана из стоп-листа.")
        showVenueOwnerOrderMenuStopListByVenueId(chatId, userId, venueId)
    }

    private suspend fun removeVenueOwnerOrderMenuOptionFromStopList(
        chatId: Long,
        userId: Long,
        data: String,
    ) {
        val parsed = parseOwnerVenueMenuOptionData(data, "owner_venue_order_menu_stoplist_unstop_option:")
        if (parsed == null) {
            enqueueMessage(chatId, "Не удалось изменить статус вкуса. Попробуйте ещё раз.")
            return
        }
        val (venueId, _, _, optionId) = parsed
        val hasAccess =
            runCatching { venueAccessRepository.hasVenueAdminOrOwner(userId, venueId) }
                .onFailure { logBestEffort("check venue owner access on root stoplist option unstop", it) }
                .getOrDefault(false)
        if (!hasAccess) {
            enqueueMessage(chatId, "Нет доступа к заведению.")
            return
        }
        val updated =
            try {
                venueMenuRepository.setOptionAvailability(
                    venueId = venueId,
                    optionId = optionId,
                    isAvailable = true,
                )
            } catch (e: DatabaseUnavailableException) {
                enqueueMessage(chatId, "База недоступна, попробуйте позже.")
                return
            }
        if (updated == null) {
            enqueueMessage(chatId, "Вкус не найден.")
            showVenueOwnerOrderMenuStopListByVenueId(chatId, userId, venueId)
            return
        }
        enqueueMessage(chatId, "✅ Вкус убран из стоп-листа.")
        showVenueOwnerOrderMenuStopListByVenueId(chatId, userId, venueId)
    }

    private suspend fun loadOwnerOrderMenuCategory(
        chatId: Long,
        venueId: Long,
        sectionId: Long,
    ): VenueMenuCategory? {
        val categories =
            try {
                venueMenuRepository.getMenu(venueId)
            } catch (e: DatabaseUnavailableException) {
                enqueueMessage(chatId, "База недоступна, попробуйте позже.")
                return null
        }
        return categories.firstOrNull { it.id == sectionId }
    }

    private suspend fun loadOwnerOrderMenuItem(
        chatId: Long,
        venueId: Long,
        sectionId: Long,
        itemId: Long,
    ): Pair<VenueMenuCategory, com.hookah.platform.backend.miniapp.venue.menu.VenueMenuItem>? {
        val category = loadOwnerOrderMenuCategory(chatId, venueId, sectionId) ?: run {
            enqueueMessage(chatId, "Раздел не найден.")
            return null
        }
        val item =
            category.items.firstOrNull { it.id == itemId }
                ?: run {
                    enqueueMessage(chatId, "Позиция не найдена.")
                    return null
                }
        return category to item
    }

    private suspend fun ensureDefaultHookahFlavorProfiles(
        chatId: Long,
        venueId: Long,
        item: com.hookah.platform.backend.miniapp.venue.menu.VenueMenuItem,
    ): Boolean? {
        if (item.options.isNotEmpty()) return false
        return try {
            defaultHookahFlavorProfiles.forEach { optionName ->
                venueMenuRepository.createOption(
                    venueId = venueId,
                    itemId = item.id,
                    name = optionName,
                    priceDeltaMinor = 0,
                    isAvailable = true,
                )
            }
            true
        } catch (e: DatabaseUnavailableException) {
            enqueueMessage(chatId, "База недоступна, попробуйте позже.")
            null
        }
    }

    private fun isHookahMenuSection(category: VenueMenuCategory): Boolean =
        category.name.trim().equals(hookahSectionName, ignoreCase = true)

    private fun buildOwnerVenueOrderMenuSectionText(category: VenueMenuCategory): String =
        buildString {
            append("🍽 Раздел: ${category.name}")
            append("\nПозиции: ${category.items.size}")
            if (category.items.isNotEmpty()) {
                append("\n\n")
                category.items.forEach { item ->
                    append(
                        "• ${item.name} — ${formatOwnerVenueOrderMenuPrice(item.priceMinor, item.currency)}" +
                            if (item.isAvailable) "" else " [стоп-лист]",
                    )
                    append("\n")
                }
            }
            append("\nВыберите действие.")
        }

    private fun buildOwnerVenueOrderMenuStopListText(
        stopListEntries: List<OwnerVenueStopListEntry>,
    ): String =
        buildString {
            append("🚫 Стоп-лист\n\n")
            val groupedBySection = stopListEntries.groupBy { it.sectionName }
            groupedBySection.entries.forEachIndexed { sectionIndex, sectionEntry ->
                append(sectionEntry.key)
                append("\n")
                val groupedByItem = sectionEntry.value.groupBy { it.itemId }
                groupedByItem.values.forEach { itemEntries ->
                    val itemEntry = itemEntries.first()
                    val hasItemInStopList = itemEntries.any { it.optionId == null }
                    append("• ${itemEntry.itemName} — ${formatOwnerVenueOrderMenuPrice(itemEntry.itemPriceMinor, itemEntry.currency)}")
                    if (hasItemInStopList) {
                        append(" [позиция]")
                    }
                    append("\n")
                    itemEntries
                        .filter { it.optionId != null }
                        .forEach { optionEntry ->
                            append("  └ ${optionEntry.optionName} [вкус]")
                            append("\n")
                        }
                }
                if (sectionIndex != groupedBySection.size - 1) {
                    append("\n")
                }
            }
        }

    private fun buildOwnerVenueOrderMenuItemText(
        item: com.hookah.platform.backend.miniapp.venue.menu.VenueMenuItem,
        showFlavorSummary: Boolean = false,
    ): String =
        buildString {
            append(item.name)
            append("\n")
            append("Цена: ${formatOwnerVenueOrderMenuPrice(item.priceMinor, item.currency)}")
            append("\n")
            append("Статус: ${if (item.isAvailable) "Активна" else "В стоп-листе"}")
            if (showFlavorSummary) {
                append("\n\nВкусы:")
                if (item.options.isEmpty()) {
                    append("\n• Пока не добавлен")
                } else {
                    item.options.forEach { option ->
                        append("\n• ${option.name}")
                        if (!option.isAvailable) {
                            append(" [стоп-лист]")
                        }
                    }
                }
            }
        }

    private fun buildOwnerVenueOrderMenuItemButtonLabel(
        item: com.hookah.platform.backend.miniapp.venue.menu.VenueMenuItem,
    ): String =
        "${item.name} — ${formatOwnerVenueOrderMenuPrice(item.priceMinor, item.currency)}" +
            if (item.isAvailable) "" else " [стоп-лист]"

    private fun buildOwnerVenueOrderMenuFlavorListText(item: com.hookah.platform.backend.miniapp.venue.menu.VenueMenuItem): String =
        buildString {
            append("Профиль вкуса\n\n")
            if (item.options.isEmpty()) {
                append("Пока не добавлены вкусы.")
            } else {
                item.options.forEach { option ->
                    append("• ${option.name}")
                    if (!option.isAvailable) {
                        append(" [стоп-лист]")
                    }
                    append("\n")
                }
            }
        }

    private fun buildOwnerVenueOrderMenuFlavorOptionText(option: com.hookah.platform.backend.miniapp.venue.menu.VenueMenuOption): String =
        buildString {
            append(option.name)
            append("\n")
            append("Статус: ${if (option.isAvailable) "Активен" else "В стоп-листе"}")
        }

    private fun buildOwnerVenueOrderMenuFlavorButtonLabel(
        option: com.hookah.platform.backend.miniapp.venue.menu.VenueMenuOption,
    ): String =
        option.name + if (option.isAvailable) "" else " [стоп-лист]"

    private fun formatOwnerVenueOrderMenuPrice(
        priceMinor: Long,
        currency: String,
    ): String {
        if (currency.equals("RUB", ignoreCase = true) && priceMinor % 100L == 0L) {
            return "${priceMinor / 100L} ₽"
        }
        return formatPrice(priceMinor, currency)
    }

    private suspend fun showVenueOwnerGuestMenuPlaceholder(
        chatId: Long,
        userId: Long,
        data: String,
    ) {
        val venueId = parseOwnerVenueIdFromCallback(data, "owner_venue_menu_guest:")
        if (venueId == null) {
            enqueueMessage(chatId, "Не удалось открыть раздел. Попробуйте ещё раз.")
            return
        }
        val hasAccess =
            runCatching { venueAccessRepository.hasVenueAdminOrOwner(userId, venueId) }
                .onFailure { logBestEffort("check venue owner access on guest menu mode", it) }
                .getOrDefault(false)
        if (!hasAccess) {
            enqueueMessage(chatId, "Нет доступа к заведению.")
            return
        }
        enqueueMessage(
            chatId,
            "Ознакомительное меню настраивается в «📝 Описание» через раздел «Меню».",
            TelegramKeyboards.inlineVenueOwnerOrderMenuBackToRoot(venueId),
        )
    }

    private suspend fun hasOwnedVenues(userId: Long?): Boolean {
        if (userId == null) return false
        val memberships =
            runCatching { venueAccessRepository.listVenueMemberships(userId) }
                .onFailure { logBestEffort("load venue memberships on /start", it) }
                .getOrDefault(emptyList())
        return memberships.any { it.role.equals("OWNER", ignoreCase = true) }
    }

    private suspend fun resolvePrimaryVenueBotAccess(userId: Long?): VenueBotAccess? {
        if (userId == null) return null
        val memberships =
            runCatching { venueAccessRepository.listVenueMemberships(userId) }
                .onFailure { logBestEffort("load venue memberships for role-aware menu", it) }
                .getOrDefault(emptyList())
        return memberships
            .mapNotNull { membership ->
                val role =
                    when (membership.role.uppercase(Locale.ROOT)) {
                        "OWNER" -> VenueBotRole.OWNER
                        "ADMIN", "MANAGER" -> VenueBotRole.MANAGER
                        "STAFF" -> VenueBotRole.STAFF
                        else -> null
                    } ?: return@mapNotNull null
                VenueBotAccess(venueId = membership.venueId, role = role)
            }.sortedBy {
                when (it.role) {
                    VenueBotRole.OWNER -> 0
                    VenueBotRole.MANAGER -> 1
                    VenueBotRole.STAFF -> 2
                }
            }.firstOrNull()
    }

    private suspend fun showVenueManagerBotEntry(
        chatId: Long,
        venueId: Long,
    ) {
        val venueName = loadVenueNameForStaffInvite(venueId)
        enqueueMessage(
            chatId,
            "Режим менеджера заведения.\nЗаведение: $venueName\n\nВыберите действие.",
            TelegramKeyboards.venueManagerMenu(),
        )
        dialogStateRepository.clear(chatId)
    }

    private suspend fun showVenueStaffBotEntry(
        chatId: Long,
        venueId: Long,
    ) {
        val venueName = loadVenueNameForStaffInvite(venueId)
        enqueueMessage(
            chatId,
            "Режим оператора смены.\nЗаведение: $venueName\n\nВыберите действие.",
            TelegramKeyboards.venueStaffMenu(),
        )
        dialogStateRepository.clear(chatId)
    }

    private suspend fun showVenueSettingsRoot(
        chatId: Long,
        from: User?,
    ) {
        val userId = from?.id
        val access = resolvePrimaryVenueBotAccess(userId)
        if (userId == null || access == null || access.role !in setOf(VenueBotRole.OWNER, VenueBotRole.MANAGER)) {
            sendFallback(chatId, from)
            return
        }
        showVenueSettingsRootByVenueId(chatId, userId, access.venueId)
    }

    private suspend fun showVenueSettingsRootByCallback(
        chatId: Long,
        userId: Long,
        data: String,
    ) {
        val venueId =
            parseOwnerVenueIdFromCallback(data, "venue_settings_root:")
                ?: resolvePrimaryVenueBotAccess(userId)?.venueId
                ?: run {
                    enqueueMessage(chatId, "Не удалось открыть настройки. Попробуйте ещё раз.")
                    return
                }
        showVenueSettingsRootByVenueId(chatId, userId, venueId)
    }

    private suspend fun showVenueSettingsRootByVenueId(
        chatId: Long,
        userId: Long,
        venueId: Long,
    ) {
        val role = resolveVenueSettingsRole(chatId, userId, venueId) ?: return
        enqueueMessage(
            chatId,
            "⚙️ Настройки\n\nВыберите раздел:",
            TelegramKeyboards.inlineVenueSettingsRootActions(
                venueId = venueId,
                showDevReset = isDevMode() && role == VenueBotRole.OWNER,
            ),
        )
    }

    private suspend fun showVenueSettingsNotifications(
        chatId: Long,
        userId: Long,
        data: String,
    ) {
        val venueId = parseVenueSettingsVenueId(chatId, data, "venue_settings_notifications:") ?: return
        resolveVenueSettingsRole(chatId, userId, venueId) ?: return
        val settings = loadVenueSettings(chatId, venueId) ?: return
        enqueueMessage(
            chatId,
            """
            🔔 Уведомления

            ${formatVenueSettingStatus("Уведомления о заказах", settings.notifyOrdersEnabled)}
            ${formatVenueSettingStatus("Уведомления о вызовах", settings.notifyStaffCallsEnabled)}
            ${formatVenueSettingStatus("Уведомления об отменах", settings.notifyCancellationsEnabled)}
            """.trimIndent(),
            TelegramKeyboards.inlineVenueSettingsNotificationActions(venueId),
        )
    }

    private suspend fun toggleVenueSettingsNotification(
        chatId: Long,
        userId: Long,
        data: String,
        setting: VenueNotificationSetting,
    ) {
        val prefix =
            when (setting) {
                VenueNotificationSetting.ORDERS -> "venue_settings_toggle_orders:"
                VenueNotificationSetting.STAFF_CALLS -> "venue_settings_toggle_staff_calls:"
                VenueNotificationSetting.CANCELLATIONS -> "venue_settings_toggle_cancellations:"
            }
        val venueId = parseVenueSettingsVenueId(chatId, data, prefix) ?: return
        resolveVenueSettingsRole(chatId, userId, venueId) ?: return
        try {
            venueSettingsRepository.toggleNotification(
                venueId = venueId,
                setting = setting,
                fallbackTimezone = systemTimezoneId(),
            )
        } catch (e: DatabaseUnavailableException) {
            enqueueMessage(chatId, "База недоступна, попробуйте позже.")
            return
        }
        showVenueSettingsNotifications(chatId, userId, "venue_settings_notifications:$venueId")
    }

    private suspend fun showVenueSettingsTimezone(
        chatId: Long,
        userId: Long,
        data: String,
    ) {
        val venueId = parseVenueSettingsVenueId(chatId, data, "venue_settings_timezone:") ?: return
        resolveVenueSettingsRole(chatId, userId, venueId) ?: return
        val settings = loadVenueSettings(chatId, venueId) ?: return
        val timezone = settings.timezone?.takeIf { it.isNotBlank() } ?: systemTimezoneId()
        enqueueMessage(
            chatId,
            """
            🕒 Часовой пояс

            Часовой пояс определяется автоматически по адресу заведения.
            Сейчас используется: $timezone
            Позже timezone будет пересчитываться при изменении адреса.
            """.trimIndent(),
            TelegramKeyboards.inlineVenueSettingsBackActions(venueId),
        )
    }

    private suspend fun showVenueSettingsOrderNumbering(
        chatId: Long,
        userId: Long,
        data: String,
    ) {
        val venueId = parseVenueSettingsVenueId(chatId, data, "venue_settings_order_numbering:") ?: return
        resolveVenueSettingsRole(chatId, userId, venueId) ?: return
        enqueueMessage(
            chatId,
            """
            🧾 Нумерация заказов

            Технический order_id не показывается гостям и персоналу.
            В интерфейсе используется номер заказа за день: Заказ №1, №2...
            display_number сбрасывается по display_date.
            Нумерация ведётся отдельно по заведению и дате.
            Timezone пока использует fallback/system timezone, позже будет timezone заведения.
            В будущем можно будет перейти на нумерацию по сменам.
            """.trimIndent(),
            TelegramKeyboards.inlineVenueSettingsBackActions(venueId),
        )
    }

    private suspend fun showVenueSettingsStatsReports(
        chatId: Long,
        userId: Long,
        data: String,
    ) {
        val venueId = parseVenueSettingsVenueId(chatId, data, "venue_settings_stats_reports:") ?: return
        resolveVenueSettingsRole(chatId, userId, venueId) ?: return
        enqueueMessage(
            chatId,
            """
            📊 Статистика и отчёты

            Сейчас считаются:
            • заказы
            • выручка
            • средний чек
            • скидки
            • отмены/исключения
            • топ позиций

            Финансовые метрики считаются по DELIVERED/CLOSED.
            Скидки и исключённые позиции учитываются в итогах.

            Расширенные отчёты и экспорт будут добавлены позже.
            """.trimIndent(),
            TelegramKeyboards.inlineVenueSettingsBackActions(venueId),
        )
    }

    private suspend fun showVenueSettingsDevReset(
        chatId: Long,
        userId: Long,
        data: String,
    ) {
        val venueId = parseVenueSettingsVenueId(chatId, data, "venue_settings_dev_reset:") ?: return
        val role = resolveVenueSettingsRole(chatId, userId, venueId) ?: return
        val text =
            if (role != VenueBotRole.OWNER || !isDevMode()) {
                """
                🧪 Сброс тестовых данных

                Сброс тестовых данных доступен только владельцу в DEV-режиме.
                """.trimIndent()
            } else {
                """
                🧪 Сброс тестовых данных

                DEV-only очистка операционных тестовых данных текущего заведения.

                Будут очищены:
                • заказы
                • дозаказы
                • позиции заказа
                • вызовы персонала

                Не удаляются: заведение, столы, QR, меню, сотрудники, роли и настройки.
                """.trimIndent()
            }
        enqueueMessage(
            chatId,
            text,
            if (role == VenueBotRole.OWNER && isDevMode()) {
                TelegramKeyboards.inlineVenueSettingsDevResetActions(venueId)
            } else {
                TelegramKeyboards.inlineVenueSettingsBackActions(venueId)
            },
        )
    }

    private suspend fun showVenueSettingsDevResetConfirm(
        chatId: Long,
        userId: Long,
        data: String,
    ) {
        val venueId = parseVenueSettingsVenueId(chatId, data, "venue_settings_dev_reset_confirm:") ?: return
        if (!ensureVenueSettingsDevResetAllowed(chatId, userId, venueId)) return
        enqueueMessage(
            chatId,
            """
            Вы уверены?

            Это удалит/очистит тестовые заказы, дозаказы, позиции заказа, вызовы персонала и временные операционные данные для этого заведения.

            Заведение, столы, QR, меню, сотрудники, роли и настройки не удаляются.
            """.trimIndent(),
            TelegramKeyboards.inlineVenueSettingsDevResetConfirmActions(venueId),
        )
    }

    private suspend fun executeVenueSettingsDevReset(
        chatId: Long,
        userId: Long,
        data: String,
    ) {
        val venueId = parseVenueSettingsVenueId(chatId, data, "venue_settings_dev_reset_execute:") ?: return
        if (!ensureVenueSettingsDevResetAllowed(chatId, userId, venueId)) return
        val result =
            try {
                venueSettingsRepository.resetDevOperationalData(venueId)
            } catch (e: DatabaseUnavailableException) {
                enqueueMessage(chatId, "База недоступна, попробуйте позже.")
                return
            }
        enqueueMessage(
            chatId,
            """
            ✅ Тестовые операционные данные очищены.

            Заказы: ${result.ordersDeleted}
            Дозаказы: ${result.batchesDeleted}
            Позиции заказа: ${result.itemsDeleted}
            Вызовы персонала: ${result.staffCallsDeleted}
            """.trimIndent(),
            TelegramKeyboards.inlineVenueSettingsBackActions(venueId),
        )
    }

    private suspend fun showVenueSettingsAbout(
        chatId: Long,
        userId: Long,
        data: String,
    ) {
        val venueId = parseVenueSettingsVenueId(chatId, data, "venue_settings_about:") ?: return
        resolveVenueSettingsRole(chatId, userId, venueId) ?: return
        enqueueMessage(
            chatId,
            """
            ℹ️ О настройках

            Настройки — это системные параметры заведения.
            Основные рабочие разделы находятся в главном меню: меню, столы, персонал, заказы, статистика.
            """.trimIndent(),
            TelegramKeyboards.inlineVenueSettingsBackActions(venueId),
        )
    }

    private suspend fun resolveVenueSettingsRole(
        chatId: Long,
        userId: Long,
        venueId: Long,
    ): VenueBotRole? {
        val role = resolveVenueRoleForVenue(chatId, userId, venueId) ?: return null
        if (role == VenueBotRole.STAFF) {
            enqueueMessage(chatId, "Раздел «Настройки» доступен владельцу или менеджеру.")
            return null
        }
        return role
    }

    private suspend fun ensureVenueSettingsDevResetAllowed(
        chatId: Long,
        userId: Long,
        venueId: Long,
    ): Boolean {
        val role = resolveVenueSettingsRole(chatId, userId, venueId) ?: return false
        if (role != VenueBotRole.OWNER || !isDevMode()) {
            enqueueMessage(chatId, "Сброс тестовых данных доступен только владельцу в DEV-режиме.")
            return false
        }
        return true
    }

    private suspend fun loadVenueSettings(
        chatId: Long,
        venueId: Long,
    ): VenueSettings? =
        try {
            venueSettingsRepository.getOrCreate(
                venueId = venueId,
                fallbackTimezone = systemTimezoneId(),
            )
        } catch (e: DatabaseUnavailableException) {
            enqueueMessage(chatId, "База недоступна, попробуйте позже.")
            null
        }

    private fun formatVenueSettingStatus(
        label: String,
        enabled: Boolean,
    ): String = "${if (enabled) "✅" else "❌"} $label: ${if (enabled) "включены" else "выключены"}"

    private fun systemTimezoneId(): String = ZoneId.systemDefault().id

    private suspend fun parseVenueSettingsVenueId(
        chatId: Long,
        data: String,
        prefix: String,
    ): Long? =
        parseOwnerVenueIdFromCallback(data, prefix)
            ?: run {
                enqueueMessage(chatId, "Не удалось открыть настройки. Попробуйте ещё раз.")
                null
            }

    private suspend fun showStatsEntry(
        chatId: Long,
        from: User?,
    ) {
        val access = resolvePrimaryVenueBotAccess(from?.id)
        if (access?.role !in setOf(VenueBotRole.OWNER, VenueBotRole.MANAGER)) {
            sendFallback(chatId, from)
            return
        }
        enqueueMessage(
            chatId,
            "📊 Статистика\n\nВыберите период:",
            TelegramKeyboards.inlineVenueStatsPeriodActions(),
        )
    }

    private suspend fun showStatsPeriod(
        chatId: Long,
        userId: Long,
        data: String,
    ) {
        val access = resolvePrimaryVenueBotAccess(userId)
        if (access == null || access.role !in setOf(VenueBotRole.OWNER, VenueBotRole.MANAGER)) {
            enqueueMessage(chatId, "Раздел статистики доступен владельцу или менеджеру.")
            return
        }
        val period = resolveVenueStatsPeriod(data)
        if (period == null) {
            enqueueMessage(chatId, "Не удалось выбрать период. Попробуйте ещё раз.")
            showStatsEntry(chatId, User(id = userId))
            return
        }
        val stats =
            try {
                venueStatsRepository.loadVenueStats(
                    venueId = access.venueId,
                    periodStart = period.periodStart,
                )
            } catch (e: DatabaseUnavailableException) {
                enqueueMessage(chatId, "База недоступна, попробуйте позже.")
                return
            }
        enqueueMessage(
            chatId,
            buildVenueStatsText(period.title, stats),
            TelegramKeyboards.inlineVenueStatsPeriodActions(),
        )
    }

    private fun resolveVenueStatsPeriod(data: String): VenueStatsPeriod? {
        val zone = ZoneId.systemDefault()
        val now = Instant.now()
        return when (data) {
            "stats_period_today" ->
                VenueStatsPeriod(
                    title = "Сегодня",
                    periodStart = LocalDate.now(zone).atStartOfDay(zone).toInstant(),
                )
            "stats_period_7d" ->
                VenueStatsPeriod(
                    title = "7 дней",
                    periodStart = now.minus(Duration.ofDays(7)),
                )
            "stats_period_30d" ->
                VenueStatsPeriod(
                    title = "30 дней",
                    periodStart = now.minus(Duration.ofDays(30)),
                )
            else -> null
        }
    }

    private fun buildVenueStatsText(
        periodTitle: String,
        stats: VenueStatsReport,
    ): String =
        buildString {
            append("📊 Статистика · $periodTitle")
            append("\n\n")
            append("Заказы: ${stats.ordersCount}")
            append("\n")
            append("Выручка: ${formatPrice(stats.revenueMinor, stats.currency)}")
            append("\n")
            append("Средний чек: ${formatPrice(stats.averageCheckMinor, stats.currency)}")
            append("\n")
            append("Скидки: ${formatPrice(stats.discountMinor, stats.currency)}")
            append("\n")
            append("Отмены/исключения: ${stats.cancelledItemsCount}")
            append("\n\n")
            append("Топ позиций:")
            if (stats.topItems.isEmpty()) {
                append("\n—")
            } else {
                stats.topItems.forEachIndexed { index, item ->
                    append("\n${index + 1}. ${item.itemName} ×${item.qty}")
                }
            }
        }

    private suspend fun showVenueStaffCalls(
        chatId: Long,
        from: User?,
    ) {
        val userId = from?.id
        if (userId == null) {
            enqueueMessage(chatId, "Не удалось определить пользователя. Попробуйте /start.")
            return
        }
        when (val access = resolvePrimaryVenueBotAccess(userId)) {
            null -> showMainMenu(chatId, from)
            else ->
                when (access.role) {
                    VenueBotRole.OWNER -> showVenueOwnerBotEntry(chatId, from)
                    VenueBotRole.MANAGER, VenueBotRole.STAFF ->
                        showVenueStaffCallsByVenueId(chatId, userId, access.venueId)
                }
        }
    }

    private suspend fun showVenueStaffCallsRootByCallback(
        chatId: Long,
        userId: Long,
        data: String,
    ) {
        val venueId = parseOwnerVenueIdFromCallback(data, "staff_venue_calls_root:")
        if (venueId == null) {
            enqueueMessage(chatId, "Не удалось открыть вызовы. Попробуйте ещё раз.")
            return
        }
        showVenueStaffCallsByVenueId(chatId, userId, venueId)
    }

    private suspend fun showVenueStaffCallsByVenueId(
        chatId: Long,
        userId: Long,
        venueId: Long,
    ) {
        val role = resolveVenueRoleForVenue(chatId, userId, venueId) ?: return
        if (role == VenueBotRole.OWNER) {
            showVenueOwnerVenueCardByUserId(chatId, userId)
            return
        }
        val calls =
            try {
                staffCallRepository.listActiveByVenue(venueId = venueId, limit = 20)
            } catch (e: DatabaseUnavailableException) {
                enqueueMessage(chatId, "База недоступна, попробуйте позже.")
                return
            }
        if (calls.isEmpty()) {
            enqueueMessage(
                chatId,
                "🛎 Вызовы\n\nАктивных вызовов сейчас нет.",
                TelegramKeyboards.inlineVenueStaffCallsRootActions(venueId),
            )
            return
        }
        enqueueMessage(
            chatId,
            buildVenueStaffCallsText(calls),
            TelegramKeyboards.inlineVenueStaffCallsRootActions(venueId),
        )
    }

    private suspend fun showVenueStaffOrders(
        chatId: Long,
        from: User?,
    ) {
        val userId = from?.id
        if (userId == null) {
            enqueueMessage(chatId, "Не удалось определить пользователя. Попробуйте /start.")
            return
        }
        when (val access = resolvePrimaryVenueBotAccess(userId)) {
            null -> showMainMenu(chatId, from)
            else ->
                when (access.role) {
                    VenueBotRole.OWNER, VenueBotRole.MANAGER, VenueBotRole.STAFF ->
                        showVenueStaffOrdersRootByVenueId(chatId, userId, access.venueId)
                }
        }
    }

    private suspend fun showVenueStaffOrdersRootByCallback(
        chatId: Long,
        userId: Long,
        data: String,
    ) {
        val venueId = parseOwnerVenueIdFromCallback(data, "staff_venue_orders_root:")
        if (venueId == null) {
            enqueueMessage(chatId, "Не удалось открыть заказы. Попробуйте ещё раз.")
            return
        }
        showVenueStaffOrdersRootByVenueId(chatId, userId, venueId)
    }

    private suspend fun showVenueStaffOrdersRootByVenueId(
        chatId: Long,
        userId: Long,
        venueId: Long,
    ) {
        resolveVenueRoleForVenue(chatId, userId, venueId) ?: return
        val queueItems = loadVenueStaffOrderQueue(chatId, venueId) ?: return
        if (queueItems.isEmpty()) {
            enqueueMessage(
                chatId,
                "🧾 Заказы\n\nАктивных заказов сейчас нет.",
                TelegramKeyboards.inlineVenueStaffOrdersRootActions(venueId, emptyList()),
            )
            return
        }
        enqueueMessage(
            chatId,
            buildVenueStaffOrderQueueText(queueItems),
            TelegramKeyboards.inlineVenueStaffOrdersRootActions(
                venueId = venueId,
                orderButtons =
                    queueItems.map { item ->
                        item.orderId to buildVenueStaffOrderQueueButtonLabel(item)
                    },
            ),
        )
    }

    private suspend fun showVenueStaffOrderDetails(
        chatId: Long,
        userId: Long,
        data: String,
    ) {
        val parsed = parseStaffVenueOrderData(data, "staff_venue_orders_order:")
        if (parsed == null) {
            enqueueMessage(chatId, "Не удалось открыть заказ. Попробуйте ещё раз.")
            return
        }
        val (venueId, orderId) = parsed
        showVenueStaffOrderDetailsByIds(chatId, userId, venueId, orderId)
    }

    private suspend fun showVenueStaffOrderFullDetails(
        chatId: Long,
        userId: Long,
        data: String,
    ) {
        val parsed = parseStaffVenueOrderData(data, "staff_venue_orders_full:")
        if (parsed == null) {
            enqueueMessage(chatId, "Не удалось открыть счёт заказа. Попробуйте ещё раз.")
            return
        }
        val (venueId, orderId) = parsed
        showVenueStaffOrderFullDetailsByIds(chatId, userId, venueId, orderId)
    }

    private suspend fun showVenueStaffOrderFullDetailsByIds(
        chatId: Long,
        userId: Long,
        venueId: Long,
        orderId: Long,
    ) {
        val role = resolveVenueRoleForVenue(chatId, userId, venueId) ?: return
        val detail =
            try {
                venueOrdersRepository.loadOrderDetail(venueId = venueId, orderId = orderId)
            } catch (e: DatabaseUnavailableException) {
                enqueueMessage(chatId, "База недоступна, попробуйте позже.")
                return
            }
        if (detail == null) {
            enqueueMessage(chatId, "Заказ не найден.")
            showVenueStaffOrdersRootByVenueId(chatId, userId, venueId)
            return
        }
        if (detail.status == OrderWorkflowStatus.CLOSED) {
            enqueueMessage(chatId, "Заказ уже закрыт.")
            showVenueStaffOrdersRootByVenueId(chatId, userId, venueId)
            return
        }
        val canCloseOrder =
            detail.batches.any { batch ->
                batch.status == OrderWorkflowStatus.ACCEPTED ||
                    batch.status == OrderWorkflowStatus.DELIVERED
            }
        val actionButtons =
            buildList {
                add("✏️ Изменить счёт" to "staff_venue_orders_edit_bill:$venueId:$orderId")
                if (canCloseOrder) {
                    add("🔒 Закрыть заказ" to "staff_venue_orders_status:$venueId:$orderId:${OrderWorkflowStatus.CLOSED.toApi()}")
                }
                if (detail.status == OrderWorkflowStatus.NEW) {
                    add("❌ Отменить заказ" to "staff_venue_orders_cancel_order:$venueId:$orderId")
                }
            }
        enqueueMessage(
            chatId,
            buildVenueStaffOrderFullText(detail),
            TelegramKeyboards.inlineVenueStaffOrderActions(
                venueId = venueId,
                orderId = orderId,
                statusButtons = emptyList(),
                actionButtons = actionButtons,
                backButton = "⬅️ Назад к заказу" to "staff_venue_orders_order:$venueId:$orderId",
            ),
        )
    }

    private suspend fun showVenueStaffOrderEditBill(
        chatId: Long,
        userId: Long,
        data: String,
    ) {
        val parsed = parseStaffVenueOrderData(data, "staff_venue_orders_edit_bill:")
        if (parsed == null) {
            enqueueMessage(chatId, "Не удалось открыть редактирование счёта. Попробуйте ещё раз.")
            return
        }
        val (venueId, orderId) = parsed
        resolveVenueRoleForVenue(chatId, userId, venueId) ?: return
        val detail = loadVenueStaffOrderDetailForEdit(chatId, userId, venueId, orderId) ?: return
        val activeItems = activeVenueOrderBillItems(detail)
        if (activeItems.isEmpty()) {
            enqueueMessage(chatId, "В счёте нет активных позиций для изменения.")
            showVenueStaffOrderFullDetailsByIds(chatId, userId, venueId, orderId)
            return
        }
        enqueueMessage(
            chatId,
            buildVenueStaffOrderBillItemPickerText(activeItems),
            TelegramKeyboards.inlineVenueStaffOrderActions(
                venueId = venueId,
                orderId = orderId,
                statusButtons = emptyList(),
                actionButtons =
                    activeItems.map { (_, item) ->
                        buildVenueStaffOrderBillItemButtonLabel(item) to
                            "staff_order_bill_item:$venueId:$orderId:${item.batchItemId}"
                    },
                backButton = "⬅️ Назад к счёту" to "staff_venue_orders_full:$venueId:$orderId",
            ),
        )
    }

    private suspend fun showVenueStaffOrderBillItemAction(
        chatId: Long,
        userId: Long,
        data: String,
    ) {
        val parsed = parseStaffVenueOrderItemData(data, "staff_order_bill_item:")
        if (parsed == null) {
            enqueueMessage(chatId, "Не удалось открыть позицию. Попробуйте ещё раз.")
            return
        }
        val (venueId, orderId, batchItemId) = parsed
        resolveVenueRoleForVenue(chatId, userId, venueId) ?: return
        val detail = loadVenueStaffOrderDetailForEdit(chatId, userId, venueId, orderId) ?: return
        val item =
            activeVenueOrderBillItems(detail)
                .firstOrNull { (_, item) -> item.batchItemId == batchItemId }
                ?: run {
                    enqueueMessage(chatId, "Позиция не найдена или уже исключена.")
                    showVenueStaffOrderEditBill(chatId, userId, "staff_venue_orders_edit_bill:$venueId:$orderId")
                    return
                }
        enqueueMessage(
            chatId,
            buildVenueStaffOrderBillItemActionText(item.second),
            TelegramKeyboards.inlineVenueStaffOrderActions(
                venueId = venueId,
                orderId = orderId,
                statusButtons = emptyList(),
                actionButtons =
                    listOf(
                        "❌ Убрать из счёта" to "staff_order_bill_exclude:$venueId:$orderId:$batchItemId",
                        "🏷 Применить скидку" to "staff_order_bill_discount:$venueId:$orderId:$batchItemId",
                    ),
                backButton = "⬅️ Назад" to "staff_venue_orders_edit_bill:$venueId:$orderId",
            ),
        )
    }

    private suspend fun promptVenueStaffOrderBillItemExcludeReason(
        chatId: Long,
        userId: Long,
        data: String,
    ) {
        val parsed = parseStaffVenueOrderItemData(data, "staff_order_bill_exclude:")
        if (parsed == null) {
            enqueueMessage(chatId, "Не удалось исключить позицию. Попробуйте ещё раз.")
            return
        }
        val (venueId, orderId, batchItemId) = parsed
        resolveVenueRoleForVenue(chatId, userId, venueId) ?: return
        dialogStateRepository.set(
            chatId,
            DialogState(
                state = DialogStateType.VENUE_STAFF_ORDERS_WAIT_ITEM_EXCLUDE_REASON,
                payload =
                    mapOf(
                        "venue_id" to venueId.toString(),
                        "order_id" to orderId.toString(),
                        "batch_item_id" to batchItemId.toString(),
                    ),
            ),
        )
        enqueueMessage(
            chatId,
            "Укажите причину исключения позиции.\nОтправьте «—», чтобы отменить действие.",
        )
    }

    private suspend fun promptVenueStaffOrderBillItemDiscountPercent(
        chatId: Long,
        userId: Long,
        data: String,
    ) {
        val parsed = parseStaffVenueOrderItemData(data, "staff_order_bill_discount:")
        if (parsed == null) {
            enqueueMessage(chatId, "Не удалось применить скидку. Попробуйте ещё раз.")
            return
        }
        val (venueId, orderId, batchItemId) = parsed
        resolveVenueRoleForVenue(chatId, userId, venueId) ?: return
        dialogStateRepository.set(
            chatId,
            DialogState(
                state = DialogStateType.VENUE_STAFF_ORDERS_WAIT_ITEM_DISCOUNT_PERCENT,
                payload =
                    mapOf(
                        "venue_id" to venueId.toString(),
                        "order_id" to orderId.toString(),
                        "batch_item_id" to batchItemId.toString(),
                    ),
            ),
        )
        enqueueMessage(
            chatId,
            "Введите скидку в процентах от 1 до 100.\nНапример: 20",
        )
    }

    private suspend fun proceedVenueStaffOrderBillItemDiscountPercent(
        chatId: Long,
        from: User?,
        text: String,
        state: DialogState,
    ) {
        val userId = from?.id
        if (userId == null) {
            dialogStateRepository.clear(chatId)
            enqueueMessage(chatId, "Не удалось определить пользователя. Попробуйте /start.")
            return
        }
        val venueId = state.payload["venue_id"]?.toLongOrNull()
        val orderId = state.payload["order_id"]?.toLongOrNull()
        val batchItemId = state.payload["batch_item_id"]?.toLongOrNull()
        if (venueId == null || orderId == null || batchItemId == null) {
            dialogStateRepository.clear(chatId)
            enqueueMessage(chatId, "Не удалось применить скидку. Попробуйте ещё раз.")
            return
        }
        val discountPercent = text.trim().toIntOrNull()
        if (discountPercent == null || discountPercent !in 1..100) {
            enqueueMessage(chatId, "Введите число от 1 до 100.\nНапример: 20")
            return
        }
        val role = resolveVenueRoleForVenue(chatId, userId, venueId) ?: run {
            dialogStateRepository.clear(chatId)
            return
        }
        val actorRole =
            when (role) {
                VenueBotRole.OWNER -> VenueRole.OWNER
                VenueBotRole.MANAGER -> VenueRole.MANAGER
                VenueBotRole.STAFF -> VenueRole.STAFF
            }
        val applied =
            try {
                venueOrdersRepository.setBatchItemDiscountPercent(
                    venueId = venueId,
                    orderId = orderId,
                    batchItemId = batchItemId,
                    discountPercent = discountPercent,
                    actor = OrderActionActor(userId = userId, role = actorRole),
                )
            } catch (e: DatabaseUnavailableException) {
                enqueueMessage(chatId, "База недоступна, попробуйте позже.")
                return
            }
        dialogStateRepository.clear(chatId)
        if (!applied) {
            enqueueMessage(chatId, "Позиция не найдена или уже исключена.")
            showVenueStaffOrderFullDetailsByIds(chatId, userId, venueId, orderId)
            return
        }
        enqueueMessage(chatId, "✅ Скидка применена.")
        showVenueStaffOrderFullDetailsByIds(chatId, userId, venueId, orderId)
    }

    private suspend fun proceedVenueStaffOrderBillItemExcludeReason(
        chatId: Long,
        from: User?,
        text: String,
        state: DialogState,
    ) {
        val userId = from?.id
        if (userId == null) {
            dialogStateRepository.clear(chatId)
            enqueueMessage(chatId, "Не удалось определить пользователя. Попробуйте /start.")
            return
        }
        val venueId = state.payload["venue_id"]?.toLongOrNull()
        val orderId = state.payload["order_id"]?.toLongOrNull()
        val batchItemId = state.payload["batch_item_id"]?.toLongOrNull()
        if (venueId == null || orderId == null || batchItemId == null) {
            dialogStateRepository.clear(chatId)
            enqueueMessage(chatId, "Не удалось исключить позицию. Попробуйте ещё раз.")
            return
        }
        val trimmed = text.trim()
        if (trimmed == "-" || trimmed == "—") {
            dialogStateRepository.clear(chatId)
            enqueueMessage(chatId, "Отмена действия.")
            showVenueStaffOrderBillItemAction(chatId, userId, "staff_order_bill_item:$venueId:$orderId:$batchItemId")
            return
        }
        val role = resolveVenueRoleForVenue(chatId, userId, venueId) ?: run {
            dialogStateRepository.clear(chatId)
            return
        }
        val actorRole =
            when (role) {
                VenueBotRole.OWNER -> VenueRole.OWNER
                VenueBotRole.MANAGER -> VenueRole.MANAGER
                VenueBotRole.STAFF -> VenueRole.STAFF
            }
        val excluded =
            try {
                venueOrdersRepository.excludeBatchItemFromBill(
                    venueId = venueId,
                    orderId = orderId,
                    batchItemId = batchItemId,
                    reasonText = trimmed,
                    actor = OrderActionActor(userId = userId, role = actorRole),
                )
            } catch (e: DatabaseUnavailableException) {
                enqueueMessage(chatId, "База недоступна, попробуйте позже.")
                return
            }
        dialogStateRepository.clear(chatId)
        if (!excluded) {
            enqueueMessage(chatId, "Позиция не найдена или уже исключена.")
            showVenueStaffOrderFullDetailsByIds(chatId, userId, venueId, orderId)
            return
        }
        enqueueMessage(chatId, "✅ Позиция исключена из счёта.")
        showVenueStaffOrderFullDetailsByIds(chatId, userId, venueId, orderId)
    }

    private suspend fun showVenueStaffOrderDetailsByIds(
        chatId: Long,
        userId: Long,
        venueId: Long,
        orderId: Long,
    ) {
        val role = resolveVenueRoleForVenue(chatId, userId, venueId) ?: return
        val detail =
            try {
                venueOrdersRepository.loadOrderDetail(venueId = venueId, orderId = orderId)
            } catch (e: DatabaseUnavailableException) {
                enqueueMessage(chatId, "База недоступна, попробуйте позже.")
                return
            }
        if (detail == null) {
            enqueueMessage(chatId, "Заказ не найден.")
            showVenueStaffOrdersRootByVenueId(chatId, userId, venueId)
            return
        }
        if (detail.status == OrderWorkflowStatus.CLOSED) {
            enqueueMessage(chatId, "Заказ уже закрыт.")
            showVenueStaffOrdersRootByVenueId(chatId, userId, venueId)
            return
        }
        val pendingNewBatches = pendingNewVenueOrderBatches(detail)
        if (pendingNewBatches.size > 1) {
            enqueueMessage(
                chatId,
                buildVenueStaffOrderMultiNewText(detail, pendingNewBatches),
                TelegramKeyboards.inlineVenueStaffOrderActions(
                    venueId = venueId,
                    orderId = orderId,
                    statusButtons = emptyList(),
                    actionButtons =
                        listOf(
                            "✅ Принять всё новое" to "staff_venue_orders_accept_all_new:$venueId:$orderId",
                            "📋 Весь заказ / Счёт" to "staff_venue_orders_full:$venueId:$orderId",
                        ),
                ),
            )
            return
        }
        if (pendingNewBatches.isEmpty()) {
            val acceptedBatches = operationalBatchesByStatus(detail, OrderWorkflowStatus.ACCEPTED)
            if (acceptedBatches.size > 1) {
                enqueueMessage(
                    chatId,
                    buildVenueStaffOrderGroupedOperationalText(
                        detail = detail,
                        batches = acceptedBatches,
                        statusText = "Принят",
                        title = "Принятые позиции",
                    ),
                    TelegramKeyboards.inlineVenueStaffOrderActions(
                        venueId = venueId,
                        orderId = orderId,
                        statusButtons = emptyList(),
                        actionButtons =
                            listOf(
                                "✅ Доставлено всё" to "staff_venue_orders_deliver_all_accepted:$venueId:$orderId",
                                "📋 Весь заказ / Счёт" to "staff_venue_orders_full:$venueId:$orderId",
                            ),
                    ),
                )
                return
            }
            val deliveredBatches = operationalBatchesByStatus(detail, OrderWorkflowStatus.DELIVERED)
            if (deliveredBatches.size > 1) {
                enqueueMessage(
                    chatId,
                    buildVenueStaffOrderGroupedOperationalText(
                        detail = detail,
                        batches = deliveredBatches,
                        statusText = "Доставлен",
                        title = "Доставленные позиции",
                    ),
                    TelegramKeyboards.inlineVenueStaffOrderActions(
                        venueId = venueId,
                        orderId = orderId,
                        statusButtons = emptyList(),
                        actionButtons =
                            listOf(
                                "📋 Весь заказ / Счёт" to "staff_venue_orders_full:$venueId:$orderId",
                            ),
                    ),
                )
                return
            }
        }
        val currentBatch = currentVenueOrderBatch(detail)
        val isFirstCurrentBatch = isCurrentBatchFirstInOrder(detail, currentBatch)
        val cancelAction =
            if (currentBatch?.status == OrderWorkflowStatus.NEW) {
                if (isFirstCurrentBatch) {
                    "❌ Отменить заказ" to "staff_venue_orders_cancel_order:$venueId:$orderId"
                } else {
                    "❌ Отменить дозаказ" to "staff_venue_orders_cancel_batch:$venueId:$orderId"
                }
            } else {
                null
            }
        val actionButtons =
            buildList {
                if (cancelAction != null) {
                    add(cancelAction)
                }
                add("📋 Весь заказ / Счёт" to "staff_venue_orders_full:$venueId:$orderId")
            }
        enqueueMessage(
            chatId,
            buildVenueStaffOrderDetailsText(detail),
            TelegramKeyboards.inlineVenueStaffOrderActions(
                venueId = venueId,
                orderId = orderId,
                statusButtons =
                    venueStaffOperationalNextStatuses(detail.status, role).map { status ->
                        venueOrderStatusActionLabel(status) to status.toApi()
                    },
                actionButtons = actionButtons,
            ),
        )
    }

    private fun venueStaffOperationalNextStatuses(
        status: OrderWorkflowStatus,
        role: VenueBotRole,
    ): List<OrderWorkflowStatus> =
        venueSideOperationalNextStatuses(status)
            .filter { nextStatus ->
                if (role != VenueBotRole.STAFF) {
                    true
                } else {
                    nextStatus in
                        setOf(
                            OrderWorkflowStatus.ACCEPTED,
                            OrderWorkflowStatus.DELIVERED,
                        )
                }
            }

    private fun venueStaffFullOrderNextStatuses(
        status: OrderWorkflowStatus,
        role: VenueBotRole,
    ): List<OrderWorkflowStatus> =
        venueSideFullOrderNextStatuses(status)
            .filter { nextStatus ->
                if (role != VenueBotRole.STAFF) {
                    true
                } else {
                    nextStatus == OrderWorkflowStatus.CLOSED
                }
            }

    private suspend fun updateVenueStaffOrderStatus(
        chatId: Long,
        userId: Long,
        data: String,
    ) {
        val parsed = parseStaffVenueOrderStatusData(data)
        if (parsed == null) {
            enqueueMessage(chatId, "Не удалось изменить статус заказа. Попробуйте ещё раз.")
            return
        }
        val (venueId, orderId, nextStatus) = parsed
        val role = resolveVenueRoleForVenue(chatId, userId, venueId) ?: return
        if (
            role == VenueBotRole.STAFF &&
            nextStatus !in
            setOf(
                OrderWorkflowStatus.ACCEPTED,
                OrderWorkflowStatus.DELIVERED,
                OrderWorkflowStatus.CLOSED,
            )
        ) {
            enqueueMessage(chatId, "Недостаточно прав для этого действия.")
            return
        }
        val actorRole =
            when (role) {
                VenueBotRole.OWNER -> VenueRole.OWNER
                VenueBotRole.MANAGER -> VenueRole.MANAGER
                VenueBotRole.STAFF -> VenueRole.STAFF
            }
        val detailBeforeUpdate =
            try {
                venueOrdersRepository.loadOrderDetail(venueId = venueId, orderId = orderId)
            } catch (e: DatabaseUnavailableException) {
                enqueueMessage(chatId, "База недоступна, попробуйте позже.")
                return
            }
        if (detailBeforeUpdate == null) {
            enqueueMessage(chatId, "Заказ не найден.")
            showVenueStaffOrdersRootByVenueId(chatId, userId, venueId)
            return
        }
        val updated =
            try {
                applyVenueSideOrderStatusTransition(
                    venueId = venueId,
                    orderId = orderId,
                    nextStatus = nextStatus,
                    actor = OrderActionActor(userId = userId, role = actorRole),
                )
            } catch (e: DatabaseUnavailableException) {
                enqueueMessage(chatId, "База недоступна, попробуйте позже.")
                return
            }
        if (updated == null) {
            enqueueMessage(chatId, "Заказ не найден.")
            showVenueStaffOrdersRootByVenueId(chatId, userId, venueId)
            return
        }
        if (!updated.applied) {
            enqueueMessage(chatId, "Нельзя выполнить этот переход статуса.")
            showVenueStaffOrderDetailsByIds(chatId, userId, venueId, orderId)
            return
        }
        notifyGuestAboutVenueOrderStatusChange(
            detail = detailBeforeUpdate,
            nextStatus = nextStatus,
        )
        enqueueMessage(chatId, "✅ Статус заказа обновлён.")
        if (nextStatus == OrderWorkflowStatus.CLOSED) {
            showVenueStaffOrdersRootByVenueId(chatId, userId, venueId)
        } else {
            showVenueStaffOrderDetailsByIds(chatId, userId, venueId, orderId)
        }
    }

    private suspend fun acceptAllNewVenueStaffOrderBatches(
        chatId: Long,
        userId: Long,
        data: String,
    ) {
        val parsed = parseStaffVenueOrderData(data, "staff_venue_orders_accept_all_new:")
        if (parsed == null) {
            enqueueMessage(chatId, "Не удалось принять новые позиции. Попробуйте ещё раз.")
            return
        }
        val (venueId, orderId) = parsed
        val role = resolveVenueRoleForVenue(chatId, userId, venueId) ?: return
        val actorRole =
            when (role) {
                VenueBotRole.OWNER -> VenueRole.OWNER
                VenueBotRole.MANAGER -> VenueRole.MANAGER
                VenueBotRole.STAFF -> VenueRole.STAFF
            }
        val detailBeforeUpdate =
            try {
                venueOrdersRepository.loadOrderDetail(venueId = venueId, orderId = orderId)
            } catch (e: DatabaseUnavailableException) {
                enqueueMessage(chatId, "База недоступна, попробуйте позже.")
                return
            }
        if (detailBeforeUpdate == null) {
            enqueueMessage(chatId, "Заказ не найден.")
            showVenueStaffOrdersRootByVenueId(chatId, userId, venueId)
            return
        }
        val pendingNewBatches = pendingNewVenueOrderBatches(detailBeforeUpdate)
        if (pendingNewBatches.size <= 1) {
            enqueueMessage(chatId, "Нет нескольких новых дозаказов для общего принятия.")
            showVenueStaffOrderDetailsByIds(chatId, userId, venueId, orderId)
            return
        }
        val updated =
            try {
                venueOrdersRepository.acceptAllNewBatches(
                    venueId = venueId,
                    orderId = orderId,
                    actor = OrderActionActor(userId = userId, role = actorRole),
                )
            } catch (e: DatabaseUnavailableException) {
                enqueueMessage(chatId, "База недоступна, попробуйте позже.")
                return
            }
        if (updated == null) {
            enqueueMessage(chatId, "Заказ не найден.")
            showVenueStaffOrdersRootByVenueId(chatId, userId, venueId)
            return
        }
        if (!updated.applied) {
            enqueueMessage(chatId, "Нельзя выполнить это действие.")
            showVenueStaffOrderDetailsByIds(chatId, userId, venueId, orderId)
            return
        }
        notifyGuestsAboutAcceptedNewBatches(detailBeforeUpdate, pendingNewBatches)
        enqueueMessage(chatId, "✅ Все новые позиции приняты.")
        showVenueStaffOrderDetailsByIds(chatId, userId, venueId, orderId)
    }

    private suspend fun deliverAllAcceptedVenueStaffOrderBatches(
        chatId: Long,
        userId: Long,
        data: String,
    ) {
        val parsed = parseStaffVenueOrderData(data, "staff_venue_orders_deliver_all_accepted:")
        if (parsed == null) {
            enqueueMessage(chatId, "Не удалось доставить принятые позиции. Попробуйте ещё раз.")
            return
        }
        val (venueId, orderId) = parsed
        val role = resolveVenueRoleForVenue(chatId, userId, venueId) ?: return
        val actorRole =
            when (role) {
                VenueBotRole.OWNER -> VenueRole.OWNER
                VenueBotRole.MANAGER -> VenueRole.MANAGER
                VenueBotRole.STAFF -> VenueRole.STAFF
            }
        val detailBeforeUpdate =
            try {
                venueOrdersRepository.loadOrderDetail(venueId = venueId, orderId = orderId)
            } catch (e: DatabaseUnavailableException) {
                enqueueMessage(chatId, "База недоступна, попробуйте позже.")
                return
            }
        if (detailBeforeUpdate == null) {
            enqueueMessage(chatId, "Заказ не найден.")
            showVenueStaffOrdersRootByVenueId(chatId, userId, venueId)
            return
        }
        val acceptedBatches = operationalBatchesByStatus(detailBeforeUpdate, OrderWorkflowStatus.ACCEPTED)
        if (acceptedBatches.size <= 1) {
            enqueueMessage(chatId, "Нет нескольких принятых позиций для общей доставки.")
            showVenueStaffOrderDetailsByIds(chatId, userId, venueId, orderId)
            return
        }
        val updated =
            try {
                venueOrdersRepository.deliverAllAcceptedBatches(
                    venueId = venueId,
                    orderId = orderId,
                    actor = OrderActionActor(userId = userId, role = actorRole),
                )
            } catch (e: DatabaseUnavailableException) {
                enqueueMessage(chatId, "База недоступна, попробуйте позже.")
                return
            }
        if (updated == null) {
            enqueueMessage(chatId, "Заказ не найден.")
            showVenueStaffOrdersRootByVenueId(chatId, userId, venueId)
            return
        }
        if (!updated.applied) {
            enqueueMessage(chatId, "Нельзя выполнить это действие.")
            showVenueStaffOrderDetailsByIds(chatId, userId, venueId, orderId)
            return
        }
        notifyGuestsAboutBatchStatusChange(detailBeforeUpdate, acceptedBatches, OrderWorkflowStatus.DELIVERED)
        enqueueMessage(chatId, "✅ Все принятые позиции доставлены.")
        showVenueStaffOrderDetailsByIds(chatId, userId, venueId, orderId)
    }

    private suspend fun promptVenueStaffBatchCancelReason(
        chatId: Long,
        userId: Long,
        data: String,
    ) {
        val parsed = parseStaffVenueOrderData(data, "staff_venue_orders_cancel_batch:")
        if (parsed == null) {
            enqueueMessage(chatId, "Не удалось отменить дозаказ. Попробуйте ещё раз.")
            return
        }
        val (venueId, orderId) = parsed
        promptVenueStaffOrderCancelReasonByScope(
            chatId = chatId,
            userId = userId,
            venueId = venueId,
            orderId = orderId,
            cancelWholeOrder = false,
        )
    }

    private suspend fun promptVenueStaffOrderCancelReason(
        chatId: Long,
        userId: Long,
        data: String,
    ) {
        val parsed = parseStaffVenueOrderData(data, "staff_venue_orders_cancel_order:")
        if (parsed == null) {
            enqueueMessage(chatId, "Не удалось отменить заказ. Попробуйте ещё раз.")
            return
        }
        val (venueId, orderId) = parsed
        promptVenueStaffOrderCancelReasonByScope(
            chatId = chatId,
            userId = userId,
            venueId = venueId,
            orderId = orderId,
            cancelWholeOrder = true,
        )
    }

    private suspend fun promptVenueStaffOrderCancelReasonByScope(
        chatId: Long,
        userId: Long,
        venueId: Long,
        orderId: Long,
        cancelWholeOrder: Boolean,
    ) {
        resolveVenueRoleForVenue(chatId, userId, venueId) ?: return
        val stateType =
            if (cancelWholeOrder) {
                DialogStateType.VENUE_STAFF_ORDERS_WAIT_ORDER_CANCEL_REASON
            } else {
                DialogStateType.VENUE_STAFF_ORDERS_WAIT_BATCH_CANCEL_REASON
            }
        dialogStateRepository.set(
            chatId,
            DialogState(
                state = stateType,
                payload =
                    mapOf(
                        "venue_id" to venueId.toString(),
                        "order_id" to orderId.toString(),
                    ),
            ),
        )
        enqueueMessage(
            chatId,
            "Укажите причину отмены.\nОтправьте «—», чтобы отменить действие.",
        )
    }

    private suspend fun proceedVenueStaffOrderCancelReason(
        chatId: Long,
        from: User?,
        text: String,
        state: DialogState,
        cancelWholeOrder: Boolean,
    ) {
        val userId = from?.id
        if (userId == null) {
            dialogStateRepository.clear(chatId)
            enqueueMessage(chatId, "Не удалось определить пользователя. Попробуйте /start.")
            return
        }
        val venueId = state.payload["venue_id"]?.toLongOrNull()
        val orderId = state.payload["order_id"]?.toLongOrNull()
        if (venueId == null || orderId == null) {
            dialogStateRepository.clear(chatId)
            enqueueMessage(chatId, "Не удалось отменить заказ. Попробуйте ещё раз.")
            return
        }
        val trimmed = text.trim()
        if (trimmed == "-" || trimmed == "—") {
            dialogStateRepository.clear(chatId)
            enqueueMessage(chatId, "Отмена действия.")
            if (cancelWholeOrder) {
                showVenueStaffOrderFullDetailsByIds(chatId, userId, venueId, orderId)
            } else {
                showVenueStaffOrderDetailsByIds(chatId, userId, venueId, orderId)
            }
            return
        }
        val role = resolveVenueRoleForVenue(chatId, userId, venueId) ?: run {
            dialogStateRepository.clear(chatId)
            return
        }
        val actorRole =
            when (role) {
                VenueBotRole.OWNER -> VenueRole.OWNER
                VenueBotRole.MANAGER -> VenueRole.MANAGER
                VenueBotRole.STAFF -> VenueRole.STAFF
            }
        val detailBeforeUpdate =
            try {
                venueOrdersRepository.loadOrderDetail(venueId = venueId, orderId = orderId)
            } catch (e: DatabaseUnavailableException) {
                enqueueMessage(chatId, "База недоступна, попробуйте позже.")
                return
            }
        if (detailBeforeUpdate == null) {
            dialogStateRepository.clear(chatId)
            enqueueMessage(chatId, "Заказ не найден.")
            showVenueStaffOrdersRootByVenueId(chatId, userId, venueId)
            return
        }
        val reasonText = trimmed.ifBlank { null }
        val updated =
            try {
                if (cancelWholeOrder) {
                    venueOrdersRepository.rejectOrder(
                        venueId = venueId,
                        orderId = orderId,
                        reasonCode = "VENUE_ORDER_CANCELLED",
                        reasonText = reasonText,
                        actor = OrderActionActor(userId = userId, role = actorRole),
                    )
                } else {
                    venueOrdersRepository.rejectLatestBatch(
                        venueId = venueId,
                        orderId = orderId,
                        reasonCode = "VENUE_BATCH_CANCELLED",
                        reasonText = reasonText,
                        actor = OrderActionActor(userId = userId, role = actorRole),
                    )
                }
            } catch (e: DatabaseUnavailableException) {
                enqueueMessage(chatId, "База недоступна, попробуйте позже.")
                return
            }
        if (updated == null) {
            dialogStateRepository.clear(chatId)
            enqueueMessage(chatId, "Заказ не найден.")
            showVenueStaffOrdersRootByVenueId(chatId, userId, venueId)
            return
        }
        if (!updated.applied) {
            dialogStateRepository.clear(chatId)
            enqueueMessage(chatId, "Нельзя выполнить это действие.")
            if (cancelWholeOrder) {
                showVenueStaffOrderFullDetailsByIds(chatId, userId, venueId, orderId)
            } else {
                showVenueStaffOrderDetailsByIds(chatId, userId, venueId, orderId)
            }
            return
        }
        dialogStateRepository.clear(chatId)
        val guestUserId = currentVenueOrderBatch(detailBeforeUpdate)?.authorUserId
        if (guestUserId != null) {
            val guestMessage =
                if (cancelWholeOrder) {
                    "Ваш заказ отменён."
                } else {
                    "Ваш дозаказ отменён."
                }
            enqueueMessage(guestUserId, guestMessage)
        }
        enqueueMessage(chatId, if (cancelWholeOrder) "✅ Заказ отменён." else "✅ Дозаказ отменён.")
        showVenueStaffOrdersRootByVenueId(chatId, userId, venueId)
    }

    private suspend fun showVenueStaffBookings(
        chatId: Long,
        from: User?,
    ) {
        val userId = from?.id
        if (userId == null) {
            enqueueMessage(chatId, "Не удалось определить пользователя. Попробуйте /start.")
            return
        }
        when (val access = resolvePrimaryVenueBotAccess(userId)) {
            null -> showMainMenu(chatId, from)
            else ->
                when (access.role) {
                    VenueBotRole.OWNER -> showVenueOwnerBotEntry(chatId, from)
                    VenueBotRole.MANAGER, VenueBotRole.STAFF ->
                        showVenueStaffBookingsByVenueId(chatId, userId, access.venueId)
                }
        }
    }

    private suspend fun showVenueStaffBookingsByVenueId(
        chatId: Long,
        userId: Long,
        venueId: Long,
    ) {
        val role = resolveVenueRoleForVenue(chatId, userId, venueId) ?: return
        if (role == VenueBotRole.OWNER) {
            showVenueOwnerVenueCardByUserId(chatId, userId)
            return
        }
        val bookings =
            try {
                guestBookingRepository.listActiveByVenue(venueId = venueId, limit = 20)
            } catch (e: DatabaseUnavailableException) {
                enqueueMessage(chatId, "База недоступна, попробуйте позже.")
                return
            }
        if (bookings.isEmpty()) {
            enqueueMessage(chatId, "📄 Брони\n\nАктуальных броней сейчас нет.")
            return
        }
        enqueueMessage(chatId, buildVenueStaffBookingsText(bookings))
    }

    private suspend fun showVenueStaffStopListRoot(
        chatId: Long,
        from: User?,
    ) {
        val userId = from?.id
        if (userId == null) {
            enqueueMessage(chatId, "Не удалось определить пользователя. Попробуйте /start.")
            return
        }
        when (val access = resolvePrimaryVenueBotAccess(userId)) {
            null -> showMainMenu(chatId, from)
            else ->
                when (access.role) {
                    VenueBotRole.OWNER ->
                        showVenueOwnerOrderMenuStopListByVenueId(chatId, userId, access.venueId)
                    VenueBotRole.MANAGER, VenueBotRole.STAFF ->
                        showVenueStaffStopListRootByVenueId(chatId, userId, access.venueId)
                }
        }
    }

    private suspend fun showVenueStaffStopListRootByCallback(
        chatId: Long,
        userId: Long,
        data: String,
    ) {
        val venueId = parseOwnerVenueIdFromCallback(data, "staff_venue_stoplist_root:")
        if (venueId == null) {
            enqueueMessage(chatId, "Не удалось открыть стоп-лист. Попробуйте ещё раз.")
            return
        }
        showVenueStaffStopListRootByVenueId(chatId, userId, venueId)
    }

    private suspend fun showVenueStaffStopListRootByVenueId(
        chatId: Long,
        userId: Long,
        venueId: Long,
    ) {
        val role = resolveVenueRoleForVenue(chatId, userId, venueId) ?: return
        if (role == VenueBotRole.OWNER) {
            showVenueOwnerOrderMenuStopListByVenueId(chatId, userId, venueId)
            return
        }
        val categories =
            try {
                venueMenuRepository.getMenu(venueId)
            } catch (e: DatabaseUnavailableException) {
                enqueueMessage(chatId, "База недоступна, попробуйте позже.")
                return
            }
        val stopListEntries =
            categories.flatMap { category ->
                category.items.flatMap { item ->
                    buildList {
                        if (!item.isAvailable) {
                            add(
                                OwnerVenueStopListEntry(
                                    sectionId = category.id,
                                    sectionName = category.name,
                                    itemId = item.id,
                                    itemName = item.name,
                                    itemPriceMinor = item.priceMinor,
                                    currency = item.currency,
                                ),
                            )
                        }
                        item.options
                            .filter { !it.isAvailable }
                            .forEach { option ->
                                add(
                                    OwnerVenueStopListEntry(
                                        sectionId = category.id,
                                        sectionName = category.name,
                                        itemId = item.id,
                                        itemName = item.name,
                                        itemPriceMinor = item.priceMinor,
                                        currency = item.currency,
                                        optionId = option.id,
                                        optionName = option.name,
                                    ),
                                )
                            }
                    }
                }
            }
        if (stopListEntries.isEmpty()) {
            enqueueMessage(
                chatId,
                "🚫 Стоп-лист\nЗдесь будут позиции, скрытые для гостей.\n\nСтоп-лист пока пуст.",
                TelegramKeyboards.inlineVenueStaffStopListRootActions(venueId, emptyList()),
            )
            return
        }
        enqueueMessage(
            chatId,
            buildOwnerVenueOrderMenuStopListText(stopListEntries),
            TelegramKeyboards.inlineVenueStaffStopListRootActions(
                venueId = venueId,
                stopListButtons =
                    stopListEntries.map { entry ->
                        if (entry.optionId == null) {
                            "✅ Убрать из стоп-листа: ${entry.itemName}" to
                                "staff_venue_stoplist_unstop_item:$venueId:${entry.sectionId}:${entry.itemId}"
                        } else {
                            "✅ Убрать из стоп-листа: ${entry.optionName}" to
                                "staff_venue_stoplist_unstop_option:$venueId:${entry.sectionId}:${entry.itemId}:${entry.optionId}"
                        }
                    },
            ),
        )
    }

    private suspend fun showVenueStaffStopListSections(
        chatId: Long,
        userId: Long,
        data: String,
    ) {
        val venueId = parseOwnerVenueIdFromCallback(data, "staff_venue_stoplist_add:")
        if (venueId == null) {
            enqueueMessage(chatId, "Не удалось открыть разделы меню. Попробуйте ещё раз.")
            return
        }
        val role = resolveVenueRoleForVenue(chatId, userId, venueId) ?: return
        if (role == VenueBotRole.OWNER) {
            showVenueOwnerOrderMenuRootByVenueId(chatId, userId, venueId)
            return
        }
        val categories =
            try {
                venueMenuRepository.getMenu(venueId)
            } catch (e: DatabaseUnavailableException) {
                enqueueMessage(chatId, "База недоступна, попробуйте позже.")
                return
            }
        if (categories.isEmpty()) {
            enqueueMessage(chatId, "Разделы заказного меню пока не добавлены.")
            return
        }
        enqueueMessage(
            chatId,
            "🚫 Стоп-лист\n\nВыберите раздел, чтобы изменить доступность позиций и вкусов.",
            TelegramKeyboards.inlineVenueStaffStopListSectionsActions(
                venueId = venueId,
                sectionButtons = categories.map { category -> category.id to category.name },
            ),
        )
    }

    private suspend fun showVenueStaffStopListSection(
        chatId: Long,
        userId: Long,
        data: String,
    ) {
        val parsed = parseOwnerVenueMenuSectionData(data, "staff_venue_stoplist_section:")
        if (parsed == null) {
            enqueueMessage(chatId, "Не удалось открыть раздел. Попробуйте ещё раз.")
            return
        }
        val (venueId, sectionId) = parsed
        val role = resolveVenueRoleForVenue(chatId, userId, venueId) ?: return
        if (role == VenueBotRole.OWNER) {
            showVenueOwnerOrderMenuSectionByIds(chatId, userId, venueId, sectionId)
            return
        }
        val category =
            loadOwnerOrderMenuCategory(chatId, venueId, sectionId)
                ?: run {
                    enqueueMessage(chatId, "Раздел не найден.")
                    showVenueStaffStopListSections(chatId, userId, "staff_venue_stoplist_add:$venueId")
                    return
                }
        enqueueMessage(
            chatId,
            buildOwnerVenueOrderMenuSectionText(category),
            TelegramKeyboards.inlineVenueStaffStopListSectionActions(
                venueId = venueId,
                sectionId = sectionId,
                itemButtons = category.items.map { item -> item.id to buildOwnerVenueOrderMenuItemButtonLabel(item) },
            ),
        )
    }

    private suspend fun showVenueStaffStopListItem(
        chatId: Long,
        userId: Long,
        data: String,
    ) {
        val parsed = parseOwnerVenueMenuItemData(data, "staff_venue_stoplist_item:")
        if (parsed == null) {
            enqueueMessage(chatId, "Не удалось открыть позицию. Попробуйте ещё раз.")
            return
        }
        val (venueId, sectionId, itemId) = parsed
        showVenueStaffStopListItemByIds(chatId, userId, venueId, sectionId, itemId)
    }

    private suspend fun showVenueStaffStopListItemByIds(
        chatId: Long,
        userId: Long,
        venueId: Long,
        sectionId: Long,
        itemId: Long,
    ) {
        val role = resolveVenueRoleForVenue(chatId, userId, venueId) ?: return
        if (role == VenueBotRole.OWNER) {
            showVenueOwnerOrderMenuItemByIds(chatId, userId, venueId, sectionId, itemId)
            return
        }
        val (category, item) = loadOwnerOrderMenuItem(chatId, venueId, sectionId, itemId) ?: return
        enqueueMessage(
            chatId,
            buildOwnerVenueOrderMenuItemText(item, showFlavorSummary = isHookahMenuSection(category)),
            TelegramKeyboards.inlineVenueStaffStopListItemActions(
                venueId = venueId,
                sectionId = sectionId,
                itemId = itemId,
                isAvailable = item.isAvailable,
                showFlavorProfile = isHookahMenuSection(category),
            ),
        )
    }

    private suspend fun showVenueStaffStopListItemFlavors(
        chatId: Long,
        userId: Long,
        data: String,
    ) {
        val parsed = parseOwnerVenueMenuItemData(data, "staff_venue_stoplist_item_flavors:")
        if (parsed == null) {
            enqueueMessage(chatId, "Не удалось открыть вкусы. Попробуйте ещё раз.")
            return
        }
        val (venueId, sectionId, itemId) = parsed
        showVenueStaffStopListItemFlavorsByIds(chatId, userId, venueId, sectionId, itemId)
    }

    private suspend fun showVenueStaffStopListItemFlavorsByIds(
        chatId: Long,
        userId: Long,
        venueId: Long,
        sectionId: Long,
        itemId: Long,
    ) {
        val role = resolveVenueRoleForVenue(chatId, userId, venueId) ?: return
        if (role == VenueBotRole.OWNER) {
            showVenueOwnerOrderMenuItemFlavorsByIds(chatId, userId, venueId, sectionId, itemId)
            return
        }
        val (category, item) = loadOwnerOrderMenuItem(chatId, venueId, sectionId, itemId) ?: return
        if (!isHookahMenuSection(category)) {
            enqueueMessage(chatId, "Вкусы доступны только для раздела «Кальянное меню».")
            return
        }
        enqueueMessage(
            chatId,
            buildOwnerVenueOrderMenuFlavorListText(item),
            TelegramKeyboards.inlineVenueStaffStopListFlavorListActions(
                venueId = venueId,
                sectionId = sectionId,
                itemId = itemId,
                flavorButtons = item.options.map { option -> option.id to buildOwnerVenueOrderMenuFlavorButtonLabel(option) },
            ),
        )
    }

    private suspend fun showVenueStaffStopListOption(
        chatId: Long,
        userId: Long,
        data: String,
    ) {
        val parsed = parseOwnerVenueMenuOptionData(data, "staff_venue_stoplist_option:")
        if (parsed == null) {
            enqueueMessage(chatId, "Не удалось открыть вкус. Попробуйте ещё раз.")
            return
        }
        val (venueId, sectionId, itemId, optionId) = parsed
        showVenueStaffStopListOptionByIds(chatId, userId, venueId, sectionId, itemId, optionId)
    }

    private suspend fun showVenueStaffStopListOptionByIds(
        chatId: Long,
        userId: Long,
        venueId: Long,
        sectionId: Long,
        itemId: Long,
        optionId: Long,
    ) {
        val role = resolveVenueRoleForVenue(chatId, userId, venueId) ?: return
        if (role == VenueBotRole.OWNER) {
            showVenueOwnerOrderMenuFlavorOptionByIds(chatId, userId, venueId, sectionId, itemId, optionId)
            return
        }
        val (category, item) = loadOwnerOrderMenuItem(chatId, venueId, sectionId, itemId) ?: return
        if (!isHookahMenuSection(category)) {
            enqueueMessage(chatId, "Вкусы доступны только для раздела «Кальянное меню».")
            return
        }
        val option =
            item.options.firstOrNull { it.id == optionId }
                ?: run {
                    enqueueMessage(chatId, "Вкус не найден.")
                    showVenueStaffStopListItemFlavorsByIds(chatId, userId, venueId, sectionId, itemId)
                    return
                }
        enqueueMessage(
            chatId,
            buildOwnerVenueOrderMenuFlavorOptionText(option),
            TelegramKeyboards.inlineVenueStaffStopListFlavorOptionActions(
                venueId = venueId,
                sectionId = sectionId,
                itemId = itemId,
                optionId = optionId,
                isAvailable = option.isAvailable,
            ),
        )
    }

    private suspend fun setVenueStaffStopListItemAvailability(
        chatId: Long,
        userId: Long,
        data: String,
        isAvailable: Boolean,
    ) {
        val prefix =
            if (isAvailable) {
                "staff_venue_stoplist_item_unstop:"
            } else {
                "staff_venue_stoplist_item_stop:"
            }
        val parsed = parseOwnerVenueMenuItemData(data, prefix)
        if (parsed == null) {
            enqueueMessage(chatId, "Не удалось изменить статус позиции. Попробуйте ещё раз.")
            return
        }
        val (venueId, sectionId, itemId) = parsed
        val role = resolveVenueRoleForVenue(chatId, userId, venueId) ?: return
        if (role == VenueBotRole.OWNER) {
            setVenueOwnerOrderMenuItemAvailability(
                chatId = chatId,
                userId = userId,
                data =
                    if (isAvailable) {
                        "owner_venue_order_menu_item_unstop:$venueId:$sectionId:$itemId"
                    } else {
                        "owner_venue_order_menu_item_stop:$venueId:$sectionId:$itemId"
                    },
                isAvailable = isAvailable,
            )
            return
        }
        val updated =
            try {
                venueMenuRepository.setItemAvailability(
                    venueId = venueId,
                    itemId = itemId,
                    isAvailable = isAvailable,
                )
            } catch (e: DatabaseUnavailableException) {
                enqueueMessage(chatId, "База недоступна, попробуйте позже.")
                return
            }
        if (updated == null) {
            enqueueMessage(chatId, "Позиция не найдена.")
            showVenueStaffStopListSection(chatId, userId, "staff_venue_stoplist_section:$venueId:$sectionId")
            return
        }
        enqueueMessage(
            chatId,
            if (isAvailable) "✅ Позиция убрана из стоп-листа." else "✅ Позиция добавлена в стоп-лист.",
        )
        showVenueStaffStopListItemByIds(chatId, userId, venueId, sectionId, itemId)
    }

    private suspend fun setVenueStaffStopListOptionAvailability(
        chatId: Long,
        userId: Long,
        data: String,
        isAvailable: Boolean,
    ) {
        val prefix =
            if (isAvailable) {
                "staff_venue_stoplist_option_unstop:"
            } else {
                "staff_venue_stoplist_option_stop:"
            }
        val parsed = parseOwnerVenueMenuOptionData(data, prefix)
        if (parsed == null) {
            enqueueMessage(chatId, "Не удалось изменить статус вкуса. Попробуйте ещё раз.")
            return
        }
        val (venueId, sectionId, itemId, optionId) = parsed
        val role = resolveVenueRoleForVenue(chatId, userId, venueId) ?: return
        if (role == VenueBotRole.OWNER) {
            setVenueOwnerOrderMenuFlavorAvailability(
                chatId = chatId,
                userId = userId,
                data =
                    if (isAvailable) {
                        "owner_venue_order_menu_item_option_unstop:$venueId:$sectionId:$itemId:$optionId"
                    } else {
                        "owner_venue_order_menu_item_option_stop:$venueId:$sectionId:$itemId:$optionId"
                    },
                isAvailable = isAvailable,
            )
            return
        }
        val updated =
            try {
                venueMenuRepository.setOptionAvailability(
                    venueId = venueId,
                    optionId = optionId,
                    isAvailable = isAvailable,
                )
            } catch (e: DatabaseUnavailableException) {
                enqueueMessage(chatId, "База недоступна, попробуйте позже.")
                return
            }
        if (updated == null) {
            enqueueMessage(chatId, "Вкус не найден.")
            showVenueStaffStopListItemFlavorsByIds(chatId, userId, venueId, sectionId, itemId)
            return
        }
        enqueueMessage(
            chatId,
            if (isAvailable) "✅ Вкус убран из стоп-листа." else "✅ Вкус добавлен в стоп-лист.",
        )
        showVenueStaffStopListOptionByIds(chatId, userId, venueId, sectionId, itemId, optionId)
    }

    private suspend fun removeVenueStaffStopListItem(
        chatId: Long,
        userId: Long,
        data: String,
    ) {
        val parsed = parseOwnerVenueMenuItemData(data, "staff_venue_stoplist_unstop_item:")
        if (parsed == null) {
            enqueueMessage(chatId, "Не удалось изменить статус позиции. Попробуйте ещё раз.")
            return
        }
        val (venueId, _, itemId) = parsed
        val role = resolveVenueRoleForVenue(chatId, userId, venueId) ?: return
        if (role == VenueBotRole.OWNER) {
            removeVenueOwnerOrderMenuItemFromStopList(
                chatId = chatId,
                userId = userId,
                data = "owner_venue_order_menu_stoplist_unstop:$venueId:0:$itemId",
            )
            return
        }
        val updated =
            try {
                venueMenuRepository.setItemAvailability(
                    venueId = venueId,
                    itemId = itemId,
                    isAvailable = true,
                )
            } catch (e: DatabaseUnavailableException) {
                enqueueMessage(chatId, "База недоступна, попробуйте позже.")
                return
            }
        if (updated == null) {
            enqueueMessage(chatId, "Позиция не найдена.")
            showVenueStaffStopListRootByVenueId(chatId, userId, venueId)
            return
        }
        enqueueMessage(chatId, "✅ Позиция убрана из стоп-листа.")
        showVenueStaffStopListRootByVenueId(chatId, userId, venueId)
    }

    private suspend fun removeVenueStaffStopListOption(
        chatId: Long,
        userId: Long,
        data: String,
    ) {
        val parsed = parseOwnerVenueMenuOptionData(data, "staff_venue_stoplist_unstop_option:")
        if (parsed == null) {
            enqueueMessage(chatId, "Не удалось изменить статус вкуса. Попробуйте ещё раз.")
            return
        }
        val (venueId, _, _, optionId) = parsed
        val role = resolveVenueRoleForVenue(chatId, userId, venueId) ?: return
        if (role == VenueBotRole.OWNER) {
            removeVenueOwnerOrderMenuOptionFromStopList(
                chatId = chatId,
                userId = userId,
                data = "owner_venue_order_menu_stoplist_unstop_option:$venueId:0:0:$optionId",
            )
            return
        }
        val updated =
            try {
                venueMenuRepository.setOptionAvailability(
                    venueId = venueId,
                    optionId = optionId,
                    isAvailable = true,
                )
            } catch (e: DatabaseUnavailableException) {
                enqueueMessage(chatId, "База недоступна, попробуйте позже.")
                return
            }
        if (updated == null) {
            enqueueMessage(chatId, "Вкус не найден.")
            showVenueStaffStopListRootByVenueId(chatId, userId, venueId)
            return
        }
        enqueueMessage(chatId, "✅ Вкус убран из стоп-листа.")
        showVenueStaffStopListRootByVenueId(chatId, userId, venueId)
    }

    private suspend fun applyVenueSideOrderStatusTransition(
        venueId: Long,
        orderId: Long,
        nextStatus: OrderWorkflowStatus,
        actor: OrderActionActor,
    ): com.hookah.platform.backend.miniapp.venue.orders.OrderStatusUpdateResult? {
        if (nextStatus != OrderWorkflowStatus.DELIVERED) {
            return venueOrdersRepository.updateOrderStatus(
                venueId = venueId,
                orderId = orderId,
                nextStatus = nextStatus,
                actor = actor,
            )
        }
        val detail = venueOrdersRepository.loadOrderDetail(venueId = venueId, orderId = orderId) ?: return null
        val transitions =
            when (detail.status) {
                OrderWorkflowStatus.ACCEPTED ->
                    listOf(
                        OrderWorkflowStatus.COOKING,
                        OrderWorkflowStatus.DELIVERING,
                        OrderWorkflowStatus.DELIVERED,
                    )
                OrderWorkflowStatus.COOKING ->
                    listOf(
                        OrderWorkflowStatus.DELIVERING,
                        OrderWorkflowStatus.DELIVERED,
                    )
                OrderWorkflowStatus.DELIVERING -> listOf(OrderWorkflowStatus.DELIVERED)
                else -> listOf(OrderWorkflowStatus.DELIVERED)
            }
        var lastResult: com.hookah.platform.backend.miniapp.venue.orders.OrderStatusUpdateResult? = null
        for (transition in transitions) {
            val stepResult =
                venueOrdersRepository.updateOrderStatus(
                    venueId = venueId,
                    orderId = orderId,
                    nextStatus = transition,
                    actor = actor,
                ) ?: return null
            if (!stepResult.applied) {
                return stepResult
            }
            lastResult = stepResult
        }
        return lastResult
    }

    private fun venueSideOperationalNextStatuses(current: OrderWorkflowStatus): List<OrderWorkflowStatus> =
        when (current) {
            OrderWorkflowStatus.NEW -> listOf(OrderWorkflowStatus.ACCEPTED)
            OrderWorkflowStatus.ACCEPTED,
            OrderWorkflowStatus.COOKING,
            OrderWorkflowStatus.DELIVERING,
            -> listOf(OrderWorkflowStatus.DELIVERED)
            OrderWorkflowStatus.DELIVERED,
            OrderWorkflowStatus.CLOSED,
            -> emptyList()
        }

    private fun venueSideFullOrderNextStatuses(current: OrderWorkflowStatus): List<OrderWorkflowStatus> =
        when (current) {
            OrderWorkflowStatus.DELIVERED -> listOf(OrderWorkflowStatus.CLOSED)
            OrderWorkflowStatus.CLOSED -> emptyList()
            else -> emptyList()
        }

    private suspend fun loadVenueStaffOrderQueue(
        chatId: Long,
        venueId: Long,
    ): List<OrderQueueItem>? {
        return try {
            venueOrdersRepository.listOperationalQueueByOrder(
                venueId = venueId,
                limit = 20,
            )
        } catch (e: DatabaseUnavailableException) {
            logger.warn("staff orders queue failed for venueId={}: {}", venueId, e.message)
            enqueueMessage(chatId, "База недоступна, попробуйте позже.")
            null
        }
    }

    private fun buildVenueStaffCallsText(items: List<StaffCallQueueItem>): String =
        buildString {
            append("🛎 Вызовы")
            append("\n\n")
            items.forEach { item ->
                append("• Вызов #${item.id} · Стол ${item.tableNumber}")
                append("\n  Причина: ${humanizeStaffCallQueueReason(item.reason)}")
                append("\n  Статус: ${humanizeStaffCallQueueStatus(item.status)}")
                item.comment?.takeIf { it.isNotBlank() }?.let { comment ->
                    append("\n  Комментарий: $comment")
                }
                append("\n")
            }
        }.trimEnd()

    private fun humanizeStaffCallQueueReason(reason: String): String =
        when (reason.uppercase(Locale.ROOT)) {
            "COME" -> "Консультация"
            "COALS" -> "Заменить угли"
            "BILL" -> "Принести счёт"
            "OTHER" -> "Другое"
            else -> reason
        }

    private fun humanizeStaffCallQueueStatus(status: String): String =
        when (status.uppercase(Locale.ROOT)) {
            "NEW" -> "Новый"
            "ACK" -> "В работе"
            "DONE" -> "Выполнен"
            "CANCELLED" -> "Отменён"
            else -> status
        }

    private fun buildVenueStaffOrderQueueText(items: List<OrderQueueItem>): String =
        buildString {
            append("🧾 Заказы")
            append("\n\n")
            items.forEach { item ->
                append("• ${formatTelegramOrderDisplayLabel(item.orderId, item.displayNumber, item.displayDate)} · Стол ${item.tableNumber}")
                append("\n  Статус: ${humanizeVenueOrderWorkflowStatus(item.status)}")
                val reordersCount = (item.activeBatchesCount - 1).coerceAtLeast(0)
                if (reordersCount > 0) {
                    append("\n  Активных дозаказов: $reordersCount")
                }
                item.comment?.takeIf { it.isNotBlank() }?.let { comment ->
                    append("\n  Комментарий текущего запроса: $comment")
                }
                append("\n")
            }
        }.trimEnd()

    private fun buildVenueStaffOrderQueueButtonLabel(item: OrderQueueItem): String {
        val reordersCount = (item.activeBatchesCount - 1).coerceAtLeast(0)
        val reorderSuffix =
            if (reordersCount > 0) {
                " · +$reordersCount дозаказ"
            } else {
                ""
            }
        return "${formatTelegramOrderDisplayLabel(item.orderId, item.displayNumber, item.displayDate)} · Стол ${item.tableNumber} · ${humanizeVenueOrderWorkflowStatus(item.status)}$reorderSuffix"
    }

    private fun buildVenueStaffOrderDetailsText(detail: com.hookah.platform.backend.miniapp.venue.orders.OrderDetail): String {
        val currentBatch = currentVenueOrderBatch(detail)
        val currentBatchItems = currentBatch?.items.orEmpty()
        val isReorder = !isCurrentBatchFirstInOrder(detail, currentBatch)
        return buildString {
            append(formatTelegramOrderDisplayLabel(detail.orderId, detail.displayNumber, detail.displayDate))
            append("\nСтол: ${detail.tableNumber}")
            append("\nСтатус: ${humanizeVenueOrderWorkflowStatus(detail.status)}")
            currentBatch?.comment?.takeIf { it.isNotBlank() }?.let { comment ->
                append("\nКомментарий: $comment")
            }
            append("\n\n")
            append(if (isReorder) "Текущий дозаказ:" else "Состав:")
            if (currentBatchItems.isEmpty()) {
                append("\n• —")
            } else {
                currentBatchItems.forEach { item ->
                    append("\n• ${item.name} ×${item.qty}")
                    if (item.priceMinor != null && !item.currency.isNullOrBlank()) {
                        append(" — ${formatPrice(item.priceMinor, item.currency)}")
                    }
                }
            }
        }
    }

    private fun buildVenueStaffOrderMultiNewText(
        detail: com.hookah.platform.backend.miniapp.venue.orders.OrderDetail,
        pendingNewBatches: List<OrderBatchDetail>,
    ): String =
        buildVenueStaffOrderGroupedOperationalText(
            detail = detail,
            batches = pendingNewBatches,
            statusText = "Новый",
            title = "Необработанные позиции",
        )

    private fun buildVenueStaffOrderGroupedOperationalText(
        detail: com.hookah.platform.backend.miniapp.venue.orders.OrderDetail,
        batches: List<OrderBatchDetail>,
        statusText: String,
        title: String,
    ): String =
        buildString {
            append(formatTelegramOrderDisplayLabel(detail.orderId, detail.displayNumber, detail.displayDate))
            append("\nСтол: ${detail.tableNumber}")
            append("\nСтатус: $statusText")
            append("\n\n$title:")
            batches.forEach { batch ->
                append("\n\n")
                append(venueOrderBatchLabel(detail, batch))
                batch.comment?.takeIf { it.isNotBlank() }?.let { comment ->
                    append("\nКомментарий: $comment")
                }
                if (batch.items.isEmpty()) {
                    append("\n• —")
                } else {
                    batch.items.forEach { item ->
                        append("\n• ${item.name} ×${item.qty}")
                        if (item.priceMinor != null && !item.currency.isNullOrBlank()) {
                            append(" — ${formatPrice(item.priceMinor * item.qty, item.currency)}")
                        }
                    }
                }
            }
        }

    private suspend fun loadVenueStaffOrderDetailForEdit(
        chatId: Long,
        userId: Long,
        venueId: Long,
        orderId: Long,
    ): OrderDetail? {
        resolveVenueRoleForVenue(chatId, userId, venueId) ?: return null
        val detail =
            try {
                venueOrdersRepository.loadOrderDetail(venueId = venueId, orderId = orderId)
            } catch (e: DatabaseUnavailableException) {
                enqueueMessage(chatId, "База недоступна, попробуйте позже.")
                return null
            }
        if (detail == null) {
            enqueueMessage(chatId, "Заказ не найден.")
            showVenueStaffOrdersRootByVenueId(chatId, userId, venueId)
            return null
        }
        if (detail.status == OrderWorkflowStatus.CLOSED) {
            enqueueMessage(chatId, "Заказ уже закрыт.")
            showVenueStaffOrdersRootByVenueId(chatId, userId, venueId)
            return null
        }
        return detail
    }

    private fun activeVenueOrderBillItems(detail: OrderDetail): List<Pair<OrderBatchDetail, OrderBatchItemDetail>> =
        detail.batches
            .sortedWith(compareBy({ it.createdAt }, { it.batchId }))
            .filter { batch -> !isCancelledVenueOrderBatch(batch) }
            .flatMap { batch ->
                batch.items
                    .filter { item -> !item.isExcluded }
                    .map { item -> batch to item }
            }

    private fun buildVenueStaffOrderBillItemPickerText(
        items: List<Pair<OrderBatchDetail, OrderBatchItemDetail>>,
    ): String =
        buildString {
            append("Выберите позицию для изменения")
            append("\n\n")
            items.forEach { (_, item) ->
                append("• ${item.name} ×${item.qty}")
                buildVenueOrderBillItemPriceText(item)?.let { priceText -> append(" — $priceText") }
                append("\n")
            }
        }.trimEnd()

    private fun buildVenueStaffOrderBillItemButtonLabel(item: OrderBatchItemDetail): String =
        "${item.name} ×${item.qty}"

    private fun buildVenueStaffOrderBillItemActionText(item: OrderBatchItemDetail): String =
        buildString {
            append("Что сделать с позицией?")
            append("\n\n")
            append("${item.name} ×${item.qty}")
            buildVenueOrderBillItemPriceText(item)?.let { priceText -> append(" — $priceText") }
        }

    private fun buildVenueOrderBillItemPriceText(item: OrderBatchItemDetail): String? {
        val priceMinor = item.priceMinor ?: return null
        val currency = item.currency?.takeIf { it.isNotBlank() } ?: return null
        val baseTotalMinor = priceMinor * item.qty
        val discountPercent = item.discountPercent?.takeIf { it in 1..100 }
        return if (discountPercent == null) {
            formatPrice(baseTotalMinor, currency)
        } else {
            val discountedTotalMinor = applyDiscountPercent(baseTotalMinor, discountPercent)
            "${formatPrice(baseTotalMinor, currency)}, скидка $discountPercent%, к оплате ${formatPrice(discountedTotalMinor, currency)}"
        }
    }

    private fun venueOrderBillItemPayableMinor(item: OrderBatchItemDetail): Long? {
        val priceMinor = item.priceMinor ?: return null
        val baseTotalMinor = priceMinor * item.qty
        val discountPercent = item.discountPercent?.takeIf { it in 1..100 }
        return if (discountPercent == null) baseTotalMinor else applyDiscountPercent(baseTotalMinor, discountPercent)
    }

    private fun applyDiscountPercent(
        amountMinor: Long,
        discountPercent: Int,
    ): Long =
        amountMinor * (100 - discountPercent) / 100

    private fun buildVenueStaffOrderFullText(detail: com.hookah.platform.backend.miniapp.venue.orders.OrderDetail): String {
        val batches =
            detail.batches
                .sortedWith(compareBy({ it.createdAt }, { it.batchId }))
                .mapIndexed { index, batch ->
                    val label =
                        if (index == 0) {
                            "Основной заказ"
                        } else {
                            "Дозаказ $index"
                        }
                    label to batch
                }
        val activeBatches =
            batches.filter { (_, batch) ->
                !isCancelledVenueOrderBatch(batch) && batch.items.any { item -> !item.isExcluded }
            }
        val cancelledBatches = batches.filter { (_, batch) -> isCancelledVenueOrderBatch(batch) }
        val excludedItems =
            batches
                .filter { (_, batch) -> !isCancelledVenueOrderBatch(batch) }
                .flatMap { (label, batch) ->
                    batch.items
                        .filter { item -> item.isExcluded }
                        .map { item -> label to item }
                }
        var totalMinor = 0L
        var totalCurrency: String? = null
        return buildString {
            append("📋 Счёт по столу #${detail.tableNumber}")
            append("\n")
            append("Статус: ${humanizeVenueOrderWorkflowStatus(detail.status)}")
            append("\n\n")
            append("🟢 Активные позиции")
            if (activeBatches.isEmpty()) {
                append("\n• —")
            } else {
                activeBatches.forEach { (label, batch) ->
                    append("\n")
                    append(label)
                    batch.comment?.takeIf { it.isNotBlank() }?.let { comment ->
                        append("\nКомментарий: $comment")
                    }
                    val activeItems = batch.items.filter { item -> !item.isExcluded }
                    if (activeItems.isEmpty()) {
                        append("\n• —")
                    } else {
                        activeItems.forEach { item ->
                            append("\n• ${item.name} ×${item.qty}")
                            val priceText = buildVenueOrderBillItemPriceText(item)
                            val payableMinor = venueOrderBillItemPayableMinor(item)
                            if (priceText != null && payableMinor != null && !item.currency.isNullOrBlank()) {
                                append(" — $priceText")
                                totalMinor += payableMinor
                                if (totalCurrency == null) {
                                    totalCurrency = item.currency
                                }
                            }
                        }
                    }
                }
            }
            append("\n\n🧾 Итого:")
            append("\n")
            val currency = totalCurrency
            if (currency != null) {
                append(formatPrice(totalMinor, currency))
            } else {
                append("—")
            }
            if (cancelledBatches.isNotEmpty() || excludedItems.isNotEmpty()) {
                append("\n\n────────────")
                append("\n\n⚠️ Отменённые позиции")
                cancelledBatches.forEach { (label, batch) ->
                    append("\n\n")
                    append(label)
                    val cancelReason =
                        batch.rejectedReasonText
                            ?.trim()
                            ?.takeIf { it.isNotBlank() }
                            ?: humanizeOrderCancelReason(batch.rejectedReasonCode)
                    if (batch.items.isEmpty()) {
                        append("\n• —")
                    } else {
                        batch.items.forEach { item ->
                            append("\n• ${item.name} ×${item.qty}")
                        }
                    }
                    append("\nПричина: $cancelReason")
                }
                excludedItems.forEach { (label, item) ->
                    append("\n\n")
                    append(label)
                    append("\n• ${item.name} ×${item.qty}")
                    append("\nПричина: ${item.excludedReasonText?.takeIf { it.isNotBlank() } ?: "Не указана"}")
                }
            }
        }
    }

    private fun isCancelledVenueOrderBatch(batch: OrderBatchDetail): Boolean =
        batch.status == OrderWorkflowStatus.CLOSED ||
            !batch.rejectedReasonCode.isNullOrBlank() ||
            !batch.rejectedReasonText.isNullOrBlank()

    private suspend fun notifyGuestAboutVenueOrderStatusChange(
        detail: com.hookah.platform.backend.miniapp.venue.orders.OrderDetail,
        nextStatus: OrderWorkflowStatus,
    ) {
        val currentBatch = currentVenueOrderBatch(detail) ?: return
        val message =
            guestOrderStatusNotificationMessage(
                nextStatus = nextStatus,
                isReorder = !isCurrentBatchFirstInOrder(detail, currentBatch),
            ) ?: return
        val guestUserId = currentBatch.authorUserId ?: return
        enqueueMessage(guestUserId, message)
    }

    private suspend fun notifyGuestsAboutAcceptedNewBatches(
        detail: com.hookah.platform.backend.miniapp.venue.orders.OrderDetail,
        acceptedBatches: List<OrderBatchDetail>,
    ) {
        notifyGuestsAboutBatchStatusChange(detail, acceptedBatches, OrderWorkflowStatus.ACCEPTED)
    }

    private suspend fun notifyGuestsAboutBatchStatusChange(
        detail: com.hookah.platform.backend.miniapp.venue.orders.OrderDetail,
        batches: List<OrderBatchDetail>,
        nextStatus: OrderWorkflowStatus,
    ) {
        batches.forEach { batch ->
            val guestUserId = batch.authorUserId ?: return@forEach
            val message =
                guestOrderStatusNotificationMessage(
                    nextStatus = nextStatus,
                    isReorder = !isCurrentBatchFirstInOrder(detail, batch),
                ) ?: return@forEach
            enqueueMessage(guestUserId, message)
        }
    }

    private fun pendingNewVenueOrderBatches(
        detail: com.hookah.platform.backend.miniapp.venue.orders.OrderDetail,
    ): List<OrderBatchDetail> =
        operationalBatchesByStatus(detail, OrderWorkflowStatus.NEW)

    private fun operationalBatchesByStatus(
        detail: com.hookah.platform.backend.miniapp.venue.orders.OrderDetail,
        status: OrderWorkflowStatus,
    ): List<OrderBatchDetail> =
        detail.batches
            .asSequence()
            .filter { it.status == status }
            .sortedWith(compareBy({ it.createdAt }, { it.batchId }))
            .toList()

    private fun currentVenueOrderBatch(
        detail: com.hookah.platform.backend.miniapp.venue.orders.OrderDetail,
    ): com.hookah.platform.backend.miniapp.venue.orders.OrderBatchDetail? =
        detail.batches
            .asSequence()
            .filter { it.status != OrderWorkflowStatus.CLOSED }
            .maxWithOrNull(compareBy({ it.createdAt }, { it.batchId }))

    private fun firstVenueOrderBatch(
        detail: com.hookah.platform.backend.miniapp.venue.orders.OrderDetail,
    ): com.hookah.platform.backend.miniapp.venue.orders.OrderBatchDetail? =
        detail.batches
            .asSequence()
            .filter { it.status != OrderWorkflowStatus.CLOSED }
            .minWithOrNull(compareBy({ it.createdAt }, { it.batchId }))

    private fun isCurrentBatchFirstInOrder(
        detail: com.hookah.platform.backend.miniapp.venue.orders.OrderDetail,
        currentBatch: com.hookah.platform.backend.miniapp.venue.orders.OrderBatchDetail?,
    ): Boolean {
        if (currentBatch == null) {
            return true
        }
        val firstBatch = firstVenueOrderBatch(detail) ?: return true
        return firstBatch.batchId == currentBatch.batchId
    }

    private fun venueOrderBatchLabel(
        detail: com.hookah.platform.backend.miniapp.venue.orders.OrderDetail,
        batch: OrderBatchDetail,
    ): String {
        val batchIndex =
            detail.batches
                .sortedWith(compareBy({ it.createdAt }, { it.batchId }))
                .indexOfFirst { it.batchId == batch.batchId }
        return if (batchIndex <= 0) {
            "Основной заказ"
        } else {
            "Дозаказ $batchIndex"
        }
    }

    private fun guestOrderStatusNotificationMessage(
        nextStatus: OrderWorkflowStatus,
        isReorder: Boolean,
    ): String? =
        when (nextStatus) {
            OrderWorkflowStatus.ACCEPTED ->
                if (isReorder) {
                    "✅ Ваш дозаказ принят."
                } else {
                    "✅ Ваш заказ принят и передан в работу."
                }
            OrderWorkflowStatus.DELIVERED ->
                if (isReorder) {
                    "✅ Ваш дозаказ доставлен."
                } else {
                    "✅ Ваш заказ доставлен."
                }
            else -> null
        }

    private fun venueOrderStatusActionLabel(status: OrderWorkflowStatus): String =
        when (status) {
            OrderWorkflowStatus.ACCEPTED -> "✅ Принять"
            OrderWorkflowStatus.COOKING -> "✅ Доставлен"
            OrderWorkflowStatus.DELIVERING -> "✅ Доставлен"
            OrderWorkflowStatus.DELIVERED -> "✅ Доставлен"
            OrderWorkflowStatus.CLOSED -> "🔒 Закрыть"
            OrderWorkflowStatus.NEW -> "Новый"
        }

    private fun humanizeVenueOrderWorkflowStatus(status: OrderWorkflowStatus): String =
        when (status) {
            OrderWorkflowStatus.NEW -> "Новый"
            OrderWorkflowStatus.ACCEPTED -> "Принят"
            OrderWorkflowStatus.COOKING -> "Принят"
            OrderWorkflowStatus.DELIVERING -> "Принят"
            OrderWorkflowStatus.DELIVERED -> "Доставлен"
            OrderWorkflowStatus.CLOSED -> "Закрыт"
        }

    private fun humanizeOrderCancelReason(reasonCode: String?): String =
        when (reasonCode?.trim()?.uppercase(Locale.ROOT)) {
            "VENUE_BATCH_CANCELLED" -> "Отменено персоналом"
            "VENUE_ORDER_CANCELLED" -> "Заказ отменён персоналом"
            else -> "Не указана"
        }

    private fun buildVenueStaffBookingsText(
        bookings: List<com.hookah.platform.backend.miniapp.guest.db.BookingRecord>,
    ): String =
        buildString {
            append("📄 Брони")
            append("\n\n")
            bookings.forEach { booking ->
                val scheduledAt =
                    LocalDateTime
                        .ofInstant(booking.scheduledAt, ZoneId.systemDefault())
                        .format(bookingDateTimeFormatter)
                append("• Бронь #${booking.id}")
                append("\n  Дата и время: $scheduledAt")
                append("\n  Гостей: ${booking.partySize ?: "—"}")
                append("\n  Статус: ${humanizeBookingStatus(booking.status)}")
                booking.comment?.takeIf { it.isNotBlank() }?.let { comment ->
                    append("\n  Комментарий: $comment")
                }
                append("\n")
            }
        }.trimEnd()

    private suspend fun resolveVenueRoleForVenue(
        chatId: Long,
        userId: Long,
        venueId: Long,
    ): VenueBotRole? {
        val membership =
            runCatching { venueAccessRepository.findVenueMembership(userId, venueId) }
                .onFailure { logBestEffort("load venue membership for role-aware bot flow", it) }
                .getOrNull()
        val role =
            when (membership?.role?.uppercase(Locale.ROOT)) {
                "OWNER" -> VenueBotRole.OWNER
                "ADMIN", "MANAGER" -> VenueBotRole.MANAGER
                "STAFF" -> VenueBotRole.STAFF
                else -> null
            }
        if (role == null) {
            enqueueMessage(chatId, "Нет доступа к заведению.")
            return null
        }
        return role
    }

    private fun parseStaffVenueOrderData(
        data: String,
        prefix: String,
    ): Pair<Long, Long>? {
        val payload = data.removePrefix(prefix)
        val parts = payload.split(":", limit = 2)
        val venueId = parts.getOrNull(0)?.toLongOrNull() ?: return null
        val orderId = parts.getOrNull(1)?.toLongOrNull() ?: return null
        return venueId to orderId
    }

    private fun parseStaffVenueOrderItemData(
        data: String,
        prefix: String,
    ): Triple<Long, Long, Long>? {
        val payload = data.removePrefix(prefix)
        val parts = payload.split(":", limit = 3)
        val venueId = parts.getOrNull(0)?.toLongOrNull() ?: return null
        val orderId = parts.getOrNull(1)?.toLongOrNull() ?: return null
        val batchItemId = parts.getOrNull(2)?.toLongOrNull() ?: return null
        return Triple(venueId, orderId, batchItemId)
    }

    private fun parseStaffVenueOrderStatusData(data: String): Triple<Long, Long, OrderWorkflowStatus>? {
        val payload = data.removePrefix("staff_venue_orders_status:")
        val parts = payload.split(":", limit = 3)
        val venueId = parts.getOrNull(0)?.toLongOrNull() ?: return null
        val orderId = parts.getOrNull(1)?.toLongOrNull() ?: return null
        val status = OrderWorkflowStatus.fromApi(parts.getOrNull(2)) ?: return null
        return Triple(venueId, orderId, status)
    }

    private suspend fun showVenueManagerVenueCard(
        chatId: Long,
        from: User?,
    ) {
        showVenueOwnerVenueCardByUserId(chatId, from?.id)
    }

    private suspend fun showVenueManagerOrderMenuRoot(
        chatId: Long,
        from: User?,
    ) {
        val userId = from?.id
        if (userId == null) {
            enqueueMessage(chatId, "Не удалось определить пользователя. Попробуйте /start.")
            return
        }
        when (val access = resolvePrimaryVenueBotAccess(userId)) {
            null -> showMainMenu(chatId, from)
            else ->
                when (access.role) {
                    VenueBotRole.OWNER, VenueBotRole.MANAGER ->
                        showVenueOwnerOrderMenuRootByVenueId(chatId, userId, access.venueId)
                    VenueBotRole.STAFF ->
                        enqueueMessage(
                            chatId,
                            "Раздел «Меню» доступен менеджеру.",
                            TelegramKeyboards.venueStaffMenu(),
                        )
                }
        }
    }

    private suspend fun showVenueManagerStopListRoot(
        chatId: Long,
        from: User?,
    ) {
        val userId = from?.id
        if (userId == null) {
            enqueueMessage(chatId, "Не удалось определить пользователя. Попробуйте /start.")
            return
        }
        when (val access = resolvePrimaryVenueBotAccess(userId)) {
            null -> showMainMenu(chatId, from)
            else ->
                when (access.role) {
                    VenueBotRole.OWNER, VenueBotRole.MANAGER ->
                        showVenueOwnerOrderMenuStopListByVenueId(chatId, userId, access.venueId)
                    VenueBotRole.STAFF ->
                        enqueueMessage(
                            chatId,
                            "Раздел «Стоп-лист» доступен менеджеру.",
                            TelegramKeyboards.venueStaffMenu(),
                        )
                }
        }
    }

    private suspend fun showVenueManagerTablesRoot(
        chatId: Long,
        from: User?,
    ) {
        val userId = from?.id
        if (userId == null) {
            enqueueMessage(chatId, "Не удалось определить пользователя. Попробуйте /start.")
            return
        }
        when (val access = resolvePrimaryVenueBotAccess(userId)) {
            null -> showMainMenu(chatId, from)
            else ->
                when (access.role) {
                    VenueBotRole.OWNER, VenueBotRole.MANAGER ->
                        showVenueOwnerTablesRootByVenueId(chatId, userId, access.venueId)
                    VenueBotRole.STAFF ->
                        enqueueMessage(
                            chatId,
                            "Раздел «Столы и QR» доступен менеджеру.",
                            TelegramKeyboards.venueStaffMenu(),
                        )
                }
        }
    }

    private fun buildOwnerVenueStaffRootText(
        members: List<VenueAccessRepository.VenueMemberSummary>,
    ): String =
        buildString {
            append("👥 Персонал")
            append("\n\n")
            if (members.isEmpty()) {
                append("Сотрудники пока не добавлены.")
            } else {
                append("Сотрудники:")
                members.forEach { member ->
                    append("\n• ")
                    append(formatOwnerVenueStaffMember(member))
                    append(" — ")
                    append(humanizeOwnerVenueStaffRole(member.role))
                }
            }
        }

    private fun formatOwnerVenueStaffMember(
        member: VenueAccessRepository.VenueMemberSummary,
    ): String {
        val username = member.username?.trim().orEmpty()
        if (username.isNotBlank()) {
            return "@$username"
        }
        val firstName = member.firstName?.trim().orEmpty()
        val lastName = member.lastName?.trim().orEmpty()
        val fullName = listOf(firstName, lastName).filter { it.isNotBlank() }.joinToString(" ")
        if (fullName.isNotBlank()) {
            return fullName
        }
        return "ID ${member.userId}"
    }

    private fun humanizeOwnerVenueStaffRole(role: String): String =
        when (role.uppercase(Locale.ROOT)) {
            "OWNER" -> "Owner"
            "ADMIN", "MANAGER" -> "Manager"
            "STAFF" -> "Staff / Оператор смены"
            else -> role
        }

    private fun humanizeOwnerVenueStaffRoleForChoice(roleKey: String): String =
        when (roleKey.lowercase(Locale.ROOT)) {
            "owner" -> "Owner"
            "manager" -> "Manager"
            "staff" -> "Staff / Оператор смены"
            else -> roleKey
        }

    private fun buildOwnerVenueStaffRoleInfoText(roleKey: String): String? =
        when (roleKey.lowercase(Locale.ROOT)) {
            "owner" ->
                "ℹ️ Роль Owner\n\n" +
                    "Может:\n" +
                    "• Полностью управлять заведением: меню, цены, стоп-лист, заказы, брони, часы, описание, столы и QR, персонал.\n\n" +
                    "Не может:\n" +
                    "• Изменять коммерческие условия платформы и подписку."
            "manager" ->
                "ℹ️ Роль Manager\n\n" +
                    "Может:\n" +
                    "• Управлять операционной работой заведения: меню, цены, стоп-лист, заказы, брони, часы, описание, столы и QR.\n\n" +
                    "Не может:\n" +
                    "• Управлять владением заведением и коммерческими условиями платформы."
            "staff" ->
                "ℹ️ Роль Staff / Оператор смены\n\n" +
                    "Может:\n" +
                    "• Выполнять операции смены: работа с заказами и статусами, стоп-лист, текущий поток гостей.\n\n" +
                    "Не может:\n" +
                    "• Изменять цены, удалять меню и разделы, менять критичные настройки и управлять владельцами.\n\n" +
                    "Рекомендуется использовать отдельный нейтральный аккаунт заведения, который постоянно находится в кальянной, а не личный аккаунт сотрудника."
            else -> null
        }

    private fun buildOwnerStaffInviteCreatedText(
        venueName: String,
        roleLabel: String,
        inviteCode: String,
        inviteUrl: String?,
        isStaffRole: Boolean,
    ): String =
        buildString {
            append("✅ Приглашение для роли $roleLabel создано.")
            append("\nКальянная: $venueName")
            append("\n\nОтправьте ссылку сотруднику.")
            if (inviteUrl != null) {
                append("\n$inviteUrl")
            } else {
                append("\nСсылка недоступна: не задан TELEGRAM_BOT_USERNAME.")
                append("\nКод приглашения: $inviteCode")
            }
            if (isStaffRole) {
                append("\n\nРекомендуется использовать отдельный нейтральный аккаунт заведения, а не личный аккаунт сотрудника.")
            }
        }

    private fun buildStaffInviteAcceptedText(alreadyMember: Boolean): String =
        if (alreadyMember) {
            "✅ Приглашение принято.\nУ вас уже есть доступ к заведению."
        } else {
            "✅ Приглашение принято.\nТеперь у вас есть доступ к заведению."
        }

    private suspend fun loadVenueNameForStaffInvite(venueId: Long): String {
        val fromPlatform =
            runCatching { platformVenueRepository.getVenueDetail(venueId)?.name }
                .onFailure { logBestEffort("load venue detail for staff invite", it) }
                .getOrNull()
                ?.trim()
                ?.takeIf { it.isNotBlank() }
        if (fromPlatform != null) return fromPlatform
        val fromVenueRepo =
            runCatching { venueRepository.findVenueById(venueId)?.name }
                .onFailure { logBestEffort("load venue name for staff invite", it) }
                .getOrNull()
                ?.trim()
                ?.takeIf { it.isNotBlank() }
        return fromVenueRepo ?: "Заведение #$venueId"
    }

    private fun buildApplicantVenueConnectionRequestText(request: VenueConnectionRequestRecord): String =
        buildString {
            append("У вас уже есть активная заявка.")
            append("\n\nНазвание: ${request.venueName}")
            append("\nГород: ${request.city}")
            append("\nКонтакт: ${request.contact}")
            append("\nКомментарий: ${request.comment?.takeIf { it.isNotBlank() } ?: "—"}")
            append("\nСтатус: ${humanizeVenueConnectionRequestStatus(request.status)}")
        }

    private fun humanizeVenueConnectionRequestStatus(status: String): String =
        when (status.uppercase(Locale.ROOT)) {
            VenueConnectionRequestRepository.STATUS_PENDING -> "На рассмотрении"
            VenueConnectionRequestRepository.STATUS_APPROVED -> "Принята"
            VenueConnectionRequestRepository.STATUS_REJECTED -> "Отклонена"
            VenueConnectionRequestRepository.STATUS_CANCELLED -> "Отменена"
            else -> status
        }

    private suspend fun showHelp(chatId: Long) {
        enqueueMessage(
            chatId,
            "Доступные команды:\n" +
                "/start — начать заново\n" +
                "/menu — главное меню\n" +
                "/my — мои заказы и брони\n" +
                "/help — помощь",
        )
    }

    private suspend fun loadCatalogVenueFromCallback(
        chatId: Long,
        data: String,
        prefix: String,
    ): CatalogVenueShort? {
        val venueId = data.removePrefix(prefix).toLongOrNull()
        if (venueId == null) {
            enqueueMessage(chatId, "Не удалось открыть заведение. Попробуйте ещё раз.")
            return null
        }
        val venue = loadCatalogVenueById(chatId, venueId)
        if (venue == null) return null
        return venue
    }

    private suspend fun loadCatalogVenueById(
        chatId: Long,
        venueId: Long,
    ): CatalogVenueShort? {
        val guestVisibleVenue =
            try {
                venueRepository.findCatalogVenueByIdForGuest(venueId)
            } catch (e: DatabaseUnavailableException) {
                enqueueMessage(chatId, "База недоступна, попробуйте позже.")
                return null
            }
        if (guestVisibleVenue != null) {
            return guestVisibleVenue
        }
        val previewContext = botVenuePreviewContexts[chatId]
        if (previewContext?.venueId == venueId) {
            val hasAccess =
                runCatching { venueAccessRepository.hasVenueAdminOrOwner(previewContext.ownerUserId, venueId) }
                    .onFailure { logBestEffort("check venue owner access in preview", it) }
                    .getOrDefault(false)
            if (!hasAccess) {
                botVenuePreviewContexts.remove(chatId)
                enqueueMessage(chatId, "Нет доступа к заведению.")
                return null
            }
            val venueDetail =
                try {
                    platformVenueRepository.getVenueDetail(venueId)
                } catch (e: DatabaseUnavailableException) {
                    enqueueMessage(chatId, "База недоступна, попробуйте позже.")
                    return null
                }
            if (venueDetail != null) {
                return CatalogVenueShort(
                    id = venueDetail.id,
                    name = venueDetail.name,
                    city = venueDetail.city,
                    address = venueDetail.address,
                )
            }
        }
        enqueueMessage(chatId, "Заведение не найдено.")
        return null
    }

    private fun parseBotVenueBookingDateData(data: String): Pair<Long, LocalDate>? {
        if (!data.startsWith("bot_catalog_venue_book_date:")) return null
        val payload = data.removePrefix("bot_catalog_venue_book_date:")
        val parts = payload.split(":", limit = 2)
        val venueId = parts.getOrNull(0)?.toLongOrNull() ?: return null
        val date = parts.getOrNull(1)?.let { runCatching { LocalDate.parse(it) }.getOrNull() } ?: return null
        return venueId to date
    }

    private fun parseBotVenueMenuSectionData(data: String): Pair<Long, Long>? {
        if (!data.startsWith("bot_catalog_venue_menu_section:")) return null
        val payload = data.removePrefix("bot_catalog_venue_menu_section:")
        val parts = payload.split(":", limit = 2)
        val venueId = parts.getOrNull(0)?.toLongOrNull() ?: return null
        val categoryId = parts.getOrNull(1)?.toLongOrNull() ?: return null
        return venueId to categoryId
    }

    private fun parseBotVenueAboutSectionData(data: String): Pair<Long, Long>? {
        if (!data.startsWith("bot_catalog_venue_about_section:")) return null
        val payload = data.removePrefix("bot_catalog_venue_about_section:")
        val parts = payload.split(":", limit = 2)
        val venueId = parts.getOrNull(0)?.toLongOrNull() ?: return null
        val sectionId = parts.getOrNull(1)?.toLongOrNull() ?: return null
        return venueId to sectionId
    }

    private fun parseBotVenueBookingMoreData(data: String): Pair<Long, Int>? {
        if (!data.startsWith("bot_catalog_venue_book_more:")) return null
        val payload = data.removePrefix("bot_catalog_venue_book_more:")
        val parts = payload.split(":", limit = 2)
        val venueId = parts.getOrNull(0)?.toLongOrNull() ?: return null
        val offset =
            parts.getOrNull(1)?.toIntOrNull()
                ?: bookingDateFirstPageSize
        return venueId to offset.coerceAtLeast(0)
    }

    private fun parseBotVenueBookingTimeData(data: String): Triple<Long, LocalDate, String>? {
        if (!data.startsWith("bot_catalog_venue_book_time:")) return null
        val payload = data.removePrefix("bot_catalog_venue_book_time:")
        val parts = payload.split(":", limit = 3)
        val venueId = parts.getOrNull(0)?.toLongOrNull() ?: return null
        val date = parts.getOrNull(1)?.let { runCatching { LocalDate.parse(it) }.getOrNull() } ?: return null
        val time = parts.getOrNull(2)?.trim().orEmpty()
        if (time.isBlank()) return null
        return Triple(venueId, date, time)
    }

    private fun parseBotVenueBookingGuestsData(data: String): BookingGuestSelection? {
        if (!data.startsWith("bot_catalog_venue_book_guests:")) return null
        val payload = data.removePrefix("bot_catalog_venue_book_guests:")
        val parts = payload.split(":", limit = 4)
        val venueId = parts.getOrNull(0)?.toLongOrNull() ?: return null
        val date = parts.getOrNull(1)?.let { runCatching { LocalDate.parse(it) }.getOrNull() } ?: return null
        val encodedTime = parts.getOrNull(2)?.trim().orEmpty()
        if (encodedTime.isBlank()) return null
        val time = encodedTime.replace('_', ':')
        val guestsToken = parts.getOrNull(3)?.trim().orEmpty()
        val guestsLabel = parseBookingGuestsLabel(guestsToken) ?: return null
        return BookingGuestSelection(
            venueId = venueId,
            date = date,
            time = time,
            guestsLabel = guestsLabel,
        )
    }

    private fun parseBotVenueBookingCommentData(
        data: String,
        prefix: String,
    ): BotBookingDraft? {
        if (!data.startsWith(prefix)) return null
        val payload = data.removePrefix(prefix)
        val parts = payload.split(":", limit = 4)
        val venueId = parts.getOrNull(0)?.toLongOrNull() ?: return null
        val date = parts.getOrNull(1)?.let { runCatching { LocalDate.parse(it) }.getOrNull() } ?: return null
        val encodedTime = parts.getOrNull(2)?.trim().orEmpty()
        if (encodedTime.isBlank()) return null
        val time = encodedTime.replace('_', ':')
        val guestsToken = parts.getOrNull(3)?.trim().orEmpty()
        val guestsLabel = parseBookingGuestsLabel(guestsToken) ?: return null
        return BotBookingDraft(
            userId = 0L,
            venueId = venueId,
            date = date,
            time = time,
            guestsLabel = guestsLabel,
        )
    }

    private fun parseBotMyBookingActionData(
        data: String,
        prefix: String,
    ): Pair<Long, Long>? {
        if (!data.startsWith(prefix)) return null
        val payload = data.removePrefix(prefix)
        val parts = payload.split(":", limit = 2)
        val bookingId = parts.getOrNull(0)?.toLongOrNull() ?: return null
        val venueId = parts.getOrNull(1)?.toLongOrNull() ?: return null
        return bookingId to venueId
    }

    private fun parseBookingGuestsLabel(token: String): String? =
        when (token) {
            "1", "2", "3", "4", "5" -> token
            "6plus" -> "6+"
            else -> null
        }

    private fun guestsLabelToToken(label: String): String =
        when (label) {
            "6+" -> "6plus"
            else -> label
        }

    private fun guestsLabelToPartySize(label: String): Int =
        when (label) {
            "6+" -> 6
            else -> label.toIntOrNull() ?: 1
        }

    private fun normalizeBotBookingComment(comment: String?): String? {
        val trimmed = comment?.trim().orEmpty()
        if (trimmed.isEmpty()) return null
        if (trimmed.length > 500) return null
        return trimmed
    }

    private data class BookingGuestSelection(
        val venueId: Long,
        val date: LocalDate,
        val time: String,
        val guestsLabel: String,
    )

    private fun buildBookingDateButtonLabel(
        date: LocalDate,
        today: LocalDate,
    ): String {
        val offset = java.time.temporal.ChronoUnit.DAYS.between(today, date).toInt()
        val weekday = formatWeekday(date.dayOfWeek)
        val datePart = date.format(bookingDateFormatter)
        return when (offset) {
            0 -> "Сегодня ($weekday, $datePart)"
            1 -> "Завтра ($weekday, $datePart)"
            2 -> "Послезавтра ($weekday, $datePart)"
            else -> "$weekday, $datePart"
        }
    }

    private fun formatWeekday(dayOfWeek: DayOfWeek): String =
        when (dayOfWeek) {
            DayOfWeek.MONDAY -> "пн"
            DayOfWeek.TUESDAY -> "вт"
            DayOfWeek.WEDNESDAY -> "ср"
            DayOfWeek.THURSDAY -> "чт"
            DayOfWeek.FRIDAY -> "пт"
            DayOfWeek.SATURDAY -> "сб"
            DayOfWeek.SUNDAY -> "вс"
        }

    private fun isBookingDateInAllowedRange(date: LocalDate): Boolean {
        val today = currentBookingDateBase()
        val maxDate = today.plusDays(bookingDateMaxDaysAhead.toLong())
        return !date.isBefore(today) && !date.isAfter(maxDate)
    }

    private fun isVenueConnectionDialogState(state: DialogStateType): Boolean =
        state == DialogStateType.VENUE_CONNECT_WAIT_NAME ||
            state == DialogStateType.VENUE_CONNECT_WAIT_CITY ||
            state == DialogStateType.VENUE_CONNECT_WAIT_CONTACT_CHOICE ||
            state == DialogStateType.VENUE_CONNECT_WAIT_CONTACT ||
            state == DialogStateType.VENUE_CONNECT_WAIT_CONTACT_EXTRA ||
            state == DialogStateType.VENUE_CONNECT_WAIT_COMMENT

    private fun isOwnerVenueTermsDialogState(state: DialogStateType): Boolean =
        state == DialogStateType.OWNER_VENUE_TERMS_WAIT_TRIAL_CUSTOM_DATE ||
            state == DialogStateType.OWNER_VENUE_TERMS_WAIT_CURRENT_PRICE ||
            state == DialogStateType.OWNER_VENUE_TERMS_WAIT_FUTURE_PRICE ||
            state == DialogStateType.OWNER_VENUE_TERMS_WAIT_FUTURE_PRICE_DATE ||
            state == DialogStateType.OWNER_VENUE_TERMS_WAIT_NOTE

    private fun isOwnerVenueProfileDialogState(state: DialogStateType): Boolean =
        state == DialogStateType.OWNER_VENUE_PROFILE_WAIT_NAME ||
            state == DialogStateType.OWNER_VENUE_PROFILE_WAIT_ADDRESS ||
            state == DialogStateType.OWNER_VENUE_PROFILE_WAIT_HOURS_OPEN ||
            state == DialogStateType.OWNER_VENUE_PROFILE_WAIT_HOURS_CLOSE ||
            state == DialogStateType.OWNER_VENUE_DESCRIPTION_WAIT_SECTION_TITLE ||
            state == DialogStateType.OWNER_VENUE_DESCRIPTION_WAIT_SECTION_TEXT ||
            state == DialogStateType.OWNER_VENUE_DESCRIPTION_WAIT_SECTION_MEDIA ||
            state == DialogStateType.OWNER_VENUE_ORDER_MENU_WAIT_SECTION_TITLE ||
            state == DialogStateType.OWNER_VENUE_ORDER_MENU_WAIT_SECTION_RENAME ||
            state == DialogStateType.OWNER_VENUE_ORDER_MENU_WAIT_ITEM_NAME ||
            state == DialogStateType.OWNER_VENUE_ORDER_MENU_WAIT_ITEM_PRICE ||
            state == DialogStateType.OWNER_VENUE_ORDER_MENU_WAIT_ITEM_RENAME ||
            state == DialogStateType.OWNER_VENUE_ORDER_MENU_WAIT_ITEM_PRICE_EDIT ||
            state == DialogStateType.OWNER_VENUE_ORDER_MENU_WAIT_OPTION_NAME ||
            state == DialogStateType.OWNER_VENUE_TABLES_WAIT_NUMBER ||
            state == DialogStateType.OWNER_VENUE_TABLES_WAIT_CAPACITY

    private fun isVenueConnectionInterruptAction(text: String): Boolean =
        text in
            setOf(
                "🍽 Каталог кальянных",
                "🍽️ Каталог кальянных",
                "🗺️ Каталог кальянных",
                "📱 Открыть Mini App",
                "🎁 Акции",
                "🪑 Я за столом / У меня QR",
                "📄 Мои заказы и брони",
                "🤝 Добавить свою кальянную",
                "🍽️ Меню",
                "🍽️ Открыть меню",
                "🧺 Корзина",
                "👥 Общий счёт",
                "📄 Мой заказ",
                "📄 Заказ",
                "🧾 Активный заказ",
                "✍️ Быстрый заказ",
                "🔔 Персонал",
                "🔔 Вызвать персонал",
                "🛎️ Вызов персонала",
                "🛎 Вызвать персонал",
                "🚪 Сменить стол",
                "🔁 Сменить стол",
                "🏠 В каталог",
                "📨 Заявки на подключение",
                "🏢 Кальянные",
                "👤 Владельцы",
                "💳 Подписки",
                "⚙️ Статусы",
            )

    private fun isOwnerVenueTermsInterruptAction(text: String): Boolean =
        text in
            setOf(
                "📨 Заявки на подключение",
                "🏢 Кальянные",
                "👤 Владельцы",
                "💳 Подписки",
                "⚙️ Статусы",
                "🍽 Каталог кальянных",
                "🍽️ Каталог кальянных",
                "🗺️ Каталог кальянных",
                "📱 Открыть Mini App",
                "🎁 Акции",
                "🪑 Я за столом / У меня QR",
                "📄 Мои заказы и брони",
                "🤝 Добавить свою кальянную",
            )

    private fun isOwnerVenueProfileInterruptAction(text: String): Boolean =
        text in
            setOf(
                "🏢 Моё заведение",
                "🍽 Меню заведения",
                "🪑 Столы и QR",
                "👥 Персонал",
                "📦 Заказы",
                "⚙️ Настройки",
                "📨 Заявки на подключение",
                "🏢 Кальянные",
                "👤 Владельцы",
                "💳 Подписки",
                "⚙️ Статусы",
                "🍽 Каталог кальянных",
                "🍽️ Каталог кальянных",
                "🗺️ Каталог кальянных",
                "📱 Открыть Mini App",
                "🎁 Акции",
                "🪑 Я за столом / У меня QR",
                "📄 Мои заказы и брони",
                "🤝 Добавить свою кальянную",
            )

    private fun normalizeVenueConnectionRequiredField(
        text: String,
        maxLength: Int,
    ): String? {
        val trimmed = text.trim()
        if (trimmed.isBlank()) return null
        if (trimmed.length > maxLength) return null
        return trimmed
    }

    private fun parseVenueConnectionOptionalComment(text: String): Pair<String?, Boolean> {
        val trimmed = text.trim()
        if (trimmed.isBlank() || trimmed == "-" || trimmed == "—") return null to true
        if (trimmed.length > 500) return null to false
        return trimmed to true
    }

    private fun parseIsoLocalDate(text: String): LocalDate? {
        val value = text.trim()
        return runCatching { LocalDate.parse(value, bookingDateConfirmFormatter) }.getOrNull()
            ?: runCatching { LocalDate.parse(value) }.getOrNull()
    }

    private fun parseOwnerVenueHoursTime(text: String): LocalTime? {
        val value = text.trim()
        return runCatching { LocalTime.parse(value, bookingTimeFormatter) }.getOrNull()
    }

    private fun parseOwnerVenueDateInput(text: String): LocalDate? {
        val value = text.trim()
        return runCatching { LocalDate.parse(value, bookingDateConfirmFormatter) }.getOrNull()
    }

    private fun parseOwnerVenueHoursWeekdayData(
        data: String,
        prefix: String,
    ): Pair<Long, Int>? {
        val payload = data.removePrefix(prefix)
        val parts = payload.split(":", limit = 2)
        val venueId = parts.getOrNull(0)?.toLongOrNull() ?: return null
        val weekday = parts.getOrNull(1)?.toIntOrNull() ?: return null
        return venueId to weekday
    }

    private fun parseOwnerVenueHoursOverrideData(
        data: String,
        prefix: String,
    ): Pair<Long, LocalDate>? {
        val payload = data.removePrefix(prefix)
        val parts = payload.split(":", limit = 2)
        val venueId = parts.getOrNull(0)?.toLongOrNull() ?: return null
        val serviceDate = parts.getOrNull(1)?.let { runCatching { LocalDate.parse(it) }.getOrNull() } ?: return null
        return venueId to serviceDate
    }

    private fun parseOwnerVenueHoursOverrideModeData(data: String): Triple<Long, LocalDate, String>? {
        val payload = data.removePrefix("owner_venue_hours_override_mode:")
        val parts = payload.split(":", limit = 3)
        val venueId = parts.getOrNull(0)?.toLongOrNull() ?: return null
        val serviceDate = parts.getOrNull(1)?.let { runCatching { LocalDate.parse(it) }.getOrNull() } ?: return null
        val mode = parts.getOrNull(2)?.lowercase(Locale.ROOT) ?: return null
        return Triple(venueId, serviceDate, mode)
    }

    private fun parseOwnerVenueDescriptionSectionData(
        data: String,
        prefix: String,
    ): Pair<Long, Long>? {
        val payload = data.removePrefix(prefix)
        val parts = payload.split(":", limit = 2)
        val venueId = parts.getOrNull(0)?.toLongOrNull() ?: return null
        val sectionId = parts.getOrNull(1)?.toLongOrNull() ?: return null
        return venueId to sectionId
    }

    private fun parseOwnerVenueMenuSectionData(
        data: String,
        prefix: String,
    ): Pair<Long, Long>? {
        val payload = data.removePrefix(prefix)
        val parts = payload.split(":", limit = 2)
        val venueId = parts.getOrNull(0)?.toLongOrNull() ?: return null
        val sectionId = parts.getOrNull(1)?.toLongOrNull() ?: return null
        return venueId to sectionId
    }

    private fun parseOwnerVenueMenuItemData(
        data: String,
        prefix: String,
    ): Triple<Long, Long, Long>? {
        val payload = data.removePrefix(prefix)
        val parts = payload.split(":", limit = 3)
        val venueId = parts.getOrNull(0)?.toLongOrNull() ?: return null
        val sectionId = parts.getOrNull(1)?.toLongOrNull() ?: return null
        val itemId = parts.getOrNull(2)?.toLongOrNull() ?: return null
        return Triple(venueId, sectionId, itemId)
    }

    private fun parseOwnerVenueMenuOptionData(
        data: String,
        prefix: String,
    ): List<Long>? {
        val payload = data.removePrefix(prefix)
        val parts = payload.split(":", limit = 4)
        val venueId = parts.getOrNull(0)?.toLongOrNull() ?: return null
        val sectionId = parts.getOrNull(1)?.toLongOrNull() ?: return null
        val itemId = parts.getOrNull(2)?.toLongOrNull() ?: return null
        val optionId = parts.getOrNull(3)?.toLongOrNull() ?: return null
        return listOf(venueId, sectionId, itemId, optionId)
    }

    private fun parseOwnerVenueTableData(
        data: String,
        prefix: String,
    ): Pair<Long, Long>? {
        val payload = data.removePrefix(prefix)
        val parts = payload.split(":", limit = 2)
        val venueId = parts.getOrNull(0)?.toLongOrNull() ?: return null
        val tableId = parts.getOrNull(1)?.toLongOrNull() ?: return null
        return venueId to tableId
    }

    private fun parseOwnerVenueStaffRoleData(
        data: String,
        prefix: String,
    ): Pair<Long, String>? {
        val payload = data.removePrefix(prefix)
        val parts = payload.split(":", limit = 2)
        val venueId = parts.getOrNull(0)?.toLongOrNull() ?: return null
        val roleKey = parts.getOrNull(1)?.trim()?.lowercase(Locale.ROOT) ?: return null
        if (roleKey !in setOf("owner", "manager", "staff")) return null
        return venueId to roleKey
    }

    private fun parseOwnerVenueStaffMemberData(
        data: String,
        prefix: String,
    ): Pair<Long, Long>? {
        val payload = data.removePrefix(prefix)
        val parts = payload.split(":", limit = 2)
        val venueId = parts.getOrNull(0)?.toLongOrNull() ?: return null
        val targetUserId = parts.getOrNull(1)?.toLongOrNull() ?: return null
        return venueId to targetUserId
    }

    private fun parseStaffInviteStartCode(token: String): String? {
        if (!token.startsWith(staffInviteStartPrefix)) return null
        val code = token.removePrefix(staffInviteStartPrefix).trim()
        if (code.isBlank()) return null
        return code
    }

    private fun parseStaffInviteCodeFromCallback(
        data: String,
        prefix: String,
    ): String? {
        if (!data.startsWith(prefix)) return null
        val code = data.removePrefix(prefix).trim()
        if (code.isBlank()) return null
        return code
    }

    private fun parseOwnerVenueDescriptionMediaDeleteData(data: String): Triple<Long, Long, Long>? {
        if (!data.startsWith("owner_venue_description_media_delete:")) return null
        val payload = data.removePrefix("owner_venue_description_media_delete:")
        val parts = payload.split(":", limit = 3)
        val venueId = parts.getOrNull(0)?.toLongOrNull() ?: return null
        val sectionId = parts.getOrNull(1)?.toLongOrNull() ?: return null
        val mediaId = parts.getOrNull(2)?.toLongOrNull() ?: return null
        return Triple(venueId, sectionId, mediaId)
    }

    private fun formatOwnerVenueDescriptionSectionStatus(
        section: VenueInfoSection,
        mediaCount: Int,
    ): String =
        when {
            !section.isVisible -> "скрыт"
            !section.textContent.isNullOrBlank() || mediaCount > 0 -> "заполнен"
            else -> "пусто"
        }

    private fun buildOwnerVenueSectionMediaLabels(
        attachments: List<com.hookah.platform.backend.telegram.db.VenueInfoSectionMediaAttachment>,
    ): List<Pair<Long, String>> {
        var imageIndex = 0
        var pdfIndex = 0
        var fileIndex = 0
        return attachments.map { attachment ->
            val label =
                when (attachment.mediaType.lowercase(Locale.ROOT)) {
                    "image" -> {
                        imageIndex += 1
                        "Изображение $imageIndex"
                    }
                    "pdf" -> {
                        pdfIndex += 1
                        "PDF $pdfIndex"
                    }
                    else -> {
                        fileIndex += 1
                        "Файл $fileIndex"
                    }
                }
            attachment.id to label
        }
    }

    private fun humanizeOwnerVenueSectionType(sectionType: String): String =
        when (sectionType.lowercase(Locale.ROOT)) {
            "about" -> "О заведении"
            "rules" -> "Правила посещения"
            "cork_fee" -> "Пробковый сбор"
            "faq" -> "FAQ"
            "menu" -> "Меню"
            "custom" -> "Пользовательский"
            else -> sectionType
        }

    private fun formatOwnerVenueHoursStatus(
        opensAt: LocalTime?,
        closesAt: LocalTime?,
    ): String {
        if (opensAt == null || closesAt == null) return "Не настроено"
        if (opensAt == closesAt) return "Закрыто"
        return "${opensAt.format(bookingTimeFormatter)}–${closesAt.format(bookingTimeFormatter)}"
    }

    private fun parsePositiveRubAmount(text: String): Long? {
        val normalized = text.trim().replace(" ", "").replace("₽", "").replace(",", ".")
        if (normalized.contains('.')) {
            return null
        }
        val value = normalized.toLongOrNull() ?: return null
        if (value <= 0) return null
        return value
    }

    private fun parseOwnerCommercialOptionalNote(text: String): String? {
        val trimmed = text.trim()
        if (trimmed.isBlank() || trimmed == "-" || trimmed == "—") return null
        if (trimmed.length > 1000) return null
        return trimmed
    }

    private fun isCommercialTermsReady(request: VenueConnectionRequestRecord): Boolean {
        if (!request.trialConfigured) return false
        if (request.currentPriceRub == null) return false
        val hasFuturePrice = request.futurePriceRub != null
        val hasFutureDate = request.futurePriceEffectiveOn != null
        return hasFuturePrice == hasFutureDate
    }

    private fun buildOwnerCommercialTermsPromptText(request: VenueConnectionRequestRecord): String =
        buildString {
            append("Коммерческие условия")
            append("\nЗаявка: ${request.venueName}")
            append("\nКонтакт: ${request.contact}")
            append("\n\nВыберите пробный период.")
        }

    private fun buildOwnerCommercialTermsSummaryText(request: VenueConnectionRequestRecord): String {
        val trialText =
            if (!request.trialConfigured) {
                "—"
            } else {
                request.trialEndsOn?.let { "до ${it.format(bookingDateConfirmFormatter)}" } ?: "Без trial"
            }
        val currentPriceText = request.currentPriceRub?.let { "$it ₽/мес" } ?: "—"
        val futureText =
            if (request.futurePriceRub != null && request.futurePriceEffectiveOn != null) {
                "${request.futurePriceRub} ₽/мес с ${request.futurePriceEffectiveOn.format(bookingDateConfirmFormatter)}"
            } else {
                "Не задана"
            }
        val note = request.commercialNote?.takeIf { it.isNotBlank() } ?: "—"
        return buildString {
            append("Коммерческие условия")
            append("\nTrial: $trialText")
            append("\nСтоимость после trial: $currentPriceText")
            append("\nБудущая стоимость: $futureText")
            append("\nЗаметка: $note")
        }
    }

    private fun isBotNavigationActionText(text: String): Boolean =
        text in
            setOf(
                "🍽️ Меню",
                "🍽️ Открыть меню",
                "🧺 Корзина",
                "👥 Общий счёт",
                "📄 Мой заказ",
                "📄 Заказ",
                "🧾 Активный заказ",
                "🔔 Персонал",
                "🔔 Вызвать персонал",
                "🛎️ Вызов персонала",
                "🛎 Вызвать персонал",
                "🚪 Сменить стол",
                "🔁 Сменить стол",
                "🍽 Каталог кальянных",
                "🍽️ Каталог кальянных",
                "🗺️ Каталог кальянных",
                "📄 Мои заказы и брони",
                "🏠 В каталог",
            )

    private fun currentBookingDateBase(): LocalDate = LocalDate.now(ZoneId.systemDefault())

    private suspend fun loadBookingTimeSlots(
        chatId: Long,
        venue: CatalogVenueShort,
        date: LocalDate,
    ): List<String>? {
        val bookingHours =
            try {
                venueBookingHoursRepository.findByVenueAndDate(venue.id, date)
            } catch (e: DatabaseUnavailableException) {
                enqueueMessage(chatId, "База недоступна, попробуйте позже.")
                return null
            }
        if (bookingHours == null) {
            enqueueMessage(chatId, "На выбранную дату в «${venue.name}» пока нет доступного времени.")
            return null
        }
        val slots =
            buildBookingTimeSlots(
                date = date,
                opensAt = bookingHours.opensAt,
                closesAt = bookingHours.closesAt,
            )
        if (slots.isEmpty()) {
            enqueueMessage(chatId, "На выбранную дату в «${venue.name}» пока нет доступного времени.")
            return null
        }
        return slots
    }

    private fun buildBookingTimeSlots(
        date: LocalDate,
        opensAt: LocalTime,
        closesAt: LocalTime,
    ): List<String> {
        if (opensAt == closesAt) return emptyList()
        val opensAtDateTime = LocalDateTime.of(date, opensAt)
        val closesAtDateTime =
            if (closesAt.isAfter(opensAt)) {
                LocalDateTime.of(date, closesAt)
            } else {
                LocalDateTime.of(date.plusDays(1), closesAt)
            }
        val lastStart = closesAtDateTime.minusHours(1)
        if (lastStart.isBefore(opensAtDateTime)) return emptyList()
        val slots = mutableListOf<String>()
        var cursor = opensAtDateTime
        while (!cursor.isAfter(lastStart)) {
            slots += cursor.toLocalTime().format(bookingTimeFormatter)
            cursor = cursor.plusHours(1)
        }
        return slots
    }

    private fun formatVenueAddress(venue: CatalogVenueShort): String {
        val address = venue.address?.trim().orEmpty().ifBlank { null }
        val city = venue.city?.trim().orEmpty().ifBlank { null }
        return when {
            address != null && city != null && !address.contains(city, ignoreCase = true) -> "$address, $city"
            address != null -> address
            city != null -> city
            else -> "Адрес уточняется"
        }
    }

    private fun formatVenueShortAddress(venue: CatalogVenueShort): String {
        val address = venue.address?.trim().orEmpty().ifBlank { null }
        val city = venue.city?.trim().orEmpty().ifBlank { null }
        val shortAddress =
            address
                ?.split(",")
                ?.map { it.trim() }
                ?.filter { it.isNotBlank() }
                ?.take(2)
                ?.joinToString(", ")
                ?.takeIf { it.isNotBlank() }
        return when {
            shortAddress != null -> shortAddress
            city != null -> city
            else -> "Адрес уточняется"
        }
    }

    private fun buildVenueRouteUrl(venue: CatalogVenueShort): String {
        val coordinateRegex = Regex("""(-?\d{1,2}(?:\.\d+)?)[,\s]+(-?\d{1,3}(?:\.\d+)?)""")
        val coordinates =
            venue.address
                ?.let { address -> coordinateRegex.find(address) }
                ?.let { match ->
                    val lat = match.groupValues.getOrNull(1)?.toDoubleOrNull()
                    val lon = match.groupValues.getOrNull(2)?.toDoubleOrNull()
                    if (lat != null && lon != null && lat in -90.0..90.0 && lon in -180.0..180.0) lat to lon else null
                }
        if (coordinates != null) {
            val (lat, lon) = coordinates
            return "https://yandex.ru/maps/?rtext=~$lat,$lon&rtt=auto"
        }
        val query = URLEncoder.encode("${venue.name}, ${formatVenueAddress(venue)}", StandardCharsets.UTF_8)
        return "https://yandex.ru/maps/?text=$query"
    }

    private fun isBotTestCatalogSeedingEnabled(): Boolean {
        return isDevMode()
    }

    private fun isDevMode(): Boolean {
        val env = config.appEnv.trim().lowercase(Locale.ROOT)
        return env == "dev" || env == "local" || env == "test"
    }

    private suspend fun applyTableToken(
        chatId: Long,
        from: User?,
        token: String,
        announceChannelChoice: Boolean = false,
    ): ApplyTableTokenResult {
        val context =
            try {
                tableTokenRepository.resolve(token)
            } catch (e: CancellationException) {
                throw e
            } catch (e: DatabaseUnavailableException) {
                return ApplyTableTokenResult.DatabaseUnavailable
            } ?: return ApplyTableTokenResult.Invalid
        val userId = from?.id ?: return ApplyTableTokenResult.Invalid
        when (ensureGuestTableFlowAccess(userId, context.venueId)) {
            VenueGuestAccessResult.Allowed -> Unit
            VenueGuestAccessResult.Forbidden -> return ApplyTableTokenResult.VenueUnavailableForGuest
            VenueGuestAccessResult.DatabaseUnavailable -> return ApplyTableTokenResult.DatabaseUnavailable
        }
        when (checkSubscription(context.venueId)) {
            SubscriptionCheckResult.Available -> Unit
            SubscriptionCheckResult.Blocked -> return ApplyTableTokenResult.Blocked
            SubscriptionCheckResult.DatabaseUnavailable -> return ApplyTableTokenResult.DatabaseUnavailable
        }
        try {
            chatContextRepository.saveContext(chatId, userId, context)
        } catch (e: CancellationException) {
            throw e
        } catch (e: DatabaseUnavailableException) {
            return ApplyTableTokenResult.DatabaseUnavailable
        }
        val activeSession =
            try {
                tableSessionRepository.resolveActiveSession(
                    venueId = context.venueId,
                    tableId = context.tableId,
                    ttl = tableSessionTtl,
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: DatabaseUnavailableException) {
                return ApplyTableTokenResult.DatabaseUnavailable
            }
        val key = BotDraftCartKey(chatId = chatId, tableToken = context.tableToken)
        clearBotDraftStateForChatExceptTableToken(chatId, context.tableToken)
        val previousSessionId = botDraftCartSessionIds[key]
        if (previousSessionId != null && previousSessionId != activeSession.id) {
            clearBotDraftCart(chatId, context.tableToken)
        }
        botDraftCartSessionIds[key] = activeSession.id
        botBookingCommentDrafts.remove(chatId)
        botBookingPendingConfirmations.remove(chatId)
        botBookingEditContexts.remove(chatId)
        dialogStateRepository.clear(chatId)
        if (announceChannelChoice) {
            enqueueMessage(chatId, keyboardRemoveMarker, ReplyKeyboardRemove(removeKeyboard = true))
            enqueueMessage(
                chatId,
                "Вы за столом №${context.tableNumber} в ${context.venueName}. Выберите удобный способ заказа.",
                TelegramKeyboards.inlineTableEntryChoice(config.webAppPublicUrl, context.tableToken),
            )
        }
        return ApplyTableTokenResult.Applied
    }

    private suspend fun showTableQrEntryHint(chatId: Long) {
        enqueueMessage(
            chatId,
            "Если вы уже за столом, откройте QR-код стола камерой Telegram/телефона. " +
                "После входа в контекст стола я предложу: Mini App или продолжить в боте.",
        )
    }

    private suspend fun continueInBot(chatId: Long) {
        val context = resolveGuestContext(chatId) ?: return
        enqueueMessage(
            chatId,
            "Продолжаем в боте. Выберите действие.",
        )
        // Restore persistent in-venue reply keyboard after channel selection.
        enqueueMessage(
            chatId,
            keyboardRemoveMarker,
            TelegramKeyboards.tableContextBotFlow(context.table),
        )
    }

    private suspend fun showBotMenu(
        chatId: Long,
        reorderMode: Boolean = false,
        sourceMessageId: Long? = null,
    ) {
        logger.warn("SHOW menu")
        val context = resolveGuestContext(chatId) ?: return
        val menu =
            try {
                guestMenuRepository.getMenu(context.table.venueId)
            } catch (e: DatabaseUnavailableException) {
                enqueueMessage(chatId, "База недоступна, попробуйте позже.")
                return
            }
        val categories =
            menu.categories
                .filter { category -> category.items.isNotEmpty() }
                .map { category -> category.id to category.name }
        if (categories.isEmpty()) {
            enqueueMessage(chatId, "В ${context.table.venueName} пока нет категорий меню.")
            return
        }
        upsertBotMenuMessage(
            chatId,
            tableToken = context.table.tableToken,
            if (reorderMode) {
                "Выберите позицию в меню, затем откройте корзину и нажмите «Оформить заказ»."
            } else {
                "${context.table.venueName}\nВыберите категорию."
            },
            TelegramKeyboards.inlineBotMenuCategories(categories),
            sourceMessageId = sourceMessageId,
        )
    }

    private suspend fun showBotSplitBillEntry(chatId: Long) {
        val context = resolveGuestContext(chatId) ?: return
        val currentTab = resolveCurrentBotTab(chatId, context) ?: return
        val currentTabType = currentTab.type.uppercase(Locale.ROOT)
        if (currentTabType == "SHARED") {
            val sharedInfoText =
                if (currentTab.ownerUserId == context.userId) {
                    val inviteCode = createSharedInviteCode(chatId, context, currentTab.tableSessionId, currentTab.id)
                    if (inviteCode != null) {
                        "Текущий счёт: Общий.\nКод приглашения: $inviteCode"
                    } else {
                        "Текущий счёт: Общий.\nВы создатель общего счёта."
                    }
                } else {
                    "Текущий счёт: Общий.\nВы уже присоединились к общему счёту."
                }
            enqueueMessage(chatId, sharedInfoText)
            return
        }
        enqueueMessage(
            chatId,
            "Общий счёт\nВыберите действие.",
            TelegramKeyboards.inlineBotSplitBillEntryActions(),
        )
    }

    private suspend fun showBotMenuCategoryItems(
        chatId: Long,
        data: String,
        sourceMessageId: Long? = null,
    ) {
        logger.warn(
            "SHOW category_items data={}",
            sanitizeTelegramForLog(data),
        )
        val parsed = parseBotMenuCategoryData(data)
        logger.warn(
            "SHOW category_items data={} parsed={}",
            sanitizeTelegramForLog(data),
            parsed?.let { "${it.first}:${it.second}" } ?: "null",
        )
        if (parsed == null) {
            logger.warn(
                "Invalid menu category callback data chat_id={} message_id={} data={}",
                chatId,
                sourceMessageId,
                sanitizeTelegramForLog(data),
            )
            enqueueMessage(chatId, "Не удалось открыть категорию. Попробуйте ещё раз.")
            return
        }
        val (categoryId, requestedPage) = parsed
        val context = resolveGuestContext(chatId) ?: return
        val menu =
            try {
                guestMenuRepository.getMenu(context.table.venueId)
            } catch (e: DatabaseUnavailableException) {
                enqueueMessage(chatId, "База недоступна, попробуйте позже.")
                return
            }
        val category = menu.categories.firstOrNull { it.id == categoryId } ?: run {
            enqueueMessage(chatId, "Категория не найдена.")
            return
        }
        val lines =
            category.items.mapIndexed { index, item ->
                "${index + 1}. ${item.name} — ${formatPrice(item.priceMinor, item.currency)}"
            }
        if (lines.isEmpty()) {
            enqueueMessage(chatId, "В категории \"${category.name}\" пока нет доступных позиций.")
            return
        }
        val totalPages = ((category.items.size - 1) / botMenuItemsPerPage) + 1
        val page = requestedPage.coerceIn(0, totalPages - 1)
        val fromIndex = page * botMenuItemsPerPage
        val toIndex = minOf(fromIndex + botMenuItemsPerPage, category.items.size)
        val pageItems = category.items.subList(fromIndex, toIndex)
        upsertBotMenuMessage(
            chatId,
            tableToken = context.table.tableToken,
            buildString {
                append(category.name)
                append("\n")
                append("Выберите позицию.")
                if (totalPages > 1) {
                    append("\n")
                    append("Страница ${page + 1}/$totalPages")
                }
            },
            TelegramKeyboards.inlineBotMenuItems(
                categoryId = category.id,
                items =
                    pageItems.map { item ->
                        item.id to "${item.name} — ${formatPrice(item.priceMinor, item.currency)}"
                    },
                page = page,
                totalPages = totalPages,
            ),
            sourceMessageId = sourceMessageId,
        )
    }

    private suspend fun showBotMenuItemDetails(
        chatId: Long,
        data: String,
        sourceMessageId: Long? = null,
    ) {
        val (categoryId, itemId) = parseBotMenuItemData(data) ?: return
        val context = resolveGuestContext(chatId) ?: return
        val menu =
            try {
                guestMenuRepository.getMenu(context.table.venueId)
            } catch (e: DatabaseUnavailableException) {
                enqueueMessage(chatId, "База недоступна, попробуйте позже.")
                return
            }
        val category = menu.categories.firstOrNull { it.id == categoryId } ?: run {
            enqueueMessage(chatId, "Категория не найдена.")
            return
        }
        val item = category.items.firstOrNull { it.id == itemId } ?: run {
            enqueueMessage(chatId, "Позиция не найдена.")
            return
        }
        val availableOptions =
            try {
                venueMenuRepository.listAvailableOptionsForItem(
                    venueId = context.table.venueId,
                    itemId = item.id,
                )
            } catch (e: DatabaseUnavailableException) {
                enqueueMessage(chatId, "База недоступна, попробуйте позже.")
                return
            }
        if (availableOptions.isNotEmpty()) {
            upsertBotMenuMessage(
                chatId,
                tableToken = context.table.tableToken,
                buildString {
                    append(item.name)
                    append("\n")
                    append("Цена: ${formatPrice(item.priceMinor, item.currency)}")
                    append("\n\n")
                    append("Выберите вкус.")
                },
                TelegramKeyboards.inlineBotMenuItemFlavorOptions(
                    categoryId = category.id,
                    itemId = item.id,
                    options = availableOptions.map { option -> option.id to option.name },
                ),
                sourceMessageId = sourceMessageId,
            )
            return
        }
        addItemToBotDraftCart(chatId, context.table.tableToken, item)
        upsertBotMenuMessage(
            chatId,
            tableToken = context.table.tableToken,
            buildString {
                append(item.name)
                append("\n")
                append("Цена: ${formatPrice(item.priceMinor, item.currency)}")
                append("\n\n")
                append("Позиция добавлена в корзину.")
            },
            TelegramKeyboards.inlineBotMenuPostAddActions(category.id),
            sourceMessageId = sourceMessageId,
        )
    }

    private suspend fun addBotMenuItemOptionToCart(
        chatId: Long,
        data: String,
        sourceMessageId: Long? = null,
    ) {
        val (categoryId, itemId, optionId) = parseBotMenuItemOptionData(data) ?: return
        val context = resolveGuestContext(chatId) ?: return
        val menu =
            try {
                guestMenuRepository.getMenu(context.table.venueId)
            } catch (e: DatabaseUnavailableException) {
                enqueueMessage(chatId, "База недоступна, попробуйте позже.")
                return
            }
        val category = menu.categories.firstOrNull { it.id == categoryId } ?: run {
            enqueueMessage(chatId, "Категория не найдена.")
            return
        }
        val item = category.items.firstOrNull { it.id == itemId } ?: run {
            enqueueMessage(chatId, "Позиция не найдена.")
            return
        }
        val availableOptions =
            try {
                venueMenuRepository.listAvailableOptionsForItem(
                    venueId = context.table.venueId,
                    itemId = item.id,
                )
            } catch (e: DatabaseUnavailableException) {
                enqueueMessage(chatId, "База недоступна, попробуйте позже.")
                return
            }
        val selectedOption = availableOptions.firstOrNull { it.id == optionId } ?: run {
            enqueueMessage(chatId, "Вкус не найден или недоступен.")
            return
        }
        addItemToBotDraftCart(
            chatId = chatId,
            tableToken = context.table.tableToken,
            item = item,
            optionId = selectedOption.id,
            optionName = selectedOption.name,
            optionPriceDeltaMinor = selectedOption.priceDeltaMinor,
        )
        val finalPriceMinor = item.priceMinor + selectedOption.priceDeltaMinor
        upsertBotMenuMessage(
            chatId,
            tableToken = context.table.tableToken,
            buildString {
                append(item.name)
                append("\n")
                append("Вкус: ${selectedOption.name}")
                append("\n")
                append("Цена: ${formatPrice(finalPriceMinor, item.currency)}")
                append("\n\n")
                append("Позиция добавлена в корзину.")
            },
            TelegramKeyboards.inlineBotMenuPostAddActions(category.id),
            sourceMessageId = sourceMessageId,
        )
    }

    private suspend fun startBotReorderFlow(
        chatId: Long,
        sourceMessageId: Long? = null,
    ) {
        resolveGuestContext(chatId) ?: return
        showBotMenu(chatId, reorderMode = true, sourceMessageId = sourceMessageId)
    }

    private suspend fun reorderBotMenuItem(
        chatId: Long,
        data: String,
        callbackId: String,
    ) {
        val (categoryId, itemId) = parseBotMenuItemData(data) ?: return
        val context = resolveGuestContext(chatId) ?: return
        val currentTab = resolveCurrentBotTab(chatId, context) ?: return
        val menu =
            try {
                guestMenuRepository.getMenu(context.table.venueId)
            } catch (e: DatabaseUnavailableException) {
                enqueueMessage(chatId, "База недоступна, попробуйте позже.")
                return
            }
        val category = menu.categories.firstOrNull { it.id == categoryId } ?: run {
            enqueueMessage(chatId, "Категория не найдена.")
            return
        }
        val item = category.items.firstOrNull { it.id == itemId } ?: run {
            enqueueMessage(chatId, "Позиция не найдена.")
            return
        }
        val activeOrder =
            ordersRepository.findActiveOrderSummaryForTab(
                tableId = context.table.tableId,
                tabId = currentTab.id,
            )
        if (activeOrder == null) {
            addItemToBotDraftCart(chatId, context.table.tableToken, item)
            enqueueMessage(
                chatId,
                buildString {
                    append(item.name)
                    append("\n")
                    append("Цена: ${formatPrice(item.priceMinor, item.currency)}")
                    append("\n\n")
                    append("Активного заказа нет. Позиция добавлена в корзину.")
                },
                TelegramKeyboards.inlineBotMenuPostAddActions(category.id),
            )
            return
        }
        val createdBatch =
            createGuestBatchFromBotItems(
                chatId = chatId,
                context = context,
                idempotencyKey = "bot-reorder:$callbackId",
                tabId = currentTab.id,
                items =
                    listOf(
                        OrderBatchItemInput(
                            itemId = item.id,
                            qty = 1,
                        ),
                    ),
            ) ?: return
        enqueueMessage(
            chatId,
            buildString {
                append(item.name)
                append("\n")
                append("Цена: ${formatPrice(item.priceMinor, item.currency)}")
                append("\n\n")
                append(
                    "✅ Дозаказ отправлен в активный ${
                        formatTelegramOrderDisplayLabel(
                            createdBatch.orderId,
                            createdBatch.displayNumber,
                            createdBatch.displayDate,
                        )
                    }.",
                )
            },
            TelegramKeyboards.inlineBotMenuPostAddActions(category.id),
        )
    }

    private suspend fun showBotMenuCart(
        chatId: Long,
        sourceMessageId: Long? = null,
    ) {
        val context = resolveGuestContext(chatId) ?: return
        val currentTab = resolveCurrentBotTab(chatId, context) ?: return
        val showSplitBillActions = currentTab.type == "PERSONAL"
        val key = BotDraftCartKey(chatId = chatId, tableToken = context.table.tableToken)
        clearBotTransientScreens(chatId, key)
        val items = getBotDraftCartItems(chatId, context.table.tableToken)
        val guestComment = botDraftCartComments[key]?.trim()?.takeIf { it.isNotBlank() }
        if (items.isEmpty()) {
            sendBotCartScreenMessage(
                chatId,
                key,
                "Корзина пуста.",
                TelegramKeyboards.inlineBotMenuCartSummaryActions(
                    showSplitBillActions = showSplitBillActions,
                    hasComment = guestComment != null,
                ),
            )
            return
        }
        val totalsByCurrency =
            items
                .groupBy { it.currency.uppercase(Locale.ROOT) }
                .mapValues { entry ->
                    entry.value.sumOf { it.priceMinor * it.qty.toLong() }
                }
        val totalText =
            if (totalsByCurrency.size == 1) {
                val (currency, totalMinor) = totalsByCurrency.entries.first()
                "Итого: ${formatPrice(totalMinor, currency)}"
            } else {
                buildString {
                    append("Итого:\n")
                    append(
                        totalsByCurrency.entries.joinToString("\n") { (currency, totalMinor) ->
                            "- ${formatPrice(totalMinor, currency)}"
                        },
                    )
                }
            }
        items.forEach { item ->
            val lineTotal = formatPrice(item.priceMinor * item.qty.toLong(), item.currency)
            sendBotCartScreenMessage(
                chatId,
                key,
                buildString {
                    append("${item.name}\n")
                    append("Количество: ${item.qty}\n")
                    append("Сумма: $lineTotal")
                },
                TelegramKeyboards.inlineBotMenuCartItemActions(item.lineId),
            )
        }
        sendBotCartScreenMessage(
            chatId,
            key,
            buildString {
                append("🧺 Корзина\n")
                append(totalText)
                append("\nКомментарий: ${guestComment ?: "—"}")
            },
            TelegramKeyboards.inlineBotMenuCartSummaryActions(
                showSplitBillActions = showSplitBillActions,
                hasComment = guestComment != null,
            ),
        )
    }

    private suspend fun clearBotMenuCart(
        chatId: Long,
        sourceMessageId: Long? = null,
    ) {
        val context = resolveGuestContext(chatId) ?: return
        clearBotDraftCart(chatId, context.table.tableToken)
        showBotMenuCart(chatId)
    }

    private suspend fun changeBotMenuCartItemQty(
        chatId: Long,
        data: String,
        delta: Int,
        sourceMessageId: Long? = null,
    ) {
        val itemId = parseBotMenuCartItemId(data) ?: return
        val context = resolveGuestContext(chatId) ?: return
        val key = BotDraftCartKey(chatId = chatId, tableToken = context.table.tableToken)
        val cart = botDraftCarts[key] ?: run {
            showBotMenuCart(chatId)
            return
        }
        cart.compute(itemId) { _, existing ->
            val current = existing ?: return@compute null
            val nextQty = current.qty + delta
            if (nextQty <= 0) null else current.copy(qty = nextQty)
        }
        if (cart.isEmpty()) {
            botDraftCarts.remove(key)
        }
        showBotMenuCart(chatId)
    }

    private suspend fun removeBotMenuCartItem(
        chatId: Long,
        data: String,
        sourceMessageId: Long? = null,
    ) {
        val itemId = parseBotMenuCartItemId(data) ?: return
        val context = resolveGuestContext(chatId) ?: return
        val key = BotDraftCartKey(chatId = chatId, tableToken = context.table.tableToken)
        val cart = botDraftCarts[key] ?: run {
            showBotMenuCart(chatId)
            return
        }
        cart.remove(itemId)
        if (cart.isEmpty()) {
            botDraftCarts.remove(key)
        }
        showBotMenuCart(chatId)
    }

    private suspend fun checkoutBotMenuCart(
        chatId: Long,
        callbackId: String,
    ) {
        val context = resolveGuestContext(chatId) ?: return
        val currentTab = resolveCurrentBotTab(chatId, context) ?: return
        val draftItems = getBotDraftCartItems(chatId, context.table.tableToken)
        if (draftItems.isEmpty()) {
            showBotMenuCart(chatId)
            return
        }
        val key = BotDraftCartKey(chatId = chatId, tableToken = context.table.tableToken)
        val cartComment = botDraftCartComments[key]
        val createdBatch =
            createGuestBatchFromBotItems(
                chatId = chatId,
                context = context,
                idempotencyKey = "bot-cart-checkout:$callbackId",
                tabId = currentTab.id,
                comment = buildBotDraftCartSubmitComment(draftItems, cartComment),
                items =
                    draftItems.map { item ->
                        OrderBatchItemInput(
                            itemId = item.itemId,
                            qty = item.qty,
                        )
                },
            )
                ?: return
        clearBotCartScreenMessages(chatId, key)
        clearBotDraftCart(chatId, context.table.tableToken)
        enqueueMessage(
            chatId,
            "✅ Заказ отправлен.\n${formatTelegramOrderDisplayLabel(createdBatch.orderId, createdBatch.displayNumber, createdBatch.displayDate)}.\nЧто дальше?",
            TelegramKeyboards.inlineBotReorderAction(),
        )
    }

    private suspend fun promptBotMenuCartComment(chatId: Long) {
        val context = resolveGuestContext(chatId) ?: return
        val key = BotDraftCartKey(chatId = chatId, tableToken = context.table.tableToken)
        dialogStateRepository.set(
            chatId,
            DialogState(DialogStateType.BOT_MENU_CART_WAIT_COMMENT),
        )
        val existing = botDraftCartComments[key]?.trim()?.takeIf { it.isNotBlank() }
        enqueueMessage(
            chatId,
            buildString {
                append("Введите комментарий к заказу или дозаказу.\n")
                append("Отправьте «—», чтобы удалить комментарий.")
                existing?.let {
                    append("\n\nТекущий комментарий: $it")
                }
            },
        )
    }

    private suspend fun proceedBotMenuCartComment(
        chatId: Long,
        rawText: String,
    ) {
        val context = resolveGuestContext(chatId) ?: run {
            dialogStateRepository.clear(chatId)
            return
        }
        val key = BotDraftCartKey(chatId = chatId, tableToken = context.table.tableToken)
        val trimmed = rawText.trim()
        if (trimmed == "-" || trimmed == "—") {
            botDraftCartComments.remove(key)
            dialogStateRepository.clear(chatId)
            enqueueMessage(chatId, "✅ Комментарий удалён.")
            showBotMenuCart(chatId)
            return
        }
        if (trimmed.isBlank()) {
            enqueueMessage(chatId, "Комментарий пустой. Введите текст или отправьте «—».")
            return
        }
        if (trimmed.length > 500) {
            enqueueMessage(chatId, "Комментарий слишком длинный. До 500 символов.")
            return
        }
        botDraftCartComments[key] = trimmed
        dialogStateRepository.clear(chatId)
        enqueueMessage(chatId, "✅ Комментарий сохранён.")
        showBotMenuCart(chatId)
    }

    private suspend fun promptBotJoinShared(chatId: Long) {
        resolveGuestContext(chatId) ?: return
        dialogStateRepository.set(chatId, DialogState(DialogStateType.BOT_JOIN_SHARED_WAIT_CODE))
        botJoinSharedAwaitingChats.add(chatId)
        enqueueMessage(chatId, "Введите 4-значный код общего счёта.")
    }

    private suspend fun proceedBotJoinSharedCode(
        chatId: Long,
        rawCode: String,
    ) {
        val context = resolveGuestContext(chatId) ?: return
        val code = rawCode.trim()
        if (!code.matches(Regex("^\\d{4}$"))) {
            enqueueMessage(chatId, "Нужен 4-значный код. Попробуйте ещё раз.")
            return
        }
        val tableSession = resolveCurrentTableSession(chatId, context) ?: return
        val joinedTab =
            try {
                guestTabsRepository.joinByInvite(
                    venueId = context.table.venueId,
                    tableSessionId = tableSession.id,
                    userId = context.userId,
                    token = code,
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: DatabaseUnavailableException) {
                enqueueMessage(chatId, "База недоступна, попробуйте позже.")
                return
            }
        if (joinedTab == null) {
            enqueueMessage(chatId, "Код недействителен или истёк. Попробуйте ещё раз.")
            return
        }
        val key = BotDraftCartKey(chatId = chatId, tableToken = context.table.tableToken)
        botSelectedTabIds[key] = joinedTab.id
        botJoinSharedAwaitingChats.remove(chatId)
        dialogStateRepository.clear(chatId)
        enqueueMessage(
            chatId,
            "✅ Вы присоединились к общему счёту.",
        )
    }

    private suspend fun createBotSharedTab(chatId: Long) {
        val context = resolveGuestContext(chatId) ?: return
        val tableSession = resolveCurrentTableSession(chatId, context) ?: return
        val sharedTab =
            try {
                guestTabsRepository.createSharedTab(
                    venueId = context.table.venueId,
                    tableSessionId = tableSession.id,
                    ownerUserId = context.userId,
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: DatabaseUnavailableException) {
                enqueueMessage(chatId, "База недоступна, попробуйте позже.")
                return
            }
        val key = BotDraftCartKey(chatId = chatId, tableToken = context.table.tableToken)
        botSelectedTabIds[key] = sharedTab.id
        val inviteCode = createSharedInviteCode(chatId, context, tableSession.id, sharedTab.id)
        if (inviteCode == null) {
            enqueueMessage(chatId, "Общий счёт создан, но код пока не сгенерирован. Попробуйте ещё раз.")
            return
        }
        enqueueMessage(
            chatId,
            "✅ Общий счёт создан.\nКод приглашения: $inviteCode",
        )
    }

    private suspend fun createSharedInviteCode(
        chatId: Long,
        context: ResolvedChatContext,
        tableSessionId: Long,
        tabId: Long,
    ): String? {
        try {
            guestTabsRepository.deleteExpiredInvites()
        } catch (e: CancellationException) {
            throw e
        } catch (e: DatabaseUnavailableException) {
            enqueueMessage(chatId, "База недоступна, попробуйте позже.")
            return null
        }
        val expiresAt = Instant.now().plus(inviteCodeTtl)
        repeat(inviteCodeMaxAttempts) {
            val code = inviteCodeRandom.nextInt(inviteCodeRangeExclusive).toString().padStart(inviteCodeLength, '0')
            val createResult =
                try {
                    guestTabsRepository.createInvite(
                        tabId = tabId,
                        venueId = context.table.venueId,
                        tableSessionId = tableSessionId,
                        createdBy = context.userId,
                        token = code,
                        expiresAt = expiresAt,
                    )
                } catch (e: CancellationException) {
                    throw e
                } catch (e: DatabaseUnavailableException) {
                    enqueueMessage(chatId, "База недоступна, попробуйте позже.")
                    return null
                }
            when (createResult) {
                CreateInviteResult.CREATED -> return code
                CreateInviteResult.TOKEN_CONFLICT -> Unit
                CreateInviteResult.FORBIDDEN -> return null
            }
        }
        return null
    }

    private suspend fun resolveCurrentTableSession(
        chatId: Long,
        context: ResolvedChatContext,
    ) = try {
        tableSessionRepository.resolveActiveSession(
            venueId = context.table.venueId,
            tableId = context.table.tableId,
            ttl = tableSessionTtl,
        )
    } catch (e: CancellationException) {
        throw e
    } catch (e: DatabaseUnavailableException) {
        enqueueMessage(chatId, "База недоступна, попробуйте позже.")
        null
    }

    private suspend fun createGuestBatchFromBotItems(
        chatId: Long,
        context: ResolvedChatContext,
        idempotencyKey: String,
        tabId: Long? = null,
        comment: String? = null,
        items: List<OrderBatchItemInput>,
    ): com.hookah.platform.backend.telegram.db.CreatedOrderBatch? {
        val tableSession =
            try {
                tableSessionRepository.resolveActiveSession(
                    venueId = context.table.venueId,
                    tableId = context.table.tableId,
                    ttl = tableSessionTtl,
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: DatabaseUnavailableException) {
                enqueueMessage(chatId, "База недоступна, попробуйте позже.")
                return null
            }
        val checkedRequestedTabId =
            if (tabId != null) {
                val isMember =
                    try {
                        guestTabsRepository.isTabMember(
                            tabId = tabId,
                            venueId = context.table.venueId,
                            tableSessionId = tableSession.id,
                            userId = context.userId,
                        )
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: DatabaseUnavailableException) {
                        enqueueMessage(chatId, "База недоступна, попробуйте позже.")
                        return null
                    }
                tabId.takeIf { isMember }
            } else {
                null
            }
        val effectiveTabId =
            if (checkedRequestedTabId != null) {
                checkedRequestedTabId
            } else {
                val personalTab =
                    try {
                        guestTabsRepository.ensurePersonalTab(
                            venueId = context.table.venueId,
                            tableSessionId = tableSession.id,
                            userId = context.userId,
                        )
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: DatabaseUnavailableException) {
                        enqueueMessage(chatId, "База недоступна, попробуйте позже.")
                        return null
                    }
                personalTab.id
            }
        return try {
            val createdBatch =
                ordersRepository.createGuestOrderBatch(
                tableId = context.table.tableId,
                venueId = context.table.venueId,
                tableSessionId = tableSession.id,
                userId = context.userId,
                idempotencyKey = idempotencyKey,
                tabId = effectiveTabId,
                comment = comment,
                items = items,
            )
            if (createdBatch == null) {
                enqueueMessage(chatId, "Не удалось оформить заказ, попробуйте ещё раз.")
                null
            } else {
                createdBatch
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: DatabaseUnavailableException) {
            enqueueMessage(chatId, "База недоступна, попробуйте позже.")
            null
        }
    }

    private suspend fun resolveCurrentBotTab(
        chatId: Long,
        context: ResolvedChatContext,
    ): com.hookah.platform.backend.miniapp.guest.db.GuestTabModel? {
        val tableSession = resolveCurrentTableSession(chatId, context) ?: return null
        val personalTab =
            try {
                guestTabsRepository.ensurePersonalTab(
                    venueId = context.table.venueId,
                    tableSessionId = tableSession.id,
                    userId = context.userId,
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: DatabaseUnavailableException) {
                enqueueMessage(chatId, "База недоступна, попробуйте позже.")
                return null
            }
        val userTabs =
            try {
                guestTabsRepository.listTabsForUser(
                    venueId = context.table.venueId,
                    tableSessionId = tableSession.id,
                    userId = context.userId,
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: DatabaseUnavailableException) {
                enqueueMessage(chatId, "База недоступна, попробуйте позже.")
                return null
            }
        val key = BotDraftCartKey(chatId = chatId, tableToken = context.table.tableToken)
        val selectedTabId = botSelectedTabIds[key]
        if (selectedTabId != null) {
            val selectedTab =
                userTabs.firstOrNull { tab ->
                    tab.id == selectedTabId && tab.status == "ACTIVE"
                }
            if (selectedTab != null) {
                return selectedTab
            }
            botSelectedTabIds.remove(key)
        }
        val activeSharedTabs =
            userTabs.filter { tab ->
                tab.status == "ACTIVE" && tab.type == "SHARED"
            }
        if (activeSharedTabs.isNotEmpty()) {
            var sharedWithActiveOrder: com.hookah.platform.backend.miniapp.guest.db.GuestTabModel? = null
            try {
                for (sharedTab in activeSharedTabs) {
                    val activeOrder =
                        ordersRepository.findActiveOrderSummaryForTab(
                            tableId = context.table.tableId,
                            tabId = sharedTab.id,
                        )
                    if (activeOrder != null) {
                        sharedWithActiveOrder = sharedTab
                        break
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                enqueueMessage(chatId, "База недоступна, попробуйте позже.")
                return null
            }
            val restoredTab = sharedWithActiveOrder ?: activeSharedTabs.first()
            botSelectedTabIds[key] = restoredTab.id
            return restoredTab
        }
        return personalTab
    }

    private suspend fun upsertBotCartMessage(
        chatId: Long,
        key: BotDraftCartKey,
        text: String,
        replyMarkup: ReplyMarkup,
        sourceMessageId: Long?,
    ) {
        if (sourceMessageId != null) {
            outboxEnqueuer.enqueueEditMessageText(
                chatId = chatId,
                messageId = sourceMessageId,
                text = text,
                replyMarkup = replyMarkup,
            )
            botCartMessageIds[key] = sourceMessageId
            return
        }
        // Without callback source message id we can't reliably edit in place:
        // stale mapping may point to an outdated/deleted message.
        botCartMessageIds.remove(key)
        enqueueMessage(chatId, text, replyMarkup)
    }

    private suspend fun upsertBotMenuMessage(
        chatId: Long,
        tableToken: String,
        text: String,
        replyMarkup: ReplyMarkup,
        sourceMessageId: Long? = null,
    ) {
        val key = BotDraftCartKey(chatId = chatId, tableToken = tableToken)
        clearBotTransientScreens(chatId, key)
        val messageId =
            runCatching { apiClient.sendMessage(chatId, text, replyMarkup)?.messageId }
                .onFailure { logBestEffort("telegram direct menu send", it) }
                .getOrNull()
        if (messageId != null) {
            botMenuMessageIds[key] = messageId
            return
        }
        enqueueMessage(chatId, text, replyMarkup)
    }

    private suspend fun clearBotTransientScreens(
        chatId: Long,
        key: BotDraftCartKey,
    ) {
        botMenuMessageIds.remove(key)?.let { previousMessageId ->
            deleteMessageBestEffort(chatId, previousMessageId)
        }
        clearBotCartScreenMessages(chatId, key)
    }

    private suspend fun sendBotBookingScreen(
        chatId: Long,
        venueId: Long,
        text: String,
        replyMarkup: ReplyMarkup? = null,
    ) {
        clearBotBookingScreenMessages(chatId)
        val key = BotBookingScreenKey(chatId = chatId, venueId = venueId)
        val messageId =
            runCatching { apiClient.sendMessage(chatId, text, replyMarkup)?.messageId }
                .onFailure { logBestEffort("telegram direct booking send", it) }
                .getOrNull()
        if (messageId != null) {
            botBookingScreenMessageIds[key] = messageId
            return
        }
        enqueueMessage(chatId, text, replyMarkup)
    }

    private suspend fun clearBotBookingScreenMessages(chatId: Long) {
        val keysToClear =
            botBookingScreenMessageIds.keys
                .filter { key -> key.chatId == chatId }
        keysToClear.forEach { key ->
            botBookingScreenMessageIds.remove(key)?.let { messageId ->
                deleteMessageBestEffort(chatId, messageId)
            }
        }
    }

    private fun formatPrice(
        priceMinor: Long,
        currency: String,
    ): String {
        val amount = priceMinor.toDouble() / 100.0
        val symbol =
            when (currency.uppercase(Locale.ROOT)) {
                "RUB" -> "₽"
                "USD" -> "$"
                "EUR" -> "€"
                else -> currency.uppercase(Locale.ROOT)
            }
        return String.format(Locale.US, "%.2f %s", amount, symbol)
    }

    private fun humanizeOrderStatus(rawStatus: String): String =
        when (rawStatus.uppercase(Locale.ROOT)) {
            "ACTIVE" -> "Принят"
            else -> "Принят"
        }

    private fun humanizeBillType(rawTabType: String?): String? =
        when (rawTabType?.uppercase(Locale.ROOT)) {
            "PERSONAL" -> "Личный"
            "SHARED" -> "Общий"
            else -> null
        }

    private fun humanizeBookingStatus(status: BookingStatus): String =
        when (status) {
            BookingStatus.PENDING -> "Ожидает подтверждения"
            BookingStatus.CONFIRMED -> "Подтверждена"
            BookingStatus.CHANGED -> "Изменена"
            BookingStatus.CANCELED -> "Отменена"
        }

    private fun parseBotMenuCategoryData(data: String): Pair<Long, Int>? {
        return when {
            data.startsWith("bot_menu_back_category:") -> {
                val categoryId = data.removePrefix("bot_menu_back_category:").toLongOrNull() ?: return null
                categoryId to 0
            }
            data.startsWith("bot_menu_category_page:") -> {
                val payload = data.removePrefix("bot_menu_category_page:")
                val parts = payload.split(":", limit = 2)
                val categoryId = parts.getOrNull(0)?.toLongOrNull() ?: return null
                val page = parts.getOrNull(1)?.toIntOrNull() ?: return null
                categoryId to page.coerceAtLeast(0)
            }
            data.startsWith("bot_menu_category:") -> {
                val categoryId = data.removePrefix("bot_menu_category:").toLongOrNull() ?: return null
                categoryId to 0
            }
            else -> null
        }
    }

    private fun parseBotMenuItemData(data: String): Pair<Long, Long>? {
        val payload =
            when {
            data.startsWith("bot_menu_item_add_to_cart:") -> data.removePrefix("bot_menu_item_add_to_cart:")
            data.startsWith("bot_menu_item_add:") -> data.removePrefix("bot_menu_item_add:")
            data.startsWith("bot_menu_item_reorder:") -> data.removePrefix("bot_menu_item_reorder:")
            data.startsWith("bot_menu_item:") -> data.removePrefix("bot_menu_item:")
            else -> return null
        }
        val parts = payload.split(":", limit = 2)
        val categoryId = parts.getOrNull(0)?.toLongOrNull() ?: return null
        val itemId = parts.getOrNull(1)?.toLongOrNull() ?: return null
        return categoryId to itemId
    }

    private fun parseBotMenuItemOptionData(data: String): Triple<Long, Long, Long>? {
        if (!data.startsWith("bot_menu_item_option_add_to_cart:")) {
            return null
        }
        val payload = data.removePrefix("bot_menu_item_option_add_to_cart:")
        val parts = payload.split(":", limit = 3)
        val categoryId = parts.getOrNull(0)?.toLongOrNull() ?: return null
        val itemId = parts.getOrNull(1)?.toLongOrNull() ?: return null
        val optionId = parts.getOrNull(2)?.toLongOrNull() ?: return null
        return Triple(categoryId, itemId, optionId)
    }

    private fun parseBotMenuCartItemId(data: String): Long? {
        return when {
            data.startsWith("bot_menu_cart_inc:") -> data.removePrefix("bot_menu_cart_inc:").toLongOrNull()
            data.startsWith("bot_menu_cart_dec:") -> data.removePrefix("bot_menu_cart_dec:").toLongOrNull()
            data.startsWith("bot_menu_cart_remove:") -> data.removePrefix("bot_menu_cart_remove:").toLongOrNull()
            else -> null
        }
    }

    private fun addItemToBotDraftCart(
        chatId: Long,
        tableToken: String,
        item: com.hookah.platform.backend.miniapp.guest.db.MenuItemModel,
        optionId: Long? = null,
        optionName: String? = null,
        optionPriceDeltaMinor: Long = 0L,
    ) {
        val key = BotDraftCartKey(chatId = chatId, tableToken = tableToken)
        val cart = botDraftCarts.computeIfAbsent(key) { ConcurrentHashMap() }
        val existingEntry =
            cart.entries.firstOrNull { (_, existing) ->
                existing.itemId == item.id && existing.optionId == optionId
            }
        if (existingEntry != null) {
            val existing = existingEntry.value
            cart[existingEntry.key] = existing.copy(qty = existing.qty + 1)
            return
        }
        val lineId = botDraftCartOrderSeq.incrementAndGet()
        val displayName =
            if (optionName.isNullOrBlank()) {
                item.name
            } else {
                "${item.name} · ${optionName}"
            }
        cart[lineId] =
            BotDraftCartItem(
                lineId = lineId,
                itemId = item.id,
                name = displayName,
                optionId = optionId,
                optionName = optionName,
                priceMinor = item.priceMinor + optionPriceDeltaMinor,
                currency = item.currency,
                qty = 1,
                orderSeq = lineId,
            )
    }

    private fun buildBotDraftCartOptionComment(items: List<BotDraftCartItem>): String? {
        val selectedOptions = items.filter { !it.optionName.isNullOrBlank() }
        if (selectedOptions.isEmpty()) {
            return null
        }
        return buildString {
            append("Выбранные вкусы: ")
            append(
                selectedOptions.joinToString("; ") { item ->
                    "${item.name} ×${item.qty}"
                },
            )
        }
    }

    private fun buildBotDraftCartSubmitComment(
        items: List<BotDraftCartItem>,
        guestComment: String?,
    ): String? {
        val optionComment = buildBotDraftCartOptionComment(items)
        val normalizedGuestComment = guestComment?.trim()?.takeIf { it.isNotBlank() }
        return when {
            normalizedGuestComment == null && optionComment == null -> null
            normalizedGuestComment != null && optionComment != null ->
                buildString {
                    append("Комментарий гостя: ")
                    append(normalizedGuestComment)
                    append("\n")
                    append(optionComment)
                }
            normalizedGuestComment != null -> normalizedGuestComment
            else -> optionComment
        }
    }

    private fun getBotDraftCartItems(
        chatId: Long,
        tableToken: String,
    ): List<BotDraftCartItem> {
        val key = BotDraftCartKey(chatId = chatId, tableToken = tableToken)
        return botDraftCarts[key]
            ?.values
            ?.sortedWith(
                compareBy<BotDraftCartItem> { it.orderSeq }
                    .thenBy { it.itemId },
            )
            .orEmpty()
    }

    private fun clearBotDraftCart(
        chatId: Long,
        tableToken: String,
    ) {
        val key = BotDraftCartKey(chatId = chatId, tableToken = tableToken)
        botDraftCarts.remove(key)
        botDraftCartComments.remove(key)
        botMenuMessageIds.remove(key)
        botCartMessageIds.remove(key)
        botCartScreenMessageIds.remove(key)
        botSelectedTabIds.remove(key)
        botDraftCartSessionIds.remove(key)
    }

    private fun clearAllBotDraftCartsForChat(chatId: Long) {
        botDraftCarts.keys.removeIf { it.chatId == chatId }
        botDraftCartComments.keys.removeIf { it.chatId == chatId }
        botMenuMessageIds.keys.removeIf { it.chatId == chatId }
        botCartMessageIds.keys.removeIf { it.chatId == chatId }
        botCartScreenMessageIds.keys.removeIf { it.chatId == chatId }
        botBookingScreenMessageIds.keys.removeIf { it.chatId == chatId }
        botBookingEditContexts.remove(chatId)
        botSelectedTabIds.keys.removeIf { it.chatId == chatId }
        botDraftCartSessionIds.keys.removeIf { it.chatId == chatId }
        botJoinSharedAwaitingChats.remove(chatId)
    }

    private fun clearBotDraftStateForChatExceptTableToken(
        chatId: Long,
        tableToken: String,
    ) {
        val keysToClear =
            buildSet {
                addAll(botDraftCarts.keys)
                addAll(botDraftCartComments.keys)
                addAll(botMenuMessageIds.keys)
                addAll(botCartMessageIds.keys)
                addAll(botCartScreenMessageIds.keys)
                addAll(botSelectedTabIds.keys)
                addAll(botDraftCartSessionIds.keys)
            }
                .filter { key ->
                    key.chatId == chatId && key.tableToken != tableToken
                }
        keysToClear.forEach { key ->
            clearBotDraftCart(key.chatId, key.tableToken)
        }
    }

    private suspend fun clearBotCartScreenMessages(
        chatId: Long,
        key: BotDraftCartKey,
    ) {
        val messageIds = botCartScreenMessageIds.remove(key).orEmpty()
        messageIds.forEach { messageId ->
            deleteMessageBestEffort(chatId, messageId)
        }
    }

    private suspend fun sendBotCartScreenMessage(
        chatId: Long,
        key: BotDraftCartKey,
        text: String,
        replyMarkup: ReplyMarkup,
    ) {
        val messageId =
            runCatching { apiClient.sendMessage(chatId, text, replyMarkup)?.messageId }
                .onFailure { logBestEffort("telegram direct cart send", it) }
                .getOrNull()
        if (messageId != null) {
            botCartScreenMessageIds.compute(key) { _, existing -> (existing ?: emptyList()) + messageId }
            return
        }
        enqueueMessage(chatId, text, replyMarkup)
    }

    private suspend fun deleteMessageBestEffort(
        chatId: Long,
        messageId: Long,
    ) {
        runCatching {
            val payload: JsonObject =
                buildJsonObject {
                    put("chat_id", chatId)
                    put("message_id", messageId)
                }
            apiClient.callMethod("deleteMessage", payload)
        }.onFailure { logBestEffort("telegram cart message delete", it) }
    }

    private suspend fun showMainMenu(
        chatId: Long,
        from: User?,
    ) {
        botVenuePreviewContexts.remove(chatId)
        val userId = from?.id
        if (isPlatformOwner(userId)) {
            showOwnerMainMenu(chatId)
            return
        }
        val hasVenueRole = userId?.let { venueAccessRepository.hasVenueRole(it) } ?: false
        enqueueMessage(
            chatId,
            "Добро пожаловать! Выберите действие.",
            TelegramKeyboards.mainMenu(
                hasVenueRole = hasVenueRole,
                isPlatformOwner = false,
                webAppUrl = config.webAppPublicUrl,
            ),
        )
        dialogStateRepository.clear(chatId)
    }

    private suspend fun showOwnerMainMenu(chatId: Long) {
        botVenuePreviewContexts.remove(chatId)
        enqueueMessage(
            chatId,
            "Панель владельца платформы. Выберите раздел.",
            TelegramKeyboards.ownerMainMenu(),
        )
        dialogStateRepository.clear(chatId)
    }

    private suspend fun showOwnerVenueConnectionRequests(chatId: Long) {
        val requests =
            try {
                venueConnectionRequestRepository.listPendingRequests(limit = 20)
            } catch (e: DatabaseUnavailableException) {
                enqueueMessage(chatId, "База недоступна, попробуйте позже.")
                return
            }
        if (requests.isEmpty()) {
            enqueueMessage(chatId, "Новых заявок пока нет.")
            return
        }
        enqueueMessage(chatId, "📨 Заявки на подключение")
        requests.forEach { request ->
            enqueueMessage(
                chatId,
                buildOwnerVenueConnectionRequestText(request),
                TelegramKeyboards.inlineOwnerVenueConnectionRequestActions(request.id),
            )
        }
    }

    private suspend fun showOwnerSectionPlaceholder(
        chatId: Long,
        section: String,
    ) {
        enqueueMessage(
            chatId,
            "$section — следующий шаг.",
            TelegramKeyboards.ownerMainMenu(),
        )
    }

    private fun buildOwnerVenueConnectionRequestText(request: VenueConnectionRequestRecord): String {
        val createdAtText = LocalDateTime.ofInstant(request.createdAt, ZoneId.systemDefault()).format(bookingDateTimeFormatter)
        return buildString {
            append("Заявка #${request.id}")
            append("\nНазвание: ${request.venueName}")
            append("\nГород: ${request.city}")
            append("\nКонтакт: ${request.contact}")
            append("\nКомментарий: ${request.comment?.takeIf { it.isNotBlank() } ?: "—"}")
            append("\nСтатус: ${humanizeVenueConnectionRequestStatus(request.status)}")
            append("\nДата: $createdAtText")
        }
    }

    private suspend fun showActiveOrder(chatId: Long) {
        val context = resolveGuestContext(chatId) ?: return
        val currentTab = resolveCurrentBotTab(chatId, context) ?: return
        val activeOrder =
            ordersRepository.findActiveOrderDetailsForTab(
                tableId = context.table.tableId,
                tabId = currentTab.id,
            )
        if (activeOrder != null) {
            val missingItemNames =
                activeOrder.batches
                    .flatMap { batch -> batch.items }
                    .filter { item -> item.itemName.isNullOrBlank() }
                    .map { item -> item.itemId }
                    .toSet()
            val fallbackItemNames =
                if (missingItemNames.isEmpty()) {
                    emptyMap()
                } else {
                    runCatching {
                        guestMenuRepository.findItemNames(
                            venueId = context.table.venueId,
                            itemIds = missingItemNames,
                        )
                    }.getOrDefault(emptyMap())
                }
            enqueueMessage(
                chatId,
                buildGuestActiveOrderText(activeOrder, fallbackItemNames),
                TelegramKeyboards.inlineBotReorderAction(),
            )
        } else {
            enqueueMessage(chatId, "Активных заказов нет.")
        }
    }

    private suspend fun startQuickOrder(chatId: Long) {
        val context = resolveGuestContext(chatId) ?: return
        dialogStateRepository.set(chatId, DialogState(DialogStateType.QUICK_ORDER_WAIT_TEXT))
        enqueueMessage(chatId, "Опишите, что хотите заказать.")
    }

    private suspend fun proceedQuickOrderText(
        chatId: Long,
        text: String,
    ) {
        dialogStateRepository.set(
            chatId,
            DialogState(DialogStateType.QUICK_ORDER_WAIT_CONFIRM, mapOf("text" to text)),
        )
        enqueueMessage(
            chatId,
            "Отправить запрос в заведение?\n\n$text",
            TelegramKeyboards.inlineConfirmQuickOrder(),
        )
    }

    private suspend fun confirmQuickOrder(chatId: Long) {
        val context = resolveGuestContext(chatId) ?: return
        val state = dialogStateRepository.get(chatId)
        val text = state.payload["text"]
        if (text.isNullOrBlank()) {
            enqueueMessage(chatId, "Нет текста заказа, отправьте заново.")
            dialogStateRepository.set(chatId, DialogState(DialogStateType.QUICK_ORDER_WAIT_TEXT))
            return
        }
        val orderId =
            ordersRepository.getOrCreateActiveOrderId(
                context.table.tableId,
                context.table.venueId,
            )
        if (orderId == null) {
            enqueueMessage(chatId, "База недоступна, попробуйте позже.")
            return
        }
        ordersRepository.createOrderBatch(orderId, context.userId, text)
        dialogStateRepository.clear(chatId)
        enqueueMessage(chatId, "Запрос отправлен, ожидайте подтверждения.")
        notifyStaffChat(
            context,
            "🆕 Быстрый заказ (чат)\n${context.table.venueName}\n" +
                "Стол №${context.table.tableNumber}\nТекст: $text",
            VenueStaffNotificationKind.ORDERS,
        )
    }

    private suspend fun showStaffCallReasons(chatId: Long) {
        val context = resolveGuestContext(chatId) ?: return
        dialogStateRepository.clear(chatId)
        enqueueMessage(chatId, "Чем помочь?", TelegramKeyboards.inlineStaffCallReasons())
    }

    private suspend fun proceedStaffCallComment(
        chatId: Long,
        comment: String,
    ) {
        createStaffCall(chatId, StaffCallReason.OTHER, comment)
    }

    private suspend fun createStaffCall(
        chatId: Long,
        reason: StaffCallReason,
        comment: String?,
    ) {
        val context = resolveGuestContext(chatId) ?: return
        if (reason == StaffCallReason.OTHER && comment.isNullOrBlank()) {
            dialogStateRepository.set(chatId, DialogState(DialogStateType.STAFF_CALL_WAIT_COMMENT))
            enqueueMessage(chatId, "Опишите, что нужно сделать.")
            return
        }
        staffCallRepository.createStaffCall(
            context.table.venueId,
            context.table.tableId,
            context.userId,
            reason,
            comment,
        ) ?: run {
            enqueueMessage(chatId, "База недоступна, попробуйте позже.")
            return
        }
        dialogStateRepository.clear(chatId)
        enqueueMessage(
            chatId,
            "Персонал уведомлён, ожидайте.",
            TelegramKeyboards.tableContextBotFlow(context.table),
        )
        val commentPart = comment?.takeIf { it.isNotBlank() }?.let { "\nКомментарий: $it" } ?: ""
        notifyStaffChat(
            context,
            "🛎️ Вызов персонала\n${context.table.venueName}\n" +
                "Стол №${context.table.tableNumber}\nПричина: $reason$commentPart",
            VenueStaffNotificationKind.STAFF_CALLS,
        )
    }

    private suspend fun notifyStaffChat(
        context: ResolvedChatContext,
        message: String,
        kind: VenueStaffNotificationKind,
    ) {
        if (!isVenueStaffNotificationEnabled(context.table.venueId, kind)) return
        val chatId = context.table.staffChatId ?: return
        scope.launch { enqueueMessage(chatId, message) }
    }

    private suspend fun isVenueStaffNotificationEnabled(
        venueId: Long,
        kind: VenueStaffNotificationKind,
    ): Boolean {
        val settings =
            try {
                venueSettingsRepository.find(venueId)
            } catch (e: DatabaseUnavailableException) {
                logBestEffort("load venue notification settings", e)
                null
            } ?: return true
        return when (kind) {
            VenueStaffNotificationKind.ORDERS -> settings.notifyOrdersEnabled
            VenueStaffNotificationKind.STAFF_CALLS -> settings.notifyStaffCallsEnabled
            VenueStaffNotificationKind.CANCELLATIONS -> settings.notifyCancellationsEnabled
        }
    }

    private suspend fun clearContextAndAskRescan(chatId: Long) {
        val storedContext = chatContextRepository.get(chatId)
        chatContextRepository.clear(chatId)
        clearAllBotDraftCartsForChat(chatId)
        botBookingCommentDrafts.remove(chatId)
        botBookingPendingConfirmations.remove(chatId)
        botBookingEditContexts.remove(chatId)
        dialogStateRepository.clear(chatId)
        val userId = storedContext?.userId
        if (isPlatformOwner(userId)) {
            showOwnerMainMenu(chatId)
            return
        }
        when (val access = resolvePrimaryVenueBotAccess(userId)) {
            null -> Unit
            else -> {
                val menu =
                    when (access.role) {
                        VenueBotRole.OWNER -> TelegramKeyboards.venueOwnerMenu()
                        VenueBotRole.MANAGER -> TelegramKeyboards.venueManagerMenu()
                        VenueBotRole.STAFF -> TelegramKeyboards.venueStaffMenu()
                    }
                enqueueMessage(chatId, "Контекст сброшен.", menu)
                return
            }
        }
        val hasVenueRole = userId?.let { venueAccessRepository.hasVenueRole(it) } ?: false
        enqueueMessage(
            chatId,
            "Контекст сброшен. Отсканируйте QR на столе или откройте каталог.",
            TelegramKeyboards.mainMenu(
                hasVenueRole = hasVenueRole,
                isPlatformOwner = false,
                webAppUrl = config.webAppPublicUrl,
            ),
        )
    }

    private suspend fun sendFallback(
        chatId: Long,
        from: User?,
        text: String = "Используйте меню ниже.",
    ) {
        when (val access = resolvePrimaryVenueBotAccess(from?.id)) {
            null -> Unit
            else -> {
                val menu =
                    when (access.role) {
                        VenueBotRole.OWNER -> TelegramKeyboards.venueOwnerMenu()
                        VenueBotRole.MANAGER -> TelegramKeyboards.venueManagerMenu()
                        VenueBotRole.STAFF -> TelegramKeyboards.venueStaffMenu()
                    }
                enqueueMessage(chatId, text, menu)
                return
            }
        }
        if (hasOwnedVenues(from?.id)) {
            enqueueMessage(
                chatId,
                text,
                TelegramKeyboards.venueOwnerMenu(),
            )
            return
        }
        when (val contextResult = loadContext(chatId)) {
            is LoadContextResult.Loaded -> {
                enqueueMessage(
                    chatId,
                    text,
                    TelegramKeyboards.tableContext(contextResult.context.table, config.webAppPublicUrl),
                )
                return
            }
            LoadContextResult.DatabaseUnavailable -> {
                enqueueMessage(chatId, "База недоступна, попробуйте позже.")
                return
            }
            LoadContextResult.Missing -> Unit
        }
        val stored =
            try {
                chatContextRepository.get(chatId)
            } catch (e: CancellationException) {
                throw e
            } catch (e: DatabaseUnavailableException) {
                enqueueMessage(chatId, "База недоступна, попробуйте позже.")
                return
            }
        val userId = from?.id ?: stored?.userId
        if (isPlatformOwner(userId)) {
            showOwnerMainMenu(chatId)
            return
        }
        when (val access = resolvePrimaryVenueBotAccess(userId)) {
            null -> Unit
            else -> {
                val menu =
                    when (access.role) {
                        VenueBotRole.OWNER -> TelegramKeyboards.venueOwnerMenu()
                        VenueBotRole.MANAGER -> TelegramKeyboards.venueManagerMenu()
                        VenueBotRole.STAFF -> TelegramKeyboards.venueStaffMenu()
                    }
                enqueueMessage(chatId, text, menu)
                return
            }
        }
        val hasVenueRole = userId?.let { venueAccessRepository.hasVenueRole(it) } ?: false
        enqueueMessage(
            chatId,
            text,
            TelegramKeyboards.mainMenu(
                hasVenueRole = hasVenueRole,
                isPlatformOwner = false,
                webAppUrl = config.webAppPublicUrl,
            ),
        )
    }

    private fun isPlatformOwner(userId: Long?): Boolean =
        userId != null && config.platformOwnerId == userId

    private suspend fun loadContext(chatId: Long): LoadContextResult {
        val saved =
            try {
                chatContextRepository.get(chatId)
            } catch (e: CancellationException) {
                throw e
            } catch (e: DatabaseUnavailableException) {
                return LoadContextResult.DatabaseUnavailable
            } ?: return LoadContextResult.Missing
        val resolved =
            try {
                tableTokenRepository.resolve(saved.tableToken)
            } catch (e: CancellationException) {
                throw e
            } catch (e: DatabaseUnavailableException) {
                return LoadContextResult.DatabaseUnavailable
            } ?: return LoadContextResult.Missing
        return LoadContextResult.Loaded(ResolvedChatContext(resolved, saved.userId))
    }

    private suspend fun resolveGuestContext(chatId: Long): ResolvedChatContext? {
        val contextResult = loadContext(chatId)
        val context =
            when (contextResult) {
                is LoadContextResult.Loaded -> contextResult.context
                LoadContextResult.DatabaseUnavailable -> {
                    enqueueMessage(chatId, "База недоступна, попробуйте позже.")
                    return null
                }
                LoadContextResult.Missing -> {
                    askScanQr(chatId)
                    return null
                }
            }
        when (ensureGuestTableFlowAccess(context.userId, context.table.venueId)) {
            VenueGuestAccessResult.Allowed -> Unit
            VenueGuestAccessResult.Forbidden -> {
                enqueueMessage(chatId, "Заведение пока не доступно для гостей.")
                runCatching { chatContextRepository.clear(chatId) }
                return null
            }
            VenueGuestAccessResult.DatabaseUnavailable -> {
                enqueueMessage(chatId, "База недоступна, попробуйте позже.")
                return null
            }
        }
        return when (checkSubscription(context.table.venueId)) {
            SubscriptionCheckResult.Available -> context
            SubscriptionCheckResult.Blocked -> {
                enqueueMessage(chatId, subscriptionBlockedMessage)
                null
            }
            SubscriptionCheckResult.DatabaseUnavailable -> {
                enqueueMessage(chatId, "База недоступна, попробуйте позже.")
                null
            }
        }
    }

    private suspend fun askScanQr(chatId: Long) {
        enqueueMessage(chatId, "Сначала отсканируйте QR на столе.")
    }

    private fun parseCommand(text: String?): ParsedCommand? {
        if (text.isNullOrBlank() || !text.startsWith("/")) return null
        val parts = text.trim().split(Regex("\\s+"))
        if (parts.isEmpty()) return null
        val name = parts.first().substringBefore("@").lowercase(Locale.ROOT)
        return ParsedCommand(name = name, argument = parts.getOrNull(1))
    }

    private suspend fun handleLinkCommand(
        message: Message,
        code: String?,
    ) {
        val context = resolveGroupCommandContext(message) ?: return
        val chatId = context.chatId
        val userId = context.userId
        if (code.isNullOrBlank()) {
            enqueueMessage(chatId, "Использование: /link <код>. Код генерируется в режиме заведения.")
            return
        }
        val normalizedCode = StaffChatLinkCodeFormat.normalizeCode(code)
        if (normalizedCode == null) {
            enqueueMessage(chatId, "Код недействителен или истёк. Сгенерируйте новый в режиме заведения.")
            return
        }
        val consumeResult =
            staffChatLinkCodeRepository.linkAndBindWithCode(
                normalizedCode,
                userId,
                chatId,
                message.messageId,
                authorize = { connection, venueId ->
                    venueAccessRepository.hasVenueAdminOrOwner(connection, userId, venueId)
                },
                bind = { connection, venueId ->
                    venueRepository.bindStaffChatInTransaction(connection, venueId, chatId, userId)
                },
            )
        when (consumeResult) {
            is LinkAndBindResult.Success -> {
                enqueueMessage(
                    chatId,
                    "✅ Чат привязан к заведению ${consumeResult.venueName}. " +
                        "Уведомления о заказах будут приходить сюда.",
                )
            }

            is LinkAndBindResult.AlreadyBoundSameChat -> {
                enqueueMessage(
                    chatId,
                    "Этот чат уже привязан к заведению ${consumeResult.venueName}.",
                )
            }

            is LinkAndBindResult.ChatAlreadyLinked -> {
                enqueueMessage(
                    chatId,
                    "Этот чат уже привязан к другому заведению. Сначала выполните /unlink в этом чате.",
                )
            }

            is LinkAndBindResult.Unauthorized -> {
                enqueueMessage(chatId, "Недостаточно прав.")
            }

            LinkAndBindResult.InvalidOrExpired -> {
                enqueueMessage(chatId, "Код недействителен или истёк. Сгенерируйте новый в режиме заведения.")
            }

            LinkAndBindResult.DatabaseError -> {
                enqueueMessage(chatId, "База недоступна, попробуйте позже.")
            }
        }
    }

    private suspend fun handleUnlinkCommand(message: Message) {
        val context = resolveGroupCommandContext(message) ?: return
        val chatId = context.chatId
        val venue = venueRepository.findVenueByStaffChatId(chatId)
        if (venue == null) {
            enqueueMessage(chatId, "Этот чат не привязан.")
            return
        }
        val userId = context.userId
        val hasRole = venueAccessRepository.hasVenueAdminOrOwner(userId, venue.id)
        if (!hasRole) {
            enqueueMessage(chatId, "Недостаточно прав.")
            return
        }
        when (val result = venueRepository.unlinkStaffChatByChatId(chatId, userId)) {
            is UnlinkResult.Success -> {
                enqueueMessage(chatId, "✅ Чат отвязан.")
            }
            UnlinkResult.NotLinked -> {
                enqueueMessage(chatId, "Этот чат не привязан.")
            }
            UnlinkResult.DatabaseError -> {
                enqueueMessage(chatId, "База недоступна, попробуйте позже.")
            }
        }
    }

    private suspend fun handleLinkTestCommand(message: Message) {
        val context = resolveGroupCommandContext(message) ?: return
        val chatId = context.chatId
        val venue = venueRepository.findVenueByStaffChatId(chatId)
        if (venue == null) {
            enqueueMessage(chatId, "Этот чат не привязан. Сгенерируйте код и выполните /link <код>.")
            return
        }
        val userId = context.userId
        val hasRole = venueAccessRepository.hasVenueAdminOrOwner(userId, venue.id)
        if (!hasRole) {
            enqueueMessage(chatId, "Недостаточно прав.")
            return
        }
        val ts = Instant.now().toString()
        val text = "✅ Тестовое уведомление. Чат привязан к ${venue.name}. (ts=$ts)"
        enqueueMessage(chatId, text)
    }

    private suspend fun ensureChatAdmin(
        chatId: Long,
        userId: Long,
    ): ChatAdminCheckResult {
        if (!config.requireStaffChatAdmin) return ChatAdminCheckResult.Allowed
        val member = apiClient.getChatMember(chatId, userId) ?: return ChatAdminCheckResult.Failed
        return when (member.status) {
            "creator", "administrator" -> ChatAdminCheckResult.Allowed
            else -> ChatAdminCheckResult.NotAllowed
        }
    }

    private suspend fun resolveGroupCommandContext(message: Message): GroupCommandContext? {
        val chatId = message.chat.id
        if (!isGroupChat(message.chat.type)) {
            enqueueMessage(chatId, "Эту команду нужно отправить в групповом чате персонала.")
            return null
        }
        val userId = message.fromUser?.id
        if (userId == null) {
            enqueueMessage(chatId, "Недостаточно прав.")
            return null
        }
        return when (val chatAdminCheck = ensureChatAdmin(chatId, userId)) {
            ChatAdminCheckResult.Failed -> {
                enqueueMessage(chatId, "Не удалось проверить права в чате, попробуйте позже.")
                null
            }
            ChatAdminCheckResult.NotAllowed -> {
                enqueueMessage(chatId, "Недостаточно прав.")
                null
            }
            ChatAdminCheckResult.Allowed -> GroupCommandContext(chatId, userId)
        }
    }

    private suspend fun checkSubscription(venueId: Long): SubscriptionCheckResult {
        return try {
            val status = subscriptionRepository.getSubscriptionStatus(venueId)
            if (status.isBlockedForGuest()) {
                SubscriptionCheckResult.Blocked
            } else {
                SubscriptionCheckResult.Available
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: DatabaseUnavailableException) {
            SubscriptionCheckResult.DatabaseUnavailable
        }
    }

    private suspend fun ensureGuestTableFlowAccess(
        userId: Long,
        venueId: Long,
    ): VenueGuestAccessResult {
        val venueStatus =
            try {
                platformVenueRepository.getVenueDetail(venueId)?.status
            } catch (e: CancellationException) {
                throw e
            } catch (e: DatabaseUnavailableException) {
                return VenueGuestAccessResult.DatabaseUnavailable
            }
        if (venueStatus == VenueStatus.PUBLISHED) {
            return VenueGuestAccessResult.Allowed
        }
        val membership =
            try {
                venueAccessRepository.findVenueMembership(userId, venueId)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                return VenueGuestAccessResult.DatabaseUnavailable
            }
        return if (membership != null) {
            VenueGuestAccessResult.Allowed
        } else {
            VenueGuestAccessResult.Forbidden
        }
    }

    private sealed class ApplyTableTokenResult {
        object Applied : ApplyTableTokenResult()

        object Invalid : ApplyTableTokenResult()

        object Blocked : ApplyTableTokenResult()

        object VenueUnavailableForGuest : ApplyTableTokenResult()

        object DatabaseUnavailable : ApplyTableTokenResult()
    }

    private sealed class LoadContextResult {
        data class Loaded(val context: ResolvedChatContext) : LoadContextResult()

        object Missing : LoadContextResult()

        object DatabaseUnavailable : LoadContextResult()
    }

    private sealed class SubscriptionCheckResult {
        object Available : SubscriptionCheckResult()

        object Blocked : SubscriptionCheckResult()

        object DatabaseUnavailable : SubscriptionCheckResult()
    }

    private sealed class VenueGuestAccessResult {
        object Allowed : VenueGuestAccessResult()

        object Forbidden : VenueGuestAccessResult()

        object DatabaseUnavailable : VenueGuestAccessResult()
    }

    private fun isGroupChat(type: String): Boolean = type == "group" || type == "supergroup"

    private fun parseStaffCallReason(value: String): StaffCallReason =
        runCatching { StaffCallReason.valueOf(value.uppercase(Locale.ROOT)) }
            .getOrDefault(StaffCallReason.OTHER)

    private suspend fun safeUpsertUser(user: User) {
        runCatching { userRepository.upsert(user) }
            .onFailure { logBestEffort("user upsert", it) }
    }

    private suspend fun enqueueMessage(
        chatId: Long,
        text: String,
        replyMarkup: ReplyMarkup? = null,
    ) {
        runCatching {
            outboxEnqueuer.enqueueSendMessage(chatId, text, replyMarkup)
        }.onFailure { logBestEffort("outbox enqueue", it) }
    }

    private suspend fun enqueuePhoto(
        chatId: Long,
        photoUrl: String,
        caption: String? = null,
        replyMarkup: ReplyMarkup? = null,
    ) {
        runCatching {
            outboxEnqueuer.enqueueSendPhoto(chatId, photoUrl, caption, replyMarkup)
        }.onFailure { logBestEffort("outbox enqueue photo", it) }
    }

    private suspend fun enqueueDocument(
        chatId: Long,
        document: String,
        caption: String? = null,
        replyMarkup: ReplyMarkup? = null,
    ) {
        runCatching {
            outboxEnqueuer.enqueueSendDocument(chatId, document, caption, replyMarkup)
        }.onFailure { logBestEffort("outbox enqueue document", it) }
    }

    private suspend fun enqueueCallbackAnswer(
        chatId: Long?,
        callbackQueryId: String,
    ) {
        val outboxChatId = chatId ?: 0L
        runCatching {
            outboxEnqueuer.enqueueAnswerCallbackQuery(outboxChatId, callbackQueryId)
        }.onFailure { logBestEffort("outbox enqueue callback", it) }
    }

    private fun logBestEffort(
        operation: String,
        throwable: Throwable,
    ) {
        logger.warn("Best-effort {} failed: {}", operation, sanitizeTelegramForLog(throwable.message))
        logger.debugTelegramException(throwable) { "Best-effort exception for $operation" }
    }
}

private data class ParsedCommand(
    val name: String,
    val argument: String?,
)

private sealed interface ChatAdminCheckResult {
    data object Allowed : ChatAdminCheckResult

    data object NotAllowed : ChatAdminCheckResult

    data object Failed : ChatAdminCheckResult
}

private data class GroupCommandContext(
    val chatId: Long,
    val userId: Long,
)
