package com.hookah.platform.backend.miniapp.guest

import com.hookah.platform.backend.api.ForbiddenException
import com.hookah.platform.backend.api.InvalidInputException
import com.hookah.platform.backend.api.NotFoundException
import com.hookah.platform.backend.miniapp.guest.db.GuestMenuRepository
import com.hookah.platform.backend.miniapp.guest.db.GuestTabsRepository
import com.hookah.platform.backend.miniapp.guest.db.GuestVenueRepository
import com.hookah.platform.backend.miniapp.guest.db.GuestVisitDetail
import com.hookah.platform.backend.miniapp.guest.db.GuestVisitOrder
import com.hookah.platform.backend.miniapp.guest.db.MenuItemModel
import com.hookah.platform.backend.miniapp.guest.db.MenuItemOptionModel
import com.hookah.platform.backend.miniapp.guest.db.VisitRepository
import com.hookah.platform.backend.miniapp.subscription.db.SubscriptionRepository

private const val REPEAT_LINE_MAX_QTY = 50
private const val REPEAT_NOTE_MAX_LENGTH = 200
private const val DEFAULT_REPEAT_CURRENCY = "RUB"

enum class RepeatOrderSkipReason(
    val message: String,
) {
    ITEM_UNAVAILABLE("Позиция больше недоступна."),
    OPTION_UNAVAILABLE("Выбранный вариант больше недоступен."),
    LEGACY_LINE_UNRESOLVED("Эту старую позицию нельзя повторить автоматически."),
}

data class RepeatOrderSelectedOption(
    val optionId: Long,
    val name: String,
    val priceDeltaMinor: Long,
)

data class RepeatOrderEligibleLine(
    val itemId: Long,
    val itemName: String,
    val quantity: Int,
    val selectedOption: RepeatOrderSelectedOption?,
    val preferenceNote: String?,
    val currentItemPriceMinor: Long,
    val currentUnitPriceMinor: Long,
    val currentLineTotalMinor: Long,
    val currency: String,
)

data class RepeatOrderSkippedLine(
    val itemName: String,
    val quantity: Int,
    val selectedOptionName: String?,
    val reason: RepeatOrderSkipReason,
)

data class RepeatOrderPlan(
    val sourceOrderId: Long,
    val venueId: Long,
    val eligibleLines: List<RepeatOrderEligibleLine>,
    val skippedLines: List<RepeatOrderSkippedLine>,
    val currentTotalMinor: Long,
    val currency: String,
)

