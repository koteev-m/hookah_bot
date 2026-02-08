package com.hookah.platform.backend.telegram

object TelegramKeyboards {
    fun mainMenu(
        hasVenueRole: Boolean,
        isPlatformOwner: Boolean,
        webAppUrl: String?,
    ): ReplyKeyboardMarkup {
        val buttons = mutableListOf<List<KeyboardButton>>()
        webAppUrl?.let { url ->
            buttons.add(
                listOf(
                    KeyboardButton(
                        text = "🗺️ Каталог кальянных",
                        webApp = WebAppInfo(url = "$url?screen=catalog"),
                    ),
                ),
            )
            if (hasVenueRole) {
                buttons.add(
                    listOf(
                        KeyboardButton(
                            text = "⚙️ Для заведения",
                            webApp = WebAppInfo(url = "$url?mode=venue"),
                        ),
                    ),
                )
            }
            if (isPlatformOwner) {
                buttons.add(
                    listOf(
                        KeyboardButton(
                            text = "🛠️ Управление платформой",
                            webApp = WebAppInfo(url = "$url?mode=platform"),
                        ),
                    ),
                )
            }
        }
        val fallbackKeyboard = listOf(listOf(KeyboardButton(text = "🗺️ Каталог кальянных")))
        return ReplyKeyboardMarkup(keyboard = buttons.ifEmpty { fallbackKeyboard })
    }

    fun tableContext(
        context: TableContext,
        webAppUrl: String?,
    ): ReplyKeyboardMarkup {
        val keyboard = mutableListOf<List<KeyboardButton>>()
        webAppUrl?.let { url ->
            val params = mapOf("table_token" to context.tableToken)
            keyboard.add(
                listOf(
                    KeyboardButton(
                        text = "🍽️ Открыть меню",
                        webApp =
                            WebAppInfo(
                                url = buildWebAppUrl(url, params + ("screen" to "menu")),
                            ),
                    ),
                ),
            )
            keyboard.add(
                listOf(
                    KeyboardButton(
                        text = "➕ Дозаказать",
                        webApp =
                            WebAppInfo(
                                url =
                                    buildWebAppUrl(
                                        url,
                                        params + ("screen" to "menu") + ("intent" to "add"),
                                    ),
                            ),
                    ),
                ),
            )
        }
        keyboard.add(listOf(KeyboardButton(text = "🧾 Активный заказ")))
        keyboard.add(listOf(KeyboardButton(text = "✍️ Быстрый заказ")))
        keyboard.add(listOf(KeyboardButton(text = "🛎️ Вызов персонала")))
        keyboard.add(listOf(KeyboardButton(text = "🔁 Сменить стол")))
        keyboard.add(listOf(KeyboardButton(text = "🏠 В каталог")))
        return ReplyKeyboardMarkup(keyboard = keyboard)
    }

    fun inlineConfirmQuickOrder(): InlineKeyboardMarkup =
        InlineKeyboardMarkup(
            inlineKeyboard =
                listOf(
                    listOf(InlineKeyboardButton(text = "✅ Отправить", callbackData = "quick_order_confirm")),
                    listOf(InlineKeyboardButton(text = "✏️ Изменить", callbackData = "quick_order_edit")),
                    listOf(InlineKeyboardButton(text = "❌ Отмена", callbackData = "quick_order_cancel")),
                ),
        )

    fun inlineStaffCallReasons(): InlineKeyboardMarkup =
        InlineKeyboardMarkup(
            inlineKeyboard =
                listOf(
                    listOf(InlineKeyboardButton(text = "🔥 Принести угли", callbackData = "staff_call_reason:COALS")),
                    listOf(InlineKeyboardButton(text = "🧾 Принести счёт", callbackData = "staff_call_reason:BILL")),
                    listOf(InlineKeyboardButton(text = "👋 Подойти к столу", callbackData = "staff_call_reason:COME")),
                    listOf(InlineKeyboardButton(text = "✍️ Другое", callbackData = "staff_call_reason:OTHER")),
                ),
        )

    fun inlineOpenActiveOrder(
        webAppUrl: String?,
        tableToken: String,
    ): InlineKeyboardMarkup? {
        val url = webAppUrl ?: return null
        return InlineKeyboardMarkup(
            inlineKeyboard =
                listOf(
                    listOf(
                        InlineKeyboardButton(
                            text = "Открыть в Mini App",
                            webApp =
                                WebAppInfo(
                                    url = buildWebAppUrl(url, mapOf("table_token" to tableToken, "screen" to "order")),
                                ),
                        ),
                    ),
                ),
        )
    }

    fun inlineOpenMenu(
        webAppUrl: String?,
        tableToken: String,
    ): InlineKeyboardMarkup? {
        val url = webAppUrl ?: return null
        return InlineKeyboardMarkup(
            inlineKeyboard =
                listOf(
                    listOf(
                        InlineKeyboardButton(
                            text = "🍽️ Открыть меню",
                            webApp =
                                WebAppInfo(
                                    url = buildWebAppUrl(url, mapOf("table_token" to tableToken, "screen" to "menu")),
                                ),
                        ),
                    ),
                ),
        )
    }
}
