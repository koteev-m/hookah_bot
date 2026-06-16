package com.hookah.platform.backend.miniapp.venue.menu

import com.hookah.platform.backend.api.InvalidInputException
import java.util.Locale

data class HookahBaseFlavorProfileApplyResult(
    val itemId: Long,
    val addedCount: Int,
    val existingCount: Int,
    val options: List<VenueMenuOption>,
)

object HookahFlavorProfileService {
    private const val HOOKAH_SECTION_NAME = "кальянное меню"

    val baseProfiles: List<String> =
        listOf(
            "Ягодный",
            "Фруктовый",
            "Цитрусовый",
            "Десертный",
            "Освежающий / мятный",
            "Напиточный",
            "Пряный",
            "Цветочный",
        )

    private val obsoleteProfileValues =
        listOf(
            "Яблоко",
            "Виноград",
            "Арбуз",
            "Дыня",
            "Черника",
            "Клубника",
            "Манго",
            "Персик",
            "Лимон",
            "Кола",
            "Жвачка",
            "Ягодные",
            "Фруктовые",
            "Цитрусовые",
            "Десертные",
            "Освежающие",
            "Мятные",
            "Напиточные",
            "Пряные",
            "Цветочные",
            "Освежающий",
            "Мятный",
            "Мята",
            "Освежающий/мятный",
            "Освежающий / Мятный",
        )

    private val obsoleteProfileKeys = obsoleteProfileValues.map { normalizeFlavorNameKey(it) }.toSet()

    fun normalizeFlavorNameKey(name: String): String =
        name.trim()
            .replace(Regex("""\s+"""), " ")
            .lowercase(Locale.ROOT)

    fun missingBaseProfiles(existingNames: Iterable<String>): List<String> {
        val existingKeys = existingNames.map { normalizeFlavorNameKey(it) }.toSet()
        return baseProfiles.filter { normalizeFlavorNameKey(it) !in existingKeys }
    }

    fun missingBaseProfileCount(existingNames: Iterable<String>): Int = missingBaseProfiles(existingNames).size

    fun isObsoleteProfileValue(name: String): Boolean {
        val trimmed = name.trim()
        if (baseProfiles.any { normalizeFlavorNameKey(it) == normalizeFlavorNameKey(trimmed) }) {
            return false
        }
        return normalizeFlavorNameKey(trimmed) in obsoleteProfileKeys
    }

    fun isHookahFlavorProfileItem(
        category: VenueMenuCategory,
        item: VenueMenuItem,
    ): Boolean {
        val effectiveType = item.effectiveType(category)
        if (effectiveType == MenuSemanticType.HOOKAH) {
            return true
        }
        return item.itemType == null &&
            category.name.trim().equals(HOOKAH_SECTION_NAME, ignoreCase = true)
    }

    suspend fun applyMissingBaseProfiles(
        venueMenuRepository: VenueMenuRepository,
        venueId: Long,
        itemId: Long,
    ): HookahBaseFlavorProfileApplyResult {
        val (category, item) = loadItem(venueMenuRepository, venueId, itemId)
        val result =
            applyMissingBaseProfiles(
                venueMenuRepository = venueMenuRepository,
                venueId = venueId,
                category = category,
                item = item,
            )
        val refreshed = loadItem(venueMenuRepository, venueId, itemId).second
        return result.copy(options = refreshed.options)
    }

    suspend fun applyMissingBaseProfiles(
        venueMenuRepository: VenueMenuRepository,
        venueId: Long,
        category: VenueMenuCategory,
        item: VenueMenuItem,
    ): HookahBaseFlavorProfileApplyResult {
        if (!isHookahFlavorProfileItem(category, item)) {
            throw InvalidInputException("base flavor profiles are available only for hookah items")
        }

        val existingKeys = item.options.map { normalizeFlavorNameKey(it.name) }.toMutableSet()
        var existingCount = 0
        val createdOptions = mutableListOf<VenueMenuOption>()

        baseProfiles.forEach { profileName ->
            val key = normalizeFlavorNameKey(profileName)
            if (key in existingKeys) {
                existingCount += 1
                return@forEach
            }
            val created =
                venueMenuRepository.createOption(
                    venueId = venueId,
                    itemId = item.id,
                    name = profileName,
                    priceDeltaMinor = 0,
                    isAvailable = true,
                ) ?: throw InvalidInputException("itemId is invalid")
            existingKeys += key
            createdOptions += created
        }

        return HookahBaseFlavorProfileApplyResult(
            itemId = item.id,
            addedCount = createdOptions.size,
            existingCount = existingCount,
            options = item.options + createdOptions,
        )
    }

    private suspend fun loadItem(
        venueMenuRepository: VenueMenuRepository,
        venueId: Long,
        itemId: Long,
    ): Pair<VenueMenuCategory, VenueMenuItem> {
        venueMenuRepository.getMenu(venueId).forEach { category ->
            val item = category.items.firstOrNull { it.id == itemId }
            if (item != null) {
                return category to item
            }
        }
        throw InvalidInputException("itemId is invalid")
    }
}
