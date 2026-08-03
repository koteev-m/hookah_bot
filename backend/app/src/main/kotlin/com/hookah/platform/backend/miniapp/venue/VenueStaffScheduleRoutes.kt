package com.hookah.platform.backend.miniapp.venue

import com.hookah.platform.backend.api.ApiErrorCodes
import com.hookah.platform.backend.api.ForbiddenException
import com.hookah.platform.backend.api.InvalidInputException
import com.hookah.platform.backend.api.NotFoundException
import com.hookah.platform.backend.api.StaffShiftInvalidIntervalException
import com.hookah.platform.backend.miniapp.venue.staff.StaffScheduleAllowedAction
import com.hookah.platform.backend.miniapp.venue.staff.StaffScheduleBatchAssignment
import com.hookah.platform.backend.miniapp.venue.staff.StaffScheduleBatchOperation
import com.hookah.platform.backend.miniapp.venue.staff.StaffScheduleConfirmationState
import com.hookah.platform.backend.miniapp.venue.staff.StaffScheduleIntervalState
import com.hookah.platform.backend.miniapp.venue.staff.StaffScheduleLifecycle
import com.hookah.platform.backend.miniapp.venue.staff.StaffScheduleResolvedInterval
import com.hookah.platform.backend.miniapp.venue.staff.VenueStaffProfileRepository
import com.hookah.platform.backend.miniapp.venue.staff.VenueStaffScheduledShift
import com.hookah.platform.backend.miniapp.venue.staff.computeStaffScheduleLifecycle
import com.hookah.platform.backend.miniapp.venue.staff.invalidStaffScheduleAllowedActions
import com.hookah.platform.backend.miniapp.venue.staff.isRestorableCanceledShift
import com.hookah.platform.backend.miniapp.venue.staff.resolveStaffScheduleInterval
import com.hookah.platform.backend.miniapp.venue.staff.staffScheduleConfirmationState
import com.hookah.platform.backend.telegram.db.VenueAccessRepository
import com.hookah.platform.backend.telegram.db.VenueBookingHours
import com.hookah.platform.backend.telegram.db.VenueBookingHoursRepository
import com.hookah.platform.backend.telegram.db.VenueSettingsRepository
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.route
import kotlinx.serialization.Serializable
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.util.Locale

@Serializable
data class VenueStaffScheduleListResponse(
    val venueId: Long,
    val venueName: String? = null,
    val timezone: String,
    val venueToday: String,
    val from: String,
    val to: String,
    val effectiveHours: List<VenueStaffScheduleEffectiveHoursDto>,
    val shifts: List<VenueStaffScheduleShiftDto>,
)

@Serializable
enum class VenueStaffScheduleEffectiveHoursState {
    OPEN,
    CLOSED,
    NOT_CONFIGURED,
}

@Serializable
data class VenueStaffScheduleEffectiveHoursDto(
    val serviceDate: String,
    val state: VenueStaffScheduleEffectiveHoursState,
    val endsNextDay: Boolean,
    val opensAt: String? = null,
    val closesAt: String? = null,
)

@Serializable
data class VenueStaffOwnScheduleResponse(
    val venueId: Long,
    val venueName: String? = null,
    val timezone: String,
    val venueToday: String,
    val from: String,
    val to: String,
    val shifts: List<VenueStaffOwnScheduleShiftDto>,
)

@Serializable
data class VenueStaffScheduleShiftDto(
    val id: Long,
    val staffProfileId: Long,
    val displayName: String,
    val roleLabel: String? = null,
    val subtype: String,
    val shiftDate: String,
    val startsAt: String,
    val endsAt: String,
    val endsNextDay: Boolean,
    val computedStatus: String? = null,
    val cancelConfirmationState: String? = null,
    val updatedAt: String,
    val storedStatus: String,
    val isGuestVisible: Boolean,
    val manuallyMarkedActive: Boolean,
    val restoreAllowed: Boolean = false,
    val warning: VenueStaffScheduleWarningDto? = null,
)

