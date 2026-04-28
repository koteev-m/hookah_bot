package com.hookah.platform.backend.telegram

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs

class TelegramKeyboardsTest {
    @Test
    fun `owner main menu renders platform sections`() {
        val markup = TelegramKeyboards.ownerMainMenu()
        val reply = assertIs<ReplyKeyboardMarkup>(markup)
        val rows = reply.keyboard.map { row -> row.map { it.text } }

        assertEquals(
            listOf(
                listOf("📨 Заявки на подключение", "🏢 Кальянные"),
                listOf("👤 Владельцы", "💳 Подписки"),
                listOf("⚙️ Статусы"),
            ),
            rows,
        )
    }

    @Test
    fun `main menu uses bot first entry structure`() {
        val markup = TelegramKeyboards.mainMenu(hasVenueRole = false, isPlatformOwner = false, webAppUrl = "https://mini.app")
        val reply = assertIs<ReplyKeyboardMarkup>(markup)
        val texts = reply.keyboard.flatten().map { it.text }

        assertEquals(
            listOf(
                "🍽 Каталог кальянных",
                "📱 Открыть Mini App",
                "🎁 Акции",
                "🪑 Я за столом / У меня QR",
                "📄 Мои заказы и брони",
                "🤝 Добавить свою кальянную",
            ),
            texts,
        )
        assertFalse(texts.contains("🧪 Тест: стол 1"))
        assertEquals(
            "https://mini.app?screen=catalog",
            reply.keyboard[0][1].webApp?.url,
        )
    }

    @Test
    fun `fallback menu includes table qr entry scenario`() {
        val markup = TelegramKeyboards.mainMenu(hasVenueRole = false, isPlatformOwner = false, webAppUrl = null)
        val reply = assertIs<ReplyKeyboardMarkup>(markup)
        val texts = reply.keyboard.flatten().map { it.text }

        assertEquals(
            listOf(
                "🍽 Каталог кальянных",
                "📱 Открыть Mini App",
                "🎁 Акции",
                "🪑 Я за столом / У меня QR",
                "📄 Мои заказы и брони",
                "🤝 Добавить свою кальянную",
            ),
            texts,
        )
        assertEquals(null, reply.keyboard[0][1].webApp)
    }

