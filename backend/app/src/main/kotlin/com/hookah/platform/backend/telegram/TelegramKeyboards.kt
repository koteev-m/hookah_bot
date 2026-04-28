package com.hookah.platform.backend.telegram

object TelegramKeyboards {
    fun venueManagerMenu(): ReplyKeyboardMarkup {
        val keyboard =
            listOf(
                listOf(
                    KeyboardButton(text = "🧾 Заказы"),
                    KeyboardButton(text = "🛎 Вызовы"),
                ),
                listOf(
                    KeyboardButton(text = "🚫 Стоп-лист"),
                    KeyboardButton(text = "📄 Брони"),
                ),
                listOf(
                    KeyboardButton(text = "🍽 Меню"),
                    KeyboardButton(text = "🏢 Заведение"),
                ),
                listOf(
                    KeyboardButton(text = "🪑 Столы и QR"),
                    KeyboardButton(text = "📊 Статистика"),
                ),
            )
        return ReplyKeyboardMarkup(keyboard = keyboard, resizeKeyboard = true)
    }

    fun venueStaffMenu(): ReplyKeyboardMarkup {
        val keyboard =
            listOf(
                listOf(
                    KeyboardButton(text = "🧾 Заказы"),
                    KeyboardButton(text = "🛎 Вызовы"),
                ),
                listOf(
                    KeyboardButton(text = "🚫 Стоп-лист"),
                    KeyboardButton(text = "📄 Брони"),
                ),
            )
        return ReplyKeyboardMarkup(keyboard = keyboard, resizeKeyboard = true)
    }

    fun venueOwnerMenu(): ReplyKeyboardMarkup {
        val keyboard =
            listOf(
                listOf(
                    KeyboardButton(text = "🏢 Моё заведение"),
                    KeyboardButton(text = "🍽 Меню заведения"),
                ),
                listOf(
                    KeyboardButton(text = "🪑 Столы и QR"),
                    KeyboardButton(text = "👥 Персонал"),
                ),
                listOf(
                    KeyboardButton(text = "📦 Заказы"),
                    KeyboardButton(text = "📊 Статистика"),
                ),
            )
        return ReplyKeyboardMarkup(keyboard = keyboard, resizeKeyboard = true)
    }

    fun ownerMainMenu(): ReplyKeyboardMarkup {
        val keyboard =
            listOf(
                listOf(
                    KeyboardButton(text = "📨 Заявки на подключение"),
                    KeyboardButton(text = "🏢 Кальянные"),
                ),
                listOf(
                    KeyboardButton(text = "👤 Владельцы"),
                    KeyboardButton(text = "💳 Подписки"),
                ),
                listOf(
                    KeyboardButton(text = "⚙️ Статусы"),
                ),
            )
        return ReplyKeyboardMarkup(keyboard = keyboard, resizeKeyboard = true)
    }

    fun mainMenu(
        hasVenueRole: Boolean,
        isPlatformOwner: Boolean,
        webAppUrl: String?,
    ): ReplyMarkup {
        val miniAppButton =
            KeyboardButton(
                text = "📱 Открыть Mini App",
                webApp =
                    webAppUrl
                        ?.takeIf { it.isNotBlank() }
                        ?.let { url ->
                            WebAppInfo(url = buildWebAppUrl(url, mapOf("screen" to "catalog")))
                        },
            )
        val keyboard =
            mutableListOf(
                listOf(
                    KeyboardButton(text = "🍽 Каталог кальянных"),
                    miniAppButton,
                ),
                listOf(
                    KeyboardButton(text = "🎁 Акции"),
                    KeyboardButton(text = "🪑 Я за столом / У меня QR"),
                ),
                listOf(
                    KeyboardButton(text = "📄 Мои заказы и брони"),
                    KeyboardButton(text = "🤝 Добавить свою кальянную"),
                ),
            )

        val url = webAppUrl?.takeIf { it.isNotBlank() }
        if (url != null && hasVenueRole) {
            keyboard.add(
                listOf(
                    KeyboardButton(
                        text = "⚙️ Для заведения",
                        webApp = WebAppInfo(url = "$url?mode=venue"),
                    ),
                ),
            )
        }
        if (url != null && isPlatformOwner) {
            keyboard.add(
                listOf(
                    KeyboardButton(
                        text = "🛠️ Управление платформой",
                        webApp = WebAppInfo(url = "$url?mode=platform"),
                    ),
                ),
            )
        }

        return ReplyKeyboardMarkup(keyboard = keyboard, resizeKeyboard = true)
    }

    fun inlineTableEntryChoice(
        webAppUrl: String?,
        tableToken: String,
    ): InlineKeyboardMarkup {
        val rows = mutableListOf<List<InlineKeyboardButton>>()
        webAppUrl
            ?.takeIf { it.isNotBlank() }
            ?.let { url ->
                rows.add(
                    listOf(
                        InlineKeyboardButton(
                            text = "📱 Заказывать в Mini App",
                            webApp =
                                WebAppInfo(
                                    url = buildWebAppUrl(url, mapOf("table_token" to tableToken, "screen" to "menu")),
                                ),
                        ),
                    ),
                )
            }
        rows.add(
            listOf(
                InlineKeyboardButton(
                    text = "💬 Заказывать в боте",
                    callbackData = "continue_in_bot",
                ),
            ),
        )
        return InlineKeyboardMarkup(inlineKeyboard = rows)
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
                        text = "🍽️ Меню",
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
        keyboard.add(listOf(KeyboardButton(text = "🔔 Вызвать персонал")))
        keyboard.add(listOf(KeyboardButton(text = "🔁 Сменить стол")))
        keyboard.add(listOf(KeyboardButton(text = "🏠 В каталог")))
        return ReplyKeyboardMarkup(keyboard = keyboard)
    }

    fun tableContextBotFlow(context: TableContext): ReplyKeyboardMarkup {
        val keyboard =
            listOf(
                listOf(
                    KeyboardButton(text = "🍽️ Меню"),
                    KeyboardButton(text = "🧺 Корзина"),
                ),
                listOf(
                    KeyboardButton(text = "📄 Мой заказ"),
                    KeyboardButton(text = "👥 Общий счёт"),
                ),
                listOf(
                    KeyboardButton(text = "🛎 Вызвать персонал"),
                    KeyboardButton(text = "🚪 Сменить стол"),
                ),
            )
        return ReplyKeyboardMarkup(keyboard = keyboard)
    }

    fun inlineBotVenueCatalog(venues: List<Pair<Long, String>>): InlineKeyboardMarkup =
        InlineKeyboardMarkup(
            inlineKeyboard =
                venues.map { (venueId, venueName) ->
                    listOf(
                        InlineKeyboardButton(
                            text = venueName,
                            callbackData = "bot_catalog_venue:$venueId",
                        ),
                    )
                },
        )

    fun inlineBotVenueCardActions(
        venueId: Long,
        routeUrl: String? = null,
    ): InlineKeyboardMarkup =
        InlineKeyboardMarkup(
            inlineKeyboard =
                listOf(
                    listOf(
                        InlineKeyboardButton(
                            text = "🪑 Забронировать стол",
                            callbackData = "bot_catalog_venue_book:$venueId",
                        ),
                        InlineKeyboardButton(
                            text = "🍽 Меню",
                            callbackData = "bot_catalog_venue_menu:$venueId",
                        ),
                    ),
                    listOf(
                        InlineKeyboardButton(
                            text = "ℹ️ О заведении",
                            callbackData = "bot_catalog_venue_about:$venueId",
                        ),
                        InlineKeyboardButton(
                            text = "🗺 Маршрут",
                            callbackData = if (routeUrl == null) "bot_catalog_venue_address:$venueId" else null,
                            url = routeUrl,
                        ),
                    ),
                ),
        )

    fun inlineBotVenueMenuSections(
        venueId: Long,
        sections: List<Pair<Long, String>>,
    ): InlineKeyboardMarkup =
        InlineKeyboardMarkup(
            inlineKeyboard =
                buildList {
                    sections.forEach { (sectionId, sectionName) ->
                        add(
                            listOf(
                                InlineKeyboardButton(
                                    text = sectionName,
                                    callbackData = "bot_catalog_venue_menu_section:$venueId:$sectionId",
                                ),
                            ),
                        )
                    }
                    add(
                        listOf(
                            InlineKeyboardButton(
                                text = "⬅️ К карточке заведения",
                                callbackData = "bot_catalog_venue:$venueId",
                            ),
                        ),
                    )
                },
        )

    fun inlineBotVenueMenuSectionActions(venueId: Long): InlineKeyboardMarkup =
        InlineKeyboardMarkup(
            inlineKeyboard =
                listOf(
                    listOf(
                        InlineKeyboardButton(
                            text = "⬅️ К разделам меню",
                            callbackData = "bot_catalog_venue_menu:$venueId",
                        ),
                    ),
                    listOf(
                        InlineKeyboardButton(
                            text = "⬅️ К карточке заведения",
                            callbackData = "bot_catalog_venue:$venueId",
                        ),
                    ),
                ),
        )

    fun inlineBotVenueAboutSections(
        venueId: Long,
        sections: List<Pair<Long, String>>,
    ): InlineKeyboardMarkup =
        InlineKeyboardMarkup(
            inlineKeyboard =
                buildList {
                    sections.forEach { (sectionId, sectionName) ->
                        add(
                            listOf(
                                InlineKeyboardButton(
                                    text = sectionName,
                                    callbackData = "bot_catalog_venue_about_section:$venueId:$sectionId",
                                ),
                            ),
                        )
                    }
                    add(
                        listOf(
                            InlineKeyboardButton(
                                text = "⬅️ К карточке заведения",
                                callbackData = "bot_catalog_venue:$venueId",
                            ),
                        ),
                    )
                },
        )

