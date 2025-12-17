package com.hookah.platform.backend

import com.hookah.platform.backend.db.DbConfig
import com.hookah.platform.backend.db.DatabaseFactory
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.java.Java
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.request
import io.ktor.http.ContentType
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.ApplicationStopped
import io.ktor.server.application.ApplicationStopping
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.config.ApplicationConfig
import io.ktor.server.http.content.staticFiles
import io.ktor.server.plugins.callid.CallId
import io.ktor.server.plugins.callid.callIdMdc
import io.ktor.server.plugins.calllogging.CallLogging
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation as ServerContentNegotiation
import io.ktor.server.request.queryString
import io.ktor.server.request.uri
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytes
import io.ktor.server.response.respondRedirect
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.head
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.io.File
import java.time.Instant
import java.time.format.DateTimeFormatter
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

fun Application.module() {
    val json = Json {
        ignoreUnknownKeys = true
        prettyPrint = false
    }

    val appConfig = environment.config
    val dbConfig = DbConfig.from(appConfig)
    val appEnv = appConfig.optionalString("app.env") ?: "dev"
    val appVersion = appConfig.optionalString("app.version") ?: "dev"
    val miniAppDevServerUrl = appConfig.optionalString("miniapp.devServerUrl")?.takeIf { it.isNotBlank() }
    val miniAppStaticDir = appConfig.optionalString("miniapp.staticDir")?.takeIf { it.isNotBlank() }

    val httpClient = HttpClient(Java) {
        install(ContentNegotiation) {
            json(json)
        }
    }

    val dataSource = DatabaseFactory.init(dbConfig)

    if (dataSource != null) {
        logger.info("DB enabled with jdbcUrl={}", redactJdbcUrl(dbConfig.jdbcUrl.orEmpty()))
    }

    environment.monitor.subscribe(ApplicationStopping) {
        logger.info("Application stopping, closing resources")
        httpClient.close()
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

        miniAppRoutes(miniAppDevServerUrl, miniAppStaticDir, httpClient)
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

private fun ApplicationConfig.optionalString(path: String): String? =
    if (propertyOrNull(path) != null) property(path).getString() else null

private fun redactJdbcUrl(jdbcUrl: String): String =
    jdbcUrl.replace(Regex("(?i)(password)=([^&;]+)"), "$1=***")
