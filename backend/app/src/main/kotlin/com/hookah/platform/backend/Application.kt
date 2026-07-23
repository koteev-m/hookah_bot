package com.hookah.platform.backend

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import com.hookah.platform.backend.ai.AiAssistantConfig
import com.hookah.platform.backend.ai.AiAssistantService
import com.hookah.platform.backend.ai.AiContextAssembler
import com.hookah.platform.backend.ai.AiToolRegistry
import com.hookah.platform.backend.ai.AuditLogAiAuditLogger
import com.hookah.platform.backend.ai.PromotionDiagnosticsTool
import com.hookah.platform.backend.ai.VenueSummaryTool
import com.hookah.platform.backend.ai.createAiAssistantClient
import com.hookah.platform.backend.analytics.AnalyticsEventRepository
import com.hookah.platform.backend.api.ApiError
import com.hookah.platform.backend.api.ApiErrorCodes
import com.hookah.platform.backend.api.ApiErrorEnvelope
import com.hookah.platform.backend.api.ApiException
import com.hookah.platform.backend.api.ApiHeaders
import com.hookah.platform.backend.api.DatabaseUnavailableException
import com.hookah.platform.backend.billing.BillingAdjustmentRepository
import com.hookah.platform.backend.billing.BillingConfig
import com.hookah.platform.backend.billing.BillingInvoiceRepository
import com.hookah.platform.backend.billing.BillingNotificationRepository
import com.hookah.platform.backend.billing.BillingOverviewService
import com.hookah.platform.backend.billing.BillingPaymentRepository
import com.hookah.platform.backend.billing.BillingProviderRegistry
import com.hookah.platform.backend.billing.BillingService
import com.hookah.platform.backend.billing.FakeBillingProvider
import com.hookah.platform.backend.billing.GenericHmacBillingProvider
import com.hookah.platform.backend.billing.GenericHmacBillingProviderConfig
import com.hookah.platform.backend.billing.billingWebhookRoutes
import com.hookah.platform.backend.billing.subscription.SubscriptionBillingConfig
import com.hookah.platform.backend.billing.subscription.SubscriptionBillingEngine
import com.hookah.platform.backend.billing.subscription.SubscriptionBillingHooks
import com.hookah.platform.backend.billing.subscription.SubscriptionBillingJob
import com.hookah.platform.backend.billing.subscription.SubscriptionBillingVenueRepository
import com.hookah.platform.backend.db.DatabaseFactory
import com.hookah.platform.backend.db.DbConfig
import com.hookah.platform.backend.metrics.AppMetrics
import com.hookah.platform.backend.miniapp.auth.miniAppAuthRoutes
import com.hookah.platform.backend.miniapp.guest.BookingExpiryWorker
import com.hookah.platform.backend.miniapp.guest.BookingExpiryWorkerConfig
import com.hookah.platform.backend.miniapp.guest.BookingReminderWorker
import com.hookah.platform.backend.miniapp.guest.BookingReminderWorkerConfig
import com.hookah.platform.backend.miniapp.guest.GuestRateLimitConfig
import com.hookah.platform.backend.miniapp.guest.InMemoryRateLimiter
import com.hookah.platform.backend.miniapp.guest.RepeatOrderResolver
import com.hookah.platform.backend.miniapp.guest.TableSessionCleanupWorker
import com.hookah.platform.backend.miniapp.guest.TableSessionConfig
import com.hookah.platform.backend.miniapp.guest.db.GuestBookingRepository
import com.hookah.platform.backend.miniapp.guest.db.GuestFavoritesRepository
import com.hookah.platform.backend.miniapp.guest.db.GuestMenuRepository
import com.hookah.platform.backend.miniapp.guest.db.GuestTabsRepository
import com.hookah.platform.backend.miniapp.guest.db.GuestVenueRepository
import com.hookah.platform.backend.miniapp.guest.db.TableSessionRepository
import com.hookah.platform.backend.miniapp.guest.db.VisitFeedbackRepository
import com.hookah.platform.backend.miniapp.guest.db.VisitRepository
import com.hookah.platform.backend.miniapp.guest.guestBookingRoutes
import com.hookah.platform.backend.miniapp.guest.guestFavoritesRoutes
import com.hookah.platform.backend.miniapp.guest.guestOrderRoutes
import com.hookah.platform.backend.miniapp.guest.guestStaffCallRoutes
import com.hookah.platform.backend.miniapp.guest.guestTableResolveRoutes
import com.hookah.platform.backend.miniapp.guest.guestTabsRoutes
import com.hookah.platform.backend.miniapp.guest.guestVenueInfoMediaRoutes
import com.hookah.platform.backend.miniapp.guest.guestVenueRoutes
import com.hookah.platform.backend.miniapp.guest.guestVisitRoutes
import com.hookah.platform.backend.miniapp.session.SessionTokenConfig
import com.hookah.platform.backend.miniapp.session.SessionTokenService
import com.hookah.platform.backend.miniapp.shift.ShiftExtensionRepository
import com.hookah.platform.backend.miniapp.shift.guestShiftExtensionRoutes
import com.hookah.platform.backend.miniapp.shift.venueShiftExtensionRoutes
import com.hookah.platform.backend.miniapp.subscription.db.SubscriptionRepository
import com.hookah.platform.backend.miniapp.venue.AuditLogRepository
import com.hookah.platform.backend.miniapp.venue.bookings.venueBookingRoutes
import com.hookah.platform.backend.miniapp.venue.feedback.venueFeedbackRoutes
import com.hookah.platform.backend.miniapp.venue.location.VenueLocationProvider
import com.hookah.platform.backend.miniapp.venue.location.createVenueLocationProvider
import com.hookah.platform.backend.miniapp.venue.menu.VenueMenuRepository
import com.hookah.platform.backend.miniapp.venue.menu.venueMenuRoutes
import com.hookah.platform.backend.miniapp.venue.orders.VenueOrdersRepository
import com.hookah.platform.backend.miniapp.venue.orders.venueOrderRoutes
import com.hookah.platform.backend.miniapp.venue.staff.StaffInviteConfig
import com.hookah.platform.backend.miniapp.venue.staff.StaffInviteRepository
import com.hookah.platform.backend.miniapp.venue.staff.VenueStaffProfileRepository
import com.hookah.platform.backend.miniapp.venue.staff.VenueStaffRepository
import com.hookah.platform.backend.miniapp.venue.stats.venueStatsRoutes
import com.hookah.platform.backend.miniapp.venue.tables.VenueTableRepository
import com.hookah.platform.backend.miniapp.venue.tables.venueTableRoutes
import com.hookah.platform.backend.miniapp.venue.venueBillingRoutes
import com.hookah.platform.backend.miniapp.venue.venueRoutes
import com.hookah.platform.backend.miniapp.venue.venueStaffCallRoutes
import com.hookah.platform.backend.miniapp.venue.venueStaffRoutes
import com.hookah.platform.backend.platform.PlatformConfig
import com.hookah.platform.backend.platform.PlatformSubscriptionSettingsRepository
import com.hookah.platform.backend.platform.PlatformUserRepository
import com.hookah.platform.backend.platform.PlatformVenueMemberRepository
import com.hookah.platform.backend.platform.PlatformVenueRepository
import com.hookah.platform.backend.platform.VenueOwnerAccountRepository
import com.hookah.platform.backend.platform.platformRoutes
import com.hookah.platform.backend.security.constantTimeEquals
import com.hookah.platform.backend.support.SupportThreadRepository
import com.hookah.platform.backend.support.guestSupportRoutes
import com.hookah.platform.backend.support.venueSupportRoutes
import com.hookah.platform.backend.telegram.InMemoryTelegramRateLimiter
import com.hookah.platform.backend.telegram.StaffBillUpdateNotifier
import com.hookah.platform.backend.telegram.StaffChatNotifier
import com.hookah.platform.backend.telegram.TableContext
import com.hookah.platform.backend.telegram.TelegramApiClient
import com.hookah.platform.backend.telegram.TelegramBotConfig
import com.hookah.platform.backend.telegram.TelegramBotRouter
import com.hookah.platform.backend.telegram.TelegramCallResult
import com.hookah.platform.backend.telegram.TelegramDownloadedFile
import com.hookah.platform.backend.telegram.TelegramInboundUpdateWorker
import com.hookah.platform.backend.telegram.TelegramOutboxEnqueuer
import com.hookah.platform.backend.telegram.TelegramOutboxWorker
import com.hookah.platform.backend.telegram.TelegramUpdate
import com.hookah.platform.backend.telegram.buildWebAppUrl
import com.hookah.platform.backend.telegram.db.ChatContextRepository
import com.hookah.platform.backend.telegram.db.DialogStateRepository
import com.hookah.platform.backend.telegram.db.IdempotencyRepository
import com.hookah.platform.backend.telegram.db.LoyaltyRepository
import com.hookah.platform.backend.telegram.db.OrdersRepository
import com.hookah.platform.backend.telegram.db.PromotionApplicationRepository
import com.hookah.platform.backend.telegram.db.PromotionPlacementRepository
import com.hookah.platform.backend.telegram.db.PromotionVenuePlacementRepository
import com.hookah.platform.backend.telegram.db.StaffCallRepository
import com.hookah.platform.backend.telegram.db.StaffChatLinkCodeRepository
import com.hookah.platform.backend.telegram.db.StaffChatNotificationRepository
import com.hookah.platform.backend.telegram.db.TableTokenRepository
import com.hookah.platform.backend.telegram.db.TelegramInboundUpdateQueueRepository
import com.hookah.platform.backend.telegram.db.TelegramOutboxRepository
import com.hookah.platform.backend.telegram.db.TelegramVenueContextRepository
import com.hookah.platform.backend.telegram.db.UserRepository
import com.hookah.platform.backend.telegram.db.VenueAccessRepository
import com.hookah.platform.backend.telegram.db.VenueBookingHoursRepository
import com.hookah.platform.backend.telegram.db.VenueConnectionRequestRepository
import com.hookah.platform.backend.telegram.db.VenueInfoSectionMediaRepository
import com.hookah.platform.backend.telegram.db.VenueInfoSectionsRepository
import com.hookah.platform.backend.telegram.db.VenueMenuSectionImagesRepository
import com.hookah.platform.backend.telegram.db.VenuePromotionMediaRepository
import com.hookah.platform.backend.telegram.db.VenuePromotionRepository
import com.hookah.platform.backend.telegram.db.VenuePromotionRuleRepository
import com.hookah.platform.backend.telegram.db.VenueRepository
import com.hookah.platform.backend.telegram.db.VenueSettingsRepository
import com.hookah.platform.backend.telegram.db.VenueStatsRepository
import com.hookah.platform.backend.telegram.debugTelegramException
import com.hookah.platform.backend.telegram.sanitizeTelegramForLog
import com.hookah.platform.backend.tools.retryWithBackoff
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.java.Java
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.request
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.ApplicationStarted
import io.ktor.server.application.ApplicationStopped
import io.ktor.server.application.ApplicationStopping
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.auth.Authentication
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.jwt.JWTPrincipal
import io.ktor.server.auth.jwt.jwt
import io.ktor.server.config.ApplicationConfig
import io.ktor.server.http.content.staticFiles
import io.ktor.server.metrics.micrometer.MicrometerMetrics
import io.ktor.server.plugins.BadRequestException
import io.ktor.server.plugins.ContentTransformationException
import io.ktor.server.plugins.callid.CallId
import io.ktor.server.plugins.callid.callId
import io.ktor.server.plugins.callid.callIdMdc
import io.ktor.server.plugins.calllogging.CallLogging
import io.ktor.server.plugins.cors.routing.CORS
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.plugins.statuspages.exception
import io.ktor.server.request.httpMethod
import io.ktor.server.request.path
import io.ktor.server.request.queryString
import io.ktor.server.request.receiveText
import io.ktor.server.request.uri
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytes
import io.ktor.server.response.respondFile
import io.ktor.server.response.respondRedirect
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.head
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.slf4j.LoggerFactory
import java.io.File
import java.net.URI
import java.time.Duration
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.UUID
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation as ServerContentNegotiation

