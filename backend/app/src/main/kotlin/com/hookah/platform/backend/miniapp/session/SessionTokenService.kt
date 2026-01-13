package com.hookah.platform.backend.miniapp.session

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import io.ktor.server.config.ApplicationConfig
import java.time.Instant
import java.util.Date
import java.util.UUID

data class IssuedToken(val token: String, val expiresAtEpochSeconds: Long)

data class SessionTokenConfig(
    val jwtSecret: String,
    val ttlSeconds: Long,
    val issuer: String,
    val audience: String
) {
    init {
        require(ttlSeconds > 0) { "api.session.ttlSeconds must be positive" }
    }

    companion object {
        fun from(appConfig: ApplicationConfig, appEnv: String): SessionTokenConfig {
            val jwtSecret = resolveJwtSecret(appConfig, appEnv)
            val ttlSeconds = resolveTtlSeconds(appConfig)
            val issuer = appConfig.propertyOrNull("api.session.issuer")
                ?.getString()
                ?.takeIf { it.isNotBlank() }
                ?: "hookah-platform"
            val audience = appConfig.propertyOrNull("api.session.audience")
                ?.getString()
                ?.takeIf { it.isNotBlank() }
                ?: "hookah-miniapp"

            return SessionTokenConfig(
                jwtSecret = jwtSecret,
                ttlSeconds = ttlSeconds,
                issuer = issuer,
                audience = audience
            )
        }

        private fun resolveJwtSecret(appConfig: ApplicationConfig, appEnv: String): String {
            val fromConfig = appConfig.propertyOrNull("api.session.jwtSecret")
                ?.getString()
                ?.trim()
                ?.takeIf { it.isNotBlank() }
            val fromEnv = System.getenv("API_SESSION_JWT_SECRET")
                ?.trim()
                ?.takeIf { it.isNotBlank() }
            val defaultDevSecret = if (appEnv == "dev") "dev-jwt-secret" else null

            return fromConfig
                ?: fromEnv
                ?: defaultDevSecret
                ?: error("api.session.jwtSecret must be configured for env=$appEnv")
        }

        private fun resolveTtlSeconds(appConfig: ApplicationConfig): Long {
            val fromConfig = appConfig.propertyOrNull("api.session.ttlSeconds")
                ?.getString()
                ?.trim()
                ?.takeIf { it.isNotBlank() }
            val fromEnv = System.getenv("API_SESSION_TTL_SECONDS")
                ?.trim()
                ?.takeIf { it.isNotBlank() }

            return (fromConfig ?: fromEnv)?.toLongOrNull() ?: 3600L
        }
    }
}

class SessionTokenService(private val config: SessionTokenConfig) {
    private val algorithm = Algorithm.HMAC256(config.jwtSecret)

    fun issueToken(subjectTelegramUserId: Long, now: Instant = Instant.now()): IssuedToken {
        val issuedAt = now
        val expiresAt = issuedAt.plusSeconds(config.ttlSeconds)
        val token = JWT.create()
            .withSubject(subjectTelegramUserId.toString())
            .withIssuer(config.issuer)
            .withAudience(config.audience)
            .withIssuedAt(Date.from(issuedAt))
            .withExpiresAt(Date.from(expiresAt))
            .withJWTId(UUID.randomUUID().toString())
            .sign(algorithm)

        return IssuedToken(token = token, expiresAtEpochSeconds = expiresAt.epochSecond)
    }
}