    fun inlineBotVenueAboutSectionActions(venueId: Long): InlineKeyboardMarkup =
        InlineKeyboardMarkup(
            inlineKeyboard =
                listOf(
                    listOf(
                        InlineKeyboardButton(
                            text = "⬅️ К разделам",
                            callbackData = "bot_catalog_venue_about:$venueId",
                        ),
                    ),
                    listOf(
                        InlineKeyboardButton(
                            text = "⬅️ К карточке заведения",
                            callbackData = "bot_catalog_venue:$venueId",
                        ),
                    ),
                ),
        )

    fun inlineBotVenueBookingDateActions(
        venueId: Long,
        dateButtons: List<Pair<String, String>>,
        nextOffset: Int?,
        buttonsPerRow: Int = 1,
        backCallbackData: String = "bot_catalog_venue:$venueId",
    ): InlineKeyboardMarkup =
        InlineKeyboardMarkup(
            inlineKeyboard =
                buildList {
                    val normalizedButtonsPerRow = buttonsPerRow.coerceAtLeast(1)
                    dateButtons.chunked(normalizedButtonsPerRow).forEach { chunk ->
                        add(
                            chunk.map { (label, isoDate) ->
                                InlineKeyboardButton(
                                    text = label,
                                    callbackData = "bot_catalog_venue_book_date:$venueId:$isoDate",
                                )
                            },
                        )
                    }
                    add(
                        buildList {
                            if (nextOffset != null) {
                                add(
                                    InlineKeyboardButton(
                                        text = "📅 Ещё даты",
                                        callbackData = "bot_catalog_venue_book_more:$venueId:$nextOffset",
                                    ),
                                )
                            }
                            add(
                                InlineKeyboardButton(
                                    text = "⬅️ Назад",
                                    callbackData = backCallbackData,
                                ),
                            )
                        },
                    )
                },
        )

    fun inlineBotVenueBookingTimeActions(
        venueId: Long,
        isoDate: String,
        timeSlots: List<String>,
        buttonsPerRow: Int = 2,
        backCallbackData: String = "bot_catalog_venue_book:$venueId",
    ): InlineKeyboardMarkup =
        InlineKeyboardMarkup(
            inlineKeyboard =
                buildList {
                    val normalizedButtonsPerRow = buttonsPerRow.coerceAtLeast(1)
                    timeSlots.chunked(normalizedButtonsPerRow).forEach { chunk ->
                        add(
                            chunk.map { time ->
                                InlineKeyboardButton(
                                    text = time,
                                    callbackData = "bot_catalog_venue_book_time:$venueId:$isoDate:$time",
                                )
                            },
                        )
                    }
                    add(
                        listOf(
                            InlineKeyboardButton(
                                text = "⬅️ Назад",
                                callbackData = backCallbackData,
                            ),
                        ),
                    )
                },
        )

    fun inlineBotVenueBookingGuestCountActions(
        venueId: Long,
        isoDate: String,
        time: String,
        backCallbackData: String = "bot_catalog_venue_book_date:$venueId:$isoDate",
    ): InlineKeyboardMarkup {
        val encodedTime = time.replace(':', '_')
        fun guestButton(
            label: String,
            token: String,
        ): InlineKeyboardButton =
            InlineKeyboardButton(
                text = label,
                callbackData = "bot_catalog_venue_book_guests:$venueId:$isoDate:$encodedTime:$token",
            )
        return InlineKeyboardMarkup(
            inlineKeyboard =
                listOf(
                    listOf(
                        guestButton("1", "1"),
                        guestButton("2", "2"),
                        guestButton("3", "3"),
                    ),
                    listOf(
                        guestButton("4", "4"),
                        guestButton("5", "5"),
                        guestButton("6+", "6plus"),
                    ),
                    listOf(
                        InlineKeyboardButton(
                            text = "⬅️ Назад",
                            callbackData = backCallbackData,
                        ),
                    ),
                ),
        )
    }

    fun inlineBotVenueBookingCommentActions(
        venueId: Long,
        isoDate: String,
        time: String,
        guestsToken: String,
    ): InlineKeyboardMarkup {
        val encodedTime = time.replace(':', '_')
        return InlineKeyboardMarkup(
            inlineKeyboard =
                listOf(
                    listOf(
                        InlineKeyboardButton(
                            text = "✍️ Оставить комментарий",
                            callbackData =
                                "bot_catalog_venue_book_comment_prompt:$venueId:$isoDate:$encodedTime:$guestsToken",
                        ),
                    ),
                    listOf(
                        InlineKeyboardButton(
                            text = "➡️ Продолжить без комментария",
                            callbackData =
                                "bot_catalog_venue_book_comment_skip:$venueId:$isoDate:$encodedTime:$guestsToken",
                        ),
                    ),
                ),
        )
    }

    fun inlineBotVenueBookingConfirmActions(): InlineKeyboardMarkup =
        InlineKeyboardMarkup(
            inlineKeyboard =
                listOf(
                    listOf(
                        InlineKeyboardButton(
                            text = "✅ Подтвердить бронь",
                            callbackData = "bot_catalog_venue_book_confirm",
                        ),
                    ),
                    listOf(
                        InlineKeyboardButton(
                            text = "⬅️ Назад к комментарию",
                            callbackData = "bot_catalog_venue_book_back_comment",
                        ),
                    ),
                ),
        )

    fun inlineVenueConnectionContactChoice(): InlineKeyboardMarkup =
        InlineKeyboardMarkup(
            inlineKeyboard =
                listOf(
                    listOf(
                        InlineKeyboardButton(
                            text = "✅ Оставить только @username",
                            callbackData = "venue_connect_contact_use_username",
                        ),
                    ),
                    listOf(
                        InlineKeyboardButton(
                            text = "➕ Добавить телефон или email",
                            callbackData = "venue_connect_contact_additional",
                        ),
                    ),
                ),
        )

    fun inlineVenueConnectionPendingActions(requestId: Long): InlineKeyboardMarkup =
        InlineKeyboardMarkup(
            inlineKeyboard =
                listOf(
                    listOf(
                        InlineKeyboardButton(
                            text = "✏️ Изменить заявку",
                            callbackData = "venue_connect_pending_edit:$requestId",
                        ),
                        InlineKeyboardButton(
                            text = "❌ Отменить заявку",
                            callbackData = "venue_connect_pending_cancel:$requestId",
                        ),
                    ),
                ),
        )

    fun inlineOwnerVenueConnectionRequestActions(requestId: Long): InlineKeyboardMarkup =
        InlineKeyboardMarkup(
            inlineKeyboard =
                listOf(
                    listOf(
                        InlineKeyboardButton(
                            text = "✅ Принять",
                            callbackData = "owner_venue_connect_approve:$requestId",
                        ),
                        InlineKeyboardButton(
                            text = "❌ Отклонить",
                            callbackData = "owner_venue_connect_reject:$requestId",
                        ),
                    ),
                ),
        )

    fun inlineOwnerVenueConnectionApprovedActions(requestId: Long): InlineKeyboardMarkup =
        InlineKeyboardMarkup(
            inlineKeyboard =
                listOf(
                    listOf(
                        InlineKeyboardButton(
                            text = "⚙️ Коммерческие условия",
                            callbackData = "owner_venue_terms_open:$requestId",
                        ),
                    ),
                    listOf(
                        InlineKeyboardButton(
                            text = "⬅️ К заявкам",
                            callbackData = "owner_venue_requests_back",
                        ),
                    ),
                ),
        )

    fun inlineOwnerVenueCommercialTrialActions(requestId: Long): InlineKeyboardMarkup =
        InlineKeyboardMarkup(
            inlineKeyboard =
                listOf(
                    listOf(
                        InlineKeyboardButton(text = "Без trial", callbackData = "owner_venue_terms_trial:$requestId:none"),
                        InlineKeyboardButton(text = "7 дней", callbackData = "owner_venue_terms_trial:$requestId:7d"),
                    ),
                    listOf(
                        InlineKeyboardButton(text = "14 дней", callbackData = "owner_venue_terms_trial:$requestId:14d"),
                        InlineKeyboardButton(text = "1 месяц", callbackData = "owner_venue_terms_trial:$requestId:1m"),
                    ),
                    listOf(
                        InlineKeyboardButton(text = "3 месяца", callbackData = "owner_venue_terms_trial:$requestId:3m"),
                        InlineKeyboardButton(text = "Другая дата окончания", callbackData = "owner_venue_terms_trial:$requestId:custom"),
                    ),
                    listOf(
                        InlineKeyboardButton(text = "⬅️ К заявкам", callbackData = "owner_venue_requests_back"),
                    ),
                ),
        )

    fun inlineOwnerVenueCommercialFuturePriceActions(requestId: Long): InlineKeyboardMarkup =
        InlineKeyboardMarkup(
            inlineKeyboard =
                listOf(
                    listOf(
                        InlineKeyboardButton(text = "Не задавать", callbackData = "owner_venue_terms_future:$requestId:skip"),
                        InlineKeyboardButton(text = "Задать будущую стоимость", callbackData = "owner_venue_terms_future:$requestId:set"),
                    ),
                ),
        )