@Serializable
data class VenueStaffScheduleWarningDto(
    val code: String,
    val message: String,
    val allowedActions: List<String>,
)

@Serializable
data class VenueStaffOwnScheduleShiftDto(
    val id: Long,
    val staffProfileId: Long,
    val shiftDate: String,
    val startsAt: String,
    val endsAt: String,
    val endsNextDay: Boolean,
    val computedStatus: String,
    val colleagues: List<VenueStaffScheduleColleagueDto>,
)

@Serializable
data class VenueStaffScheduleColleagueDto(
    val staffProfileId: Long,
    val displayName: String,
    val roleLabel: String? = null,
    val subtype: String,
    val shiftDate: String,
    val startsAt: String,
    val endsAt: String,
    val endsNextDay: Boolean,
    val computedStatus: String,
)

@Serializable
data class VenueStaffScheduleCreateRequest(
    val staffProfileId: Long,
    val shiftDate: String,
    val startsAt: String,
    val endsAt: String,
)

@Serializable
data class VenueStaffScheduleUpdateRequest(
    val shiftDate: String,
    val startsAt: String,
    val endsAt: String,
    val expectedUpdatedAt: String,
)

@Serializable
data class VenueStaffScheduleCancelRequest(
    val expectedUpdatedAt: String,
    val expectedConfirmationState: String,
)

@Serializable
data class VenueStaffScheduleRestoreRequest(
    val expectedUpdatedAt: String,
    val startsAt: String? = null,
    val endsAt: String? = null,
)

@Serializable
enum class VenueStaffScheduleBatchOperation {
    CREATE,
    RESTORE,
}

@Serializable
data class VenueStaffScheduleBatchAssignmentRequest(
    val staffProfileId: Long,
    val shiftDate: String,
    val startsAt: String,
    val endsAt: String,
    val operation: VenueStaffScheduleBatchOperation,
    val expectedUpdatedAt: String? = null,
)

@Serializable
data class VenueStaffScheduleBatchRequest(
    val assignments: List<VenueStaffScheduleBatchAssignmentRequest>,
)

@Serializable
data class VenueStaffScheduleMutationResponse(
    val shift: VenueStaffScheduleShiftDto,
)

@Serializable
data class VenueStaffScheduleBatchMutationResponse(
    val shifts: List<VenueStaffScheduleShiftDto>,
)