private val logger = LoggerFactory.getLogger("Application")

@Serializable
private data class HealthResponse(val status: String)

@Serializable
private data class DbHealthResponse(val status: String, val message: String? = null)

@Serializable
private data class TelegramQueueHealthResponse(val status: String, val depth: Long? = null)

@Serializable
private data class VersionResponse(
    val service: String,
    val env: String,
    val version: String,
    val time: String,
)

internal data class ModuleOverrides(
    val tableTokenResolver: (suspend (String) -> TableContext?)? = null,
    val telegramFileDownloader: (suspend (String) -> TelegramDownloadedFile?)? = null,
    val staffChatNotifier: StaffChatNotifier? = null,
    val staffBillUpdateNotifier: StaffBillUpdateNotifier? = null,
    val venueLocationProvider: VenueLocationProvider? = null,
)

private fun ApplicationCall.isApiRequest(): Boolean {
    val p = request.path()
    return p == "/api" || p.startsWith("/api/")
}

private suspend fun ApplicationCall.respondApiError(
    status: HttpStatusCode,
    code: String,
    message: String,
    details: JsonObject? = null,
) {
    respond(
        status,
        ApiErrorEnvelope(
            error = ApiError(code = code, message = message, details = details),
            requestId = callId,
        ),
    )
}

private suspend fun ApplicationCall.respondInvalidRequestBody() {
    respondApiError(
        status = HttpStatusCode.BadRequest,
        code = ApiErrorCodes.INVALID_INPUT,
        message = "Invalid request body",
    )
}

fun Application.module() {
    moduleWithOverrides(ModuleOverrides())
}

