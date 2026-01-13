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
private const val DEFAULT_INIT_DATA_MAX_AGE_SECONDS = 300L
private const val DEFAULT_INIT_DATA_MAX_FUTURE_SKEW_SECONDS = 30L

fun Route.miniAppAuthRoutes(
    appConfig: ApplicationConfig,
    sessionTokenService: SessionTokenService,
    userRepository: UserRepository
) {
    val initDataConfig = initDataValidationConfig(appConfig)
    val authService = TelegramAuthService(
        appConfig = appConfig,
        userRepository = userRepository,
        sessionTokenService = sessionTokenService,
        initDataConfig = initDataConfig
    )
    route("/api") {
        post("/auth/telegram") {
            val request = call.receive<TelegramAuthRequest>()
            val initData = request.initData
            validateInitData(initData, initDataConfig)

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

private fun validateInitData(initData: String, config: InitDataValidationConfig) {
    if (initData.isBlank()) {
        throw InvalidInputException("initData is required")
    }
    if (initData.length > config.maxLength) {
        throw InvalidInputException("initData is too long")
    }
}

private class TelegramAuthService(
    private val appConfig: ApplicationConfig,
    private val userRepository: UserRepository,
    private val sessionTokenService: SessionTokenService,
    private val initDataConfig: InitDataValidationConfig
) {
    private val validator = TelegramInitDataValidator(
        botTokenProvider = { findTelegramBotToken(appConfig) },
        nowEpochSeconds = { Instant.now().epochSecond },
        maxAgeSeconds = initDataConfig.maxAgeSeconds,
        maxFutureSkewSeconds = initDataConfig.maxFutureSkewSeconds
    )

    suspend fun authenticate(initData: String): TelegramAuthResult {
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

private fun findTelegramBotToken(config: ApplicationConfig): String? {
    val fromConfig = config.propertyOrNull("telegram.token")
        ?.getString()
        ?.trim()
        ?.takeIf { it.isNotBlank() }
    val fromEnv = System.getenv("TELEGRAM_BOT_TOKEN")
        ?.trim()
        ?.takeIf { it.isNotBlank() }

    return fromConfig ?: fromEnv
}

private data class TelegramAuthResult(
    val token: com.hookah.platform.backend.miniapp.session.IssuedToken,
    val user: TelegramWebAppUser
)

private data class InitDataValidationConfig(
    val maxAgeSeconds: Long,
    val maxFutureSkewSeconds: Long,
    val maxLength: Int
)

private fun initDataValidationConfig(appConfig: ApplicationConfig): InitDataValidationConfig {
    return InitDataValidationConfig(
        maxAgeSeconds = resolvePositiveLong(
            appConfig = appConfig,
            configKey = "telegram.miniapp.initData.maxAgeSeconds",
            envKey = "TELEGRAM_MINIAPP_INITDATA_MAX_AGE_SECONDS",
            defaultValue = DEFAULT_INIT_DATA_MAX_AGE_SECONDS
        ),
        maxFutureSkewSeconds = resolvePositiveLong(
            appConfig = appConfig,
            configKey = "telegram.miniapp.initData.maxFutureSkewSeconds",
            envKey = "TELEGRAM_MINIAPP_INITDATA_MAX_FUTURE_SKEW_SECONDS",
            defaultValue = DEFAULT_INIT_DATA_MAX_FUTURE_SKEW_SECONDS
        ),
        maxLength = INIT_DATA_MAX_LENGTH
    )
}

private fun resolvePositiveLong(
    appConfig: ApplicationConfig,
    configKey: String,
    envKey: String,
    defaultValue: Long
): Long {
    val fromConfig = appConfig.propertyOrNull(configKey)
        ?.getString()
        ?.trim()
        ?.takeIf { it.isNotBlank() }
    val fromEnv = System.getenv(envKey)
        ?.trim()
        ?.takeIf { it.isNotBlank() }

    val raw = fromConfig ?: fromEnv ?: return defaultValue
    val parsed = raw.toLongOrNull()
    if (parsed == null || parsed <= 0) {
        error("$configKey/$envKey must be a positive number")
    }
    return parsed
}
