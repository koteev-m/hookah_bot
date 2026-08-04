package com.hookah.platform.backend.miniapp.venue

import java.util.Locale

enum class VenueRole {
    OWNER,
    MANAGER,
    STAFF,
}

enum class VenuePermission {
    STAFF_ACCESS_VIEW,
    STAFF_INVITE_CREATE_STAFF,
    STAFF_INVITE_CREATE_MANAGER,
    STAFF_INVITE_REVOKE_STAFF,
    STAFF_INVITE_REVOKE_MANAGER,
    STAFF_PROFILE_MANAGE_STAFF,
    STAFF_PROFILE_PUBLISH_STAFF,
    STAFF_PROFILE_EDIT_OWN,
    STAFF_CHAT_LINK,
    VENUE_SETTINGS,
    ORDER_STATUS_UPDATE,
    ORDER_QUEUE_VIEW,
    BOOKING_VIEW,
    BOOKING_ARRIVAL_UPDATE,
    BOOKING_MANAGE,
    SUPPORT_VIEW,
    SUPPORT_MANAGE,
    SHIFT_EXTENSION_VIEW,
    SHIFT_EXTENSION_CONFIRM,
    SHIFT_EXTENSION_SETTINGS,
    FEEDBACK_VIEW,
    STAFF_SCHEDULE_VIEW,
    STAFF_SCHEDULE_VIEW_OWN,
    STAFF_SCHEDULE_MANAGE,
    STAFF_MODULE_SETTINGS_MANAGE,
    MENU_VIEW,
    MENU_MANAGE,
    MENU_AVAILABILITY_MANAGE,
    MENU_SHIFT_CHECK,
    TABLE_VIEW,
    TABLE_MANAGE,
    TABLE_TOKEN_ROTATE,
    TABLE_TOKEN_ROTATE_ALL,
    TABLE_QR_EXPORT,
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
            VenueRole.OWNER ->
                setOf(
                    VenuePermission.STAFF_ACCESS_VIEW,
                    VenuePermission.STAFF_INVITE_CREATE_STAFF,
                    VenuePermission.STAFF_INVITE_CREATE_MANAGER,
                    VenuePermission.STAFF_INVITE_REVOKE_STAFF,
                    VenuePermission.STAFF_INVITE_REVOKE_MANAGER,
                    VenuePermission.STAFF_PROFILE_MANAGE_STAFF,
                    VenuePermission.STAFF_PROFILE_PUBLISH_STAFF,
                    VenuePermission.STAFF_CHAT_LINK,
                    VenuePermission.VENUE_SETTINGS,
                    VenuePermission.ORDER_STATUS_UPDATE,
                    VenuePermission.ORDER_QUEUE_VIEW,
                    VenuePermission.BOOKING_VIEW,
                    VenuePermission.BOOKING_ARRIVAL_UPDATE,
                    VenuePermission.BOOKING_MANAGE,
                    VenuePermission.SUPPORT_VIEW,
                    VenuePermission.SUPPORT_MANAGE,
                    VenuePermission.SHIFT_EXTENSION_VIEW,
                    VenuePermission.SHIFT_EXTENSION_CONFIRM,
                    VenuePermission.SHIFT_EXTENSION_SETTINGS,
                    VenuePermission.FEEDBACK_VIEW,
                    VenuePermission.STAFF_SCHEDULE_VIEW,
                    VenuePermission.STAFF_SCHEDULE_MANAGE,
                    VenuePermission.STAFF_MODULE_SETTINGS_MANAGE,
                    VenuePermission.MENU_VIEW,
                    VenuePermission.MENU_MANAGE,
                    VenuePermission.MENU_AVAILABILITY_MANAGE,
                    VenuePermission.MENU_SHIFT_CHECK,
                    VenuePermission.TABLE_VIEW,
                    VenuePermission.TABLE_MANAGE,
                    VenuePermission.TABLE_TOKEN_ROTATE,
                    VenuePermission.TABLE_TOKEN_ROTATE_ALL,
                    VenuePermission.TABLE_QR_EXPORT,
                )
            VenueRole.MANAGER ->
                setOf(
                    VenuePermission.STAFF_ACCESS_VIEW,
                    VenuePermission.STAFF_INVITE_CREATE_STAFF,
                    VenuePermission.STAFF_INVITE_REVOKE_STAFF,
                    VenuePermission.STAFF_PROFILE_MANAGE_STAFF,
                    VenuePermission.STAFF_PROFILE_PUBLISH_STAFF,
                    VenuePermission.STAFF_PROFILE_EDIT_OWN,
                    VenuePermission.STAFF_CHAT_LINK,
                    VenuePermission.ORDER_STATUS_UPDATE,
                    VenuePermission.ORDER_QUEUE_VIEW,
                    VenuePermission.BOOKING_VIEW,
                    VenuePermission.BOOKING_ARRIVAL_UPDATE,
                    VenuePermission.BOOKING_MANAGE,
                    VenuePermission.SUPPORT_VIEW,
                    VenuePermission.SUPPORT_MANAGE,
                    VenuePermission.SHIFT_EXTENSION_VIEW,
                    VenuePermission.SHIFT_EXTENSION_CONFIRM,
                    VenuePermission.SHIFT_EXTENSION_SETTINGS,
                    VenuePermission.FEEDBACK_VIEW,
                    VenuePermission.STAFF_SCHEDULE_VIEW,
                    VenuePermission.STAFF_SCHEDULE_MANAGE,
                    VenuePermission.STAFF_MODULE_SETTINGS_MANAGE,
                    VenuePermission.MENU_VIEW,
                    VenuePermission.MENU_MANAGE,
                    VenuePermission.MENU_AVAILABILITY_MANAGE,
                    VenuePermission.MENU_SHIFT_CHECK,
                    VenuePermission.TABLE_VIEW,
                    VenuePermission.TABLE_MANAGE,
                    VenuePermission.TABLE_QR_EXPORT,
                )
            VenueRole.STAFF ->
                setOf(
                    VenuePermission.STAFF_PROFILE_EDIT_OWN,
                    VenuePermission.ORDER_QUEUE_VIEW,
                    VenuePermission.ORDER_STATUS_UPDATE,
                    VenuePermission.BOOKING_VIEW,
                    VenuePermission.BOOKING_ARRIVAL_UPDATE,
                    VenuePermission.SHIFT_EXTENSION_VIEW,
                    VenuePermission.SHIFT_EXTENSION_CONFIRM,
                    VenuePermission.STAFF_SCHEDULE_VIEW_OWN,
                    VenuePermission.MENU_VIEW,
                    VenuePermission.MENU_AVAILABILITY_MANAGE,
                    VenuePermission.TABLE_VIEW,
                )
        }
    }
}