    fun inlineOwnerVenueCommercialSummaryActions(requestId: Long): InlineKeyboardMarkup =
        InlineKeyboardMarkup(
            inlineKeyboard =
                listOf(
                    listOf(
                        InlineKeyboardButton(
                            text = "✅ Создать и передать владельцу",
                            callbackData = "owner_venue_create_from_request:$requestId",
                        ),
                    ),
                    listOf(
                        InlineKeyboardButton(
                            text = "✏️ Изменить условия",
                            callbackData = "owner_venue_terms_open:$requestId",
                        ),
                    ),
                    listOf(
                        InlineKeyboardButton(
                            text = "⬅️ К заявкам",
                            callbackData = "owner_venue_requests_back",
                        ),
                    ),
                ),
        )

    fun inlineVenueOwnerOnboardingEntry(webAppUrl: String?): InlineKeyboardMarkup =
        InlineKeyboardMarkup(
            inlineKeyboard =
                listOf(
                    listOf(
                        webAppUrl
                            ?.takeIf { it.isNotBlank() }
                            ?.let { url ->
                                InlineKeyboardButton(
                                    text = "📱 Настраивать в Mini App",
                                    webApp = WebAppInfo(url = buildWebAppUrl(url, mapOf("mode" to "venue"))),
                                )
                            }
                            ?: InlineKeyboardButton(
                                text = "📱 Настраивать в Mini App",
                                callbackData = "owner_venue_onboarding_miniapp_unavailable",
                            ),
                    ),
                    listOf(
                        InlineKeyboardButton(
                            text = "💬 Настраивать в боте",
                            callbackData = "owner_venue_onboarding_entry",
                        ),
                    ),
                ),
        )

    fun inlineVenueOwnerProfileActions(venueId: Long): InlineKeyboardMarkup =
        InlineKeyboardMarkup(
            inlineKeyboard =
                listOf(
                    listOf(
                        InlineKeyboardButton(
                            text = "✏️ Название",
                            callbackData = "owner_venue_field:$venueId:name",
                        ),
                        InlineKeyboardButton(
                            text = "📍 Адрес",
                            callbackData = "owner_venue_field:$venueId:address",
                        ),
                    ),
                    listOf(
                        InlineKeyboardButton(
                            text = "🕒 Часы работы",
                            callbackData = "owner_venue_field:$venueId:hours",
                        ),
                        InlineKeyboardButton(
                            text = "📝 Описание",
                            callbackData = "owner_venue_field:$venueId:description",
                        ),
                    ),
                    listOf(
                        InlineKeyboardButton(
                            text = "👁 Предпросмотр",
                            callbackData = "owner_venue_publish_preview:$venueId",
                        ),
                    ),
                    listOf(
                        InlineKeyboardButton(
                            text = "✅ Готовность к публикации",
                            callbackData = "owner_venue_publish_readiness:$venueId",
                        ),
                    ),
                ),
        )

    fun inlineVenueManagerProfileActions(venueId: Long): InlineKeyboardMarkup =
        InlineKeyboardMarkup(
            inlineKeyboard =
                listOf(
                    listOf(
                        InlineKeyboardButton(
                            text = "✏️ Название",
                            callbackData = "owner_venue_field:$venueId:name",
                        ),
                        InlineKeyboardButton(
                            text = "📍 Адрес",
                            callbackData = "owner_venue_field:$venueId:address",
                        ),
                    ),
                    listOf(
                        InlineKeyboardButton(
                            text = "🕒 Часы работы",
                            callbackData = "owner_venue_field:$venueId:hours",
                        ),
                        InlineKeyboardButton(
                            text = "📝 Описание",
                            callbackData = "owner_venue_field:$venueId:description",
                        ),
                    ),
                    listOf(
                        InlineKeyboardButton(
                            text = "👁 Предпросмотр",
                            callbackData = "owner_venue_publish_preview:$venueId",
                        ),
                    ),
                ),
        )

    fun inlineVenueOwnerOrderMenuRootActions(
        venueId: Long,
        sectionButtons: List<Pair<Long, String>>,
    ): InlineKeyboardMarkup =
        InlineKeyboardMarkup(
            inlineKeyboard =
                buildList {
                    sectionButtons.forEach { (sectionId, title) ->
                        add(
                            listOf(
                                InlineKeyboardButton(
                                    text = title,
                                    callbackData = "owner_venue_order_menu_section:$venueId:$sectionId",
                                ),
                            ),
                        )
                    }
                    add(
                        listOf(
                            InlineKeyboardButton(
                                text = "➕ Добавить раздел",
                                callbackData = "owner_venue_order_menu_add:$venueId",
                            ),
                        ),
                    )
                    add(
                        listOf(
                            InlineKeyboardButton(
                                text = "🚫 Стоп-лист",
                                callbackData = "owner_venue_order_menu_stoplist:$venueId",
                            ),
                        ),
                    )
                },
        )

    fun inlineVenueOwnerTablesRootActions(venueId: Long): InlineKeyboardMarkup =
        InlineKeyboardMarkup(
            inlineKeyboard =
                listOf(
                    listOf(
                        InlineKeyboardButton(
                            text = "📋 Список столов",
                            callbackData = "owner_venue_tables_list:$venueId",
                        ),
                    ),
                    listOf(
                        InlineKeyboardButton(
                            text = "➕ Добавить стол",
                            callbackData = "owner_venue_tables_add:$venueId",
                        ),
                    ),
                    listOf(
                        InlineKeyboardButton(
                            text = "🧾 QR-коды",
                            callbackData = "owner_venue_tables_qr:$venueId",
                        ),
                    ),
                ),
        )

    fun inlineVenueOwnerStaffRootActions(
        venueId: Long,
        removableMembers: List<Pair<Long, String>> = emptyList(),
    ): InlineKeyboardMarkup =
        InlineKeyboardMarkup(
            inlineKeyboard =
                buildList {
                    removableMembers.forEach { (userId, label) ->
                        add(
                            listOf(
                                InlineKeyboardButton(
                                    text = "🚫 Удалить доступ: $label",
                                    callbackData = "owner_venue_staff_remove_prompt:$venueId:$userId",
                                ),
                            ),
                        )
                    }
                    add(
                        listOf(
                            InlineKeyboardButton(
                                text = "➕ Добавить сотрудника",
                                callbackData = "owner_venue_staff_add:$venueId",
                            ),
                        ),
                    )
                },
        )

    fun inlineVenueOwnerStaffRemoveConfirmActions(
        venueId: Long,
        userId: Long,
    ): InlineKeyboardMarkup =
        InlineKeyboardMarkup(
            inlineKeyboard =
                listOf(
                    listOf(
                        InlineKeyboardButton(
                            text = "✅ Подтвердить удаление",
                            callbackData = "owner_venue_staff_remove_confirm:$venueId:$userId",
                        ),
                    ),
                    listOf(
                        InlineKeyboardButton(
                            text = "⬅️ Отмена",
                            callbackData = "owner_venue_staff_root:$venueId",
                        ),
                    ),
                ),
        )

    fun inlineVenueOwnerStaffRoleChooserActions(venueId: Long): InlineKeyboardMarkup =
        InlineKeyboardMarkup(
            inlineKeyboard =
                listOf(
                    listOf(
                        InlineKeyboardButton(
                            text = "Owner",
                            callbackData = "owner_venue_staff_role_select:$venueId:owner",
                        ),
                        InlineKeyboardButton(
                            text = "ℹ️ О роли Owner",
                            callbackData = "owner_venue_staff_role_info:$venueId:owner",
                        ),
                    ),
                    listOf(
                        InlineKeyboardButton(
                            text = "Manager",
                            callbackData = "owner_venue_staff_role_select:$venueId:manager",
                        ),
                        InlineKeyboardButton(
                            text = "ℹ️ О роли Manager",
                            callbackData = "owner_venue_staff_role_info:$venueId:manager",
                        ),
                    ),
                    listOf(
                        InlineKeyboardButton(
                            text = "Staff / Оператор смены",
                            callbackData = "owner_venue_staff_role_select:$venueId:staff",
                        ),
                        InlineKeyboardButton(
                            text = "ℹ️ О роли Staff",
                            callbackData = "owner_venue_staff_role_info:$venueId:staff",
                        ),
                    ),
                    listOf(
                        InlineKeyboardButton(
                            text = "⬅️ К персоналу",
                            callbackData = "owner_venue_staff_root:$venueId",
                        ),
                    ),
                ),
        )

    fun inlineVenueOwnerStaffRoleInfoActions(
        venueId: Long,
        roleKey: String,
    ): InlineKeyboardMarkup {
        val roleTitle = venueOwnerStaffRoleTitle(roleKey)
        return InlineKeyboardMarkup(
            inlineKeyboard =
                listOf(
                    listOf(
                        InlineKeyboardButton(
                            text = "✅ Выбрать роль $roleTitle",
                            callbackData = "owner_venue_staff_role_select:$venueId:$roleKey",
                        ),
                    ),
                    listOf(
                        InlineKeyboardButton(
                            text = "⬅️ К выбору роли",
                            callbackData = "owner_venue_staff_add:$venueId",
                        ),
                    ),
                    listOf(
                        InlineKeyboardButton(
                            text = "⬅️ К персоналу",
                            callbackData = "owner_venue_staff_root:$venueId",
                        ),
                    ),
                ),
        )
    }