internal fun Application.moduleWithOverrides(overrides: ModuleOverrides) {
    val json =
        Json {
            ignoreUnknownKeys = true
            prettyPrint = false
        }

    val telegramJson =
        Json {
            ignoreUnknownKeys = true
            prettyPrint = false
        }

    val appConfig = environment.config
    val appEnv =
        (
            appConfig.optionalString("app.env")
                ?: System.getenv("APP_ENV")
                ?: "dev"
        ).trim().lowercase(Locale.ROOT)
    val dbConfig = DbConfig.from(appConfig)
    val appVersion = appConfig.optionalString("app.version") ?: "dev"
    val miniAppDevServerUrl = appConfig.optionalString("miniapp.devServerUrl")?.takeIf { it.isNotBlank() }
    val miniAppStaticDir = appConfig.optionalString("miniapp.staticDir")?.takeIf { it.isNotBlank() }
    val sessionTokenConfig = SessionTokenConfig.from(appConfig, appEnv)
    val sessionTokenService = SessionTokenService(sessionTokenConfig)
    val billingConfig = BillingConfig.from(appConfig, appEnv)
    val genericBillingConfig = GenericHmacBillingProviderConfig.from(appConfig)
    val subscriptionBillingConfig = SubscriptionBillingConfig.from(appConfig)
    val guestRateLimitConfig = GuestRateLimitConfig.from(appConfig)
    val tableSessionConfig = TableSessionConfig.from(appConfig)
    val bookingExpiryWorkerConfig = BookingExpiryWorkerConfig.from(appConfig)
    val bookingReminderWorkerConfig = BookingReminderWorkerConfig.from(appConfig)
    val aiAssistantConfig = AiAssistantConfig.from(appConfig)
    val guestRateLimiter = InMemoryRateLimiter()
    val platformConfig = PlatformConfig.from(appConfig)
    val appMetrics = AppMetrics()

    val httpClient =
        HttpClient(Java) {
            install(ContentNegotiation) {
                json(json)
            }
        }
    val venueLocationProvider =
        overrides.venueLocationProvider ?: createVenueLocationProvider(appConfig, httpClient, json)

    val dataSource = DatabaseFactory.init(dbConfig)
    val venueRepository = VenueRepository(dataSource)
    val venueBookingHoursRepository = VenueBookingHoursRepository(dataSource)
    val venueInfoSectionsRepository = VenueInfoSectionsRepository(dataSource)
    val venueInfoSectionMediaRepository = VenueInfoSectionMediaRepository(dataSource)
    val venueMenuSectionImagesRepository = VenueMenuSectionImagesRepository(dataSource)
    val venueConnectionRequestRepository = VenueConnectionRequestRepository(dataSource)
    val userRepository = UserRepository(dataSource)
    val guestVenueRepository = GuestVenueRepository(dataSource)
    val guestFavoritesRepository = GuestFavoritesRepository(dataSource)
    val visitRepository = VisitRepository(dataSource)
    val visitFeedbackRepository = VisitFeedbackRepository(dataSource)
    val guestBookingRepository = GuestBookingRepository(dataSource, visitRepository)
    val supportThreadRepository = SupportThreadRepository(dataSource)
    val guestMenuRepository = GuestMenuRepository(dataSource)
    val guestTabsRepository = GuestTabsRepository(dataSource)
    val analyticsEventRepository = AnalyticsEventRepository(dataSource)
    val subscriptionRepository = SubscriptionRepository(dataSource, analyticsEventRepository)
    val repeatOrderResolver =
        RepeatOrderResolver(
            visitRepository = visitRepository,
            guestMenuRepository = guestMenuRepository,
            guestTabsRepository = guestTabsRepository,
            guestVenueRepository = guestVenueRepository,
            subscriptionRepository = subscriptionRepository,
        )
    val venuePromotionRepository = VenuePromotionRepository(dataSource)
    val venuePromotionMediaRepository = VenuePromotionMediaRepository(dataSource)
    val venuePromotionRuleRepository = VenuePromotionRuleRepository(dataSource)
    val promotionPlacementRepository = PromotionPlacementRepository(dataSource)
    val promotionVenuePlacementRepository = PromotionVenuePlacementRepository(dataSource)
    val promotionApplicationRepository = PromotionApplicationRepository(dataSource)
    val loyaltyRepository = LoyaltyRepository(dataSource)
    val ordersRepository =
        OrdersRepository(
            dataSource = dataSource,
            analyticsEventRepository = analyticsEventRepository,
            promotionApplicationRepository = promotionApplicationRepository,
            venuePromotionRuleRepository = venuePromotionRuleRepository,
            loyaltyRepository = loyaltyRepository,
        )
    val venueOrdersRepository =
        VenueOrdersRepository(
            dataSource,
            analyticsEventRepository,
            visitRepository,
            visitFeedbackRepository,
            loyaltyRepository,
        )
    val venueSettingsRepository = VenueSettingsRepository(dataSource)
    val venueStatsRepository = VenueStatsRepository(dataSource)
    val venueMenuRepository = VenueMenuRepository(dataSource)
    val venueTableRepository = VenueTableRepository(dataSource)
    val staffCallRepository = StaffCallRepository(dataSource)
    val tableSessionRepository = TableSessionRepository(dataSource, analyticsEventRepository)
    val shiftExtensionRepository = ShiftExtensionRepository(dataSource)
    val tableTokenRepository = TableTokenRepository(dataSource)
    val auditLogRepository = AuditLogRepository(dataSource, json)
    val aiAssistantService =
        AiAssistantService(
            config = aiAssistantConfig,
            client = createAiAssistantClient(aiAssistantConfig, httpClient, json),
            toolRegistry =
                AiToolRegistry(
                    promotionDiagnosticsTool =
                        PromotionDiagnosticsTool(
                            promotionRepository = venuePromotionRepository,
                            ruleRepository = venuePromotionRuleRepository,
                        ),
                    venueSummaryTool =
                        VenueSummaryTool(
                            venuePromotionRepository = venuePromotionRepository,
                            promotionPlacementRepository = promotionPlacementRepository,
                            promotionVenuePlacementRepository = promotionVenuePlacementRepository,
                            loyaltyRepository = loyaltyRepository,
                            venueSettingsRepository = venueSettingsRepository,
                            visitFeedbackRepository = visitFeedbackRepository,
                            venueStatsRepository = venueStatsRepository,
                            venueOrdersRepository = venueOrdersRepository,
                            staffCallRepository = staffCallRepository,
                        ),
                ),
            contextAssembler = AiContextAssembler(),
            auditLogger = AuditLogAiAuditLogger(auditLogRepository),
        )
    val platformVenueRepository = PlatformVenueRepository(dataSource)
    val subscriptionSettingsRepository = PlatformSubscriptionSettingsRepository(dataSource)
    val platformUserRepository = PlatformUserRepository(dataSource)
    val platformVenueMemberRepository = PlatformVenueMemberRepository(dataSource)
    val venueOwnerAccountRepository = VenueOwnerAccountRepository(dataSource)
    val billingInvoiceRepository = BillingInvoiceRepository(dataSource)
    val billingAdjustmentRepository = BillingAdjustmentRepository(dataSource)
    val billingPaymentRepository = BillingPaymentRepository(dataSource)
    val billingNotificationRepository = BillingNotificationRepository(dataSource)
    val billingProviderRegistry =
        BillingProviderRegistry(
            listOf(
                FakeBillingProvider(),
                GenericHmacBillingProvider(genericBillingConfig),
            ),
        )
    val resolvedBillingProvider = billingProviderRegistry.resolve(billingConfig.normalizedProvider)
    if (resolvedBillingProvider == null) {
        if (appEnv == "prod" || appEnv == "production") {
            throw IllegalStateException("Unknown billing provider '${billingConfig.provider}' in production")
        }
        logger.warn("Unknown billing provider '{}', falling back to FAKE", billingConfig.provider)
    }
    if (resolvedBillingProvider is GenericHmacBillingProvider) {
        genericBillingConfig.validateRequired()
    }
    val billingProvider = resolvedBillingProvider ?: FakeBillingProvider()
    val subscriptionBillingHooks = SubscriptionBillingHooks(subscriptionRepository)
    val billingService =
        BillingService(
            provider = billingProvider,
            invoiceRepository = billingInvoiceRepository,
            paymentRepository = billingPaymentRepository,
            hooks = subscriptionBillingHooks,
        )
    val billingOverviewService =
        BillingOverviewService(
            subscriptionRepository = subscriptionRepository,
            settingsRepository = subscriptionSettingsRepository,
            invoiceRepository = billingInvoiceRepository,
            adjustmentRepository = billingAdjustmentRepository,
            billingService = billingService,
            provider = billingProvider,
            config = subscriptionBillingConfig,
        )
    val subscriptionBillingEngine =
        SubscriptionBillingEngine(
            dataSource = dataSource,
            venueRepository = SubscriptionBillingVenueRepository(dataSource),
            settingsRepository = subscriptionSettingsRepository,
            billingService = billingService,
            invoiceRepository = billingInvoiceRepository,
            adjustmentRepository = billingAdjustmentRepository,
            notificationRepository = billingNotificationRepository,
            subscriptionRepository = subscriptionRepository,
            auditLogRepository = auditLogRepository,
            config = subscriptionBillingConfig,
            platformOwnerUserId = platformConfig.ownerUserId,
            json = json,
        )
    val subscriptionBillingJob =
        SubscriptionBillingJob(
            engine = subscriptionBillingEngine,
            intervalSeconds = subscriptionBillingConfig.intervalSeconds,
        )
    val tableTokenResolver = overrides.tableTokenResolver ?: tableTokenRepository::resolve

    if (dataSource != null) {
        logger.info("DB enabled with jdbcUrl={}", redactJdbcUrl(dbConfig.jdbcUrl.orEmpty()))
    }

    val telegramConfig = TelegramBotConfig.from(appConfig, appEnv)
    if (
        (appEnv == "prod" || appEnv == "production") &&
        telegramConfig.enabled &&
        telegramConfig.mode == TelegramBotConfig.Mode.WEBHOOK &&
        dataSource == null
    ) {
        logger.error("Webhook mode requires database configuration in production")
        throw IllegalStateException("Webhook mode requires database configuration in production")
    }
    val staffInviteConfig = StaffInviteConfig.from(appConfig, appEnv)
    var telegramScope: CoroutineScope? = null
    var telegramApiClient: TelegramApiClient? = null
    var telegramRouter: TelegramBotRouter? = null
    var telegramWebhookWorkerScope: CoroutineScope? = null
    var telegramWebhookWorkerJob: Job? = null
    var telegramOutboxWorkerScope: CoroutineScope? = null
    var telegramOutboxWorkerJob: Job? = null
    val telegramInboundUpdateQueueRepository = TelegramInboundUpdateQueueRepository(dataSource)
    val telegramOutboxRepository = TelegramOutboxRepository(dataSource)
    val telegramOutboxEnqueuer = TelegramOutboxEnqueuer(telegramOutboxRepository, telegramJson)
    val staffChatNotifierScope =
        CoroutineScope(
            SupervisorJob() + Dispatchers.IO + CoroutineName("staff-chat-notifier"),
        )
    val subscriptionBillingScope =
        CoroutineScope(
            SupervisorJob() + Dispatchers.IO + CoroutineName("subscription-billing"),
        )
    val tableSessionCleanupScope =
        CoroutineScope(
            SupervisorJob() + Dispatchers.IO + CoroutineName("table-session-cleanup"),
        )
    val bookingExpiryScope =
        CoroutineScope(
            SupervisorJob() + Dispatchers.IO + CoroutineName("booking-expiry"),
        )
    val bookingReminderScope =
        CoroutineScope(
            SupervisorJob() + Dispatchers.IO + CoroutineName("booking-reminders"),
        )
    val tableSessionCleanupWorker =
        TableSessionCleanupWorker(
            repository = tableSessionRepository,
            intervalMillis = tableSessionConfig.cleanupInterval.toMillis(),
            scope = tableSessionCleanupScope,
        )
    var tableSessionCleanupJob: Job? = null
    val bookingExpiryWorker =
        BookingExpiryWorker(
            repository = guestBookingRepository,
            interval = bookingExpiryWorkerConfig.interval,
            batchSize = bookingExpiryWorkerConfig.batchSize,
            scope = bookingExpiryScope,
        )
    var bookingExpiryJob: Job? = null
    var bookingReminderJob: Job? = null
    val staffChatLinkCodeRepository =
        StaffChatLinkCodeRepository(
            dataSource = dataSource,
            pepper = telegramConfig.staffChatLinkSecretPepper,
            ttlSeconds = telegramConfig.staffChatLinkTtlSeconds,
        )
    val venueAccessRepository = VenueAccessRepository(dataSource)
    val telegramVenueContextRepository = TelegramVenueContextRepository(dataSource)
    val venueStaffRepository = VenueStaffRepository(dataSource)
    val venueStaffProfileRepository = VenueStaffProfileRepository(dataSource, json)
    val bookingReminderWorker =
        BookingReminderWorker(
            repository = guestBookingRepository,
            outboxEnqueuer = telegramOutboxEnqueuer,
            venueSettingsRepository = venueSettingsRepository,
            interval = bookingReminderWorkerConfig.interval,
            batchSize = bookingReminderWorkerConfig.batchSize,
            scope = bookingReminderScope,
        )
    val staffInviteRepository =
        StaffInviteRepository(
            dataSource = dataSource,
            pepper = staffInviteConfig.secretPepper,
        )
    val staffChatNotificationRepository = StaffChatNotificationRepository(dataSource)
    val staffChatNotifier =
        StaffChatNotifier(
            venueRepository = venueRepository,
            notificationRepository = staffChatNotificationRepository,
            venueSettingsRepository = venueSettingsRepository,
            isTelegramActive = { telegramConfig.enabled && !telegramConfig.token.isNullOrBlank() },
            scope = staffChatNotifierScope,
            json = telegramJson,
            venueMiniAppUrl = { venueId ->
                telegramConfig.webAppPublicUrl?.let { url ->
                    buildWebAppUrl(
                        url,
                        mapOf(
                            "mode" to "venue",
                            "venueId" to venueId.toString(),
                        ),
                    )
                }
            },
            venueOrdersRepository = venueOrdersRepository,
            staffCallRepository = staffCallRepository,
        )
    val guestStaffChatNotifier = overrides.staffChatNotifier ?: staffChatNotifier
    if (telegramConfig.enabled && !telegramConfig.token.isNullOrBlank()) {
        telegramScope =
            CoroutineScope(
                SupervisorJob() + Dispatchers.Default + CoroutineName("telegram-bot"),
            )
        val botScope = telegramScope!!
        val requestTimeoutMs = (telegramConfig.longPollingTimeoutSeconds + 15L) * 1000
        val socketTimeoutMs = (telegramConfig.longPollingTimeoutSeconds + 10L) * 1000
        telegramApiClient =
            TelegramApiClient(
                token = telegramConfig.token!!,
                client =
                    HttpClient(Java) {
                        install(ContentNegotiation) { json(telegramJson) }
                        install(HttpTimeout) {
                            connectTimeoutMillis = 10_000
                            socketTimeoutMillis = socketTimeoutMs
                            requestTimeoutMillis = requestTimeoutMs
                        }
                    },
                json = telegramJson,
            )
        telegramRouter =
            TelegramBotRouter(
                config = telegramConfig,
                apiClient = telegramApiClient,
                outboxEnqueuer = telegramOutboxEnqueuer,
                idempotencyRepository = IdempotencyRepository(dataSource),
                userRepository = userRepository,
                tableTokenRepository = tableTokenRepository,
                chatContextRepository = ChatContextRepository(dataSource),
                dialogStateRepository = DialogStateRepository(dataSource, telegramJson),
                ordersRepository = ordersRepository,
                staffCallRepository = staffCallRepository,
                staffChatLinkCodeRepository = staffChatLinkCodeRepository,
                guestBookingRepository = guestBookingRepository,
                venueRepository = venueRepository,
                venueBookingHoursRepository = venueBookingHoursRepository,
                venueMenuSectionImagesRepository = venueMenuSectionImagesRepository,
                venueMenuRepository = venueMenuRepository,
                venueTableRepository = venueTableRepository,
                venueAccessRepository = venueAccessRepository,
                venueContextRepository = telegramVenueContextRepository,
                venueStaffRepository = venueStaffRepository,
                staffInviteRepository = staffInviteRepository,
                staffInviteConfig = staffInviteConfig,
                subscriptionRepository = subscriptionRepository,
                guestMenuRepository = guestMenuRepository,
                tableSessionRepository = tableSessionRepository,
                guestTabsRepository = guestTabsRepository,
                platformVenueRepository = platformVenueRepository,
                platformSubscriptionSettingsRepository = subscriptionSettingsRepository,
                platformVenueMemberRepository = platformVenueMemberRepository,
                venueOwnerAccountRepository = venueOwnerAccountRepository,
                tableSessionTtl = tableSessionConfig.ttl,
                json = telegramJson,
                venueConnectionRequestRepository = venueConnectionRequestRepository,
                scope = botScope,
                venueInfoSectionsRepository = venueInfoSectionsRepository,
                venueInfoSectionMediaRepository = venueInfoSectionMediaRepository,
                venueOrdersRepository = venueOrdersRepository,
                venueStatsRepository = venueStatsRepository,
                venuePromotionRepository = venuePromotionRepository,
                venuePromotionMediaRepository = venuePromotionMediaRepository,
                venuePromotionRuleRepository = venuePromotionRuleRepository,
                promotionPlacementRepository = promotionPlacementRepository,
                promotionVenuePlacementRepository = promotionVenuePlacementRepository,
                loyaltyRepository = loyaltyRepository,
                venueSettingsRepository = venueSettingsRepository,
                visitRepository = visitRepository,
                guestFavoritesRepository = guestFavoritesRepository,
                visitFeedbackRepository = visitFeedbackRepository,
                aiAssistantService = aiAssistantService,
                staffChatNotifier = staffChatNotifier,
                auditLogRepository = auditLogRepository,
                shiftExtensionRepository = shiftExtensionRepository,
                supportThreadRepository = supportThreadRepository,
                bookingRemindersEnabled = bookingReminderWorkerConfig.enabled,
                repeatOrderResolver = repeatOrderResolver,
            )

        if (dataSource != null) {
            telegramOutboxWorkerScope =
                CoroutineScope(
                    SupervisorJob() + Dispatchers.IO + CoroutineName("telegram-outbox-worker"),
                )
            val outboxRateLimiter =
                InMemoryTelegramRateLimiter(
                    minInterval = Duration.ofMillis(telegramConfig.outbox.perChatMinIntervalMillis),
                )
            val outboxWorker =
                TelegramOutboxWorker(
                    repository = telegramOutboxRepository,
                    apiClientProvider = { telegramApiClient },
                    json = telegramJson,
                    rateLimiter = outboxRateLimiter,
                    config = telegramConfig.outbox,
                    scope = telegramOutboxWorkerScope!!,
                    metrics = appMetrics,
                )
            telegramOutboxWorkerJob = outboxWorker.start()
        } else {
            logger.warn("Telegram outbox worker disabled: database is not configured")
        }

        when (telegramConfig.mode) {
            TelegramBotConfig.Mode.LONG_POLLING -> {
                val router = telegramRouter!!
                botScope.launch {
                    var offset: Long? = null
                    while (isActive) {
                        val updates: List<TelegramUpdate> =
                            try {
                                retryWithBackoff(
                                    maxAttempts = 3,
                                    maxDelayMillis = 2000,
                                    jitterRatio = 0.2,
                                    shouldRetry = { e -> e !is CancellationException },
                                ) {
                                    telegramApiClient.getUpdates(
                                        offset,
                                        telegramConfig.longPollingTimeoutSeconds,
                                    )
                                }
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                logger.warn("Telegram long polling error: {}", sanitizeTelegramForLog(e.message))
                                logger.debugTelegramException(e) { "Telegram long polling exception" }
                                delay(1000)
                                continue
                            }

                        val sortedUpdates = updates.sortedBy { it.updateId }
                        for (update in sortedUpdates) {
                            try {
                                router.process(update)
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                logger.warn(
                                    "Telegram update processing failed id={}: {}",
                                    update.updateId,
                                    sanitizeTelegramForLog(e.message),
                                )
                                logger.debugTelegramException(e) {
                                    "Telegram update processing exception id=${update.updateId}"
                                }
                            } finally {
                                offset = update.updateId + 1
                            }
                        }
                    }
                }
            }

            TelegramBotConfig.Mode.WEBHOOK -> {
                logger.info("Telegram webhook mode enabled at {}", telegramConfig.webhookPath)
                if (dataSource != null) {
                    val router = telegramRouter!!
                    telegramWebhookWorkerScope =
                        CoroutineScope(
                            SupervisorJob() + Dispatchers.IO + CoroutineName("telegram-webhook-worker"),
                        )
                    val worker =
                        TelegramInboundUpdateWorker(
                            repository = telegramInboundUpdateQueueRepository,
                            router = router,
                            json = telegramJson,
                            scope = telegramWebhookWorkerScope!!,
                            metrics = appMetrics,
                        )
                    telegramWebhookWorkerJob = worker.start()
                } else {
                    logger.warn("Telegram webhook worker disabled: database is not configured")
                }
            }
        }
    }

    monitor.subscribe(ApplicationStarted) {
        if (dataSource != null) {
            subscriptionBillingJob.start(subscriptionBillingScope)
            tableSessionCleanupJob = tableSessionCleanupWorker.start()
            if (bookingExpiryWorkerConfig.enabled) {
                bookingExpiryJob = bookingExpiryWorker.start()
            } else {
                logger.info("Booking expiry worker disabled by config")
            }
            if (bookingReminderWorkerConfig.enabled) {
                bookingReminderJob = bookingReminderWorker.start()
            } else {
                logger.info("Booking reminder worker disabled by config")
            }
            logger.info("Visit feedback worker disabled for History-only feedback MVP")
        } else {
            logger.info("Subscription billing job disabled: database is not configured")
            logger.info("Table session cleanup worker disabled: database is not configured")
            logger.info("Booking expiry worker disabled: database is not configured")
            logger.info("Booking reminder worker disabled: database is not configured")
            logger.info("Visit feedback worker disabled for History-only feedback MVP")
        }
        if (telegramConfig.enabled && !telegramConfig.token.isNullOrBlank()) {
            val apiClient = telegramApiClient
            val scope = telegramScope
            if (apiClient != null && scope != null) {
                scope.launch {
                    configureTelegramCommandMenu(apiClient)
                }
            }
        }
    }
    monitor.subscribe(ApplicationStopping) {
        logger.info("Application stopping, closing resources")
        subscriptionBillingJob.stop()
        tableSessionCleanupJob?.cancel()
        bookingExpiryJob?.cancel()
        bookingReminderJob?.cancel()
        httpClient.close()
        telegramApiClient?.close()
        telegramScope?.cancel()
        telegramWebhookWorkerJob?.cancel()
        telegramWebhookWorkerScope?.cancel()
        telegramOutboxWorkerJob?.cancel()
        telegramOutboxWorkerScope?.cancel()
        staffChatNotifierScope.cancel()
        subscriptionBillingScope.cancel()
        tableSessionCleanupScope.cancel()
        bookingExpiryScope.cancel()
        bookingReminderScope.cancel()
        DatabaseFactory.close(dataSource)
    }
    monitor.subscribe(ApplicationStopped) {
        logger.info("Application stopped")
    }

    install(CallId) {
        header(ApiHeaders.REQUEST_ID)
        replyToHeader(ApiHeaders.REQUEST_ID)
        generate { UUID.randomUUID().toString() }
        verify { it.isNotBlank() }
    }

    install(CallLogging) {
        callIdMdc("requestId")
    }

    install(CORS) {
        allowMethod(HttpMethod.Options)
        allowMethod(HttpMethod.Get)
        allowMethod(HttpMethod.Post)
        allowMethod(HttpMethod.Put)
        allowMethod(HttpMethod.Patch)
        allowMethod(HttpMethod.Delete)
        allowHeader(HttpHeaders.ContentType)
        allowHeader(HttpHeaders.Authorization)
        appConfig.corsAllowedHosts().forEach { spec ->
            allowHost(spec.host, schemes = spec.schemes)
        }
    }

    install(MicrometerMetrics) {
        registry = appMetrics.registry
    }

    install(ServerContentNegotiation) {
        json(json)
    }

    install(StatusPages) {
        exception<ContentTransformationException> { call, cause ->
            if (!call.isApiRequest()) {
                throw cause
            }
            call.respondInvalidRequestBody()
        }
        exception<BadRequestException> { call, cause ->
            if (!call.isApiRequest()) {
                throw cause
            }
            call.respondInvalidRequestBody()
        }
        exception<ApiException> { call, cause ->
            if (!call.isApiRequest()) {
                throw cause
            }
            call.respondApiError(
                status = cause.httpStatus,
                code = cause.code,
                message = cause.message,
                details = cause.details,
            )
        }
        exception<Throwable> { call, cause ->
            if (!call.isApiRequest()) {
                throw cause
            }
            if (cause is CancellationException) {
                throw cause
            }
            val safeMessage =
                (cause.message ?: "unknown error")
                    .replace(Regex("[\\r\\n\\t]"), " ")
                    .take(200)
            logger.warn(
                "Unhandled API error requestId={} method={} path={} error={} message={}",
                call.callId,
                call.request.httpMethod.value,
                call.request.path(),
                cause::class.qualifiedName ?: cause::class.simpleName,
                safeMessage,
            )
            logger.debug("Unhandled API error", cause)
            call.respondApiError(
                status = HttpStatusCode.InternalServerError,
                code = ApiErrorCodes.INTERNAL_ERROR,
                message = "Internal error",
            )
        }
        status(HttpStatusCode.NotFound) { call, _ ->
            if (call.isApiRequest()) {
                call.respondApiError(
                    status = HttpStatusCode.NotFound,
                    code = ApiErrorCodes.NOT_FOUND,
                    message = "Not found",
                )
            }
        }
        status(HttpStatusCode.MethodNotAllowed) { call, _ ->
            if (call.isApiRequest()) {
                call.respondApiError(
                    status = HttpStatusCode.MethodNotAllowed,
                    code = ApiErrorCodes.INVALID_INPUT,
                    message = "Method not allowed",
                )
            }
        }
    }

    install(Authentication) {
        jwt("miniapp-session") {
            val algorithm = Algorithm.HMAC256(sessionTokenConfig.jwtSecret)
            verifier(
                JWT
                    .require(algorithm)
                    .withIssuer(sessionTokenConfig.issuer)
                    .withAudience(sessionTokenConfig.audience)
                    .build(),
            )
            validate { credentials ->
                val subject = credentials.payload.subject ?: return@validate null
                subject.toLongOrNull() ?: return@validate null
                JWTPrincipal(credentials.payload)
            }
            challenge { _, _ ->
                call.respondApiError(
                    status = HttpStatusCode.Unauthorized,
                    code = ApiErrorCodes.UNAUTHORIZED,
                    message = "Unauthorized",
                )
            }
        }
    }

    routing {
        get("/health") {
            call.respond(HealthResponse(status = "ok"))
        }

        get("/db/health") {
            val dataSourceToUse = dataSource
            if (dataSourceToUse == null) {
                call.respond(HttpStatusCode.ServiceUnavailable, DbHealthResponse(status = "disabled"))
                return@get
            }

            try {
                val isOk =
                    withContext(Dispatchers.IO) {
                        dataSourceToUse.connection.use { connection ->
                            connection.createStatement().use { statement ->
                                statement.executeQuery("SELECT 1").use { resultSet ->
                                    resultSet.next()
                                }
                            }
                        }
                    }

                if (isOk) {
                    call.respond(DbHealthResponse(status = "ok"))
                } else {
                    call.respond(
                        HttpStatusCode.ServiceUnavailable,
                        DbHealthResponse(status = "error", message = "db_unavailable"),
                    )
                }
            } catch (e: Exception) {
                val safeMessage =
                    (e.message ?: "unknown error")
                        .replace(Regex("[\\r\\n\\t]"), " ")
                        .take(200)
                logger.warn("DB health check failed: {} {}", e::class.simpleName, safeMessage)
                logger.debug("DB health check failed", e)
                call.respond(
                    HttpStatusCode.ServiceUnavailable,
                    DbHealthResponse(status = "error", message = "db_unavailable"),
                )
            }
        }

        if (telegramConfig.enabled && telegramConfig.mode == TelegramBotConfig.Mode.WEBHOOK) {
            get("/telegram/queue/health") {
                try {
                    val depth = telegramInboundUpdateQueueRepository.queueDepth()
                    call.respond(TelegramQueueHealthResponse(status = "ok", depth = depth))
                } catch (e: DatabaseUnavailableException) {
                    call.respond(
                        HttpStatusCode.ServiceUnavailable,
                        TelegramQueueHealthResponse(status = "db_unavailable"),
                    )
                }
            }
        }

        get("/metrics") {
            if (dataSource != null) {
                try {
                    appMetrics.setInboundQueueDepth(telegramInboundUpdateQueueRepository.queueDepth())
                    appMetrics.setOutboundQueueDepth(telegramOutboxRepository.queueDepth())
                } catch (_: DatabaseUnavailableException) {
                    logger.debug("Skipping queue metrics refresh: db unavailable")
                }
            }
            call.respondText(appMetrics.scrape(), ContentType.parse("text/plain; version=0.0.4"))
        }

        get("/version") {
            val time = DateTimeFormatter.ISO_INSTANT.format(Instant.now())
            call.respond(
                VersionResponse(
                    service = "backend",
                    env = appEnv,
                    version = appVersion,
                    time = time,
                ),
            )
        }

        billingWebhookRoutes(
            config = billingConfig,
            providerRegistry = billingProviderRegistry,
            billingService = billingService,
        )

        miniAppAuthRoutes(appConfig, sessionTokenService, userRepository)

        val guestInfoMediaDownloader: (suspend (String) -> TelegramDownloadedFile?)? =
            overrides.telegramFileDownloader ?: telegramApiClient?.let { client ->
                { fileId -> client.downloadFile(fileId) }
            }

        route("/api") {
            route("/guest") {
                guestVenueInfoMediaRoutes(
                    guestVenueRepository = guestVenueRepository,
                    venueInfoSectionsRepository = venueInfoSectionsRepository,
                    venueInfoSectionMediaRepository = venueInfoSectionMediaRepository,
                    subscriptionRepository = subscriptionRepository,
                    telegramFileDownloader = guestInfoMediaDownloader,
                )
            }
        }

        authenticate("miniapp-session") {
            route("/api") {
                route("/guest") {
                    guestVenueRoutes(
                        guestVenueRepository = guestVenueRepository,
                        guestFavoritesRepository = guestFavoritesRepository,
                        guestMenuRepository = guestMenuRepository,
                        venueStaffProfileRepository = venueStaffProfileRepository,
                        venueInfoSectionsRepository = venueInfoSectionsRepository,
                        venueInfoSectionMediaRepository = venueInfoSectionMediaRepository,
                        subscriptionRepository = subscriptionRepository,
                        venueBookingHoursRepository = venueBookingHoursRepository,
                        venueSettingsRepository = venueSettingsRepository,
                    )
                    guestFavoritesRoutes(
                        guestFavoritesRepository = guestFavoritesRepository,
                    )
                    guestTableResolveRoutes(
                        tableTokenResolver = tableTokenResolver,
                        guestVenueRepository = guestVenueRepository,
                        subscriptionRepository = subscriptionRepository,
                        tableSessionRepository = tableSessionRepository,
                        tableSessionConfig = tableSessionConfig,
                        guestTabsRepository = guestTabsRepository,
                    )
                    guestTabsRoutes(
                        guestTabsRepository = guestTabsRepository,
                        guestVenueRepository = guestVenueRepository,
                        subscriptionRepository = subscriptionRepository,
                    )
                    guestOrderRoutes(
                        guestRateLimitConfig = guestRateLimitConfig,
                        rateLimiter = guestRateLimiter,
                        tableTokenResolver = tableTokenResolver,
                        guestVenueRepository = guestVenueRepository,
                        guestMenuRepository = guestMenuRepository,
                        subscriptionRepository = subscriptionRepository,
                        ordersRepository = ordersRepository,
                        staffCallRepository = staffCallRepository,
                        tableSessionRepository = tableSessionRepository,
                        tableSessionConfig = tableSessionConfig,
                        guestTabsRepository = guestTabsRepository,
                        staffChatNotifier = guestStaffChatNotifier,
                        userRepository = userRepository,
                        venueSettingsRepository = venueSettingsRepository,
                        venueOrdersRepository = venueOrdersRepository,
                    )
                    guestBookingRoutes(
                        guestVenueRepository = guestVenueRepository,
                        subscriptionRepository = subscriptionRepository,
                        guestBookingRepository = guestBookingRepository,
                        venueRepository = venueRepository,
                        outboxEnqueuer = telegramOutboxEnqueuer,
                        staffChatNotifier = guestStaffChatNotifier,
                        userRepository = userRepository,
                        venueSettingsRepository = venueSettingsRepository,
                        venueBookingHoursRepository = venueBookingHoursRepository,
                    )
                    guestSupportRoutes(
                        supportThreadRepository = supportThreadRepository,
                        venueRepository = venueRepository,
                        outboxEnqueuer = telegramOutboxEnqueuer,
                        tableTokenResolver = tableTokenResolver,
                        tableSessionRepository = tableSessionRepository,
                        tableSessionConfig = tableSessionConfig,
                        guestBookingRepository = guestBookingRepository,
                        auditLogRepository = auditLogRepository,
                        guestRateLimitConfig = guestRateLimitConfig,
                        rateLimiter = guestRateLimiter,
                    )
                    guestVisitRoutes(
                        visitRepository = visitRepository,
                        visitFeedbackRepository = visitFeedbackRepository,
                        repeatOrderResolver = repeatOrderResolver,
                        analyticsEventRepository = analyticsEventRepository,
                        venueSettingsRepository = venueSettingsRepository,
                    )
                    guestStaffCallRoutes(
                        guestRateLimitConfig = guestRateLimitConfig,
                        rateLimiter = guestRateLimiter,
                        tableTokenResolver = tableTokenResolver,
                        guestVenueRepository = guestVenueRepository,
                        subscriptionRepository = subscriptionRepository,
                        staffCallRepository = staffCallRepository,
                        tableSessionRepository = tableSessionRepository,
                        tableSessionConfig = tableSessionConfig,
                        staffChatNotifier = guestStaffChatNotifier,
                        userRepository = userRepository,
                        ordersRepository = ordersRepository,
                    )
                    guestShiftExtensionRoutes(
                        tableTokenResolver = tableTokenResolver,
                        guestVenueRepository = guestVenueRepository,
                        subscriptionRepository = subscriptionRepository,
                        tableSessionRepository = tableSessionRepository,
                        tableSessionConfig = tableSessionConfig,
                        guestTabsRepository = guestTabsRepository,
                        ordersRepository = ordersRepository,
                        shiftExtensionRepository = shiftExtensionRepository,
                        venueSettingsRepository = venueSettingsRepository,
                        staffBillUpdateNotifier = overrides.staffBillUpdateNotifier ?: staffChatNotifier,
                        venueOrdersRepository = venueOrdersRepository,
                    )
                    get("/_ping") {
                        call.respond(mapOf("ok" to true))
                    }
                }
                venueRoutes(
                    venueAccessRepository = venueAccessRepository,
                    staffChatLinkCodeRepository = staffChatLinkCodeRepository,
                    venueRepository = venueRepository,
                    venueBookingHoursRepository = venueBookingHoursRepository,
                    venueSettingsRepository = venueSettingsRepository,
                    venueLocationProvider = venueLocationProvider,
                    staffChatNotifier = guestStaffChatNotifier,
                    telegramBotUsername = telegramConfig.botUsername,
                )
                venueBillingRoutes(
                    venueAccessRepository = venueAccessRepository,
                    billingOverviewService = billingOverviewService,
                    auditLogRepository = auditLogRepository,
                )
                venueStaffRoutes(
                    venueAccessRepository = venueAccessRepository,
                    venueStaffRepository = venueStaffRepository,
                    venueStaffProfileRepository = venueStaffProfileRepository,
                    staffInviteRepository = staffInviteRepository,
                    staffInviteConfig = staffInviteConfig,
                    venueSettingsRepository = venueSettingsRepository,
                    venueOwnerAccountRepository = venueOwnerAccountRepository,
                    auditLogRepository = auditLogRepository,
                    telegramBotUsername = telegramConfig.botUsername,
                )
                venueStaffCallRoutes(
                    venueAccessRepository = venueAccessRepository,
                    staffCallRepository = staffCallRepository,
                    auditLogRepository = auditLogRepository,
                )
                venueTableRoutes(
                    venueAccessRepository = venueAccessRepository,
                    venueTableRepository = venueTableRepository,
                    auditLogRepository = auditLogRepository,
                    telegramBotUsername = telegramConfig.botUsername,
                )
                venueMenuRoutes(
                    venueAccessRepository = venueAccessRepository,
                    venueMenuRepository = venueMenuRepository,
                )
                venueOrderRoutes(
                    venueAccessRepository = venueAccessRepository,
                    venueOrdersRepository = venueOrdersRepository,
                    outboxEnqueuer = telegramOutboxEnqueuer,
                    staffBillUpdateNotifier = overrides.staffBillUpdateNotifier ?: staffChatNotifier,
                    staffCallRepository = staffCallRepository,
                    auditLogRepository = auditLogRepository,
                )
                venueBookingRoutes(
                    venueAccessRepository = venueAccessRepository,
                    guestBookingRepository = guestBookingRepository,
                    outboxEnqueuer = telegramOutboxEnqueuer,
                    supportThreadRepository = supportThreadRepository,
                    venueSettingsRepository = venueSettingsRepository,
                    bookingRemindersEnabled = bookingReminderWorkerConfig.enabled,
                )
                venueSupportRoutes(
                    venueAccessRepository = venueAccessRepository,
                    supportThreadRepository = supportThreadRepository,
                    outboxEnqueuer = telegramOutboxEnqueuer,
                    auditLogRepository = auditLogRepository,
                )
                venueStatsRoutes(
                    venueAccessRepository = venueAccessRepository,
                    venueStatsRepository = venueStatsRepository,
                    venueSettingsRepository = venueSettingsRepository,
                )
                venueFeedbackRoutes(
                    venueAccessRepository = venueAccessRepository,
                    visitFeedbackRepository = visitFeedbackRepository,
                    supportThreadRepository = supportThreadRepository,
                )
                venueShiftExtensionRoutes(
                    venueAccessRepository = venueAccessRepository,
                    shiftExtensionRepository = shiftExtensionRepository,
                    venueOrdersRepository = venueOrdersRepository,
                    staffBillUpdateNotifier = overrides.staffBillUpdateNotifier ?: staffChatNotifier,
                    venueSettingsRepository = venueSettingsRepository,
                )
                platformRoutes(
                    platformConfig = platformConfig,
                    platformVenueRepository = platformVenueRepository,
                    platformUserRepository = platformUserRepository,
                    auditLogRepository = auditLogRepository,
                    billingInvoiceRepository = billingInvoiceRepository,
                    billingService = billingService,
                    billingOverviewService = billingOverviewService,
                    subscriptionSettingsRepository = subscriptionSettingsRepository,
                    platformVenueMemberRepository = platformVenueMemberRepository,
                    venueOwnerAccountRepository = venueOwnerAccountRepository,
                    staffInviteRepository = staffInviteRepository,
                    staffInviteConfig = staffInviteConfig,
                    supportThreadRepository = supportThreadRepository,
                    outboxEnqueuer = telegramOutboxEnqueuer,
                    telegramBotUsername = telegramConfig.botUsername,
                )
            }
        }

        miniAppRoutes(miniAppDevServerUrl, miniAppStaticDir, httpClient)

        if (telegramConfig.enabled && telegramConfig.mode == TelegramBotConfig.Mode.WEBHOOK) {
            if (telegramRouter != null) {
                post(telegramConfig.webhookPath) {
                    val secret = telegramConfig.webhookSecretToken
                    if (!secret.isNullOrBlank()) {
                        val header = call.request.headers["X-Telegram-Bot-Api-Secret-Token"]
                        if (!constantTimeEquals(secret, header)) {
                            call.respond(HttpStatusCode.Forbidden)
                            return@post
                        }
                    }
                    try {
                        val payload = call.receiveText()
                        val update = telegramJson.decodeFromString(TelegramUpdate.serializer(), payload)
                        telegramInboundUpdateQueueRepository.enqueue(update.updateId, payload)
                        call.respond(HttpStatusCode.OK)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: SerializationException) {
                        logger.warn("Webhook enqueue failed: {}", sanitizeTelegramForLog(e.message))
                        logger.debugTelegramException(e) { "Webhook enqueue exception" }
                        call.respond(HttpStatusCode.BadRequest)
                    } catch (e: ContentTransformationException) {
                        logger.warn("Webhook enqueue failed: {}", sanitizeTelegramForLog(e.message))
                        logger.debugTelegramException(e) { "Webhook enqueue exception" }
                        call.respond(HttpStatusCode.BadRequest)
                    } catch (e: DatabaseUnavailableException) {
                        logger.warn("Webhook enqueue failed: {}", sanitizeTelegramForLog(e.message))
                        logger.debugTelegramException(e) { "Webhook enqueue exception" }
                        call.respond(HttpStatusCode.ServiceUnavailable)
                    } catch (e: Exception) {
                        logger.warn("Webhook enqueue failed: {}", sanitizeTelegramForLog(e.message))
                        logger.debugTelegramException(e) { "Webhook enqueue exception" }
                        call.respond(HttpStatusCode.InternalServerError)
                    }
                }
            }
        }
    }
}

