package com.hookah.platform.backend.miniapp.venue

enum class VenueRole {
    OWNER,
    MANAGER,
    STAFF
}

enum class VenuePermission {
    STAFF_CHAT_LINK,
    VENUE_SETTINGS,
    ORDER_STATUS_UPDATE,
    ORDER_QUEUE_VIEW
}

object VenueRoleMapping {
    fun fromDb(role: String?): VenueRole? {
        if (role.isNullOrBlank()) {
            return null
        }
        return when (role.trim().uppercase()) {
            "OWNER" -> VenueRole.OWNER
            "ADMIN" -> VenueRole.MANAGER
            "MANAGER" -> VenueRole.STAFF
            "STAFF" -> VenueRole.STAFF
            else -> null
        }
    }
}

object VenuePermissions {
    fun forRole(role: VenueRole): Set<VenuePermission> {
        return when (role) {
            VenueRole.OWNER -> setOf(
                VenuePermission.STAFF_CHAT_LINK,
                VenuePermission.VENUE_SETTINGS,
                VenuePermission.ORDER_STATUS_UPDATE,
                VenuePermission.ORDER_QUEUE_VIEW
            )
            VenueRole.MANAGER -> setOf(
                VenuePermission.ORDER_STATUS_UPDATE,
                VenuePermission.ORDER_QUEUE_VIEW
            )
            VenueRole.STAFF -> setOf(
                VenuePermission.ORDER_QUEUE_VIEW
            )
        }
    }
}