fun Route.venueStaffScheduleRoutes(
    venueAccessRepository: VenueAccessRepository,
    venueStaffProfileRepository: VenueStaffProfileRepository,
    venueBookingHoursRepository: VenueBookingHoursRepository,
    venueSettingsRepository: VenueSettingsRepository,
    auditLogRepository: AuditLogRepository,
    clock: Clock = Clock.systemUTC(),
) {
    route("/venue") {
        get("/{venueId}/staff/shifts") {
            val membership = call.requireStaffScheduleMembership(venueAccessRepository)
            membership.requirePermission(VenuePermission.STAFF_SCHEDULE_VIEW)
            val context = resolveStaffScheduleContext(venueSettingsRepository, membership.venueId, clock)
            val range = call.requireStaffScheduleRange(context.venueToday)
            val serviceDates = range.serviceDates()
            val hoursByDate =
                venueBookingHoursRepository
                    .findByVenuesAndDates(mapOf(membership.venueId to serviceDates.toSet()))[membership.venueId]
                    .orEmpty()
            val rows =
                venueStaffProfileRepository.listScheduledShifts(
                    venueId = membership.venueId,
                    from = range.from,
                    to = range.to,
                )
            call.respond(
                VenueStaffScheduleListResponse(
                    venueId = membership.venueId,
                    venueName = membership.venueName,
                    timezone = context.zoneId.id,
                    venueToday = context.venueToday.toString(),
                    from = range.from.toString(),
                    to = range.to.toString(),
                    effectiveHours = serviceDates.map { date -> hoursByDate[date].toEffectiveHoursDto(date) },
                    shifts = rows.mapNotNull { it.toAdminDto(context) },
                ),
            )
        }

        get("/{venueId}/staff/shifts/me") {
            val membership = call.requireStaffScheduleMembership(venueAccessRepository)
            membership.requirePermission(VenuePermission.STAFF_SCHEDULE_VIEW_OWN)
            val context = resolveStaffScheduleContext(venueSettingsRepository, membership.venueId, clock)
            val range = call.requireStaffScheduleRange(context.venueToday)
            val candidates =
                venueStaffProfileRepository.listScheduledShifts(
                    venueId = membership.venueId,
                    from = range.from.minusDays(1),
                    to = range.to.plusDays(1),
                )
            val resolvedCandidates = candidates.mapNotNull { it.resolveForStaff(context) }
            val ownShifts =
                resolvedCandidates
                    .filter {
                        it.row.profile.linkedUserId == membership.userId &&
                            !it.row.shift.shiftDate.isBefore(range.from) &&
                            !it.row.shift.shiftDate.isAfter(range.to)
                    }
                    .sortedWith(compareBy({ it.row.shift.shiftDate }, { it.row.shift.startsAt }, { it.row.shift.id }))
                    .map { own -> own.toOwnDto(resolvedCandidates, membership.userId) }
            call.respond(
                VenueStaffOwnScheduleResponse(
                    venueId = membership.venueId,
                    venueName = membership.venueName,
                    timezone = context.zoneId.id,
                    venueToday = context.venueToday.toString(),
                    from = range.from.toString(),
                    to = range.to.toString(),
                    shifts = ownShifts,
                ),
            )
        }

        post("/{venueId}/staff/shifts") {
            val membership = call.requireStaffScheduleMembership(venueAccessRepository)
            membership.requirePermission(VenuePermission.STAFF_SCHEDULE_MANAGE)
            val context = resolveStaffScheduleContext(venueSettingsRepository, membership.venueId, clock)
            val request = call.receive<VenueStaffScheduleCreateRequest>()
            if (request.staffProfileId <= 0) {
                throw InvalidInputException("staffProfileId must be positive")
            }
            val input = request.toIntervalInput(context)
            val created =
                venueStaffProfileRepository.createScheduledShift(
                    venueId = membership.venueId,
                    staffProfileId = request.staffProfileId,
                    shiftDate = input.shiftDate,
                    startsAt = input.startsAt,
                    endsAt = input.endsAt,
                    actorUserId = membership.userId,
                    now = context.now,
                    zoneId = context.zoneId,
                    auditLogRepository = auditLogRepository,
                ) ?: throw NotFoundException()
            call.respond(
                VenueStaffScheduleMutationResponse(
                    shift = created.toAdminDto(context) ?: throw NotFoundException(),
                ),
            )
        }

        post("/{venueId}/staff/shifts/batch") {
            val membership = call.requireStaffScheduleMembership(venueAccessRepository)
            membership.requirePermission(VenuePermission.STAFF_SCHEDULE_MANAGE)
            val context = resolveStaffScheduleContext(venueSettingsRepository, membership.venueId, clock)
            val request = call.receive<VenueStaffScheduleBatchRequest>()
            if (request.assignments.isEmpty() || request.assignments.size > MAX_STAFF_SCHEDULE_BATCH_SIZE) {
                throw InvalidInputException("Batch должен содержать от 1 до 50 назначений.")
            }
            val slots =
                request.assignments.map { assignment -> assignment.staffProfileId to assignment.shiftDate.trim() }
            if (slots.distinct().size != slots.size) {
                throw InvalidInputException("В batch есть повторяющиеся сотрудник и дата.")
            }
            val assignments =
                request.assignments.map { assignment ->
                    if (assignment.staffProfileId <= 0) {
                        throw InvalidInputException("staffProfileId must be positive")
                    }
                    val input =
                        normalizeNewStaffScheduleInterval(
                            shiftDateRaw = assignment.shiftDate,
                            startsAtRaw = assignment.startsAt,
                            endsAtRaw = assignment.endsAt,
                            context = context,
                        )
                    val expectedUpdatedAt =
                        when (assignment.operation) {
                            VenueStaffScheduleBatchOperation.CREATE -> {
                                if (assignment.expectedUpdatedAt != null) {
                                    throw InvalidInputException("CREATE не принимает expectedUpdatedAt.")
                                }
                                null
                            }
                            VenueStaffScheduleBatchOperation.RESTORE ->
                                assignment.expectedUpdatedAt
                                    ?.let(::parseExpectedUpdatedAt)
                                    ?: throw InvalidInputException("RESTORE требует expectedUpdatedAt.")
                        }
                    StaffScheduleBatchAssignment(
                        staffProfileId = assignment.staffProfileId,
                        shiftDate = input.shiftDate,
                        startsAt = input.startsAt,
                        endsAt = input.endsAt,
                        operation =
                            when (assignment.operation) {
                                VenueStaffScheduleBatchOperation.CREATE -> StaffScheduleBatchOperation.CREATE
                                VenueStaffScheduleBatchOperation.RESTORE -> StaffScheduleBatchOperation.RESTORE
                            },
                        expectedUpdatedAt = expectedUpdatedAt,
                    )
                }
            val mutated =
                venueStaffProfileRepository.mutateScheduledShiftsBatch(
                    venueId = membership.venueId,
                    assignments = assignments,
                    actorUserId = membership.userId,
                    now = context.now,
                    venueToday = context.venueToday,
                    zoneId = context.zoneId,
                    auditLogRepository = auditLogRepository,
                )
            call.respond(
                VenueStaffScheduleBatchMutationResponse(
                    shifts = mutated.map { it.toAdminDto(context) ?: throw NotFoundException() },
                ),
            )
        }

        put("/{venueId}/staff/shifts/{shiftId}") {
            val membership = call.requireStaffScheduleMembership(venueAccessRepository)
            membership.requirePermission(VenuePermission.STAFF_SCHEDULE_MANAGE)
            val shiftId = call.requireScheduleShiftId()
            val context = resolveStaffScheduleContext(venueSettingsRepository, membership.venueId, clock)
            val request = call.receive<VenueStaffScheduleUpdateRequest>()
            val input = request.toIntervalInput()
            val updated =
                venueStaffProfileRepository.updateScheduledShift(
                    venueId = membership.venueId,
                    shiftId = shiftId,
                    shiftDate = input.shiftDate,
                    startsAt = input.startsAt,
                    endsAt = input.endsAt,
                    expectedUpdatedAt = parseExpectedUpdatedAt(request.expectedUpdatedAt),
                    actorUserId = membership.userId,
                    now = context.now,
                    venueToday = context.venueToday,
                    zoneId = context.zoneId,
                    auditLogRepository = auditLogRepository,
                ) ?: throw NotFoundException()
            call.respond(
                VenueStaffScheduleMutationResponse(
                    shift = updated.toAdminDto(context) ?: throw NotFoundException(),
                ),
            )
        }

        post("/{venueId}/staff/shifts/{shiftId}/restore") {
            val membership = call.requireStaffScheduleMembership(venueAccessRepository)
            membership.requirePermission(VenuePermission.STAFF_SCHEDULE_MANAGE)
            val shiftId = call.requireScheduleShiftId()
            val context = resolveStaffScheduleContext(venueSettingsRepository, membership.venueId, clock)
            val request = call.receive<VenueStaffScheduleRestoreRequest>()
            if ((request.startsAt == null) != (request.endsAt == null)) {
                throw InvalidInputException("startsAt и endsAt нужно передать вместе.")
            }
            val restored =
                venueStaffProfileRepository.restoreScheduledShift(
                    venueId = membership.venueId,
                    shiftId = shiftId,
                    startsAt = request.startsAt?.let { parseLocalTime(it, "startsAt") },
                    endsAt = request.endsAt?.let { parseLocalTime(it, "endsAt") },
                    expectedUpdatedAt = parseExpectedUpdatedAt(request.expectedUpdatedAt),
                    actorUserId = membership.userId,
                    now = context.now,
                    venueToday = context.venueToday,
                    zoneId = context.zoneId,
                    auditLogRepository = auditLogRepository,
                ) ?: throw NotFoundException()
            call.respond(
                VenueStaffScheduleMutationResponse(
                    shift = restored.toAdminDto(context) ?: throw NotFoundException(),
                ),
            )
        }

        post("/{venueId}/staff/shifts/{shiftId}/cancel") {
            val membership = call.requireStaffScheduleMembership(venueAccessRepository)
            membership.requirePermission(VenuePermission.STAFF_SCHEDULE_MANAGE)
            val shiftId = call.requireScheduleShiftId()
            val context = resolveStaffScheduleContext(venueSettingsRepository, membership.venueId, clock)
            val request = call.receive<VenueStaffScheduleCancelRequest>()
            val canceled =
                venueStaffProfileRepository.cancelScheduledShift(
                    venueId = membership.venueId,
                    shiftId = shiftId,
                    expectedUpdatedAt = parseExpectedUpdatedAt(request.expectedUpdatedAt),
                    expectedConfirmationState = parseConfirmationState(request.expectedConfirmationState),
                    actorUserId = membership.userId,
                    now = context.now,
                    venueToday = context.venueToday,
                    zoneId = context.zoneId,
                    auditLogRepository = auditLogRepository,
                ) ?: throw NotFoundException()
            call.respond(
                VenueStaffScheduleMutationResponse(
                    shift = canceled.toAdminDto(context) ?: throw NotFoundException(),
                ),
            )
        }
    }
}

