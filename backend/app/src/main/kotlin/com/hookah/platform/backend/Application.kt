package com.hookah.platform.backend

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import com.hookah.platform.backend.api.ApiError
import com.hookah.platform.backend.api.ApiErrorCodes
import com.hookah.platform.backend.api.ApiErrorEnvelope
import com.hookah.platform.backend.api.ApiException
import com.hookah.platform.backend.api.ApiHeaders
import com.hookah.platform.backend.api.DatabaseUnavailableException
import com.hookah.platform.backend.billing.BillingConfig
import com.hookah.platform.backend.billing.BillingInvoiceRepository
import com.hookah.platform.backend.billing.BillingNotificationRepository
import com.hookah.platform.backend.billing.BillingPaymentRepository
import com.hookah.platform.backend.billing.BillingProviderRegistry
import com.hookah.platform.backend.billing.BillingService
import com.hookah.platform.backend.billing.FakeBillingProvider
import com.hookah.platform.backend.billing.billingWebhookRoutes
import com.hookah.platform.backend.billing.subscription.SubscriptionBillingConfig
import com.hookah.platform.backend.billing.subscription.SubscriptionBillingEngine
import com.hookah.platform.backend.billing.subscription.SubscriptionBillingHooks
import com.hookah.platform.backend.billing.subscription.SubscriptionBillingJob
import com.hookah.platform.backend.billing.subscription.SubscriptionBillingVenueRepository
import com.hookah.platform.backend.db.DatabaseFactory
import com.hookah.platform.backend.db.DbConfig
import com.hookah.platform.backend.miniapp.auth.miniAppAuthRoutes
import com.hookah.platform.backend.miniapp.guest.db.GuestMenuRepository
import com.hookah.platform.backend.miniapp.guest.db.GuestVenueRepository
import com.hookah.platform.backend.miniapp.guest.guestOrderRoutes
import com.hookah.platform.backend.miniapp.guest.guestStaffCallRoutes
import com.hookah.platform.backend.miniapp.guest.guestTableResolveRoutes
import com.hookah.platform.backend.miniapp.guest.guestVenueRoutes
import com.hookah.platform.backend.miniapp.session.SessionTokenConfig
import com.hookah.platform.backend.miniapp.session.SessionTokenService
import com.hookah.platform.backend.miniapp.subscription.db.SubscriptionRepository
import com.hookah.platform.backend.miniapp.venue.AuditLogRepository
import com.hookah.platform.backend.miniapp.venue.menu.VenueMenuRepository
import com.hookah.platform.backend.miniapp.venue.menu.venueMenuRoutes
import com.hookah.platform.backend.miniapp.venue.orders.VenueOrdersRepository
import com.hookah.platform.backend.miniapp.venue.orders.venueOrderRoutes
import com.hookah.platform.backend.miniapp.venue.staff.StaffInviteConfig
import com.hookah.platform.backend.miniapp.venue.staff.StaffInviteRepository
import com.hookah.platform.backend.miniapp.venue.staff.VenueStaffRepository
import com.hookah.platform.backend.miniapp.venue.tables.VenueTableRepository
import com.hookah.platform.backend.miniapp.venue.tables.venueTableRoutes
import com.hookah.platform.backend.miniapp.venue.venueRoutes
import com.hookah.platform.backend.miniapp.venue.venueStaffRoutes
import com.hookah.platform.backend.platform.PlatformConfig
import com.hookah.platform.backend.platform.PlatformSubscriptionSettingsRepository
import com.hookah.platform.backend.platform.PlatformUserRepository
import com.hookah.platform.backend.platform.PlatformVenueMemberRepository
import com.hookah.platform.backend.platform.PlatformVenueRepository
import com.hookah.platform.backend.platform.platformRoutes
import com.hookah.platform.backend.security.constantTimeEquals
import com.hookah.platform.backend.telegram.InMemoryTelegramRateLimiter
import com.hookah.platform.backend.telegram.StaffChatNotifier
import com.hookah.platform.backend.telegram.TableContext
import com.hookah.platform.backend.telegram.TelegramApiClient
import com.hookah.platform.backend.telegram.TelegramBotConfig
import com.hookah.platform.backend.telegram.TelegramBotRouter
import com.hookah.platform.backend.telegram.TelegramInboundUpdateWorker
import com.hookah.platform.backend.telegram.TelegramOutboxEnqueuer
import com.hookah.platform.backend.telegram.TelegramOutboxWorker
import com.hookah.platform.backend.telegram.TelegramUpdate
import com.hookah.platform.backend.telegram.db.ChatContextRepository
import com.hookah.platform.backend.telegram.db.DialogStateRepository
import com.hookah.platform.backend.telegram.db.IdempotencyRepository
import com.hookah.platform.backend.telegram.db.OrdersRepository
import com.hookah.platform.backend.telegram.db.StaffCallRepository
import com.hookah.platform.backend.telegram.db.StaffChatLinkCodeRepository
import com.hookah.platform.backend.telegram.db.StaffChatNotificationRepository
import com.hookah.platform.backend.telegram.db.TableTokenRepository
import com.hookah.platform.backend.telegram.db.TelegramInboundUpdateQueueRepository
import com.hookah.platform.backend.telegram.db.TelegramOutboxRepository
import com.hookah.platform.backend.telegram.db.UserRepository
import com.hookah.platform.backend.telegram.db.VenueAccessRepository
import com.hookah.platform.backend.telegram.db.VenueRepository
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
import io.ktor.server.plugins.BadRequestException
import io.ktor.server.plugins.ContentTransformationException
import io.ktor.server.plugins.callid.CallId
import io.ktor.server.plugins.callid.callId
import io.ktor.server.plugins.callid.callIdMdc
import io.ktor.server.plugins.calllogging.CallLogging
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.plugins.statuspages.exception
import io.ktor.server.request.httpMethod
import io.ktor.server.request.path
import io.ktor.server.request.queryString
import io.ktor.server.request.receiveText
import io.ktor.server.request.uri
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytes
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
import org.slf4j.LoggerFactory
import java.io.File
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
    module(ModuleOverrides())
}

