package com.hookah.platform.backend

import com.hookah.platform.backend.api.ApiError
import com.hookah.platform.backend.api.ApiErrorCodes
import com.hookah.platform.backend.api.ApiErrorEnvelope
import com.hookah.platform.backend.api.ApiException
import com.hookah.platform.backend.db.DbConfig
import com.hookah.platform.backend.db.DatabaseFactory
import com.hookah.platform.backend.miniapp.session.SessionTokenConfig
import com.hookah.platform.backend.telegram.TelegramApiClient
import com.hookah.platform.backend.telegram.TelegramBotConfig
import com.hookah.platform.backend.telegram.TelegramBotRouter
import com.hookah.platform.backend.telegram.TelegramUpdate
import com.hookah.platform.backend.telegram.debugTelegramException
import com.hookah.platform.backend.telegram.db.ChatContextRepository
import com.hookah.platform.backend.telegram.db.DialogStateRepository
import com.hookah.platform.backend.telegram.db.IdempotencyRepository
import com.hookah.platform.backend.telegram.db.OrdersRepository
import com.hookah.platform.backend.telegram.db.StaffChatLinkCodeRepository
import com.hookah.platform.backend.telegram.db.StaffCallRepository
import com.hookah.platform.backend.telegram.db.TableTokenRepository
import com.hookah.platform.backend.telegram.db.UserRepository
import com.hookah.platform.backend.telegram.db.VenueRepository
import com.hookah.platform.backend.telegram.db.VenueAccessRepository
import com.hookah.platform.backend.telegram.sanitizeTelegramForLog
import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.java.Java
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.request
import io.ktor.http.ContentType
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.auth.Authentication
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.jwt.JWTPrincipal
import io.ktor.server.auth.jwt.jwt
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.ApplicationStopped
import io.ktor.server.application.ApplicationStopping
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.config.ApplicationConfig
import io.ktor.server.http.content.staticFiles
import io.ktor.server.plugins.callid.CallId
import io.ktor.server.plugins.callid.callId
import io.ktor.server.plugins.callid.callIdMdc
import io.ktor.server.plugins.calllogging.CallLogging
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation as ServerContentNegotiation
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.plugins.statuspages.exception
import io.ktor.server.request.receive
import io.ktor.server.request.httpMethod
import io.ktor.server.request.path
import io.ktor.server.request.queryString
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
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.cancel
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.slf4j.LoggerFactory
import java.io.File
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.UUID

private val logger = LoggerFactory.getLogger("Application")

@Serializable
private data class HealthResponse(val status: String)

@Serializable
private data class DbHealthResponse(val status: String, val message: String? = null)

@Serializable
private data class VersionResponse(
    val service: String,
    val env: String,
    val version: String,
    val time: String
)

@Serializable
private data class LinkCodeResponse(
    val code: String,
    val expiresAt: String,
    val ttlSeconds: Long
)

@Serializable
private data class LinkCodeRequest(
    val userId: Long? = null
)

private fun ApplicationCall.isApiRequest(): Boolean = request.path().startsWith("/api/")

private suspend fun ApplicationCall.respondApiError(
    status: HttpStatusCode,
    code: String,
    message: String,
    details: JsonObject? = null
) {
    respond(
        status,
        ApiErrorEnvelope(
            error = ApiError(code = code, message = message, details = details),
            requestId = callId
        )
    )
}

