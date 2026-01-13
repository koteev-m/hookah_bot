package com.hookah.platform.backend.miniapp.auth

import com.hookah.platform.backend.api.ConfigException
import com.hookah.platform.backend.api.DatabaseUnavailableException
import com.hookah.platform.backend.api.InitDataInvalidException
import com.hookah.platform.backend.api.InvalidInputException
import com.hookah.platform.backend.miniapp.api.MiniAppUserDto
import com.hookah.platform.backend.miniapp.api.TelegramAuthRequest
import com.hookah.platform.backend.miniapp.api.TelegramAuthResponse
import com.hookah.platform.backend.miniapp.session.SessionTokenService
import com.hookah.platform.backend.telegram.User
import com.hookah.platform.backend.telegram.db.UserRepository
import io.ktor.server.application.call
import io.ktor.server.config.ApplicationConfig
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import java.sql.SQLException
import java.time.Instant

private const val INIT_DATA_MAX_LENGTH = 8192
private const val INIT_DATA_MAX_AGE_SECONDS = 300L
private const val INIT_DATA_MAX_FUTURE_SKEW_SECONDS = 30L

fun Route.miniAppAuthRoutes(
    appConfig: ApplicationConfig,
    sessionTokenService: SessionTokenService,
    userRepository: UserRepository
) {
    val authService = TelegramAuthService(
        appConfig = appConfig,
        userRepository = userRepository,
        sessionTokenService = sessionTokenService
    )
    route("/api") {
        post("/auth/telegram") {
            val request = call.receive<TelegramAuthRequest>()
            val initData = request.initData
            validateInitData(initData)

            val result = authService.authenticate(initData)

            call.respond(
                TelegramAuthResponse(
                    token = result.token.token,
                    expiresAtEpochSeconds = result.token.expiresAtEpochSeconds,
                    user = MiniAppUserDto(
                        telegramUserId = result.user.id,
                        username = result.user.username,
                        firstName = result.user.first_name,
                        lastName = result.user.last_name
                    )
                )
            )
        }
    }
}

private fun validateInitData(initData: String) {
    if (initData.isBlank()) {
        throw InvalidInputException("initData is required")
    }
    if (initData.length > INIT_DATA_MAX_LENGTH) {
        throw InvalidInputException("initData is too long")
    }
}

private class TelegramAuthService(
    private val appConfig: ApplicationConfig,
    private val userRepository: UserRepository,
    private val sessionTokenService: SessionTokenService
) {
    suspend fun authenticate(initData: String): TelegramAuthResult {
        val botToken = resolveTelegramBotToken(appConfig)
        val validator = TelegramInitDataValidator(
            botTokenProvider = { botToken },
            nowEpochSeconds = { Instant.now().epochSecond },
            maxAgeSeconds = INIT_DATA_MAX_AGE_SECONDS,
            maxFutureSkewSeconds = INIT_DATA_MAX_FUTURE_SKEW_SECONDS
        )

        val validated = try {
            validator.validate(initData)
        } catch (error: TelegramInitDataError) {
            when (error) {
                TelegramInitDataError.BotTokenNotConfigured -> throw ConfigException("Telegram bot token is required")
                TelegramInitDataError.InvalidHash,
                TelegramInitDataError.Expired,
                TelegramInitDataError.TooFarInFuture,
                is TelegramInitDataError.MissingField,
                TelegramInitDataError.InvalidAuthDate,
                TelegramInitDataError.InvalidUserJson,
                TelegramInitDataError.InvalidFormat -> throw InitDataInvalidException()
            }
        }

        val upsertedId = try {
            userRepository.upsert(
                User(
                    id = validated.user.id,
                    username = validated.user.username,
                    firstName = validated.user.first_name,
                    lastName = validated.user.last_name
                )
            )
        } catch (e: SQLException) {
            throw DatabaseUnavailableException()
        }

        if (upsertedId == null) {
            throw DatabaseUnavailableException()
        }

        val issuedToken = sessionTokenService.issueToken(validated.user.id)

        return TelegramAuthResult(token = issuedToken, user = validated.user)
    }
}

private fun resolveTelegramBotToken(config: ApplicationConfig): String {
    val fromConfig = config.propertyOrNull("telegram.token")
        ?.getString()
        ?.trim()
        ?.takeIf { it.isNotBlank() }
    val fromEnv = System.getenv("TELEGRAM_BOT_TOKEN")
        ?.trim()
        ?.takeIf { it.isNotBlank() }

    return fromConfig
        ?: fromEnv
        ?: throw ConfigException("Telegram bot token is required")
}

private data class TelegramAuthResult(
    val token: com.hookah.platform.backend.miniapp.session.IssuedToken,
    val user: TelegramWebAppUser
)
