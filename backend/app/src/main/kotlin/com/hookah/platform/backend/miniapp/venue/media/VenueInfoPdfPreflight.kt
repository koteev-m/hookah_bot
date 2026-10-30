package com.hookah.platform.backend.miniapp.venue.media

import com.hookah.platform.backend.api.VenueInfoMediaInvalidException

/** Bounded lexical admission for the supported single classic-xref subset, before OpenPDF. */
internal object VenueInfoPdfPreflight {
    private const val MAX_OBJECTS = 20000L
    private const val MAX_TOKENS = 100000
    private val forbidden = setOf("/Prev", "/XRefStm", "/ObjStm", "/XRef", "/Encrypt")

    fun check(bytes: ByteArray) {
        if (bytes.size !in 1..VENUE_INFO_MEDIA_MAX_BYTES) invalid()
        val lexer = Lexer(bytes)
        val tokens = mutableListOf<Token>()
        var depth = 0
        var streamLength: Int? = null
        while (true) {
            val token = lexer.next() ?: break
            if (tokens.size >= MAX_TOKENS || token.text in forbidden) invalid()
            if (token.text in setOf("[", "<<")) {
                depth++
                if (depth > 40) invalid()
            }
            if (token.text in setOf("]", ">>")) {
                depth--
                if (depth < 0) invalid()
            }
            if (tokens.lastOrNull()?.text == "/Length") streamLength = integer(token.text, bytes.size.toLong()).toInt()
            if (tokens.lastOrNull()?.text == "/Size") integer(token.text, MAX_OBJECTS)
            if (token.text in setOf("obj", "R")) {
                integer(tokens.getOrNull(tokens.size - 2)?.text, MAX_OBJECTS)
                integer(tokens.lastOrNull()?.text, 65535)
            }
            tokens.add(token)
            if (token.text == "stream") {
                lexer.skipStream(streamLength ?: invalid())
                streamLength = null
            }
            if (token.text == "endobj") streamLength = null
        }
        if (depth != 0) invalid()
        val marker = tokens.indexOfLast { it.text == "startxref" }
        if (marker < 0 || marker + 2 != tokens.size) invalid()
        val offset = integer(tokens[marker + 1].text, (bytes.size - 1).toLong()).toInt()
        val xref = Lexer(bytes, offset)
        if (xref.next()?.text != "xref") invalid()
        var entries = 0L
        val objectIds = mutableSetOf<Long>()
        val offsets = mutableSetOf<Long>()
        while (true) {
            val first = xref.next()?.text ?: invalid()
            if (first == "trailer") break
            val start = integer(first, MAX_OBJECTS)
            val count = integer(xref.next()?.text, MAX_OBJECTS)
            if (count == 0L || start + count > MAX_OBJECTS || entries + count > MAX_OBJECTS) invalid()
            entries += count
            repeat(count.toInt()) { index ->
                if (!objectIds.add(start + index)) invalid()
                val objectOffset = integer(xref.next()?.text, (bytes.size - 1).toLong())
                integer(xref.next()?.text, 65535)
                when (xref.next()?.text) {
                    "n" -> if (objectOffset == 0L || !offsets.add(objectOffset)) invalid()
                    "f" -> Unit
                    else -> invalid()
                }
            }
        }
        if (entries == 0L || tokens.count { it.text == "xref" } != 1) invalid()
    }

    private fun integer(
        text: String?,
        maximum: Long,
    ): Long {
        if (text == null || text.isEmpty() || text.any { it !in '0'..'9' }) invalid()
        return (text.toLongOrNull() ?: invalid()).also { if (it > maximum) invalid() }
    }

    private data class Token(val text: String)

    private class Lexer(private val bytes: ByteArray, private var position: Int = 0) {
        private fun at() = bytes[position].toInt() and 255

        private fun white(c: Int) = c in setOf(0, 9, 10, 12, 13, 32)

        private fun delimiter(c: Int) = white(c) || c in setOf(40, 41, 60, 62, 91, 93, 123, 125, 47, 37)

        fun next(): Token? {
            while (position < bytes.size) {
                if (white(at())) {
                    position++
                    continue
                }
                if (at() != 37) break
                while (position < bytes.size && at() !in setOf(10, 13)) position++
            }
            if (position == bytes.size) return null
            val start = position
            when (at()) {
                40 -> {
                    var depth = 1
                    position++
                    while (position < bytes.size && depth > 0) {
                        when (at()) {
                            92 -> {
                                position++
                                if (position < bytes.size) position++
                            }
                            40 -> {
                                depth++
                                position++
                                if (depth > 40) invalid()
                            }
                            41 -> {
                                depth--
                                position++
                            }
                            else -> position++
                        }
                    }
                    if (depth != 0) invalid()
                    return Token("string")
                }
                60 -> {
                    position++
                    if (position < bytes.size && at() == 60) {
                        position++
                        return Token("<<")
                    }
                    while (position < bytes.size && at() != 62) {
                        if (!white(at()) && at().toChar().digitToIntOrNull(16) == null) invalid()
                        position++
                    }
                    if (position == bytes.size) invalid()
                    position++
                    return Token("string")
                }
                62 -> {
                    position++
                    if (position == bytes.size || at() != 62) invalid()
                    position++
                    return Token(">>")
                }
                91, 93 -> {
                    position++
                    return Token(bytes[start].toInt().toChar().toString())
                }
                47 -> position++
            }
            while (position < bytes.size && !delimiter(at())) {
                position++
                if (position - start > 256) invalid()
            }
            if (position == start) invalid()
            val raw = String(bytes, start, position - start, Charsets.ISO_8859_1)
            if (!raw.startsWith('/')) return Token(raw)
            val decoded = StringBuilder()
            var i = 0
            while (i < raw.length) {
                if (raw[i] == '#') {
                    if (i + 2 >= raw.length) invalid()
                    val hi = raw[i + 1].digitToIntOrNull(16) ?: invalid()
                    val lo = raw[i + 2].digitToIntOrNull(16) ?: invalid()
                    decoded.append((hi * 16 + lo).toChar())
                    i += 3
                } else {
                    decoded.append(raw[i++])
                }
            }
            return Token(decoded.toString())
        }

        fun skipStream(length: Int) {
            if (position >= bytes.size) invalid()
            if (at() == 13) {
                position++
                if (position < bytes.size && at() == 10) position++
            } else if (at() == 10) {
                position++
            } else {
                invalid()
            }
            if (length > bytes.size - position) invalid()
            position += length
            if (next()?.text != "endstream") invalid()
        }
    }

    private fun invalid(): Nothing =
        throw VenueInfoMediaInvalidException(
            "PDF имеет неподдерживаемую структуру. Экспортируйте обычный плоский PDF без изменений и вложений.",
        )
}
