package com.hookah.platform.backend.miniapp.venue

import java.util.Locale

enum class VenueStatus(val dbValue: String) {
    DRAFT("DRAFT"),
    PUBLISHED("PUBLISHED"),
    HIDDEN("HIDDEN"),
    PAUSED("PAUSED"),
    SUSPENDED("SUSPENDED"),
    ARCHIVED("ARCHIVED"),
    DELETED("DELETED"),
    ;

    companion object {
        fun fromDb(value: String?): VenueStatus? {
            val normalized = value?.trim()?.uppercase(Locale.ROOT) ?: return null
            return entries.firstOrNull { it.dbValue == normalized }
        }
    }
}