private fun Route.miniAppRoutes(
    devServerUrl: String?,
    staticDir: String?,
    httpClient: HttpClient,
) {
    route("/miniapp") {
        get {
            call.redirectToMiniAppRoot()
        }
        head {
            call.redirectToMiniAppRoot()
        }

        if (!devServerUrl.isNullOrBlank()) {
            get("/") {
                call.proxyToDevServer(call.miniAppIndexRequestUri(), HttpMethod.Get, devServerUrl, httpClient)
            }
            head("/") {
                call.proxyToDevServer(call.miniAppIndexRequestUri(), HttpMethod.Head, devServerUrl, httpClient)
            }
            get("{...}") {
                call.proxyToDevServer(call.request.uri, HttpMethod.Get, devServerUrl, httpClient)
            }
            head("{...}") {
                call.proxyToDevServer(call.request.uri, HttpMethod.Head, devServerUrl, httpClient)
            }
            return@route
        }

        val staticFolder = staticDir?.let { File(it) }?.takeIf { file -> file.exists() && file.isDirectory }
        if (staticFolder != null) {
            val indexFile = File(staticFolder, "index.html")
            get("/") {
                if (indexFile.exists() && indexFile.isFile) {
                    call.respondFile(indexFile)
                } else {
                    call.respond(HttpStatusCode.NotFound)
                }
            }
            head("/") {
                if (indexFile.exists() && indexFile.isFile) {
                    call.respondBytes(
                        bytes = ByteArray(0),
                        contentType = ContentType.Text.Html,
                        status = HttpStatusCode.OK,
                    )
                } else {
                    call.respond(HttpStatusCode.NotFound)
                }
            }
            staticFiles("", staticFolder) {
                default("index.html")
            }
            return@route
        }

        get("/") {
            call.respondText(
                text = "Mini app dev server is not configured. Set MINIAPP_DEV_SERVER_URL or MINIAPP_STATIC_DIR.",
                contentType = ContentType.Text.Plain,
            )
        }
        head("/") {
            call.respond(HttpStatusCode.MethodNotAllowed)
        }
    }
}

