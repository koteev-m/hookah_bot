package com.hookah.platform.backend.telegram

import com.hookah.platform.backend.miniapp.venue.orders.OrderWorkflowStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class TelegramKeyboardsTest {
    @Test
    fun `owner main menu renders platform sections`() {
        val markup = TelegramKeyboards.ownerMainMenu()
        val reply = assertIs<ReplyKeyboardMarkup>(markup)
        val rows = reply.keyboard.map { row -> row.map { it.text } }

        assertEquals(
            listOf(
                listOf("📨 Заявки на подключение", "🏢 Кальянные"),
                listOf("👤 Клиенты / Лимиты"),
                listOf("📣 Продвижение"),
            ),
            rows,
        )
    }

    @Test
    fun `owner main menu hides placeholder platform sections`() {
        val markup = TelegramKeyboards.ownerMainMenu()
        val reply = assertIs<ReplyKeyboardMarkup>(markup)
        val texts = reply.keyboard.flatten().map { it.text }

        assertFalse(texts.contains("💳 Подписки"))
        assertFalse(texts.contains("⚙️ Статусы"))
    }

    @Test
    fun `platform owner menu shows mini app panel when configured`() {
        val panelUrl = buildWebAppUrl("https://mini.app/miniapp/", mapOf("mode" to "platform"))
        val markup = TelegramKeyboards.ownerMainMenu(showMiniAppEntry = true, platformPanelUrl = panelUrl)
        val reply = assertIs<ReplyKeyboardMarkup>(markup)
        val button = reply.keyboard.flatten().first { it.text == "📱 Панель платформы" }

        assertEquals(null, button.webApp)
    }

    @Test
    fun `main menu shows guest mini app entry when configured`() {
        val markup =
            TelegramKeyboards.mainMenu(
                hasVenueRole = false,
                isPlatformOwner = false,
                webAppUrl = "https://mini.app/miniapp/?mode=guest",
            )
        val reply = assertIs<ReplyKeyboardMarkup>(markup)
        val texts = reply.keyboard.flatten().map { it.text }

        assertEquals(
            listOf(
                "📱 Открыть Mini App",
                "🍽 Каталог кальянных",
                "🎁 Акции",
                "🪑 Я за столом / У меня QR",
                "📄 Мои заказы и брони",
                "👤 Мой профиль",
                "🤝 Добавить свою кальянную",
            ),
            texts,
        )
        assertFalse(texts.contains("🧪 Тест: стол 1"))
        assertEquals(true, reply.keyboard.flatten().all { it.webApp == null })
    }

    @Test
    fun `fallback menu hides guest mini app entry when url is missing`() {
        val markup = TelegramKeyboards.mainMenu(hasVenueRole = false, isPlatformOwner = false, webAppUrl = null)
        val reply = assertIs<ReplyKeyboardMarkup>(markup)
        val texts = reply.keyboard.flatten().map { it.text }

        assertEquals(
            listOf(
                "🍽 Каталог кальянных",
                "🎁 Акции",
                "🪑 Я за столом / У меня QR",
                "📄 Мои заказы и брони",
                "👤 Мой профиль",
                "🤝 Добавить свою кальянную",
            ),
            texts,
        )
        assertFalse(texts.contains("📱 Открыть Mini App"))
        assertEquals(true, reply.keyboard.flatten().all { it.webApp == null })
    }

    @Test
    fun `main menu uses compact launch layout`() {
        val markup =
            TelegramKeyboards.mainMenu(
                hasVenueRole = false,
                isPlatformOwner = false,
                webAppUrl = "https://mini.app/miniapp/?mode=guest",
            )
        val reply = assertIs<ReplyKeyboardMarkup>(markup)
        val rows = reply.keyboard.map { row -> row.map { it.text } }

        assertEquals(
            listOf(
                listOf("📱 Открыть Mini App"),
                listOf("🍽 Каталог кальянных"),
                listOf("🎁 Акции", "🪑 Я за столом / У меня QR"),
                listOf("📄 Мои заказы и брони", "👤 Мой профиль"),
                listOf("🤝 Добавить свою кальянную"),
            ),
            rows,
        )
    }

    @Test
    fun `guest profile actions include name birthday favorite venues loyalty and back`() {
        val markup = TelegramKeyboards.inlineGuestProfileActions()
        val buttons = markup.inlineKeyboard.flatten()

        assertEquals("✏️ Изменить имя", buttons[0].text)
        assertEquals("guest_profile_name", buttons[0].callbackData)
        assertEquals("🎁 Указать день рождения", buttons[1].text)
        assertEquals("guest_profile_birthday", buttons[1].callbackData)
        assertEquals("⭐ Избранные заведения", buttons[2].text)
        assertEquals("fav_v_list:profile", buttons[2].callbackData)
        assertEquals("🎁 Лояльность", buttons[3].text)
        assertEquals("guest_loyalty", buttons[3].callbackData)
        assertEquals("↩️ Назад", buttons[4].text)
        assertEquals("guest_profile_back", buttons[4].callbackData)
    }

    @Test
    fun `my orders and bookings actions include visit history`() {
        val buttons = TelegramKeyboards.inlineBotMyOrdersAndBookingsActions().inlineKeyboard.flatten()

        assertEquals("📜 История посещений", buttons.single().text)
        assertEquals("visit_history", buttons.single().callbackData)
    }

    @Test
    fun `guest visit history actions open visits`() {
        val buttons =
            TelegramKeyboards.inlineGuestVisitHistoryActions(
                visits = listOf(10L to "12.05 · Mix · 2 400 ₽"),
            ).inlineKeyboard.flatten()

        assertEquals("12.05 · Mix · 2 400 ₽", buttons.single().text)
        assertEquals("visit_open:10", buttons.single().callbackData)
    }

    @Test
    fun `guest visit detail actions return to history`() {
        val buttons = TelegramKeyboards.inlineGuestVisitDetailActions().inlineKeyboard.flatten()

        assertEquals("↩️ К истории посещений", buttons.single().text)
        assertEquals("visit_history_back", buttons.single().callbackData)
    }

    @Test
    fun `guest visit detail actions can include repeat order`() {
        val buttons =
            TelegramKeyboards
                .inlineGuestVisitDetailActions(visitId = 10L, canRepeat = true)
                .inlineKeyboard
                .flatten()

        assertEquals("🔁 Повторить заказ", buttons[0].text)
        assertEquals("visit_repeat_ask:10", buttons[0].callbackData)
        assertEquals("↩️ К истории посещений", buttons[1].text)
        assertEquals("visit_history_back", buttons[1].callbackData)
    }

    @Test
    fun `guest visit detail actions require explicit order selection for multiple orders`() {
        val buttons =
            TelegramKeyboards
                .inlineGuestVisitDetailActions(
                    visitId = 10L,
                    repeatOrders = listOf(20L to "заказ №20", 21L to "заказ №21"),
                ).inlineKeyboard
                .flatten()

        assertEquals("🔁 Повторить заказ №20", buttons[0].text)
        assertEquals("visit_repeat_ask:10:20", buttons[0].callbackData)
        assertEquals("🔁 Повторить заказ №21", buttons[1].text)
        assertEquals("visit_repeat_ask:10:21", buttons[1].callbackData)
        assertEquals("visit_history_back", buttons[2].callbackData)
    }

    @Test
    fun `guest visit repeat confirmation actions add or return to visit`() {
        val buttons = TelegramKeyboards.inlineGuestVisitRepeatConfirmActions(visitId = 10L).inlineKeyboard.flatten()

        assertEquals("✅ Добавить в корзину", buttons[0].text)
        assertEquals("visit_repeat_confirm:10", buttons[0].callbackData)
        assertEquals("↩️ Назад к визиту", buttons[1].text)
        assertEquals("visit_open:10", buttons[1].callbackData)
    }

    @Test
    fun `visit feedback actions render ratings comment and skip`() {
        val buttons = TelegramKeyboards.inlineVisitFeedbackActions(visitId = 10L).inlineKeyboard.flatten()

        assertEquals(listOf("⭐ 1", "⭐ 2", "⭐ 3", "⭐ 4", "⭐ 5"), buttons.take(5).map { it.text })
        assertEquals(
            listOf("fb_r:10:1", "fb_r:10:2", "fb_r:10:3", "fb_r:10:4", "fb_r:10:5"),
            buttons.take(5).map {
                it.callbackData
            },
        )
        assertEquals("💬 Оставить комментарий", buttons[5].text)
        assertEquals("fb_c:10", buttons[5].callbackData)
        assertEquals("Пропустить", buttons[6].text)
        assertEquals("fb_skip:10", buttons[6].callbackData)
        assertTrue(buttons.all { it.callbackData.orEmpty().length <= 64 })
    }

    @Test
    fun `visit feedback comment prompt renders comment and not now`() {
        val buttons = TelegramKeyboards.inlineVisitFeedbackCommentPrompt(visitId = 10L).inlineKeyboard.flatten()

        assertEquals("💬 Оставить комментарий", buttons[0].text)
        assertEquals("fb_c:10", buttons[0].callbackData)
        assertEquals("Без комментария", buttons[1].text)
        assertEquals("fb_skip:10", buttons[1].callbackData)
    }

    @Test
    fun `visit feedback rating only actions render ratings`() {
        val buttons = TelegramKeyboards.inlineVisitFeedbackRatingOnlyActions(visitId = 10L).inlineKeyboard.flatten()

        assertEquals(listOf("⭐ 1", "⭐ 2", "⭐ 3", "⭐ 4", "⭐ 5"), buttons.map { it.text })
        assertEquals(
            listOf("fb_r:10:1", "fb_r:10:2", "fb_r:10:3", "fb_r:10:4", "fb_r:10:5"),
            buttons.map { it.callbackData },
        )
        assertTrue(buttons.all { it.callbackData.orEmpty().length <= 64 })
    }

    @Test
    fun `visit feedback staff reply action is compact`() {
        val button = TelegramKeyboards.inlineVisitFeedbackStaffReply(feedbackId = 91L).inlineKeyboard.flatten().single()

        assertEquals("💬 Ответить гостю", button.text)
        assertEquals("fb_reply:91", button.callbackData)
        assertTrue(button.callbackData!!.length <= 64)
    }

    @Test
    fun `public review cta actions render url and done callbacks`() {
        val buttons =
            TelegramKeyboards
                .inlinePublicReviewCtaActions(venueId = 10L, publicReviewUrl = "https://yandex.ru/maps/org/mix/reviews")
                .inlineKeyboard
                .flatten()

        assertEquals("⭐ Оставить отзыв", buttons[0].text)
        assertEquals("https://yandex.ru/maps/org/mix/reviews", buttons[0].url)
        assertEquals("✅ Уже оставил отзыв", buttons[1].text)
        assertEquals("pubrev_done:10", buttons[1].callbackData)
        assertTrue(buttons.mapNotNull { it.callbackData }.all { it.length <= 64 })
    }

    @Test
    fun `venue feedback root renders filter actions`() {
        val buttons = TelegramKeyboards.inlineVenueFeedbackRootActions(venueId = 10L).inlineKeyboard.flatten()

        assertEquals("🔴 Требуют ответа", buttons[0].text)
        assertEquals("vf_l:a:needs:0", buttons[0].callbackData)
        assertEquals("⚠️ Низкие оценки", buttons[1].text)
        assertEquals("vf_l:a:low:0", buttons[1].callbackData)
        assertEquals("✅ Все отзывы", buttons[2].text)
        assertEquals("vf_l:a:all:0", buttons[2].callbackData)
        assertEquals("⭐ Ссылка на отзывы", buttons[3].text)
        assertEquals("vfr_url:10", buttons[3].callbackData)
        assertEquals("↩️ Назад", buttons[4].text)
        assertEquals("owner_venue_stats_root:10", buttons[4].callbackData)
        assertTrue(buttons.all { it.callbackData.orEmpty().length <= 64 })
    }

    @Test
    fun `venue public review url actions render add edit clear and back`() {
        val emptyButtons =
            TelegramKeyboards.inlineVenuePublicReviewUrlActions(venueId = 10L, hasUrl = false).inlineKeyboard.flatten()
        assertEquals("✏️ Добавить ссылку", emptyButtons[0].text)
        assertEquals("vfr_url_edit:10", emptyButtons[0].callbackData)
        assertEquals("↩️ К отзывам", emptyButtons[1].text)
        assertEquals("venue_feedback_root:10", emptyButtons[1].callbackData)

        val configuredButtons =
            TelegramKeyboards.inlineVenuePublicReviewUrlActions(venueId = 10L, hasUrl = true).inlineKeyboard.flatten()
        assertEquals("✏️ Изменить ссылку", configuredButtons[0].text)
        assertEquals("vfr_url_edit:10", configuredButtons[0].callbackData)
        assertEquals("🗑 Удалить ссылку", configuredButtons[1].text)
        assertEquals("vfr_url_clear:10", configuredButtons[1].callbackData)
        assertEquals("↩️ К отзывам", configuredButtons[2].text)
        assertTrue(configuredButtons.all { it.callbackData.orEmpty().length <= 64 })
    }

    @Test
    fun `venue feedback list renders compact item and pagination callbacks`() {
        val buttons =
            TelegramKeyboards.inlineVenueFeedbackListActions(
                venueId = 10L,
                filter = "needs",
                page = 1,
                feedbacks = listOf(92L to "13.05 · ⭐ 2/5 · Максим · не отвечен"),
                hasPrevious = true,
                hasNext = true,
            ).inlineKeyboard.flatten()

        assertEquals("13.05 · ⭐ 2/5 · Максим · не отвечен", buttons[0].text)
        assertEquals("vf_o:a:2k:needs:1", buttons[0].callbackData)
        assertEquals("◀️ Назад", buttons[1].text)
        assertEquals("vf_l:a:needs:0", buttons[1].callbackData)
        assertEquals("▶️ Далее", buttons[2].text)
        assertEquals("vf_l:a:needs:2", buttons[2].callbackData)
        assertEquals("↩️ К фильтрам", buttons[3].text)
        assertEquals("venue_feedback_root:10", buttons[3].callbackData)
        assertTrue(buttons.all { it.callbackData.orEmpty().length <= 64 })
    }

    @Test
    fun `venue feedback detail back keeps current filter and page`() {
        val buttons =
            TelegramKeyboards.inlineVenueFeedbackDetailActions(
                venueId = 10L,
                feedbackId = 92L,
                filter = "needs",
                page = 1,
            ).inlineKeyboard.flatten()

        assertEquals("💬 Ответить гостю", buttons[0].text)
        assertEquals("fb_reply:92", buttons[0].callbackData)
        assertEquals("↩️ К отзывам", buttons[1].text)
        assertEquals("vf_l:a:needs:1", buttons[1].callbackData)
        assertTrue(buttons.all { it.callbackData.orEmpty().length <= 64 })
    }

    @Test
    fun `staff venue menu exposes operational actions only`() {
        val markup = TelegramKeyboards.venueStaffMenu()
        val rows = markup.keyboard.map { row -> row.map { it.text } }

        assertEquals(
            listOf(
                listOf("🧾 Заказы", "🛎 Вызовы"),
                listOf("📄 Брони"),
                listOf("🔄 Сменить заведение"),
            ),
            rows,
        )
        assertFalse(rows.flatten().contains("🚫 Стоп-лист"))
        assertFalse(rows.flatten().contains("🍽 Меню"))
        assertFalse(rows.flatten().contains("🪑 Столы и QR"))
        assertFalse(rows.flatten().contains("👥 Персонал"))
        assertFalse(rows.flatten().contains("💬 Чат персонала"))
    }

    @Test
    fun `staff venue menu shows mini app panel when configured`() {
        val panelUrl =
            buildWebAppUrl(
                "https://mini.app/miniapp/",
                mapOf("mode" to "venue", "venueId" to "10"),
            )
        val markup = TelegramKeyboards.venueStaffMenu(showMiniAppEntry = true, venuePanelUrl = panelUrl)
        val button = markup.keyboard.flatten().first { it.text == "📱 Открыть рабочую панель" }

        assertEquals(null, button.webApp)
        assertFalse(markup.keyboard.flatten().any { it.text.contains("🧪") })
        assertFalse(markup.keyboard.flatten().any { it.text == "🚫 Стоп-лист" })
        assertFalse(markup.keyboard.flatten().any { it.text == "👥 Персонал" })
    }

    @Test
    fun `venue manager menu exposes staff management and staff chat but not owner settings`() {
        val markup = TelegramKeyboards.venueManagerMenu()
        val rows = markup.keyboard.map { row -> row.map { it.text } }

        assertEquals(
            listOf(
                listOf("🧾 Заказы", "🛎 Вызовы"),
                listOf("🚫 Стоп-лист", "📄 Брони"),
                listOf("🍽 Меню", "🏢 Заведение"),
                listOf("🪑 Столы и QR", "📊 Статистика"),
                listOf("💬 Чат персонала", "👥 Персонал"),
                listOf("📣 Продвижение"),
                listOf("🔄 Сменить заведение"),
            ),
            rows,
        )
        assertFalse(rows.flatten().contains("⚙️ Настройки"))
        assertFalse(rows.flatten().contains("🤖 Помощник"))
    }

    @Test
    fun `venue manager menu shows mini app panel when configured`() {
        val panelUrl =
            buildWebAppUrl(
                "https://mini.app/miniapp/",
                mapOf("mode" to "venue", "venueId" to "10"),
            )
        val markup =
            TelegramKeyboards.venueManagerMenu(
                showAiAssistant = true,
                showMiniAppEntry = true,
                venuePanelUrl = panelUrl,
            )
        val rows = markup.keyboard.map { row -> row.map { it.text } }
        val button = markup.keyboard.flatten().first { it.text == "📱 Открыть панель заведения" }

        assertEquals(null, button.webApp)
        assertEquals(0, rows.indexOf(listOf("📱 Открыть панель заведения")))
        assertFalse(rows.flatten().any { it.contains("🧪") })
    }

    @Test
    fun `venue manager menu hides mini app panel when disabled`() {
        val markup =
            TelegramKeyboards.venueManagerMenu(
                showMiniAppEntry = false,
                venuePanelUrl = "https://mini.app/miniapp/?mode=venue&venueId=10",
            )
        val texts = markup.keyboard.flatten().map { it.text }

        assertFalse(texts.contains("📱 Открыть панель заведения"))
    }

    @Test
    fun `venue manager menu shows assistant when enabled`() {
        val markup = TelegramKeyboards.venueManagerMenu(showAiAssistant = true)
        val rows = markup.keyboard.map { row -> row.map { it.text } }

        assertTrue(rows.flatten().contains("🤖 Помощник"))
        assertTrue(rows.indexOf(listOf("🤖 Помощник")) > rows.indexOf(listOf("📣 Продвижение")))
    }

    @Test
    fun `venue owner menu exposes global owner navigation only`() {
        val markup = TelegramKeyboards.venueOwnerMenu()
        val rows = markup.keyboard.map { row -> row.map { it.text } }

        assertEquals(
            listOf(
                listOf("🏢 Мои заведения", "📊 Статистика заведений"),
                listOf("🍽 Каталог кальянных", "👤 Мой профиль"),
            ),
            rows,
        )
        val allButtons = rows.flatten()
        assertFalse(allButtons.contains("🔄 Сменить заведение"))
        assertFalse(allButtons.contains("🍽 Меню заведения"))
        assertFalse(allButtons.contains("🪑 Столы и QR"))
        assertFalse(allButtons.contains("👥 Персонал"))
        assertFalse(allButtons.contains("📦 Заказы"))
        assertFalse(allButtons.contains("📄 Брони"))
        assertFalse(allButtons.contains("📊 Статистика"))
        assertFalse(allButtons.contains("💬 Чат персонала"))
        assertFalse(allButtons.contains("⚙️ Настройки"))
    }

    @Test
    fun `venue owner menu shows mini app panel when configured`() {
        val panelUrl = buildWebAppUrl("https://mini.app/miniapp/", mapOf("mode" to "venue"))
        val markup = TelegramKeyboards.venueOwnerMenu(showMiniAppEntry = true, venuePanelUrl = panelUrl)
        val rows = markup.keyboard.map { row -> row.map { it.text } }
        val button = markup.keyboard.flatten().first { it.text == "📱 Открыть панель заведения" }

        assertEquals(0, rows.indexOf(listOf("📱 Открыть панель заведения")))
        assertEquals(null, button.webApp)
    }

    @Test
    fun `owner venue quota actions render create when capacity remains`() {
        val buttons = TelegramKeyboards.inlineOwnerVenueQuotaActions(canCreateVenue = true).inlineKeyboard.flatten()

        assertEquals("➕ Создать новое заведение", buttons[0].text)
        assertEquals("owner_quota_create_start", buttons[0].callbackData)
        assertEquals("↩️ Назад", buttons[1].text)
        assertEquals("staff_venue_menu_back", buttons[1].callbackData)
    }

    @Test
    fun `owner venue quota actions render limit request when quota is exhausted`() {
        val buttons = TelegramKeyboards.inlineOwnerVenueQuotaActions(canCreateVenue = false).inlineKeyboard.flatten()

        assertEquals("📩 Запросить ещё заведение", buttons[0].text)
        assertEquals("owner_quota_request_start", buttons[0].callbackData)
        assertEquals("↩️ Назад", buttons[1].text)
        assertEquals("staff_venue_menu_back", buttons[1].callbackData)
    }

    @Test
    fun `owner venues dashboard actions render venues create and request`() {
        val buttons =
            TelegramKeyboards.inlineOwnerVenuesDashboardActions(
                venues = listOf(10L to "✅ Mix · Черновик"),
                canCreateVenue = true,
            ).inlineKeyboard.flatten()

        assertEquals("✅ Mix · Черновик", buttons[0].text)
        assertEquals("owner_venue_select:10", buttons[0].callbackData)
        assertEquals("➕ Создать новое заведение", buttons[1].text)
        assertEquals("owner_quota_create_start", buttons[1].callbackData)
        assertEquals("📩 Запросить увеличение лимита", buttons[2].text)
        assertEquals("owner_quota_request_start", buttons[2].callbackData)
    }

    @Test
    fun `owner venues dashboard actions hide create when quota is exhausted but keep request`() {
        val buttons =
            TelegramKeyboards.inlineOwnerVenuesDashboardActions(
                venues = listOf(10L to "Mix · Черновик"),
                canCreateVenue = false,
            ).inlineKeyboard.flatten()

        assertEquals("Mix · Черновик", buttons[0].text)
        assertEquals("owner_venue_select:10", buttons[0].callbackData)
        assertEquals("📩 Запросить увеличение лимита", buttons[1].text)
        assertEquals("owner_quota_request_start", buttons[1].callbackData)
        assertFalse(buttons.any { it.callbackData == "owner_quota_create_start" })
    }

    @Test
    fun `owner selected venue hub renders grouped sections and navigation only`() {
        val buttons =
            TelegramKeyboards
                .inlineOwnerSelectedVenueHubActions(10L, showAiAssistant = true)
                .inlineKeyboard
                .flatten()

        assertEquals(
            listOf(
                "🟢 Работа смены",
                "⚙️ Настройка заведения",
                "📊 Статистика",
                "📣 Продвижение",
                "🤖 Помощник",
                "👁 Предпросмотр для гостя",
                "↩️ К моим заведениям",
                "🏢 Выбрать другое заведение",
            ),
            buttons.map { it.text },
        )
        assertEquals("owner_venue_hub_shift:10", buttons[0].callbackData)
        assertEquals("owner_venue_hub_setup:10", buttons[1].callbackData)
        assertEquals("owner_venue_stats_root:10", buttons[2].callbackData)
        assertEquals("venue_marketing_root:10", buttons[3].callbackData)
        assertEquals("venue_ai:10", buttons[4].callbackData)
        assertEquals("owner_venue_publish_preview:10", buttons[5].callbackData)
        assertEquals("owner_venues_dashboard", buttons[6].callbackData)
        assertEquals("owner_venue_change", buttons[7].callbackData)
        assertEquals(false, buttons.any { it.text == "🏢 Профиль / карточка" })
        assertEquals(false, buttons.any { it.text == "📦 Заказы" })
        assertEquals(false, buttons.any { it.text == "💬 Чат персонала" })
    }

    @Test
    fun `owner selected venue hub shows mini app panel when configured`() {
        val panelUrl =
            buildWebAppUrl(
                "https://mini.app/miniapp/",
                mapOf("mode" to "venue", "venueId" to "10"),
            )
        val buttons =
            TelegramKeyboards
                .inlineOwnerSelectedVenueHubActions(
                    10L,
                    showMiniAppEntry = true,
                    venuePanelUrl = panelUrl,
                )
                .inlineKeyboard
                .flatten()
        val button = buttons.first { it.text == "📱 Открыть панель заведения" }

        assertEquals(null, button.callbackData)
        assertEquals(panelUrl, button.webApp?.url)
        assertFalse(buttons.any { it.text.contains("🧪") })
    }

    @Test
    fun `venue mini app entry uses inline web app with venue id`() {
        val markup = TelegramKeyboards.inlineVenueMiniAppEntry("https://mini.app/miniapp/", 10L)
        val button = markup?.inlineKeyboard?.flatten()?.single()

        assertEquals("📱 Открыть панель заведения", button?.text)
        assertEquals(null, button?.url)
        assertEquals(null, button?.callbackData)
        val webAppUrl = button?.webApp?.url.orEmpty()
        assertTrue(webAppUrl.contains("mode=venue"))
        assertTrue(webAppUrl.contains("venueId=10"))
        assertFalse(webAppUrl.contains("start_param"))
        assertFalse(webAppUrl.contains("table_token"))
    }

    @Test
    fun `venue mini app root entry uses inline web app without venue id`() {
        val panelUrl = buildWebAppUrl("https://mini.app/miniapp/", mapOf("mode" to "venue"))
        val markup = TelegramKeyboards.inlineVenueMiniAppEntry(panelUrl)
        val button = markup?.inlineKeyboard?.flatten()?.single()

        assertEquals("📱 Открыть панель заведения", button?.text)
        assertEquals(null, button?.url)
        assertEquals(null, button?.callbackData)
        val webAppUrl = button?.webApp?.url.orEmpty()
        assertTrue(webAppUrl.contains("mode=venue"))
        assertFalse(webAppUrl.contains("venueId="))
        assertFalse(webAppUrl.contains("start_param"))
        assertFalse(webAppUrl.contains("table_token"))
    }

    @Test
    fun `platform mini app entry uses inline web app`() {
        val markup = TelegramKeyboards.inlinePlatformMiniAppEntry("https://mini.app/miniapp/")
        val button = markup?.inlineKeyboard?.flatten()?.single()

        assertEquals("📱 Панель платформы", button?.text)
        assertEquals(null, button?.url)
        assertEquals(null, button?.callbackData)
        val webAppUrl = button?.webApp?.url.orEmpty()
        assertTrue(webAppUrl.contains("mode=platform"))
        assertFalse(webAppUrl.contains("start_param"))
        assertFalse(webAppUrl.contains("table_token"))
    }

    @Test
    fun `venue marketing root renders marketing sections`() {
        val buttons =
            TelegramKeyboards
                .inlineVenueMarketingRootActions(10L, showAiAssistant = true)
                .inlineKeyboard
                .flatten()

        assertEquals(
            listOf(
                "🎁 Акции",
                "🎁 Лояльность",
                "🖼 Баннеры / Афиши",
                "📌 Размещения",
                "🏆 Поднять в Акциях",
                "⭐ Оценки и отзывы",
                "🔗 Ссылка на отзывы",
                "🤖 Помощник по продвижению",
                "↩️ Назад к заведению",
            ),
            buttons.map { it.text },
        )
        assertEquals("vm_promos:10", buttons[0].callbackData)
        assertEquals("vm_loyalty:10", buttons[1].callbackData)
        assertEquals("vm_banners:10", buttons[2].callbackData)
        assertEquals("vm_placements:10", buttons[3].callbackData)
        assertEquals("vm_top_req:10", buttons[4].callbackData)
        assertEquals("vm_feedback:10", buttons[5].callbackData)
        assertEquals("vm_review_url:10", buttons[6].callbackData)
        assertEquals("vm_ai:10", buttons[7].callbackData)
        assertEquals("owner_venue_hub:10", buttons[8].callbackData)
        assertTrue(buttons.all { it.callbackData.orEmpty().length <= 64 })
    }

    @Test
    fun `venue ai assistant actions render diagnostics and draft text tools`() {
        val rootButtons =
            TelegramKeyboards
                .inlineVenueAiAssistantRootActions(
                    venueId = 10L,
                    diagnosticsCallbackData = "venue_ai_diag:10",
                    promotionSummaryCallbackData = "venue_ai_sum:promotion:10",
                    feedbackSummaryCallbackData = "venue_ai_sum:feedback:10",
                    loyaltySummaryCallbackData = "venue_ai_sum:loyalty:10",
                    ordersSummaryCallbackData = "venue_ai_sum:orders:10",
                    promotionTextCallbackData = "venue_ai_draft:promotion:10",
                    feedbackReplyCallbackData = "venue_ai_draft:review:10",
                    bannerTextCallbackData = "venue_ai_draft:banner:10",
                    backText = "↩️ К заведению",
                    backCallbackData = "owner_venue_hub:10",
                ).inlineKeyboard
                .flatten()
        assertEquals(
            listOf(
                "🔍 Почему акция не применяется?",
                "📣 Сводка по продвижению",
                "⭐ Сводка по отзывам",
                "🎁 Сводка по лояльности",
                "🧾 Сводка по заказам",
                "✍️ Помочь с текстом акции",
                "💬 Черновик ответа на отзыв",
                "🖼 Текст для баннера",
                "↩️ К заведению",
            ),
            rootButtons.map { it.text },
        )
        assertEquals(
            listOf(
                "venue_ai_diag:10",
                "venue_ai_sum:promotion:10",
                "venue_ai_sum:feedback:10",
                "venue_ai_sum:loyalty:10",
                "venue_ai_sum:orders:10",
                "venue_ai_draft:promotion:10",
                "venue_ai_draft:review:10",
                "venue_ai_draft:banner:10",
                "owner_venue_hub:10",
            ),
            rootButtons.map { it.callbackData },
        )

        val diagnosticsButtons =
            TelegramKeyboards.inlineVenueAiPromotionDiagnosticsActions(
                venueId = 10L,
                promotions = listOf(20L to "Активна · Счастливые часы"),
                promotionCallbackPrefix = "venue_ai_diag_p",
                assistantCallbackData = "venue_ai:10",
            ).inlineKeyboard.flatten()
        assertEquals(listOf("Активна · Счастливые часы", "↩️ К помощнику"), diagnosticsButtons.map { it.text })
        assertEquals(listOf("venue_ai_diag_p:10:20", "venue_ai:10"), diagnosticsButtons.map { it.callbackData })
    }

    @Test
    fun `ai env names use underscore variables`() {
        val configText = checkNotNull(javaClass.classLoader.getResource("application.conf")).readText()

        assertTrue(configText.contains("AI_ASSISTANT_ENABLED"))
        assertTrue(configText.contains("AI_PROVIDER"))
        assertFalse(configText.contains("AI-ASSISTANT-ENABLED"))
        assertFalse(configText.contains("AI-PROVIDER"))
    }

    @Test
    fun `venue marketing loyalty actions render setup and lifecycle`() {
        val rootButtons =
            TelegramKeyboards.inlineVenueMarketingLoyaltyRootActions(
                10L,
                hasProgram = false,
            ).inlineKeyboard.flatten()
        assertEquals("🎁 Каждый N-й кальян", rootButtons[0].text)
        assertEquals("vm_loyalty_program:10", rootButtons[0].callbackData)
        assertEquals("➕ Настроить программу", rootButtons[1].text)
        assertEquals("vm_loyalty_setup:10", rootButtons[1].callbackData)
        assertEquals("↩️ К продвижению", rootButtons[2].text)
        assertEquals("venue_marketing_root:10", rootButtons[2].callbackData)

        val nthButtons = TelegramKeyboards.inlineVenueMarketingLoyaltyNthActions(10L).inlineKeyboard.flatten()
        assertEquals(
            listOf("Каждый 3-й", "Каждый 5-й", "Каждый 6-й", "✏️ Указать своё число", "↩️ К лояльности"),
            nthButtons.map { it.text },
        )
        assertEquals(
            listOf(
                "vm_loyalty_n:10:3",
                "vm_loyalty_n:10:5",
                "vm_loyalty_n:10:6",
                "vm_loyalty_custom:10",
                "vm_loyalty:10",
            ),
            nthButtons.map { it.callbackData },
        )

        val draftButtons =
            TelegramKeyboards.inlineVenueMarketingLoyaltyProgramActions(
                10L,
                20L,
                "DRAFT",
            ).inlineKeyboard.flatten()
        assertEquals("✏️ Изменить N", draftButtons[0].text)
        assertEquals("vm_loyalty_setup:10", draftButtons[0].callbackData)
        assertEquals("🎯 Что засчитывается", draftButtons[1].text)
        assertEquals("vm_loyalty_earn:10:20", draftButtons[1].callbackData)
        assertEquals("🎁 Что можно получить бесплатно", draftButtons[2].text)
        assertEquals("vm_loyalty_reward:10:20", draftButtons[2].callbackData)
        assertEquals("▶️ Включить", draftButtons[3].text)
        assertEquals("vm_loyalty_status:10:20:ACTIVE", draftButtons[3].callbackData)
        assertEquals("🗄 Архивировать", draftButtons[4].text)
        assertEquals("vm_loyalty_status:10:20:ARCHIVED", draftButtons[4].callbackData)

        val activeButtons =
            TelegramKeyboards.inlineVenueMarketingLoyaltyProgramActions(
                10L,
                20L,
                "ACTIVE",
            ).inlineKeyboard.flatten()
        assertEquals("⏸ Приостановить", activeButtons[3].text)
        assertEquals("vm_loyalty_status:10:20:PAUSED", activeButtons[3].callbackData)

        val customButtons =
            TelegramKeyboards.inlineVenueMarketingLoyaltyCustomNWaitActions(
                10L,
            ).inlineKeyboard.flatten()
        assertEquals("↩️ К выбору N", customButtons.single().text)
        assertEquals("vm_loyalty_setup:10", customButtons.single().callbackData)

        val earnScopeButtons =
            TelegramKeyboards.inlineVenueMarketingLoyaltyTargetScopeActions(
                10L,
                20L,
                "earn",
            ).inlineKeyboard.flatten()
        assertEquals(
            listOf("✅ Все кальяны", "🎯 Выбрать отдельные позиции", "↩️ Назад"),
            earnScopeButtons.map { it.text },
        )
        assertEquals(
            listOf("vle_all:10:20", "vle_items:10:20:0", "vm_loyalty_program:10"),
            earnScopeButtons.map {
                it.callbackData
            },
        )

        val rewardScopeButtons =
            TelegramKeyboards.inlineVenueMarketingLoyaltyTargetScopeActions(
                10L,
                20L,
                "reward",
            ).inlineKeyboard.flatten()
        assertEquals(
            listOf("vlr_all:10:20", "vlr_items:10:20:0", "vm_loyalty_program:10"),
            rewardScopeButtons.map {
                it.callbackData
            },
        )
    }

    @Test
    fun `venue marketing placement screens render owner navigation`() {
        val rootButtons = TelegramKeyboards.inlineVenueMarketingPlacementRootActions(10L).inlineKeyboard.flatten()
        assertEquals("🕓 На проверке", rootButtons[0].text)
        assertEquals("vm_places_pending:10", rootButtons[0].callbackData)
        assertEquals("✅ Активные", rootButtons[1].text)
        assertEquals("vm_places_active:10", rootButtons[1].callbackData)
        assertEquals("🗄 Завершённые / Архив", rootButtons[2].text)
        assertEquals("vm_places_finished:10", rootButtons[2].callbackData)
        assertEquals("↩️ К продвижению", rootButtons[3].text)
        assertEquals("venue_marketing_root:10", rootButtons[3].callbackData)

        val listButtons =
            TelegramKeyboards
                .inlineVenueMarketingPlacementListActions(
                    venueId = 10L,
                    filter = "pending",
                    placements = listOf(77L to "Общие акции · Афиша · на проверке"),
                ).inlineKeyboard
                .flatten()
        assertEquals("Общие акции · Афиша · на проверке", listButtons[0].text)
        assertEquals("vm_place_open:10:77:pending", listButtons[0].callbackData)
        assertEquals("↩️ К размещениям", listButtons[1].text)
        assertEquals("vm_placements:10", listButtons[1].callbackData)

        val detailButtons =
            TelegramKeyboards.inlineVenueMarketingPlacementDetailActions(
                10L,
                "active",
            ).inlineKeyboard.flatten()
        assertEquals("↩️ К размещениям", detailButtons.single().text)
        assertEquals("vm_places_active:10", detailButtons.single().callbackData)
    }

    @Test
    fun `venue promotions root and archive actions render archive navigation`() {
        val rootButtons =
            TelegramKeyboards
                .inlineVenuePromotionsRootActions(venueId = 10L, promotions = listOf(501L to "черновик · Сет"))
                .inlineKeyboard
                .flatten()

        assertTrue(rootButtons.any { it.text == "черновик · Сет" && it.callbackData == "vp_o:10:501" })
        assertTrue(rootButtons.any { it.text == "➕ Создать акцию" && it.callbackData == "vp_new:10" })
        assertTrue(rootButtons.any { it.text == "🗄 Архив" && it.callbackData == "vp_archive_root:10" })
        assertTrue(rootButtons.any { it.text == "↩️ К продвижению" && it.callbackData == "venue_marketing_root:10" })

        val archiveButtons =
            TelegramKeyboards
                .inlineVenuePromotionsArchiveActions(venueId = 10L, promotions = listOf(502L to "архив · Афиша"))
                .inlineKeyboard
                .flatten()
        assertEquals("архив · Афиша", archiveButtons[0].text)
        assertEquals("vp_archive_open:10:502", archiveButtons[0].callbackData)
        assertEquals("↩️ К акциям", archiveButtons[1].text)
        assertEquals("vp_root:10", archiveButtons[1].callbackData)

        val archivedDetailButtons =
            TelegramKeyboards.inlineVenuePromotionArchivedDetailActions(
                10L,
            ).inlineKeyboard.flatten()
        assertEquals(listOf("↩️ К архиву"), archivedDetailButtons.map { it.text })
        assertEquals("vp_archive_root:10", archivedDetailButtons.single().callbackData)
    }

    @Test
    fun `owner selected venue shift submenu renders operational actions`() {
        val buttons = TelegramKeyboards.inlineOwnerSelectedVenueShiftActions(10L).inlineKeyboard.flatten()

        assertEquals(
            listOf("📦 Заказы", "🛎 Вызовы", "📄 Брони", "🚫 Стоп-лист", "↩️ Назад к заведению"),
            buttons.map { it.text },
        )
        assertEquals("staff_venue_orders_root:10", buttons[0].callbackData)
        assertEquals("staff_venue_calls_root:10", buttons[1].callbackData)
        assertEquals("owner_venue_bookings_root:10", buttons[2].callbackData)
        assertEquals("staff_venue_stoplist_root:10", buttons[3].callbackData)
        assertEquals("owner_venue_hub:10", buttons[4].callbackData)
    }

    @Test
    fun `owner selected venue setup submenu renders setup actions`() {
        val buttons = TelegramKeyboards.inlineOwnerSelectedVenueSetupActions(10L).inlineKeyboard.flatten()

        assertEquals(
            listOf(
                "🏢 Профиль / карточка",
                "🍽 Заказное меню",
                "🪑 Столы и QR",
                "👥 Персонал",
                "💬 Чат персонала",
                "⏱ Сколько держим бронь",
                "✅ Готовность к публикации",
                "↩️ Назад к заведению",
            ),
            buttons.map { it.text },
        )
        assertEquals("owner_venue_profile:10", buttons[0].callbackData)
        assertEquals("owner_venue_order_menu_root:10", buttons[1].callbackData)
        assertEquals("owner_venue_tables_root:10", buttons[2].callbackData)
        assertEquals("owner_venue_staff_root:10", buttons[3].callbackData)
        assertEquals("venue_staff_chat_root:10", buttons[4].callbackData)
        assertEquals("venue_booking_hold_settings:10", buttons[5].callbackData)
        assertEquals("owner_venue_publish_readiness:10", buttons[6].callbackData)
        assertEquals("owner_venue_hub:10", buttons[7].callbackData)
        assertFalse(buttons.any { it.text == "📣 Акции" || it.callbackData == "vp_root:10" })
        assertFalse(buttons.any { it.text == "⚙️ Настройки" })
        assertFalse(buttons.any { it.text == "🔔 Уведомления" })
    }

    @Test
    fun `inline venue booking root actions render settings for managers only`() {
        val managerButtons =
            TelegramKeyboards
                .inlineVenueBookingRootActions(10L, canManageSettings = true)
                .inlineKeyboard
                .flatten()
        val staffButtons =
            TelegramKeyboards
                .inlineVenueBookingRootActions(10L, canManageSettings = false)
                .inlineKeyboard
                .flatten()

        assertEquals("⚙️ Настройки брони", managerButtons[0].text)
        assertEquals("venue_booking_hold_settings:10", managerButtons[0].callbackData)
        assertEquals("↩️ Назад к заведению", managerButtons[1].text)
        assertEquals(listOf("↩️ Назад к заведению"), staffButtons.map { it.text })
    }

    @Test
    fun `inline venue promotion template actions include active templates`() {
        val buttons =
            TelegramKeyboards
                .inlineVenuePromotionTemplateActions(venueId = 10L)
                .inlineKeyboard
                .flatten()

        assertTrue(buttons.any { it.text == "📝 Простая акция" && it.callbackData == "vp_tpl:10:TEXT_ONLY" })
        assertTrue(
            buttons.any { it.text == "🕒 Счастливые часы" && it.callbackData == "vp_tpl:10:HAPPY_HOURS_PERCENT" },
        )
        assertTrue(buttons.any { it.text == "🎁 Подарок к позиции" && it.callbackData == "vp_tpl:10:GIFT_WITH_ITEM" })
        assertTrue(buttons.any { it.text == "🖼 Баннер / афиша" && it.callbackData == "vp_tpl:10:BANNER" })
        assertTrue(buttons.any { it.text == "↩️ Назад" && it.callbackData == "vp_root:10" })
    }

    @Test
    fun `inline text promotion detail actions hide rules entry`() {
        val buttons =
            TelegramKeyboards
                .inlineVenuePromotionDetailActions(
                    venueId = 10L,
                    promotionId = 501L,
                    status = "DRAFT",
                    showRules = false,
                )
                .inlineKeyboard
                .flatten()

        assertTrue(buttons.none { it.text == "⚙️ Правила акции" })
        assertTrue(buttons.any { it.text == "▶️ Включить" && it.callbackData == "vp_on:10:501" })
    }

    @Test
    fun `inline banner promotion detail actions show media actions and hide terms and rules`() {
        val buttons =
            TelegramKeyboards
                .inlineVenuePromotionDetailActions(
                    venueId = 10L,
                    promotionId = 501L,
                    status = "DRAFT",
                    showTerms = false,
                    showRules = false,
                    showBannerMediaActions = true,
                )
                .inlineKeyboard
                .flatten()

        assertTrue(buttons.any { it.text == "🖼 Заменить изображение" && it.callbackData == "vp_img:10:501" })
        assertTrue(buttons.any { it.text == "🗑 Удалить изображение" && it.callbackData == "vp_img_del:10:501" })
        assertTrue(buttons.none { it.text == "✏️ Условия" })
        assertTrue(buttons.none { it.text == "⚙️ Правила акции" })
    }

    @Test
    fun `inline happy hours promotion detail actions include rules entry`() {
        val buttons =
            TelegramKeyboards
                .inlineVenuePromotionDetailActions(
                    venueId = 10L,
                    promotionId = 501L,
                    status = "DRAFT",
                    descriptionText = "✏️ Описание и условия",
                    showTerms = false,
                    showRules = true,
                )
                .inlineKeyboard
                .flatten()

        assertTrue(buttons.any { it.text == "✏️ Описание и условия" && it.callbackData == "vp_ed:10:501" })
        assertTrue(buttons.none { it.text == "✏️ Условия" })
        assertTrue(buttons.any { it.text == "⚙️ Правила акции" && it.callbackData == "vpr_root:10:501" })
        assertTrue(buttons.any { it.text == "▶️ Включить" && it.callbackData == "vp_on:10:501" })
    }

    @Test
    fun `inline gift promotion detail uses common rules entry`() {
        val buttons =
            TelegramKeyboards
                .inlineVenuePromotionDetailActions(
                    venueId = 10L,
                    promotionId = 501L,
                    status = "DRAFT",
                    showRules = true,
                )
                .inlineKeyboard
                .flatten()

        assertTrue(buttons.any { it.text == "⚙️ Правила акции" && it.callbackData == "vpr_root:10:501" })
        assertTrue(buttons.none { it.text == "🎯 Условие подарка" })
        assertTrue(buttons.none { it.text == "🎁 Подарок" })
        assertTrue(buttons.none { it.text == "🕒 Расписание" && it.callbackData == "vpg_s:10:501" })
        assertTrue(buttons.none { it.text == "⚖️ Совместимость акций" })

        val triggerButtons =
            TelegramKeyboards
                .inlineVenueGiftTriggerTypeActions(
                    venueId = 10L,
                    promotionId = 501L,
                    typeButtons = listOf("HOOKAH" to "Кальяны", "TEA" to "Чай"),
                )
                .inlineKeyboard
                .flatten()
        assertTrue(triggerButtons.any { it.text == "Кальяны" && it.callbackData == "vpg_tcat:10:501:HOOKAH" })

        val rewardButtons =
            TelegramKeyboards
                .inlineVenueGiftRewardItemsActions(
                    venueId = 10L,
                    promotionId = 501L,
                    rewardType = "TEA",
                    page = 0,
                    items = listOf(701L to "Чай"),
                    hasPreviousPage = false,
                    hasNextPage = false,
                )
                .inlineKeyboard
                .flatten()
        assertTrue(rewardButtons.any { it.text == "Чай" && it.callbackData == "vpg_ritem:10:501:TEA:701:0" })

        val rewardModeButtons =
            TelegramKeyboards
                .inlineVenueGiftRewardModeActions(
                    venueId = 10L,
                    promotionId = 501L,
                )
                .inlineKeyboard
                .flatten()
        assertTrue(rewardModeButtons.any { it.text == "🎁 Конкретная позиция" && it.callbackData == "vpg_rf:10:501" })
        assertTrue(
            rewardModeButtons.any {
                it.text == "🎁 На выбор из нескольких позиций" && it.callbackData == "vpg_rcmode:10:501"
            },
        )

        val rewardChoiceCategoryButtons =
            TelegramKeyboards
                .inlineVenueGiftRewardChoiceCategoryActions(
                    venueId = 10L,
                    promotionId = 501L,
                    typeButtons = listOf("TEA" to "Чай", "DRINK" to "Напитки"),
                )
                .inlineKeyboard
                .flatten()
        assertTrue(rewardChoiceCategoryButtons.any { it.text == "Чай" && it.callbackData == "vpg_rcpick:10:501:TEA:0" })

        val rewardChoiceItemButtons =
            TelegramKeyboards
                .inlineVenueGiftRewardChoiceItemsActions(
                    venueId = 10L,
                    promotionId = 501L,
                    rewardType = "TEA",
                    page = 0,
                    items = listOf(701L to "Чай", 702L to "Зелёный чай"),
                    selectedItemIds = setOf(701L),
                    hasPreviousPage = false,
                    hasNextPage = false,
                )
                .inlineKeyboard
                .flatten()
        assertTrue(
            rewardChoiceItemButtons.any { it.text == "✅ Чай" && it.callbackData == "vpg_rcitog:10:501:TEA:701:0" },
        )
        assertTrue(
            rewardChoiceItemButtons.any {
                it.text == "Зелёный чай" &&
                    it.callbackData == "vpg_rcitog:10:501:TEA:702:0"
            },
        )
        assertTrue(rewardChoiceItemButtons.any { it.text == "✅ Готово" && it.callbackData == "vpg_rcdone:10:501:TEA" })
    }

    @Test
    fun `inline venue promotion rule actions render settings callbacks only`() {
        val targetButtons =
            TelegramKeyboards
                .inlineVenuePromotionRuleTargetTypeActions(
                    venueId = 10L,
                    promotionId = 501L,
                    typeButtons = listOf("HOOKAH" to "Кальяны", "TEA" to "Чай"),
                )
                .inlineKeyboard
                .flatten()
        assertEquals("Кальяны", targetButtons[0].text)
        assertEquals("vpr_t:10:501:HOOKAH", targetButtons[0].callbackData)
        assertTrue(targetButtons.any { it.text == "↩️ К правилам" && it.callbackData == "vpr_root:10:501" })

        val detailButtons =
            TelegramKeyboards
                .inlineVenuePromotionRuleDetailActions(
                    venueId = 10L,
                    promotionId = 501L,
                    ruleId = 601L,
                    status = "DRAFT",
                )
                .inlineKeyboard
                .flatten()
        assertTrue(detailButtons.none { it.text == "▶️ Включить" })
        assertTrue(detailButtons.none { it.text == "⏸ Приостановить" })
        assertTrue(detailButtons.none { it.text == "🗄 Архивировать" })
        assertTrue(detailButtons.any { it.text == "🎯 На что действует" && it.callbackData == "vpr_tedit:10:501:601" })
        assertTrue(detailButtons.any { it.text == "✏️ Процент скидки" && it.callbackData == "vpr_pct:10:501:601" })
        assertTrue(detailButtons.any { it.text == "🕒 Расписание" && it.callbackData == "vpr_s:10:501:601" })
        assertTrue(detailButtons.any { it.text == "⚖️ Совместимость акций" && it.callbackData == "vpr_cmp:10:501:601" })
        assertTrue(detailButtons.any { it.text == "🗑 Удалить правило" && it.callbackData == "vpr_del:10:501:601" })
        assertTrue(detailButtons.any { it.text == "↩️ К правилам" && it.callbackData == "vpr_root:10:501" })
        assertTrue(detailButtons.none { it.text == "🎁 Подарок" })

        val giftDetailButtons =
            TelegramKeyboards
                .inlineVenuePromotionRuleDetailActions(
                    venueId = 10L,
                    promotionId = 501L,
                    ruleId = 602L,
                    status = "ACTIVE",
                    ruleType = "GIFT_WITH_ITEM",
                )
                .inlineKeyboard
                .flatten()
        assertTrue(giftDetailButtons.any { it.text == "🎯 Условие подарка" && it.callbackData == "vpg_tedit:10:501" })
        assertTrue(giftDetailButtons.any { it.text == "🎁 Подарок" && it.callbackData == "vpg_rew:10:501" })
        assertTrue(giftDetailButtons.any { it.text == "🕒 Расписание" && it.callbackData == "vpr_s:10:501:602" })
        assertTrue(
            giftDetailButtons.any { it.text == "⚖️ Совместимость акций" && it.callbackData == "vpr_cmp:10:501:602" },
        )
        assertTrue(giftDetailButtons.any { it.text == "🗑 Удалить правило" && it.callbackData == "vpr_del:10:501:602" })
        assertTrue(giftDetailButtons.any { it.text == "↩️ К правилам" && it.callbackData == "vpr_root:10:501" })
        assertTrue(giftDetailButtons.none { it.text == "✏️ Процент скидки" })

        val deleteConfirmButtons =
            TelegramKeyboards
                .inlineVenuePromotionRuleDeleteConfirmActions(
                    venueId = 10L,
                    promotionId = 501L,
                    ruleId = 601L,
                )
                .inlineKeyboard
                .flatten()
        assertTrue(
            deleteConfirmButtons.any {
                it.text == "✅ Да, удалить правило" && it.callbackData == "vpr_del_yes:10:501:601"
            },
        )
        assertTrue(
            deleteConfirmButtons.any { it.text == "↩️ Назад к правилу" && it.callbackData == "vpr_o:10:501:601" },
        )

        val targetEditButtons =
            TelegramKeyboards
                .inlineVenuePromotionRuleTargetEditActions(
                    venueId = 10L,
                    promotionId = 501L,
                    ruleId = 601L,
                    typeButtons = listOf("HOOKAH" to "Кальяны", "TEA" to "Чай"),
                )
                .inlineKeyboard
                .flatten()
        assertTrue(targetEditButtons.any { it.text == "Кальяны" && it.callbackData == "vpr_tcat:10:501:601:HOOKAH" })
        assertTrue(targetEditButtons.any { it.text == "↩️ К правилу" && it.callbackData == "vpr_o:10:501:601" })

        val scopeButtons =
            TelegramKeyboards
                .inlineVenuePromotionRuleTargetScopeEditActions(
                    venueId = 10L,
                    promotionId = 501L,
                    ruleId = 601L,
                    targetType = "HOOKAH",
                    targetLabel = "Кальяны",
                )
                .inlineKeyboard
                .flatten()
        assertTrue(
            scopeButtons.any {
                it.text == "✅ Все позиции категории «Кальяны»" && it.callbackData == "vpr_tall:10:501:601:HOOKAH"
            },
        )
        assertTrue(
            scopeButtons.any {
                it.text == "🎯 Выбрать отдельные позиции" && it.callbackData == "vpr_titems:10:501:601:HOOKAH:0"
            },
        )

        val itemButtons =
            TelegramKeyboards
                .inlineVenuePromotionRuleTargetItemsEditActions(
                    venueId = 10L,
                    promotionId = 501L,
                    ruleId = 601L,
                    targetType = "HOOKAH",
                    page = 0,
                    items = listOf(701L to "Кальян обычный", 702L to "Премиум кальян"),
                    selectedItemIds = setOf(701L),
                    hasPreviousPage = false,
                    hasNextPage = false,
                )
                .inlineKeyboard
                .flatten()
        assertTrue(
            itemButtons.any { it.text == "✅ Кальян обычный" && it.callbackData == "vpr_itog:10:501:601:HOOKAH:701:0" },
        )
        assertTrue(
            itemButtons.any { it.text == "Премиум кальян" && it.callbackData == "vpr_itog:10:501:601:HOOKAH:702:0" },
        )
        assertTrue(itemButtons.any { it.text == "✅ Готово" && it.callbackData == "vpr_idone:10:501:601:HOOKAH" })

        val percentEditButtons =
            TelegramKeyboards
                .inlineVenuePromotionRulePercentEditInputActions(venueId = 10L, promotionId = 501L, ruleId = 601L)
                .inlineKeyboard
                .flatten()
        assertEquals(1, percentEditButtons.size)
        assertEquals("↩️ К правилу", percentEditButtons.single().text)
        assertEquals("vpr_o:10:501:601", percentEditButtons.single().callbackData)

        val rootButtons =
            TelegramKeyboards
                .inlineVenuePromotionRulesRootActions(venueId = 10L, promotionId = 501L, rules = emptyList())
                .inlineKeyboard
                .flatten()
        assertTrue(rootButtons.any { it.text == "➕ Добавить правило" && it.callbackData == "vpr_add:10:501" })

        val compatibilityButtons =
            TelegramKeyboards
                .inlineVenuePromotionRuleCompatibilityActions(
                    venueId = 10L,
                    promotionId = 501L,
                    ruleId = 601L,
                    stackable = false,
                )
                .inlineKeyboard
                .flatten()
        assertTrue(
            compatibilityButtons.any {
                it.text == "✅ Не суммировать с похожими акциями" && it.callbackData == "vpr_cmp_set:10:501:601:0"
            },
        )
        assertTrue(
            compatibilityButtons.any {
                it.text == "Можно суммировать с другими акциями" && it.callbackData == "vpr_cmp_set:10:501:601:1"
            },
        )
        assertTrue(compatibilityButtons.any { it.text == "↩️ К правилу" && it.callbackData == "vpr_o:10:501:601" })

        val stackableCompatibilityButtons =
            TelegramKeyboards
                .inlineVenuePromotionRuleCompatibilityActions(
                    venueId = 10L,
                    promotionId = 501L,
                    ruleId = 601L,
                    stackable = true,
                )
                .inlineKeyboard
                .flatten()
        assertTrue(
            stackableCompatibilityButtons.any {
                it.text == "Не суммировать с похожими акциями" && it.callbackData == "vpr_cmp_set:10:501:601:0"
            },
        )
        assertTrue(
            stackableCompatibilityButtons.any {
                it.text == "✅ Можно суммировать с другими акциями" && it.callbackData == "vpr_cmp_set:10:501:601:1"
            },
        )
    }

    @Test
    fun `inline venue promotion rule schedule actions render mode days time and back`() {
        val buttons =
            TelegramKeyboards
                .inlineVenuePromotionRuleScheduleActions(venueId = 10L, promotionId = 501L, ruleId = 601L)
                .inlineKeyboard
                .flatten()

        assertEquals("✅ Всегда", buttons[0].text)
        assertEquals("vpr_sa:10:501:601", buttons[0].callbackData)
        assertTrue(buttons.any { it.text == "📅 Выбрать дни" && it.callbackData == "vpr_sd:10:501:601" })
        assertTrue(buttons.any { it.text == "🕒 Время проведения" && it.callbackData == "vpr_st:10:501:601" })
        assertFalse(buttons.any { it.text == "🕒 Время начала" || it.text == "🕒 Время окончания" })
        assertTrue(buttons.any { it.text == "↩️ К правилу" && it.callbackData == "vpr_o:10:501:601" })
    }

    @Test
    fun `inline venue promotion rule wait-state actions render only back`() {
        val timeButtons =
            TelegramKeyboards
                .inlineVenuePromotionRuleTimeInputActions(venueId = 10L, promotionId = 501L, ruleId = 601L)
                .inlineKeyboard
                .flatten()
        assertEquals(1, timeButtons.size)
        assertEquals("↩️ К правилу", timeButtons.single().text)
        assertEquals("vpr_o:10:501:601", timeButtons.single().callbackData)

        val percentButtons =
            TelegramKeyboards
                .inlineVenuePromotionRulePercentInputActions(venueId = 10L, promotionId = 501L)
                .inlineKeyboard
                .flatten()
        assertEquals(1, percentButtons.size)
        assertEquals("↩️ К правилам", percentButtons.single().text)
        assertEquals("vpr_root:10:501", percentButtons.single().callbackData)
    }

    @Test
    fun `inline venue promotion rule days picker toggles selected days`() {
        val buttons =
            TelegramKeyboards
                .inlineVenuePromotionRuleScheduleDaysActions(
                    venueId = 10L,
                    promotionId = 501L,
                    ruleId = 601L,
                    selectedDays = setOf(1, 5),
                )
                .inlineKeyboard
                .flatten()

        assertEquals("✅ Пн", buttons[0].text)
        assertEquals("vpr_d:10:501:601:1", buttons[0].callbackData)
        assertTrue(buttons.any { it.text == "Вт" && it.callbackData == "vpr_d:10:501:601:2" })
        assertTrue(buttons.any { it.text == "✅ Пт" && it.callbackData == "vpr_d:10:501:601:5" })
        assertTrue(buttons.any { it.text == "✅ Готово" && it.callbackData == "vpr_dd:10:501:601" })
        assertTrue(buttons.any { it.text == "↩️ Назад" && it.callbackData == "vpr_s:10:501:601" })
    }

    @Test
    fun `inline venue booking hold settings render quick custom and back choices`() {
        val buttons =
            TelegramKeyboards.inlineVenueBookingHoldSettingsActions(
                10L,
                currentHoldMinutes = 45,
            ).inlineKeyboard.flatten()

        assertEquals(
            listOf("30 минут", "60 минут", "✏️ Ввести своё число", "↩️ Назад"),
            buttons.map { it.text },
        )
        assertEquals("venue_booking_hold_set:10:30", buttons[0].callbackData)
        assertEquals("venue_booking_hold_set:10:60", buttons[1].callbackData)
        assertEquals("venue_booking_hold_custom:10", buttons[2].callbackData)
        assertEquals("venue_booking_hold_back:10", buttons[3].callbackData)
    }

    @Test
    fun `inline venue booking hold settings do not render current quick value as action`() {
        val buttons =
            TelegramKeyboards.inlineVenueBookingHoldSettingsActions(
                10L,
                currentHoldMinutes = 30,
            ).inlineKeyboard.flatten()

        assertEquals(
            listOf("60 минут", "✏️ Ввести своё число", "↩️ Назад"),
            buttons.map { it.text },
        )
        assertTrue(buttons.none { it.callbackData == "venue_booking_hold_set:10:30" })
    }

    @Test
    fun `venue booking hold custom input renders only back and cancel`() {
        val buttons = TelegramKeyboards.inlineVenueBookingHoldCustomInputActions(10L).inlineKeyboard.flatten()

        assertEquals(listOf("⬅️ Назад", "✖️ Отмена"), buttons.map { it.text })
        assertEquals(
            listOf("venue_booking_hold_back:10", "venue_booking_hold_back:10"),
            buttons.map { it.callbackData },
        )
        assertFalse(buttons.any { it.text.contains("минут") || it.text.contains("Ввести") })
    }

    @Test
    fun `owner setup root sections return to setup root`() {
        fun callbackFor(
            markup: InlineKeyboardMarkup,
            label: String,
        ): String? = markup.inlineKeyboard.flatten().firstOrNull { it.text == label }?.callbackData

        assertEquals(
            "owner_venue_hub_setup:10",
            callbackFor(TelegramKeyboards.inlineVenueOwnerProfileActions(10L), "↩️ Назад к настройке"),
        )
        assertEquals(
            "owner_venue_hub_setup:10",
            callbackFor(
                TelegramKeyboards.inlineVenueOwnerOrderMenuRootActions(10L, emptyList()),
                "↩️ Назад к настройке",
            ),
        )
        assertEquals(
            "owner_venue_hub_setup:10",
            callbackFor(TelegramKeyboards.inlineVenueOwnerTablesRootActions(10L), "↩️ Назад к настройке"),
        )
        assertEquals(
            "owner_venue_hub_setup:10",
            callbackFor(TelegramKeyboards.inlineVenueOwnerStaffRootActions(10L), "↩️ Назад к настройке"),
        )
    }

    @Test
    fun `owner venue description media upload actions render done and back`() {
        val buttons =
            TelegramKeyboards.inlineOwnerVenueDescriptionMediaUploadActions(
                10L,
                101L,
            ).inlineKeyboard.flatten()

        assertEquals(listOf("✅ Готово", "⬅️ Назад"), buttons.map { it.text })
        assertEquals(
            listOf("owner_venue_description_media_done:10:101", "owner_venue_description_media_back:10:101"),
            buttons.map { it.callbackData },
        )
    }

    @Test
    fun `owner venue stats selector renders all venues concrete venues and back`() {
        val buttons =
            TelegramKeyboards.inlineOwnerVenueStatsSelectorActions(
                venues = listOf(10L to "✅ Mix · Черновик", 11L to "Особняк · Опубликовано"),
            ).inlineKeyboard.flatten()

        assertEquals("📊 Все заведения", buttons[0].text)
        assertEquals("owner_stats_all", buttons[0].callbackData)
        assertEquals("✅ Mix · Черновик", buttons[1].text)
        assertEquals("owner_stats_venue:10", buttons[1].callbackData)
        assertEquals("Особняк · Опубликовано", buttons[2].text)
        assertEquals("owner_stats_venue:11", buttons[2].callbackData)
        assertEquals("↩️ Назад", buttons[3].text)
        assertEquals("staff_venue_menu_back", buttons[3].callbackData)
    }

    @Test
    fun `owner publish readiness actions include back to selected venue`() {
        val buttons = TelegramKeyboards.inlineVenueOwnerPublishReadinessActions(10L).inlineKeyboard.flatten()

        assertEquals(true, buttons.any { it.text == "↩️ Назад к заведению" && it.callbackData == "owner_venue_hub:10" })
    }

    @Test
    fun `owner venue root keyboards use setup and operational back targets`() {
        val orderMenuButtons =
            TelegramKeyboards.inlineVenueOwnerOrderMenuRootActions(
                venueId = 10L,
                sectionButtons = listOf(20L to "Кальянное меню"),
            ).inlineKeyboard.flatten()
        val ordersButtons =
            TelegramKeyboards.inlineVenueStaffOrdersRootActions(
                10L,
                emptyList(),
            ).inlineKeyboard.flatten()
        val callsButtons = TelegramKeyboards.inlineVenueStaffCallsRootActions(10L).inlineKeyboard.flatten()
        val bookingsButtons = TelegramKeyboards.inlineBackToVenueMenu(10L).inlineKeyboard.flatten()

        assertEquals(
            true,
            orderMenuButtons.any {
                it.text == "↩️ Назад к настройке" && it.callbackData == "owner_venue_hub_setup:10"
            },
        )
        assertEquals(
            true,
            ordersButtons.any { it.text == "↩️ Назад к заведению" && it.callbackData == "venue_menu_back:10" },
        )
        assertEquals(
            true,
            callsButtons.any { it.text == "↩️ Назад к заведению" && it.callbackData == "venue_menu_back:10" },
        )
        assertEquals(
            true,
            bookingsButtons.any { it.text == "↩️ Назад к заведению" && it.callbackData == "venue_menu_back:10" },
        )
    }

    @Test
    fun `platform owner limit request actions render full partial reject callbacks`() {
        val buttons = TelegramKeyboards.inlinePlatformOwnerLimitRequestActions(17L).inlineKeyboard.flatten()

        assertEquals("✅ Одобрить полностью", buttons[0].text)
        assertEquals("platform_owner_limit_req_approve:17", buttons[0].callbackData)
        assertEquals("✏️ Одобрить частично", buttons[1].text)
        assertEquals("platform_owner_limit_req_partial:17", buttons[1].callbackData)
        assertEquals("❌ Отклонить", buttons[2].text)
        assertEquals("platform_owner_limit_req_reject:17", buttons[2].callbackData)
        assertEquals("↩️ К запросам", buttons[3].text)
        assertEquals("platform_owner_limit_requests_back", buttons[3].callbackData)
    }

    @Test
    fun `platform venue status actions render supplied short callbacks`() {
        val buttons =
            TelegramKeyboards.inlinePlatformVenueStatusActions(
                venueId = 42L,
                actions =
                    listOf(
                        "🚀 Опубликовать" to "publish",
                        "⛔ Приостановить" to "suspend",
                    ),
            ).inlineKeyboard.flatten()
        val texts = buttons.map { it.text }
        val callbacks = buttons.mapNotNull { it.callbackData }

        assertTrue(texts.contains("🚀 Опубликовать"))
        assertFalse(callbacks.any { it.contains("archive") })
        assertFalse(callbacks.any { it.contains("delete") })
        assertTrue(callbacks.all { it.length <= 64 })
    }

    @Test
    fun `platform venue status delete review actions require extra step`() {
        val buttons = TelegramKeyboards.inlinePlatformVenueStatusDeleteReviewActions(42L).inlineKeyboard.flatten()

        assertEquals("⚠️ Продолжить к удалению", buttons[0].text)
        assertEquals("platform_venue_status_delete_review:42", buttons[0].callbackData)
        assertEquals("↩️ Назад", buttons[1].text)
        assertEquals("platform_venue_status:42", buttons[1].callbackData)
    }

    @Test
    fun `platform venue suspend reason prompt has back action`() {
        val buttons = TelegramKeyboards.inlinePlatformVenueSuspendReasonPromptActions(42L).inlineKeyboard.flatten()

        assertEquals(1, buttons.size)
        assertEquals("↩️ Назад", buttons[0].text)
        assertEquals("platform_venue_status:42", buttons[0].callbackData)
    }

    @Test
    fun `venue selector renders venue role callbacks`() {
        val markup =
            TelegramKeyboards.inlineVenueSelectorActions(
                listOf(
                    10L to "Mix · OWNER",
                    11L to "Smoke Lab · MANAGER",
                ),
            )
        val buttons = markup.inlineKeyboard.flatten()

        assertEquals("Mix · OWNER", buttons[0].text)
        assertEquals("venue_select:10", buttons[0].callbackData)
        assertEquals("Smoke Lab · MANAGER", buttons[1].text)
        assertEquals("venue_select:11", buttons[1].callbackData)
    }

    @Test
    fun `stats callbacks carry venue id without reputation actions`() {
        val markup = TelegramKeyboards.inlineVenueStatsPeriodActions(venueId = 10L)
        val buttons = markup.inlineKeyboard.flatten()

        assertEquals("stats_period:10:today", buttons[0].callbackData)
        assertEquals("stats_period:10:7d", buttons[1].callbackData)
        assertEquals("stats_period:10:30d", buttons[2].callbackData)
        assertEquals(false, buttons.any { it.text == "⭐ Оценки и отзывы" })
        assertEquals(false, buttons.any { it.text == "🔗 Ссылка на отзывы" })
        assertEquals("venue_menu_back:10", buttons[3].callbackData)
    }

    @Test
    fun `venue settings legacy root renders staff chat and setup back only`() {
        val markup = TelegramKeyboards.inlineVenueSettingsRootActions(venueId = 10L, showDevReset = false)
        val buttons = markup.inlineKeyboard.flatten()
        val texts = buttons.map { it.text }

        assertEquals(
            listOf("💬 Чат персонала", "↩️ Назад к настройке заведения"),
            texts,
        )
        assertFalse(texts.contains("🕒 Часовой пояс"))
        assertFalse(texts.contains("🧾 Нумерация заказов"))
        assertFalse(texts.contains("📊 Статистика и отчёты"))
        assertFalse(texts.any { it.contains("Тестовые данные") || it.contains("тестовые данные") })
        assertEquals("venue_staff_chat_root:10", buttons[0].callbackData)
        assertEquals("owner_venue_hub_setup:10", buttons[1].callbackData)
    }

    @Test
    fun `venue notification settings legacy keyboard has no toggles`() {
        val buttons = TelegramKeyboards.inlineVenueSettingsNotificationActions(venueId = 10L).inlineKeyboard.flatten()
        val texts = buttons.map { it.text }

        assertEquals(true, texts.contains("💬 Чат персонала"))
        assertEquals("↩️ Назад к настройке заведения", buttons.last().text)
        assertEquals("owner_venue_hub_setup:10", buttons.last().callbackData)
        assertFalse(texts.any { it.contains("Переключить") })
        assertFalse(texts.any { it.contains("Часовой пояс") })
        assertFalse(texts.any { it.contains("Статистика") })
        assertFalse(texts.any { it.contains("Нумерация") })
    }

    @Test
    fun `venue timezone settings keyboard renders read only back action`() {
        val markup = TelegramKeyboards.inlineVenueSettingsTimezoneActions(venueId = 10L)
        val buttons = markup.inlineKeyboard.flatten()

        assertFalse(buttons.any { it.text == "✏️ Изменить часовой пояс" })
        assertFalse(buttons.any { it.callbackData == "venue_settings_timezone_edit:10" })
        assertEquals("↩️ Назад к настройке заведения", buttons[0].text)
        assertEquals("owner_venue_hub_setup:10", buttons[0].callbackData)
    }

    @Test
    fun `staff chat keyboard renders generate status test and back actions`() {
        val linked = TelegramKeyboards.inlineVenueStaffChatActions(venueId = 10L, isLinked = true)
        val linkedButtons = linked.inlineKeyboard.flatten()

        assertEquals("🔗 Сгенерировать код подключения", linkedButtons[0].text)
        assertEquals("venue_staff_chat_generate:10", linkedButtons[0].callbackData)
        assertEquals("📡 Проверить статус", linkedButtons[1].text)
        assertEquals("venue_staff_chat_status:10", linkedButtons[1].callbackData)
        assertEquals("🧪 Отправить тест", linkedButtons[2].text)
        assertEquals("venue_staff_chat_test:10", linkedButtons[2].callbackData)
        assertEquals("↩️ Назад к заведению", linkedButtons[3].text)
        assertEquals("venue_menu_back:10", linkedButtons[3].callbackData)
        val linkedButtonTexts = linkedButtons.map { it.text }
        assertFalse(linkedButtonTexts.contains("🧾 Заказы"))
        assertFalse(linkedButtonTexts.contains("🛎 Вызовы"))
        assertFalse(linkedButtonTexts.contains("🍽 Меню"))
        assertFalse(linkedButtonTexts.contains("💬 Чат персонала"))

        val unlinked = TelegramKeyboards.inlineVenueStaffChatActions(venueId = 10L, isLinked = false)
        assertFalse(unlinked.inlineKeyboard.flatten().any { it.text == "🧪 Отправить тест" })
    }

    @Test
    fun `inline bot venue catalog renders venue buttons`() {
        val markup = TelegramKeyboards.inlineBotVenueCatalog(listOf(10L to "First", 11L to "Second"))
        val buttons = markup.inlineKeyboard.flatten()

        assertEquals("First", buttons[0].text)
        assertEquals("bot_catalog_venue:10", buttons[0].callbackData)
        assertEquals("Second", buttons[1].text)
        assertEquals("bot_catalog_venue:11", buttons[1].callbackData)
    }

    @Test
    fun `inline bot venue catalog can render favorite venues entry`() {
        val markup =
            TelegramKeyboards.inlineBotVenueCatalog(
                venues = listOf(10L to "First"),
                includeFavoritesEntry = true,
            )
        val buttons = markup.inlineKeyboard.flatten()

        assertEquals("⭐ Избранные заведения", buttons[0].text)
        assertEquals("fav_v_list", buttons[0].callbackData)
        assertEquals("First", buttons[1].text)
    }

    @Test
    fun `favorite venues keyboard supports catalog and profile back navigation`() {
        val catalogBack = TelegramKeyboards.inlineFavoriteVenues(emptyList()).inlineKeyboard.flatten().single()
        val profileBack =
            TelegramKeyboards.inlineFavoriteVenues(
                venues = emptyList(),
                backText = "↩️ К профилю",
                backCallbackData = "guest_profile_open",
            ).inlineKeyboard.flatten().single()

        assertEquals("↩️ К каталогу", catalogBack.text)
        assertEquals("bot_catalog_open", catalogBack.callbackData)
        assertEquals("↩️ К профилю", profileBack.text)
        assertEquals("guest_profile_open", profileBack.callbackData)
    }

    @Test
    fun `inline bot venue card actions renders compact venue actions`() {
        val markup = TelegramKeyboards.inlineBotVenueCardActions(42L, "https://yandex.ru/maps/?text=test")
        val buttons = markup.inlineKeyboard.flatten()

        assertEquals("🪑 Забронировать стол", buttons[0].text)
        assertEquals("bot_catalog_venue_book:42", buttons[0].callbackData)
        assertFalse(buttons.any { it.text == "🍽 Меню" || it.callbackData == "bot_catalog_venue_menu:42" })
        assertEquals("ℹ️ Информация", buttons[1].text)
        assertEquals("bot_catalog_venue_about:42", buttons[1].callbackData)
        assertEquals("🗺 Маршрут", buttons[2].text)
        assertEquals("https://yandex.ru/maps/?text=test", buttons[2].url)
        assertEquals(null, buttons[2].callbackData)
        assertEquals("↩️ К каталогу", buttons[3].text)
        assertEquals("bot_catalog_open", buttons[3].callbackData)
    }

    @Test
    fun `inline bot venue card actions can render favorite action`() {
        val markup =
            TelegramKeyboards.inlineBotVenueCardActions(
                venueId = 42L,
                favoriteActionText = "⭐ В избранное",
                favoriteActionCallbackData = "fav_v_add:42",
            )
        val buttons = markup.inlineKeyboard.flatten()

        assertEquals(true, buttons.any { it.text == "⭐ В избранное" && it.callbackData == "fav_v_add:42" })
    }

    @Test
    fun `inline bot venue card actions can render promotions action`() {
        val markup =
            TelegramKeyboards.inlineBotVenueCardActions(
                venueId = 42L,
                promotionsActionText = "🎁 Акции заведения",
                promotionsActionCallbackData = "gp_v:42",
            )
        val buttons = markup.inlineKeyboard.flatten()

        assertEquals(true, buttons.any { it.text == "🎁 Акции заведения" && it.callbackData == "gp_v:42" })
    }

    @Test
    fun `inline bot venue menu sections renders section callbacks and back action`() {
        val markup =
            TelegramKeyboards.inlineBotVenueMenuSections(
                venueId = 42L,
                sections = listOf(10L to "Кальяны", 11L to "Напитки"),
            )
        val buttons = markup.inlineKeyboard.flatten()

        assertEquals("Кальяны", buttons[0].text)
        assertEquals("bot_catalog_venue_menu_section:42:10", buttons[0].callbackData)
        assertEquals("Напитки", buttons[1].text)
        assertEquals("bot_catalog_venue_menu_section:42:11", buttons[1].callbackData)
        assertEquals("⬅️ К карточке заведения", buttons[2].text)
        assertEquals("bot_catalog_venue:42", buttons[2].callbackData)
    }

    @Test
    fun `inline bot venue menu section actions renders navigation buttons`() {
        val markup = TelegramKeyboards.inlineBotVenueMenuSectionActions(42L)
        val buttons = markup.inlineKeyboard.flatten()

        assertEquals("⬅️ К разделам меню", buttons[0].text)
        assertEquals("bot_catalog_venue_menu:42", buttons[0].callbackData)
        assertEquals("⬅️ К карточке заведения", buttons[1].text)
        assertEquals("bot_catalog_venue:42", buttons[1].callbackData)
    }

    @Test
    fun `inline bot venue booking date actions renders nearest dates and navigation`() {
        val markup =
            TelegramKeyboards.inlineBotVenueBookingDateActions(
                venueId = 42L,
                dateButtons =
                    listOf(
                        "Сегодня (вт, 01.04)" to "2026-04-01",
                        "Завтра (ср, 02.04)" to "2026-04-02",
                        "Послезавтра (чт, 03.04)" to "2026-04-03",
                    ),
                nextOffset = 3,
            )
        val buttons = markup.inlineKeyboard.flatten()

        assertEquals("Сегодня (вт, 01.04)", buttons[0].text)
        assertEquals("bot_catalog_venue_book_date:42:2026-04-01", buttons[0].callbackData)
        assertEquals("Завтра (ср, 02.04)", buttons[1].text)
        assertEquals("bot_catalog_venue_book_date:42:2026-04-02", buttons[1].callbackData)
        assertEquals("Послезавтра (чт, 03.04)", buttons[2].text)
        assertEquals("bot_catalog_venue_book_date:42:2026-04-03", buttons[2].callbackData)
        assertEquals("📅 Ещё даты", buttons[3].text)
        assertEquals("bot_catalog_venue_book_more:42:3", buttons[3].callbackData)
        assertEquals("⬅️ Назад", buttons[4].text)
        assertEquals("bot_catalog_venue:42", buttons[4].callbackData)
    }

    @Test
    fun `inline bot venue booking time actions can render four time buttons per row`() {
        val markup =
            TelegramKeyboards.inlineBotVenueBookingTimeActions(
                venueId = 42L,
                isoDate = "2026-04-03",
                timeSlots = listOf("14:00", "14:30", "15:00", "15:30", "16:00"),
                buttonsPerRow = 4,
            )

        assertEquals(listOf("14:00", "14:30", "15:00", "15:30"), markup.inlineKeyboard[0].map { it.text })
        assertEquals(listOf("16:00"), markup.inlineKeyboard[1].map { it.text })
        assertEquals("bot_catalog_venue_book_time:42:2026-04-03:14:30", markup.inlineKeyboard[0][1].callbackData)
    }

    @Test
    fun `inline bot venue booking comment actions can go back to guest count`() {
        val markup =
            TelegramKeyboards.inlineBotVenueBookingCommentActions(
                venueId = 42L,
                isoDate = "2026-04-03",
                time = "14:30",
                guestsToken = "3",
            )
        val inputMarkup =
            TelegramKeyboards.inlineBotVenueBookingCommentInputActions(
                venueId = 42L,
                isoDate = "2026-04-03",
                time = "14:30",
            )

        assertEquals("↩️ Назад", markup.inlineKeyboard[2][0].text)
        assertEquals("bot_catalog_venue_book_time:42:2026-04-03:14:30", markup.inlineKeyboard[2][0].callbackData)
        assertEquals("↩️ Назад", inputMarkup.inlineKeyboard[0][0].text)
        assertEquals("bot_catalog_venue_book_time:42:2026-04-03:14:30", inputMarkup.inlineKeyboard[0][0].callbackData)
    }

    @Test
    fun `inline venue staff booking actions render confirm and message`() {
        val markup = TelegramKeyboards.inlineVenueStaffBookingActions(venueId = 10L, bookingId = 77L, canConfirm = true)
        val buttons = markup.inlineKeyboard.flatten()

        assertEquals("✅ Подтвердить", buttons[0].text)
        assertEquals("staff_booking_confirm:10:77", buttons[0].callbackData)
        assertEquals("✉️ Написать гостю", buttons[1].text)
        assertEquals("staff_booking_message:10:77", buttons[1].callbackData)
        assertEquals("❌ Отменить бронь", buttons[2].text)
        assertEquals("staff_booking_cancel_ask:10:77", buttons[2].callbackData)
        assertTrue(buttons.none { it.callbackData?.startsWith("staff_booking_seated_ask:") == true })
        assertTrue(buttons.none { it.callbackData?.startsWith("staff_booking_noshow_ask:") == true })
    }

    @Test
    fun `inline venue staff booking actions can render arrival only`() {
        val buttons =
            TelegramKeyboards
                .inlineVenueStaffBookingActions(
                    venueId = 10L,
                    bookingId = 77L,
                    canConfirm = false,
                    canCancel = false,
                    canMarkVisit = true,
                    canMessageGuest = false,
                ).inlineKeyboard
                .flatten()

        assertEquals(listOf("✅ Гость пришёл", "🚫 Не пришёл"), buttons.map { it.text })
        assertEquals("staff_booking_seated_ask:10:77", buttons[0].callbackData)
        assertEquals("staff_booking_noshow_ask:10:77", buttons[1].callbackData)
    }

    @Test
    fun `inline venue staff booking actions can hide active buttons for terminal booking`() {
        val buttons =
            TelegramKeyboards
                .inlineVenueStaffBookingActions(
                    venueId = 10L,
                    bookingId = 77L,
                    canConfirm = false,
                    canCancel = false,
                    canMarkVisit = false,
                ).inlineKeyboard
                .flatten()

        assertEquals(listOf("✉️ Написать гостю"), buttons.map { it.text })
    }

    @Test
    fun `inline venue staff booking visit confirm renders confirm and back`() {
        val seatedButtons =
            TelegramKeyboards
                .inlineVenueStaffBookingVisitConfirmActions(10L, 77L, "seated")
                .inlineKeyboard
                .flatten()
        val noShowButtons =
            TelegramKeyboards
                .inlineVenueStaffBookingVisitConfirmActions(10L, 77L, "noshow")
                .inlineKeyboard
                .flatten()

        assertEquals("✅ Да, отметить", seatedButtons[0].text)
        assertEquals("staff_booking_seated_yes:10:77", seatedButtons[0].callbackData)
        assertEquals("↩️ Назад", seatedButtons[1].text)
        assertEquals("staff_booking_cancel_back:10:77", seatedButtons[1].callbackData)
        assertEquals("staff_booking_noshow_yes:10:77", noShowButtons[0].callbackData)
    }

    @Test
    fun `inline venue staff booking cancel reason renders reasons and back`() {
        val buttons = TelegramKeyboards.inlineVenueStaffBookingCancelReasonActions(10L, 77L).inlineKeyboard.flatten()

        assertEquals("Нет мест", buttons[0].text)
        assertEquals("sbc_r:10:77:no_seats", buttons[0].callbackData)
        assertEquals("Заведение закрыто", buttons[1].text)
        assertEquals("sbc_r:10:77:closed", buttons[1].callbackData)
        assertEquals("Ошибка в бронировании", buttons[2].text)
        assertEquals("sbc_r:10:77:booking_error", buttons[2].callbackData)
        assertEquals("Другое", buttons[3].text)
        assertEquals("sbc_r:10:77:other", buttons[3].callbackData)
        assertEquals("↩️ Назад", buttons[4].text)
        assertEquals("staff_booking_cancel_back:10:77", buttons[4].callbackData)
    }

    @Test
    fun `inline venue staff booking cancel confirm renders confirm and back`() {
        val buttons =
            TelegramKeyboards
                .inlineVenueStaffBookingCancelConfirmActions(10L, 77L, "no_seats")
                .inlineKeyboard
                .flatten()

        assertEquals("✅ Да, отменить бронь", buttons[0].text)
        assertEquals("sbc_y:10:77:no_seats", buttons[0].callbackData)
        assertEquals("↩️ Назад", buttons[1].text)
        assertEquals("staff_booking_cancel_ask:10:77", buttons[1].callbackData)
    }

    @Test
    fun `inline guest booking reply renders reply callback`() {
        val buttons = TelegramKeyboards.inlineGuestBookingReplyActions(10L, 77L).inlineKeyboard.flatten()

        assertEquals("↩️ Ответить", buttons[0].text)
        assertEquals("guest_booking_reply:10:77", buttons[0].callbackData)
    }

    @Test
    fun `inline booking reminder actions render guest callbacks`() {
        val buttons = TelegramKeyboards.inlineBookingReminderActions(77L, reminderId = 900L).inlineKeyboard.flatten()

        assertEquals("✅ Да, буду", buttons[0].text)
        assertEquals("br_ok:77:900", buttons[0].callbackData)
        assertEquals("🔄 Перенести", buttons[1].text)
        assertEquals("br_reschedule:77", buttons[1].callbackData)
        assertEquals("❌ Отменить", buttons[2].text)
        assertEquals("br_cancel:77", buttons[2].callbackData)
    }

    @Test
    fun `confirmed booking reminder actions omit attendance callback`() {
        val buttons = TelegramKeyboards.inlineBookingReminderConfirmedActions(77L).inlineKeyboard.flatten()

        assertEquals(listOf("🔄 Перенести", "❌ Отменить"), buttons.map { it.text })
        assertEquals(listOf("br_reschedule:77", "br_cancel:77"), buttons.map { it.callbackData })
    }

    @Test
    fun `inline booking reminder cancel confirm renders confirm and back`() {
        val buttons = TelegramKeyboards.inlineBookingReminderCancelConfirm(77L).inlineKeyboard.flatten()

        assertEquals("✅ Да, отменить", buttons[0].text)
        assertEquals("br_cancel_yes:77", buttons[0].callbackData)
        assertEquals("↩️ Назад", buttons[1].text)
        assertEquals("br_back:77", buttons[1].callbackData)
    }

    @Test
    fun `table entry choice includes mini app web app and bot fallback`() {
        val markup = TelegramKeyboards.inlineTableEntryChoice("https://mini.app/miniapp/", "abc123")
        val buttons = markup.inlineKeyboard.flatten()

        assertEquals(2, buttons.size)
        assertEquals("📱 Заказывать в Mini App", buttons[0].text)
        assertEquals(null, buttons[0].url)
        assertEquals(null, buttons[0].callbackData)
        val webAppUrl = buttons[0].webApp?.url.orEmpty()
        assertTrue(webAppUrl.contains("mode=guest"))
        assertTrue(webAppUrl.contains("tableToken=abc123"))
        assertTrue(webAppUrl.contains("screen=menu"))
        assertFalse(webAppUrl.contains("start_param"))
        assertFalse(webAppUrl.contains("tgWebAppData"))
        assertEquals("💬 Заказывать в боте", buttons[1].text)
        assertEquals("continue_in_bot", buttons[1].callbackData)
        assertEquals(null, buttons[1].webApp)
    }

    @Test
    fun `table entry choice includes active table session when provided`() {
        val markup =
            TelegramKeyboards.inlineTableEntryChoice(
                "https://mini.app/miniapp/",
                "abc123",
                tableSessionId = 55L,
            )
        val webAppUrl = markup.inlineKeyboard.flatten()[0].webApp?.url.orEmpty()

        assertTrue(webAppUrl.contains("tableToken=abc123"))
        assertTrue(webAppUrl.contains("tableSessionId=55"))
        assertFalse(webAppUrl.contains("tgWebAppData"))
    }

    @Test
    fun `table entry choice reports missing mini app url safely`() {
        val markup = TelegramKeyboards.inlineTableEntryChoice(null, "abc123")
        val buttons = markup.inlineKeyboard.flatten()

        assertEquals("📱 Mini App не настроен", buttons[0].text)
        assertEquals("guest_miniapp_unavailable", buttons[0].callbackData)
        assertEquals(null, buttons[0].webApp)
        assertEquals("💬 Заказывать в боте", buttons[1].text)
    }

    @Test
    fun `table context bot flow menu button does not have web app link`() {
        val context =
            TableContext(
                venueId = 1L,
                venueName = "Venue",
                tableId = 2L,
                tableNumber = 3,
                tableToken = "token",
                staffChatId = null,
            )
        val markup = TelegramKeyboards.tableContextBotFlow(context)
        val openMenuButton = markup.keyboard.flatten().first { it.text == "🍽️ Меню" }

        assertEquals(null, openMenuButton.webApp)
    }

    @Test
    fun `table context bot flow includes explicit cart entry`() {
        val context =
            TableContext(
                venueId = 1L,
                venueName = "Venue",
                tableId = 2L,
                tableNumber = 3,
                tableToken = "token",
                staffChatId = null,
            )
        val markup = TelegramKeyboards.tableContextBotFlow(context)
        val texts = markup.keyboard.flatten().map { it.text }

        assertEquals(true, texts.contains("🧺 Корзина"))
    }

    @Test
    fun `table context bot flow includes explicit split bill entry`() {
        val context =
            TableContext(
                venueId = 1L,
                venueName = "Venue",
                tableId = 2L,
                tableNumber = 3,
                tableToken = "token",
                staffChatId = null,
            )
        val markup = TelegramKeyboards.tableContextBotFlow(context)
        val texts = markup.keyboard.flatten().map { it.text }

        assertEquals(true, texts.contains("👥 Общий счёт"))
    }

    @Test
    fun `table context bot flow does not include shift extension service entry`() {
        val context =
            TableContext(
                venueId = 1L,
                venueName = "Venue",
                tableId = 2L,
                tableNumber = 3,
                tableToken = "token",
                staffChatId = null,
            )
        val markup = TelegramKeyboards.tableContextBotFlow(context)
        val texts = markup.keyboard.flatten().map { it.text }

        assertEquals(false, texts.contains("Продление работы заведения"))
    }

    @Test
    fun `table context bot flow includes visit history entry`() {
        val context =
            TableContext(
                venueId = 1L,
                venueName = "Venue",
                tableId = 2L,
                tableNumber = 3,
                tableToken = "token",
                staffChatId = null,
            )
        val markup = TelegramKeyboards.tableContextBotFlow(context)
        val texts = markup.keyboard.flatten().map { it.text }

        assertEquals(true, texts.contains("📜 История"))
    }

    @Test
    fun `table context bot flow includes profile entry`() {
        val context =
            TableContext(
                venueId = 1L,
                venueName = "Venue",
                tableId = 2L,
                tableNumber = 3,
                tableToken = "token",
                staffChatId = null,
            )
        val markup = TelegramKeyboards.tableContextBotFlow(context)
        val texts = markup.keyboard.flatten().map { it.text }

        assertEquals(true, texts.contains("👤 Профиль"))
    }

    @Test
    fun `table context bot flow includes favorite items entry`() {
        val context =
            TableContext(
                venueId = 1L,
                venueName = "Venue",
                tableId = 2L,
                tableNumber = 3,
                tableToken = "token",
                staffChatId = null,
            )
        val markup = TelegramKeyboards.tableContextBotFlow(context)
        val texts = markup.keyboard.flatten().map { it.text }

        assertEquals(true, texts.contains("⭐ Любимое"))
    }

    @Test
    fun `table context bot flow includes venue promotions entry`() {
        val context =
            TableContext(
                venueId = 1L,
                venueName = "Venue",
                tableId = 2L,
                tableNumber = 3,
                tableToken = "token",
                staffChatId = null,
            )
        val markup = TelegramKeyboards.tableContextBotFlow(context)
        val texts = markup.keyboard.flatten().map { it.text }

        assertEquals(true, texts.contains("🎁 Акции заведения"))
    }

    @Test
    fun `table context bot flow uses compact bot table layout`() {
        val context =
            TableContext(
                venueId = 1L,
                venueName = "Venue",
                tableId = 2L,
                tableNumber = 3,
                tableToken = "token",
                staffChatId = null,
            )
        val markup = TelegramKeyboards.tableContextBotFlow(context)
        val rows = markup.keyboard.map { row -> row.map { it.text } }

        assertEquals(
            listOf(
                listOf("📱 Заказывать в Mini App", "💬 Заказывать в боте"),
                listOf("🍽️ Меню", "🧺 Корзина"),
                listOf("📄 Мой заказ", "✍️ Быстрый заказ"),
                listOf("👥 Общий счёт", "🛎 Вызвать персонал"),
                listOf("🎁 Акции заведения", "⭐ Любимое"),
                listOf("📜 История", "👤 Профиль"),
                listOf("🔁 Сменить стол", "🚪 Завершить визит"),
            ),
            rows,
        )
    }

    @Test
    fun `guest mini app entry uses inline web app catalog url`() {
        val markup = TelegramKeyboards.inlineMiniAppEntry("https://mini.app/miniapp/?mode=guest")
        val button = markup?.inlineKeyboard?.flatten()?.single()

        assertEquals("📱 Открыть Mini App", button?.text)
        assertEquals(null, button?.url)
        assertEquals(null, button?.callbackData)
        assertEquals("https://mini.app/miniapp/?mode=guest", button?.webApp?.url)
    }

    @Test
    fun `guest table mini app entry uses inline web app`() {
        val markup =
            TelegramKeyboards.inlineGuestTableMiniAppEntry(
                "https://mini.app/miniapp/?mode=guest&tableToken=token&screen=menu#/venue/10",
            )
        val button = markup?.inlineKeyboard?.flatten()?.single()
        val webAppUrl = button?.webApp?.url.orEmpty()

        assertEquals("📱 Заказывать в Mini App", button?.text)
        assertEquals(null, button?.url)
        assertEquals(null, button?.callbackData)
        assertTrue(webAppUrl.contains("mode=guest"))
        assertTrue(webAppUrl.contains("tableToken=token"))
        assertTrue(webAppUrl.contains("#/venue/10"))
        assertFalse(webAppUrl.contains("tgWebAppData"))
    }

    @Test
    fun `table actions back returns to table actions`() {
        val buttons = TelegramKeyboards.inlineTableActionsBack().inlineKeyboard.flatten()

        assertEquals("↩️ Назад к действиям стола", buttons.single().text)
        assertEquals("table_actions_back", buttons.single().callbackData)
    }

    @Test
    fun `guest shift extension actions return to bot menu categories`() {
        val buttons = TelegramKeyboards.inlineGuestShiftExtensionActions(canRequest = true).inlineKeyboard.flatten()

        assertEquals("Продлить на 1 час", buttons[0].text)
        assertEquals("guest_shift_extension_request", buttons[0].callbackData)
        assertEquals("⬅️ К категориям", buttons[1].text)
        assertEquals("bot_menu_back_categories", buttons[1].callbackData)
    }

    @Test
    fun `table relocation inline actions contain staff call and back buttons`() {
        val markup = TelegramKeyboards.inlineTableRelocationActions()
        val buttons = markup.inlineKeyboard.flatten()

        assertEquals(2, buttons.size)
        assertEquals("🛎 Позвать персонал для смены стола", buttons[0].text)
        assertEquals("relocation_call_staff", buttons[0].callbackData)
        assertEquals("↩️ Назад к действиям стола", buttons[1].text)
        assertEquals("relocation_back_to_table_actions", buttons[1].callbackData)
    }

    @Test
    fun `venue owner onboarding entry uses bot flow and configured mini app link`() {
        val markup = TelegramKeyboards.inlineVenueOwnerOnboardingEntry("https://mini.app")
        val buttons = markup.inlineKeyboard.flatten()

        assertEquals("💬 Настраивать в боте", buttons[0].text)
        assertEquals("owner_venue_onboarding_entry", buttons[0].callbackData)
        assertEquals(null, buttons[0].webApp)
        assertEquals("📱 Открыть Mini App", buttons[1].text)
        assertEquals(null, buttons[1].callbackData)
        assertEquals("https://mini.app?mode=venue", buttons[1].webApp?.url)
    }

    @Test
    fun `venue owner onboarding entry reports missing mini app config without soon copy`() {
        val markup = TelegramKeyboards.inlineVenueOwnerOnboardingEntry(null)
        val buttons = markup.inlineKeyboard.flatten()

        assertEquals("📱 Mini App не настроен", buttons[1].text)
        assertEquals("owner_venue_onboarding_miniapp_unavailable", buttons[1].callbackData)
        assertEquals(null, buttons[1].webApp)
    }

    @Test
    fun `inline bot menu categories renders category callback buttons`() {
        val markup =
            TelegramKeyboards.inlineBotMenuCategories(
                listOf(1L to "Кальяны", 2L to "Напитки"),
            )
        val buttons = markup.inlineKeyboard.flatten()

        assertEquals("Кальяны", buttons[0].text)
        assertEquals("bot_menu_category:1", buttons[0].callbackData)
        assertEquals("Напитки", buttons[1].text)
        assertEquals("bot_menu_category:2", buttons[1].callbackData)
    }

    @Test
    fun `inline bot menu categories can include shift extension service action`() {
        val markup =
            TelegramKeyboards.inlineBotMenuCategories(
                categories = listOf(1L to "Кальяны"),
                includeShiftExtension = true,
            )
        val buttons = markup.inlineKeyboard.flatten()

        assertEquals("Продление работы заведения", buttons[1].text)
        assertEquals("guest_shift_extension_open", buttons[1].callbackData)
    }

    @Test
    fun `inline bot menu categories can return to table actions`() {
        val markup =
            TelegramKeyboards.inlineBotMenuCategories(
                categories = listOf(1L to "Кальяны"),
                includeTableActionsBack = true,
            )
        val buttons = markup.inlineKeyboard.flatten()

        assertEquals("↩️ Назад к действиям стола", buttons[1].text)
        assertEquals("table_actions_back", buttons[1].callbackData)
    }

    @Test
    fun `inline bot menu items renders item callback buttons`() {
        val markup =
            TelegramKeyboards.inlineBotMenuItems(
                categoryId = 10L,
                items = listOf(101L to "Кальян — 250.00 ₽", 102L to "Лимонад — 45.00 ₽"),
                page = 0,
                totalPages = 2,
            )
        val buttons = markup.inlineKeyboard.flatten()

        assertEquals("Кальян — 250.00 ₽", buttons[0].text)
        assertEquals("bot_menu_item:10:101", buttons[0].callbackData)
        assertEquals("Лимонад — 45.00 ₽", buttons[1].text)
        assertEquals("bot_menu_item:10:102", buttons[1].callbackData)
        assertEquals("▶️", buttons[2].text)
        assertEquals("bot_menu_category_page:10:1", buttons[2].callbackData)
        assertEquals("⬅️ К категориям", buttons[3].text)
        assertEquals("bot_menu_back_categories", buttons[3].callbackData)
    }

    @Test
    fun `inline bot menu item navigation renders back buttons`() {
        val markup =
            TelegramKeyboards.inlineBotMenuItemNavigation(
                categoryId = 10L,
                itemId = 101L,
                hasActiveOrder = false,
            )
        val buttons = markup.inlineKeyboard.flatten()

        assertEquals("➕ Добавить в корзину", buttons[0].text)
        assertEquals("bot_menu_item_add_to_cart:10:101", buttons[0].callbackData)
        assertEquals("🧺 Корзина", buttons[1].text)
        assertEquals("bot_menu_item_cart", buttons[1].callbackData)
        assertEquals("🔔 Вызвать персонал", buttons[2].text)
        assertEquals("bot_menu_item_staff", buttons[2].callbackData)
        assertEquals("⬅️ К позициям категории", buttons[3].text)
        assertEquals("bot_menu_back_category:10", buttons[3].callbackData)
        assertEquals("⬅️ К категориям", buttons[4].text)
        assertEquals("bot_menu_back_categories", buttons[4].callbackData)
    }

    @Test
    fun `inline bot menu item navigation renders reorder action for active order`() {
        val markup =
            TelegramKeyboards.inlineBotMenuItemNavigation(
                categoryId = 10L,
                itemId = 101L,
                hasActiveOrder = true,
            )
        val buttons = markup.inlineKeyboard.flatten()

        assertEquals("➕ Дозаказать", buttons[0].text)
        assertEquals("bot_menu_item_reorder:10:101", buttons[0].callbackData)
    }

    @Test
    fun `inline bot menu item navigation can render favorite action`() {
        val markup =
            TelegramKeyboards.inlineBotMenuItemNavigation(
                categoryId = 10L,
                itemId = 101L,
                hasActiveOrder = false,
                favoriteActionText = "⭐ В любимые",
                favoriteActionCallbackData = "fav_i_add:101",
            )
        val buttons = markup.inlineKeyboard.flatten()

        assertEquals(true, buttons.any { it.text == "⭐ В любимые" && it.callbackData == "fav_i_add:101" })
    }

    @Test
    fun `inline post add actions renders back navigation`() {
        val markup = TelegramKeyboards.inlineBotMenuPostAddActions(categoryId = 10L)
        val buttons = markup.inlineKeyboard.flatten()

        assertEquals("🧺 Корзина", buttons[0].text)
        assertEquals("bot_menu_item_cart", buttons[0].callbackData)
        assertEquals("⬅️ К позициям категории", buttons[1].text)
        assertEquals("bot_menu_back_category:10", buttons[1].callbackData)
        assertEquals("⬅️ К категориям", buttons[2].text)
        assertEquals("bot_menu_back_categories", buttons[2].callbackData)
    }

    @Test
    fun `inline cart item actions render qty controls for concrete item`() {
        val markup = TelegramKeyboards.inlineBotMenuCartItemActions(itemId = 11L)
        val buttons = markup.inlineKeyboard.flatten()

        assertEquals("➖", buttons[0].text)
        assertEquals("bot_menu_cart_dec:11", buttons[0].callbackData)
        assertEquals("➕", buttons[1].text)
        assertEquals("bot_menu_cart_inc:11", buttons[1].callbackData)
        assertEquals("🗑️ Удалить", buttons[2].text)
        assertEquals("bot_menu_cart_remove:11", buttons[2].callbackData)
    }

    @Test
    fun `inline cart summary actions render checkout and clear`() {
        val markup = TelegramKeyboards.inlineBotMenuCartSummaryActions()
        val buttons = markup.inlineKeyboard.flatten()

        assertEquals("💬 Добавить комментарий", buttons[0].text)
        assertEquals("bot_menu_cart_comment", buttons[0].callbackData)
        assertEquals("✅ Оформить заказ", buttons[1].text)
        assertEquals("bot_menu_cart_checkout", buttons[1].callbackData)
        assertEquals("🗑️ Очистить корзину", buttons[2].text)
        assertEquals("bot_menu_cart_clear", buttons[2].callbackData)
    }

    @Test
    fun `inline cart actions keep compatibility and include item and summary rows`() {
        val markup = TelegramKeyboards.inlineBotMenuCartActions(itemRefs = listOf(1 to 11L))
        val buttons = markup.inlineKeyboard.flatten()

        assertEquals("➖", buttons[0].text)
        assertEquals("bot_menu_cart_dec:11", buttons[0].callbackData)
        assertEquals("➕", buttons[1].text)
        assertEquals("bot_menu_cart_inc:11", buttons[1].callbackData)
        assertEquals("🗑️ Удалить", buttons[2].text)
        assertEquals("bot_menu_cart_remove:11", buttons[2].callbackData)
        assertEquals("💬 Добавить комментарий", buttons[3].text)
        assertEquals("bot_menu_cart_comment", buttons[3].callbackData)
        assertEquals("✅ Оформить заказ", buttons[4].text)
        assertEquals("bot_menu_cart_checkout", buttons[4].callbackData)
        assertEquals("🗑️ Очистить корзину", buttons[5].text)
        assertEquals("bot_menu_cart_clear", buttons[5].callbackData)
    }

    @Test
    fun `inline reorder action keeps only reorder button`() {
        val markup = TelegramKeyboards.inlineBotReorderAction()
        val buttons = markup.inlineKeyboard.flatten()

        assertEquals("➕ Дозаказать", buttons[0].text)
        assertEquals("bot_menu_reorder_entry", buttons[0].callbackData)
    }

    @Test
    fun `inline post checkout actions render product next steps`() {
        val markup = TelegramKeyboards.inlineBotPostCheckoutActions()
        val buttons = markup.inlineKeyboard.flatten()

        assertEquals("📄 Мой заказ", buttons[0].text)
        assertEquals("bot_active_order_view", buttons[0].callbackData)
        assertEquals("➕ Дозаказать", buttons[1].text)
        assertEquals("bot_menu_reorder_entry", buttons[1].callbackData)
        assertEquals("🔔 Вызвать персонал", buttons[2].text)
        assertEquals("bot_menu_item_staff", buttons[2].callbackData)
    }

    @Test
    fun `inline active order actions keep reorder and staff`() {
        val markup = TelegramKeyboards.inlineBotActiveOrderActions()
        val buttons = markup.inlineKeyboard.flatten()

        assertEquals("➕ Дозаказать", buttons[0].text)
        assertEquals("bot_menu_reorder_entry", buttons[0].callbackData)
        assertEquals("🔔 Вызвать персонал", buttons[1].text)
        assertEquals("bot_menu_item_staff", buttons[1].callbackData)
    }

    @Test
    fun `inline shared next actions render menu cart and active order`() {
        val markup = TelegramKeyboards.inlineBotSharedNextActions()
        val buttons = markup.inlineKeyboard.flatten()

        assertEquals("🍽️ Меню", buttons[0].text)
        assertEquals("bot_menu_back_categories", buttons[0].callbackData)
        assertEquals("🧺 Корзина", buttons[1].text)
        assertEquals("bot_menu_item_cart", buttons[1].callbackData)
        assertEquals("📄 Мой заказ", buttons[2].text)
        assertEquals("bot_active_order_view", buttons[2].callbackData)
    }

    @Test
    fun `inline split bill entry actions render create and join`() {
        val markup = TelegramKeyboards.inlineBotSplitBillEntryActions()
        val buttons = markup.inlineKeyboard.flatten()

        assertEquals("👥 Создать общий счёт", buttons[0].text)
        assertEquals("bot_tabs_create_shared", buttons[0].callbackData)
        assertEquals("🔑 Присоединиться к общему счёту", buttons[1].text)
        assertEquals("bot_tabs_join_prompt", buttons[1].callbackData)
    }

    @Test
    fun `inline continue in bot actions render quick entries`() {
        val markup = TelegramKeyboards.inlineBotContinueInBotActions()
        val buttons = markup.inlineKeyboard.flatten()

        assertEquals("🍽️ Меню", buttons[0].text)
        assertEquals("bot_menu_back_categories", buttons[0].callbackData)
        assertEquals("🧺 Корзина", buttons[1].text)
        assertEquals("bot_menu_item_cart", buttons[1].callbackData)
        assertEquals("👥 Общий счёт", buttons[2].text)
        assertEquals("bot_open_split_bill_entry", buttons[2].callbackData)
        assertEquals("📄 Мой заказ", buttons[3].text)
        assertEquals("bot_active_order_view", buttons[3].callbackData)
    }

    @Test
    fun `inline staff call reasons render consultation coals and bill`() {
        val markup = TelegramKeyboards.inlineStaffCallReasons()
        val buttons = markup.inlineKeyboard.flatten()

        assertEquals("💬 Консультация", buttons[0].text)
        assertEquals("staff_call_reason:COME", buttons[0].callbackData)
        assertEquals("🔥 Заменить угли", buttons[1].text)
        assertEquals("staff_call_reason:COALS", buttons[1].callbackData)
        assertEquals("🧾 Принести счёт", buttons[2].text)
        assertEquals("staff_call_reason:BILL", buttons[2].callbackData)
    }

    @Test
    fun `inline staff call bill payment methods render card and cash`() {
        val markup = TelegramKeyboards.inlineStaffCallBillPaymentMethods()
        val buttons = markup.inlineKeyboard.flatten()

        assertEquals("💳 Картой", buttons[0].text)
        assertEquals("staff_call_bill_payment:CARD", buttons[0].callbackData)
        assertEquals("💵 Наличными", buttons[1].text)
        assertEquals("staff_call_bill_payment:CASH", buttons[1].callbackData)
    }

    @Test
    fun `inline venue connection contact choice renders username and additional actions`() {
        val markup = TelegramKeyboards.inlineVenueConnectionContactChoice()
        val buttons = markup.inlineKeyboard.flatten()

        assertEquals("✅ Оставить только @username", buttons[0].text)
        assertEquals("venue_connect_contact_use_username", buttons[0].callbackData)
        assertEquals("➕ Добавить телефон или email", buttons[1].text)
        assertEquals("venue_connect_contact_additional", buttons[1].callbackData)
    }

    @Test
    fun `inline venue connection pending actions render edit and cancel callbacks`() {
        val markup = TelegramKeyboards.inlineVenueConnectionPendingActions(42L)
        val buttons = markup.inlineKeyboard.flatten()

        assertEquals("✏️ Изменить заявку", buttons[0].text)
        assertEquals("venue_connect_pending_edit:42", buttons[0].callbackData)
        assertEquals("❌ Отменить заявку", buttons[1].text)
        assertEquals("venue_connect_pending_cancel:42", buttons[1].callbackData)
    }

    @Test
    fun `inline owner venue connection request actions render approve and reject callbacks`() {
        val markup = TelegramKeyboards.inlineOwnerVenueConnectionRequestActions(77L)
        val buttons = markup.inlineKeyboard.flatten()

        assertEquals("✅ Принять", buttons[0].text)
        assertEquals("owner_venue_connect_approve:77", buttons[0].callbackData)
        assertEquals("❌ Отклонить", buttons[1].text)
        assertEquals("owner_venue_connect_reject:77", buttons[1].callbackData)
        assertEquals("⬅️ К заявкам", buttons[2].text)
        assertEquals("owner_venue_requests_back", buttons[2].callbackData)
    }

    @Test
    fun `inline owner approved venue connection actions render next step callbacks`() {
        val markup = TelegramKeyboards.inlineOwnerVenueConnectionApprovedActions(88L)
        val buttons = markup.inlineKeyboard.flatten()

        assertEquals("⚙️ Коммерческие условия", buttons[0].text)
        assertEquals("owner_venue_terms_open:88", buttons[0].callbackData)
        assertEquals("🧹 Закрыть без создания", buttons[1].text)
        assertEquals("owner_venue_connect_close:88", buttons[1].callbackData)
        assertEquals("⬅️ К заявкам", buttons[2].text)
        assertEquals("owner_venue_requests_back", buttons[2].callbackData)
    }

    @Test
    fun `inline owner approved venue connection actions can render create action when terms are ready`() {
        val markup = TelegramKeyboards.inlineOwnerVenueConnectionApprovedActions(88L, canCreateVenue = true)
        val buttons = markup.inlineKeyboard.flatten()

        assertEquals("✅ Создать и передать владельцу", buttons[0].text)
        assertEquals("owner_venue_create_from_request:88", buttons[0].callbackData)
        assertEquals("✏️ Изменить условия", buttons[1].text)
        assertEquals("owner_venue_terms_open:88", buttons[1].callbackData)
        assertEquals("🧹 Закрыть без создания", buttons[2].text)
        assertEquals("owner_venue_connect_close:88", buttons[2].callbackData)
        assertEquals("⬅️ К заявкам", buttons[3].text)
        assertEquals("owner_venue_requests_back", buttons[3].callbackData)
    }

    @Test
    fun `inline owner commercial zero price confirmation renders confirm and back callbacks`() {
        val buttons = TelegramKeyboards.inlineOwnerVenueCommercialZeroPriceConfirmActions(88L).inlineKeyboard.flatten()

        assertEquals("✅ Да, бесплатно", buttons[0].text)
        assertEquals("owner_venue_terms_zero_price:88:confirm", buttons[0].callbackData)
        assertEquals("✏️ Ввести другую цену", buttons[1].text)
        assertEquals("owner_venue_terms_open:88", buttons[1].callbackData)
        assertEquals("⬅️ К заявкам", buttons[2].text)
        assertEquals("owner_venue_requests_back", buttons[2].callbackData)
    }

    @Test
    fun `inline owner venue commercial future price actions include back to requests`() {
        val buttons = TelegramKeyboards.inlineOwnerVenueCommercialFuturePriceActions(88L).inlineKeyboard.flatten()

        assertEquals("Не задавать", buttons[0].text)
        assertEquals("owner_venue_terms_future:88:skip", buttons[0].callbackData)
        assertEquals("Задать будущую стоимость", buttons[1].text)
        assertEquals("owner_venue_terms_future:88:set", buttons[1].callbackData)
        assertEquals("⬅️ К заявкам", buttons[2].text)
        assertEquals("owner_venue_requests_back", buttons[2].callbackData)
    }

    @Test
    fun `inline venue owner tables root actions render list add and qr callbacks`() {
        val markup = TelegramKeyboards.inlineVenueOwnerTablesRootActions(15L)
        val buttons = markup.inlineKeyboard.flatten()

        assertEquals("📋 Список столов", buttons[0].text)
        assertEquals("owner_venue_tables_list:15", buttons[0].callbackData)
        assertEquals("➕ Добавить стол", buttons[1].text)
        assertEquals("owner_venue_tables_add:15", buttons[1].callbackData)
        assertEquals("🧾 QR-коды", buttons[2].text)
        assertEquals("owner_venue_tables_qr:15", buttons[2].callbackData)
        assertEquals("↩️ Назад к настройке", buttons[3].text)
        assertEquals("owner_venue_hub_setup:15", buttons[3].callbackData)
    }

    @Test
    fun `inline venue owner table actions render edit capacity qr toggle rotate and back callbacks for owner`() {
        val markup =
            TelegramKeyboards.inlineVenueOwnerTableActions(
                venueId = 15L,
                tableId = 25L,
                isActive = true,
                canRotateQr = true,
            )
        val buttons = markup.inlineKeyboard.flatten()

        assertEquals("✏️ Изменить номер", buttons[0].text)
        assertEquals("owner_venue_tables_table_rename:15:25", buttons[0].callbackData)
        assertEquals("👥 Изменить вместимость", buttons[1].text)
        assertEquals("owner_venue_tables_table_capacity:15:25", buttons[1].callbackData)
        assertEquals("🧾 Показать QR", buttons[2].text)
        assertEquals("owner_venue_tables_qr_table:15:25", buttons[2].callbackData)
        assertEquals("🔁 Перевыпустить QR", buttons[3].text)
        assertEquals("owner_venue_tables_qr_rotate:15:25", buttons[3].callbackData)
        assertEquals("🚫 Отключить стол", buttons[4].text)
        assertEquals("owner_venue_tables_table_toggle:15:25", buttons[4].callbackData)
        assertEquals("⬅️ К списку столов", buttons[5].text)
        assertEquals("owner_venue_tables_list:15", buttons[5].callbackData)
    }

    @Test
    fun `inline venue owner table actions hide rotate for manager and render enable callback for inactive table`() {
        val markup =
            TelegramKeyboards.inlineVenueOwnerTableActions(
                venueId = 15L,
                tableId = 25L,
                isActive = false,
                canRotateQr = false,
            )
        val buttons = markup.inlineKeyboard.flatten()

        assertEquals(false, buttons.any { it.text == "🔁 Перевыпустить QR" })
        assertEquals("✅ Включить стол", buttons[3].text)
        assertEquals("owner_venue_tables_table_toggle:15:25", buttons[3].callbackData)
        assertEquals("⬅️ К списку столов", buttons[4].text)
        assertEquals("owner_venue_tables_list:15", buttons[4].callbackData)
    }

    @Test
    fun `inline venue owner table qr fallback actions render generator url and back callback`() {
        val markup = TelegramKeyboards.inlineVenueOwnerTableQrFallbackActions(15L, 25L)
        val buttons = markup.inlineKeyboard.flatten()

        assertEquals("🧾 Открыть генератор QR", buttons[0].text)
        assertEquals("https://www.qrcode-monkey.com/", buttons[0].url)
        assertEquals(null, buttons[0].callbackData)
        assertEquals("⬅️ К столу", buttons[1].text)
        assertEquals("owner_venue_tables_table:15:25", buttons[1].callbackData)
    }

    @Test
    fun `inline venue owner order menu item actions render flavor profile entry for hookah items`() {
        val markup =
            TelegramKeyboards.inlineVenueOwnerOrderMenuItemActions(
                venueId = 11L,
                sectionId = 22L,
                itemId = 33L,
                isAvailable = true,
                showFlavorProfile = true,
            )
        val buttons = markup.inlineKeyboard.flatten()

        assertEquals("🍓 Вкусы / варианты", buttons[0].text)
        assertEquals("owner_venue_order_menu_item_flavors:11:22:33", buttons[0].callbackData)
        assertEquals("💰 Цена", buttons[1].text)
        assertEquals("✏️ Название", buttons[2].text)
        assertEquals("🚫 Наличие / стоп-лист", buttons[3].text)
        assertEquals("⚙️ Дополнительно", buttons[4].text)
        assertEquals("owner_venue_order_menu_item_more:11:22:33", buttons[4].callbackData)
        assertFalse(buttons.any { it.text == "🏷 Тип позиции" })
    }

    @Test
    fun `inline venue owner order menu item advanced actions render item type override`() {
        val markup =
            TelegramKeyboards.inlineVenueOwnerOrderMenuItemAdvancedActions(
                venueId = 11L,
                sectionId = 22L,
                itemId = 33L,
            )
        val buttons = markup.inlineKeyboard.flatten()

        assertEquals("🏷 Тип позиции", buttons[0].text)
        assertEquals("omt_i:11:22:33", buttons[0].callbackData)
        assertEquals("↩️ Назад к позиции", buttons[1].text)
        assertEquals("owner_venue_order_menu_item:11:22:33", buttons[1].callbackData)
    }

    @Test
    fun `inline venue owner order menu section actions do not render category flavor normalize action`() {
        val markup =
            TelegramKeyboards.inlineVenueOwnerOrderMenuSectionActions(
                venueId = 11L,
                sectionId = 22L,
                itemButtons = listOf(33L to "Авторский кальян — 850 ₽"),
            )
        val buttons = markup.inlineKeyboard.flatten()

        assertTrue(buttons.any { it.text == "Авторский кальян — 850 ₽" })
        assertTrue(buttons.any { it.text == "➕ Добавить позицию" })
        assertTrue(buttons.any { it.text == "✏️ Переименовать раздел" })
        assertTrue(buttons.any { it.text == "🏷 Тип раздела" })
        assertTrue(buttons.any { it.text == "🗑 Удалить раздел" })
        assertTrue(buttons.any { it.text == "⬅️ Назад к меню" })
        assertFalse(buttons.any { it.text.contains("Привести все позиции") })
        assertFalse(buttons.any { it.text.contains("Сбросить вкусы всех") })
        assertFalse(buttons.any { it.text == "⚙️ Дополнительно" })
        assertFalse(buttons.any { it.callbackData?.startsWith("owner_venue_section_flavors_norm_") == true })
    }

    @Test
    fun `inline venue owner order menu type actions render human labels`() {
        val categoryMarkup =
            TelegramKeyboards.inlineVenueOwnerOrderMenuCategoryTypeActions(
                venueId = 11L,
                sectionId = 22L,
                typeButtons =
                    listOf(
                        "HOOKAH" to "Кальяны",
                        "TEA" to "Чай",
                        "DRINK" to "Напитки",
                        "FOOD" to "Еда",
                        "OTHER" to "Другое",
                    ),
            )
        val categoryButtons = categoryMarkup.inlineKeyboard.flatten()
        assertEquals("Кальяны", categoryButtons[0].text)
        assertEquals("omt_cs:11:22:HOOKAH", categoryButtons[0].callbackData)
        assertTrue(categoryButtons.any { it.text == "Другое" })
        assertTrue(
            categoryButtons.any { it.text == "↩️ Назад" && it.callbackData == "owner_venue_order_menu_section:11:22" },
        )

        val itemMarkup =
            TelegramKeyboards.inlineVenueOwnerOrderMenuItemTypeActions(
                venueId = 11L,
                sectionId = 22L,
                itemId = 33L,
                typeButtons =
                    listOf(
                        "HOOKAH" to "Кальяны",
                        "TEA" to "Чай",
                        "DRINK" to "Напитки",
                        "FOOD" to "Еда",
                        "OTHER" to "Другое",
                    ),
            )
        val itemButtons = itemMarkup.inlineKeyboard.flatten()
        assertEquals("Как у раздела", itemButtons[0].text)
        assertEquals("omt_is:11:22:33:INHERIT", itemButtons[0].callbackData)
        assertTrue(itemButtons.any { it.text == "Напитки" && it.callbackData == "omt_is:11:22:33:DRINK" })
        assertTrue(
            itemButtons.any { it.text == "↩️ Назад" && it.callbackData == "owner_venue_order_menu_item_more:11:22:33" },
        )
    }

    @Test
    fun `inline venue owner order menu flavor list actions render option and add callbacks`() {
        val markup =
            TelegramKeyboards.inlineVenueOwnerOrderMenuFlavorListActions(
                venueId = 11L,
                sectionId = 22L,
                itemId = 33L,
                flavorButtons = listOf(44L to "Ягодный", 45L to "Фруктовый"),
                baseProfileButtons =
                    listOf(
                        0 to "Ягодный",
                        1 to "Фруктовый",
                        2 to "Цитрусовый",
                        3 to "Десертный",
                        4 to "Освежающий / мятный",
                        5 to "Напиточный",
                        6 to "Пряный",
                        7 to "Цветочный",
                    ),
            )
        val buttons = markup.inlineKeyboard.flatten()

        assertEquals("Ягодный", buttons[0].text)
        assertEquals("owner_venue_order_menu_item_option:11:22:33:44", buttons[0].callbackData)
        assertEquals("Фруктовый", buttons[1].text)
        assertEquals("owner_venue_order_menu_item_option:11:22:33:45", buttons[1].callbackData)
        assertEquals("Ягодный", buttons[2].text)
        assertEquals("owner_venue_item_flavor_p:11:22:33:0", buttons[2].callbackData)
        assertEquals("Фруктовый", buttons[3].text)
        assertEquals("owner_venue_item_flavor_p:11:22:33:1", buttons[3].callbackData)
        assertEquals("Цитрусовый", buttons[4].text)
        assertEquals("owner_venue_item_flavor_p:11:22:33:2", buttons[4].callbackData)
        assertEquals("Освежающий / мятный", buttons[6].text)
        assertEquals("owner_venue_item_flavor_p:11:22:33:4", buttons[6].callbackData)
        assertEquals("Цветочный", buttons[9].text)
        assertEquals("owner_venue_item_flavor_p:11:22:33:7", buttons[9].callbackData)
        assertEquals(
            false,
            buttons.any {
                it.text in
                    setOf(
                        "Яблоко",
                        "Кола",
                        "Жвачка",
                        "Ягодные",
                        "Фруктовые",
                        "Цитрусовые",
                        "Освежающий",
                        "Мятный",
                    )
            },
        )
        assertEquals(false, buttons.any { it.text.contains("Убрать") })
        assertEquals("➕ Добавить свой вариант", buttons[10].text)
        assertEquals("owner_venue_order_menu_item_option_add:11:22:33", buttons[10].callbackData)
        assertEquals("✅ Добавить базовые вкусовые профили", buttons[11].text)
        assertEquals("owner_venue_item_flavors_std:11:22:33", buttons[11].callbackData)
        assertEquals("⚙️ Дополнительно", buttons[12].text)
        assertEquals("owner_venue_item_flavors_more:11:22:33", buttons[12].callbackData)
        assertEquals("⬅️ Назад к позиции", buttons[13].text)
        assertEquals("owner_venue_order_menu_item:11:22:33", buttons[13].callbackData)
        assertEquals(false, buttons.any { it.text == "♻️ Сбросить к базовым профилям" })
    }

    @Test
    fun `inline venue owner order menu flavor advanced actions render reset and back callbacks`() {
        val markup =
            TelegramKeyboards.inlineVenueOwnerOrderMenuFlavorAdvancedActions(
                venueId = 11L,
                sectionId = 22L,
                itemId = 33L,
            )
        val buttons = markup.inlineKeyboard.flatten()

        assertEquals("♻️ Сбросить к базовым профилям", buttons[0].text)
        assertEquals("owner_venue_item_flavors_norm_ask:11:22:33", buttons[0].callbackData)
        assertEquals("↩️ Назад к вкусам / вариантам", buttons[1].text)
        assertEquals("owner_venue_order_menu_item_flavors:11:22:33", buttons[1].callbackData)
    }

    @Test
    fun `inline venue owner order menu flavor normalize confirm actions render confirm and back callbacks`() {
        val markup =
            TelegramKeyboards.inlineVenueOwnerOrderMenuFlavorNormalizeConfirmActions(
                venueId = 11L,
                sectionId = 22L,
                itemId = 33L,
            )
        val buttons = markup.inlineKeyboard.flatten()

        assertEquals("✅ Да, сбросить к базовым профилям", buttons[0].text)
        assertEquals("owner_venue_item_flavors_norm_confirm:11:22:33", buttons[0].callbackData)
        assertEquals("↩️ Назад", buttons[1].text)
        assertEquals("owner_venue_order_menu_item_flavors:11:22:33", buttons[1].callbackData)
    }

    @Test
    fun `inline venue owner order menu flavor option actions render rename stop and delete callbacks`() {
        val markup =
            TelegramKeyboards.inlineVenueOwnerOrderMenuFlavorOptionActions(
                venueId = 11L,
                sectionId = 22L,
                itemId = 33L,
                optionId = 44L,
                isAvailable = true,
            )
        val buttons = markup.inlineKeyboard.flatten()

        assertEquals("✏️ Переименовать", buttons[0].text)
        assertEquals("owner_venue_order_menu_item_option_rename:11:22:33:44", buttons[0].callbackData)
        assertEquals("🚫 В стоп-лист", buttons[1].text)
        assertEquals("owner_venue_order_menu_item_option_stop:11:22:33:44", buttons[1].callbackData)
        assertEquals("🗑 Удалить вариант", buttons[2].text)
        assertEquals("owner_venue_order_menu_item_option_delete_ask:11:22:33:44", buttons[2].callbackData)
        assertEquals("⬅️ Назад к вкусам / вариантам", buttons[3].text)
        assertEquals("owner_venue_order_menu_item_flavors:11:22:33", buttons[3].callbackData)
    }

    @Test
    fun `inline venue owner order menu flavor delete confirm actions render confirm and back callbacks`() {
        val markup =
            TelegramKeyboards.inlineVenueOwnerOrderMenuFlavorDeleteConfirmActions(
                venueId = 11L,
                sectionId = 22L,
                itemId = 33L,
                optionId = 44L,
            )
        val buttons = markup.inlineKeyboard.flatten()

        assertEquals("✅ Да, удалить", buttons[0].text)
        assertEquals("owner_venue_order_menu_item_option_delete_confirm:11:22:33:44", buttons[0].callbackData)
        assertEquals("↩️ Назад", buttons[1].text)
        assertEquals("owner_venue_order_menu_item_option:11:22:33:44", buttons[1].callbackData)
    }

    @Test
    fun `inline venue owner staff root actions render add callback`() {
        val markup = TelegramKeyboards.inlineVenueOwnerStaffRootActions(17L)
        val buttons = markup.inlineKeyboard.flatten()

        assertEquals("➕ Добавить сотрудника", buttons[0].text)
        assertEquals("owner_venue_staff_add:17", buttons[0].callbackData)
        assertEquals("↩️ Назад к настройке", buttons[1].text)
        assertEquals("owner_venue_hub_setup:17", buttons[1].callbackData)
    }

    @Test
    fun `inline venue owner staff root actions render role change and revoke buttons for owner`() {
        val markup =
            TelegramKeyboards.inlineVenueOwnerStaffRootActions(
                venueId = 17L,
                editableMembers = listOf(101L to "@manager_one", 102L to "Смена 2"),
                removableMembers = listOf(101L to "@manager_one", 102L to "Смена 2"),
            )
        val buttons = markup.inlineKeyboard.flatten()

        assertEquals("✏️ Изменить роль: @manager_one", buttons[0].text)
        assertEquals("owner_venue_staff_role_change_prompt:17:101", buttons[0].callbackData)
        assertEquals("✏️ Изменить роль: Смена 2", buttons[1].text)
        assertEquals("owner_venue_staff_role_change_prompt:17:102", buttons[1].callbackData)
        assertEquals("🗑 Удалить доступ: @manager_one", buttons[2].text)
        assertEquals("owner_venue_staff_remove_prompt:17:101", buttons[2].callbackData)
        assertEquals("🗑 Удалить доступ: Смена 2", buttons[3].text)
        assertEquals("owner_venue_staff_remove_prompt:17:102", buttons[3].callbackData)
        assertEquals("➕ Добавить сотрудника", buttons[4].text)
        assertEquals("owner_venue_staff_add:17", buttons[4].callbackData)
        assertEquals("↩️ Назад к настройке", buttons[5].text)
        assertEquals("owner_venue_hub_setup:17", buttons[5].callbackData)
    }

    @Test
    fun `inline venue owner staff remove confirm actions render confirm and cancel callbacks`() {
        val markup = TelegramKeyboards.inlineVenueOwnerStaffRemoveConfirmActions(17L, 101L)
        val buttons = markup.inlineKeyboard.flatten()

        assertEquals("✅ Подтвердить удаление", buttons[0].text)
        assertEquals("owner_venue_staff_remove_confirm:17:101", buttons[0].callbackData)
        assertEquals("⬅️ Отмена", buttons[1].text)
        assertEquals("owner_venue_staff_root:17", buttons[1].callbackData)
    }

    @Test
    fun `inline venue owner staff role chooser actions render manager and staff only`() {
        val markup = TelegramKeyboards.inlineVenueOwnerStaffRoleChooserActions(17L)
        val buttons = markup.inlineKeyboard.flatten()

        assertEquals(false, buttons.any { it.text == "Owner" })
        assertEquals("Manager", buttons[0].text)
        assertEquals("owner_venue_staff_role_select:17:manager", buttons[0].callbackData)
        assertEquals("ℹ️ О роли Manager", buttons[1].text)
        assertEquals("owner_venue_staff_role_info:17:manager", buttons[1].callbackData)
        assertEquals("Staff / Оператор смены", buttons[2].text)
        assertEquals("owner_venue_staff_role_select:17:staff", buttons[2].callbackData)
        assertEquals("ℹ️ О роли Staff", buttons[3].text)
        assertEquals("owner_venue_staff_role_info:17:staff", buttons[3].callbackData)
        assertEquals("⬅️ К персоналу", buttons[4].text)
        assertEquals("owner_venue_staff_root:17", buttons[4].callbackData)
    }

    @Test
    fun `inline venue owner staff role change actions for staff render only manager target`() {
        val buttons =
            TelegramKeyboards
                .inlineVenueOwnerStaffRoleChangeActions(17L, 101L, targetRoleKeys = setOf("manager"))
                .inlineKeyboard
                .flatten()

        assertEquals("Сделать Manager", buttons[0].text)
        assertEquals("owner_venue_staff_role_change_select:17:101:manager", buttons[0].callbackData)
        assertEquals("⬅️ К персоналу", buttons[1].text)
        assertEquals("owner_venue_staff_root:17", buttons[1].callbackData)
        assertEquals(false, buttons.any { it.callbackData == "owner_venue_staff_role_change_select:17:101:staff" })
    }

    @Test
    fun `inline venue owner staff role change actions for manager render only staff target`() {
        val buttons =
            TelegramKeyboards
                .inlineVenueOwnerStaffRoleChangeActions(17L, 101L, targetRoleKeys = setOf("staff"))
                .inlineKeyboard
                .flatten()

        assertEquals("Сделать Staff / Оператор смены", buttons[0].text)
        assertEquals("owner_venue_staff_role_change_select:17:101:staff", buttons[0].callbackData)
        assertEquals("⬅️ К персоналу", buttons[1].text)
        assertEquals("owner_venue_staff_root:17", buttons[1].callbackData)
        assertEquals(false, buttons.any { it.callbackData == "owner_venue_staff_role_change_select:17:101:manager" })
    }

    @Test
    fun `inline venue owner staff role chooser can limit manager to staff invite`() {
        val markup =
            TelegramKeyboards.inlineVenueOwnerStaffRoleChooserActions(
                venueId = 17L,
                allowedRoleKeys = setOf("staff"),
            )
        val buttons = markup.inlineKeyboard.flatten()

        assertEquals("Staff / Оператор смены", buttons[0].text)
        assertEquals("owner_venue_staff_role_select:17:staff", buttons[0].callbackData)
        assertEquals("ℹ️ О роли Staff", buttons[1].text)
        assertEquals("owner_venue_staff_role_info:17:staff", buttons[1].callbackData)
        assertEquals("⬅️ К персоналу", buttons[2].text)
        assertEquals("owner_venue_staff_root:17", buttons[2].callbackData)
    }

    @Test
    fun `inline venue owner staff role info actions render select and back callbacks`() {
        val markup = TelegramKeyboards.inlineVenueOwnerStaffRoleInfoActions(17L, "staff")
        val buttons = markup.inlineKeyboard.flatten()

        assertEquals("✅ Выбрать роль Staff / Оператор смены", buttons[0].text)
        assertEquals("owner_venue_staff_role_select:17:staff", buttons[0].callbackData)
        assertEquals("⬅️ К выбору роли", buttons[1].text)
        assertEquals("owner_venue_staff_add:17", buttons[1].callbackData)
        assertEquals("⬅️ К персоналу", buttons[2].text)
        assertEquals("owner_venue_staff_root:17", buttons[2].callbackData)
    }

    @Test
    fun `inline venue owner staff role info can hide select action`() {
        val markup = TelegramKeyboards.inlineVenueOwnerStaffRoleInfoActions(17L, "manager", canSelect = false)
        val buttons = markup.inlineKeyboard.flatten()

        assertEquals("⬅️ К выбору роли", buttons[0].text)
        assertEquals("owner_venue_staff_add:17", buttons[0].callbackData)
        assertEquals("⬅️ К персоналу", buttons[1].text)
        assertEquals("owner_venue_staff_root:17", buttons[1].callbackData)
    }

    @Test
    fun `staff chat order batch actions use short callbacks`() {
        val accept = TelegramKeyboards.inlineStaffChatOrderBatchAccept(17L, 57L).inlineKeyboard.flatten().single()
        val deliver = TelegramKeyboards.inlineStaffChatOrderBatchDeliver(17L, 57L).inlineKeyboard.flatten().single()
        val close = TelegramKeyboards.inlineStaffChatOrderCloseBill(17L, 19L, 57L).inlineKeyboard.flatten()
        val liveAccepted =
            TelegramKeyboards.inlineStaffChatOrderActions(
                venueId = 17L,
                orderId = 19L,
                batchId = 57L,
                status = OrderWorkflowStatus.ACCEPTED,
                webAppUrl = "https://mini.app/miniapp/?mode=venue&venueId=17",
            ).inlineKeyboard.flatten()
        val closeConfirm =
            TelegramKeyboards.inlineStaffChatOrderCloseBillConfirm(
                17L,
                19L,
                57L,
            ).inlineKeyboard.flatten()

        assertEquals("✅ Принять", accept.text)
        assertEquals("sc_ob_a:17:57", accept.callbackData)
        assertTrue(accept.callbackData!!.length <= 64)
        assertEquals("🚚 Доставлено", deliver.text)
        assertEquals("sc_ob_d:17:57", deliver.callbackData)
        assertTrue(deliver.callbackData!!.length <= 64)
        assertEquals("🧾 Закрыть счёт", close[0].text)
        assertEquals("sc_oc_ask:h:j:1l", close[0].callbackData)
        assertTrue(close[0].callbackData!!.length <= 64)
        assertEquals("🔄 Обновить", close[1].text)
        assertEquals("sc_or:h:j:1l", close[1].callbackData)
        assertTrue(close[1].callbackData!!.length <= 64)
        assertEquals("🚚 Доставлено", liveAccepted[0].text)
        assertEquals("🔄 Обновить", liveAccepted[1].text)
        assertEquals("📱 Открыть Mini App", liveAccepted[2].text)
        assertEquals("https://mini.app/miniapp/?mode=venue&venueId=17", liveAccepted[2].url)
        assertEquals(null, liveAccepted[2].webApp)
        assertEquals("✅ Да, общий счёт оплачен и закрыт", closeConfirm[0].text)
        assertEquals("sc_oc_yes:h:j:1l", closeConfirm[0].callbackData)
        assertTrue(closeConfirm[0].callbackData!!.length <= 64)
        assertEquals("↩️ Назад", closeConfirm[1].text)
        assertEquals("sc_oc_back:h:j:1l", closeConfirm[1].callbackData)
        assertTrue(closeConfirm[1].callbackData!!.length <= 64)
    }

    @Test
    fun `staff chat live order actions can name selected batch target`() {
        val acceptAddOn =
            TelegramKeyboards.inlineStaffChatOrderActions(
                venueId = 17L,
                orderId = 19L,
                batchId = 58L,
                status = OrderWorkflowStatus.NEW,
                webAppUrl = null,
                batchLabel = "Дозаказ №1",
            ).inlineKeyboard.flatten()
        val deliverMain =
            TelegramKeyboards.inlineStaffChatOrderActions(
                venueId = 17L,
                orderId = 19L,
                batchId = 57L,
                status = OrderWorkflowStatus.ACCEPTED,
                webAppUrl = null,
                batchLabel = "Основной заказ",
            ).inlineKeyboard.flatten()

        assertEquals("✅ Принять дозаказ №1", acceptAddOn[0].text)
        assertEquals("sc_ob_a:17:58", acceptAddOn[0].callbackData)
        assertEquals("🚚 Доставлен основной заказ", deliverMain[0].text)
        assertEquals("sc_ob_d:17:57", deliverMain[0].callbackData)
    }

    @Test
    fun `staff chat live order actions include short shift extension callbacks`() {
        val buttons =
            TelegramKeyboards.inlineStaffChatOrderActions(
                venueId = 17L,
                orderId = 19L,
                batchId = 57L,
                status = OrderWorkflowStatus.ACCEPTED,
                webAppUrl = null,
                pendingShiftExtensionRequestId = 501L,
            ).inlineKeyboard.flatten()

        assertEquals("🚚 Доставлено", buttons[0].text)
        assertEquals("sc_ob_d:17:57", buttons[0].callbackData)
        assertEquals("✅ Подтвердить продление", buttons[1].text)
        assertEquals("sc_se_a:17:501", buttons[1].callbackData)
        assertTrue(buttons[1].callbackData!!.length <= 64)
        assertEquals("❌ Отказать в продлении", buttons[2].text)
        assertEquals("sc_se_r:17:501", buttons[2].callbackData)
        assertTrue(buttons[2].callbackData!!.length <= 64)
        assertEquals("🔄 Обновить", buttons[3].text)
    }

    @Test
    fun `guest item unavailable actions contain replacement keep and staff call`() {
        val buttons =
            TelegramKeyboards.inlineGuestItemUnavailableActions(
                chooseReplacementCallback = "giu_menu:a:j:dw",
                keepWithoutItemCallback = "giu_keep:a:j:dw",
                callStaffCallback = "giu_call:a:j:dw",
                keepWithoutItemText = "✅ Оставить без «Сок»",
            ).inlineKeyboard.flatten()

        assertEquals("🍽 Выбрать замену", buttons[0].text)
        assertEquals("giu_menu:a:j:dw", buttons[0].callbackData)
        assertTrue(buttons[0].callbackData!!.length <= 64)
        assertEquals("✅ Оставить без «Сок»", buttons[1].text)
        assertEquals("giu_keep:a:j:dw", buttons[1].callbackData)
        assertTrue(buttons[1].callbackData!!.length <= 64)
        assertEquals("🛎 Позвать персонал", buttons[2].text)
        assertEquals("giu_call:a:j:dw", buttons[2].callbackData)
        assertTrue(buttons[2].callbackData!!.length <= 64)
    }

    @Test
    fun `staff chat staff call actions use short callbacks`() {
        val ack = TelegramKeyboards.inlineStaffChatStaffCallAck(17L, 6L).inlineKeyboard.flatten().single()
        val done = TelegramKeyboards.inlineStaffChatStaffCallDone(17L, 6L).inlineKeyboard.flatten().single()

        assertEquals("✅ Принять вызов", ack.text)
        assertEquals("sc_call_ack:17:6", ack.callbackData)
        assertTrue(ack.callbackData!!.length <= 64)
        assertEquals("✅ Выполнено", done.text)
        assertEquals("sc_call_done:17:6", done.callbackData)
        assertTrue(done.callbackData!!.length <= 64)
    }
}
