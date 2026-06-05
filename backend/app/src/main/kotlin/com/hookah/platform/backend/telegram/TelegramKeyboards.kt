package com.hookah.platform.backend.telegram

import com.hookah.platform.backend.miniapp.venue.orders.OrderWorkflowStatus

object TelegramKeyboards {
    fun venueManagerMenu(
        showAiAssistant: Boolean = false,
        showMiniAppEntry: Boolean = false,
        venuePanelUrl: String? = null,
    ): ReplyKeyboardMarkup {
        val keyboard =
            buildList {
                if (showMiniAppEntry && venuePanelUrl != null) {
                    add(
                        listOf(
                            KeyboardButton(
                                text = "📱 Открыть панель заведения",
                            ),
                        ),
                    )
                }
                add(
                    listOf(
                        KeyboardButton(text = "🧾 Заказы"),
                        KeyboardButton(text = "🛎 Вызовы"),
                    ),
                )
                add(
                    listOf(
                        KeyboardButton(text = "🚫 Стоп-лист"),
                        KeyboardButton(text = "📄 Брони"),
                    ),
                )
                add(
                    listOf(
                        KeyboardButton(text = "🍽 Меню"),
                        KeyboardButton(text = "🏢 Заведение"),
                    ),
                )
                add(
                    listOf(
                        KeyboardButton(text = "🪑 Столы и QR"),
                        KeyboardButton(text = "📊 Статистика"),
                    ),
                )
                add(
                    listOf(
                        KeyboardButton(text = "💬 Чат персонала"),
                        KeyboardButton(text = "👥 Персонал"),
                    ),
                )
                add(
                    listOf(
                        KeyboardButton(text = "📣 Продвижение"),
                    ),
                )
                if (showAiAssistant) {
                    add(
                        listOf(
                            KeyboardButton(text = "🤖 Помощник"),
                        ),
                    )
                }
                add(
                    listOf(
                        KeyboardButton(text = "🔄 Сменить заведение"),
                    ),
                )
            }
        return ReplyKeyboardMarkup(keyboard = keyboard, resizeKeyboard = true)
    }

    fun venueStaffMenu(
        showMiniAppEntry: Boolean = false,
        venuePanelUrl: String? = null,
    ): ReplyKeyboardMarkup {
        val keyboard =
            buildList {
                if (showMiniAppEntry && venuePanelUrl != null) {
                    add(
                        listOf(
                            KeyboardButton(
                                text = "📱 Открыть рабочую панель",
                            ),
                        ),
                    )
                }
                add(
                    listOf(
                        KeyboardButton(text = "🧾 Заказы"),
                        KeyboardButton(text = "🛎 Вызовы"),
                    ),
                )
                add(
                    listOf(
                        KeyboardButton(text = "📄 Брони"),
                    ),
                )
                add(
                    listOf(
                        KeyboardButton(text = "🔄 Сменить заведение"),
                    ),
                )
            }
        return ReplyKeyboardMarkup(keyboard = keyboard, resizeKeyboard = true)
    }

    fun venueOwnerMenu(
        showMiniAppEntry: Boolean = false,
        venuePanelUrl: String? = null,
    ): ReplyKeyboardMarkup {
        val keyboard =
            buildList {
                if (showMiniAppEntry && venuePanelUrl != null) {
                    add(
                        listOf(
                            KeyboardButton(
                                text = "📱 Открыть панель заведения",
                            ),
                        ),
                    )
                }
                add(
                    listOf(
                        KeyboardButton(text = "🏢 Мои заведения"),
                        KeyboardButton(text = "📊 Статистика заведений"),
                    ),
                )
                add(
                    listOf(
                        KeyboardButton(text = "🍽 Каталог кальянных"),
                        KeyboardButton(text = "👤 Мой профиль"),
                    ),
                )
            }
        return ReplyKeyboardMarkup(keyboard = keyboard, resizeKeyboard = true)
    }

    fun ownerMainMenu(
        showMiniAppEntry: Boolean = false,
        platformPanelUrl: String? = null,
    ): ReplyKeyboardMarkup {
        val keyboard =
            buildList {
                if (showMiniAppEntry) {
                    add(
                        listOf(
                            KeyboardButton(text = "📱 Панель платформы"),
                        ),
                    )
                }
                add(
                    listOf(
                        KeyboardButton(text = "📨 Заявки на подключение"),
                        KeyboardButton(text = "🏢 Кальянные"),
                    ),
                )
                add(
                    listOf(
                        KeyboardButton(text = "👤 Клиенты / Лимиты"),
                    ),
                )
                add(
                    listOf(
                        KeyboardButton(text = "📣 Продвижение"),
                    ),
                )
            }
        return ReplyKeyboardMarkup(keyboard = keyboard, resizeKeyboard = true)
    }

    fun mainMenu(
        hasVenueRole: Boolean,
        isPlatformOwner: Boolean,
        webAppUrl: String?,
    ): ReplyMarkup {
        val keyboard =
            buildList {
                if (webAppUrl != null) {
                    add(
                        listOf(
                            KeyboardButton(text = "📱 Открыть Mini App"),
                        ),
                    )
                }
                add(
                    listOf(
                        KeyboardButton(text = "🍽 Каталог кальянных"),
                    ),
                )
                add(
                    listOf(
                        KeyboardButton(text = "🎁 Акции"),
                        KeyboardButton(text = "🪑 Я за столом / У меня QR"),
                    ),
                )
                add(
                    listOf(
                        KeyboardButton(text = "📄 Мои заказы и брони"),
                        KeyboardButton(text = "👤 Мой профиль"),
                    ),
                )
                add(
                    listOf(
                        KeyboardButton(text = "🤝 Добавить свою кальянную"),
                    ),
                )
            }

        return ReplyKeyboardMarkup(keyboard = keyboard, resizeKeyboard = true)
    }

    fun inlineVenueSelectorActions(venues: List<Pair<Long, String>>): InlineKeyboardMarkup =
        InlineKeyboardMarkup(
            inlineKeyboard =
                venues.map { (venueId, label) ->
                    listOf(
                        InlineKeyboardButton(
                            text = label,
                            callbackData = "venue_select:$venueId",
                        ),
                    )
                },
        )

    fun inlineOwnerVenueQuotaActions(canCreateVenue: Boolean): InlineKeyboardMarkup {
        val rows = mutableListOf<List<InlineKeyboardButton>>()
        if (canCreateVenue) {
            rows +=
                listOf(
                    InlineKeyboardButton(
                        text = "➕ Создать новое заведение",
                        callbackData = "owner_quota_create_start",
                    ),
                )
        } else {
            rows +=
                listOf(
                    InlineKeyboardButton(
                        text = "📩 Запросить ещё заведение",
                        callbackData = "owner_quota_request_start",
                    ),
                )
        }
        rows +=
            listOf(
                InlineKeyboardButton(
                    text = "↩️ Назад",
                    callbackData = "staff_venue_menu_back",
                ),
            )
        return InlineKeyboardMarkup(inlineKeyboard = rows)
    }

    fun inlineOwnerVenuesDashboardActions(
        venues: List<Pair<Long, String>>,
        canCreateVenue: Boolean,
    ): InlineKeyboardMarkup {
        val rows =
            venues
                .map { (venueId, label) ->
                    listOf(
                        InlineKeyboardButton(
                            text = label,
                            callbackData = "owner_venue_select:$venueId",
                        ),
                    )
                }.toMutableList()
        if (canCreateVenue) {
            rows +=
                listOf(
                    InlineKeyboardButton(
                        text = "➕ Создать новое заведение",
                        callbackData = "owner_quota_create_start",
                    ),
                )
        }
        rows +=
            listOf(
                InlineKeyboardButton(
                    text = "📩 Запросить увеличение лимита",
                    callbackData = "owner_quota_request_start",
                ),
            )
        rows +=
            listOf(
                InlineKeyboardButton(
                    text = "↩️ Назад",
                    callbackData = "staff_venue_menu_back",
                ),
            )
        return InlineKeyboardMarkup(inlineKeyboard = rows)
    }

    fun inlineOwnerSelectedVenueHubActions(
        venueId: Long,
        showAiAssistant: Boolean = false,
        showMiniAppEntry: Boolean = false,
        venuePanelUrl: String? = null,
    ): InlineKeyboardMarkup =
        InlineKeyboardMarkup(
            inlineKeyboard =
                buildList {
                    if (showMiniAppEntry && venuePanelUrl != null) {
                        add(
                            listOf(
                                InlineKeyboardButton(
                                    text = "📱 Открыть панель заведения",
                                    webApp = WebAppInfo(venuePanelUrl),
                                ),
                            ),
                        )
                    }
                    add(
                        listOf(
                            InlineKeyboardButton(
                                text = "🟢 Работа смены",
                                callbackData = "owner_venue_hub_shift:$venueId",
                            ),
                        ),
                    )
                    add(
                        listOf(
                            InlineKeyboardButton(
                                text = "⚙️ Настройка заведения",
                                callbackData = "owner_venue_hub_setup:$venueId",
                            ),
                        ),
                    )
                    add(
                        listOf(
                            InlineKeyboardButton(
                                text = "📊 Статистика",
                                callbackData = "owner_venue_stats_root:$venueId",
                            ),
                        ),
                    )
                    add(
                        listOf(
                            InlineKeyboardButton(
                                text = "📣 Продвижение",
                                callbackData = "venue_marketing_root:$venueId",
                            ),
                        ),
                    )
                    if (showAiAssistant) {
                        add(
                            listOf(
                                InlineKeyboardButton(
                                    text = "🤖 Помощник",
                                    callbackData = "venue_ai:$venueId",
                                ),
                            ),
                        )
                    }
                    add(
                        listOf(
                            InlineKeyboardButton(
                                text = "👁 Предпросмотр для гостя",
                                callbackData = "owner_venue_publish_preview:$venueId",
                            ),
                        ),
                    )
                    add(
                        listOf(
                            InlineKeyboardButton(
                                text = "↩️ К моим заведениям",
                                callbackData = "owner_venues_dashboard",
                            ),
                        ),
                    )
                    add(
                        listOf(
                            InlineKeyboardButton(
                                text = "🏢 Выбрать другое заведение",
                                callbackData = "owner_venue_change",
                            ),
                        ),
                    )
                },
        )

    fun inlineVenueMarketingRootActions(
        venueId: Long,
        showAiAssistant: Boolean = false,
    ): InlineKeyboardMarkup =
        InlineKeyboardMarkup(
            inlineKeyboard =
                buildList {
                    add(
                        listOf(
                            InlineKeyboardButton(
                                text = "🎁 Акции",
                                callbackData = "vm_promos:$venueId",
                            ),
                        ),
                    )
                    add(
                        listOf(
                            InlineKeyboardButton(
                                text = "🎁 Лояльность",
                                callbackData = "vm_loyalty:$venueId",
                            ),
                        ),
                    )
                    add(
                        listOf(
                            InlineKeyboardButton(
                                text = "🖼 Баннеры / Афиши",
                                callbackData = "vm_banners:$venueId",
                            ),
                        ),
                    )
                    add(
                        listOf(
                            InlineKeyboardButton(
                                text = "📌 Размещения",
                                callbackData = "vm_placements:$venueId",
                            ),
                        ),
                    )
                    add(
                        listOf(
                            InlineKeyboardButton(
                                text = "🏆 Поднять в Акциях",
                                callbackData = "vm_top_req:$venueId",
                            ),
                        ),
                    )
                    add(
                        listOf(
                            InlineKeyboardButton(
                                text = "⭐ Оценки и отзывы",
                                callbackData = "vm_feedback:$venueId",
                            ),
                        ),
                    )
                    add(
                        listOf(
                            InlineKeyboardButton(
                                text = "🔗 Ссылка на отзывы",
                                callbackData = "vm_review_url:$venueId",
                            ),
                        ),
                    )
                    if (showAiAssistant) {
                        add(
                            listOf(
                                InlineKeyboardButton(
                                    text = "🤖 Помощник по продвижению",
                                    callbackData = "vm_ai:$venueId",
                                ),
                            ),
                        )
                    }
                    add(
                        listOf(
                            InlineKeyboardButton(
                                text = "↩️ Назад к заведению",
                                callbackData = "owner_venue_hub:$venueId",
                            ),
                        ),
                    )
                },
        )

    fun inlineVenueAiAssistantRootActions(
        venueId: Long,
        diagnosticsCallbackData: String = "vm_ai_diag:$venueId",
        promotionSummaryCallbackData: String = "vm_ai_sum:promotion:$venueId",
        feedbackSummaryCallbackData: String = "vm_ai_sum:feedback:$venueId",
        loyaltySummaryCallbackData: String = "vm_ai_sum:loyalty:$venueId",
        ordersSummaryCallbackData: String = "vm_ai_sum:orders:$venueId",
        promotionTextCallbackData: String = "vm_ai_draft:promotion:$venueId",
        feedbackReplyCallbackData: String = "vm_ai_draft:review:$venueId",
        bannerTextCallbackData: String = "vm_ai_draft:banner:$venueId",
        backText: String = "↩️ К продвижению",
        backCallbackData: String = "venue_marketing_root:$venueId",
    ): InlineKeyboardMarkup =
        InlineKeyboardMarkup(
            inlineKeyboard =
                listOf(
                    listOf(
                        InlineKeyboardButton(
                            text = "🔍 Почему акция не применяется?",
                            callbackData = diagnosticsCallbackData,
                        ),
                    ),
                    listOf(
                        InlineKeyboardButton(
                            text = "📣 Сводка по продвижению",
                            callbackData = promotionSummaryCallbackData,
                        ),
                    ),
                    listOf(
                        InlineKeyboardButton(
                            text = "⭐ Сводка по отзывам",
                            callbackData = feedbackSummaryCallbackData,
                        ),
                    ),
                    listOf(
                        InlineKeyboardButton(
                            text = "🎁 Сводка по лояльности",
                            callbackData = loyaltySummaryCallbackData,
                        ),
                    ),
                    listOf(
                        InlineKeyboardButton(
                            text = "🧾 Сводка по заказам",
                            callbackData = ordersSummaryCallbackData,
                        ),
                    ),
                    listOf(
                        InlineKeyboardButton(
                            text = "✍️ Помочь с текстом акции",
                            callbackData = promotionTextCallbackData,
                        ),
                    ),
                    listOf(
                        InlineKeyboardButton(
                            text = "💬 Черновик ответа на отзыв",
                            callbackData = feedbackReplyCallbackData,
                        ),
                    ),
                    listOf(
                        InlineKeyboardButton(
                            text = "🖼 Текст для баннера",
                            callbackData = bannerTextCallbackData,
                        ),
                    ),
                    listOf(
                        InlineKeyboardButton(
                            text = backText,
                            callbackData = backCallbackData,
                        ),
                    ),
                ),
        )

    fun inlineVenueAiPromotionDiagnosticsActions(
        venueId: Long,
        promotions: List<Pair<Long, String>>,
        promotionCallbackPrefix: String = "vm_ai_diag_p",
        assistantCallbackData: String = "vm_ai:$venueId",
    ): InlineKeyboardMarkup =
        InlineKeyboardMarkup(
            inlineKeyboard =
                promotions.map { (promotionId, label) ->
                    listOf(
                        InlineKeyboardButton(
                            text = label,
                            callbackData = "$promotionCallbackPrefix:$venueId:$promotionId",
                        ),
                    )
                } +
                    listOf(
                        listOf(
                            InlineKeyboardButton(
                                text = "↩️ К помощнику",
                                callbackData = assistantCallbackData,
                            ),
                        ),
                    ),
        )

    fun inlineVenueMarketingTopRequestActions(venueId: Long): InlineKeyboardMarkup =
        InlineKeyboardMarkup(
            inlineKeyboard =
                listOf(
                    listOf(
                        InlineKeyboardButton(
                            text = "📨 Отправить заявку",
                            callbackData = "vm_top_send:$venueId",
                        ),
                    ),
                    listOf(
                        InlineKeyboardButton(
                            text = "↩️ К продвижению",
                            callbackData = "venue_marketing_root:$venueId",
                        ),
                    ),
                ),
        )

    fun inlineVenueMarketingInfoActions(venueId: Long): InlineKeyboardMarkup =
        InlineKeyboardMarkup(
            inlineKeyboard =
                listOf(
                    listOf(
                        InlineKeyboardButton(
                            text = "🎁 Открыть акции",
                            callbackData = "vm_promos:$venueId",
                        ),
                    ),
                    listOf(
                        InlineKeyboardButton(
                            text = "↩️ К продвижению",
                            callbackData = "venue_marketing_root:$venueId",
                        ),
                    ),
                ),
        )

    fun inlineVenueMarketingLoyaltyRootActions(
        venueId: Long,
        hasProgram: Boolean,
    ): InlineKeyboardMarkup =
        InlineKeyboardMarkup(
            inlineKeyboard =
                buildList {
                    add(
                        listOf(
                            InlineKeyboardButton(
                                text = "🎁 Каждый N-й кальян",
                                callbackData = "vm_loyalty_program:$venueId",
                            ),
                        ),
                    )
                    if (!hasProgram) {
                        add(
                            listOf(
                                InlineKeyboardButton(
                                    text = "➕ Настроить программу",
                                    callbackData = "vm_loyalty_setup:$venueId",
                                ),
                            ),
                        )
                    }
                    add(
                        listOf(
                            InlineKeyboardButton(
                                text = "↩️ К продвижению",
                                callbackData = "venue_marketing_root:$venueId",
                            ),
                        ),
                    )
                },
        )

    fun inlineVenueMarketingLoyaltyNthActions(venueId: Long): InlineKeyboardMarkup =
        InlineKeyboardMarkup(
            inlineKeyboard =
                listOf(
                    listOf(
                        InlineKeyboardButton(
                            text = "Каждый 3-й",
                            callbackData = "vm_loyalty_n:$venueId:3",
                        ),
                    ),
                    listOf(
                        InlineKeyboardButton(
                            text = "Каждый 5-й",
                            callbackData = "vm_loyalty_n:$venueId:5",
                        ),
                    ),
                    listOf(
                        InlineKeyboardButton(
                            text = "Каждый 6-й",
                            callbackData = "vm_loyalty_n:$venueId:6",
                        ),
                    ),
                    listOf(
                        InlineKeyboardButton(
                            text = "✏️ Указать своё число",
                            callbackData = "vm_loyalty_custom:$venueId",
                        ),
                    ),
                    listOf(
                        InlineKeyboardButton(
                            text = "↩️ К лояльности",
                            callbackData = "vm_loyalty:$venueId",
                        ),
                    ),
                ),
        )

