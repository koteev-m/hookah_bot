package com.hookah.platform.backend.miniapp.shift

import com.hookah.platform.backend.api.ForbiddenException
import com.hookah.platform.backend.api.InvalidInputException
import com.hookah.platform.backend.api.NotFoundException
import com.hookah.platform.backend.api.VisitGenerationChangedException
import com.hookah.platform.backend.miniapp.guest.TableSessionConfig
import com.hookah.platform.backend.miniapp.guest.db.GuestTableContextLifecycleRepository
import com.hookah.platform.backend.miniapp.guest.db.GuestTabsRepository
import com.hookah.platform.backend.miniapp.guest.db.GuestVenueRepository
import com.hookah.platform.backend.miniapp.guest.db.TableSessionContext
import com.hookah.platform.backend.miniapp.guest.db.TableSessionRecord
import com.hookah.platform.backend.miniapp.guest.db.TableSessionRepository
import com.hookah.platform.backend.miniapp.guest.ensureGuestActionAvailable
import com.hookah.platform.backend.miniapp.guest.mutateParticipation
import com.hookah.platform.backend.miniapp.guest.requirePlatformGuestTokenAccessIfNeeded
import com.hookah.platform.backend.miniapp.guest.requiredOrderId
import com.hookah.platform.backend.miniapp.guest.requiredVisitGeneration
import com.hookah.platform.backend.miniapp.guest.validateTableToken
import com.hookah.platform.backend.miniapp.subscription.db.SubscriptionRepository
import com.hookah.platform.backend.miniapp.venue.VenuePermission
import com.hookah.platform.backend.miniapp.venue.VenuePermissions
import com.hookah.platform.backend.miniapp.venue.orders.OrderDetail
import com.hookah.platform.backend.miniapp.venue.orders.OrderWorkflowStatus
import com.hookah.platform.backend.miniapp.venue.orders.VenueOrdersRepository
import com.hookah.platform.backend.miniapp.venue.orders.toOrderBillSnapshot
import com.hookah.platform.backend.miniapp.venue.requireUserId
import com.hookah.platform.backend.miniapp.venue.requireVenueId
import com.hookah.platform.backend.miniapp.venue.resolveVenueRole
import com.hookah.platform.backend.telegram.StaffBillUpdateChange
import com.hookah.platform.backend.telegram.StaffBillUpdateNotifier
import com.hookah.platform.backend.telegram.StaffBillUpdatedNotification
import com.hookah.platform.backend.telegram.TableContext
import com.hookah.platform.backend.telegram.db.OrdersRepository
import com.hookah.platform.backend.telegram.db.VenueAccessRepository
import com.hookah.platform.backend.telegram.db.VenueSettingsRepository
import com.hookah.platform.backend.telegram.toStaffOrderBatchLiveBlocks
import io.ktor.server.application.call
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.route
import java.math.BigDecimal
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private const val DEFAULT_CURRENCY = "RUB"
private val defaultShiftExtensionZoneId = ZoneId.of(VenueSettingsRepository.DEFAULT_AUTO_TIMEZONE)
private val instantFormatter = DateTimeFormatter.ISO_OFFSET_DATE_TIME

