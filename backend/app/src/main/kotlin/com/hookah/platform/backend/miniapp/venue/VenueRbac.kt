package com.hookah.platform.backend.miniapp.venue

import java.util.Locale

enum class VenueRole {
    OWNER,
    MANAGER,
    STAFF
}

enum class VenuePermission {
    STAFF_CHAT_LINK,
    VENUE_SETTINGS,
    ORDER_STATUS_UPDATE,
    ORDER_QUEUE_VIEW,
    MENU_VIEW,
    MENU_MANAGE,
    TABLE_VIEW,
    TABLE_MANAGE,
    TABLE_TOKEN_ROTATE,
    TABLE_TOKEN_ROTATE_ALL,
    TABLE_QR_EXPORT
}

object VenueRoleMapping {
    /**
     * Maps raw DB roles from venue_members.role to API roles.
     *
     * ADMIN is a legacy alias for MANAGER to avoid confusing expectations about access levels.
     * MANAGER means a manager role (order status updates + queue view), STAFF is limited to queue view.
     */
    fun fromDb(role: String?): VenueRole? {
        if (role.isNullOrBlank()) {
            return null
        }
        return when (role.trim().uppercase(Locale.ROOT)) {
            "OWNER" -> VenueRole.OWNER
            "ADMIN", "MANAGER" -> VenueRole.MANAGER
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
                VenuePermission.ORDER_QUEUE_VIEW,
                VenuePermission.MENU_VIEW,
                VenuePermission.MENU_MANAGE,
                VenuePermission.TABLE_VIEW,
                VenuePermission.TABLE_MANAGE,
                VenuePermission.TABLE_TOKEN_ROTATE,
                VenuePermission.TABLE_TOKEN_ROTATE_ALL,
                VenuePermission.TABLE_QR_EXPORT
            )
            VenueRole.MANAGER -> setOf(
                VenuePermission.ORDER_STATUS_UPDATE,
                VenuePermission.ORDER_QUEUE_VIEW,
                VenuePermission.MENU_VIEW,
                VenuePermission.MENU_MANAGE,
                VenuePermission.TABLE_VIEW,
                VenuePermission.TABLE_MANAGE,
                VenuePermission.TABLE_QR_EXPORT
            )
            VenueRole.STAFF -> setOf(
                VenuePermission.ORDER_QUEUE_VIEW,
                VenuePermission.ORDER_STATUS_UPDATE,
                VenuePermission.MENU_VIEW,
                VenuePermission.TABLE_VIEW
            )
        }
    }
}