private fun ApplicationCall.miniAppIndexRequestUri(): String {
    val query = request.queryString()
    return if (query.isBlank()) "/miniapp/index.html" else "/miniapp/index.html?$query"
}

private suspend fun ApplicationCall.proxyToDevServer(
    requestUri: String,
    method: HttpMethod,
    devServerUrl: String,
    httpClient: HttpClient,
) {
    if (method != HttpMethod.Get && method != HttpMethod.Head) {
        respond(HttpStatusCode.MethodNotAllowed)
        return
    }

    val targetUrl = devServerUrl.trimEnd('/') + requestUri
    val response =
        httpClient.request(targetUrl) {
            this.method = method
        }

    val contentType = response.headers["Content-Type"]?.let { ContentType.parse(it) }

    if (method == HttpMethod.Head) {
        respondBytes(
            bytes = ByteArray(0),
            contentType = contentType,
            status = response.status,
        )
        return
    }

    val bytes: ByteArray = response.body()
    respondBytes(
        bytes = bytes,
        contentType = contentType,
        status = response.status,
    )
}

private suspend fun ApplicationCall.redirectToMiniAppRoot() {
    val query = request.queryString()
    val target = if (query.isBlank()) "/miniapp/" else "/miniapp/?$query"
    respondRedirect(url = target, permanent = false)
}