fun Route.guestShiftExtensionRoutes(
    tableTokenResolver: suspend (String) -> TableContext?,
    guestVenueRepository: GuestVenueRepository,
    subscriptionRepository: SubscriptionRepository,
    tableSessionRepository: TableSessionRepository,
    tableSessionConfig: TableSessionConfig,
    guestTabsRepository: GuestTabsRepository,
    ordersRepository: OrdersRepository,
    shiftExtensionRepository: ShiftExtensionRepository,
    venueSettingsRepository: VenueSettingsRepository,
    staffBillUpdateNotifier: StaffBillUpdateNotifier? = null,
    venueOrdersRepository: VenueOrdersRepository? = null,
    platformOwnerUserId: Long? = null,
    guestTableContextLifecycleRepository: GuestTableContextLifecycleRepository? = null,
) {
    get("/table/extension-options") {
        val userId = call.requireUserId()
        val token = validateTableToken(call.request.queryParameters["tableToken"])
        val sessionId = parsePositiveLong(call.request.queryParameters["tableSessionId"], "tableSessionId")
        val tabId = parsePositiveLong(call.request.queryParameters["tabId"], "tabId")
        val table = tableTokenResolver(token) ?: throw NotFoundException()
        ensureGuestActionAvailable(table.venueId, guestVenueRepository, subscriptionRepository, readOnly = true)
        requirePlatformGuestTokenAccessIfNeeded(
            userId,
            platformOwnerUserId,
            guestTableContextLifecycleRepository,
            token,
            table,
            sessionId,
            tableSessionConfig.ttl,
        )
        val zoneId = resolveVenueZoneId(venueSettingsRepository, table.venueId)
        val window =
            shiftExtensionRepository.guestExtensionWindow(
                table.venueId,
                table.tableId,
                sessionId,
                tabId,
                userId,
            )
        if (window.paidUntil == null) {
            call.respond(
                GuestShiftExtensionOptionsResponse(
                    available = false,
                    unavailableReason = "STAFF_MANAGED_SEATING",
                    tableSessionId = sessionId,
                    tabId = tabId,
                    orderId = window.orderId,
                ),
            )
            return@get
        }
        val settings = shiftExtensionRepository.getSettings(table.venueId)
        val pending =
            shiftExtensionRepository.findPendingRequest(
                tableSessionId = sessionId,
                tabId = tabId,
            )
        if (!settings.enabled) {
            call.respond(
                GuestShiftExtensionOptionsResponse(
                    available = false,
                    unavailableReason = "EXTENSION_DISABLED",
                    tableSessionId = sessionId,
                    tabId = tabId,
                    orderId = window.orderId,
                    pendingRequest = pending?.toDto(zoneId),
                ),
            )
            return@get
        }
        if (settings.priceMinor == null) {
            call.respond(
                GuestShiftExtensionOptionsResponse(
                    available = false,
                    unavailableReason = "EXTENSION_NOT_CONFIGURED",
                    tableSessionId = sessionId,
                    tabId = tabId,
                    orderId = window.orderId,
                    pendingRequest = pending?.toDto(zoneId),
                ),
            )
            return@get
        }

        call.respond(
            GuestShiftExtensionOptionsResponse(
                available = true,
                durationMinutes = settings.durationMinutes,
                priceMinor = settings.priceMinor,
                currency = settings.currency,
                confirmedTerms = settings.terms(),
                tableSessionId = sessionId,
                tabId = tabId,
                orderId = window.orderId,
                currentOrderableUntil = formatInstant(window.paidUntil, zoneId),
                proposedOrderableUntil =
                    formatInstant(
                        window.paidUntil.plusSeconds(settings.durationMinutes.toLong() * 60L),
                        zoneId,
                    ),
                pendingRequest = pending?.toDto(zoneId),
            ),
        )
    }

    post("/table/extension-requests") {
        val userId = call.requireUserId()
        val request = call.receive<GuestShiftExtensionRequest>()
        val generation = requiredVisitGeneration(request.tableSessionId, request.expectedVisitGeneration)
        val expectedOrder = requiredOrderId(request.expectedOrderId)
        val idempotencyKey = requireShiftExtensionKey(request.idempotencyKey)
        val token = validateTableToken(request.tableToken)
        val table = tableTokenResolver(token) ?: throw NotFoundException()
        val context =
            guestTabsRepository.mutateParticipation(
                userId,
                TableSessionContext(request.tableSessionId, table.venueId, table.tableId),
                generation,
                platformOwnerUserId,
                guestTableContextLifecycleRepository,
                tableSessionConfig.ttl,
                token,
                requiredTab = request.tabId,
                touchOnSuccess = false,
            ) { connection ->
                if (userId == platformOwnerUserId) {
                    guestTabsRepository.ensurePersonalTab(connection, table.venueId, request.tableSessionId, userId)
                }
                val activeOrder =
                    ordersRepository.findActiveOrderDetailsForTab(
                        connection,
                        request.tableSessionId,
                        request.tabId,
                        forUpdate = true,
                    )
                if (activeOrder == null) throw NotFoundException("Active order not found")
                if (activeOrder.orderId != expectedOrder) throw VisitGenerationChangedException()
                val current =
                    tableSessionRepository.findSessionForTable(
                        connection, request.tableSessionId, table.venueId, table.tableId, forUpdate = true,
                    ) ?: throw NotFoundException()
                val command =
                    CreateShiftExtensionRequestCommand(
                        venueId = table.venueId, tableSessionId = current.id, tableId = table.tableId,
                        tabId = request.tabId, orderId = expectedOrder, requestedByUserId = userId,
                        currentOrderableUntil = current.expiresAt, idempotencyKey = idempotencyKey,
                        comment = request.comment,
                        visitGeneration = generation,
                        confirmedTerms = request.confirmedTerms,
                    )
                val replay = shiftExtensionRepository.findExactReplay(connection, command)
                if (replay != null) {
                    return@mutateParticipation PlatformGuestShiftExtensionMutation(
                        GuestShiftExtensionContext(table, current, request.tabId, expectedOrder),
                        replay,
                        replay = true,
                    )
                }
                val session =
                    tableSessionRepository.touchActiveSession(
                        connection, current.id, table.venueId, table.tableId,
                        tableSessionConfig.ttl, java.time.Instant.now(),
                    ) ?: throw NotFoundException()
                val record =
                    shiftExtensionRepository.createPendingRequest(
                        connection,
                        command.copy(currentOrderableUntil = session.expiresAt),
                    )
                PlatformGuestShiftExtensionMutation(
                    GuestShiftExtensionContext(table, session, request.tabId, expectedOrder),
                    record,
                )
            }
        val zoneId = resolveVenueZoneId(venueSettingsRepository, context.context.table.venueId)
        if (!context.replay) {
            refreshStaffChatBill(
                notifier = staffBillUpdateNotifier,
                venueOrdersRepository = venueOrdersRepository,
                venueId = context.context.table.venueId,
                orderId = context.context.orderId,
                change = StaffBillUpdateChange.SHIFT_EXTENSION_REQUESTED,
            )
        }
        call.respond(ShiftExtensionRequestResponse(request = context.record.toDto(zoneId)))
    }
}