internal fun Application.module(overrides: ModuleOverrides) {
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
    val subscriptionBillingConfig = SubscriptionBillingConfig.from(appConfig)
    val platformConfig = PlatformConfig.from(appConfig)

    val httpClient =
        HttpClient(Java) {
            install(ContentNegotiation) {
                json(json)
            }
        }

    val dataSource = DatabaseFactory.init(dbConfig)
    val venueRepository = VenueRepository(dataSource)
    val userRepository = UserRepository(dataSource)
    val guestVenueRepository = GuestVenueRepository(dataSource)
    val guestMenuRepository = GuestMenuRepository(dataSource)
    val subscriptionRepository = SubscriptionRepository(dataSource)
    val ordersRepository = OrdersRepository(dataSource)
    val venueOrdersRepository = VenueOrdersRepository(dataSource)
    val venueMenuRepository = VenueMenuRepository(dataSource)
    val venueTableRepository = VenueTableRepository(dataSource)
    val staffCallRepository = StaffCallRepository(dataSource)
    val tableTokenRepository = TableTokenRepository(dataSource)
    val auditLogRepository = AuditLogRepository(dataSource, json)
    val platformVenueRepository = PlatformVenueRepository(dataSource)
    val subscriptionSettingsRepository = PlatformSubscriptionSettingsRepository(dataSource)
    val platformUserRepository = PlatformUserRepository(dataSource)
    val platformVenueMemberRepository = PlatformVenueMemberRepository(dataSource)
    val billingInvoiceRepository = BillingInvoiceRepository(dataSource)
    val billingPaymentRepository = BillingPaymentRepository(dataSource)
    val billingNotificationRepository = BillingNotificationRepository(dataSource)
    val billingProviderRegistry = BillingProviderRegistry(listOf(FakeBillingProvider()))
    val resolvedBillingProvider = billingProviderRegistry.resolve(billingConfig.normalizedProvider)
    if (resolvedBillingProvider == null) {
        if (appEnv == "prod" || appEnv == "production") {
            throw IllegalStateException("Unknown billing provider '${billingConfig.provider}' in production")
        }
        logger.warn("Unknown billing provider '{}', falling back to FAKE", billingConfig.provider)
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
    val subscriptionBillingEngine =
        SubscriptionBillingEngine(
            dataSource = dataSource,
            venueRepository = SubscriptionBillingVenueRepository(dataSource),
            settingsRepository = subscriptionSettingsRepository,
            billingService = billingService,
            invoiceRepository = billingInvoiceRepository,
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
    val staffChatLinkCodeRepository =
        StaffChatLinkCodeRepository(
            dataSource = dataSource,
            pepper = telegramConfig.staffChatLinkSecretPepper,
            ttlSeconds = telegramConfig.staffChatLinkTtlSeconds,
        )
    val venueAccessRepository = VenueAccessRepository(dataSource)
    val venueStaffRepository = VenueStaffRepository(dataSource)
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
            outboxEnqueuer = telegramOutboxEnqueuer,
            isTelegramActive = { telegramConfig.enabled && !telegramConfig.token.isNullOrBlank() },
            scope = staffChatNotifierScope,
        )
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
                venueRepository = venueRepository,
                venueAccessRepository = venueAccessRepository,
                subscriptionRepository = subscriptionRepository,
                json = telegramJson,
                scope = botScope,
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
                )
            telegramOutboxWorkerJob = outboxWorker.start()
        } else {
            logger.warn("Telegram outbox worker disabled: database is not configured")
        }

        when (telegramConfig.mode) {
            TelegramBotConfig.Mode.LONG_POLLING -> {
                val router = telegramRouter
                if (router != null) {
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
            }

            TelegramBotConfig.Mode.WEBHOOK -> {
                logger.info("Telegram webhook mode enabled at {}", telegramConfig.webhookPath)
                if (dataSource != null && telegramRouter != null) {
                    telegramWebhookWorkerScope =
                        CoroutineScope(
                            SupervisorJob() + Dispatchers.IO + CoroutineName("telegram-webhook-worker"),
                        )
                    val worker =
                        TelegramInboundUpdateWorker(
                            repository = telegramInboundUpdateQueueRepository,
                            router = telegramRouter,
                            json = telegramJson,
                            scope = telegramWebhookWorkerScope!!,
                        )
                    telegramWebhookWorkerJob = worker.start()
                } else if (dataSource == null) {
                    logger.warn("Telegram webhook worker disabled: database is not configured")
                }
            }
        }
    }

    environment.monitor.subscribe(ApplicationStarted) {
        if (dataSource != null) {
            subscriptionBillingJob.start(subscriptionBillingScope)
        } else {
            logger.info("Subscription billing job disabled: database is not configured")
        }
    }
    environment.monitor.subscribe(ApplicationStopping) {
        logger.info("Application stopping, closing resources")
        subscriptionBillingJob.stop()
        httpClient.close()
        telegramApiClient?.close()
        telegramScope?.cancel()
        telegramWebhookWorkerJob?.cancel()
        telegramWebhookWorkerScope?.cancel()
        telegramOutboxWorkerJob?.cancel()
        telegramOutboxWorkerScope?.cancel()
        staffChatNotifierScope.cancel()
        subscriptionBillingScope.cancel()
        DatabaseFactory.close(dataSource)
    }
    environment.monitor.subscribe(ApplicationStopped) {
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

        authenticate("miniapp-session") {
            route("/api") {
                route("/guest") {
                    guestVenueRoutes(
                        guestVenueRepository = guestVenueRepository,
                        guestMenuRepository = guestMenuRepository,
                        subscriptionRepository = subscriptionRepository,
                    )
                    guestTableResolveRoutes(
                        tableTokenResolver = tableTokenResolver,
                        guestVenueRepository = guestVenueRepository,
                        subscriptionRepository = subscriptionRepository,
                    )
                    guestOrderRoutes(
                        tableTokenResolver = tableTokenResolver,
                        guestVenueRepository = guestVenueRepository,
                        guestMenuRepository = guestMenuRepository,
                        subscriptionRepository = subscriptionRepository,
                        ordersRepository = ordersRepository,
                        staffChatNotifier = staffChatNotifier,
                    )
                    guestStaffCallRoutes(
                        tableTokenResolver = tableTokenResolver,
                        guestVenueRepository = guestVenueRepository,
                        subscriptionRepository = subscriptionRepository,
                        staffCallRepository = staffCallRepository,
                    )
                    get("/_ping") {
                        call.respond(mapOf("ok" to true))
                    }
                }
                venueRoutes(
                    venueAccessRepository = venueAccessRepository,
                    staffChatLinkCodeRepository = staffChatLinkCodeRepository,
                    venueRepository = venueRepository,
                )
                venueStaffRoutes(
                    venueAccessRepository = venueAccessRepository,
                    venueStaffRepository = venueStaffRepository,
                    staffInviteRepository = staffInviteRepository,
                    staffInviteConfig = staffInviteConfig,
                )
                venueTableRoutes(
                    venueAccessRepository = venueAccessRepository,
                    venueTableRepository = venueTableRepository,
                    auditLogRepository = auditLogRepository,
                    webAppPublicUrl = telegramConfig.webAppPublicUrl,
                )
                venueMenuRoutes(
                    venueAccessRepository = venueAccessRepository,
                    venueMenuRepository = venueMenuRepository,
                )
                venueOrderRoutes(
                    venueAccessRepository = venueAccessRepository,
                    venueOrdersRepository = venueOrdersRepository,
                )
                platformRoutes(
                    platformConfig = platformConfig,
                    platformVenueRepository = platformVenueRepository,
                    platformUserRepository = platformUserRepository,
                    auditLogRepository = auditLogRepository,
                    billingInvoiceRepository = billingInvoiceRepository,
                    billingService = billingService,
                    subscriptionSettingsRepository = subscriptionSettingsRepository,
                    platformVenueMemberRepository = platformVenueMemberRepository,
                    staffInviteRepository = staffInviteRepository,
                    staffInviteConfig = staffInviteConfig,
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
        handle {
            call.respond(HttpStatusCode.MethodNotAllowed)
        }
    }

    if (!devServerUrl.isNullOrBlank()) {
        route("/miniapp/") {
            get {
                call.proxyToDevServer(call.request.uri, HttpMethod.Get, devServerUrl, httpClient)
            }
            head {
                call.proxyToDevServer(call.request.uri, HttpMethod.Head, devServerUrl, httpClient)
            }
            get("{...}") {
                call.proxyToDevServer(call.request.uri, HttpMethod.Get, devServerUrl, httpClient)
            }
            head("{...}") {
                call.proxyToDevServer(call.request.uri, HttpMethod.Head, devServerUrl, httpClient)
            }
            handle {
                call.respond(HttpStatusCode.MethodNotAllowed)
            }
        }
        return
    }

    val staticFolder = staticDir?.let { File(it) }?.takeIf { it.exists() && it.isDirectory }
    if (staticFolder != null) {
        staticFiles("/miniapp", staticFolder) {
            default("index.html")
        }
        return
    }

    route("/miniapp/") {
        get {
            call.respondText(
                text = "Mini app dev server is not configured. Set MINIAPP_DEV_SERVER_URL or MINIAPP_STATIC_DIR.",
                contentType = ContentType.Text.Plain,
            )
        }
        head {
            call.respond(HttpStatusCode.MethodNotAllowed)
        }
        handle {
            call.respond(HttpStatusCode.MethodNotAllowed)
        }
    }
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

private fun ApplicationConfig.optionalString(path: String): String? =
    if (propertyOrNull(path) != null) property(path).getString() else null

private fun redactJdbcUrl(jdbcUrl: String): String = jdbcUrl.replace(Regex("(?i)(password)=([^&;]+)"), "$1=***")