    fun inlineVenueMarketingLoyaltyProgramActions(
        venueId: Long,
        programId: Long,
        status: String,
    ): InlineKeyboardMarkup =
        InlineKeyboardMarkup(
            inlineKeyboard =
                buildList {
                    add(
                        listOf(
                            InlineKeyboardButton(
                                text = "✏️ Изменить N",
                                callbackData = "vm_loyalty_setup:$venueId",
                            ),
                        ),
                    )
                    add(
                        listOf(
                            InlineKeyboardButton(
                                text = "🎯 Что засчитывается",
                                callbackData = "vm_loyalty_earn:$venueId:$programId",
                            ),
                        ),
                    )
                    add(
                        listOf(
                            InlineKeyboardButton(
                                text = "🎁 Что можно получить бесплатно",
                                callbackData = "vm_loyalty_reward:$venueId:$programId",
                            ),
                        ),
                    )
                    if (status == "ACTIVE") {
                        add(
                            listOf(
                                InlineKeyboardButton(
                                    text = "⏸ Приостановить",
                                    callbackData = "vm_loyalty_status:$venueId:$programId:PAUSED",
                                ),
                            ),
                        )
                    } else {
                        add(
                            listOf(
                                InlineKeyboardButton(
                                    text = "▶️ Включить",
                                    callbackData = "vm_loyalty_status:$venueId:$programId:ACTIVE",
                                ),
                            ),
                        )
                    }
                    add(
                        listOf(
                            InlineKeyboardButton(
                                text = "🗄 Архивировать",
                                callbackData = "vm_loyalty_status:$venueId:$programId:ARCHIVED",
                            ),
                        ),
                    )
                    add(
                        listOf(
                            InlineKeyboardButton(
                                text = "↩️ К лояльности",
                                callbackData = "vm_loyalty:$venueId",
                            ),
                        ),
                    )
                },
        )

    fun inlineVenueMarketingLoyaltyCustomNWaitActions(venueId: Long): InlineKeyboardMarkup =
        InlineKeyboardMarkup(
            inlineKeyboard =
                listOf(
                    listOf(
                        InlineKeyboardButton(
                            text = "↩️ К выбору N",
                            callbackData = "vm_loyalty_setup:$venueId",
                        ),
                    ),
                ),
        )

    fun inlineVenueMarketingLoyaltyTargetScopeActions(
        venueId: Long,
        programId: Long,
        kind: String,
    ): InlineKeyboardMarkup {
        val allCallback = if (kind == "earn") "vle_all:$venueId:$programId" else "vlr_all:$venueId:$programId"
        val itemsCallback = if (kind == "earn") "vle_items:$venueId:$programId:0" else "vlr_items:$venueId:$programId:0"
        return InlineKeyboardMarkup(
            inlineKeyboard =
                listOf(
                    listOf(
                        InlineKeyboardButton(
                            text = "✅ Все кальяны",
                            callbackData = allCallback,
                        ),
                    ),
                    listOf(
                        InlineKeyboardButton(
                            text = "🎯 Выбрать отдельные позиции",
                            callbackData = itemsCallback,
                        ),
                    ),
                    listOf(
                        InlineKeyboardButton(
                            text = "↩️ Назад",
                            callbackData = "vm_loyalty_program:$venueId",
                        ),
                    ),
                ),
        )
    }

    fun inlineVenueMarketingLoyaltyTargetItemsActions(
        venueId: Long,
        programId: Long,
        kind: String,
        page: Int,
        items: List<Pair<Long, String>>,
        selectedItemIds: Set<Long>,
        hasPreviousPage: Boolean,
        hasNextPage: Boolean,
    ): InlineKeyboardMarkup =
        InlineKeyboardMarkup(
            inlineKeyboard =
                buildList {
                    items.forEach { (itemId, name) ->
                        val togglePrefix = if (kind == "earn") "vle_t" else "vlr_t"
                        add(
                            listOf(
                                InlineKeyboardButton(
                                    text = "${if (itemId in selectedItemIds) "✅ " else ""}$name",
                                    callbackData = "$togglePrefix:$venueId:$programId:$itemId:$page",
                                ),
                            ),
                        )
                    }
                    val itemsPrefix = if (kind == "earn") "vle_items" else "vlr_items"
                    addPromotionTargetPagination(
                        hasPreviousPage = hasPreviousPage,
                        hasNextPage = hasNextPage,
                        previousCallback = "$itemsPrefix:$venueId:$programId:${page - 1}",
                        nextCallback = "$itemsPrefix:$venueId:$programId:${page + 1}",
                    )
                    add(
                        listOf(
                            InlineKeyboardButton(
                                text = "✅ Готово",
                                callbackData = "${if (kind == "earn") "vle_done" else "vlr_done"}:$venueId:$programId",
                            ),
                        ),
                    )
                    add(
                        listOf(
                            InlineKeyboardButton(
                                text = "↩️ Назад",
                                callbackData =
                                    if (kind == "earn") {
                                        "vm_loyalty_earn:$venueId:$programId"
                                    } else {
                                        "vm_loyalty_reward:$venueId:$programId"
                                    },
                            ),
                        ),
                    )
                },
        )

    fun inlineVenueMarketingPlacementRootActions(venueId: Long): InlineKeyboardMarkup =
        InlineKeyboardMarkup(
            inlineKeyboard =
                listOf(
                    listOf(
                        InlineKeyboardButton(
                            text = "🕓 На проверке",
                            callbackData = "vm_places_pending:$venueId",
                        ),
                    ),
                    listOf(
                        InlineKeyboardButton(
                            text = "✅ Активные",
                            callbackData = "vm_places_active:$venueId",
                        ),
                    ),
                    listOf(
                        InlineKeyboardButton(
                            text = "🗄 Завершённые / Архив",
                            callbackData = "vm_places_finished:$venueId",
                        ),
                    ),
                    listOf(
                        InlineKeyboardButton(
                            text = "↩️ К продвижению",
                            callbackData = "venue_marketing_root:$venueId",
                        ),
                    ),
                ),
        )

    fun inlineVenueMarketingPlacementListActions(
        venueId: Long,
        placements: List<Pair<Long, String>>,
        filter: String,
    ): InlineKeyboardMarkup =
        InlineKeyboardMarkup(
            inlineKeyboard =
                buildList {
                    placements.forEach { (placementId, label) ->
                        add(
                            listOf(
                                InlineKeyboardButton(
                                    text = label,
                                    callbackData = "vm_place_open:$venueId:$placementId:$filter",
                                ),
                            ),
                        )
                    }
                    add(
                        listOf(
                            InlineKeyboardButton(
                                text = "↩️ К размещениям",
                                callbackData = "vm_placements:$venueId",
                            ),
                        ),
                    )
                },
        )