fun Route.venueShiftExtensionRoutes(
    venueAccessRepository: VenueAccessRepository,
    shiftExtensionRepository: ShiftExtensionRepository,
    venueOrdersRepository: VenueOrdersRepository,
    staffBillUpdateNotifier: StaffBillUpdateNotifier?,
    venueSettingsRepository: VenueSettingsRepository,
) {
    route("/venue/{venueId}/shift-extension-settings") {
        get {
            val userId = call.requireUserId()
            val venueId = call.requireVenueId()
            requireVenuePermission(venueAccessRepository, userId, venueId, VenuePermission.SHIFT_EXTENSION_SETTINGS)
            val settings = shiftExtensionRepository.getSettings(venueId)
            call.respond(ShiftExtensionSettingsResponse(settings = settings.toDto()))
        }

        put {
            val userId = call.requireUserId()
            val venueId = call.requireVenueId()
            requireVenuePermission(venueAccessRepository, userId, venueId, VenuePermission.SHIFT_EXTENSION_SETTINGS)
            val request = call.receive<ShiftExtensionSettingsUpdateRequest>()
            val command = request.toCommand(venueId)
            val settings = shiftExtensionRepository.upsertSettings(command)
            call.respond(ShiftExtensionSettingsResponse(settings = settings.toDto()))
        }
    }

    route("/venue/{venueId}/shift-extension-requests") {
        get {
            val userId = call.requireUserId()
            val venueId = call.requireVenueId()
            requireVenuePermission(venueAccessRepository, userId, venueId, VenuePermission.SHIFT_EXTENSION_VIEW)
            val status =
                call.request.queryParameters["status"]
                    ?.let { raw -> parseShiftExtensionStatus(raw) }
            val zoneId = resolveVenueZoneId(venueSettingsRepository, venueId)
            val records = shiftExtensionRepository.listRequests(venueId, status)
            call.respond(ShiftExtensionRequestsResponse(items = records.map { it.toDto(zoneId) }))
        }

        post("/{requestId}/approve") {
            val userId = call.requireUserId()
            val venueId = call.requireVenueId()
            requireVenuePermission(venueAccessRepository, userId, venueId, VenuePermission.SHIFT_EXTENSION_CONFIRM)
            val requestId =
                call.parameters["requestId"]?.toLongOrNull()
                    ?: throw InvalidInputException("requestId must be a number")
            val result =
                shiftExtensionRepository.approveRequest(
                    venueId = venueId,
                    requestId = requestId,
                    actorUserId = userId,
                )
            val zoneId = resolveVenueZoneId(venueSettingsRepository, venueId)
            refreshStaffChatBill(
                notifier = staffBillUpdateNotifier,
                venueOrdersRepository = venueOrdersRepository,
                venueId = venueId,
                orderId = result.orderId,
                change = StaffBillUpdateChange.SHIFT_EXTENSION_APPROVED,
            )
            call.respond(
                ShiftExtensionDecisionResponse(
                    request = result.request.toDto(zoneId),
                    applied = result.applied,
                ),
            )
        }

        post("/{requestId}/reject") {
            val userId = call.requireUserId()
            val venueId = call.requireVenueId()
            requireVenuePermission(venueAccessRepository, userId, venueId, VenuePermission.SHIFT_EXTENSION_CONFIRM)
            val requestId =
                call.parameters["requestId"]?.toLongOrNull()
                    ?: throw InvalidInputException("requestId must be a number")
            val request = call.receive<ShiftExtensionDecisionRequest>()
            val result =
                shiftExtensionRepository.rejectRequest(
                    venueId = venueId,
                    requestId = requestId,
                    actorUserId = userId,
                    reasonText = request.reasonText,
                )
            val zoneId = resolveVenueZoneId(venueSettingsRepository, venueId)
            refreshStaffChatBill(
                notifier = staffBillUpdateNotifier,
                venueOrdersRepository = venueOrdersRepository,
                venueId = venueId,
                orderId = result.orderId,
                change = StaffBillUpdateChange.SHIFT_EXTENSION_REJECTED,
            )
            call.respond(
                ShiftExtensionDecisionResponse(
                    request = result.request.toDto(zoneId),
                    applied = result.applied,
                ),
            )
        }
    }
}