private data class StaffScheduleMembership(
    val userId: Long,
    val venueId: Long,
    val venueName: String?,
    val role: VenueRole,
)

private data class StaffScheduleRequestContext(
    val now: Instant,
    val zoneId: ZoneId,
    val venueToday: LocalDate,
)

private data class StaffScheduleRange(
    val from: LocalDate,
    val to: LocalDate,
)

private fun StaffScheduleRange.serviceDates(): List<LocalDate> =
    (0L..ChronoUnit.DAYS.between(from, to)).map(from::plusDays)

private fun VenueBookingHours?.toEffectiveHoursDto(serviceDate: LocalDate): VenueStaffScheduleEffectiveHoursDto {
    if (this == null) {
        return VenueStaffScheduleEffectiveHoursDto(
            serviceDate = serviceDate.toString(),
            state = VenueStaffScheduleEffectiveHoursState.NOT_CONFIGURED,
            endsNextDay = false,
        )
    }
    if (isClosed) {
        return VenueStaffScheduleEffectiveHoursDto(
            serviceDate = serviceDate.toString(),
            state = VenueStaffScheduleEffectiveHoursState.CLOSED,
            endsNextDay = false,
        )
    }
    val scheduleWindow = checkNotNull(toScheduleWindow(serviceDate))
    return VenueStaffScheduleEffectiveHoursDto(
        serviceDate = serviceDate.toString(),
        state = VenueStaffScheduleEffectiveHoursState.OPEN,
        opensAt = formatScheduleTime(opensAt),
        closesAt = formatScheduleTime(closesAt),
        endsNextDay = scheduleWindow.closesAt.toLocalDate().isAfter(serviceDate),
    )
}

