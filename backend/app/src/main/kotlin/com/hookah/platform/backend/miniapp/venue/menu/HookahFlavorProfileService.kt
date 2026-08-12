package com.hookah.platform.backend.miniapp.venue.menu

import java.util.Locale

const val BASE_FLAVOR_PROFILE_ALREADY_EXISTS_MESSAGE = "base flavor profile already exists"

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

    fun isCanonicalProfileValue(name: String): Boolean {
        val key = normalizeFlavorNameKey(name)
        return baseProfiles.any { normalizeFlavorNameKey(it) == key }
    }

    fun isHookahMenuSection(
        categoryName: String,
        categoryType: MenuSemanticType,
    ): Boolean =
        categoryType == MenuSemanticType.HOOKAH ||
            categoryName.trim().equals(HOOKAH_SECTION_NAME, ignoreCase = true)

    fun isObsoleteProfileValue(name: String): Boolean {
        val trimmed = name.trim()
        if (isCanonicalProfileValue(trimmed)) {
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
        return item.itemType == null && isHookahMenuSection(category.name, category.categoryType)
    }
}