private suspend fun configureTelegramCommandMenu(apiClient: TelegramApiClient) {
    val commands =
        buildJsonArray {
            add(
                buildJsonObject {
                    put("command", "start")
                    put("description", "🔄 Обновить")
                },
            )
            add(
                buildJsonObject {
                    put("command", "menu")
                    put("description", "🏠 Главное меню")
                },
            )
            add(
                buildJsonObject {
                    put("command", "support")
                    put("description", "💬 Обращение в поддержку")
                },
            )
        }
    val scopes =
        listOf(
            "default",
            "all_private_chats",
            "all_group_chats",
            "all_chat_administrators",
        )

    scopes.forEach { scopeType ->
        val deletePayload =
            buildJsonObject {
                put(
                    "scope",
                    buildJsonObject {
                        put("type", scopeType)
                    },
                )
            }
        when (val result = apiClient.callMethod("deleteMyCommands", deletePayload)) {
            is TelegramCallResult.Success -> logger.info("Telegram bot commands cleared for scope={}", scopeType)
            is TelegramCallResult.Failure ->
                logger.warn(
                    "Failed to clear Telegram bot commands for scope={}: {}{}",
                    scopeType,
                    sanitizeTelegramForLog(result.description),
                    result.errorCode?.let { " (code=$it)" } ?: "",
                )
        }
    }

    scopes.forEach { scopeType ->
        val commandsPayload =
            buildJsonObject {
                put(
                    "scope",
                    buildJsonObject {
                        put("type", scopeType)
                    },
                )
                put("commands", commands)
            }
        when (val result = apiClient.callMethod("setMyCommands", commandsPayload)) {
            is TelegramCallResult.Success -> logger.info("Telegram bot commands configured for scope={}", scopeType)
            is TelegramCallResult.Failure ->
                logger.warn(
                    "Failed to configure Telegram bot commands for scope={}: {}{}",
                    scopeType,
                    sanitizeTelegramForLog(result.description),
                    result.errorCode?.let { " (code=$it)" } ?: "",
                )
        }
    }

    val defaultCommandsPayload =
        buildJsonObject {
            put("commands", commands)
        }
    when (val result = apiClient.callMethod("setMyCommands", defaultCommandsPayload)) {
        is TelegramCallResult.Success -> logger.info("Telegram bot commands configured")
        is TelegramCallResult.Failure ->
            logger.warn(
                "Failed to configure Telegram bot commands: {}{}",
                sanitizeTelegramForLog(result.description),
                result.errorCode?.let { " (code=$it)" } ?: "",
            )
    }

    val menuButtonPayload =
        buildJsonObject {
            put(
                "menu_button",
                buildJsonObject {
                    put("type", "commands")
                },
            )
        }
    when (val result = apiClient.callMethod("setChatMenuButton", menuButtonPayload)) {
        is TelegramCallResult.Success -> logger.info("Telegram menu button configured")
        is TelegramCallResult.Failure ->
            logger.warn(
                "Failed to configure Telegram menu button: {}{}",
                sanitizeTelegramForLog(result.description),
                result.errorCode?.let { " (code=$it)" } ?: "",
            )
    }
}

