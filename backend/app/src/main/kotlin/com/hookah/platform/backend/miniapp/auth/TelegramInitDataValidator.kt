package com.hookah.platform.backend.miniapp.auth

import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

@Serializable
data class TelegramWebAppUser(
    val id: Long,
    val username: String? = null,
    val first_name: String? = null,
    val last_name: String? = null,
    val language_code: String? = null,
    val allows_write_to_pm: Boolean? = null,
)

data class TelegramInitDataValidated(
    val user: TelegramWebAppUser,
    val authDateEpochSeconds: Long,
)

fun parseInitData(raw: String): Map<String, String> {
    if (raw.isBlank()) return emptyMap()

    val result = LinkedHashMap<String, String>()
    raw.split("&").forEach { part ->
        if (part.isEmpty()) return@forEach
        val pieces = part.split("=", limit = 2)
        val rawKey = pieces.getOrElse(0) { "" }
        val rawValue = pieces.getOrElse(1) { "" }
        val key = runCatching { URLDecoder.decode(rawKey, StandardCharsets.UTF_8) }
            .getOrElse { throw TelegramInitDataError.InvalidFormat }
        val value = runCatching { URLDecoder.decode(rawValue, StandardCharsets.UTF_8) }
            .getOrElse { throw TelegramInitDataError.InvalidFormat }

        if (result.containsKey(key)) {
            throw TelegramInitDataError.InvalidFormat
        }

        result[key] = value
    }

    return result
}

fun buildDataCheckString(data: Map<String, String>): String {
    return data
        .filterKeys { it != "hash" }
        .toSortedMap()
        .entries
        .joinToString("\n") { (key, value) -> "$key=$value" }
}

internal fun normalizeHexLower64(value: String): String? {
    if (value.length != 64) return null

    val builder = StringBuilder(value.length)
    for (char in value) {
        val normalized = when (char) {
            in '0'..'9' -> char
            in 'a'..'f' -> char
            in 'A'..'F' -> (char.code + 32).toChar()
            else -> return null
        }
        builder.append(normalized)
    }

    return builder.toString()
}

sealed class TelegramInitDataError(message: String) : RuntimeException(message) {
    class MissingField(val field: String) : TelegramInitDataError("Missing field: $field")
    object InvalidHash : TelegramInitDataError("Invalid hash")
    object InvalidFormat : TelegramInitDataError("Invalid init data format")
    object InvalidAuthDate : TelegramInitDataError("Invalid auth_date")
    object Expired : TelegramInitDataError("Init data expired")
    object TooFarInFuture : TelegramInitDataError("auth_date is too far in the future")
    object BotTokenNotConfigured : TelegramInitDataError("Bot token is not configured")
    object InvalidUserJson : TelegramInitDataError("Invalid user json")
}

class TelegramInitDataValidator(
    private val botTokenProvider: () -> String?,
    private val nowEpochSeconds: () -> Long,
    private val maxAgeSeconds: Long,
    private val maxFutureSkewSeconds: Long,
) {
    private val json = Json { ignoreUnknownKeys = true }

    fun validate(rawInitData: String): TelegramInitDataValidated {
        val botToken = botTokenProvider()?.trim()?.takeIf { it.isNotBlank() }
            ?: throw TelegramInitDataError.BotTokenNotConfigured
        val data = parseInitData(rawInitData)

        val receivedHashRaw = data["hash"] ?: throw TelegramInitDataError.MissingField("hash")
        val receivedHash = normalizeHexLower64(receivedHashRaw) ?: throw TelegramInitDataError.InvalidHash
        val authDateString = data["auth_date"] ?: throw TelegramInitDataError.MissingField("auth_date")
        val userJson = data["user"] ?: throw TelegramInitDataError.MissingField("user")

        val authDate = authDateString.toLongOrNull() ?: throw TelegramInitDataError.InvalidAuthDate
        val now = nowEpochSeconds()

        if (now - authDate > maxAgeSeconds) {
            throw TelegramInitDataError.Expired
        }

        if (authDate - now > maxFutureSkewSeconds) {
            throw TelegramInitDataError.TooFarInFuture
        }

        val dataCheckString = buildDataCheckString(data)
        val calculatedHash = calculateTelegramInitDataHash(botToken, dataCheckString)

        if (!constantTimeEquals(receivedHash, calculatedHash)) {
            throw TelegramInitDataError.InvalidHash
        }

        val user = runCatching {
            json.decodeFromString<TelegramWebAppUser>(userJson)
        }.getOrElse { throwable ->
            if (throwable is SerializationException || throwable is IllegalArgumentException) {
                throw TelegramInitDataError.InvalidUserJson
            }
            throw throwable
        }

        return TelegramInitDataValidated(user = user, authDateEpochSeconds = authDate)
    }
}

internal fun calculateTelegramInitDataHash(botToken: String, dataCheckString: String): String {
    val secretKey = hmacSha256(WEB_APP_DATA_KEY, botToken.toByteArray(StandardCharsets.UTF_8))
    val dataHash = hmacSha256(secretKey, dataCheckString.toByteArray(StandardCharsets.UTF_8))
    return dataHash.toHexString()
}

internal fun hmacSha256(key: ByteArray, message: ByteArray): ByteArray {
    val mac = Mac.getInstance(HMAC_ALGORITHM)
    mac.init(SecretKeySpec(key, HMAC_ALGORITHM))
    return mac.doFinal(message)
}

internal fun ByteArray.toHexString(): String {
    val chars = CharArray(size * 2)
    for (i in indices) {
        val byte = this[i].toInt() and 0xFF
        chars[i * 2] = HEX_CHARS[byte ushr 4]
        chars[i * 2 + 1] = HEX_CHARS[byte and 0x0F]
    }
    return String(chars)
}

fun constantTimeEquals(a: String, b: String): Boolean {
    if (a.length != b.length) return false

    var result = 0
    for (i in a.indices) {
        result = result or (a[i].code xor b[i].code)
    }
    return result == 0
}

private const val HMAC_ALGORITHM = "HmacSHA256"
private val WEB_APP_DATA_KEY = "WebAppData".toByteArray(StandardCharsets.UTF_8)
private val HEX_CHARS = charArrayOf('0', '1', '2', '3', '4', '5', '6', '7', '8', '9', 'a', 'b', 'c', 'd', 'e', 'f')