private data class GuestShiftExtensionContext(
    val table: TableContext,
    val tableSession: TableSessionRecord,
    val tabId: Long,
    val orderId: Long,
)

private data class PlatformGuestShiftExtensionMutation(
    val context: GuestShiftExtensionContext,
    val record: ShiftExtensionRequestRecord,
    val replay: Boolean = false,
)

private suspend fun requireVenuePermission(
    venueAccessRepository: VenueAccessRepository,
    userId: Long,
    venueId: Long,
    permission: VenuePermission,
) {
    val role = resolveVenueRole(venueAccessRepository, userId, venueId)
    val permissions = VenuePermissions.forRole(role)
    if (!permissions.contains(permission)) {
        throw ForbiddenException()
    }
}

private suspend fun refreshStaffChatBill(
    notifier: StaffBillUpdateNotifier?,
    venueOrdersRepository: VenueOrdersRepository?,
    venueId: Long,
    orderId: Long,
    change: StaffBillUpdateChange,
) {
    val staffNotifier = notifier ?: return
    val ordersRepository = venueOrdersRepository ?: return
    val detail = ordersRepository.loadOrderDetail(venueId, orderId) ?: return
    staffNotifier.notifyBillUpdatedNow(
        StaffBillUpdatedNotification(
            venueId = venueId,
            orderId = orderId,
            displayNumber = detail.displayNumber,
            tableLabel = detail.tableNumber.toString(),
            change = change,
            bill = detail.toOrderBillSnapshot(DEFAULT_CURRENCY),
            batches = detail.toStaffOrderBatchLiveBlocks(),
            status = detail.status,
            actionBatchId = staffChatActionBatchId(detail),
            pendingShiftExtension = detail.pendingShiftExtension,
            updatedAt = detail.updatedAt,
        ),
    )
}

private fun staffChatActionBatchId(detail: OrderDetail): Long =
    when (detail.status) {
        OrderWorkflowStatus.NEW ->
            detail.batches.lastOrNull { batch -> batch.status == OrderWorkflowStatus.NEW }
        OrderWorkflowStatus.ACCEPTED,
        OrderWorkflowStatus.COOKING,
        OrderWorkflowStatus.DELIVERING,
        ->
            detail.batches.lastOrNull { batch ->
                batch.status == OrderWorkflowStatus.ACCEPTED ||
                    batch.status == OrderWorkflowStatus.COOKING ||
                    batch.status == OrderWorkflowStatus.DELIVERING
            }
        OrderWorkflowStatus.DELIVERED ->
            detail.batches.lastOrNull { batch -> batch.status == OrderWorkflowStatus.DELIVERED }
        OrderWorkflowStatus.CLOSED ->
            detail.batches.lastOrNull()
    }?.batchId ?: detail.batches.lastOrNull()?.batchId ?: detail.orderId

private fun parsePositiveLong(
    raw: String?,
    name: String,
): Long =
    raw?.toLongOrNull()?.takeIf { value -> value > 0L }
        ?: throw InvalidInputException("$name must be a positive number")