private fun ApplicationConfig.optionalString(path: String): String? =
    if (propertyOrNull(path) != null) property(path).getString() else null

private data class CorsAllowedHost(
    val host: String,
    val schemes: List<String>,
)

private val defaultCorsAllowedHosts =
    listOf(
        CorsAllowedHost(host = "localhost:5173", schemes = listOf("http")),
        CorsAllowedHost(host = "127.0.0.1:5173", schemes = listOf("http")),
    )

private fun ApplicationConfig.corsAllowedHosts(): List<CorsAllowedHost> {
    val configured =
        optionalString("cors.allowedHosts")
            .orEmpty()
            .split(',')
            .mapNotNull(::parseCorsAllowedHost)

    return (defaultCorsAllowedHosts + configured)
        .groupBy { it.host }
        .map { (host, specs) ->
            CorsAllowedHost(
                host = host,
                schemes = specs.flatMap { it.schemes }.distinct(),
            )
        }
}

private fun parseCorsAllowedHost(value: String): CorsAllowedHost? {
    val trimmed = value.trim().trimEnd('/')
    if (trimmed.isBlank()) {
        return null
    }

    val rawUri = if ("://" in trimmed) trimmed else "https://$trimmed"
    val uri = runCatching { URI(rawUri) }.getOrNull() ?: return null
    val scheme = uri.scheme?.lowercase(Locale.ROOT)?.takeIf { it == "http" || it == "https" } ?: "https"
    val host = uri.host?.takeIf { it.isNotBlank() } ?: return null
    val hostWithPort = if (uri.port >= 0) "$host:${uri.port}" else host
    return CorsAllowedHost(host = hostWithPort, schemes = listOf(scheme))
}

private fun redactJdbcUrl(jdbcUrl: String): String = jdbcUrl.replace(Regex("(?i)(password)=([^&;]+)"), "$1=***")
