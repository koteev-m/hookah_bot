package com.hookah.platform.backend.miniapp.venue.staff

internal object StaffInviteCodeFormat {
    const val MAX_CODE_LEN = 64
    const val CODE_ALPHABET = "ABCDEFGHJKMNPQRSTUVWXYZ23456789"

    private val codeAlphabetSet = CODE_ALPHABET.toSet()

    fun isLikelyValidCodeFormat(code: String): Boolean {
        if (code.isEmpty() || code.length > MAX_CODE_LEN) return false
        return code.all { it in codeAlphabetSet }
    }

    fun normalizeCode(raw: String?): String? {
        val trimmed = raw?.trim() ?: return null
        if (trimmed.isEmpty() || trimmed.length > MAX_CODE_LEN) return null
        val builder = StringBuilder(trimmed.length)
        for (ch in trimmed) {
            val upper = when (ch) {
                in 'a'..'z' -> (ch.code - 32).toChar()
                else -> ch
            }
            if (upper !in codeAlphabetSet) {
                return null
            }
            builder.append(upper)
        }
        return builder.toString()
    }
}
