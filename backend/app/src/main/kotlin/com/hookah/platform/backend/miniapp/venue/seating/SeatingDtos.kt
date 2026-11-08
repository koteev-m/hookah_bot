package com.hookah.platform.backend.miniapp.venue.seating

import kotlinx.serialization.Serializable

@Serializable
data class SeatingRules(
    val revision: Long = 0,
    val usePlan: Boolean = false,
    val guestTableChoice: Boolean = false,
    val limitDuration: Boolean = false,
    val durationMinutes: Int = 120,
    val bufferMinutes: Int = 0,
)

@Serializable
data class HallDto(
    val id: Long,
    val name: String,
    val active: Boolean,
    val revision: Long,
    val planId: String? = null,
    val planWidth: Int? = null,
    val planHeight: Int? = null,
)

@Serializable
data class TableLayout(
    val x: Double,
    val y: Double,
    val width: Double,
    val height: Double,
    val rotation: Double = 0.0,
    val shape: String = "RECTANGLE",
)

@Serializable
data class HallTableDto(
    val id: Long,
    val number: Int,
    val hallId: Long?,
    val capacity: Int?,
    val active: Boolean,
    val revision: Long,
    val layout: TableLayout?,
    val state: String,
    val reason: String? = null,
    val seating: SeatingDto? = null,
    val nextBooking: TableBookingDto? = null,
)

@Serializable
data class SeatingDto(
    val id: Long,
    val tableId: Long,
    val visitId: Long,
    val tableSessionId: Long,
    val bookingId: Long?,
    val partySize: Int,
    val startedAt: String,
    val agreedEndAt: String?,
    val revision: Long,
)

@Serializable
data class TableBookingDto(
    val id: Long,
    val tableId: Long?,
    val scheduledAt: String,
    val agreedEndAt: String?,
    val partySize: Int?,
    val status: String,
    val source: String,
    val guestName: String?,
    val phone: String?,
    val comment: String?,
    val createdBy: Long?,
    val revision: Long,
)

@Serializable
data class HallState(
    val venueId: Long,
    val timezone: String,
    val rules: SeatingRules,
    val halls: List<HallDto>,
    val tables: List<HallTableDto>,
    val bookings: List<TableBookingDto>,
    val unresolvedBookings: Int,
    val imageUploadAvailable: Boolean = false,
)

@Serializable
data class SeatingCommand(
    val requestId: String,
    val action: String,
    val id: Long? = null,
    val revision: Long? = null,
    val tableId: Long? = null,
    val hallId: Long? = null,
    val name: String? = null,
    val phone: String? = null,
    val comment: String? = null,
    val active: Boolean? = null,
    val number: Int? = null,
    val capacity: Int? = null,
    val layout: TableLayout? = null,
    val rules: SeatingRules? = null,
    val localStart: String? = null,
    val localEnd: String? = null,
    val partySize: Int? = null,
    val bookingId: Long? = null,
    val shorterVisitAcknowledged: Boolean = false,
    val reconcileLegacySession: Boolean = false,
    val planId: String? = null,
    val confirmPlanReplacement: Boolean = false,
)

@Serializable
data class SeatingResult(val id: Long, val revision: Long = 0)

@Serializable
data class TableAvailability(
    val venueId: Long,
    val timezone: String,
    val enabled: Boolean,
    val scheduledAt: String?,
    val agreedEndAt: String?,
    val reason: String?,
    val halls: List<HallDto>,
    val tables: List<HallTableDto>,
)

@Serializable
data class HallPlanUpload(val id: String, val width: Int, val height: Int)