    fun inlineVenueMarketingMixedPlacementListActions(
        venueId: Long,
        rows: List<Pair<String, String>>,
    ): InlineKeyboardMarkup =
        InlineKeyboardMarkup(
            inlineKeyboard =
                buildList {
                    rows.forEach { (callbackData, label) ->
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
                                text = "↩️ К размещениям",
                                callbackData = "vm_placements:$venueId",
                            ),
                        ),
                    )
                },
        )

    fun inlineVenueMarketingPlacementDetailActions(
        venueId: Long,
        backFilter: String,
    ): InlineKeyboardMarkup =
        InlineKeyboardMarkup(
            inlineKeyboard =
                listOf(
                    listOf(
                        InlineKeyboardButton(
                            text = "↩️ К размещениям",
                            callbackData = "vm_places_$backFilter:$venueId",
                        ),
                    ),
                ),
        )

    fun inlineOwnerSelectedVenueShiftActions(venueId: Long): InlineKeyboardMarkup =
        InlineKeyboardMarkup(
            inlineKeyboard =
                listOf(
                    listOf(
                        InlineKeyboardButton(
                            text = "📦 Заказы",
                            callbackData = "staff_venue_orders_root:$venueId",
                        ),
                    ),
                    listOf(
                        InlineKeyboardButton(
                            text = "🛎 Вызовы",
                            callbackData = "staff_venue_calls_root:$venueId",
                        ),
                    ),
                    listOf(
                        InlineKeyboardButton(
                            text = "📄 Брони",
                            callbackData = "owner_venue_bookings_root:$venueId",
                        ),
                    ),
                    listOf(
                        InlineKeyboardButton(
                            text = "🚫 Стоп-лист",
                            callbackData = "staff_venue_stoplist_root:$venueId",
                        ),
                    ),
                    listOf(
                        InlineKeyboardButton(
                            text = "↩️ Назад к заведению",
                            callbackData = "owner_venue_hub:$venueId",
                        ),
                    ),
                ),
        )

    fun inlineOwnerSelectedVenueSetupActions(venueId: Long): InlineKeyboardMarkup =
        InlineKeyboardMarkup(
            inlineKeyboard =
                listOf(
                    listOf(
                        InlineKeyboardButton(
                            text = "🏢 Профиль / карточка",
                            callbackData = "owner_venue_profile:$venueId",
                        ),
                    ),
                    listOf(
                        InlineKeyboardButton(
                            text = "🍽 Заказное меню",
                            callbackData = "owner_venue_order_menu_root:$venueId",
                        ),
                    ),
                    listOf(
                        InlineKeyboardButton(
                            text = "🪑 Столы и QR",
                            callbackData = "owner_venue_tables_root:$venueId",
                        ),
                    ),
                    listOf(
                        InlineKeyboardButton(
                            text = "👥 Персонал",
                            callbackData = "owner_venue_staff_root:$venueId",
                        ),
                    ),
                    listOf(
                        InlineKeyboardButton(
                            text = "💬 Чат персонала",
                            callbackData = "venue_staff_chat_root:$venueId",
                        ),
                    ),
                    listOf(
                        InlineKeyboardButton(
                            text = "⏱ Сколько держим бронь",
                            callbackData = "venue_booking_hold_settings:$venueId",
                        ),
                    ),
                    listOf(
                        InlineKeyboardButton(
                            text = "✅ Готовность к публикации",
                            callbackData = "owner_venue_publish_readiness:$venueId",
                        ),
                    ),
                    listOf(
                        InlineKeyboardButton(
                            text = "↩️ Назад к заведению",
                            callbackData = "owner_venue_hub:$venueId",
                        ),
                    ),
                ),
        )

    fun inlineVenueBookingRootActions(
        venueId: Long,
        canManageSettings: Boolean,
    ): InlineKeyboardMarkup =
        InlineKeyboardMarkup(
            inlineKeyboard =
                buildList {
                    if (canManageSettings) {
                        add(
                            listOf(
                                InlineKeyboardButton(
                                    text = "⚙️ Настройки брони",
                                    callbackData = "venue_booking_hold_settings:$venueId",
                                ),
                            ),
                        )
                    }
                    add(
                        listOf(
                            InlineKeyboardButton(
                                text = "↩️ Назад к заведению",
                                callbackData = "venue_menu_back:$venueId",
                            ),
                        ),
                    )
                },
        )

    fun inlineVenueBookingHoldSettingsActions(
        venueId: Long,
        currentHoldMinutes: Int? = null,
    ): InlineKeyboardMarkup =
        InlineKeyboardMarkup(
            inlineKeyboard =
                buildList {
                    val quickButtons =
                        listOf(30, 60)
                            .filter { it != currentHoldMinutes }
                            .map { minutes ->
                                InlineKeyboardButton(
                                    text = "$minutes минут",
                                    callbackData = "venue_booking_hold_set:$venueId:$minutes",
                                )
                            }
                    if (quickButtons.isNotEmpty()) {
                        add(quickButtons)
                    }
                    add(
                        listOf(
                            InlineKeyboardButton(
                                text = "✏️ Ввести своё число",
                                callbackData = "venue_booking_hold_custom:$venueId",
                            ),
                        ),
                    )
                    add(
                        listOf(
                            InlineKeyboardButton(
                                text = "↩️ Назад",
                                callbackData = "venue_booking_hold_back:$venueId",
                            ),
                        ),
                    )
                },
        )

    fun inlineVenueBookingHoldCustomInputActions(venueId: Long): InlineKeyboardMarkup =
        InlineKeyboardMarkup(
            inlineKeyboard =
                listOf(
                    listOf(
                        InlineKeyboardButton(
                            text = "⬅️ Назад",
                            callbackData = "venue_booking_hold_back:$venueId",
                        ),
                    ),
                    listOf(
                        InlineKeyboardButton(
                            text = "✖️ Отмена",
                            callbackData = "venue_booking_hold_back:$venueId",
                        ),
                    ),
                ),
        )

    fun inlineOwnerVenueStatsSelectorActions(venues: List<Pair<Long, String>>): InlineKeyboardMarkup =
        InlineKeyboardMarkup(
            inlineKeyboard =
                buildList {
                    add(
                        listOf(
                            InlineKeyboardButton(
                                text = "📊 Все заведения",
                                callbackData = "owner_stats_all",
                            ),
                        ),
                    )
                    venues.forEach { (venueId, label) ->
                        add(
                            listOf(
                                InlineKeyboardButton(
                                    text = label,
                                    callbackData = "owner_stats_venue:$venueId",
                                ),
                            ),
                        )
                    }
                    add(
                        listOf(
                            InlineKeyboardButton(
                                text = "↩️ Назад",
                                callbackData = "staff_venue_menu_back",
                            ),
                        ),
                    )
                },
        )

    fun inlineOwnerVenueStatsPlaceholderActions(): InlineKeyboardMarkup =
        InlineKeyboardMarkup(
            inlineKeyboard =
                listOf(
                    listOf(
                        InlineKeyboardButton(
                            text = "↩️ К статистике заведений",
                            callbackData = "owner_stats_root",
                        ),
                    ),
                ),
        )

    fun inlinePlatformVenueListActions(venues: List<Pair<Long, String>>): InlineKeyboardMarkup =
        InlineKeyboardMarkup(
            inlineKeyboard =
                buildList {
                    venues.forEach { (venueId, label) ->
                        add(
                            listOf(
                                InlineKeyboardButton(
                                    text = label,
                                    callbackData = "platform_venue_open:$venueId",
                                ),
                            ),
                        )
                    }
                },
        )

    fun inlinePlatformVenueCardActions(venueId: Long): InlineKeyboardMarkup =
        InlineKeyboardMarkup(
            inlineKeyboard =
                listOf(
                    listOf(
                        InlineKeyboardButton(
                            text = "💳 Подписка",
                            callbackData = "platform_venue_subscription:$venueId",
                        ),
                        InlineKeyboardButton(
                            text = "⚙️ Статус",
                            callbackData = "platform_venue_status:$venueId",
                        ),
                    ),
                    listOf(
                        InlineKeyboardButton(
                            text = "↩️ К списку",
                            callbackData = "platform_venues_list",
                        ),
                    ),
                ),
        )

    fun inlinePlatformSubscriptionZeroPriceConfirmActions(venueId: Long): InlineKeyboardMarkup =
        InlineKeyboardMarkup(
            inlineKeyboard =
                listOf(
                    listOf(
                        InlineKeyboardButton(
                            text = "✅ Да, бесплатно",
                            callbackData = "platform_subscription_zero_price:$venueId:confirm",
                        ),
                    ),
                    listOf(
                        InlineKeyboardButton(
                            text = "✏️ Ввести другую цену",
                            callbackData = "platform_subscription_edit_price:$venueId",
                        ),
                    ),
                    listOf(
                        InlineKeyboardButton(
                            text = "↩️ К подписке",
                            callbackData = "platform_venue_subscription:$venueId",
                        ),
                    ),
                ),
        )

    fun inlinePlatformVenueBackToCardActions(venueId: Long): InlineKeyboardMarkup =
        InlineKeyboardMarkup(
            inlineKeyboard =
                listOf(
                    listOf(
                        InlineKeyboardButton(
                            text = "↩️ К карточке",
                            callbackData = "platform_venue_open:$venueId",
                        ),
                    ),
                    listOf(
                        InlineKeyboardButton(
                            text = "↩️ К списку",
                            callbackData = "platform_venues_list",
                        ),
                    ),
                ),
        )

    fun inlinePlatformVenueSubscriptionActions(venueId: Long): InlineKeyboardMarkup =
        InlineKeyboardMarkup(
            inlineKeyboard =
                listOf(
                    listOf(
                        InlineKeyboardButton(
                            text = "✏️ Изменить текущую цену",
                            callbackData = "platform_subscription_edit_price:$venueId",
                        ),
                    ),
                    listOf(
                        InlineKeyboardButton(
                            text = "📅 Задать будущую цену",
                            callbackData = "platform_subscription_future_price:$venueId",
                        ),
                    ),
                    listOf(
                        InlineKeyboardButton(
                            text = "↩️ К карточке",
                            callbackData = "platform_venue_open:$venueId",
                        ),
                    ),
                    listOf(
                        InlineKeyboardButton(
                            text = "↩️ К списку",
                            callbackData = "platform_venues_list",
                        ),
                    ),
                ),
        )

    fun inlinePlatformPromotionRootActions(): InlineKeyboardMarkup =
        InlineKeyboardMarkup(
            inlineKeyboard =
                listOf(
                    listOf(
                        InlineKeyboardButton(
                            text = "🖼 Баннеры / Афиши",
                            callbackData = "platform_places_root",
                        ),
                    ),
                    listOf(
                        InlineKeyboardButton(
                            text = "🏆 Топ в Акциях",
                            callbackData = "platform_vtop_root",
                        ),
                    ),
                    listOf(
                        InlineKeyboardButton(
                            text = "↩️ Назад",
                            callbackData = "platform_owner_main",
                        ),
                    ),
                ),
        )

    fun inlinePlatformPromotionPlacementRootActions(): InlineKeyboardMarkup =
        InlineKeyboardMarkup(
            inlineKeyboard =
                listOf(
                    listOf(
                        InlineKeyboardButton(
                            text = "🕓 На проверке",
                            callbackData = "platform_places_pending",
                        ),
                    ),
                    listOf(
                        InlineKeyboardButton(
                            text = "✅ Активные",
                            callbackData = "platform_places_active",
                        ),
                    ),
                    listOf(
                        InlineKeyboardButton(
                            text = "🗄 Завершённые / Архив",
                            callbackData = "platform_places_finished",
                        ),
                    ),
                    listOf(
                        InlineKeyboardButton(
                            text = "↩️ К продвижению",
                            callbackData = "platform_promo_root",
                        ),
                    ),
                ),
        )

    fun inlinePlatformPromotionPlacementListActions(
        placements: List<Pair<Long, String>>,
        backCallbackData: String = "platform_places_root",
    ): InlineKeyboardMarkup =
        InlineKeyboardMarkup(
            inlineKeyboard =
                buildList {
                    placements.forEach { (placementId, label) ->
                        add(
                            listOf(
                                InlineKeyboardButton(
                                    text = label,
                                    callbackData = "platform_place_open:$placementId",
                                ),
                            ),
                        )
                    }
                    add(
                        listOf(
                            InlineKeyboardButton(
                                text = "↩️ Назад",
                                callbackData = backCallbackData,
                            ),
                        ),
                    )
                },
        )

    fun inlinePlatformVenueTopPlacementRootActions(): InlineKeyboardMarkup =
        InlineKeyboardMarkup(
            inlineKeyboard =
                listOf(
                    listOf(
                        InlineKeyboardButton(
                            text = "🕓 Заявки",
                            callbackData = "platform_vtop_pending",
                        ),
                    ),
                    listOf(
                        InlineKeyboardButton(
                            text = "✅ Активные",
                            callbackData = "platform_vtop_active",
                        ),
                    ),
                    listOf(
                        InlineKeyboardButton(
                            text = "🗄 Завершённые / Архив",
                            callbackData = "platform_vtop_finished",
                        ),
                    ),
                    listOf(
                        InlineKeyboardButton(
                            text = "↩️ К продвижению",
                            callbackData = "platform_promo_root",
                        ),
                    ),
                ),
        )

    fun inlinePlatformVenueTopPlacementListActions(
        placements: List<Pair<Long, String>>,
        backCallbackData: String = "platform_vtop_root",
    ): InlineKeyboardMarkup =
        InlineKeyboardMarkup(
            inlineKeyboard =
                buildList {
                    placements.forEach { (placementId, label) ->
                        add(
                            listOf(
                                InlineKeyboardButton(
                                    text = label,
                                    callbackData = "platform_vtop_open:$placementId",
                                ),
                            ),
                        )
                    }
                    add(
                        listOf(
                            InlineKeyboardButton(
                                text = "↩️ Назад",
                                callbackData = backCallbackData,
                            ),
                        ),
                    )
                },
        )

    fun inlinePlatformVenueTopPlacementDetailActions(
        placementId: Long,
        showApprove: Boolean = true,
        showStatusActions: Boolean = true,
        backCallbackData: String = "platform_vtop_pending",
    ): InlineKeyboardMarkup =
        InlineKeyboardMarkup(
            inlineKeyboard =
                buildList {
                    if (showApprove) {
                        add(
                            listOf(
                                InlineKeyboardButton(
                                    text = "✅ Одобрить",
                                    callbackData = "platform_vtop_approve:$placementId",
                                ),
                                InlineKeyboardButton(
                                    text = "❌ Отклонить",
                                    callbackData = "platform_vtop_reject:$placementId",
                                ),
                            ),
                        )
                    }
                    if (showStatusActions) {
                        add(
                            listOf(
                                InlineKeyboardButton(
                                    text = "⏸ Приостановить",
                                    callbackData = "platform_vtop_pause:$placementId",
                                ),
                                InlineKeyboardButton(
                                    text = "🗄 Архивировать",
                                    callbackData = "platform_vtop_archive:$placementId",
                                ),
                            ),
                        )
                    }
                    add(
                        listOf(
                            InlineKeyboardButton(
                                text = "↩️ Назад",
                                callbackData = backCallbackData,
                            ),
                        ),
                    )
                },
        )

    fun inlinePlatformVenueTopPlacementApprovalPeriodActions(placementId: Long): InlineKeyboardMarkup =
        InlineKeyboardMarkup(
            inlineKeyboard =
                listOf(
                    listOf(
                        InlineKeyboardButton(
                            text = "1 день",
                            callbackData = "platform_vtop_approve_days:$placementId:1",
                        ),
                        InlineKeyboardButton(
                            text = "7 дней",
                            callbackData = "platform_vtop_approve_days:$placementId:7",
                        ),
                    ),
                    listOf(
                        InlineKeyboardButton(
                            text = "30 дней",
                            callbackData = "platform_vtop_approve_days:$placementId:30",
                        ),
                    ),
                    listOf(
                        InlineKeyboardButton(
                            text = "📅 Задать период",
                            callbackData = "platform_vtop_approve_period:$placementId",
                        ),
                    ),
                    listOf(
                        InlineKeyboardButton(
                            text = "↩️ Назад",
                            callbackData = "platform_vtop_open:$placementId",
                        ),
                    ),
                ),
        )

    fun inlinePlatformVenueTopPlacementManualPromptActions(placementId: Long): InlineKeyboardMarkup =
        InlineKeyboardMarkup(
            inlineKeyboard =
                listOf(
                    listOf(
                        InlineKeyboardButton(
                            text = "↩️ Назад",
                            callbackData = "platform_vtop_approve:$placementId",
                        ),
                    ),
                ),
        )

    fun inlinePlatformPromotionPlacementDetailActions(
        placementId: Long,
        showApprove: Boolean = true,
        showStatusActions: Boolean = true,
        backCallbackData: String = "platform_places_pending",
    ): InlineKeyboardMarkup =
        InlineKeyboardMarkup(
            inlineKeyboard =
                buildList {
                    if (showApprove) {
                        add(
                            listOf(
                                InlineKeyboardButton(
                                    text = "✅ Одобрить",
                                    callbackData = "platform_place_approve:$placementId",
                                ),
                                InlineKeyboardButton(
                                    text = "❌ Отклонить",
                                    callbackData = "platform_place_reject:$placementId",
                                ),
                            ),
                        )
                    }
                    if (showStatusActions) {
                        add(
                            listOf(
                                InlineKeyboardButton(
                                    text = "⏸ Приостановить",
                                    callbackData = "platform_place_pause:$placementId",
                                ),
                                InlineKeyboardButton(
                                    text = "🗄 Архивировать",
                                    callbackData = "platform_place_archive:$placementId",
                                ),
                            ),
                        )
                    }
                    add(
                        listOf(
                            InlineKeyboardButton(
                                text = "↩️ Назад",
                                callbackData = backCallbackData,
                            ),
                        ),
                    )
                },
        )

    fun inlinePlatformPromotionPlacementApprovalPeriodActions(placementId: Long): InlineKeyboardMarkup =
        InlineKeyboardMarkup(
            inlineKeyboard =
                listOf(
                    listOf(
                        InlineKeyboardButton(
                            text = "1 день",
                            callbackData = "platform_place_approve_days:$placementId:1",
                        ),
                        InlineKeyboardButton(
                            text = "7 дней",
                            callbackData = "platform_place_approve_days:$placementId:7",
                        ),
                    ),
                    listOf(
                        InlineKeyboardButton(
                            text = "30 дней",
                            callbackData = "platform_place_approve_days:$placementId:30",
                        ),
                    ),
                    listOf(
                        InlineKeyboardButton(
                            text = "Ввести вручную",
                            callbackData = "platform_place_approve_manual:$placementId",
                        ),
                    ),
                    listOf(
                        InlineKeyboardButton(
                            text = "↩️ Назад",
                            callbackData = "platform_place_open:$placementId",
                        ),
                    ),
                ),
        )

    fun inlinePlatformPromotionPlacementManualPromptActions(placementId: Long): InlineKeyboardMarkup =
        InlineKeyboardMarkup(
            inlineKeyboard =
                listOf(
                    listOf(
                        InlineKeyboardButton(
                            text = "↩️ Назад",
                            callbackData = "platform_place_approve:$placementId",
                        ),
                    ),
                ),
        )

    fun inlinePlatformVenueStatusActions(
        venueId: Long,
        actions: List<Pair<String, String>>,
    ): InlineKeyboardMarkup =
        InlineKeyboardMarkup(
            inlineKeyboard =
                buildList {
                    actions.forEach { (label, action) ->
                        add(
                            listOf(
                                InlineKeyboardButton(
                                    text = label,
                                    callbackData = "platform_venue_status_ask:$venueId:$action",
                                ),
                            ),
                        )
                    }
                    add(
                        listOf(
                            InlineKeyboardButton(
                                text = "↩️ К карточке",
                                callbackData = "platform_venue_open:$venueId",
                            ),
                        ),
                    )
                },
        )

    fun inlinePlatformVenueStatusConfirmActions(
        venueId: Long,
        action: String,
    ): InlineKeyboardMarkup =
        InlineKeyboardMarkup(
            inlineKeyboard =
                listOf(
                    listOf(
                        InlineKeyboardButton(
                            text = "✅ Да, выполнить",
                            callbackData = "platform_venue_status_yes:$venueId:$action",
                        ),
                    ),
                    listOf(
                        InlineKeyboardButton(
                            text = "↩️ Назад",
                            callbackData = "platform_venue_status:$venueId",
                        ),
                    ),
                ),
        )

    fun inlinePlatformVenueStatusDeleteReviewActions(venueId: Long): InlineKeyboardMarkup =
        InlineKeyboardMarkup(
            inlineKeyboard =
                listOf(
                    listOf(
                        InlineKeyboardButton(
                            text = "⚠️ Продолжить к удалению",
                            callbackData = "platform_venue_status_delete_review:$venueId",
                        ),
                    ),
                    listOf(
                        InlineKeyboardButton(
                            text = "↩️ Назад",
                            callbackData = "platform_venue_status:$venueId",
                        ),
                    ),
                ),
        )

    fun inlinePlatformVenueSuspendReasonPromptActions(venueId: Long): InlineKeyboardMarkup =
        InlineKeyboardMarkup(
            inlineKeyboard =
                listOf(
                    listOf(
                        InlineKeyboardButton(
                            text = "↩️ Назад",
                            callbackData = "platform_venue_status:$venueId",
                        ),
                    ),
                ),
        )

    fun inlinePlatformOwnerLimitRequestActions(requestId: Long): InlineKeyboardMarkup =
        InlineKeyboardMarkup(
            inlineKeyboard =
                listOf(
                    listOf(
                        InlineKeyboardButton(
                            text = "✅ Одобрить полностью",
                            callbackData = "platform_owner_limit_req_approve:$requestId",
                        ),
                    ),
                    listOf(
                        InlineKeyboardButton(
                            text = "✏️ Одобрить частично",
                            callbackData = "platform_owner_limit_req_partial:$requestId",
                        ),
                        InlineKeyboardButton(
                            text = "❌ Отклонить",
                            callbackData = "platform_owner_limit_req_reject:$requestId",
                        ),
                    ),
                    listOf(
                        InlineKeyboardButton(
                            text = "↩️ К запросам",
                            callbackData = "platform_owner_limit_requests_back",
                        ),
                    ),
                ),
        )

    fun inlineGuestProfileActions(): InlineKeyboardMarkup {
        val rows =
            listOf(
                listOf(
                    InlineKeyboardButton(
                        text = "✏️ Изменить имя",
                        callbackData = "guest_profile_name",
                    ),
                ),
                listOf(
                    InlineKeyboardButton(
                        text = "🎁 Указать день рождения",
                        callbackData = "guest_profile_birthday",
                    ),
                ),
                listOf(
                    InlineKeyboardButton(
                        text = "🎁 Лояльность",
                        callbackData = "guest_loyalty",
                    ),
                ),
                listOf(
                    InlineKeyboardButton(
                        text = "↩️ Назад",
                        callbackData = "guest_profile_back",
                    ),
                ),
            )
        return InlineKeyboardMarkup(inlineKeyboard = rows)
    }

    fun inlineTableEntryChoice(
        webAppUrl: String?,
        tableToken: String,
        tableSessionId: Long? = null,
    ): InlineKeyboardMarkup {
        val rows =
            buildList {
                add(
                    listOf(
                        webAppUrl?.let { url ->
                            val params =
                                buildMap {
                                    put("mode", "guest")
                                    put("tableToken", tableToken)
                                    put("screen", "menu")
                                    if (tableSessionId != null) {
                                        put("tableSessionId", tableSessionId.toString())
                                    }
                                }
                            InlineKeyboardButton(
                                text = "📱 Заказывать в Mini App",
                                webApp =
                                    WebAppInfo(
                                        buildWebAppUrl(
                                            url,
                                            params,
                                        ),
                                    ),
                            )
                        } ?: InlineKeyboardButton(
                            text = "📱 Mini App не настроен",
                            callbackData = "guest_miniapp_unavailable",
                        ),
                    ),
                )
                add(
                    listOf(
                        InlineKeyboardButton(
                            text = "💬 Заказывать в боте",
                            callbackData = "continue_in_bot",
                        ),
                    ),
                )
            }
        return InlineKeyboardMarkup(inlineKeyboard = rows)
    }

    fun tableContext(
        context: TableContext,
        webAppUrl: String?,
    ): ReplyKeyboardMarkup = tableContextBotFlow(context)

    fun tableContextBotFlow(context: TableContext): ReplyKeyboardMarkup {
        val keyboard =
            listOf(
                listOf(
                    KeyboardButton(text = "📱 Заказывать в Mini App"),
                    KeyboardButton(text = "💬 Заказывать в боте"),
                ),
                listOf(
                    KeyboardButton(text = "🍽️ Меню"),
                    KeyboardButton(text = "🧺 Корзина"),
                ),
                listOf(
                    KeyboardButton(text = "📄 Мой заказ"),
                    KeyboardButton(text = "✍️ Быстрый заказ"),
                ),
                listOf(
                    KeyboardButton(text = "👥 Общий счёт"),
                    KeyboardButton(text = "🛎 Вызвать персонал"),
                ),
                listOf(
                    KeyboardButton(text = "🎁 Акции заведения"),
                    KeyboardButton(text = "⭐ Любимое"),
                ),
                listOf(
                    KeyboardButton(text = "📜 История"),
                    KeyboardButton(text = "👤 Профиль"),
                ),
                listOf(KeyboardButton(text = "🚪 Сменить стол")),
            )
        return ReplyKeyboardMarkup(keyboard = keyboard)
    }

    fun inlineTableActionsBack(): InlineKeyboardMarkup =
        InlineKeyboardMarkup(
            inlineKeyboard =
                listOf(
                    listOf(
                        InlineKeyboardButton(
                            text = "↩️ Назад к действиям стола",
                            callbackData = "table_actions_back",
                        ),
                    ),
                ),
        )

    fun inlineBotVenueCatalog(
        venues: List<Pair<Long, String>>,
        includeFavoritesEntry: Boolean = false,
    ): InlineKeyboardMarkup =
        InlineKeyboardMarkup(
            inlineKeyboard =
                buildList {
                    if (includeFavoritesEntry) {
                        add(
                            listOf(
                                InlineKeyboardButton(
                                    text = "⭐ Избранные заведения",
                                    callbackData = "fav_v_list",
                                ),
                            ),
                        )
                    }
                    venues.forEach { (venueId, venueName) ->
                        add(
                            listOf(
                                InlineKeyboardButton(
                                    text = venueName,
                                    callbackData = "bot_catalog_venue:$venueId",
                                ),
                            ),
                        )
                    }
                },
        )

    fun inlineBotVenueCardActions(
        venueId: Long,
        routeUrl: String? = null,
        backText: String = "↩️ К каталогу",
        backCallbackData: String = "bot_catalog_open",
        favoriteActionText: String? = null,
        favoriteActionCallbackData: String? = null,
        promotionsActionText: String? = null,
        promotionsActionCallbackData: String? = null,
    ): InlineKeyboardMarkup =
        InlineKeyboardMarkup(
            inlineKeyboard =
                buildList {
                    add(
                        listOf(
                            InlineKeyboardButton(
                                text = "🪑 Забронировать стол",
                                callbackData = "bot_catalog_venue_book:$venueId",
                            ),
                        ),
                    )
                    add(
                        listOf(
                            InlineKeyboardButton(
                                text = "ℹ️ Информация",
                                callbackData = "bot_catalog_venue_about:$venueId",
                            ),
                            InlineKeyboardButton(
                                text = "🗺 Маршрут",
                                callbackData = if (routeUrl == null) "bot_catalog_venue_address:$venueId" else null,
                                url = routeUrl,
                            ),
                        ),
                    )
                    if (favoriteActionText != null && favoriteActionCallbackData != null) {
                        add(
                            listOf(
                                InlineKeyboardButton(
                                    text = favoriteActionText,
                                    callbackData = favoriteActionCallbackData,
                                ),
                            ),
                        )
                    }
                    if (promotionsActionText != null && promotionsActionCallbackData != null) {
                        add(
                            listOf(
                                InlineKeyboardButton(
                                    text = promotionsActionText,
                                    callbackData = promotionsActionCallbackData,
                                ),
                            ),
                        )
                    }
                    add(
                        listOf(
                            InlineKeyboardButton(
                                text = backText,
                                callbackData = backCallbackData,
                            ),
                        ),
                    )
                },
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

    fun inlineGuestPromotions(
        promotions: List<Pair<Long, String>>,
        source: String = "g",
        backCallbackData: String? = null,
    ): InlineKeyboardMarkup =
        InlineKeyboardMarkup(
            inlineKeyboard =
                buildList {
                    promotions.forEach { (promotionId, label) ->
                        add(
                            listOf(
                                InlineKeyboardButton(
                                    text = label,
                                    callbackData = "gp_o:$promotionId:$source",
                                ),
                            ),
                        )
                    }
                    backCallbackData?.let { callback ->
                        add(
                            listOf(
                                InlineKeyboardButton(
                                    text = "↩️ Назад",
                                    callbackData = callback,
                                ),
                            ),
                        )
                    }
                },
        )

    fun inlineGuestPromotionVenueFeed(
        venues: List<Pair<Long, String>>,
        page: Int,
        hasPrevious: Boolean,
        hasNext: Boolean,
    ): InlineKeyboardMarkup =
        InlineKeyboardMarkup(
            inlineKeyboard =
                buildList {
                    venues.forEach { (venueId, label) ->
                        add(
                            listOf(
                                InlineKeyboardButton(
                                    text = label,
                                    callbackData = "gp_v:$venueId",
                                ),
                            ),
                        )
                    }
                    val pagination =
                        buildList {
                            if (hasPrevious) {
                                add(
                                    InlineKeyboardButton(
                                        text = "◀️ Назад",
                                        callbackData = "gp_page:${(page - 1).coerceAtLeast(0)}",
                                    ),
                                )
                            }
                            if (hasNext) {
                                add(
                                    InlineKeyboardButton(
                                        text = "▶️ Далее",
                                        callbackData = "gp_page:${page + 1}",
                                    ),
                                )
                            }
                        }
                    if (pagination.isNotEmpty()) {
                        add(pagination)
                    }
                },
        )

    fun inlineGuestPromotedPromotionActions(
        promotionId: Long,
        source: String = "g",
    ): InlineKeyboardMarkup =
        InlineKeyboardMarkup(
            inlineKeyboard =
                listOf(
                    listOf(
                        InlineKeyboardButton(
                            text = "Подробнее",
                            callbackData = "gp_o:$promotionId:$source",
                        ),
                    ),
                ),
        )

    fun inlineGuestPromotionDetailActions(
        venueId: Long,
        backCallbackData: String,
    ): InlineKeyboardMarkup =
        InlineKeyboardMarkup(
            inlineKeyboard =
                listOf(
                    listOf(
                        InlineKeyboardButton(
                            text = "🏢 Карточка заведения",
                            callbackData = "bot_catalog_venue:$venueId",
                        ),
                    ),
                    listOf(
                        InlineKeyboardButton(
                            text = "↩️ К акциям",
                            callbackData = backCallbackData,
                        ),
                    ),
                ),
        )

    fun inlineVenuePromotionsRootActions(
        venueId: Long,
        promotions: List<Pair<Long, String>>,
        backText: String = "↩️ К продвижению",
        backCallbackData: String = "venue_marketing_root:$venueId",
    ): InlineKeyboardMarkup =
        InlineKeyboardMarkup(
            inlineKeyboard =
                buildList {
                    promotions.forEach { (promotionId, label) ->
                        add(
                            listOf(
                                InlineKeyboardButton(
                                    text = label,
                                    callbackData = "vp_o:$venueId:$promotionId",
                                ),
                            ),
                        )
                    }
                    add(
                        listOf(
                            InlineKeyboardButton(
                                text = "➕ Создать акцию",
                                callbackData = "vp_new:$venueId",
                            ),
                        ),
                    )
                    add(
                        listOf(
                            InlineKeyboardButton(
                                text = "🗄 Архив",
                                callbackData = "vp_archive_root:$venueId",
                            ),
                        ),
                    )
                    add(
                        listOf(
                            InlineKeyboardButton(
                                text = backText,
                                callbackData = backCallbackData,
                            ),
                        ),
                    )
                },
        )

    fun inlineVenuePromotionsArchiveActions(
        venueId: Long,
        promotions: List<Pair<Long, String>>,
    ): InlineKeyboardMarkup =
        InlineKeyboardMarkup(
            inlineKeyboard =
                buildList {
                    promotions.forEach { (promotionId, label) ->
                        add(
                            listOf(
                                InlineKeyboardButton(
                                    text = label,
                                    callbackData = "vp_archive_open:$venueId:$promotionId",
                                ),
                            ),
                        )
                    }
                    add(
                        listOf(
                            InlineKeyboardButton(
                                text = "↩️ К акциям",
                                callbackData = "vp_root:$venueId",
                            ),
                        ),
                    )
                },
        )

    fun inlineVenuePromotionTemplateActions(venueId: Long): InlineKeyboardMarkup =
        InlineKeyboardMarkup(
            inlineKeyboard =
                listOf(
                    listOf(
                        InlineKeyboardButton(
                            text = "📝 Простая акция",
                            callbackData = "vp_tpl:$venueId:TEXT_ONLY",
                        ),
                    ),
                    listOf(
                        InlineKeyboardButton(
                            text = "🕒 Счастливые часы",
                            callbackData = "vp_tpl:$venueId:HAPPY_HOURS_PERCENT",
                        ),
                    ),
                    listOf(
                        InlineKeyboardButton(
                            text = "🎁 Подарок к позиции",
                            callbackData = "vp_tpl:$venueId:GIFT_WITH_ITEM",
                        ),
                    ),
                    listOf(
                        InlineKeyboardButton(
                            text = "🖼 Баннер / афиша",
                            callbackData = "vp_tpl:$venueId:BANNER",
                        ),
                    ),
                    listOf(
                        InlineKeyboardButton(
                            text = "↩️ Назад",
                            callbackData = "vp_root:$venueId",
                        ),
                    ),
                ),
        )

    fun inlineVenuePromotionCreateProgressActions(venueId: Long): InlineKeyboardMarkup =
        InlineKeyboardMarkup(
            inlineKeyboard =
                listOf(
                    listOf(
                        InlineKeyboardButton(
                            text = "↩️ К акциям",
                            callbackData = "vp_root:$venueId",
                        ),
                    ),
                ),
        )

    fun inlineVenuePromotionEditPromptActions(
        venueId: Long,
        promotionId: Long,
    ): InlineKeyboardMarkup =
        InlineKeyboardMarkup(
            inlineKeyboard =
                listOf(
                    listOf(
                        InlineKeyboardButton(
                            text = "↩️ К акции",
                            callbackData = "vp_o:$venueId:$promotionId",
                        ),
                    ),
                ),
        )

    fun inlineVenuePromotionDetailActions(
        venueId: Long,
        promotionId: Long,
        status: String,
        descriptionText: String = "✏️ Описание",
        showTerms: Boolean = true,
        showRules: Boolean = true,
        showBannerMediaActions: Boolean = false,
        showPlacementRequest: Boolean = false,
    ): InlineKeyboardMarkup =
        InlineKeyboardMarkup(
            inlineKeyboard =
                buildList {
                    add(
                        listOf(
                            InlineKeyboardButton(
                                text = "✏️ Название",
                                callbackData = "vp_et:$venueId:$promotionId",
                            ),
                        ),
                    )
                    add(
                        listOf(
                            InlineKeyboardButton(
                                text = descriptionText,
                                callbackData = "vp_ed:$venueId:$promotionId",
                            ),
                        ),
                    )
                    if (showTerms) {
                        add(
                            listOf(
                                InlineKeyboardButton(
                                    text = "✏️ Условия",
                                    callbackData = "vp_er:$venueId:$promotionId",
                                ),
                            ),
                        )
                    }
                    if (showBannerMediaActions) {
                        add(
                            listOf(
                                InlineKeyboardButton(
                                    text = "🖼 Заменить изображение",
                                    callbackData = "vp_img:$venueId:$promotionId",
                                ),
                            ),
                        )
                        add(
                            listOf(
                                InlineKeyboardButton(
                                    text = "🗑 Удалить изображение",
                                    callbackData = "vp_img_del:$venueId:$promotionId",
                                ),
                            ),
                        )
                    }
                    if (showPlacementRequest) {
                        add(
                            listOf(
                                InlineKeyboardButton(
                                    text = "📌 Разместить как баннер",
                                    callbackData = "vp_place:$venueId:$promotionId",
                                ),
                            ),
                        )
                    }
                    if (showRules) {
                        add(
                            listOf(
                                InlineKeyboardButton(
                                    text = "⚙️ Правила акции",
                                    callbackData = "vpr_root:$venueId:$promotionId",
                                ),
                            ),
                        )
                    }
                    if (status == "ACTIVE") {
                        add(
                            listOf(
                                InlineKeyboardButton(
                                    text = "⏸ Приостановить",
                                    callbackData = "vp_off:$venueId:$promotionId",
                                ),
                            ),
                        )
                    } else {
                        add(
                            listOf(
                                InlineKeyboardButton(
                                    text = "▶️ Включить",
                                    callbackData = "vp_on:$venueId:$promotionId",
                                ),
                            ),
                        )
                    }
                    add(
                        listOf(
                            InlineKeyboardButton(
                                text = "🗄 Архивировать",
                                callbackData = "vp_arch:$venueId:$promotionId",
                            ),
                        ),
                    )
                    add(
                        listOf(
                            InlineKeyboardButton(
                                text = "↩️ К акциям",
                                callbackData = "vp_root:$venueId",
                            ),
                        ),
                    )
                },
        )

    fun inlineVenuePromotionArchivedDetailActions(venueId: Long): InlineKeyboardMarkup =
        InlineKeyboardMarkup(
            inlineKeyboard =
                listOf(
                    listOf(
                        InlineKeyboardButton(
                            text = "↩️ К архиву",
                            callbackData = "vp_archive_root:$venueId",
                        ),
                    ),
                ),
        )

    fun inlineVenuePromotionPlacementSurfaceActions(
        venueId: Long,
        promotionId: Long,
    ): InlineKeyboardMarkup =
        InlineKeyboardMarkup(
            inlineKeyboard =
                listOf(
                    listOf(
                        InlineKeyboardButton(
                            text = "🎁 Вверху общих акций",
                            callbackData = "vp_place_new:$venueId:$promotionId:G",
                        ),
                    ),
                    listOf(
                        InlineKeyboardButton(
                            text = "🎁 Вверху акций заведения",
                            callbackData = "vp_place_new:$venueId:$promotionId:V",
                        ),
                    ),
                    listOf(
                        InlineKeyboardButton(
                            text = "↩️ К акции",
                            callbackData = "vp_o:$venueId:$promotionId",
                        ),
                    ),
                ),
        )

    fun inlineVenuePromotionRulesRootActions(
        venueId: Long,
        promotionId: Long,
        rules: List<Pair<Long, String>>,
        showAddRule: Boolean = true,
    ): InlineKeyboardMarkup =
        InlineKeyboardMarkup(
            inlineKeyboard =
                buildList {
                    rules.forEach { (ruleId, label) ->
                        add(
                            listOf(
                                InlineKeyboardButton(
                                    text = label,
                                    callbackData = "vpr_o:$venueId:$promotionId:$ruleId",
                                ),
                            ),
                        )
                    }
                    if (showAddRule) {
                        add(
                            listOf(
                                InlineKeyboardButton(
                                    text = "➕ Добавить правило",
                                    callbackData = "vpr_add:$venueId:$promotionId",
                                ),
                            ),
                        )
                    }
                    add(
                        listOf(
                            InlineKeyboardButton(
                                text = "↩️ К акции",
                                callbackData = "vp_o:$venueId:$promotionId",
                            ),
                        ),
                    )
                },
        )

    fun inlineVenuePromotionRuleTargetTypeActions(
        venueId: Long,
        promotionId: Long,
        typeButtons: List<Pair<String, String>>,
    ): InlineKeyboardMarkup =
        InlineKeyboardMarkup(
            inlineKeyboard =
                buildList {
                    typeButtons.chunked(2).forEach { row ->
                        add(
                            row.map { (type, label) ->
                                InlineKeyboardButton(
                                    text = label,
                                    callbackData = "vpr_t:$venueId:$promotionId:$type",
                                )
                            },
                        )
                    }
                    add(
                        listOf(
                            InlineKeyboardButton(
                                text = "↩️ К правилам",
                                callbackData = "vpr_root:$venueId:$promotionId",
                            ),
                        ),
                    )
                },
        )

    fun inlineVenuePromotionRuleTargetEditActions(
        venueId: Long,
        promotionId: Long,
        ruleId: Long,
        typeButtons: List<Pair<String, String>>,
    ): InlineKeyboardMarkup =
        InlineKeyboardMarkup(
            inlineKeyboard =
                buildList {
                    typeButtons.chunked(2).forEach { row ->
                        add(
                            row.map { (type, label) ->
                                InlineKeyboardButton(
                                    text = label,
                                    callbackData = "vpr_tcat:$venueId:$promotionId:$ruleId:$type",
                                )
                            },
                        )
                    }
                    add(
                        listOf(
                            InlineKeyboardButton(
                                text = "↩️ К правилу",
                                callbackData = "vpr_o:$venueId:$promotionId:$ruleId",
                            ),
                        ),
                    )
                },
        )

    fun inlineVenuePromotionRuleTargetScopeCreateActions(
        venueId: Long,
        promotionId: Long,
        targetType: String,
        targetLabel: String,
    ): InlineKeyboardMarkup =
        InlineKeyboardMarkup(
            inlineKeyboard =
                listOf(
                    listOf(
                        InlineKeyboardButton(
                            text = "✅ Все позиции категории «$targetLabel»",
                            callbackData = "vpr_tall_new:$venueId:$promotionId:$targetType",
                        ),
                    ),
                    listOf(
                        InlineKeyboardButton(
                            text = "🎯 Выбрать отдельные позиции",
                            callbackData = "vpr_titems_new:$venueId:$promotionId:$targetType:0",
                        ),
                    ),
                    listOf(
                        InlineKeyboardButton(
                            text = "↩️ Назад",
                            callbackData = "vpr_add:$venueId:$promotionId",
                        ),
                    ),
                ),
        )

    fun inlineVenuePromotionRuleTargetScopeEditActions(
        venueId: Long,
        promotionId: Long,
        ruleId: Long,
        targetType: String,
        targetLabel: String,
    ): InlineKeyboardMarkup =
        InlineKeyboardMarkup(
            inlineKeyboard =
                listOf(
                    listOf(
                        InlineKeyboardButton(
                            text = "✅ Все позиции категории «$targetLabel»",
                            callbackData = "vpr_tall:$venueId:$promotionId:$ruleId:$targetType",
                        ),
                    ),
                    listOf(
                        InlineKeyboardButton(
                            text = "🎯 Выбрать отдельные позиции",
                            callbackData = "vpr_titems:$venueId:$promotionId:$ruleId:$targetType:0",
                        ),
                    ),
                    listOf(
                        InlineKeyboardButton(
                            text = "↩️ Назад",
                            callbackData = "vpr_tedit:$venueId:$promotionId:$ruleId",
                        ),
                    ),
                ),
        )

    fun inlineVenuePromotionRuleTargetItemsCreateActions(
        venueId: Long,
        promotionId: Long,
        targetType: String,
        page: Int,
        items: List<Pair<Long, String>>,
        selectedItemIds: Set<Long>,
        hasPreviousPage: Boolean,
        hasNextPage: Boolean,
    ): InlineKeyboardMarkup =
        InlineKeyboardMarkup(
            inlineKeyboard =
                buildList {
                    items.forEach { (itemId, name) ->
                        add(
                            listOf(
                                InlineKeyboardButton(
                                    text = "${if (itemId in selectedItemIds) "✅ " else ""}$name",
                                    callbackData = "vpr_itog_new:$venueId:$promotionId:$targetType:$itemId:$page",
                                ),
                            ),
                        )
                    }
                    addPromotionTargetPagination(
                        hasPreviousPage = hasPreviousPage,
                        hasNextPage = hasNextPage,
                        previousCallback = "vpr_titems_new:$venueId:$promotionId:$targetType:${page - 1}",
                        nextCallback = "vpr_titems_new:$venueId:$promotionId:$targetType:${page + 1}",
                    )
                    add(
                        listOf(
                            InlineKeyboardButton(
                                text = "✅ Готово",
                                callbackData = "vpr_idone_new:$venueId:$promotionId:$targetType",
                            ),
                        ),
                    )
                    add(
                        listOf(
                            InlineKeyboardButton(
                                text = "↩️ Назад",
                                callbackData = "vpr_t:$venueId:$promotionId:$targetType",
                            ),
                        ),
                    )
                },
        )

    fun inlineVenuePromotionRuleTargetItemsEditActions(
        venueId: Long,
        promotionId: Long,
        ruleId: Long,
        targetType: String,
        page: Int,
        items: List<Pair<Long, String>>,
        selectedItemIds: Set<Long>,
        hasPreviousPage: Boolean,
        hasNextPage: Boolean,
    ): InlineKeyboardMarkup =
        InlineKeyboardMarkup(
            inlineKeyboard =
                buildList {
                    items.forEach { (itemId, name) ->
                        add(
                            listOf(
                                InlineKeyboardButton(
                                    text = "${if (itemId in selectedItemIds) "✅ " else ""}$name",
                                    callbackData = "vpr_itog:$venueId:$promotionId:$ruleId:$targetType:$itemId:$page",
                                ),
                            ),
                        )
                    }
                    addPromotionTargetPagination(
                        hasPreviousPage = hasPreviousPage,
                        hasNextPage = hasNextPage,
                        previousCallback = "vpr_titems:$venueId:$promotionId:$ruleId:$targetType:${page - 1}",
                        nextCallback = "vpr_titems:$venueId:$promotionId:$ruleId:$targetType:${page + 1}",
                    )
                    add(
                        listOf(
                            InlineKeyboardButton(
                                text = "✅ Готово",
                                callbackData = "vpr_idone:$venueId:$promotionId:$ruleId:$targetType",
                            ),
                        ),
                    )
                    add(
                        listOf(
                            InlineKeyboardButton(
                                text = "↩️ Назад",
                                callbackData = "vpr_tcat:$venueId:$promotionId:$ruleId:$targetType",
                            ),
                        ),
                    )
                },
        )

    private fun MutableList<List<InlineKeyboardButton>>.addPromotionTargetPagination(
        hasPreviousPage: Boolean,
        hasNextPage: Boolean,
        previousCallback: String,
        nextCallback: String,
    ) {
        val row = mutableListOf<InlineKeyboardButton>()
        if (hasPreviousPage) {
            row += InlineKeyboardButton(text = "◀️ Назад", callbackData = previousCallback)
        }
        if (hasNextPage) {
            row += InlineKeyboardButton(text = "▶️ Далее", callbackData = nextCallback)
        }
        if (row.isNotEmpty()) {
            add(row)
        }
    }

    fun inlineVenueGiftTriggerTypeActions(
        venueId: Long,
        promotionId: Long,
        typeButtons: List<Pair<String, String>>,
    ): InlineKeyboardMarkup =
        InlineKeyboardMarkup(
            inlineKeyboard =
                buildList {
                    typeButtons.chunked(2).forEach { row ->
                        add(
                            row.map { (type, label) ->
                                InlineKeyboardButton(
                                    text = label,
                                    callbackData = "vpg_tcat:$venueId:$promotionId:$type",
                                )
                            },
                        )
                    }
                    add(
                        listOf(
                            InlineKeyboardButton(
                                text = "↩️ К акции",
                                callbackData = "vp_o:$venueId:$promotionId",
                            ),
                        ),
                    )
                },
        )

    fun inlineVenueGiftTriggerScopeActions(
        venueId: Long,
        promotionId: Long,
        targetType: String,
        targetLabel: String,
    ): InlineKeyboardMarkup =
        InlineKeyboardMarkup(
            inlineKeyboard =
                listOf(
                    listOf(
                        InlineKeyboardButton(
                            text = "✅ Все позиции категории «$targetLabel»",
                            callbackData = "vpg_tall:$venueId:$promotionId:$targetType",
                        ),
                    ),
                    listOf(
                        InlineKeyboardButton(
                            text = "🎯 Выбрать отдельные позиции",
                            callbackData = "vpg_titems:$venueId:$promotionId:$targetType:0",
                        ),
                    ),
                    listOf(
                        InlineKeyboardButton(
                            text = "↩️ Назад",
                            callbackData = "vpg_tedit:$venueId:$promotionId",
                        ),
                    ),
                ),
        )

    fun inlineVenueGiftTriggerItemsActions(
        venueId: Long,
        promotionId: Long,
        targetType: String,
        page: Int,
        items: List<Pair<Long, String>>,
        selectedItemIds: Set<Long>,
        hasPreviousPage: Boolean,
        hasNextPage: Boolean,
    ): InlineKeyboardMarkup =
        InlineKeyboardMarkup(
            inlineKeyboard =
                buildList {
                    items.forEach { (itemId, name) ->
                        add(
                            listOf(
                                InlineKeyboardButton(
                                    text = "${if (itemId in selectedItemIds) "✅ " else ""}$name",
                                    callbackData = "vpg_itog:$venueId:$promotionId:$targetType:$itemId:$page",
                                ),
                            ),
                        )
                    }
                    addPromotionTargetPagination(
                        hasPreviousPage = hasPreviousPage,
                        hasNextPage = hasNextPage,
                        previousCallback = "vpg_titems:$venueId:$promotionId:$targetType:${page - 1}",
                        nextCallback = "vpg_titems:$venueId:$promotionId:$targetType:${page + 1}",
                    )
                    add(
                        listOf(
                            InlineKeyboardButton(
                                text = "✅ Готово",
                                callbackData = "vpg_idone:$venueId:$promotionId:$targetType",
                            ),
                        ),
                    )
                    add(
                        listOf(
                            InlineKeyboardButton(
                                text = "↩️ Назад",
                                callbackData = "vpg_tcat:$venueId:$promotionId:$targetType",
                            ),
                        ),
                    )
                },
        )

    fun inlineVenueGiftRewardModeActions(
        venueId: Long,
        promotionId: Long,
    ): InlineKeyboardMarkup =
        InlineKeyboardMarkup(
            inlineKeyboard =
                listOf(
                    listOf(
                        InlineKeyboardButton(
                            text = "🎁 Конкретная позиция",
                            callbackData = "vpg_rf:$venueId:$promotionId",
                        ),
                    ),
                    listOf(
                        InlineKeyboardButton(
                            text = "🎁 На выбор из нескольких позиций",
                            callbackData = "vpg_rcmode:$venueId:$promotionId",
                        ),
                    ),
                    listOf(
                        InlineKeyboardButton(
                            text = "↩️ К акции",
                            callbackData = "vp_o:$venueId:$promotionId",
                        ),
                    ),
                ),
        )

    fun inlineVenueGiftRewardCategoryActions(
        venueId: Long,
        promotionId: Long,
        typeButtons: List<Pair<String, String>>,
    ): InlineKeyboardMarkup =
        InlineKeyboardMarkup(
            inlineKeyboard =
                buildList {
                    typeButtons.chunked(2).forEach { row ->
                        add(
                            row.map { (type, label) ->
                                InlineKeyboardButton(
                                    text = label,
                                    callbackData = "vpg_rcat:$venueId:$promotionId:$type:0",
                                )
                            },
                        )
                    }
                    add(
                        listOf(
                            InlineKeyboardButton(
                                text = "↩️ К акции",
                                callbackData = "vp_o:$venueId:$promotionId",
                            ),
                        ),
                    )
                },
        )

    fun inlineVenueGiftRewardChoiceCategoryActions(
        venueId: Long,
        promotionId: Long,
        typeButtons: List<Pair<String, String>>,
    ): InlineKeyboardMarkup =
        InlineKeyboardMarkup(
            inlineKeyboard =
                buildList {
                    typeButtons.chunked(2).forEach { row ->
                        add(
                            row.map { (type, label) ->
                                InlineKeyboardButton(
                                    text = label,
                                    callbackData = "vpg_rcpick:$venueId:$promotionId:$type:0",
                                )
                            },
                        )
                    }
                    add(
                        listOf(
                            InlineKeyboardButton(
                                text = "↩️ Назад",
                                callbackData = "vpg_rew:$venueId:$promotionId",
                            ),
                        ),
                    )
                },
        )

    fun inlineVenueGiftRewardItemsActions(
        venueId: Long,
        promotionId: Long,
        rewardType: String,
        page: Int,
        items: List<Pair<Long, String>>,
        hasPreviousPage: Boolean,
        hasNextPage: Boolean,
    ): InlineKeyboardMarkup =
        InlineKeyboardMarkup(
            inlineKeyboard =
                buildList {
                    items.forEach { (itemId, name) ->
                        add(
                            listOf(
                                InlineKeyboardButton(
                                    text = name,
                                    callbackData = "vpg_ritem:$venueId:$promotionId:$rewardType:$itemId:$page",
                                ),
                            ),
                        )
                    }
                    addPromotionTargetPagination(
                        hasPreviousPage = hasPreviousPage,
                        hasNextPage = hasNextPage,
                        previousCallback = "vpg_rcat:$venueId:$promotionId:$rewardType:${page - 1}",
                        nextCallback = "vpg_rcat:$venueId:$promotionId:$rewardType:${page + 1}",
                    )
                    add(
                        listOf(
                            InlineKeyboardButton(
                                text = "↩️ Назад",
                                callbackData = "vpg_rew:$venueId:$promotionId",
                            ),
                        ),
                    )
                },
        )

    fun inlineVenueGiftRewardChoiceItemsActions(
        venueId: Long,
        promotionId: Long,
        rewardType: String,
        page: Int,
        items: List<Pair<Long, String>>,
        selectedItemIds: Set<Long>,
        hasPreviousPage: Boolean,
        hasNextPage: Boolean,
    ): InlineKeyboardMarkup =
        InlineKeyboardMarkup(
            inlineKeyboard =
                buildList {
                    items.forEach { (itemId, name) ->
                        add(
                            listOf(
                                InlineKeyboardButton(
                                    text = if (itemId in selectedItemIds) "✅ $name" else name,
                                    callbackData = "vpg_rcitog:$venueId:$promotionId:$rewardType:$itemId:$page",
                                ),
                            ),
                        )
                    }
                    addPromotionTargetPagination(
                        hasPreviousPage = hasPreviousPage,
                        hasNextPage = hasNextPage,
                        previousCallback = "vpg_rcpick:$venueId:$promotionId:$rewardType:${page - 1}",
                        nextCallback = "vpg_rcpick:$venueId:$promotionId:$rewardType:${page + 1}",
                    )
                    add(
                        listOf(
                            InlineKeyboardButton(
                                text = "✅ Готово",
                                callbackData = "vpg_rcdone:$venueId:$promotionId:$rewardType",
                            ),
                        ),
                    )
                    add(
                        listOf(
                            InlineKeyboardButton(
                                text = "↩️ Назад",
                                callbackData = "vpg_rew:$venueId:$promotionId",
                            ),
                        ),
                    )
                },
        )

    fun inlineVenuePromotionRulePercentInputActions(
        venueId: Long,
        promotionId: Long,
    ): InlineKeyboardMarkup =
        InlineKeyboardMarkup(
            inlineKeyboard =
                listOf(
                    listOf(
                        InlineKeyboardButton(
                            text = "↩️ К правилам",
                            callbackData = "vpr_root:$venueId:$promotionId",
                        ),
                    ),
                ),
        )

    fun inlineVenuePromotionRulePercentEditInputActions(
        venueId: Long,
        promotionId: Long,
        ruleId: Long,
    ): InlineKeyboardMarkup =
        InlineKeyboardMarkup(
            inlineKeyboard =
                listOf(
                    listOf(
                        InlineKeyboardButton(
                            text = "↩️ К правилу",
                            callbackData = "vpr_o:$venueId:$promotionId:$ruleId",
                        ),
                    ),
                ),
        )

    fun inlineVenuePromotionRuleTimeInputActions(
        venueId: Long,
        promotionId: Long,
        ruleId: Long,
    ): InlineKeyboardMarkup =
        InlineKeyboardMarkup(
            inlineKeyboard =
                listOf(
                    listOf(
                        InlineKeyboardButton(
                            text = "↩️ К правилу",
                            callbackData = "vpr_o:$venueId:$promotionId:$ruleId",
                        ),
                    ),
                ),
        )

    fun inlineVenuePromotionRuleDetailActions(
        venueId: Long,
        promotionId: Long,
        ruleId: Long,
        status: String,
        ruleType: String = "HAPPY_HOURS_PERCENT",
    ): InlineKeyboardMarkup =
        InlineKeyboardMarkup(
            inlineKeyboard =
                buildList {
                    val isGiftRule = ruleType == "GIFT_WITH_ITEM"
                    add(
                        listOf(
                            InlineKeyboardButton(
                                text = if (isGiftRule) "🎯 Условие подарка" else "🎯 На что действует",
                                callbackData =
                                    if (isGiftRule) {
                                        "vpg_tedit:$venueId:$promotionId"
                                    } else {
                                        "vpr_tedit:$venueId:$promotionId:$ruleId"
                                    },
                            ),
                        ),
                    )
                    if (isGiftRule) {
                        add(
                            listOf(
                                InlineKeyboardButton(
                                    text = "🎁 Подарок",
                                    callbackData = "vpg_rew:$venueId:$promotionId",
                                ),
                            ),
                        )
                    } else {
                        add(
                            listOf(
                                InlineKeyboardButton(
                                    text = "✏️ Процент скидки",
                                    callbackData = "vpr_pct:$venueId:$promotionId:$ruleId",
                                ),
                            ),
                        )
                    }
                    add(
                        listOf(
                            InlineKeyboardButton(
                                text = "🕒 Расписание",
                                callbackData = "vpr_s:$venueId:$promotionId:$ruleId",
                            ),
                        ),
                    )
                    add(
                        listOf(
                            InlineKeyboardButton(
                                text = "⚖️ Совместимость акций",
                                callbackData = "vpr_cmp:$venueId:$promotionId:$ruleId",
                            ),
                        ),
                    )
                    add(
                        listOf(
                            InlineKeyboardButton(
                                text = "🗑 Удалить правило",
                                callbackData = "vpr_del:$venueId:$promotionId:$ruleId",
                            ),
                        ),
                    )
                    add(
                        listOf(
                            InlineKeyboardButton(
                                text = "↩️ К правилам",
                                callbackData = "vpr_root:$venueId:$promotionId",
                            ),
                        ),
                    )
                },
        )

    fun inlineVenuePromotionRuleDeleteConfirmActions(
        venueId: Long,
        promotionId: Long,
        ruleId: Long,
    ): InlineKeyboardMarkup =
        InlineKeyboardMarkup(
            inlineKeyboard =
                listOf(
                    listOf(
                        InlineKeyboardButton(
                            text = "✅ Да, удалить правило",
                            callbackData = "vpr_del_yes:$venueId:$promotionId:$ruleId",
                        ),
                    ),
                    listOf(
                        InlineKeyboardButton(
                            text = "↩️ Назад к правилу",
                            callbackData = "vpr_o:$venueId:$promotionId:$ruleId",
                        ),
                    ),
                ),
        )

    fun inlineVenuePromotionRuleCompatibilityActions(
        venueId: Long,
        promotionId: Long,
        ruleId: Long,
        stackable: Boolean = false,
    ): InlineKeyboardMarkup =
        InlineKeyboardMarkup(
            inlineKeyboard =
                listOf(
                    listOf(
                        InlineKeyboardButton(
                            text =
                                if (stackable) {
                                    "Не суммировать с похожими акциями"
                                } else {
                                    "✅ Не суммировать с похожими акциями"
                                },
                            callbackData = "vpr_cmp_set:$venueId:$promotionId:$ruleId:0",
                        ),
                    ),
                    listOf(
                        InlineKeyboardButton(
                            text =
                                if (stackable) {
                                    "✅ Можно суммировать с другими акциями"
                                } else {
                                    "Можно суммировать с другими акциями"
                                },
                            callbackData = "vpr_cmp_set:$venueId:$promotionId:$ruleId:1",
                        ),
                    ),
                    listOf(
                        InlineKeyboardButton(
                            text = "↩️ К правилу",
                            callbackData = "vpr_o:$venueId:$promotionId:$ruleId",
                        ),
                    ),
                ),
        )

    fun inlineVenuePromotionRuleScheduleActions(
        venueId: Long,
        promotionId: Long,
        ruleId: Long,
    ): InlineKeyboardMarkup =
        InlineKeyboardMarkup(
            inlineKeyboard =
                listOf(
                    listOf(
                        InlineKeyboardButton(
                            text = "✅ Всегда",
                            callbackData = "vpr_sa:$venueId:$promotionId:$ruleId",
                        ),
                    ),
                    listOf(
                        InlineKeyboardButton(
                            text = "📅 Выбрать дни",
                            callbackData = "vpr_sd:$venueId:$promotionId:$ruleId",
                        ),
                    ),
                    listOf(
                        InlineKeyboardButton(
                            text = "🕒 Время проведения",
                            callbackData = "vpr_st:$venueId:$promotionId:$ruleId",
                        ),
                    ),
                    listOf(
                        InlineKeyboardButton(
                            text = "↩️ К правилу",
                            callbackData = "vpr_o:$venueId:$promotionId:$ruleId",
                        ),
                    ),
                ),
        )

    fun inlineVenuePromotionRuleScheduleDaysActions(
        venueId: Long,
        promotionId: Long,
        ruleId: Long,
        selectedDays: Set<Int>,
    ): InlineKeyboardMarkup {
        val labels =
            listOf(
                1 to "Пн",
                2 to "Вт",
                3 to "Ср",
                4 to "Чт",
                5 to "Пт",
                6 to "Сб",
                7 to "Вс",
            )
        return InlineKeyboardMarkup(
            inlineKeyboard =
                buildList {
                    labels.chunked(4).forEach { row ->
                        add(
                            row.map { (day, label) ->
                                InlineKeyboardButton(
                                    text = if (day in selectedDays) "✅ $label" else label,
                                    callbackData = "vpr_d:$venueId:$promotionId:$ruleId:$day",
                                )
                            },
                        )
                    }
                    add(
                        listOf(
                            InlineKeyboardButton(
                                text = "✅ Готово",
                                callbackData = "vpr_dd:$venueId:$promotionId:$ruleId",
                            ),
                        ),
                    )
                    add(
                        listOf(
                            InlineKeyboardButton(
                                text = "↩️ Назад",
                                callbackData = "vpr_s:$venueId:$promotionId:$ruleId",
                            ),
                        ),
                    )
                },
        )
    }

    fun inlineVenuePromotionTermsActions(
        venueId: Long,
        promotionId: Long? = null,
    ): InlineKeyboardMarkup =
        InlineKeyboardMarkup(
            inlineKeyboard =
                listOf(
                    listOf(
                        InlineKeyboardButton(
                            text = "Пропустить",
                            callbackData = "vp_terms_skip:$venueId",
                        ),
                    ),
                    listOf(
                        InlineKeyboardButton(
                            text = if (promotionId == null) "↩️ К акциям" else "↩️ К акции",
                            callbackData = promotionId?.let { "vp_o:$venueId:$it" } ?: "vp_root:$venueId",
                        ),
                    ),
                ),
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

    fun inlineVenueStaffBookingActions(
        venueId: Long,
        bookingId: Long,
        canConfirm: Boolean,
        canCancel: Boolean = true,
        canMarkVisit: Boolean = true,
        canMessageGuest: Boolean = true,
    ): InlineKeyboardMarkup =
        InlineKeyboardMarkup(
            inlineKeyboard =
                buildList {
                    if (canConfirm) {
                        add(
                            listOf(
                                InlineKeyboardButton(
                                    text = "✅ Подтвердить",
                                    callbackData = "staff_booking_confirm:$venueId:$bookingId",
                                ),
                            ),
                        )
                    }
                    if (canMarkVisit) {
                        add(
                            listOf(
                                InlineKeyboardButton(
                                    text = "✅ Гость пришёл",
                                    callbackData = "staff_booking_seated_ask:$venueId:$bookingId",
                                ),
                                InlineKeyboardButton(
                                    text = "🚫 Не пришёл",
                                    callbackData = "staff_booking_noshow_ask:$venueId:$bookingId",
                                ),
                            ),
                        )
                    }
                    if (canMessageGuest) {
                        add(
                            listOf(
                                InlineKeyboardButton(
                                    text = "✉️ Написать гостю",
                                    callbackData = "staff_booking_message:$venueId:$bookingId",
                                ),
                            ),
                        )
                    }
                    if (canCancel) {
                        add(
                            listOf(
                                InlineKeyboardButton(
                                    text = "❌ Отменить бронь",
                                    callbackData = "staff_booking_cancel_ask:$venueId:$bookingId",
                                ),
                            ),
                        )
                    }
                },
        )

    fun inlineVenueStaffBookingVisitConfirmActions(
        venueId: Long,
        bookingId: Long,
        status: String,
    ): InlineKeyboardMarkup {
        val normalized = status.lowercase()
        val yesCallback =
            when (normalized) {
                "seated" -> "staff_booking_seated_yes:$venueId:$bookingId"
                "noshow" -> "staff_booking_noshow_yes:$venueId:$bookingId"
                else -> "staff_booking_cancel_back:$venueId:$bookingId"
            }
        return InlineKeyboardMarkup(
            inlineKeyboard =
                listOf(
                    listOf(
                        InlineKeyboardButton(
                            text = "✅ Да, отметить",
                            callbackData = yesCallback,
                        ),
                    ),
                    listOf(
                        InlineKeyboardButton(
                            text = "↩️ Назад",
                            callbackData = "staff_booking_cancel_back:$venueId:$bookingId",
                        ),
                    ),
                ),
        )
    }

    fun inlineVenueStaffBookingCancelConfirmActions(
        venueId: Long,
        bookingId: Long,
        reasonToken: String,
    ): InlineKeyboardMarkup =
        InlineKeyboardMarkup(
            inlineKeyboard =
                listOf(
                    listOf(
                        InlineKeyboardButton(
                            text = "✅ Да, отменить бронь",
                            callbackData = "sbc_y:$venueId:$bookingId:$reasonToken",
                        ),
                    ),
                    listOf(
                        InlineKeyboardButton(
                            text = "↩️ Назад",
                            callbackData = "staff_booking_cancel_ask:$venueId:$bookingId",
                        ),
                    ),
                ),
        )

    fun inlineVenueStaffBookingCancelReasonActions(
        venueId: Long,
        bookingId: Long,
    ): InlineKeyboardMarkup =
        InlineKeyboardMarkup(
            inlineKeyboard =
                listOf(
                    listOf(
                        InlineKeyboardButton(
                            text = "Нет мест",
                            callbackData = "sbc_r:$venueId:$bookingId:no_seats",
                        ),
                    ),
                    listOf(
                        InlineKeyboardButton(
                            text = "Заведение закрыто",
                            callbackData = "sbc_r:$venueId:$bookingId:closed",
                        ),
                    ),
                    listOf(
                        InlineKeyboardButton(
                            text = "Ошибка в бронировании",
                            callbackData = "sbc_r:$venueId:$bookingId:booking_error",
                        ),
                    ),
                    listOf(
                        InlineKeyboardButton(
                            text = "Другое",
                            callbackData = "sbc_r:$venueId:$bookingId:other",
                        ),
                    ),
                    listOf(
                        InlineKeyboardButton(
                            text = "↩️ Назад",
                            callbackData = "staff_booking_cancel_back:$venueId:$bookingId",
                        ),
                    ),
                ),
        )

    fun inlineGuestBookingReplyActions(
        venueId: Long,
        bookingId: Long,
    ): InlineKeyboardMarkup =
        InlineKeyboardMarkup(
            inlineKeyboard =
                listOf(
                    listOf(
                        InlineKeyboardButton(
                            text = "↩️ Ответить",
                            callbackData = "guest_booking_reply:$venueId:$bookingId",
                        ),
                    ),
                ),
        )

    fun inlineBookingReminderCancelConfirm(bookingId: Long): InlineKeyboardMarkup =
        InlineKeyboardMarkup(
            inlineKeyboard =
                listOf(
                    listOf(
                        InlineKeyboardButton(
                            text = "✅ Да, отменить",
                            callbackData = "br_cancel_yes:$bookingId",
                        ),
                    ),
                    listOf(
                        InlineKeyboardButton(
                            text = "↩️ Назад",
                            callbackData = "br_back:$bookingId",
                        ),
                    ),
                ),
        )

    fun inlineBookingReminderActions(bookingId: Long): InlineKeyboardMarkup =
        InlineKeyboardMarkup(
            inlineKeyboard =
                listOf(
                    listOf(
                        InlineKeyboardButton(
                            text = "✅ Приду",
                            callbackData = "br_ok:$bookingId",
                        ),
                    ),
                    listOf(
                        InlineKeyboardButton(
                            text = "❌ Отменить",
                            callbackData = "br_cancel:$bookingId",
                        ),
                        InlineKeyboardButton(
                            text = "✉️ Написать заведению",
                            callbackData = "br_msg:$bookingId",
                        ),
                    ),
                ),
        )

    fun inlineVisitFeedbackActions(visitId: Long): InlineKeyboardMarkup =
        InlineKeyboardMarkup(
            inlineKeyboard =
                listOf(
                    (1..5).map { rating ->
                        InlineKeyboardButton(
                            text = "⭐ $rating",
                            callbackData = "fb_r:$visitId:$rating",
                        )
                    },
                    listOf(
                        InlineKeyboardButton(
                            text = "💬 Оставить комментарий",
                            callbackData = "fb_c:$visitId",
                        ),
                    ),
                    listOf(
                        InlineKeyboardButton(
                            text = "Пропустить",
                            callbackData = "fb_skip:$visitId",
                        ),
                    ),
                ),
        )

    fun inlineVisitFeedbackCommentPrompt(visitId: Long): InlineKeyboardMarkup =
        InlineKeyboardMarkup(
            inlineKeyboard =
                listOf(
                    listOf(
                        InlineKeyboardButton(
                            text = "💬 Оставить комментарий",
                            callbackData = "fb_c:$visitId",
                        ),
                    ),
                    listOf(
                        InlineKeyboardButton(
                            text = "Без комментария",
                            callbackData = "fb_skip:$visitId",
                        ),
                    ),
                ),
        )

    fun inlineVisitFeedbackRatingOnlyActions(visitId: Long): InlineKeyboardMarkup =
        InlineKeyboardMarkup(
            inlineKeyboard =
                listOf(
                    (1..5).map { rating ->
                        InlineKeyboardButton(
                            text = "⭐ $rating",
                            callbackData = "fb_r:$visitId:$rating",
                        )
                    },
                ),
        )

    fun inlinePublicReviewCtaActions(
        venueId: Long,
        publicReviewUrl: String,
    ): InlineKeyboardMarkup =
        InlineKeyboardMarkup(
            inlineKeyboard =
                listOf(
                    listOf(
                        InlineKeyboardButton(
                            text = "⭐ Оставить отзыв",
                            url = publicReviewUrl,
                        ),
                    ),
                    listOf(
                        InlineKeyboardButton(
                            text = "✅ Уже оставил отзыв",
                            callbackData = "pubrev_done:$venueId",
                        ),
                    ),
                ),
        )

    fun inlineVisitFeedbackStaffReply(feedbackId: Long): InlineKeyboardMarkup =
        InlineKeyboardMarkup(
            inlineKeyboard =
                listOf(
                    listOf(
                        InlineKeyboardButton(
                            text = "💬 Ответить гостю",
                            callbackData = "fb_reply:$feedbackId",
                        ),
                    ),
                ),
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
                    listOf(
                        InlineKeyboardButton(
                            text = "↩️ Назад",
                            callbackData = "bot_catalog_venue_book_time:$venueId:$isoDate:$time",
                        ),
                    ),
                ),
        )
    }

    fun inlineBotVenueBookingCommentInputActions(
        venueId: Long,
        isoDate: String,
        time: String,
    ): InlineKeyboardMarkup =
        InlineKeyboardMarkup(
            inlineKeyboard =
                listOf(
                    listOf(
                        InlineKeyboardButton(
                            text = "↩️ Назад",
                            callbackData = "bot_catalog_venue_book_time:$venueId:$isoDate:$time",
                        ),
                    ),
                ),
        )

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
                    listOf(
                        InlineKeyboardButton(
                            text = "⬅️ К заявкам",
                            callbackData = "owner_venue_requests_back",
                        ),
                    ),
                ),
        )

    fun inlineOwnerVenueConnectionApprovedActions(
        requestId: Long,
        canCreateVenue: Boolean = false,
    ): InlineKeyboardMarkup =
        InlineKeyboardMarkup(
            inlineKeyboard =
                buildList {
                    if (canCreateVenue) {
                        add(
                            listOf(
                                InlineKeyboardButton(
                                    text = "✅ Создать и передать владельцу",
                                    callbackData = "owner_venue_create_from_request:$requestId",
                                ),
                            ),
                        )
                    }
                    add(
                        listOf(
                            InlineKeyboardButton(
                                text = if (canCreateVenue) "✏️ Изменить условия" else "⚙️ Коммерческие условия",
                                callbackData = "owner_venue_terms_open:$requestId",
                            ),
                        ),
                    )
                    add(
                        listOf(
                            InlineKeyboardButton(
                                text = "🧹 Закрыть без создания",
                                callbackData = "owner_venue_connect_close:$requestId",
                            ),
                        ),
                    )
                    add(
                        listOf(
                            InlineKeyboardButton(
                                text = "⬅️ К заявкам",
                                callbackData = "owner_venue_requests_back",
                            ),
                        ),
                    )
                },
        )

    fun inlineOwnerVenueCommercialZeroPriceConfirmActions(requestId: Long): InlineKeyboardMarkup =
        InlineKeyboardMarkup(
            inlineKeyboard =
                listOf(
                    listOf(
                        InlineKeyboardButton(
                            text = "✅ Да, бесплатно",
                            callbackData = "owner_venue_terms_zero_price:$requestId:confirm",
                        ),
                    ),
                    listOf(
                        InlineKeyboardButton(
                            text = "✏️ Ввести другую цену",
                            callbackData = "owner_venue_terms_open:$requestId",
                        ),
                    ),
                    listOf(
                        InlineKeyboardButton(text = "⬅️ К заявкам", callbackData = "owner_venue_requests_back"),
                    ),
                ),
        )

    fun inlineOwnerVenueCommercialTrialActions(requestId: Long): InlineKeyboardMarkup =
        InlineKeyboardMarkup(
            inlineKeyboard =
                listOf(
                    listOf(
                        InlineKeyboardButton(
                            text = "Без trial",
                            callbackData = "owner_venue_terms_trial:$requestId:none",
                        ),
                        InlineKeyboardButton(text = "7 дней", callbackData = "owner_venue_terms_trial:$requestId:7d"),
                    ),
                    listOf(
                        InlineKeyboardButton(text = "14 дней", callbackData = "owner_venue_terms_trial:$requestId:14d"),
                        InlineKeyboardButton(text = "1 месяц", callbackData = "owner_venue_terms_trial:$requestId:1m"),
                    ),
                    listOf(
                        InlineKeyboardButton(text = "3 месяца", callbackData = "owner_venue_terms_trial:$requestId:3m"),
                        InlineKeyboardButton(
                            text = "Другая дата окончания",
                            callbackData = "owner_venue_terms_trial:$requestId:custom",
                        ),
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
                        InlineKeyboardButton(
                            text = "Не задавать",
                            callbackData = "owner_venue_terms_future:$requestId:skip",
                        ),
                        InlineKeyboardButton(
                            text = "Задать будущую стоимость",
                            callbackData = "owner_venue_terms_future:$requestId:set",
                        ),
                    ),
                    listOf(
                        InlineKeyboardButton(text = "⬅️ К заявкам", callbackData = "owner_venue_requests_back"),
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
                        InlineKeyboardButton(
                            text = "💬 Настраивать в боте",
                            callbackData = "owner_venue_onboarding_entry",
                        ),
                    ),
                    listOf(
                        webAppUrl?.let { url ->
                            InlineKeyboardButton(
                                text = "📱 Открыть Mini App",
                                webApp = WebAppInfo(buildWebAppUrl(url, mapOf("mode" to "venue"))),
                            )
                        } ?: InlineKeyboardButton(
                            text = "📱 Mini App не настроен",
                            callbackData = "owner_venue_onboarding_miniapp_unavailable",
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
                            text = "🏙 Город",
                            callbackData = "owner_venue_field:$venueId:city",
                        ),
                    ),
                    listOf(
                        InlineKeyboardButton(
                            text = "📍 Адрес",
                            callbackData = "owner_venue_field:$venueId:address",
                        ),
                        InlineKeyboardButton(
                            text = "☎️ Контакт",
                            callbackData = "owner_venue_field:$venueId:contact",
                        ),
                    ),
                    listOf(
                        InlineKeyboardButton(
                            text = "📝 Краткое описание",
                            callbackData = "owner_venue_field:$venueId:card_description",
                        ),
                    ),
                    listOf(
                        InlineKeyboardButton(
                            text = "🕒 Часы работы",
                            callbackData = "owner_venue_field:$venueId:hours",
                        ),
                        InlineKeyboardButton(
                            text = "ℹ️ О заведении / Правила",
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
                    listOf(
                        InlineKeyboardButton(
                            text = "↩️ Назад к настройке",
                            callbackData = "owner_venue_hub_setup:$venueId",
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
                            text = "🏙 Город",
                            callbackData = "owner_venue_field:$venueId:city",
                        ),
                    ),
                    listOf(
                        InlineKeyboardButton(
                            text = "📍 Адрес",
                            callbackData = "owner_venue_field:$venueId:address",
                        ),
                        InlineKeyboardButton(
                            text = "☎️ Контакт",
                            callbackData = "owner_venue_field:$venueId:contact",
                        ),
                    ),
                    listOf(
                        InlineKeyboardButton(
                            text = "📝 Краткое описание",
                            callbackData = "owner_venue_field:$venueId:card_description",
                        ),
                    ),
                    listOf(
                        InlineKeyboardButton(
                            text = "🕒 Часы работы",
                            callbackData = "owner_venue_field:$venueId:hours",
                        ),
                        InlineKeyboardButton(
                            text = "ℹ️ О заведении / Правила",
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
                            text = "↩️ Назад",
                            callbackData = "staff_venue_menu_back",
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
                    add(
                        listOf(
                            InlineKeyboardButton(
                                text = "↩️ Назад к настройке",
                                callbackData = "owner_venue_hub_setup:$venueId",
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
                    listOf(
                        InlineKeyboardButton(
                            text = "↩️ Назад к настройке",
                            callbackData = "owner_venue_hub_setup:$venueId",
                        ),
                    ),
                ),
        )

    fun inlineVenueOwnerStaffRootActions(
        venueId: Long,
        editableMembers: List<Pair<Long, String>> = emptyList(),
        removableMembers: List<Pair<Long, String>> = emptyList(),
    ): InlineKeyboardMarkup =
        InlineKeyboardMarkup(
            inlineKeyboard =
                buildList {
                    editableMembers.forEach { (userId, label) ->
                        add(
                            listOf(
                                InlineKeyboardButton(
                                    text = "✏️ Изменить роль: $label",
                                    callbackData = "owner_venue_staff_role_change_prompt:$venueId:$userId",
                                ),
                            ),
                        )
                    }
                    removableMembers.forEach { (userId, label) ->
                        add(
                            listOf(
                                InlineKeyboardButton(
                                    text = "🗑 Удалить доступ: $label",
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
                    add(
                        listOf(
                            InlineKeyboardButton(
                                text = "↩️ Назад к настройке",
                                callbackData = "owner_venue_hub_setup:$venueId",
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

    fun inlineVenueOwnerStaffRoleChangeActions(
        venueId: Long,
        userId: Long,
        targetRoleKeys: Set<String>,
    ): InlineKeyboardMarkup =
        InlineKeyboardMarkup(
            inlineKeyboard =
                buildList {
                    if ("manager" in targetRoleKeys) {
                        add(
                            listOf(
                                InlineKeyboardButton(
                                    text = "Сделать Manager",
                                    callbackData = "owner_venue_staff_role_change_select:$venueId:$userId:manager",
                                ),
                            ),
                        )
                    }
                    if ("staff" in targetRoleKeys) {
                        add(
                            listOf(
                                InlineKeyboardButton(
                                    text = "Сделать Staff / Оператор смены",
                                    callbackData = "owner_venue_staff_role_change_select:$venueId:$userId:staff",
                                ),
                            ),
                        )
                    }
                    add(
                        listOf(
                            InlineKeyboardButton(
                                text = "⬅️ К персоналу",
                                callbackData = "owner_venue_staff_root:$venueId",
                            ),
                        ),
                    )
                },
        )

    fun inlineVenueOwnerStaffRoleChooserActions(
        venueId: Long,
        allowedRoleKeys: Set<String> = setOf("manager", "staff"),
    ): InlineKeyboardMarkup =
        InlineKeyboardMarkup(
            inlineKeyboard =
                buildList {
                    if ("owner" in allowedRoleKeys) {
                        add(
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
                        )
                    }
                    if ("manager" in allowedRoleKeys) {
                        add(
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
                        )
                    }
                    if ("staff" in allowedRoleKeys) {
                        add(
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
                        )
                    }
                    add(
                        listOf(
                            InlineKeyboardButton(
                                text = "⬅️ К персоналу",
                                callbackData = "owner_venue_staff_root:$venueId",
                            ),
                        ),
                    )
                },
        )

    fun inlineVenueOwnerStaffRoleInfoActions(
        venueId: Long,
        roleKey: String,
        canSelect: Boolean = true,
    ): InlineKeyboardMarkup {
        val roleTitle = venueOwnerStaffRoleTitle(roleKey)
        return InlineKeyboardMarkup(
            inlineKeyboard =
                buildList {
                    if (canSelect) {
                        add(
                            listOf(
                                InlineKeyboardButton(
                                    text = "✅ Выбрать роль $roleTitle",
                                    callbackData = "owner_venue_staff_role_select:$venueId:$roleKey",
                                ),
                            ),
                        )
                    }
                    add(
                        listOf(
                            InlineKeyboardButton(
                                text = "⬅️ К выбору роли",
                                callbackData = "owner_venue_staff_add:$venueId",
                            ),
                        ),
                    )
                    add(
                        listOf(
                            InlineKeyboardButton(
                                text = "⬅️ К персоналу",
                                callbackData = "owner_venue_staff_root:$venueId",
                            ),
                        ),
                    )
                },
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
        isActive: Boolean = true,
        canRotateQr: Boolean = true,
    ): InlineKeyboardMarkup =
        InlineKeyboardMarkup(
            inlineKeyboard =
                buildList {
                    add(
                        listOf(
                            InlineKeyboardButton(
                                text = "✏️ Изменить номер",
                                callbackData = "owner_venue_tables_table_rename:$venueId:$tableId",
                            ),
                        ),
                    )
                    add(
                        listOf(
                            InlineKeyboardButton(
                                text = "👥 Изменить вместимость",
                                callbackData = "owner_venue_tables_table_capacity:$venueId:$tableId",
                            ),
                        ),
                    )
                    add(
                        listOf(
                            InlineKeyboardButton(
                                text = "🧾 Показать QR",
                                callbackData = "owner_venue_tables_qr_table:$venueId:$tableId",
                            ),
                        ),
                    )
                    if (canRotateQr) {
                        add(
                            listOf(
                                InlineKeyboardButton(
                                    text = "🔁 Перевыпустить QR",
                                    callbackData = "owner_venue_tables_qr_rotate:$venueId:$tableId",
                                ),
                            ),
                        )
                    }
                    add(
                        listOf(
                            InlineKeyboardButton(
                                text = if (isActive) "🚫 Отключить стол" else "✅ Включить стол",
                                callbackData = "owner_venue_tables_table_toggle:$venueId:$tableId",
                            ),
                        ),
                    )
                    add(
                        listOf(
                            InlineKeyboardButton(
                                text = "⬅️ К списку столов",
                                callbackData = "owner_venue_tables_list:$venueId",
                            ),
                        ),
                    )
                },
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
                                text = "🏷 Тип раздела",
                                callbackData = "omt_c:$venueId:$sectionId",
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
                    if (showFlavorProfile) {
                        add(
                            listOf(
                                InlineKeyboardButton(
                                    text = "🍓 Вкусы / варианты",
                                    callbackData = "owner_venue_order_menu_item_flavors:$venueId:$sectionId:$itemId",
                                ),
                            ),
                        )
                    }
                    add(
                        listOf(
                            InlineKeyboardButton(
                                text = "💰 Цена",
                                callbackData = "owner_venue_order_menu_item_price:$venueId:$sectionId:$itemId",
                            ),
                        ),
                    )
                    add(
                        listOf(
                            InlineKeyboardButton(
                                text = "✏️ Название",
                                callbackData = "owner_venue_order_menu_item_rename:$venueId:$sectionId:$itemId",
                            ),
                        ),
                    )
                    add(
                        listOf(
                            InlineKeyboardButton(
                                text = if (isAvailable) "🚫 Наличие / стоп-лист" else "✅ Наличие / стоп-лист",
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
                                text = "⚙️ Дополнительно",
                                callbackData = "owner_venue_order_menu_item_more:$venueId:$sectionId:$itemId",
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

    fun inlineVenueOwnerOrderMenuItemAdvancedActions(
        venueId: Long,
        sectionId: Long,
        itemId: Long,
    ): InlineKeyboardMarkup =
        InlineKeyboardMarkup(
            inlineKeyboard =
                listOf(
                    listOf(
                        InlineKeyboardButton(
                            text = "🏷 Тип позиции",
                            callbackData = "omt_i:$venueId:$sectionId:$itemId",
                        ),
                    ),
                    listOf(
                        InlineKeyboardButton(
                            text = "↩️ Назад к позиции",
                            callbackData = "owner_venue_order_menu_item:$venueId:$sectionId:$itemId",
                        ),
                    ),
                ),
        )

    fun inlineVenueOwnerOrderMenuCategoryTypeActions(
        venueId: Long,
        sectionId: Long,
        typeButtons: List<Pair<String, String>>,
    ): InlineKeyboardMarkup =
        InlineKeyboardMarkup(
            inlineKeyboard =
                buildList {
                    typeButtons.chunked(2).forEach { row ->
                        add(
                            row.map { (type, label) ->
                                InlineKeyboardButton(
                                    text = label,
                                    callbackData = "omt_cs:$venueId:$sectionId:$type",
                                )
                            },
                        )
                    }
                    add(
                        listOf(
                            InlineKeyboardButton(
                                text = "↩️ Назад",
                                callbackData = "owner_venue_order_menu_section:$venueId:$sectionId",
                            ),
                        ),
                    )
                },
        )

    fun inlineVenueOwnerOrderMenuItemTypeActions(
        venueId: Long,
        sectionId: Long,
        itemId: Long,
        typeButtons: List<Pair<String, String>>,
    ): InlineKeyboardMarkup =
        InlineKeyboardMarkup(
            inlineKeyboard =
                buildList {
                    add(
                        listOf(
                            InlineKeyboardButton(
                                text = "Как у раздела",
                                callbackData = "omt_is:$venueId:$sectionId:$itemId:INHERIT",
                            ),
                        ),
                    )
                    typeButtons.chunked(2).forEach { row ->
                        add(
                            row.map { (type, label) ->
                                InlineKeyboardButton(
                                    text = label,
                                    callbackData = "omt_is:$venueId:$sectionId:$itemId:$type",
                                )
                            },
                        )
                    }
                    add(
                        listOf(
                            InlineKeyboardButton(
                                text = "↩️ Назад",
                                callbackData = "owner_venue_order_menu_item_more:$venueId:$sectionId:$itemId",
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
        baseProfileButtons: List<Pair<Int, String>> = emptyList(),
    ): InlineKeyboardMarkup =
        InlineKeyboardMarkup(
            inlineKeyboard =
                buildList {
                    flavorButtons.forEach { (optionId, label) ->
                        add(
                            listOf(
                                InlineKeyboardButton(
                                    text = label,
                                    callbackData =
                                        "owner_venue_order_menu_item_option:$venueId:$sectionId:$itemId:" +
                                            "$optionId",
                                ),
                            ),
                        )
                    }
                    baseProfileButtons
                        .chunked(3)
                        .forEach { row ->
                            add(
                                row.map { (profileIndex, label) ->
                                    InlineKeyboardButton(
                                        text = label,
                                        callbackData =
                                            "owner_venue_item_flavor_p:$venueId:$sectionId:$itemId:" +
                                                "$profileIndex",
                                    )
                                },
                            )
                        }
                    add(
                        listOf(
                            InlineKeyboardButton(
                                text = "➕ Добавить свой вариант",
                                callbackData = "owner_venue_order_menu_item_option_add:$venueId:$sectionId:$itemId",
                            ),
                        ),
                    )
                    add(
                        listOf(
                            InlineKeyboardButton(
                                text = "✅ Добавить базовые вкусовые профили",
                                callbackData = "owner_venue_item_flavors_std:$venueId:$sectionId:$itemId",
                            ),
                        ),
                    )
                    add(
                        listOf(
                            InlineKeyboardButton(
                                text = "⚙️ Дополнительно",
                                callbackData = "owner_venue_item_flavors_more:$venueId:$sectionId:$itemId",
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

    fun inlineVenueOwnerOrderMenuFlavorAdvancedActions(
        venueId: Long,
        sectionId: Long,
        itemId: Long,
    ): InlineKeyboardMarkup =
        InlineKeyboardMarkup(
            inlineKeyboard =
                listOf(
                    listOf(
                        InlineKeyboardButton(
                            text = "♻️ Сбросить к базовым профилям",
                            callbackData = "owner_venue_item_flavors_norm_ask:$venueId:$sectionId:$itemId",
                        ),
                    ),
                    listOf(
                        InlineKeyboardButton(
                            text = "↩️ Назад к вкусам / вариантам",
                            callbackData = "owner_venue_order_menu_item_flavors:$venueId:$sectionId:$itemId",
                        ),
                    ),
                ),
        )

    fun inlineVenueOwnerOrderMenuFlavorNormalizeConfirmActions(
        venueId: Long,
        sectionId: Long,
        itemId: Long,
    ): InlineKeyboardMarkup =
        InlineKeyboardMarkup(
            inlineKeyboard =
                listOf(
                    listOf(
                        InlineKeyboardButton(
                            text = "✅ Да, сбросить к базовым профилям",
                            callbackData = "owner_venue_item_flavors_norm_confirm:$venueId:$sectionId:$itemId",
                        ),
                    ),
                    listOf(
                        InlineKeyboardButton(
                            text = "↩️ Назад",
                            callbackData = "owner_venue_order_menu_item_flavors:$venueId:$sectionId:$itemId",
                        ),
                    ),
                ),
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
                            callbackData =
                                "owner_venue_order_menu_item_option_rename:$venueId:$sectionId:$itemId:" +
                                    "$optionId",
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
                            text = "🗑 Удалить вариант",
                            callbackData =
                                "owner_venue_order_menu_item_option_delete_ask:$venueId:$sectionId:$itemId:" +
                                    "$optionId",
                        ),
                    ),
                    listOf(
                        InlineKeyboardButton(
                            text = "⬅️ Назад к вкусам / вариантам",
                            callbackData = "owner_venue_order_menu_item_flavors:$venueId:$sectionId:$itemId",
                        ),
                    ),
                ),
        )

    fun inlineVenueOwnerOrderMenuFlavorDeleteConfirmActions(
        venueId: Long,
        sectionId: Long,
        itemId: Long,
        optionId: Long,
    ): InlineKeyboardMarkup =
        InlineKeyboardMarkup(
            inlineKeyboard =
                listOf(
                    listOf(
                        InlineKeyboardButton(
                            text = "✅ Да, удалить",
                            callbackData =
                                "owner_venue_order_menu_item_option_delete_confirm:$venueId:$sectionId:$itemId:" +
                                    "$optionId",
                        ),
                    ),
                    listOf(
                        InlineKeyboardButton(
                            text = "↩️ Назад",
                            callbackData = "owner_venue_order_menu_item_option:$venueId:$sectionId:$itemId:$optionId",
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
                    add(
                        listOf(
                            InlineKeyboardButton(
                                text = "↩️ Назад к заведению",
                                callbackData = "venue_menu_back:$venueId",
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
                    listOf(
                        InlineKeyboardButton(
                            text = "↩️ Назад к заведению",
                            callbackData = "venue_menu_back:$venueId",
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

    fun inlineStaffChatOrderBatchAccept(
        venueId: Long,
        batchId: Long,
    ): InlineKeyboardMarkup =
        inlineStaffChatOrderActions(
            venueId = venueId,
            orderId = null,
            batchId = batchId,
            status = OrderWorkflowStatus.NEW,
            webAppUrl = null,
        )

    fun inlineStaffChatOrderActions(
        venueId: Long,
        orderId: Long?,
        batchId: Long,
        status: OrderWorkflowStatus,
        webAppUrl: String?,
    ): InlineKeyboardMarkup {
        val rows =
            buildList {
                when (status) {
                    OrderWorkflowStatus.NEW ->
                        add(
                            listOf(
                                InlineKeyboardButton(
                                    text = "✅ Принять",
                                    callbackData = "sc_ob_a:$venueId:$batchId",
                                ),
                            ),
                        )
                    OrderWorkflowStatus.ACCEPTED,
                    OrderWorkflowStatus.COOKING,
                    OrderWorkflowStatus.DELIVERING,
                    ->
                        add(
                            listOf(
                                InlineKeyboardButton(
                                    text = "🚚 Доставлено",
                                    callbackData = "sc_ob_d:$venueId:$batchId",
                                ),
                            ),
                        )
                    OrderWorkflowStatus.DELIVERED -> {
                        if (orderId != null) {
                            add(
                                listOf(
                                    InlineKeyboardButton(
                                        text = "🧾 Закрыть счёт",
                                        callbackData =
                                            staffChatOrderCloseCallback(
                                                prefix = "sc_oc_ask",
                                                venueId = venueId,
                                                orderId = orderId,
                                                batchId = batchId,
                                            ),
                                    ),
                                ),
                            )
                        }
                    }
                    OrderWorkflowStatus.CLOSED -> Unit
                }
                if (status != OrderWorkflowStatus.CLOSED && orderId != null) {
                    add(
                        listOf(
                            InlineKeyboardButton(
                                text = "🔄 Обновить",
                                callbackData = staffChatOrderCloseCallback("sc_or", venueId, orderId, batchId),
                            ),
                        ),
                    )
                }
                if (status != OrderWorkflowStatus.CLOSED && webAppUrl != null) {
                    add(
                        listOf(
                            InlineKeyboardButton(
                                text = "📱 Открыть Mini App",
                                webApp = WebAppInfo(webAppUrl),
                            ),
                        ),
                    )
                }
            }
        return InlineKeyboardMarkup(inlineKeyboard = rows)
    }

    fun inlineStaffChatOrderBatchDeliver(
        venueId: Long,
        batchId: Long,
    ): InlineKeyboardMarkup =
        inlineStaffChatOrderActions(
            venueId = venueId,
            orderId = null,
            batchId = batchId,
            status = OrderWorkflowStatus.ACCEPTED,
            webAppUrl = null,
        )

    fun inlineStaffChatOrderCloseBill(
        venueId: Long,
        orderId: Long,
        batchId: Long,
    ): InlineKeyboardMarkup =
        inlineStaffChatOrderActions(
            venueId = venueId,
            orderId = orderId,
            batchId = batchId,
            status = OrderWorkflowStatus.DELIVERED,
            webAppUrl = null,
        )

    fun inlineStaffChatOrderCloseBillConfirm(
        venueId: Long,
        orderId: Long,
        batchId: Long,
    ): InlineKeyboardMarkup =
        InlineKeyboardMarkup(
            inlineKeyboard =
                listOf(
                    listOf(
                        InlineKeyboardButton(
                            text = "✅ Да, общий счёт оплачен и закрыт",
                            callbackData = staffChatOrderCloseCallback("sc_oc_yes", venueId, orderId, batchId),
                        ),
                    ),
                    listOf(
                        InlineKeyboardButton(
                            text = "↩️ Назад",
                            callbackData = staffChatOrderCloseCallback("sc_oc_back", venueId, orderId, batchId),
                        ),
                    ),
                ),
        )

    fun inlineGuestItemUnavailableActions(
        chooseReplacementCallback: String,
        keepWithoutItemCallback: String,
        callStaffCallback: String,
        keepWithoutItemText: String,
    ): InlineKeyboardMarkup =
        InlineKeyboardMarkup(
            inlineKeyboard =
                listOf(
                    listOf(
                        InlineKeyboardButton(
                            text = "🍽 Выбрать замену",
                            callbackData = chooseReplacementCallback,
                        ),
                    ),
                    listOf(
                        InlineKeyboardButton(
                            text = keepWithoutItemText,
                            callbackData = keepWithoutItemCallback,
                        ),
                    ),
                    listOf(
                        InlineKeyboardButton(
                            text = "🛎 Позвать персонал",
                            callbackData = callStaffCallback,
                        ),
                    ),
                ),
        )

    fun inlineStaffChatStaffCallAck(
        venueId: Long,
        staffCallId: Long,
    ): InlineKeyboardMarkup =
        InlineKeyboardMarkup(
            inlineKeyboard =
                listOf(
                    listOf(
                        InlineKeyboardButton(
                            text = "✅ Принять вызов",
                            callbackData = "sc_call_ack:$venueId:$staffCallId",
                        ),
                    ),
                ),
        )

    fun inlineStaffChatStaffCallDone(
        venueId: Long,
        staffCallId: Long,
    ): InlineKeyboardMarkup =
        InlineKeyboardMarkup(
            inlineKeyboard =
                listOf(
                    listOf(
                        InlineKeyboardButton(
                            text = "✅ Выполнено",
                            callbackData = "sc_call_done:$venueId:$staffCallId",
                        ),
                    ),
                ),
        )

    private fun staffChatOrderCloseCallback(
        prefix: String,
        venueId: Long,
        orderId: Long,
        batchId: Long,
    ): String = "$prefix:${compactCallbackId(venueId)}:${compactCallbackId(orderId)}:${compactCallbackId(batchId)}"

    private fun compactCallbackId(value: Long): String = java.lang.Long.toString(value, 36)

    fun inlineVenueStatsPeriodActions(venueId: Long? = null): InlineKeyboardMarkup =
        InlineKeyboardMarkup(
            inlineKeyboard =
                buildList {
                    add(
                        listOf(
                            InlineKeyboardButton(
                                text = "Сегодня",
                                callbackData = venueStatsPeriodCallback(venueId, "today"),
                            ),
                        ),
                    )
                    add(
                        listOf(
                            InlineKeyboardButton(
                                text = "7 дней",
                                callbackData = venueStatsPeriodCallback(venueId, "7d"),
                            ),
                        ),
                    )
                    add(
                        listOf(
                            InlineKeyboardButton(
                                text = "30 дней",
                                callbackData = venueStatsPeriodCallback(venueId, "30d"),
                            ),
                        ),
                    )
                    add(
                        listOf(
                            InlineKeyboardButton(
                                text = "⬅️ Назад",
                                callbackData = venueId?.let { "venue_menu_back:$it" } ?: "staff_venue_menu_back",
                            ),
                        ),
                    )
                },
        )

    private fun venueStatsPeriodCallback(
        venueId: Long?,
        period: String,
    ): String = venueId?.let { "stats_period:$it:$period" } ?: "stats_period_$period"

    fun inlineVenueFeedbackRootActions(
        venueId: Long,
        backText: String = "↩️ Назад",
        backCallbackData: String = "owner_venue_stats_root:$venueId",
        reviewUrlCallbackData: String = "vfr_url:$venueId",
    ): InlineKeyboardMarkup =
        InlineKeyboardMarkup(
            inlineKeyboard =
                listOf(
                    listOf(
                        InlineKeyboardButton(
                            text = "🔴 Требуют ответа",
                            callbackData = venueFeedbackListCallback(venueId, "needs", 0),
                        ),
                    ),
                    listOf(
                        InlineKeyboardButton(
                            text = "⚠️ Низкие оценки",
                            callbackData = venueFeedbackListCallback(venueId, "low", 0),
                        ),
                    ),
                    listOf(
                        InlineKeyboardButton(
                            text = "✅ Все отзывы",
                            callbackData = venueFeedbackListCallback(venueId, "all", 0),
                        ),
                    ),
                    listOf(
                        InlineKeyboardButton(
                            text = "⭐ Ссылка на отзывы",
                            callbackData = reviewUrlCallbackData,
                        ),
                    ),
                    listOf(
                        InlineKeyboardButton(
                            text = backText,
                            callbackData = backCallbackData,
                        ),
                    ),
                ),
        )

    fun inlineVenuePublicReviewUrlActions(
        venueId: Long,
        hasUrl: Boolean,
        backText: String = "↩️ К отзывам",
        backCallbackData: String = "venue_feedback_root:$venueId",
        editCallbackData: String = "vfr_url_edit:$venueId",
        clearCallbackData: String = "vfr_url_clear:$venueId",
    ): InlineKeyboardMarkup =
        InlineKeyboardMarkup(
            inlineKeyboard =
                buildList {
                    add(
                        listOf(
                            InlineKeyboardButton(
                                text = if (hasUrl) "✏️ Изменить ссылку" else "✏️ Добавить ссылку",
                                callbackData = editCallbackData,
                            ),
                        ),
                    )
                    if (hasUrl) {
                        add(
                            listOf(
                                InlineKeyboardButton(
                                    text = "🗑 Удалить ссылку",
                                    callbackData = clearCallbackData,
                                ),
                            ),
                        )
                    }
                    add(
                        listOf(
                            InlineKeyboardButton(
                                text = backText,
                                callbackData = backCallbackData,
                            ),
                        ),
                    )
                },
        )

    fun inlineVenueFeedbackListActions(
        venueId: Long,
        filter: String,
        page: Int,
        feedbacks: List<Pair<Long, String>>,
        hasPrevious: Boolean,
        hasNext: Boolean,
    ): InlineKeyboardMarkup =
        InlineKeyboardMarkup(
            inlineKeyboard =
                buildList {
                    feedbacks.forEach { (feedbackId, label) ->
                        add(
                            listOf(
                                InlineKeyboardButton(
                                    text = label,
                                    callbackData = venueFeedbackOpenCallback(venueId, feedbackId, filter, page),
                                ),
                            ),
                        )
                    }
                    val nav = mutableListOf<InlineKeyboardButton>()
                    if (hasPrevious) {
                        nav +=
                            InlineKeyboardButton(
                                text = "◀️ Назад",
                                callbackData = venueFeedbackListCallback(venueId, filter, (page - 1).coerceAtLeast(0)),
                            )
                    }
                    if (hasNext) {
                        nav +=
                            InlineKeyboardButton(
                                text = "▶️ Далее",
                                callbackData = venueFeedbackListCallback(venueId, filter, page + 1),
                            )
                    }
                    if (nav.isNotEmpty()) {
                        add(nav)
                    }
                    add(
                        listOf(
                            InlineKeyboardButton(
                                text = "↩️ К фильтрам",
                                callbackData = "venue_feedback_root:$venueId",
                            ),
                        ),
                    )
                },
        )

    fun inlineVenueFeedbackDetailActions(
        venueId: Long,
        feedbackId: Long,
        filter: String = "all",
        page: Int = 0,
    ): InlineKeyboardMarkup =
        InlineKeyboardMarkup(
            inlineKeyboard =
                listOf(
                    listOf(
                        InlineKeyboardButton(
                            text = "💬 Ответить гостю",
                            callbackData = "fb_reply:$feedbackId",
                        ),
                    ),
                    listOf(
                        InlineKeyboardButton(
                            text = "↩️ К отзывам",
                            callbackData = venueFeedbackListCallback(venueId, filter, page),
                        ),
                    ),
                ),
        )

    private fun venueFeedbackListCallback(
        venueId: Long,
        filter: String,
        page: Int,
    ): String = "vf_l:${compactCallbackId(venueId)}:$filter:$page"

    private fun venueFeedbackOpenCallback(
        venueId: Long,
        feedbackId: Long,
        filter: String,
        page: Int,
    ): String = "vf_o:${compactCallbackId(venueId)}:${compactCallbackId(feedbackId)}:$filter:$page"

    fun inlineVenueSettingsRootActions(
        venueId: Long,
        showDevReset: Boolean,
    ): InlineKeyboardMarkup = inlineVenueSettingsNotificationActions(venueId)

    fun inlineVenueStaffChatActions(
        venueId: Long,
        isLinked: Boolean,
        backText: String = "↩️ Назад к заведению",
        backCallbackData: String = "venue_menu_back:$venueId",
    ): InlineKeyboardMarkup =
        InlineKeyboardMarkup(
            inlineKeyboard =
                buildList {
                    add(
                        listOf(
                            InlineKeyboardButton(
                                text = "🔗 Сгенерировать код подключения",
                                callbackData = "venue_staff_chat_generate:$venueId",
                            ),
                        ),
                    )
                    add(
                        listOf(
                            InlineKeyboardButton(
                                text = "📡 Проверить статус",
                                callbackData = "venue_staff_chat_status:$venueId",
                            ),
                        ),
                    )
                    if (isLinked) {
                        add(
                            listOf(
                                InlineKeyboardButton(
                                    text = "🧪 Отправить тест",
                                    callbackData = "venue_staff_chat_test:$venueId",
                                ),
                            ),
                        )
                    }
                    add(
                        listOf(
                            InlineKeyboardButton(
                                text = backText,
                                callbackData = backCallbackData,
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
                            text = "↩️ Назад к настройке заведения",
                            callbackData = "owner_venue_hub_setup:$venueId",
                        ),
                    ),
                ),
        )

    fun inlineVenueSettingsTimezoneActions(venueId: Long): InlineKeyboardMarkup =
        InlineKeyboardMarkup(
            inlineKeyboard =
                listOf(
                    listOf(
                        InlineKeyboardButton(
                            text = "↩️ Назад к настройке заведения",
                            callbackData = "owner_venue_hub_setup:$venueId",
                        ),
                    ),
                ),
        )

    fun inlineBackToVenueMenu(venueId: Long): InlineKeyboardMarkup =
        InlineKeyboardMarkup(
            inlineKeyboard =
                listOf(
                    listOf(
                        InlineKeyboardButton(
                            text = "↩️ Назад к заведению",
                            callbackData = "venue_menu_back:$venueId",
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
                                text = "↩️ Назад к заведению",
                                callbackData = "venue_menu_back:$venueId",
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

    fun inlineOwnerVenueDescriptionMediaUploadActions(
        venueId: Long,
        sectionId: Long,
    ): InlineKeyboardMarkup =
        InlineKeyboardMarkup(
            inlineKeyboard =
                listOf(
                    listOf(
                        InlineKeyboardButton(
                            text = "✅ Готово",
                            callbackData = "owner_venue_description_media_done:$venueId:$sectionId",
                        ),
                    ),
                    listOf(
                        InlineKeyboardButton(
                            text = "⬅️ Назад",
                            callbackData = "owner_venue_description_media_back:$venueId:$sectionId",
                        ),
                    ),
                ),
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
                    listOf(
                        InlineKeyboardButton(
                            text = "↩️ Назад к заведению",
                            callbackData = "owner_venue_hub:$venueId",
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

    fun inlineBotMyOrdersAndBookingsActions(): InlineKeyboardMarkup =
        InlineKeyboardMarkup(
            inlineKeyboard =
                listOf(
                    listOf(
                        InlineKeyboardButton(
                            text = "📜 История посещений",
                            callbackData = "visit_history",
                        ),
                    ),
                ),
        )

    fun inlineGuestVisitHistoryActions(visits: List<Pair<Long, String>>): InlineKeyboardMarkup =
        InlineKeyboardMarkup(
            inlineKeyboard =
                visits.map { (visitId, label) ->
                    listOf(
                        InlineKeyboardButton(
                            text = label,
                            callbackData = "visit_open:$visitId",
                        ),
                    )
                },
        )

    fun inlineGuestVisitDetailActions(
        visitId: Long? = null,
        canRepeat: Boolean = false,
    ): InlineKeyboardMarkup =
        InlineKeyboardMarkup(
            inlineKeyboard =
                buildList {
                    if (canRepeat && visitId != null) {
                        add(
                            listOf(
                                InlineKeyboardButton(
                                    text = "🔁 Повторить заказ",
                                    callbackData = "visit_repeat_ask:$visitId",
                                ),
                            ),
                        )
                    }
                    add(
                        listOf(
                            InlineKeyboardButton(
                                text = "↩️ К истории посещений",
                                callbackData = "visit_history_back",
                            ),
                        ),
                    )
                },
        )

    fun inlineGuestVisitRepeatConfirmActions(visitId: Long): InlineKeyboardMarkup =
        InlineKeyboardMarkup(
            inlineKeyboard =
                listOf(
                    listOf(
                        InlineKeyboardButton(
                            text = "✅ Добавить в корзину",
                            callbackData = "visit_repeat_confirm:$visitId",
                        ),
                    ),
                    listOf(
                        InlineKeyboardButton(
                            text = "↩️ Назад к визиту",
                            callbackData = "visit_open:$visitId",
                        ),
                    ),
                ),
        )

    fun inlineGuestVisitRepeatBackActions(visitId: Long): InlineKeyboardMarkup =
        InlineKeyboardMarkup(
            inlineKeyboard =
                listOf(
                    listOf(
                        InlineKeyboardButton(
                            text = "↩️ Назад к визиту",
                            callbackData = "visit_open:$visitId",
                        ),
                    ),
                ),
        )

    fun inlineBotMenuCategories(
        categories: List<Pair<Long, String>>,
        includeTableActionsBack: Boolean = false,
    ): InlineKeyboardMarkup =
        InlineKeyboardMarkup(
            inlineKeyboard =
                buildList {
                    categories.forEach { (id, name) ->
                        add(
                            listOf(
                                InlineKeyboardButton(
                                    text = name,
                                    callbackData = "bot_menu_category:$id",
                                ),
                            ),
                        )
                    }
                    if (includeTableActionsBack) {
                        add(
                            listOf(
                                InlineKeyboardButton(
                                    text = "↩️ Назад к действиям стола",
                                    callbackData = "table_actions_back",
                                ),
                            ),
                        )
                    }
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
        favoriteActionText: String? = null,
        favoriteActionCallbackData: String? = null,
    ): InlineKeyboardMarkup =
        InlineKeyboardMarkup(
            inlineKeyboard =
                buildList {
                    add(
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
                    )
                    if (favoriteActionText != null && favoriteActionCallbackData != null) {
                        add(
                            listOf(
                                InlineKeyboardButton(
                                    text = favoriteActionText,
                                    callbackData = favoriteActionCallbackData,
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
                                text = "🔔 Вызвать персонал",
                                callbackData = "bot_menu_item_staff",
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

    fun inlineFavoriteVenues(
        venues: List<Pair<Long, String>>,
        backCallbackData: String = "bot_catalog_open",
    ): InlineKeyboardMarkup =
        InlineKeyboardMarkup(
            inlineKeyboard =
                buildList {
                    venues.forEach { (venueId, label) ->
                        add(
                            listOf(
                                InlineKeyboardButton(
                                    text = label,
                                    callbackData = "bot_catalog_venue:$venueId",
                                ),
                            ),
                        )
                    }
                    add(
                        listOf(
                            InlineKeyboardButton(
                                text = "↩️ К каталогу",
                                callbackData = backCallbackData,
                            ),
                        ),
                    )
                },
        )

    fun inlineFavoriteMenuItems(items: List<Triple<Long, Long, String>>): InlineKeyboardMarkup =
        InlineKeyboardMarkup(
            inlineKeyboard =
                buildList {
                    items.forEach { (categoryId, itemId, label) ->
                        add(
                            listOf(
                                InlineKeyboardButton(
                                    text = label,
                                    callbackData = "bot_menu_item:$categoryId:$itemId",
                                ),
                            ),
                        )
                    }
                    add(
                        listOf(
                            InlineKeyboardButton(
                                text = "↩️ Назад к действиям стола",
                                callbackData = "table_actions_back",
                            ),
                        ),
                    )
                },
        )

    fun inlineBotMenuItemFlavorOptions(
        categoryId: Long,
        itemId: Long,
        options: List<Pair<Long, String>>,
        favoriteActionText: String? = null,
        favoriteActionCallbackData: String? = null,
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
                    if (favoriteActionText != null && favoriteActionCallbackData != null) {
                        add(
                            listOf(
                                InlineKeyboardButton(
                                    text = favoriteActionText,
                                    callbackData = favoriteActionCallbackData,
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

    fun inlineBotMenuPostAddActions(
        categoryId: Long,
        favoriteActionText: String? = null,
        favoriteActionCallbackData: String? = null,
    ): InlineKeyboardMarkup =
        InlineKeyboardMarkup(
            inlineKeyboard =
                buildList {
                    if (favoriteActionText != null && favoriteActionCallbackData != null) {
                        add(
                            listOf(
                                InlineKeyboardButton(
                                    text = favoriteActionText,
                                    callbackData = favoriteActionCallbackData,
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
        hasGiftChoice: Boolean = false,
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
                    if (hasGiftChoice) {
                        add(
                            listOf(
                                InlineKeyboardButton(
                                    text = "🎁 Выбрать подарок",
                                    callbackData = "bot_menu_gift_choice",
                                ),
                            ),
                        )
                    }
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

    fun inlineBotGiftChoiceRequiredActions(): InlineKeyboardMarkup =
        InlineKeyboardMarkup(
            inlineKeyboard =
                listOf(
                    listOf(
                        InlineKeyboardButton(
                            text = "🎁 Выбрать подарок",
                            callbackData = "bot_menu_gift_choice",
                        ),
                    ),
                    listOf(
                        InlineKeyboardButton(
                            text = "Оформить без подарка",
                            callbackData = "bot_gift_skip",
                        ),
                    ),
                ),
        )

    fun inlineBotGiftChoiceOptionsActions(options: List<Triple<Long, String, Boolean>>): InlineKeyboardMarkup =
        InlineKeyboardMarkup(
            inlineKeyboard =
                buildList {
                    options.forEach { (itemId, name, selected) ->
                        add(
                            listOf(
                                InlineKeyboardButton(
                                    text = if (selected) "✅ $name" else name,
                                    callbackData = "bot_gift_opt:$itemId",
                                ),
                            ),
                        )
                    }
                    add(
                        listOf(
                            InlineKeyboardButton(
                                text = "✅ Готово",
                                callbackData = "bot_gift_done",
                            ),
                        ),
                    )
                    add(
                        listOf(
                            InlineKeyboardButton(
                                text = "↩️ К корзине",
                                callbackData = "bot_menu_item_cart",
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

    fun inlineTableRelocationActions(): InlineKeyboardMarkup =
        InlineKeyboardMarkup(
            inlineKeyboard =
                listOf(
                    listOf(
                        InlineKeyboardButton(
                            text = "🛎 Позвать персонал для смены стола",
                            callbackData = "relocation_call_staff",
                        ),
                    ),
                    listOf(
                        InlineKeyboardButton(
                            text = "↩️ Назад к действиям стола",
                            callbackData = "relocation_back_to_table_actions",
                        ),
                    ),
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
                            text = "💬 Чат персонала",
                            callbackData = "venue_staff_chat_root:$venueId",
                        ),
                    ),
                    listOf(
                        InlineKeyboardButton(
                            text = "↩️ Назад к настройке заведения",
                            callbackData = "owner_venue_hub_setup:$venueId",
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
                            text = "↩️ Назад к настройке заведения",
                            callbackData = "owner_venue_hub_setup:$venueId",
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

    fun inlineVenueMiniAppEntry(webAppUrl: String?): InlineKeyboardMarkup? {
        val url = webAppUrl ?: return null
        return InlineKeyboardMarkup(
            inlineKeyboard =
                listOf(
                    listOf(
                        InlineKeyboardButton(
                            text = "📱 Открыть панель заведения",
                            webApp = WebAppInfo(url),
                        ),
                    ),
                ),
        )
    }

    fun inlineVenueMiniAppEntry(
        webAppUrl: String?,
        venueId: Long,
    ): InlineKeyboardMarkup? {
        val url = webAppUrl ?: return null
        return InlineKeyboardMarkup(
            inlineKeyboard =
                listOf(
                    listOf(
                        InlineKeyboardButton(
                            text = "📱 Открыть панель заведения",
                            webApp =
                                WebAppInfo(
                                    url =
                                        buildWebAppUrl(
                                            url,
                                            mapOf(
                                                "mode" to "venue",
                                                "venueId" to venueId.toString(),
                                            ),
                                        ),
                                ),
                        ),
                    ),
                ),
        )
    }

    fun inlinePlatformMiniAppEntry(webAppUrl: String?): InlineKeyboardMarkup? {
        val url = webAppUrl ?: return null
        return InlineKeyboardMarkup(
            inlineKeyboard =
                listOf(
                    listOf(
                        InlineKeyboardButton(
                            text = "📱 Панель платформы",
                            webApp =
                                WebAppInfo(
                                    url = buildWebAppUrl(url, mapOf("mode" to "platform")),
                                ),
                        ),
                    ),
                ),
        )
    }

    fun inlineMiniAppEntry(webAppUrl: String?): InlineKeyboardMarkup? {
        val url = webAppUrl ?: return null
        return InlineKeyboardMarkup(
            inlineKeyboard =
                listOf(
                    listOf(
                        InlineKeyboardButton(
                            text = "📱 Открыть Mini App",
                            webApp = WebAppInfo(url),
                        ),
                    ),
                ),
        )
    }

    fun inlineGuestTableMiniAppEntry(webAppUrl: String?): InlineKeyboardMarkup? {
        val url = webAppUrl ?: return null
        return InlineKeyboardMarkup(
            inlineKeyboard =
                listOf(
                    listOf(
                        InlineKeyboardButton(
                            text = "📱 Заказывать в Mini App",
                            webApp = WebAppInfo(url),
                        ),
                    ),
                ),
        )
    }
}
