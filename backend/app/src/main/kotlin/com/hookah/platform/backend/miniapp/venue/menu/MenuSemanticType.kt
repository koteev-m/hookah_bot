package com.hookah.platform.backend.miniapp.venue.menu

import java.util.Locale

enum class MenuSemanticType(val dbValue: String) {
    HOOKAH("HOOKAH"),
    TEA("TEA"),
    DRINK("DRINK"),
    FOOD("FOOD"),
    OTHER("OTHER"),
    ;

    companion object {
        fun fromDb(value: String?): MenuSemanticType {
            val normalized = value?.trim()?.uppercase(Locale.ROOT)
            return entries.firstOrNull { it.dbValue == normalized } ?: OTHER
        }

        fun nullableFromDb(value: String?): MenuSemanticType? {
            val normalized = value?.trim()?.uppercase(Locale.ROOT) ?: return null
            return entries.firstOrNull { it.dbValue == normalized }
        }
    }
}