    fun inlineVenueOwnerStaffRoleSelectedActions(
        venueId: Long,
        roleKey: String,
    ): InlineKeyboardMarkup {
        val roleTitle = venueOwnerStaffRoleTitle(roleKey)
        return InlineKeyboardMarkup(
            inlineKeyboard =
                listOf(
                    listOf(
                        InlineKeyboardButton(
                            text = "ℹ️ О роли $roleTitle",
                            callbackData = "owner_venue_staff_role_info:$venueId:$roleKey",
                        ),
                    ),
                    listOf(
                        InlineKeyboardButton(
                            text = "⬅️ К выбору роли",
                            callbackData = "owner_venue_staff_add:$venueId",
                        ),
                    ),
                    listOf(
                        InlineKeyboardButton(
                            text = "⬅️ К персоналу",
                            callbackData = "owner_venue_staff_root:$venueId",
                        ),
                    ),
                ),
        )
    }

    fun inlineVenueOwnerStaffInviteCreatedActions(
        venueId: Long,
        roleKey: String,
    ): InlineKeyboardMarkup =
        InlineKeyboardMarkup(
            inlineKeyboard =
                listOf(
                    listOf(
                        InlineKeyboardButton(
                            text = "🔄 Пересоздать приглашение",
                            callbackData = "owner_venue_staff_role_select:$venueId:$roleKey",
                        ),
                    ),
                    listOf(
                        InlineKeyboardButton(
                            text = "⬅️ К персоналу",
                            callbackData = "owner_venue_staff_root:$venueId",
                        ),
                    ),
                ),
        )

    fun inlineStaffInviteDecisionActions(code: String): InlineKeyboardMarkup =
        InlineKeyboardMarkup(
            inlineKeyboard =
                listOf(
                    listOf(
                        InlineKeyboardButton(
                            text = "✅ Принять приглашение",
                            callbackData = "staff_invite_accept:$code",
                        ),
                        InlineKeyboardButton(
                            text = "❌ Отклонить",
                            callbackData = "staff_invite_decline:$code",
                        ),
                    ),
                ),
        )

    fun inlineVenueOwnerTablesListActions(
        venueId: Long,
        tableButtons: List<Pair<Long, String>>,
    ): InlineKeyboardMarkup =
        InlineKeyboardMarkup(
            inlineKeyboard =
                buildList {
                    tableButtons.forEach { (tableId, label) ->
                        add(
                            listOf(
                                InlineKeyboardButton(
                                    text = label,
                                    callbackData = "owner_venue_tables_table:$venueId:$tableId",
                                ),
                            ),
                        )
                    }
                    add(
                        listOf(
                            InlineKeyboardButton(
                                text = "➕ Добавить стол",
                                callbackData = "owner_venue_tables_add:$venueId",
                            ),
                        ),
                    )
                    add(
                        listOf(
                            InlineKeyboardButton(
                                text = "⬅️ К столам и QR",
                                callbackData = "owner_venue_tables_root:$venueId",
                            ),
                        ),
                    )
                },
        )

    private fun venueOwnerStaffRoleTitle(roleKey: String): String =
        when (roleKey.lowercase()) {
            "owner" -> "Owner"
            "manager" -> "Manager"
            "staff" -> "Staff / Оператор смены"
            else -> roleKey
        }

    fun inlineVenueOwnerTableActions(
        venueId: Long,
        tableId: Long,
    ): InlineKeyboardMarkup =
        InlineKeyboardMarkup(
            inlineKeyboard =
                listOf(
                    listOf(
                        InlineKeyboardButton(
                            text = "✏️ Изменить номер",
                            callbackData = "owner_venue_tables_table_rename:$venueId:$tableId",
                        ),
                    ),
                    listOf(
                        InlineKeyboardButton(
                            text = "👥 Изменить вместимость",
                            callbackData = "owner_venue_tables_table_capacity:$venueId:$tableId",
                        ),
                    ),
                    listOf(
                        InlineKeyboardButton(
                            text = "🧾 Показать QR",
                            callbackData = "owner_venue_tables_qr_table:$venueId:$tableId",
                        ),
                    ),
                    listOf(
                        InlineKeyboardButton(
                            text = "🔁 Перевыпустить QR",
                            callbackData = "owner_venue_tables_qr_rotate:$venueId:$tableId",
                        ),
                    ),
                    listOf(
                        InlineKeyboardButton(
                            text = "⬅️ К списку столов",
                            callbackData = "owner_venue_tables_list:$venueId",
                        ),
                    ),
                ),
        )

    fun inlineVenueOwnerTableQrFallbackActions(
        venueId: Long,
        tableId: Long,
    ): InlineKeyboardMarkup =
        InlineKeyboardMarkup(
            inlineKeyboard =
                listOf(
                    listOf(
                        InlineKeyboardButton(
                            text = "🧾 Открыть генератор QR",
                            url = "https://www.qrcode-monkey.com/",
                        ),
                    ),
                    listOf(
                        InlineKeyboardButton(
                            text = "⬅️ К столу",
                            callbackData = "owner_venue_tables_table:$venueId:$tableId",
                        ),
                    ),
                ),
        )

    fun inlineVenueOwnerTablesQrActions(
        venueId: Long,
        tableButtons: List<Pair<Long, String>>,
    ): InlineKeyboardMarkup =
        InlineKeyboardMarkup(
            inlineKeyboard =
                buildList {
                    tableButtons.forEach { (tableId, label) ->
                        add(
                            listOf(
                                InlineKeyboardButton(
                                    text = label,
                                    callbackData = "owner_venue_tables_qr_table:$venueId:$tableId",
                                ),
                            ),
                        )
                    }
                    add(
                        listOf(
                            InlineKeyboardButton(
                                text = "⬅️ К столам и QR",
                                callbackData = "owner_venue_tables_root:$venueId",
                            ),
                        ),
                    )
                },
        )

    fun inlineVenueOwnerOrderMenuSectionActions(
        venueId: Long,
        sectionId: Long,
        itemButtons: List<Pair<Long, String>> = emptyList(),
    ): InlineKeyboardMarkup =
        InlineKeyboardMarkup(
            inlineKeyboard =
                buildList {
                    itemButtons.forEach { (itemId, label) ->
                        add(
                            listOf(
                                InlineKeyboardButton(
                                    text = label,
                                    callbackData = "owner_venue_order_menu_item:$venueId:$sectionId:$itemId",
                                ),
                            ),
                        )
                    }
                    add(
                        listOf(
                            InlineKeyboardButton(
                                text = "➕ Добавить позицию",
                                callbackData = "owner_venue_order_menu_item_add:$venueId:$sectionId",
                            ),
                        ),
                    )
                    add(
                        listOf(
                            InlineKeyboardButton(
                                text = "✏️ Переименовать раздел",
                                callbackData = "owner_venue_order_menu_section_rename:$venueId:$sectionId",
                            ),
                        ),
                    )
                    add(
                        listOf(
                            InlineKeyboardButton(
                                text = "🗑 Удалить раздел",
                                callbackData = "owner_venue_order_menu_section_delete:$venueId:$sectionId",
                            ),
                        ),
                    )
                    add(
                        listOf(
                            InlineKeyboardButton(
                                text = "⬅️ Назад к меню",
                                callbackData = "owner_venue_order_menu_root:$venueId",
                            ),
                        ),
                    )
                },
        )

    fun inlineVenueOwnerOrderMenuItemActions(
        venueId: Long,
        sectionId: Long,
        itemId: Long,
        isAvailable: Boolean,
        showFlavorProfile: Boolean = false,
    ): InlineKeyboardMarkup =
        InlineKeyboardMarkup(
            inlineKeyboard =
                buildList {
                    add(
                        listOf(
                            InlineKeyboardButton(
                                text = "✏️ Изменить название",
                                callbackData = "owner_venue_order_menu_item_rename:$venueId:$sectionId:$itemId",
                            ),
                        ),
                    )
                    add(
                        listOf(
                            InlineKeyboardButton(
                                text = "💰 Изменить цену",
                                callbackData = "owner_venue_order_menu_item_price:$venueId:$sectionId:$itemId",
                            ),
                        ),
                    )
                    if (showFlavorProfile) {
                        add(
                            listOf(
                                InlineKeyboardButton(
                                    text = "🍓 Настроить вкусы",
                                    callbackData = "owner_venue_order_menu_item_flavors:$venueId:$sectionId:$itemId",
                                ),
                            ),
                        )
                    }
                    add(
                        listOf(
                            InlineKeyboardButton(
                                text = if (isAvailable) "🚫 В стоп-лист" else "✅ Убрать из стоп-листа",
                                callbackData =
                                    if (isAvailable) {
                                        "owner_venue_order_menu_item_stop:$venueId:$sectionId:$itemId"
                                    } else {
                                        "owner_venue_order_menu_item_unstop:$venueId:$sectionId:$itemId"
                                    },
                            ),
                        ),
                    )
                    add(
                        listOf(
                            InlineKeyboardButton(
                                text = "🗑 Удалить позицию",
                                callbackData = "owner_venue_order_menu_item_delete:$venueId:$sectionId:$itemId",
                            ),
                        ),
                    )
                    add(
                        listOf(
                            InlineKeyboardButton(
                                text = "⬅️ Назад к разделу",
                                callbackData = "owner_venue_order_menu_section:$venueId:$sectionId",
                            ),
                        ),
                    )
                },
        )

    fun inlineVenueOwnerOrderMenuFlavorListActions(
        venueId: Long,
        sectionId: Long,
        itemId: Long,
        flavorButtons: List<Pair<Long, String>>,
    ): InlineKeyboardMarkup =
        InlineKeyboardMarkup(
            inlineKeyboard =
                buildList {
                    flavorButtons.forEach { (optionId, label) ->
                        add(
                            listOf(
                                InlineKeyboardButton(
                                    text = label,
                                    callbackData = "owner_venue_order_menu_item_option:$venueId:$sectionId:$itemId:$optionId",
                                ),
                            ),
                        )
                    }
                    add(
                        listOf(
                            InlineKeyboardButton(
                                text = "➕ Добавить вкус",
                                callbackData = "owner_venue_order_menu_item_option_add:$venueId:$sectionId:$itemId",
                            ),
                        ),
                    )
                    add(
                        listOf(
                            InlineKeyboardButton(
                                text = "⬅️ Назад к позиции",
                                callbackData = "owner_venue_order_menu_item:$venueId:$sectionId:$itemId",
                            ),
                        ),
                    )
                },
        )