    @Test
    fun `main menu uses compact three by two layout`() {
        val markup = TelegramKeyboards.mainMenu(hasVenueRole = false, isPlatformOwner = false, webAppUrl = "https://mini.app")
        val reply = assertIs<ReplyKeyboardMarkup>(markup)
        val rows = reply.keyboard.map { row -> row.map { it.text } }

        assertEquals(
            listOf(
                listOf("🍽 Каталог кальянных", "📱 Открыть Mini App"),
                listOf("🎁 Акции", "🪑 Я за столом / У меня QR"),
                listOf("📄 Мои заказы и брони", "🤝 Добавить свою кальянную"),
            ),
            rows,
        )
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
    fun `inline bot venue card actions renders compact venue actions`() {
        val markup = TelegramKeyboards.inlineBotVenueCardActions(42L, "https://yandex.ru/maps/?text=test")
        val buttons = markup.inlineKeyboard.flatten()

        assertEquals("🪑 Забронировать стол", buttons[0].text)
        assertEquals("bot_catalog_venue_book:42", buttons[0].callbackData)
        assertEquals("🍽 Меню", buttons[1].text)
        assertEquals("bot_catalog_venue_menu:42", buttons[1].callbackData)
        assertEquals("ℹ️ О заведении", buttons[2].text)
        assertEquals("bot_catalog_venue_about:42", buttons[2].callbackData)
        assertEquals("🗺 Маршрут", buttons[3].text)
        assertEquals("https://yandex.ru/maps/?text=test", buttons[3].url)
        assertEquals(null, buttons[3].callbackData)
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
    fun `table entry choice includes mini app and bot continuation`() {
        val markup = TelegramKeyboards.inlineTableEntryChoice("https://mini.app/miniapp/", "abc123")
        val buttons = markup.inlineKeyboard.flatten()

        assertEquals("📱 Заказывать в Mini App", buttons[0].text)
        assertEquals("https://mini.app/miniapp/?table_token=abc123&screen=menu", buttons[0].webApp?.url)
        assertEquals("💬 Заказывать в боте", buttons[1].text)
        assertEquals("continue_in_bot", buttons[1].callbackData)
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
    fun `table context bot flow uses compact three row layout`() {
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
                listOf("🍽️ Меню", "🧺 Корзина"),
                listOf("📄 Мой заказ", "👥 Общий счёт"),
                listOf("🛎 Вызвать персонал", "🚪 Сменить стол"),
            ),
            rows,
        )
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
    fun `inline post add actions renders back navigation`() {
        val markup = TelegramKeyboards.inlineBotMenuPostAddActions(categoryId = 10L)
        val buttons = markup.inlineKeyboard.flatten()

        assertEquals("⬅️ К позициям категории", buttons[0].text)
        assertEquals("bot_menu_back_category:10", buttons[0].callbackData)
        assertEquals("⬅️ К категориям", buttons[1].text)
        assertEquals("bot_menu_back_categories", buttons[1].callbackData)
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

        assertEquals("✅ Оформить заказ", buttons[0].text)
        assertEquals("bot_menu_cart_checkout", buttons[0].callbackData)
        assertEquals("🗑️ Очистить корзину", buttons[1].text)
        assertEquals("bot_menu_cart_clear", buttons[1].callbackData)
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
        assertEquals("✅ Оформить заказ", buttons[3].text)
        assertEquals("bot_menu_cart_checkout", buttons[3].callbackData)
        assertEquals("🗑️ Очистить корзину", buttons[4].text)
        assertEquals("bot_menu_cart_clear", buttons[4].callbackData)
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
    }

    @Test
    fun `inline owner approved venue connection actions render next step callbacks`() {
        val markup = TelegramKeyboards.inlineOwnerVenueConnectionApprovedActions(88L)
        val buttons = markup.inlineKeyboard.flatten()

        assertEquals("⚙️ Коммерческие условия", buttons[0].text)
        assertEquals("owner_venue_terms_open:88", buttons[0].callbackData)
        assertEquals("⬅️ К заявкам", buttons[1].text)
        assertEquals("owner_venue_requests_back", buttons[1].callbackData)
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
    }

    @Test
    fun `inline venue owner table actions render edit capacity qr and back callbacks`() {
        val markup = TelegramKeyboards.inlineVenueOwnerTableActions(15L, 25L)
        val buttons = markup.inlineKeyboard.flatten()

        assertEquals("✏️ Изменить номер", buttons[0].text)
        assertEquals("owner_venue_tables_table_rename:15:25", buttons[0].callbackData)
        assertEquals("👥 Изменить вместимость", buttons[1].text)
        assertEquals("owner_venue_tables_table_capacity:15:25", buttons[1].callbackData)
        assertEquals("🧾 Показать QR", buttons[2].text)
        assertEquals("owner_venue_tables_qr_table:15:25", buttons[2].callbackData)
        assertEquals("🔁 Перевыпустить QR", buttons[3].text)
        assertEquals("owner_venue_tables_qr_rotate:15:25", buttons[3].callbackData)
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

        assertEquals("🍓 Настроить вкусы", buttons[2].text)
        assertEquals("owner_venue_order_menu_item_flavors:11:22:33", buttons[2].callbackData)
    }

    @Test
    fun `inline venue owner order menu flavor list actions render option and add callbacks`() {
        val markup =
            TelegramKeyboards.inlineVenueOwnerOrderMenuFlavorListActions(
                venueId = 11L,
                sectionId = 22L,
                itemId = 33L,
                flavorButtons = listOf(44L to "Ягодный", 45L to "Фруктовый"),
            )
        val buttons = markup.inlineKeyboard.flatten()

        assertEquals("Ягодный", buttons[0].text)
        assertEquals("owner_venue_order_menu_item_option:11:22:33:44", buttons[0].callbackData)
        assertEquals("Фруктовый", buttons[1].text)
        assertEquals("owner_venue_order_menu_item_option:11:22:33:45", buttons[1].callbackData)
        assertEquals("➕ Добавить вкус", buttons[2].text)
        assertEquals("owner_venue_order_menu_item_option_add:11:22:33", buttons[2].callbackData)
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
        assertEquals("🗑 Удалить", buttons[2].text)
        assertEquals("owner_venue_order_menu_item_option_delete:11:22:33:44", buttons[2].callbackData)
    }

    @Test
    fun `inline venue owner staff root actions render add callback`() {
        val markup = TelegramKeyboards.inlineVenueOwnerStaffRootActions(17L)
        val buttons = markup.inlineKeyboard.flatten()

        assertEquals("➕ Добавить сотрудника", buttons[0].text)
        assertEquals("owner_venue_staff_add:17", buttons[0].callbackData)
    }

    @Test
    fun `inline venue owner staff root actions render revoke buttons for removable members`() {
        val markup =
            TelegramKeyboards.inlineVenueOwnerStaffRootActions(
                venueId = 17L,
                removableMembers = listOf(101L to "@manager_one", 102L to "Смена 2"),
            )
        val buttons = markup.inlineKeyboard.flatten()

        assertEquals("🚫 Удалить доступ: @manager_one", buttons[0].text)
        assertEquals("owner_venue_staff_remove_prompt:17:101", buttons[0].callbackData)
        assertEquals("🚫 Удалить доступ: Смена 2", buttons[1].text)
        assertEquals("owner_venue_staff_remove_prompt:17:102", buttons[1].callbackData)
        assertEquals("➕ Добавить сотрудника", buttons[2].text)
        assertEquals("owner_venue_staff_add:17", buttons[2].callbackData)
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
    fun `inline venue owner staff role chooser actions render role and info callbacks`() {
        val markup = TelegramKeyboards.inlineVenueOwnerStaffRoleChooserActions(17L)
        val buttons = markup.inlineKeyboard.flatten()

        assertEquals("Owner", buttons[0].text)
        assertEquals("owner_venue_staff_role_select:17:owner", buttons[0].callbackData)
        assertEquals("ℹ️ О роли Owner", buttons[1].text)
        assertEquals("owner_venue_staff_role_info:17:owner", buttons[1].callbackData)
        assertEquals("Manager", buttons[2].text)
        assertEquals("owner_venue_staff_role_select:17:manager", buttons[2].callbackData)
        assertEquals("ℹ️ О роли Manager", buttons[3].text)
        assertEquals("owner_venue_staff_role_info:17:manager", buttons[3].callbackData)
        assertEquals("Staff / Оператор смены", buttons[4].text)
        assertEquals("owner_venue_staff_role_select:17:staff", buttons[4].callbackData)
        assertEquals("ℹ️ О роли Staff", buttons[5].text)
        assertEquals("owner_venue_staff_role_info:17:staff", buttons[5].callbackData)
        assertEquals("⬅️ К персоналу", buttons[6].text)
        assertEquals("owner_venue_staff_root:17", buttons[6].callbackData)
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
}
