package com.hookah.platform.backend.telegram

/** Short action names leave room for the participation digest in Telegram's callback_data. */
internal object GuestVisitButton {
    private data class Action(val code: String, val label: String)

    private val actions =
        mapOf(
            "bot_menu_item" to Action("w", "Выбрать позицию меню"),
            "guest_reorder" to Action("q", "Дозаказать позицию"),
            "bot_menu_cart_checkout" to Action("h", "Оформить эту корзину"),
            "guest_join_code" to Action("k", "Вступить в общий счёт"),
            "guest_cart_note" to Action("n", "Применить комментарий"),
            "guest_staff_note" to Action("v", "Вызвать персонал"),
            "guest_end_visit" to Action("e", "Завершить визит"),
            "bot_tabs_create_shared" to Action("s", "Создать общий счёт"),
            "bot_tabs_join_prompt" to Action("j", "Ввести код общего счёта"),
            "bot_menu_cart_retry" to Action("u", "Повторить исходную отправку"),
            "bot_menu_cart_clear" to Action("c", "Очистить корзину"),
            "bot_menu_cart_comment" to Action("t", "Ввести комментарий"),
            "bot_menu_cart_inc" to Action("i", "Увеличить количество"),
            "bot_menu_cart_dec" to Action("d", "Уменьшить количество"),
            "bot_menu_cart_remove" to Action("r", "Удалить позицию"),
            "bot_menu_item_option_add_to_cart" to Action("o", "Добавить выбранный вариант"),
            "bot_menu_item_add" to Action("a", "Выбрать позицию"),
            "bot_menu_item_add_to_cart" to Action("b", "Добавить позицию"),
            "relocation_call_staff" to Action("m", "Позвать персонал для пересадки"),
            "staff_call_reason" to Action("p", "Продолжить вызов персонала"),
            "staff_call_bill_payment" to Action("f", "Запросить оплату заказа"),
            "guest_shift_extension_request" to Action("x", "Запросить продление"),
        )
    private val names = actions.entries.associate { (name, action) -> action.code to name }

    fun label(raw: String): String? = actions[raw.substringBefore(':')]?.label

    fun encode(raw: String): String? {
        val args = raw.split(':')
        val action = actions[args.first()] ?: return null
        if (args.first() == "bot_menu_cart_checkout") return action.code
        if (args.first() == "guest_reorder") {
            val ids = args.drop(1).take(2)
            if (ids.size != 2 || ids.any { (it.toLongOrNull(36) ?: 0) <= 0 }) return null
            return (listOf(action.code) + ids.map { it.toLong(36).toString(36) }).joinToString(":")
        }
        if (args.first() == "guest_join_code") {
            val code = args.getOrNull(1)?.takeIf { it.matches(Regex("[0-9]{4}")) } ?: return null
            val targets = args.drop(2).map { it.toLongOrNull()?.takeIf { id -> id > 0 } ?: return null }
            if (targets.size != 2) return null
            return (listOf(action.code, code) + targets.map { it.toString(36) }).joinToString(":")
        }
        val numbers = args.drop(1).map { it.toLongOrNull()?.toString(36) ?: it.lowercase() }
        val encoded = (listOf(action.code) + numbers).joinToString(":")
        return encoded.takeIf {
            it.length <= 44 &&
                it.all {
                        ch ->
                    ch in 'a'..'z' || ch in '0'..'9' || ch == ':' || ch == '-'
                }
        }
    }

    fun decode(encoded: String): String? {
        val args = encoded.split(':')
        val name = names[args.first()] ?: return null
        val values =
            args.drop(1).mapIndexed { index, value ->
                val it = value
                when {
                    name == "bot_menu_cart_retry" -> it
                    name == "guest_reorder" -> it.takeIf { id -> (id.toLongOrNull(36) ?: 0) > 0 } ?: return null
                    name == "guest_join_code" && index == 0 ->
                        it.takeIf {
                                code ->
                            code.matches(Regex("[0-9]{4}"))
                        } ?: return null
                    name.startsWith("staff_call_") -> it.uppercase()
                    name == "guest_shift_extension_request" && index == 2 -> it.uppercase()
                    else -> it.toLongOrNull(36)?.toString() ?: return null
                }
            }
        return (listOf(name) + values).joinToString(":")
    }
}
