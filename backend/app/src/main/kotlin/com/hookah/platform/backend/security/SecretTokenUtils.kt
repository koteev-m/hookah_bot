package com.hookah.platform.backend.security

import java.net.Inet6Address
import java.net.InetAddress
import java.security.MessageDigest

fun constantTimeEquals(expected: String, provided: String?): Boolean {
    if (provided == null) return false
    return MessageDigest.isEqual(
        expected.toByteArray(Charsets.UTF_8),
        provided.toByteArray(Charsets.UTF_8)
    )
}

class IpAllowlist private constructor(private val entries: List<IpRange>) {
    fun isAllowed(clientIp: String?): Boolean {
        if (clientIp.isNullOrBlank()) return false
        val address = parseAddress(clientIp) ?: return false
        return entries.any { it.matches(address) }
    }

    companion object {
        fun parse(raw: String?): IpAllowlist? {
            val items = raw
                ?.split(',')
                ?.map { it.trim() }
                ?.filter { it.isNotEmpty() }
                .orEmpty()
            if (items.isEmpty()) return null

            val entries = items.map { value ->
                IpRange.parse(value) ?: error("Invalid IP allowlist entry: $value")
            }
            return IpAllowlist(entries)
        }
    }
}

private data class IpRange(
    val networkBytes: ByteArray,
    val prefixLength: Int
) {
    fun matches(address: InetAddress): Boolean {
        val bytes = address.address
        if (bytes.size != networkBytes.size) return false
        val masked = applyMask(bytes, prefixLength)
        return masked.contentEquals(networkBytes)
    }

    companion object {
        fun parse(raw: String): IpRange? {
            val trimmed = raw.trim()
            if (trimmed.isEmpty()) return null

            val parts = trimmed.split('/', limit = 2)
            val address = parseAddress(parts[0]) ?: return null
            val maxBits = address.address.size * 8
            val prefix = if (parts.size == 2) {
                parts[1].toIntOrNull()?.takeIf { it in 0..maxBits } ?: return null
            } else {
                maxBits
            }

            val networkBytes = applyMask(address.address, prefix)
            return IpRange(networkBytes = networkBytes, prefixLength = prefix)
        }
    }
}

private fun applyMask(bytes: ByteArray, prefixLength: Int): ByteArray {
    val result = bytes.copyOf()
    val fullBytes = prefixLength / 8
    val remainder = prefixLength % 8
    for (i in fullBytes until result.size) {
        result[i] = if (i == fullBytes && remainder > 0) {
            val mask = (0xFF shl (8 - remainder)) and 0xFF
            (result[i].toInt() and mask).toByte()
        } else {
            0
        }
    }
    return result
}

private fun parseAddress(value: String): InetAddress? {
    val trimmed = value.trim()
    if (trimmed.isEmpty()) return null

    return if (trimmed.contains(':')) {
        if (!isValidIpv6Literal(trimmed)) return null
        try {
            val address = InetAddress.getByName(trimmed)
            if (address is Inet6Address) address else null
        } catch (e: Exception) {
            null
        }
    } else {
        parseIpv4Literal(trimmed)
    }
}

private fun parseIpv4Literal(value: String): InetAddress? {
    if (value.any { it != '.' && !it.isDigit() }) return null
    val parts = value.split('.', limit = 4)
    if (parts.size != 4) return null
    val bytes = ByteArray(4)
    for ((index, part) in parts.withIndex()) {
        if (part.isEmpty()) return null
        val number = part.toIntOrNull() ?: return null
        if (number !in 0..255) return null
        bytes[index] = number.toByte()
    }
    return try {
        InetAddress.getByAddress(bytes)
    } catch (e: Exception) {
        null
    }
}

private fun isValidIpv6Literal(value: String): Boolean {
    if (!value.contains(':')) return false
    return value.all { it.isDigit() || it in 'a'..'f' || it in 'A'..'F' || it == ':' || it == '.' }
}