    fun inlineVenueOwnerOrderMenuFlavorOptionActions(
        venueId: Long,
        sectionId: Long,
        itemId: Long,
        optionId: Long,
        isAvailable: Boolean,
    ): InlineKeyboardMarkup =
        InlineKeyboardMarkup(
            inlineKeyboard =
                listOf(
                    listOf(
                        InlineKeyboardButton(
                            text = "✏️ Переименовать",
                            callbackData = "owner_venue_order_menu_item_option_rename:$venueId:$sectionId:$itemId:$optionId",
                        ),
                    ),
                    listOf(
                        InlineKeyboardButton(
                            text = if (isAvailable) "🚫 В стоп-лист" else "✅ Убрать из стоп-листа",
                            callbackData =
                                if (isAvailable) {
                                    "owner_venue_order_menu_item_option_stop:$venueId:$sectionId:$itemId:$optionId"
                                } else {
                                    "owner_venue_order_menu_item_option_unstop:$venueId:$sectionId:$itemId:$optionId"
                                },
                        ),
                    ),
                    listOf(
                        InlineKeyboardButton(
                            text = "🗑 Удалить",
                            callbackData = "owner_venue_order_menu_item_option_delete:$venueId:$sectionId:$itemId:$optionId",
                        ),
                    ),
                    listOf(
                        InlineKeyboardButton(
                            text = "⬅️ Назад к вкусам",
                            callbackData = "owner_venue_order_menu_item_flavors:$venueId:$sectionId:$itemId",
                        ),
                    ),
                ),
        )

    fun inlineVenueOwnerOrderMenuStopListActions(
        venueId: Long,
        stopListButtons: List<Pair<String, String>>,
    ): InlineKeyboardMarkup =
        InlineKeyboardMarkup(
            inlineKeyboard =
                buildList {
                    stopListButtons.forEach { (label, callbackData) ->
                        add(
                            listOf(
                                InlineKeyboardButton(
                                    text = label,
                                    callbackData = callbackData,
                                ),
                            ),
                        )
                    }
                    add(
                        listOf(
                            InlineKeyboardButton(
                                text = "⬅️ Назад к меню",
                                callbackData = "owner_venue_order_menu_root:$venueId",
                            ),
                        ),
                    )
                },
        )

    fun inlineVenueStaffOrdersRootActions(
        venueId: Long,
        orderButtons: List<Pair<Long, String>>,
    ): InlineKeyboardMarkup =
        InlineKeyboardMarkup(
            inlineKeyboard =
                buildList {
                    orderButtons.forEach { (orderId, label) ->
                        add(
                            listOf(
                                InlineKeyboardButton(
                                    text = label,
                                    callbackData = "staff_venue_orders_order:$venueId:$orderId",
                                ),
                            ),
                        )
                    }
                    add(
                        listOf(
                            InlineKeyboardButton(
                                text = "🔄 Обновить",
                                callbackData = "staff_venue_orders_root:$venueId",
                            ),
                        ),
                    )
                },
        )

    fun inlineVenueStaffCallsRootActions(venueId: Long): InlineKeyboardMarkup =
        InlineKeyboardMarkup(
            inlineKeyboard =
                listOf(
                    listOf(
                        InlineKeyboardButton(
                            text = "🔄 Обновить",
                            callbackData = "staff_venue_calls_root:$venueId",
                        ),
                    ),
                ),
        )

    fun inlineVenueStaffOrderActions(
        venueId: Long,
        orderId: Long,
        statusButtons: List<Pair<String, String>>,
        actionButtons: List<Pair<String, String>> = emptyList(),
        backButton: Pair<String, String>? = "⬅️ К заказам" to "staff_venue_orders_root:$venueId",
    ): InlineKeyboardMarkup =
        InlineKeyboardMarkup(
            inlineKeyboard =
                buildList {
                    statusButtons.forEach { (label, nextStatus) ->
                        add(
                            listOf(
                                InlineKeyboardButton(
                                    text = label,
                                    callbackData = "staff_venue_orders_status:$venueId:$orderId:$nextStatus",
                                ),
                            ),
                        )
                    }
                    actionButtons.forEach { (label, callbackData) ->
                        add(
                            listOf(
                                InlineKeyboardButton(
                                    text = label,
                                    callbackData = callbackData,
                                ),
                            ),
                        )
                    }
                    if (backButton != null) {
                        add(
                            listOf(
                                InlineKeyboardButton(
                                    text = backButton.first,
                                    callbackData = backButton.second,
                                ),
                            ),
                        )
                    }
                },
        )

    fun inlineVenueStatsPeriodActions(): InlineKeyboardMarkup =
        InlineKeyboardMarkup(
            inlineKeyboard =
                listOf(
                    listOf(
                        InlineKeyboardButton(
                            text = "Сегодня",
                            callbackData = "stats_period_today",
                        ),
                    ),
                    listOf(
                        InlineKeyboardButton(
                            text = "7 дней",
                            callbackData = "stats_period_7d",
                        ),
                    ),
                    listOf(
                        InlineKeyboardButton(
                            text = "30 дней",
                            callbackData = "stats_period_30d",
                        ),
                    ),
                    listOf(
                        InlineKeyboardButton(
                            text = "⬅️ Назад",
                            callbackData = "staff_venue_menu_back",
                        ),
                    ),
                ),
        )

    fun inlineVenueSettingsRootActions(
        venueId: Long,
        showDevReset: Boolean,
    ): InlineKeyboardMarkup =
        InlineKeyboardMarkup(
            inlineKeyboard =
                buildList {
                    add(
                        listOf(
                            InlineKeyboardButton(
                                text = "🔔 Уведомления",
                                callbackData = "venue_settings_notifications:$venueId",
                            ),
                        ),
                    )
                    add(
                        listOf(
                            InlineKeyboardButton(
                                text = "🕒 Часовой пояс",
                                callbackData = "venue_settings_timezone:$venueId",
                            ),
                        ),
                    )
                    add(
                        listOf(
                            InlineKeyboardButton(
                                text = "🧾 Нумерация заказов",
                                callbackData = "venue_settings_order_numbering:$venueId",
                            ),
                        ),
                    )
                    add(
                        listOf(
                            InlineKeyboardButton(
                                text = "📊 Статистика и отчёты",
                                callbackData = "venue_settings_stats_reports:$venueId",
                            ),
                        ),
                    )
                    if (showDevReset) {
                        add(
                            listOf(
                                InlineKeyboardButton(
                                    text = "🧪 Сброс тестовых данных",
                                    callbackData = "venue_settings_dev_reset:$venueId",
                                ),
                            ),
                        )
                    }
                    add(
                        listOf(
                            InlineKeyboardButton(
                                text = "ℹ️ О настройках",
                                callbackData = "venue_settings_about:$venueId",
                            ),
                        ),
                    )
                    add(
                        listOf(
                            InlineKeyboardButton(
                                text = "⬅️ Назад",
                                callbackData = "staff_venue_menu_back",
                            ),
                        ),
                    )
                },
        )

    fun inlineVenueSettingsBackActions(venueId: Long): InlineKeyboardMarkup =
        InlineKeyboardMarkup(
            inlineKeyboard =
                listOf(
                    listOf(
                        InlineKeyboardButton(
                            text = "⬅️ К настройкам",
                            callbackData = "venue_settings_root:$venueId",
                        ),
                    ),
                ),
        )

    fun inlineVenueStaffStopListRootActions(
        venueId: Long,
        stopListButtons: List<Pair<String, String>>,
    ): InlineKeyboardMarkup =
        InlineKeyboardMarkup(
            inlineKeyboard =
                buildList {
                    stopListButtons.forEach { (label, callbackData) ->
                        add(
                            listOf(
                                InlineKeyboardButton(
                                    text = label,
                                    callbackData = callbackData,
                                ),
                            ),
                        )
                    }
                    add(
                        listOf(
                            InlineKeyboardButton(
                                text = "➕ Добавить через меню",
                                callbackData = "staff_venue_stoplist_add:$venueId",
                            ),
                        ),
                    )
                    add(
                        listOf(
                            InlineKeyboardButton(
                                text = "⬅️ К меню смены",
                                callbackData = "staff_venue_menu_back",
                            ),
                        ),
                    )
                },
        )

    fun inlineVenueStaffStopListSectionsActions(
        venueId: Long,
        sectionButtons: List<Pair<Long, String>>,
    ): InlineKeyboardMarkup =
        InlineKeyboardMarkup(
            inlineKeyboard =
                buildList {
                    sectionButtons.forEach { (sectionId, label) ->
                        add(
                            listOf(
                                InlineKeyboardButton(
                                    text = label,
                                    callbackData = "staff_venue_stoplist_section:$venueId:$sectionId",
                                ),
                            ),
                        )
                    }
                    add(
                        listOf(
                            InlineKeyboardButton(
                                text = "⬅️ К стоп-листу",
                                callbackData = "staff_venue_stoplist_root:$venueId",
                            ),
                        ),
                    )
                },
        )