private fun parseShiftExtensionStatus(raw: String): ShiftExtensionRequestStatus =
    ShiftExtensionRequestStatus.entries.firstOrNull { status ->
        status.dbValue.equals(raw, ignoreCase = true) ||
            status.name.equals(raw, ignoreCase = true)
    } ?: throw InvalidInputException("status must be one of: pending, approved, rejected, cancelled")

private fun ShiftExtensionSettingsUpdateRequest.toCommand(venueId: Long): UpdateShiftExtensionSettingsCommand {
    if (durationMinutes <= 0 || durationMinutes > 240) {
        throw InvalidInputException("durationMinutes must be between 1 and 240")
    }
    val normalizedCurrency = currency?.trim()?.uppercase().takeUnless { it.isNullOrBlank() } ?: DEFAULT_CURRENCY
    if (normalizedCurrency != DEFAULT_CURRENCY) {
        throw InvalidInputException("currency must be RUB")
    }
    if (maxExtensionsPerSession != null && maxExtensionsPerSession <= 0) {
        throw InvalidInputException("maxExtensionsPerSession must be positive")
    }
    val normalizedPriceMinor = resolveSettingsPriceMinor(priceMinor, priceRub)
    if (enabled && normalizedPriceMinor == null) {
        throw InvalidInputException("price must be configured when shift extension is enabled")
    }
    return UpdateShiftExtensionSettingsCommand(
        venueId = venueId,
        enabled = enabled,
        durationMinutes = durationMinutes,
        priceMinor = normalizedPriceMinor,
        currency = normalizedCurrency,
        maxExtensionsPerSession = maxExtensionsPerSession,
    )
}

private fun resolveSettingsPriceMinor(
    priceMinor: Long?,
    priceRub: String?,
): Long? {
    if (priceMinor != null && !priceRub.isNullOrBlank()) {
        throw InvalidInputException("set either priceMinor or priceRub")
    }
    priceMinor?.let { value ->
        if (value <= 0L) {
            throw InvalidInputException("priceMinor must be positive")
        }
        return value
    }
    val rawRub = priceRub?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    val normalizedRub = rawRub.replace(',', '.')
    val rub =
        normalizedRub.toBigDecimalOrNull()
            ?: throw InvalidInputException("priceRub must be a number")
    if (rub <= BigDecimal.ZERO) {
        throw InvalidInputException("priceRub must be positive")
    }
    return try {
        rub.movePointRight(2).longValueExact()
    } catch (e: ArithmeticException) {
        throw InvalidInputException("priceRub must have at most two decimal places")
    }
}

private suspend fun resolveVenueZoneId(
    venueSettingsRepository: VenueSettingsRepository,
    venueId: Long,
): ZoneId = venueSettingsRepository.resolveZoneId(venueId, defaultShiftExtensionZoneId)

private fun ShiftExtensionSettings.toDto(): ShiftExtensionSettingsDto =
    ShiftExtensionSettingsDto(
        venueId = venueId,
        enabled = enabled,
        durationMinutes = durationMinutes,
        priceMinor = priceMinor,
        priceRub = priceMinor?.let { minor -> formatPriceRub(minor) },
        currency = currency,
        maxExtensionsPerSession = maxExtensionsPerSession,
        configured = enabled && priceMinor != null,
    )

private fun formatPriceRub(priceMinor: Long): String {
    val rub = BigDecimal.valueOf(priceMinor, 2).stripTrailingZeros()
    return rub.toPlainString()
}

private fun ShiftExtensionRequestRecord.toDto(zoneId: ZoneId): ShiftExtensionRequestDto =
    ShiftExtensionRequestDto(
        id = id,
        venueId = venueId,
        tableSessionId = tableSessionId,
        tableId = tableId,
        tableNumber = tableNumber?.toString(),
        tabId = tabId,
        orderId = orderId,
        requestedByUserId = requestedByUserId,
        status = status.dbValue.lowercase(),
        durationMinutes = durationMinutes,
        priceMinor = priceMinor,
        currency = currency,
        currentOrderableUntil = formatInstant(currentOrderableUntil, zoneId),
        requestedUntil = formatInstant(requestedUntil, zoneId),
        comment = comment,
        decidedByUserId = decidedByUserId,
        decidedAt = decidedAt?.let { formatInstant(it, zoneId) },
        rejectReason = rejectReason,
        createdAt = formatInstant(createdAt, zoneId),
        updatedAt = formatInstant(updatedAt, zoneId),
    )

private fun formatInstant(
    value: java.time.Instant,
    zoneId: ZoneId,
): String = instantFormatter.format(value.atZone(zoneId))