private data class StaffScheduleIntervalInput(
    val shiftDate: LocalDate,
    val startsAt: LocalTime,
    val endsAt: LocalTime,
)

private data class ResolvedStaffScheduleRow(
    val row: VenueStaffScheduledShift,
    val interval: StaffScheduleResolvedInterval,
    val lifecycle: StaffScheduleLifecycle,
)

private suspend fun ApplicationCall.requireStaffScheduleMembership(
    venueAccessRepository: VenueAccessRepository,
): StaffScheduleMembership {
    val userId = requireUserId()
    val venueId = requireVenueId()
    val membership =
        venueAccessRepository.findVenueMembership(userId, venueId)
            ?: throw ForbiddenException()
    val role = VenueRoleMapping.fromDb(membership.role) ?: throw ForbiddenException()
    return StaffScheduleMembership(
        userId = userId,
        venueId = venueId,
        venueName = membership.venueName,
        role = role,
    )
}

private fun StaffScheduleMembership.requirePermission(permission: VenuePermission) {
    if (permission !in VenuePermissions.forRole(role)) {
        throw ForbiddenException()
    }
}

private suspend fun resolveStaffScheduleContext(
    venueSettingsRepository: VenueSettingsRepository,
    venueId: Long,
    clock: Clock,
): StaffScheduleRequestContext {
    val zoneId =
        venueSettingsRepository.resolveZoneId(
            venueId = venueId,
            fallback = ZoneId.of(VenueSettingsRepository.DEFAULT_AUTO_TIMEZONE),
        )
    val now = clock.instant()
    return StaffScheduleRequestContext(
        now = now,
        zoneId = zoneId,
        venueToday = LocalDateTime.ofInstant(now, zoneId).toLocalDate(),
    )
}