    fun inlineVenueStaffStopListSectionActions(
        venueId: Long,
        sectionId: Long,
        itemButtons: List<Pair<Long, String>>,
    ): InlineKeyboardMarkup =
        InlineKeyboardMarkup(
            inlineKeyboard =
                buildList {
                    itemButtons.forEach { (itemId, label) ->
                        add(
                            listOf(
                                InlineKeyboardButton(
                                    text = label,
                                    callbackData = "staff_venue_stoplist_item:$venueId:$sectionId:$itemId",
                                ),
                            ),
                        )
                    }
                    add(
                        listOf(
                            InlineKeyboardButton(
                                text = "⬅️ К разделам",
                                callbackData = "staff_venue_stoplist_add:$venueId",
                            ),
                        ),
                    )
                    add(
                        listOf(
                            InlineKeyboardButton(
                                text = "⬅️ К стоп-листу",
                                callbackData = "staff_venue_stoplist_root:$venueId",
                            ),
                        ),
                    )
                },
        )

    fun inlineVenueStaffStopListItemActions(
        venueId: Long,
        sectionId: Long,
        itemId: Long,
        isAvailable: Boolean,
        showFlavorProfile: Boolean = false,
    ): InlineKeyboardMarkup =
        InlineKeyboardMarkup(
            inlineKeyboard =
                buildList {
                    if (showFlavorProfile) {
                        add(
                            listOf(
                                InlineKeyboardButton(
                                    text = "🍓 Настроить вкусы",
                                    callbackData = "staff_venue_stoplist_item_flavors:$venueId:$sectionId:$itemId",
                                ),
                            ),
                        )
                    }
                    add(
                        listOf(
                            InlineKeyboardButton(
                                text = if (isAvailable) "🚫 В стоп-лист" else "✅ Убрать из стоп-листа",
                                callbackData =
                                    if (isAvailable) {
                                        "staff_venue_stoplist_item_stop:$venueId:$sectionId:$itemId"
                                    } else {
                                        "staff_venue_stoplist_item_unstop:$venueId:$sectionId:$itemId"
                                    },
                            ),
                        ),
                    )
                    add(
                        listOf(
                            InlineKeyboardButton(
                                text = "⬅️ К разделу",
                                callbackData = "staff_venue_stoplist_section:$venueId:$sectionId",
                            ),
                        ),
                    )
                },
        )

    fun inlineVenueStaffStopListFlavorListActions(
        venueId: Long,
        sectionId: Long,
        itemId: Long,
        flavorButtons: List<Pair<Long, String>>,
    ): InlineKeyboardMarkup =
        InlineKeyboardMarkup(
            inlineKeyboard =
                buildList {
                    flavorButtons.forEach { (optionId, label) ->
                        add(
                            listOf(
                                InlineKeyboardButton(
                                    text = label,
                                    callbackData = "staff_venue_stoplist_option:$venueId:$sectionId:$itemId:$optionId",
                                ),
                            ),
                        )
                    }
                    add(
                        listOf(
                            InlineKeyboardButton(
                                text = "⬅️ К позиции",
                                callbackData = "staff_venue_stoplist_item:$venueId:$sectionId:$itemId",
                            ),
                        ),
                    )
                },
        )

    fun inlineVenueStaffStopListFlavorOptionActions(
        venueId: Long,
        sectionId: Long,
        itemId: Long,
        optionId: Long,
        isAvailable: Boolean,
    ): InlineKeyboardMarkup =
        InlineKeyboardMarkup(
            inlineKeyboard =
                listOf(
                    listOf(
                        InlineKeyboardButton(
                            text = if (isAvailable) "🚫 В стоп-лист" else "✅ Убрать из стоп-листа",
                            callbackData =
                                if (isAvailable) {
                                    "staff_venue_stoplist_option_stop:$venueId:$sectionId:$itemId:$optionId"
                                } else {
                                    "staff_venue_stoplist_option_unstop:$venueId:$sectionId:$itemId:$optionId"
                                },
                        ),
                    ),
                    listOf(
                        InlineKeyboardButton(
                            text = "⬅️ К вкусам",
                            callbackData = "staff_venue_stoplist_item_flavors:$venueId:$sectionId:$itemId",
                        ),
                    ),
                ),
        )

    fun inlineVenueOwnerOrderMenuBackToSection(
        venueId: Long,
        sectionId: Long,
    ): InlineKeyboardMarkup =
        InlineKeyboardMarkup(
            inlineKeyboard =
                listOf(
                    listOf(
                        InlineKeyboardButton(
                            text = "⬅️ Назад к разделу",
                            callbackData = "owner_venue_order_menu_section:$venueId:$sectionId",
                        ),
                    ),
                    listOf(
                        InlineKeyboardButton(
                            text = "⬅️ Назад к меню",
                            callbackData = "owner_venue_order_menu_root:$venueId",
                        ),
                    ),
                ),
        )

    fun inlineVenueOwnerOrderMenuBackToRoot(venueId: Long): InlineKeyboardMarkup =
        InlineKeyboardMarkup(
            inlineKeyboard =
                listOf(
                    listOf(
                        InlineKeyboardButton(
                            text = "⬅️ Назад к меню",
                            callbackData = "owner_venue_order_menu_root:$venueId",
                        ),
                    ),
                ),
        )

    fun inlineOwnerVenueDescriptionSections(
        venueId: Long,
        sectionButtons: List<Pair<Long, String>>,
    ): InlineKeyboardMarkup =
        InlineKeyboardMarkup(
            inlineKeyboard =
                buildList {
                    sectionButtons.forEach { (sectionId, label) ->
                        add(
                            listOf(
                                InlineKeyboardButton(
                                    text = label,
                                    callbackData = "owner_venue_description_section:$venueId:$sectionId",
                                ),
                            ),
                        )
                    }
                    add(
                        listOf(
                            InlineKeyboardButton(
                                text = "➕ Добавить свой раздел",
                                callbackData = "owner_venue_description_add:$venueId",
                            ),
                        ),
                    )
                    add(
                        listOf(
                            InlineKeyboardButton(
                                text = "⬅️ К заведению",
                                callbackData = "owner_venue_description_back:$venueId",
                            ),
                        ),
                    )
                },
        )

    fun inlineOwnerVenueDescriptionSectionActions(
        venueId: Long,
        sectionId: Long,
        isVisible: Boolean,
        hasText: Boolean = false,
        mediaDeleteButtons: List<Pair<Long, String>> = emptyList(),
    ): InlineKeyboardMarkup =
        InlineKeyboardMarkup(
            inlineKeyboard =
                buildList {
                    add(
                        listOf(
                            InlineKeyboardButton(
                                text = if (hasText) "✏️ Изменить текст" else "✏️ Добавить текст",
                                callbackData = "owner_venue_description_text:$venueId:$sectionId",
                            ),
                            InlineKeyboardButton(
                                text = "🖼 Добавить медиа",
                                callbackData = "owner_venue_description_media:$venueId:$sectionId",
                            ),
                        ),
                    )
                    mediaDeleteButtons.forEach { (mediaId, label) ->
                        val normalizedDeleteLabel =
                            when {
                                label.startsWith("Изображение ") -> "изображение ${label.removePrefix("Изображение ")}"
                                label.startsWith("Файл ") -> "файл ${label.removePrefix("Файл ")}"
                                else -> label
                            }
                        add(
                            listOf(
                                InlineKeyboardButton(
                                    text = "🗑 Удалить $normalizedDeleteLabel",
                                    callbackData = "owner_venue_description_media_delete:$venueId:$sectionId:$mediaId",
                                ),
                            ),
                        )
                    }
                    add(
                        listOf(
                            InlineKeyboardButton(
                                text = if (isVisible) "🙈 Скрыть раздел" else "👁 Показать раздел",
                                callbackData = "owner_venue_description_visibility:$venueId:$sectionId",
                            ),
                        ),
                    )
                    add(
                        listOf(
                            InlineKeyboardButton(
                                text = "⬅️ К разделам",
                                callbackData = "owner_venue_description_open:$venueId",
                            ),
                        ),
                    )
                },
        )

    fun inlineOwnerVenueHoursMain(
        venueId: Long,
        dayButtons: List<Pair<Int, String>>,
    ): InlineKeyboardMarkup =
        InlineKeyboardMarkup(
            inlineKeyboard =
                buildList {
                    dayButtons.forEach { (weekday, label) ->
                        add(
                            listOf(
                                InlineKeyboardButton(
                                    text = label,
                                    callbackData = "owner_venue_hours_day:$venueId:$weekday",
                                ),
                            ),
                        )
                    }
                    add(
                        listOf(
                            InlineKeyboardButton(
                                text = "📅 Исключения",
                                callbackData = "owner_venue_hours_overrides:$venueId",
                            ),
                        ),
                    )
                    add(
                        listOf(
                            InlineKeyboardButton(
                                text = "⬅️ Назад",
                                callbackData = "owner_venue_hours_back:$venueId",
                            ),
                        ),
                    )
                },
        )

    fun inlineOwnerVenueHoursDayActions(
        venueId: Long,
        weekday: Int,
    ): InlineKeyboardMarkup =
        InlineKeyboardMarkup(
            inlineKeyboard =
                listOf(
                    listOf(
                        InlineKeyboardButton(
                            text = "🕒 Изменить часы",
                            callbackData = "owner_venue_hours_day_edit:$venueId:$weekday",
                        ),
                    ),
                    listOf(
                        InlineKeyboardButton(
                            text = "🚫 Закрыть день",
                            callbackData = "owner_venue_hours_day_close:$venueId:$weekday",
                        ),
                        InlineKeyboardButton(
                            text = "✅ Открыть день",
                            callbackData = "owner_venue_hours_day_open:$venueId:$weekday",
                        ),
                    ),
                    listOf(
                        InlineKeyboardButton(
                            text = "⬅️ Назад",
                            callbackData = "owner_venue_hours_open:$venueId",
                        ),
                    ),
                ),
        )