class RepeatOrderResolver(
    private val visitRepository: VisitRepository,
    private val guestMenuRepository: GuestMenuRepository,
    private val guestTabsRepository: GuestTabsRepository,
    private val guestVenueRepository: GuestVenueRepository? = null,
    private val subscriptionRepository: SubscriptionRepository? = null,
) {
    suspend fun resolveForGuest(
        userId: Long,
        visitId: Long,
        tableSessionId: Long,
        tabId: Long,
        orderId: Long?,
    ): RepeatOrderPlan {
        val detail =
            visitRepository.getGuestVisitDetail(userId = userId, visitId = visitId)
                ?: throw NotFoundException()
        val order = selectOrder(detail, orderId)
        val session = guestTabsRepository.findActiveTableSession(tableSessionId) ?: throw NotFoundException()
        if (session.venueId != detail.venueId) {
            throw ForbiddenException("Repeat order venue does not match table context")
        }
        val venueRepository =
            guestVenueRepository
                ?: throw IllegalStateException("Guest venue repository is required for repeat context validation")
        val subscriptions =
            subscriptionRepository
                ?: throw IllegalStateException("Subscription repository is required for repeat context validation")
        ensureGuestActionAvailable(detail.venueId, venueRepository, subscriptions)
        if (
            !guestTabsRepository.isTabMember(
                tabId = tabId,
                venueId = detail.venueId,
                tableSessionId = session.id,
                userId = userId,
            )
        ) {
            throw ForbiddenException("Tab access denied")
        }
        return resolveSelectedOrder(detail, order)
    }

    suspend fun resolveOrder(
        detail: GuestVisitDetail,
        orderId: Long?,
    ): RepeatOrderPlan = resolveSelectedOrder(detail, selectOrder(detail, orderId))

    suspend fun resolveOrders(detail: GuestVisitDetail): List<RepeatOrderPlan> {
        if (detail.orders.isEmpty()) return emptyList()
        val currentItemsById = loadCurrentItems(detail.venueId)
        return detail.orders.map { order -> buildPlan(detail.venueId, order, currentItemsById) }
    }

    private suspend fun resolveSelectedOrder(
        detail: GuestVisitDetail,
        order: GuestVisitOrder,
    ): RepeatOrderPlan = buildPlan(detail.venueId, order, loadCurrentItems(detail.venueId))

    private suspend fun loadCurrentItems(venueId: Long): Map<Long, MenuItemModel> =
        guestMenuRepository
            .getMenu(venueId)
            .categories
            .flatMap { category -> category.items }
            .associateBy { item -> item.id }

    private fun selectOrder(
        detail: GuestVisitDetail,
        orderId: Long?,
    ): GuestVisitOrder {
        if (detail.orders.isEmpty()) {
            throw InvalidInputException("Visit has no completed order")
        }
        if (orderId == null) {
            if (detail.orders.size != 1) {
                throw InvalidInputException("orderId is required when visit has multiple orders")
            }
            return detail.orders.single()
        }
        return detail.orders.firstOrNull { order -> order.orderId == orderId }
            ?: throw NotFoundException()
    }

    private fun buildPlan(
        venueId: Long,
        order: GuestVisitOrder,
        currentItemsById: Map<Long, MenuItemModel>,
    ): RepeatOrderPlan {
        val skipped = mutableListOf<RepeatOrderSkippedLine>()
        val rawEligible = mutableListOf<RepeatOrderEligibleLine>()
        order.items
            .filterNot { item -> item.isPromotionReward }
            .forEach { historical ->
                val currentItem = currentItemsById[historical.itemId]
                if (currentItem == null) {
                    skipped +=
                        RepeatOrderSkippedLine(
                            itemName = historical.itemName,
                            quantity = historical.qty.coerceAtLeast(0),
                            selectedOptionName = historical.selectedOption?.name,
                            reason = RepeatOrderSkipReason.ITEM_UNAVAILABLE,
                        )
                    return@forEach
                }
                if (historical.qty !in 1..REPEAT_LINE_MAX_QTY) {
                    skipped += historical.toLegacySkippedLine()
                    return@forEach
                }
                val currentOption =
                    resolveCurrentOption(
                        historicalOptionId = historical.selectedOption?.optionId,
                        historicalOption = historical.selectedOption,
                        currentItem = currentItem,
                    )
                if (historical.selectedOption != null && historical.selectedOption.optionId == null) {
                    skipped += historical.toLegacySkippedLine()
                    return@forEach
                }
                if (historical.selectedOption != null && currentOption == null) {
                    skipped +=
                        RepeatOrderSkippedLine(
                            itemName = historical.itemName,
                            quantity = historical.qty,
                            selectedOptionName = historical.selectedOption.name,
                            reason = RepeatOrderSkipReason.OPTION_UNAVAILABLE,
                        )
                    return@forEach
                }
                val option =
                    currentOption?.let {
                        RepeatOrderSelectedOption(
                            optionId = it.id,
                            name = it.name,
                            priceDeltaMinor = it.priceDeltaMinor,
                        )
                    }
                val currentUnitPriceMinor = currentItem.priceMinor + (option?.priceDeltaMinor ?: 0L)
                rawEligible +=
                    RepeatOrderEligibleLine(
                        itemId = currentItem.id,
                        itemName = currentItem.name,
                        quantity = historical.qty,
                        selectedOption = option,
                        preferenceNote = normalizeRepeatNote(historical.preferenceNote),
                        currentItemPriceMinor = currentItem.priceMinor,
                        currentUnitPriceMinor = currentUnitPriceMinor,
                        currentLineTotalMinor = currentUnitPriceMinor * historical.qty.toLong(),
                        currency = currentItem.currency,
                    )
            }

        val eligible =
            rawEligible
                .groupBy { line -> RepeatLineKey(line.itemId, line.selectedOption?.optionId, line.preferenceNote) }
                .values
                .mapNotNull { matchingLines ->
                    val quantity = matchingLines.sumOf { line -> line.quantity }
                    if (quantity !in 1..REPEAT_LINE_MAX_QTY) {
                        val first = matchingLines.first()
                        skipped +=
                            RepeatOrderSkippedLine(
                                itemName = first.itemName,
                                quantity = quantity,
                                selectedOptionName = first.selectedOption?.name,
                                reason = RepeatOrderSkipReason.LEGACY_LINE_UNRESOLVED,
                            )
                        null
                    } else {
                        val first = matchingLines.first()
                        first.copy(
                            quantity = quantity,
                            currentLineTotalMinor = first.currentUnitPriceMinor * quantity.toLong(),
                        )
                    }
                }
        val currency = eligible.firstOrNull()?.currency ?: order.currency ?: DEFAULT_REPEAT_CURRENCY
        return RepeatOrderPlan(
            sourceOrderId = order.orderId,
            venueId = venueId,
            eligibleLines = eligible,
            skippedLines = skipped,
            currentTotalMinor = eligible.sumOf { line -> line.currentLineTotalMinor },
            currency = currency,
        )
    }

    private fun resolveCurrentOption(
        historicalOptionId: Long?,
        historicalOption: com.hookah.platform.backend.miniapp.guest.db.GuestVisitOrderItemOption?,
        currentItem: MenuItemModel,
    ): MenuItemOptionModel? {
        if (historicalOption == null) return null
        val optionId = historicalOptionId ?: return null
        return currentItem.options.firstOrNull { option -> option.id == optionId }
    }

    private fun com.hookah.platform.backend.miniapp.guest.db.GuestVisitOrderItem.toLegacySkippedLine() =
        RepeatOrderSkippedLine(
            itemName = itemName,
            quantity = qty.coerceAtLeast(0),
            selectedOptionName = selectedOption?.name,
            reason = RepeatOrderSkipReason.LEGACY_LINE_UNRESOLVED,
        )

    private data class RepeatLineKey(
        val itemId: Long,
        val optionId: Long?,
        val preferenceNote: String?,
    )
}

private fun normalizeRepeatNote(note: String?): String? =
    note
        ?.trim()
        ?.takeIf { value -> value.isNotEmpty() }
        ?.take(REPEAT_NOTE_MAX_LENGTH)