private fun ApplicationCall.requireStaffScheduleRange(venueToday: LocalDate): StaffScheduleRange {
    val from = parseLocalDate(request.queryParameters["from"], "from")
    val to = parseLocalDate(request.queryParameters["to"], "to")
    val inclusiveDistance = ChronoUnit.DAYS.between(from, to)
    if (inclusiveDistance < 0) {
        throw InvalidInputException("from must not be after to")
    }
    if (inclusiveDistance > MAX_STAFF_SCHEDULE_RANGE_DISTANCE_DAYS) {
        throw InvalidInputException("Период графика не должен превышать 31 день.")
    }
    if (from.isBefore(venueToday.minusDays(STAFF_SCHEDULE_PAST_READ_DAYS)) ||
        to.isAfter(venueToday.plusDays(STAFF_SCHEDULE_FUTURE_DAYS))
    ) {
        throw InvalidInputException("Период графика выходит за допустимый диапазон.")
    }
    return StaffScheduleRange(from, to)
}

private fun VenueStaffScheduleCreateRequest.toIntervalInput(
    context: StaffScheduleRequestContext,
): StaffScheduleIntervalInput {
    return normalizeNewStaffScheduleInterval(shiftDate, startsAt, endsAt, context)
}

private fun VenueStaffScheduleUpdateRequest.toIntervalInput(): StaffScheduleIntervalInput =
    parseStaffScheduleInterval(shiftDate, startsAt, endsAt)

private fun normalizeNewStaffScheduleInterval(
    shiftDateRaw: String,
    startsAtRaw: String,
    endsAtRaw: String,
    context: StaffScheduleRequestContext,
): StaffScheduleIntervalInput {
    val input = parseStaffScheduleInterval(shiftDateRaw, startsAtRaw, endsAtRaw)
    val shiftDate = input.shiftDate
    val startsAt = input.startsAt
    val endsAt = input.endsAt
    if (shiftDate.isAfter(context.venueToday.plusDays(STAFF_SCHEDULE_FUTURE_DAYS))) {
        throw InvalidInputException("Смену можно запланировать не более чем на 90 дней вперёд.")
    }
    val resolution = resolveStaffScheduleInterval(shiftDate, startsAt, endsAt, context.zoneId)
    val interval =
        resolution.interval
            ?.takeIf { resolution.state == StaffScheduleIntervalState.VALID }
            ?: throw StaffShiftInvalidIntervalException()
    if (!interval.startsAt.isAfter(context.now)) {
        throw InvalidInputException("Начало смены должно быть в будущем.")
    }
    return input
}

private fun parseStaffScheduleInterval(
    shiftDateRaw: String,
    startsAtRaw: String,
    endsAtRaw: String,
): StaffScheduleIntervalInput =
    StaffScheduleIntervalInput(
        shiftDate = parseLocalDate(shiftDateRaw, "shiftDate"),
        startsAt = parseLocalTime(startsAtRaw, "startsAt"),
        endsAt = parseLocalTime(endsAtRaw, "endsAt"),
    )

