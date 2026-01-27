package com.hookah.platform.backend.miniapp.venue.menu

import com.hookah.platform.backend.api.ForbiddenException
import com.hookah.platform.backend.api.InvalidInputException
import com.hookah.platform.backend.api.NotFoundException
import com.hookah.platform.backend.miniapp.venue.VenuePermission
import com.hookah.platform.backend.miniapp.venue.VenuePermissions
import com.hookah.platform.backend.miniapp.venue.requireUserId
import com.hookah.platform.backend.miniapp.venue.requireVenueId
import com.hookah.platform.backend.miniapp.venue.resolveVenueRole
import com.hookah.platform.backend.telegram.db.VenueAccessRepository
import io.ktor.server.application.call
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.patch
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import java.util.Locale

private const val NAME_MAX_LENGTH = 120
private val allowedCurrencies = setOf("RUB")

fun Route.venueMenuRoutes(
    venueAccessRepository: VenueAccessRepository,
    venueMenuRepository: VenueMenuRepository
) {
    route("/venue") {
        get("/menu") {
            val userId = call.requireUserId()
            val venueId = call.requireVenueId()
            val role = resolveVenueRole(venueAccessRepository, userId, venueId)
            val permissions = VenuePermissions.forRole(role)
            if (!permissions.contains(VenuePermission.MENU_VIEW)) {
                throw ForbiddenException()
            }
            val categories = venueMenuRepository.getMenu(venueId)
            call.respond(
                VenueMenuResponse(
                    venueId = venueId,
                    categories = categories.map { it.toDto() }
                )
            )
        }

        post("/menu/categories") {
            val userId = call.requireUserId()
            val venueId = call.requireVenueId()
            ensureMenuManage(venueAccessRepository, userId, venueId)
            val payload = call.receive<CreateCategoryRequest>()
            val name = payload.name.trim()
            validateName(name)
            val created = venueMenuRepository.createCategory(venueId, name)
            call.respond(created.toDto())
        }

        patch("/menu/categories/{id}") {
            val userId = call.requireUserId()
            val venueId = call.requireVenueId()
            ensureMenuManage(venueAccessRepository, userId, venueId)
            val categoryId = call.parameters["id"]?.toLongOrNull()
                ?: throw InvalidInputException("categoryId must be a number")
            val payload = call.receive<UpdateCategoryRequest>()
            val name = payload.name?.trim() ?: throw InvalidInputException("name is required")
            validateName(name)
            val updated = venueMenuRepository.updateCategory(venueId, categoryId, name)
                ?: throw NotFoundException()
            call.respond(updated.toDto())
        }

        delete("/menu/categories/{id}") {
            val userId = call.requireUserId()
            val venueId = call.requireVenueId()
            ensureMenuManage(venueAccessRepository, userId, venueId)
            val categoryId = call.parameters["id"]?.toLongOrNull()
                ?: throw InvalidInputException("categoryId must be a number")
            if (!venueMenuRepository.categoryExists(venueId, categoryId)) {
                throw NotFoundException()
            }
            if (venueMenuRepository.categoryHasItems(venueId, categoryId)) {
                throw InvalidInputException("Category has items")
            }
            val deleted = venueMenuRepository.deleteCategory(venueId, categoryId)
            if (!deleted) {
                throw NotFoundException()
            }
            call.respond(mapOf("ok" to true))
        }

        post("/menu/items") {
            val userId = call.requireUserId()
            val venueId = call.requireVenueId()
            ensureMenuManage(venueAccessRepository, userId, venueId)
            val payload = call.receive<CreateItemRequest>()
            val name = payload.name.trim()
            validateName(name)
            validatePrice(payload.priceMinor)
            val currency = payload.currency.trim().uppercase(Locale.ROOT)
            validateCurrency(currency)
            val created = venueMenuRepository.createItem(
                venueId = venueId,
                categoryId = payload.categoryId,
                name = name,
                priceMinor = payload.priceMinor,
                currency = currency,
                isAvailable = payload.isAvailable
            ) ?: throw InvalidInputException("categoryId is invalid")
            call.respond(created.toDto())
        }

        patch("/menu/items/{id}") {
            val userId = call.requireUserId()
            val venueId = call.requireVenueId()
            ensureMenuManage(venueAccessRepository, userId, venueId)
            val itemId = call.parameters["id"]?.toLongOrNull()
                ?: throw InvalidInputException("itemId must be a number")
            val payload = call.receive<UpdateItemRequest>()
            if (!venueMenuRepository.itemExists(venueId, itemId)) {
                throw NotFoundException()
            }
            val name = payload.name?.trim()
            if (name != null) {
                validateName(name)
            }
            payload.priceMinor?.let { validatePrice(it) }
            val currency = payload.currency?.trim()?.uppercase(Locale.ROOT)
            if (currency != null) {
                validateCurrency(currency)
            }
            if (payload.categoryId != null && !venueMenuRepository.categoryExists(venueId, payload.categoryId)) {
                throw InvalidInputException("categoryId is invalid")
            }
            val updated = venueMenuRepository.updateItem(
                venueId = venueId,
                itemId = itemId,
                categoryId = payload.categoryId,
                name = name,
                priceMinor = payload.priceMinor,
                currency = currency,
                isAvailable = payload.isAvailable
            ) ?: throw NotFoundException()
            call.respond(updated.toDto())
        }

        delete("/menu/items/{id}") {
            val userId = call.requireUserId()
            val venueId = call.requireVenueId()
            ensureMenuManage(venueAccessRepository, userId, venueId)
            val itemId = call.parameters["id"]?.toLongOrNull()
                ?: throw InvalidInputException("itemId must be a number")
            val deleted = venueMenuRepository.deleteItem(venueId, itemId)
            if (!deleted) {
                throw NotFoundException()
            }
            call.respond(mapOf("ok" to true))
        }

        patch("/menu/items/{id}/availability") {
            val userId = call.requireUserId()
            val venueId = call.requireVenueId()
            ensureMenuManage(venueAccessRepository, userId, venueId)
            val itemId = call.parameters["id"]?.toLongOrNull()
                ?: throw InvalidInputException("itemId must be a number")
            val payload = call.receive<AvailabilityRequest>()
            val updated = venueMenuRepository.setItemAvailability(venueId, itemId, payload.isAvailable)
                ?: throw NotFoundException()
            call.respond(updated.toDto())
        }

        post("/menu/reorder/categories") {
            val userId = call.requireUserId()
            val venueId = call.requireVenueId()
            ensureMenuManage(venueAccessRepository, userId, venueId)
            val payload = call.receive<ReorderCategoriesRequest>()
            validateUniqueIds(payload.categoryIds, "categoryIds")
            val success = venueMenuRepository.reorderCategories(venueId, payload.categoryIds)
            if (!success) {
                throw InvalidInputException("categoryIds must belong to venue")
            }
            call.respond(mapOf("ok" to true))
        }

        post("/menu/reorder/items") {
            val userId = call.requireUserId()
            val venueId = call.requireVenueId()
            ensureMenuManage(venueAccessRepository, userId, venueId)
            val payload = call.receive<ReorderItemsRequest>()
            validateUniqueIds(payload.itemIds, "itemIds")
            val success = venueMenuRepository.reorderItems(venueId, payload.categoryId, payload.itemIds)
            if (!success) {
                throw InvalidInputException("itemIds must belong to category")
            }
            call.respond(mapOf("ok" to true))
        }

        post("/menu/options") {
            val userId = call.requireUserId()
            val venueId = call.requireVenueId()
            ensureMenuManage(venueAccessRepository, userId, venueId)
            val payload = call.receive<CreateOptionRequest>()
            val name = payload.name.trim()
            validateName(name)
            validatePrice(payload.priceDeltaMinor)
            val created = venueMenuRepository.createOption(
                venueId = venueId,
                itemId = payload.itemId,
                name = name,
                priceDeltaMinor = payload.priceDeltaMinor,
                isAvailable = payload.isAvailable
            ) ?: throw InvalidInputException("itemId is invalid")
            call.respond(created.toDto())
        }

        patch("/menu/options/{id}") {
            val userId = call.requireUserId()
            val venueId = call.requireVenueId()
            ensureMenuManage(venueAccessRepository, userId, venueId)
            val optionId = call.parameters["id"]?.toLongOrNull()
                ?: throw InvalidInputException("optionId must be a number")
            if (!venueMenuRepository.optionExists(venueId, optionId)) {
                throw NotFoundException()
            }
            val payload = call.receive<UpdateOptionRequest>()
            val name = payload.name?.trim()
            if (name != null) {
                validateName(name)
            }
            payload.priceDeltaMinor?.let { validatePrice(it) }
            val updated = venueMenuRepository.updateOption(
                venueId = venueId,
                optionId = optionId,
                name = name,
                priceDeltaMinor = payload.priceDeltaMinor,
                isAvailable = payload.isAvailable
            ) ?: throw NotFoundException()
            call.respond(updated.toDto())
        }

        patch("/menu/options/{id}/availability") {
            val userId = call.requireUserId()
            val venueId = call.requireVenueId()
            ensureMenuManage(venueAccessRepository, userId, venueId)
            val optionId = call.parameters["id"]?.toLongOrNull()
                ?: throw InvalidInputException("optionId must be a number")
            val payload = call.receive<AvailabilityRequest>()
            val updated = venueMenuRepository.setOptionAvailability(venueId, optionId, payload.isAvailable)
                ?: throw NotFoundException()
            call.respond(updated.toDto())
        }

        delete("/menu/options/{id}") {
            val userId = call.requireUserId()
            val venueId = call.requireVenueId()
            ensureMenuManage(venueAccessRepository, userId, venueId)
            val optionId = call.parameters["id"]?.toLongOrNull()
                ?: throw InvalidInputException("optionId must be a number")
            val deleted = venueMenuRepository.deleteOption(venueId, optionId)
            if (!deleted) {
                throw NotFoundException()
            }
            call.respond(mapOf("ok" to true))
        }
    }
}