    fun inlineOwnerVenueHoursOverrides(
        venueId: Long,
        overrideButtons: List<Pair<String, String>>,
    ): InlineKeyboardMarkup =
        InlineKeyboardMarkup(
            inlineKeyboard =
                buildList {
                    overrideButtons.forEach { (label, dateIso) ->
                        add(
                            listOf(
                                InlineKeyboardButton(
                                    text = label,
                                    callbackData = "owner_venue_hours_override_edit:$venueId:$dateIso",
                                ),
                            ),
                        )
                    }
                    add(
                        listOf(
                            InlineKeyboardButton(
                                text = "➕ Добавить исключение",
                                callbackData = "owner_venue_hours_override_add:$venueId",
                            ),
                        ),
                    )
                    add(
                        listOf(
                            InlineKeyboardButton(
                                text = "⬅️ Назад",
                                callbackData = "owner_venue_hours_open:$venueId",
                            ),
                        ),
                    )
                },
        )

    fun inlineOwnerVenueHoursOverrideMode(
        venueId: Long,
        dateIso: String,
    ): InlineKeyboardMarkup =
        InlineKeyboardMarkup(
            inlineKeyboard =
                listOf(
                    listOf(
                        InlineKeyboardButton(
                            text = "✅ Открыт",
                            callbackData = "owner_venue_hours_override_mode:$venueId:$dateIso:open",
                        ),
                        InlineKeyboardButton(
                            text = "🚫 Закрыт",
                            callbackData = "owner_venue_hours_override_mode:$venueId:$dateIso:closed",
                        ),
                    ),
                    listOf(
                        InlineKeyboardButton(
                            text = "⬅️ К исключениям",
                            callbackData = "owner_venue_hours_overrides:$venueId",
                        ),
                    ),
                ),
        )

    fun inlineOwnerVenueHoursOverrideEditActions(
        venueId: Long,
        dateIso: String,
    ): InlineKeyboardMarkup =
        InlineKeyboardMarkup(
            inlineKeyboard =
                listOf(
                    listOf(
                        InlineKeyboardButton(
                            text = "🕒 Изменить часы",
                            callbackData = "owner_venue_hours_override_mode:$venueId:$dateIso:open",
                        ),
                    ),
                    listOf(
                        InlineKeyboardButton(
                            text = "🚫 Закрыть день",
                            callbackData = "owner_venue_hours_override_mode:$venueId:$dateIso:closed",
                        ),
                        InlineKeyboardButton(
                            text = "❌ Удалить",
                            callbackData = "owner_venue_hours_override_delete:$venueId:$dateIso",
                        ),
                    ),
                    listOf(
                        InlineKeyboardButton(
                            text = "⬅️ К исключениям",
                            callbackData = "owner_venue_hours_overrides:$venueId",
                        ),
                    ),
                ),
        )

    fun inlineVenueOwnerPublishReadinessActions(venueId: Long): InlineKeyboardMarkup =
        InlineKeyboardMarkup(
            inlineKeyboard =
                listOf(
                    listOf(
                        InlineKeyboardButton(
                            text = "👁 Предпросмотр",
                            callbackData = "owner_venue_publish_preview:$venueId",
                        ),
                    ),
                    listOf(
                        InlineKeyboardButton(
                            text = "✅ Опубликовать кальянную",
                            callbackData = "owner_venue_publish_confirm:$venueId",
                        ),
                    ),
                ),
        )

    fun inlineBotMyBookingActions(
        bookingId: Long,
        venueId: Long,
    ): InlineKeyboardMarkup =
        InlineKeyboardMarkup(
            inlineKeyboard =
                listOf(
                    listOf(
                        InlineKeyboardButton(
                            text = "✏️ Изменить",
                            callbackData = "bot_my_booking_edit:$bookingId:$venueId",
                        ),
                        InlineKeyboardButton(
                            text = "❌ Отменить",
                            callbackData = "bot_my_booking_cancel:$bookingId:$venueId",
                        ),
                    ),
                ),
        )

    fun inlineBotMenuCategories(categories: List<Pair<Long, String>>): InlineKeyboardMarkup =
        InlineKeyboardMarkup(
            inlineKeyboard =
                categories.map { (id, name) ->
                    listOf(
                        InlineKeyboardButton(
                            text = name,
                            callbackData = "bot_menu_category:$id",
                        ),
                    )
                },
        )

    fun inlineBotMenuItems(
        categoryId: Long,
        items: List<Pair<Long, String>>,
        page: Int,
        totalPages: Int,
    ): InlineKeyboardMarkup =
        InlineKeyboardMarkup(
            inlineKeyboard =
                buildList {
                    items.forEach { (itemId, label) ->
                        add(
                            listOf(
                                InlineKeyboardButton(
                                    text = label,
                                    callbackData = "bot_menu_item:$categoryId:$itemId",
                                ),
                            ),
                        )
                    }
                    if (totalPages > 1) {
                        val navRow = mutableListOf<InlineKeyboardButton>()
                        if (page > 0) {
                            navRow.add(
                                InlineKeyboardButton(
                                    text = "◀️",
                                    callbackData = "bot_menu_category_page:$categoryId:${page - 1}",
                                ),
                            )
                        }
                        if (page < totalPages - 1) {
                            navRow.add(
                                InlineKeyboardButton(
                                    text = "▶️",
                                    callbackData = "bot_menu_category_page:$categoryId:${page + 1}",
                                ),
                            )
                        }
                        if (navRow.isNotEmpty()) {
                            add(navRow)
                        }
                    }
                    add(
                        listOf(
                            InlineKeyboardButton(
                                text = "⬅️ К категориям",
                                callbackData = "bot_menu_back_categories",
                            ),
                        ),
                    )
                },
        )

    fun inlineBotMenuItemNavigation(
        categoryId: Long,
        itemId: Long,
        hasActiveOrder: Boolean,
    ): InlineKeyboardMarkup =
        InlineKeyboardMarkup(
            inlineKeyboard =
                listOf(
                    listOf(
                        InlineKeyboardButton(
                            text = if (hasActiveOrder) "➕ Дозаказать" else "➕ Добавить в корзину",
                            callbackData =
                                if (hasActiveOrder) {
                                    "bot_menu_item_reorder:$categoryId:$itemId"
                                } else {
                                    "bot_menu_item_add_to_cart:$categoryId:$itemId"
                                },
                        ),
                    ),
                    listOf(
                        InlineKeyboardButton(
                            text = "🧺 Корзина",
                            callbackData = "bot_menu_item_cart",
                        ),
                    ),
                    listOf(
                        InlineKeyboardButton(
                            text = "🔔 Вызвать персонал",
                            callbackData = "bot_menu_item_staff",
                        ),
                    ),
                    listOf(
                        InlineKeyboardButton(
                            text = "⬅️ К позициям категории",
                            callbackData = "bot_menu_back_category:$categoryId",
                        ),
                    ),
                    listOf(
                        InlineKeyboardButton(
                            text = "⬅️ К категориям",
                            callbackData = "bot_menu_back_categories",
                        ),
                    ),
                ),
        )

    fun inlineBotMenuItemFlavorOptions(
        categoryId: Long,
        itemId: Long,
        options: List<Pair<Long, String>>,
    ): InlineKeyboardMarkup =
        InlineKeyboardMarkup(
            inlineKeyboard =
                buildList {
                    options.forEach { (optionId, optionName) ->
                        add(
                            listOf(
                                InlineKeyboardButton(
                                    text = optionName,
                                    callbackData = "bot_menu_item_option_add_to_cart:$categoryId:$itemId:$optionId",
                                ),
                            ),
                        )
                    }
                    add(
                        listOf(
                            InlineKeyboardButton(
                                text = "🧺 Корзина",
                                callbackData = "bot_menu_item_cart",
                            ),
                        ),
                    )
                    add(
                        listOf(
                            InlineKeyboardButton(
                                text = "⬅️ К позициям категории",
                                callbackData = "bot_menu_back_category:$categoryId",
                            ),
                        ),
                    )
                },
        )

    fun inlineBotMenuPostAddActions(categoryId: Long): InlineKeyboardMarkup =
        InlineKeyboardMarkup(
            inlineKeyboard =
                listOf(
                    listOf(
                        InlineKeyboardButton(
                            text = "🧺 Корзина",
                            callbackData = "bot_menu_item_cart",
                        ),
                    ),
                    listOf(
                        InlineKeyboardButton(
                            text = "⬅️ К позициям категории",
                            callbackData = "bot_menu_back_category:$categoryId",
                        ),
                    ),
                    listOf(
                        InlineKeyboardButton(
                            text = "⬅️ К категориям",
                            callbackData = "bot_menu_back_categories",
                        ),
                    ),
                ),
        )

    fun inlineBotMenuCartItemActions(itemId: Long): InlineKeyboardMarkup =
        InlineKeyboardMarkup(
            inlineKeyboard =
                listOf(
                    listOf(
                        InlineKeyboardButton(
                            text = "➖",
                            callbackData = "bot_menu_cart_dec:$itemId",
                        ),
                        InlineKeyboardButton(
                            text = "➕",
                            callbackData = "bot_menu_cart_inc:$itemId",
                        ),
                        InlineKeyboardButton(
                            text = "🗑️ Удалить",
                            callbackData = "bot_menu_cart_remove:$itemId",
                        ),
                    ),
                ),
        )