private fun VenueStaffScheduledShift.toAdminDto(context: StaffScheduleRequestContext): VenueStaffScheduleShiftDto? {
    val startsAt = shift.startsAt ?: return null
    val endsAt = shift.endsAt ?: return null
    val resolution = resolveStaffScheduleInterval(shift.shiftDate, startsAt, endsAt, context.zoneId)
    if (resolution.state == StaffScheduleIntervalState.INCOMPLETE) {
        return null
    }
    val interval = resolution.interval
    val lifecycle =
        if (shift.status.equals("canceled", ignoreCase = true)) {
            StaffScheduleLifecycle.CANCELED
        } else {
            interval?.let {
                computeStaffScheduleLifecycle(
                    storedStatus = shift.status,
                    interval = it,
                    now = context.now,
                )
            }
        }
    val invalidActions =
        if (resolution.state == StaffScheduleIntervalState.INVALID) {
            invalidStaffScheduleAllowedActions(shift, context.venueToday)
        } else {
            emptySet()
        }
    return VenueStaffScheduleShiftDto(
        id = shift.id,
        staffProfileId = shift.staffProfileId,
        displayName = profile.displayName,
        roleLabel = profile.roleLabel,
        subtype = profile.subtype,
        shiftDate = shift.shiftDate.toString(),
        startsAt = startsAt.toString(),
        endsAt = endsAt.toString(),
        endsNextDay = interval?.endsNextDay ?: !endsAt.isAfter(startsAt),
        computedStatus = lifecycle?.toWireValue(),
        cancelConfirmationState =
            when {
                resolution.state == StaffScheduleIntervalState.INVALID &&
                    StaffScheduleAllowedAction.CANCEL in invalidActions ->
                    StaffScheduleConfirmationState.INVALID_INTERVAL.name
                lifecycle != null -> staffScheduleConfirmationState(lifecycle)?.name
                else -> null
            },
        updatedAt = shift.updatedAt.toString(),
        storedStatus = shift.status,
        isGuestVisible = shift.isGuestVisible,
        manuallyMarkedActive = shift.manuallyMarkedActive,
        restoreAllowed = shift.isRestorableCanceledShift(context.now, context.venueToday, context.zoneId),
        warning =
            if (resolution.state == StaffScheduleIntervalState.INVALID) {
                VenueStaffScheduleWarningDto(
                    code = ApiErrorCodes.STAFF_SHIFT_INVALID_INTERVAL,
                    message = "Не удалось определить интервал смены. Проверьте дату и время.",
                    allowedActions = invalidActions.map { it.name },
                )
            } else {
                null
            },
    )
}

private fun VenueStaffScheduledShift.resolveForStaff(context: StaffScheduleRequestContext): ResolvedStaffScheduleRow? {
    val resolution =
        resolveStaffScheduleInterval(
            shiftDate = shift.shiftDate,
            startsAt = shift.startsAt,
            endsAt = shift.endsAt,
            zoneId = context.zoneId,
        )
    val interval = resolution.interval ?: return null
    if (resolution.state != StaffScheduleIntervalState.VALID) {
        return null
    }
    return ResolvedStaffScheduleRow(
        row = this,
        interval = interval,
        lifecycle = computeStaffScheduleLifecycle(shift.status, interval, context.now),
    )
}

