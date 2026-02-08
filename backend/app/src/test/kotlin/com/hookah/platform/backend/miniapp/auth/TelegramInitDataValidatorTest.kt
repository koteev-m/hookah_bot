package com.hookah.platform.backend.miniapp.auth

import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class TelegramInitDataValidatorTest {
    private val botToken = "test-bot-token"
    private val nowEpochSeconds = 1_700_000_000L
    private val maxAgeSeconds = 300L
    private val maxFutureSkewSeconds = 30L

    private val validator =
        TelegramInitDataValidator(
            botTokenProvider = { botToken },
            nowEpochSeconds = { nowEpochSeconds },
            maxAgeSeconds = maxAgeSeconds,
            maxFutureSkewSeconds = maxFutureSkewSeconds,
        )

    @Test
    fun `valid init data passes validation`() {
        val userJson = """{"id":12345,"username":"john"}"""
        val initData = generateValidInitData(botToken, userJson, nowEpochSeconds)

        val validated = validator.validate(initData)

        assertEquals(12345, validated.user.id)
        assertEquals(nowEpochSeconds, validated.authDateEpochSeconds)
    }

    @Test
    fun `invalid hash triggers error`() {
        val userJson = """{"id":12345,"username":"john"}"""
        val initData = generateValidInitData(botToken, userJson, nowEpochSeconds)
        val tampered = initData.replace(Regex("hash=[^&]+"), "hash=deadbeef")

        assertFailsWith<TelegramInitDataError.InvalidHash> {
            validator.validate(tampered)
        }
    }

    @Test
    fun `missing auth date fails`() {
        val userJson = """{"id":1}"""
        val initData =
            generateValidInitData(
                botToken,
                userJson,
                nowEpochSeconds,
                includeAuthDate = false,
            )

        assertFailsWith<TelegramInitDataError.MissingField> {
            validator.validate(initData)
        }
    }

    @Test
    fun `expired auth date fails`() {
        val userJson = """{"id":1}"""
        val expiredDate = nowEpochSeconds - maxAgeSeconds - 1
        val initData = generateValidInitData(botToken, userJson, expiredDate)

        assertFailsWith<TelegramInitDataError.Expired> {
            validator.validate(initData)
        }
    }

    @Test
    fun `auth date too far in future fails`() {
        val userJson = """{"id":1}"""
        val futureDate = nowEpochSeconds + maxFutureSkewSeconds + 1
        val initData = generateValidInitData(botToken, userJson, futureDate)

        assertFailsWith<TelegramInitDataError.TooFarInFuture> {
            validator.validate(initData)
        }
    }

    @Test
    fun `missing bot token fails`() {
        val userJson = """{"id":1}"""
        val initData = generateValidInitData(botToken, userJson, nowEpochSeconds)

        val failingValidator =
            TelegramInitDataValidator(
                botTokenProvider = { null },
                nowEpochSeconds = { nowEpochSeconds },
                maxAgeSeconds = maxAgeSeconds,
                maxFutureSkewSeconds = maxFutureSkewSeconds,
            )

        assertFailsWith<TelegramInitDataError.BotTokenNotConfigured> {
            failingValidator.validate(initData)
        }
    }

    @Test
    fun `invalid user json fails`() {
        val userJson = "not-json"
        val initData = generateValidInitData(botToken, userJson, nowEpochSeconds)

        assertFailsWith<TelegramInitDataError.InvalidUserJson> {
            validator.validate(initData)
        }
    }

    @Test
    fun `duplicate keys are rejected`() {
        assertFailsWith<TelegramInitDataError.InvalidFormat> {
            parseInitData("user=1&user=2")
        }
    }

    @Test
    fun `uppercase hash is accepted`() {
        val userJson = """{"id":1}"""
        val initData = generateValidInitData(botToken, userJson, nowEpochSeconds)
        val uppercasedHash =
            initData.replace(Regex("(hash=)([a-f0-9]{64})")) { matchResult ->
                val prefix = matchResult.groupValues[1]
                val hashValue = matchResult.groupValues[2]
                prefix + hashValue.uppercase()
            }

        val validated = validator.validate(uppercasedHash)

        assertEquals(1, validated.user.id)
    }

    @Test
    fun `broken percent encoding is invalid format`() {
        val broken = "user=%ZZ&auth_date=1700000000&hash=00"

        assertFailsWith<TelegramInitDataError.InvalidFormat> {
            validator.validate(broken)
        }
    }

    private fun generateValidInitData(
        botToken: String,
        userJson: String,
        authDate: Long,
        extraFields: Map<String, String> = emptyMap(),
        includeAuthDate: Boolean = true,
    ): String {
        val params = LinkedHashMap<String, String>()
        if (includeAuthDate) {
            params["auth_date"] = authDate.toString()
        }
        params["user"] = userJson
        params.putAll(extraFields)

        val dataCheckString = buildDataCheckString(params)
        val hash = calculateTelegramInitDataHash(botToken, dataCheckString)

        val finalParams = LinkedHashMap(params)
        finalParams["hash"] = hash

        return finalParams.entries.joinToString("&") { (key, value) ->
            "${URLEncoder.encode(key, StandardCharsets.UTF_8)}=${URLEncoder.encode(value, StandardCharsets.UTF_8)}"
        }
    }
}