    fun inlineBotMenuCartSummaryActions(
        showSplitBillActions: Boolean = false,
        hasComment: Boolean = false,
    ): InlineKeyboardMarkup =
        InlineKeyboardMarkup(
            inlineKeyboard =
                buildList {
                    add(
                        listOf(
                            InlineKeyboardButton(
                                text = if (hasComment) "✏️ Изменить комментарий" else "💬 Добавить комментарий",
                                callbackData = "bot_menu_cart_comment",
                            ),
                        ),
                    )
                    add(
                        listOf(
                            InlineKeyboardButton(
                                text = "✅ Оформить заказ",
                                callbackData = "bot_menu_cart_checkout",
                            ),
                        ),
                    )
                    if (showSplitBillActions) {
                        add(
                            listOf(
                                InlineKeyboardButton(
                                    text = "👥 Создать общий счёт",
                                    callbackData = "bot_tabs_create_shared",
                                ),
                            ),
                        )
                        add(
                            listOf(
                                InlineKeyboardButton(
                                    text = "🔑 Присоединиться к общему счёту",
                                    callbackData = "bot_tabs_join_prompt",
                                ),
                            ),
                        )
                    }
                    add(
                        listOf(
                            InlineKeyboardButton(
                                text = "🗑️ Очистить корзину",
                                callbackData = "bot_menu_cart_clear",
                            ),
                        ),
                    )
                },
        )

    fun inlineBotMenuCartActions(itemRefs: List<Pair<Int, Long>>): InlineKeyboardMarkup =
        InlineKeyboardMarkup(
            inlineKeyboard =
                buildList {
                    itemRefs.forEach { (_, itemId) ->
                        add(
                            listOf(
                                InlineKeyboardButton(
                                    text = "➖",
                                    callbackData = "bot_menu_cart_dec:$itemId",
                                ),
                                InlineKeyboardButton(
                                    text = "➕",
                                    callbackData = "bot_menu_cart_inc:$itemId",
                                ),
                                InlineKeyboardButton(
                                    text = "🗑️ Удалить",
                                    callbackData = "bot_menu_cart_remove:$itemId",
                                ),
                            ),
                        )
                    }
                    addAll(inlineBotMenuCartSummaryActions().inlineKeyboard)
                },
        )

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
                    listOf(InlineKeyboardButton(text = "💬 Консультация", callbackData = "staff_call_reason:COME")),
                    listOf(InlineKeyboardButton(text = "🔥 Заменить угли", callbackData = "staff_call_reason:COALS")),
                    listOf(InlineKeyboardButton(text = "🧾 Принести счёт", callbackData = "staff_call_reason:BILL")),
                ),
        )

    fun inlineStaffCallBillPaymentMethods(): InlineKeyboardMarkup =
        InlineKeyboardMarkup(
            inlineKeyboard =
                listOf(
                    listOf(
                        InlineKeyboardButton(
                            text = "💳 Картой",
                            callbackData = "staff_call_bill_payment:CARD",
                        ),
                    ),
                    listOf(
                        InlineKeyboardButton(
                            text = "💵 Наличными",
                            callbackData = "staff_call_bill_payment:CASH",
                        ),
                    ),
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

    fun inlineBotPostCheckoutActions(): InlineKeyboardMarkup =
        InlineKeyboardMarkup(
            inlineKeyboard =
                listOf(
                    listOf(
                        InlineKeyboardButton(
                            text = "📄 Мой заказ",
                            callbackData = "bot_active_order_view",
                        ),
                    ),
                    listOf(
                        InlineKeyboardButton(
                            text = "➕ Дозаказать",
                            callbackData = "bot_menu_reorder_entry",
                        ),
                    ),
                    listOf(
                        InlineKeyboardButton(
                            text = "🔔 Вызвать персонал",
                            callbackData = "bot_menu_item_staff",
                        ),
                    ),
                ),
        )

    fun inlineBotActiveOrderActions(showSplitBillActions: Boolean = false): InlineKeyboardMarkup =
        InlineKeyboardMarkup(
            inlineKeyboard =
                buildList {
                    add(
                        listOf(
                            InlineKeyboardButton(
                                text = "➕ Дозаказать",
                                callbackData = "bot_menu_reorder_entry",
                            ),
                        ),
                    )
                    if (showSplitBillActions) {
                        add(
                            listOf(
                                InlineKeyboardButton(
                                    text = "👥 Создать общий счёт",
                                    callbackData = "bot_tabs_create_shared",
                                ),
                            ),
                        )
                        add(
                            listOf(
                                InlineKeyboardButton(
                                    text = "🔑 Присоединиться к общему счёту",
                                    callbackData = "bot_tabs_join_prompt",
                                ),
                            ),
                        )
                    }
                    add(
                        listOf(
                            InlineKeyboardButton(
                                text = "🔔 Вызвать персонал",
                                callbackData = "bot_menu_item_staff",
                            ),
                        ),
                    )
                },
        )

    fun inlineBotSharedNextActions(): InlineKeyboardMarkup =
        InlineKeyboardMarkup(
            inlineKeyboard =
                listOf(
                    listOf(
                        InlineKeyboardButton(
                            text = "🍽️ Меню",
                            callbackData = "bot_menu_back_categories",
                        ),
                    ),
                    listOf(
                        InlineKeyboardButton(
                            text = "🧺 Корзина",
                            callbackData = "bot_menu_item_cart",
                        ),
                    ),
                    listOf(
                        InlineKeyboardButton(
                            text = "📄 Мой заказ",
                            callbackData = "bot_active_order_view",
                        ),
                    ),
                ),
        )

    fun inlineBotContinueInBotActions(): InlineKeyboardMarkup =
        InlineKeyboardMarkup(
            inlineKeyboard =
                listOf(
                    listOf(
                        InlineKeyboardButton(
                            text = "🍽️ Меню",
                            callbackData = "bot_menu_back_categories",
                        ),
                    ),
                    listOf(
                        InlineKeyboardButton(
                            text = "🧺 Корзина",
                            callbackData = "bot_menu_item_cart",
                        ),
                    ),
                    listOf(
                        InlineKeyboardButton(
                            text = "👥 Общий счёт",
                            callbackData = "bot_open_split_bill_entry",
                        ),
                    ),
                    listOf(
                        InlineKeyboardButton(
                            text = "📄 Мой заказ",
                            callbackData = "bot_active_order_view",
                        ),
                    ),
                ),
        )

    fun inlineBotSplitBillEntryActions(): InlineKeyboardMarkup =
        InlineKeyboardMarkup(
            inlineKeyboard =
                listOf(
                    listOf(
                        InlineKeyboardButton(
                            text = "👥 Создать общий счёт",
                            callbackData = "bot_tabs_create_shared",
                        ),
                    ),
                    listOf(
                        InlineKeyboardButton(
                            text = "🔑 Присоединиться к общему счёту",
                            callbackData = "bot_tabs_join_prompt",
                        ),
                    ),
                ),
        )

    fun inlineBotReorderAction(): InlineKeyboardMarkup =
        InlineKeyboardMarkup(
            inlineKeyboard =
                listOf(
                    listOf(
                        InlineKeyboardButton(
                            text = "➕ Дозаказать",
                            callbackData = "bot_menu_reorder_entry",
                        ),
                    ),
                ),
        )

    fun inlineVenueSettingsNotificationActions(venueId: Long): InlineKeyboardMarkup =
        InlineKeyboardMarkup(
            inlineKeyboard =
                listOf(
                    listOf(
                        InlineKeyboardButton(
                            text = "🔄 Переключить заказы",
                            callbackData = "venue_settings_toggle_orders:$venueId",
                        ),
                    ),
                    listOf(
                        InlineKeyboardButton(
                            text = "🔄 Переключить вызовы",
                            callbackData = "venue_settings_toggle_staff_calls:$venueId",
                        ),
                    ),
                    listOf(
                        InlineKeyboardButton(
                            text = "🔄 Переключить отмены",
                            callbackData = "venue_settings_toggle_cancellations:$venueId",
                        ),
                    ),
                    listOf(
                        InlineKeyboardButton(
                            text = "⬅️ К настройкам",
                            callbackData = "venue_settings_root:$venueId",
                        ),
                    ),
                ),
        )

    fun inlineVenueSettingsDevResetActions(venueId: Long): InlineKeyboardMarkup =
        InlineKeyboardMarkup(
            inlineKeyboard =
                listOf(
                    listOf(
                        InlineKeyboardButton(
                            text = "🧪 Очистить тестовые заказы",
                            callbackData = "venue_settings_dev_reset_confirm:$venueId",
                        ),
                    ),
                    listOf(
                        InlineKeyboardButton(
                            text = "⬅️ К настройкам",
                            callbackData = "venue_settings_root:$venueId",
                        ),
                    ),
                ),
        )

    fun inlineVenueSettingsDevResetConfirmActions(venueId: Long): InlineKeyboardMarkup =
        InlineKeyboardMarkup(
            inlineKeyboard =
                listOf(
                    listOf(
                        InlineKeyboardButton(
                            text = "✅ Да, очистить",
                            callbackData = "venue_settings_dev_reset_execute:$venueId",
                        ),
                    ),
                    listOf(
                        InlineKeyboardButton(
                            text = "⬅️ Отмена",
                            callbackData = "venue_settings_dev_reset:$venueId",
                        ),
                    ),
                ),
        )

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
                            text = "🍽️ Меню",
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