private fun ResolvedStaffScheduleRow.toOwnDto(
    candidates: List<ResolvedStaffScheduleRow>,
    authenticatedUserId: Long,
): VenueStaffOwnScheduleShiftDto {
    val colleagues =
        if (lifecycle == StaffScheduleLifecycle.CANCELED) {
            emptyList()
        } else {
            candidates
                .asSequence()
                .filter { it.row.shift.id != row.shift.id }
                .filter { it.lifecycle != StaffScheduleLifecycle.CANCELED }
                .filter { it.row.profile.linkedUserId != authenticatedUserId }
                .filter { colleague ->
                    colleague.interval.startsAt.isBefore(interval.endsAt) &&
                        colleague.interval.endsAt.isAfter(interval.startsAt)
                }
                .sortedWith(
                    compareBy(
                        { it.interval.startsAt },
                        { it.row.profile.displayName },
                        { it.row.shift.id },
                    ),
                )
                .map { it.toColleagueDto() }
                .toList()
        }
    return VenueStaffOwnScheduleShiftDto(
        id = row.shift.id,
        staffProfileId = row.shift.staffProfileId,
        shiftDate = row.shift.shiftDate.toString(),
        startsAt = checkNotNull(row.shift.startsAt).toString(),
        endsAt = checkNotNull(row.shift.endsAt).toString(),
        endsNextDay = interval.endsNextDay,
        computedStatus = lifecycle.toWireValue(),
        colleagues = colleagues,
    )
}

private fun ResolvedStaffScheduleRow.toColleagueDto(): VenueStaffScheduleColleagueDto =
    VenueStaffScheduleColleagueDto(
        staffProfileId = row.shift.staffProfileId,
        displayName = row.profile.displayName,
        roleLabel = row.profile.roleLabel,
        subtype = row.profile.subtype,
        shiftDate = row.shift.shiftDate.toString(),
        startsAt = checkNotNull(row.shift.startsAt).toString(),
        endsAt = checkNotNull(row.shift.endsAt).toString(),
        endsNextDay = interval.endsNextDay,
        computedStatus = lifecycle.toWireValue(),
    )

private fun StaffScheduleLifecycle.toWireValue(): String = name.lowercase(Locale.ROOT)

private fun ApplicationCall.requireScheduleShiftId(): Long {
    val shiftId =
        parameters["shiftId"]?.toLongOrNull()
            ?: throw InvalidInputException("shiftId must be a number")
    if (shiftId <= 0) {
        throw InvalidInputException("shiftId must be positive")
    }
    return shiftId
}

private fun parseExpectedUpdatedAt(raw: String): Instant =
    runCatching { Instant.parse(raw.trim()) }
        .getOrElse { throw InvalidInputException("expectedUpdatedAt must be an ISO-8601 instant") }

private fun parseConfirmationState(raw: String): StaffScheduleConfirmationState =
    runCatching { StaffScheduleConfirmationState.valueOf(raw.trim().uppercase(Locale.ROOT)) }
        .getOrElse {
            throw InvalidInputException(
                "expectedConfirmationState must be SCHEDULED, ACTIVE or INVALID_INTERVAL",
            )
        }

private fun parseLocalDate(
    raw: String?,
    fieldName: String,
): LocalDate {
    val value =
        raw?.trim()?.takeIf { DATE_PATTERN.matches(it) }
            ?: throw InvalidInputException("$fieldName must be YYYY-MM-DD")
    return runCatching { LocalDate.parse(value) }
        .getOrElse { throw InvalidInputException("$fieldName must be YYYY-MM-DD") }
}

private fun parseLocalTime(
    raw: String,
    fieldName: String,
): LocalTime {
    val value = raw.trim()
    if (!TIME_PATTERN.matches(value)) {
        throw InvalidInputException("$fieldName must be HH:mm")
    }
    return runCatching { LocalTime.parse(value) }
        .getOrElse { throw InvalidInputException("$fieldName must be HH:mm") }
}

private const val MAX_STAFF_SCHEDULE_RANGE_DISTANCE_DAYS = 30L
private const val MAX_STAFF_SCHEDULE_BATCH_SIZE = 50
private const val STAFF_SCHEDULE_PAST_READ_DAYS = 30L
private const val STAFF_SCHEDULE_FUTURE_DAYS = 90L
private val DATE_PATTERN = Regex("""\d{4}-\d{2}-\d{2}""")
private val TIME_PATTERN = Regex("""(?:[01]\d|2[0-3]):[0-5]\d""")