fun Application.module() {
    val json = Json {
        ignoreUnknownKeys = true
        prettyPrint = false
    }

    val telegramJson = Json {
        ignoreUnknownKeys = true
        prettyPrint = false
    }

    val appConfig = environment.config
    val appEnv = (appConfig.optionalString("app.env")
        ?: System.getenv("APP_ENV")
        ?: "dev").trim().lowercase(Locale.ROOT)
    val dbConfig = DbConfig.from(appConfig)
    val appVersion = appConfig.optionalString("app.version") ?: "dev"
    val miniAppDevServerUrl = appConfig.optionalString("miniapp.devServerUrl")?.takeIf { it.isNotBlank() }
    val miniAppStaticDir = appConfig.optionalString("miniapp.staticDir")?.takeIf { it.isNotBlank() }
    val sessionTokenConfig = SessionTokenConfig.from(appConfig, appEnv)

    val httpClient = HttpClient(Java) {
        install(ContentNegotiation) {
            json(json)
        }
    }

    val dataSource = DatabaseFactory.init(dbConfig)
    val venueRepository = VenueRepository(dataSource)

    if (dataSource != null) {
        logger.info("DB enabled with jdbcUrl={}", redactJdbcUrl(dbConfig.jdbcUrl.orEmpty()))
    }

    val telegramConfig = TelegramBotConfig.from(appConfig)
    var telegramScope: CoroutineScope? = null
    var telegramApiClient: TelegramApiClient? = null
    var telegramRouter: TelegramBotRouter? = null
    val staffChatLinkCodeRepository = StaffChatLinkCodeRepository(
        dataSource = dataSource,
        pepper = telegramConfig.staffChatLinkSecretPepper,
        ttlSeconds = telegramConfig.staffChatLinkTtlSeconds
    )
    val venueAccessRepository = VenueAccessRepository(dataSource)
    if (telegramConfig.enabled && !telegramConfig.token.isNullOrBlank()) {
        telegramScope = CoroutineScope(
            SupervisorJob() + Dispatchers.Default + CoroutineName("telegram-bot")
        )
        val botScope = telegramScope!!
        val requestTimeoutMs = (telegramConfig.longPollingTimeoutSeconds + 15L) * 1000
        val socketTimeoutMs = (telegramConfig.longPollingTimeoutSeconds + 10L) * 1000
        telegramApiClient = TelegramApiClient(
            token = telegramConfig.token!!,
            client = HttpClient(Java) {
                install(ContentNegotiation) { json(telegramJson) }
                install(HttpTimeout) {
                    connectTimeoutMillis = 10_000
                    socketTimeoutMillis = socketTimeoutMs
                    requestTimeoutMillis = requestTimeoutMs
                }
            },
            json = telegramJson
        )
        telegramRouter = TelegramBotRouter(
            config = telegramConfig,
            apiClient = telegramApiClient,
            idempotencyRepository = IdempotencyRepository(dataSource),
            userRepository = UserRepository(dataSource),
            tableTokenRepository = TableTokenRepository(dataSource),
            chatContextRepository = ChatContextRepository(dataSource),
            dialogStateRepository = DialogStateRepository(dataSource, telegramJson),
            ordersRepository = OrdersRepository(dataSource),
            staffCallRepository = StaffCallRepository(dataSource),
            staffChatLinkCodeRepository = staffChatLinkCodeRepository,
            venueRepository = venueRepository,
            venueAccessRepository = venueAccessRepository,
            json = telegramJson,
            scope = botScope
        )

        when (telegramConfig.mode) {
            TelegramBotConfig.Mode.LONG_POLLING -> {
                val router = telegramRouter
                if (router != null) {
                    botScope.launch {
                        var offset: Long? = null
                        while (isActive) {
                            val updates: List<TelegramUpdate> = try {
                                telegramApiClient.getUpdates(
                                    offset,
                                    telegramConfig.longPollingTimeoutSeconds
                                )
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
                                        sanitizeTelegramForLog(e.message)
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
            }
        }
    }

    environment.monitor.subscribe(ApplicationStopping) {
        logger.info("Application stopping, closing resources")
        httpClient.close()
        telegramApiClient?.close()
        telegramScope?.cancel()
        DatabaseFactory.close(dataSource)
    }
    environment.monitor.subscribe(ApplicationStopped) {
        logger.info("Application stopped")
    }

    install(CallId) {
        header("X-Request-Id")
        replyToHeader("X-Request-Id")
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
        exception<ApiException> { call, cause ->
            if (!call.isApiRequest()) {
                throw cause
            }
            call.respondApiError(
                status = cause.httpStatus,
                code = cause.code,
                message = cause.message,
                details = cause.details
            )
        }
        exception<Throwable> { call, cause ->
            if (!call.isApiRequest()) {
                throw cause
            }
            if (cause is CancellationException) {
                throw cause
            }
            val safeMessage = (cause.message ?: "unknown error")
                .replace(Regex("[\\r\\n\\t]"), " ")
                .take(200)
            logger.warn(
                "Unhandled API error requestId={} method={} path={} error={} message={}",
                call.callId,
                call.request.httpMethod.value,
                call.request.path(),
                cause::class.qualifiedName ?: cause::class.simpleName,
                safeMessage
            )
            logger.debug("Unhandled API error", cause)
            call.respondApiError(
                status = HttpStatusCode.InternalServerError,
                code = ApiErrorCodes.INTERNAL_ERROR,
                message = "Internal error"
            )
        }
        status(HttpStatusCode.NotFound) { call, _ ->
            if (call.isApiRequest()) {
                call.respondApiError(
                    status = HttpStatusCode.NotFound,
                    code = ApiErrorCodes.NOT_FOUND,
                    message = "Not found"
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
                    .build()
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
                    message = "Unauthorized"
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
                val isOk = withContext(Dispatchers.IO) {
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
                        DbHealthResponse(status = "error", message = "db_unavailable")
                    )
                }
            } catch (e: Exception) {
                val safeMessage = (e.message ?: "unknown error")
                    .replace(Regex("[\\r\\n\\t]"), " ")
                    .take(200)
                logger.warn("DB health check failed: {} {}", e::class.simpleName, safeMessage)
                logger.debug("DB health check failed", e)
                call.respond(
                    HttpStatusCode.ServiceUnavailable,
                    DbHealthResponse(status = "error", message = "db_unavailable")
                )
            }
        }

        get("/version") {
            val time = DateTimeFormatter.ISO_INSTANT.format(Instant.now())
            call.respond(
                VersionResponse(
                    service = "backend",
                    env = appEnv,
                    version = appVersion,
                    time = time
                )
            )
        }

        authenticate("miniapp-session") {
            route("/api") {
                route("/guest") {
                    get("/_ping") {
                        call.respond(mapOf("ok" to true))
                    }
                }
            }
        }

        staffChatLinkRoutes(staffChatLinkCodeRepository, venueAccessRepository, venueRepository)
        miniAppRoutes(miniAppDevServerUrl, miniAppStaticDir, httpClient)

        if (telegramConfig.enabled && telegramConfig.mode == TelegramBotConfig.Mode.WEBHOOK) {
            val router = telegramRouter
            if (router != null) {
                post(telegramConfig.webhookPath) {
                    val secret = telegramConfig.webhookSecretToken
                    if (!secret.isNullOrBlank()) {
                        val header = call.request.headers["X-Telegram-Bot-Api-Secret-Token"]
                        if (header != secret) {
                            call.respond(HttpStatusCode.Forbidden)
                            return@post
                        }
                    }
                    try {
                        val update = call.receive<TelegramUpdate>()
                        router.process(update)
                        call.respond(HttpStatusCode.OK)
                    } catch (e: Exception) {
                        logger.warn("Webhook processing failed: {}", sanitizeTelegramForLog(e.message))
                        logger.debugTelegramException(e) { "Webhook processing exception" }
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
    httpClient: HttpClient
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
                contentType = ContentType.Text.Plain
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
    httpClient: HttpClient
) {
    if (method != HttpMethod.Get && method != HttpMethod.Head) {
        respond(HttpStatusCode.MethodNotAllowed)
        return
    }

    val targetUrl = devServerUrl.trimEnd('/') + requestUri
    val response = httpClient.request(targetUrl) {
        this.method = method
    }

    val contentType = response.headers["Content-Type"]?.let { ContentType.parse(it) }

    if (method == HttpMethod.Head) {
        respondBytes(
            bytes = ByteArray(0),
            contentType = contentType,
            status = response.status
        )
        return
    }

    val bytes: ByteArray = response.body()
    respondBytes(
        bytes = bytes,
        contentType = contentType,
        status = response.status
    )
}

private suspend fun ApplicationCall.redirectToMiniAppRoot() {
    val query = request.queryString()
    val target = if (query.isBlank()) "/miniapp/" else "/miniapp/?$query"
    respondRedirect(url = target, permanent = false)
}

private fun Route.staffChatLinkRoutes(
    staffChatLinkCodeRepository: StaffChatLinkCodeRepository,
    venueAccessRepository: VenueAccessRepository,
    venueRepository: VenueRepository
) {
    route("/api") {
        post("/venue/{venueId}/staff-chat/link-code") {
            val venueId = call.parameters["venueId"]?.toLongOrNull()
            if (venueId == null) {
                call.respond(HttpStatusCode.BadRequest, mapOf("error" to "invalid_venue_id"))
                return@post
            }
            val requestBody = runCatching { call.receive<LinkCodeRequest>() }.getOrNull()
            val userId = call.request.headers["X-Telegram-User-Id"]?.toLongOrNull()
                ?: requestBody?.userId
            if (userId == null) {
                call.respond(HttpStatusCode.BadRequest, mapOf("error" to "missing_user"))
                return@post
            }
            val hasAccess = venueAccessRepository.hasVenueAdminOrOwner(userId, venueId)
            if (!hasAccess) {
                call.respond(HttpStatusCode.Forbidden, mapOf("error" to "forbidden"))
                return@post
            }
            val venueExists = venueRepository.findVenueById(venueId) != null
            if (!venueExists) {
                call.respond(HttpStatusCode.NotFound, mapOf("error" to "venue_not_found"))
                return@post
            }
            val created = staffChatLinkCodeRepository.createLinkCode(venueId, userId)
            if (created == null) {
                call.respond(HttpStatusCode.ServiceUnavailable, mapOf("error" to "db_unavailable"))
                return@post
            }
            logger.info(
                "Generated staff chat link code venueId={} by userId={} expiresAt={}",
                venueId,
                userId,
                created.expiresAt
            )
            call.respond(
                LinkCodeResponse(
                    code = created.code,
                    expiresAt = created.expiresAt.toString(),
                    ttlSeconds = created.ttlSeconds
                )
            )
        }
    }
}

private fun ApplicationConfig.optionalString(path: String): String? =
    if (propertyOrNull(path) != null) property(path).getString() else null

private fun redactJdbcUrl(jdbcUrl: String): String =
    jdbcUrl.replace(Regex("(?i)(password)=([^&;]+)"), "$1=***")