private suspend fun ensureMenuManage(
    venueAccessRepository: VenueAccessRepository,
    userId: Long,
    venueId: Long
) {
    val role = resolveVenueRole(venueAccessRepository, userId, venueId)
    val permissions = VenuePermissions.forRole(role)
    if (!permissions.contains(VenuePermission.MENU_MANAGE)) {
        throw ForbiddenException()
    }
}

private fun validateName(name: String) {
    if (name.isBlank()) {
        throw InvalidInputException("name must not be blank")
    }
    if (name.length > NAME_MAX_LENGTH) {
        throw InvalidInputException("name length must be <= $NAME_MAX_LENGTH")
    }
}

private fun validatePrice(priceMinor: Long) {
    if (priceMinor < 0) {
        throw InvalidInputException("price must be >= 0")
    }
}

private fun validateCurrency(currency: String) {
    if (currency.isBlank()) {
        throw InvalidInputException("currency must not be blank")
    }
    if (!allowedCurrencies.contains(currency)) {
        throw InvalidInputException("currency must be one of: ${allowedCurrencies.joinToString()}")
    }
}

private fun validateUniqueIds(ids: List<Long>, fieldName: String) {
    if (ids.isEmpty()) {
        throw InvalidInputException("$fieldName must not be empty")
    }
    if (ids.any { it <= 0 }) {
        throw InvalidInputException("$fieldName must contain positive ids")
    }
    if (ids.toSet().size != ids.size) {
        throw InvalidInputException("$fieldName must not contain duplicates")
    }
}

private fun VenueMenuCategory.toDto(): VenueMenuCategoryDto = VenueMenuCategoryDto(
    id = id,
    name = name,
    sortOrder = sortOrder,
    items = items.map { it.toDto() }
)

private fun VenueMenuItem.toDto(): VenueMenuItemDto = VenueMenuItemDto(
    id = id,
    categoryId = categoryId,
    name = name,
    priceMinor = priceMinor,
    currency = currency,
    isAvailable = isAvailable,
    sortOrder = sortOrder,
    options = options.map { it.toDto() }
)

private fun VenueMenuOption.toDto(): VenueMenuOptionDto = VenueMenuOptionDto(
    id = id,
    itemId = itemId,
    name = name,
    priceDeltaMinor = priceDeltaMinor,
    isAvailable = isAvailable,
    sortOrder = sortOrder
)
