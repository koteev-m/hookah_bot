package com.hookah.platform.backend.telegram

import com.hookah.platform.backend.telegram.db.StaffChatNotificationClaim
import com.hookah.platform.backend.telegram.db.StaffChatNotificationRepository
import com.hookah.platform.backend.telegram.db.VenueRepository
import com.hookah.platform.backend.telegram.db.VenueSettings
import com.hookah.platform.backend.telegram.db.VenueSettingsRepository
import com.hookah.platform.backend.telegram.db.VenueShort
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class StaffChatNotifierTest {
    private val venueRepository: VenueRepository = mockk()
    private val notificationRepository: StaffChatNotificationRepository = mockk()
    private val venueSettingsRepository: VenueSettingsRepository = mockk()
    private val notifier =
        StaffChatNotifier(
            venueRepository = venueRepository,
            notificationRepository = notificationRepository,
            venueSettingsRepository = venueSettingsRepository,
            isTelegramActive = { true },
            scope = CoroutineScope(Dispatchers.Unconfined),
        )

    @Test
    fun `notifyNewBatch sends only once for same batch`() =
        runBlocking {
            coEvery { venueSettingsRepository.find(1L) } returns null
            coEvery { venueRepository.findVenueById(1L) } returns
                VenueShort(
                    id = 1L,
                    name = "Venue",
                    staffChatId = 777L,
                )
            coEvery { notificationRepository.tryClaimAndEnqueue(10L, 777L, "sendMessage", any()) } returnsMany
                listOf(
                    StaffChatNotificationClaim.CLAIMED,
                    StaffChatNotificationClaim.ALREADY,
                )

            val event =
                NewBatchNotification(
                    venueId = 1L,
                    orderId = 2L,
                    batchId = 10L,
                    tableLabel = "1",
                    itemsSummary = "Tea x1",
                    comment = "Позвоните, пожалуйста",
                )

            val first = notifier.notifyNewBatchNow(event)
            val second = notifier.notifyNewBatchNow(event)

            assertEquals(StaffChatNotificationResult.SENT_OR_QUEUED, first)
            assertEquals(StaffChatNotificationResult.SKIPPED_DUPLICATE, second)
            coVerify(exactly = 2) { notificationRepository.tryClaimAndEnqueue(10L, 777L, "sendMessage", any()) }
        }

    @Test
    fun `notifyNewBatch uses display order number and adds inline accept action`() =
        runBlocking {
            coEvery { venueSettingsRepository.find(1L) } returns null
            coEvery { venueRepository.findVenueById(1L) } returns
                VenueShort(
                    id = 1L,
                    name = "Venue",
                    staffChatId = 777L,
                )
            val payloadSlot = slot<String>()
            coEvery {
                notificationRepository.tryClaimAndEnqueue(57L, 777L, "sendMessage", capture(payloadSlot))
            } returns
                StaffChatNotificationClaim.CLAIMED

            val result =
                notifier.notifyNewBatchNow(
                    NewBatchNotification(
                        venueId = 1L,
                        orderId = 19L,
                        batchId = 57L,
                        tableLabel = "7",
                        itemsSummary = "Darkside x2",
                        comment = "Без мяты",
                        displayNumber = 12,
                        isFirstBatch = true,
                        guestDisplayName = "Алексей",
                    ),
                )

            assertEquals(StaffChatNotificationResult.SENT_OR_QUEUED, result)
            val payload = payloadSlot.captured
            assertTrue(payload.contains("Новый заказ №12"), payload)
            assertFalse(payload.contains("Новый заказ к счёту"), payload)
            assertTrue(payload.contains("Стол: 7"), payload)
            assertTrue(payload.contains("Гость: Алексей"), payload)
            assertTrue(payload.contains("Состав:\\n• Darkside ×2"), payload)
            assertTrue(payload.contains("Комментарий: Без мяты"), payload)
            assertTrue(
                payload.contains("Если позиция закончилась или нужно изменить счёт — откройте детали заказа в боте."),
                payload,
            )
            assertFalse(payload.contains("партия"), payload)
            assertFalse(payload.contains("#57"), payload)
            assertFalse(payload.contains("Заказ: #19"), payload)
            assertTrue(payload.contains("\"inline_keyboard\""), payload)
            assertTrue(payload.contains("✅ Принять"), payload)
            assertTrue(payload.contains("sc_ob_a:1:57"), payload)
            assertFalse(payload.contains("\"keyboard\":[["), payload)
            assertFalse(payload.contains("\"remove_keyboard\":true"), payload)
        }

    @Test
    fun `notifyNewBatch labels subsequent batch as reorder`() =
        runBlocking {
            coEvery { venueSettingsRepository.find(1L) } returns null
            coEvery { venueRepository.findVenueById(1L) } returns
                VenueShort(
                    id = 1L,
                    name = "Venue",
                    staffChatId = 777L,
                )
            val payloadSlot = slot<String>()
            coEvery {
                notificationRepository.tryClaimAndEnqueue(58L, 777L, "sendMessage", capture(payloadSlot))
            } returns
                StaffChatNotificationClaim.CLAIMED

            val result =
                notifier.notifyNewBatchNow(
                    NewBatchNotification(
                        venueId = 1L,
                        orderId = 19L,
                        batchId = 58L,
                        tableLabel = "7",
                        itemsSummary = "Уголь x1",
                        comment = null,
                        displayNumber = 12,
                        isFirstBatch = false,
                    ),
                )

            assertEquals(StaffChatNotificationResult.SENT_OR_QUEUED, result)
            val payload = payloadSlot.captured
            assertTrue(payload.contains("Дозаказ к заказу №12"), payload)
            assertFalse(payload.contains("Новый заказ к счёту"), payload)
            assertFalse(payload.contains("партия"), payload)
        }

    @Test
    fun `notifyNewBatch labels replacement before accept as updated order`() =
        runBlocking {
            coEvery { venueSettingsRepository.find(1L) } returns null
            coEvery { venueRepository.findVenueById(1L) } returns
                VenueShort(
                    id = 1L,
                    name = "Venue",
                    staffChatId = 777L,
                )
            val payloadSlot = slot<String>()
            coEvery {
                notificationRepository.tryClaimAndEnqueue(59L, 777L, "sendMessage", capture(payloadSlot))
            } returns
                StaffChatNotificationClaim.CLAIMED

            val result =
                notifier.notifyNewBatchNow(
                    NewBatchNotification(
                        venueId = 1L,
                        orderId = 19L,
                        batchId = 59L,
                        tableLabel = "7",
                        itemsSummary = "Сок x1",
                        comment = null,
                        displayNumber = 12,
                        isFirstBatch = false,
                        isReplacementBeforeAccept = true,
                    ),
                )

            assertEquals(StaffChatNotificationResult.SENT_OR_QUEUED, result)
            val payload = payloadSlot.captured
            assertTrue(payload.contains("Заказ №12 обновлён"), payload)
            assertFalse(payload.contains("Дозаказ к заказу"), payload)
            assertFalse(payload.contains("Новый заказ к счёту"), payload)
        }

    @Test
    fun `order notification text can render accepted and delivered state`() {
        val accepted =
            buildNewBatchNotificationText(
                venueName = "Mix",
                tableLabel = "105",
                itemsSummary = "Darkside x2",
                comment = null,
                displayNumber = 12,
                statusLine = "✅ Принял: @waiter",
                guestDisplayName = "Максим",
            )
        val delivered =
            buildNewBatchNotificationText(
                venueName = "Mix",
                tableLabel = "105",
                itemsSummary = "Darkside x2",
                comment = null,
                displayNumber = 12,
                statusLine = "✅ Доставлено: @waiter",
                guestDisplayName = "Максим",
            )

        assertTrue(accepted.contains("Новый заказ №12"), accepted)
        assertFalse(accepted.contains("Новый заказ к счёту"), accepted)
        assertTrue(accepted.contains("Гость: Максим"), accepted)
        assertTrue(accepted.contains("✅ Принял: @waiter"), accepted)
        assertTrue(delivered.contains("Гость: Максим"), delivered)
        assertTrue(delivered.contains("✅ Доставлено: @waiter"), delivered)
        assertFalse(delivered.contains("партия"), delivered)
    }

    @Test
    fun `order notification text renders items as bullet list with staff bot hint`() {
        val text =
            buildNewBatchNotificationText(
                venueName = "Mix",
                tableLabel = "105",
                itemsSummary = "Сок x1, Вода с газом x1, Кальян обычный x1",
                comment = null,
                displayNumber = 12,
                guestDisplayName = "Максим",
            )

        assertTrue(text.contains("Состав:\n• Сок ×1\n• Вода с газом ×1\n• Кальян обычный ×1"), text)
        assertFalse(text.contains("Состав: Сок"), text)
        assertTrue(
            text.contains("Если позиция закончилась или нужно изменить счёт — откройте детали заказа в боте."),
            text,
        )
    }

    @Test
    fun `order notification text shows applied promotion discount`() {
        val text =
            buildNewBatchNotificationText(
                venueName = "Mix",
                tableLabel = "105",
                itemsSummary = "Кальян обычный x2 — 2 000 ₽",
                comment = null,
                displayNumber = 12,
                guestDisplayName = "Максим",
                promotionDiscounts =
                    listOf(
                        NewBatchPromotionDiscount(
                            label = "Счастливые часы",
                            discountMinor = 40000L,
                            currency = "RUB",
                        ),
                    ),
                totalPayableMinor = 160000L,
                totalCurrency = "RUB",
            )

        assertTrue(text.contains("• Кальян обычный ×2 — 2 000 ₽"), text)
        assertTrue(text.contains("🎁 Счастливые часы: −400 ₽"), text)
        assertTrue(text.contains("Итого к оплате: 1 600 ₽"), text)
    }

    @Test
    fun `order notification text shows promotion breakdown with percent and gift names`() {
        val text =
            buildNewBatchNotificationText(
                venueName = "Mix",
                tableLabel = "105",
                itemsSummary = "Кальян обычный x1 — 2 000 ₽\nСок x1 — 500 ₽",
                comment = null,
                displayNumber = 12,
                guestDisplayName = "Максим",
                promotionDiscounts =
                    listOf(
                        NewBatchPromotionDiscount(
                            label = "Счастливые часы",
                            discountMinor = 20000L,
                            currency = "RUB",
                            ruleType = "HAPPY_HOURS_PERCENT",
                        ),
                        NewBatchPromotionDiscount(
                            label = "Сок в подарок",
                            discountMinor = 50000L,
                            currency = "RUB",
                            ruleType = "GIFT_WITH_ITEM",
                        ),
                    ),
                totalPayableMinor = 180000L,
                totalCurrency = "RUB",
            )

        assertTrue(text.contains("🎁 Акции:\n• Счастливые часы: −200 ₽\n• Сок в подарок: −500 ₽"), text)
        assertTrue(text.contains("Итого к оплате: 1 800 ₽"), text)
    }

    @Test
    fun `order notification text shows gift promotion discount`() {
        val text =
            buildNewBatchNotificationText(
                venueName = "Mix",
                tableLabel = "105",
                itemsSummary = "Кальян обычный x1 — 1 100 ₽\nЧай x1 — 300 ₽",
                comment = null,
                displayNumber = 12,
                guestDisplayName = "Максим",
                promotionDiscounts =
                    listOf(
                        NewBatchPromotionDiscount(
                            label = "Чай в подарок",
                            discountMinor = 30000L,
                            currency = "RUB",
                            ruleType = "GIFT_WITH_ITEM",
                        ),
                    ),
                totalPayableMinor = 110000L,
                totalCurrency = "RUB",
            )

        assertTrue(text.contains("• Кальян обычный ×1 — 1 100 ₽"), text)
        assertTrue(text.contains("• Чай ×1 — 300 ₽"), text)
        assertTrue(text.contains("🎁 Чай в подарок: −300 ₽"), text)
        assertTrue(text.contains("Итого к оплате: 1 100 ₽"), text)
    }

    @Test
    fun `order notification text shows loyalty redemption discount`() {
        val text =
            buildNewBatchNotificationText(
                venueName = "Mix",
                tableLabel = "105",
                itemsSummary = "Кальян обычный x1 — 2 000 ₽",
                comment = null,
                displayNumber = 12,
                guestDisplayName = "Максим",
                promotionDiscounts =
                    listOf(
                        NewBatchPromotionDiscount(
                            label = "Лояльность: бесплатный кальян",
                            discountMinor = 200000L,
                            currency = "RUB",
                            ruleType = "LOYALTY_NTH_HOOKAH",
                        ),
                    ),
                totalPayableMinor = 0L,
                totalCurrency = "RUB",
            )

        assertTrue(text.contains("• Кальян обычный ×1 — 2 000 ₽"), text)
        assertTrue(text.contains("🎁 Лояльность: бесплатный кальян: −2 000 ₽"), text)
        assertTrue(text.contains("Итого к оплате: 0 ₽"), text)
    }

    @Test
    fun `staff call updated text keeps guest name`() {
        val accepted =
            buildStaffCallNotificationText(
                venueName = "Mix",
                tableLabel = "105",
                reason = StaffCallReason.COME,
                comment = null,
                statusLine = "✅ Принял вызов: @waiter",
                guestDisplayName = "Максим",
            )
        val done =
            buildStaffCallNotificationText(
                venueName = "Mix",
                tableLabel = "105",
                reason = StaffCallReason.COME,
                comment = null,
                statusLine = "✅ Выполнено: @waiter",
                guestDisplayName = "Максим",
            )

        assertTrue(accepted.contains("Гость: Максим"), accepted)
        assertTrue(accepted.contains("✅ Принял вызов: @waiter"), accepted)
        assertTrue(done.contains("Гость: Максим"), done)
        assertTrue(done.contains("✅ Выполнено: @waiter"), done)
    }

    @Test
    fun `staff-facing notifications fall back to guest label`() {
        val orderText =
            buildNewBatchNotificationText(
                venueName = "Mix",
                tableLabel = "105",
                itemsSummary = "Darkside x2",
                comment = null,
                displayNumber = 12,
            )
        val callText =
            buildStaffCallNotificationText(
                venueName = "Mix",
                tableLabel = "105",
                reason = StaffCallReason.COME,
                comment = null,
            )
        val bookingText =
            buildBookingStaffNotificationText(
                venueName = "Mix",
                event = BookingStaffNotificationEvent.CREATED,
                displayNumber = 1,
                scheduledAtText = "03.04.2026 18:00",
                partySize = 2,
                comment = null,
            )

        assertTrue(orderText.contains("Гость: Гость"), orderText)
        assertTrue(callText.contains("Гость: Гость"), callText)
        assertTrue(bookingText.contains("Гость: Гость"), bookingText)
    }

    @Test
    fun `notifyBooking uses display booking number and hides raw booking id`() =
        runBlocking {
            coEvery { venueRepository.findVenueById(1L) } returns
                VenueShort(
                    id = 1L,
                    name = "Mix",
                    staffChatId = 777L,
                )
            val payloadSlot = slot<String>()
            coEvery {
                notificationRepository.tryClaimAndEnqueue(
                    -2_000_000_000_071L,
                    777L,
                    "sendMessage",
                    capture(payloadSlot),
                )
            } returns StaffChatNotificationClaim.CLAIMED

            val result =
                notifier.notifyBookingNow(
                    BookingStaffNotification(
                        venueId = 1L,
                        bookingId = 7L,
                        event = BookingStaffNotificationEvent.CREATED,
                        scheduledAtText = "03.04.2026 18:00",
                        partySize = 3,
                        comment = "У окна",
                        displayNumber = 1,
                        guestDisplayName = "Мария",
                    ),
                )

            assertEquals(StaffChatNotificationResult.SENT_OR_QUEUED, result)
            val payload = payloadSlot.captured
            assertTrue(payload.contains("📅 Новая бронь №1"), payload)
            assertTrue(payload.contains("Заведение: Mix"), payload)
            assertTrue(payload.contains("Гость: Мария"), payload)
            assertTrue(payload.contains("Дата и время: 03.04.2026 18:00"), payload)
            assertTrue(payload.contains("Гостей: 3"), payload)
            assertTrue(payload.contains("Комментарий: У окна"), payload)
            assertFalse(payload.contains("#7"), payload)
            assertFalse(payload.contains("bookingId"), payload)
            assertFalse(payload.contains("\"keyboard\":[["), payload)
            assertTrue(payload.contains("\"inline_keyboard\""), payload)
            assertTrue(payload.contains("✅ Подтвердить"), payload)
            assertTrue(payload.contains("staff_booking_confirm:1:7"), payload)
            assertTrue(payload.contains("✉️ Написать гостю"), payload)
            assertTrue(payload.contains("staff_booking_message:1:7"), payload)
            assertTrue(payload.contains("❌ Отменить бронь"), payload)
            assertTrue(payload.contains("staff_booking_cancel_ask:1:7"), payload)
        }

    @Test
    fun `notifyBooking distinguishes venue cancellation from guest cancellation`() =
        runBlocking {
            coEvery { venueRepository.findVenueById(1L) } returns
                VenueShort(
                    id = 1L,
                    name = "Mix",
                    staffChatId = 777L,
                )
            val payloadSlot = slot<String>()
            coEvery {
                notificationRepository.tryClaimAndEnqueue(
                    -2_000_000_000_000L - (7L * 10L) - BookingStaffNotificationEvent.VENUE_CANCELLED.dedupeCode,
                    777L,
                    "sendMessage",
                    capture(payloadSlot),
                )
            } returns StaffChatNotificationClaim.CLAIMED

            val result =
                notifier.notifyBookingNow(
                    BookingStaffNotification(
                        venueId = 1L,
                        bookingId = 7L,
                        event = BookingStaffNotificationEvent.VENUE_CANCELLED,
                        scheduledAtText = "03.04.2026 18:00",
                        partySize = 3,
                        comment = "У окна",
                        displayNumber = 1,
                        cancelReasonText = "Нет мест",
                        actorDisplayName = "@waiter",
                        guestDisplayName = "Мария",
                    ),
                )

            assertEquals(StaffChatNotificationResult.SENT_OR_QUEUED, result)
            val payload = payloadSlot.captured
            assertTrue(payload.contains("❌ Бронь №1 отменена заведением"), payload)
            assertTrue(payload.contains("Отменил: @waiter"), payload)
            assertTrue(payload.contains("Гость: Мария"), payload)
            assertTrue(payload.contains("Причина: Нет мест"), payload)
            assertFalse(payload.contains("Гость отменил"), payload)
        }

    @Test
    fun `booking cancellation notification is sent when staff chat is linked and cancellation setting is disabled`() =
        runBlocking {
            coEvery { venueSettingsRepository.find(1L) } returns
                VenueSettings(
                    venueId = 1L,
                    notifyOrdersEnabled = true,
                    notifyStaffCallsEnabled = true,
                    notifyCancellationsEnabled = false,
                    timezone = "Europe/Moscow",
                )
            coEvery { venueRepository.findVenueById(1L) } returns
                VenueShort(
                    id = 1L,
                    name = "Mix",
                    staffChatId = 777L,
                )
            coEvery {
                notificationRepository.tryClaimAndEnqueue(
                    -2_000_000_000_000L - (7L * 10L) - BookingStaffNotificationEvent.VENUE_CANCELLED.dedupeCode,
                    777L,
                    "sendMessage",
                    any(),
                )
            } returns StaffChatNotificationClaim.CLAIMED

            val result =
                notifier.notifyBookingNow(
                    BookingStaffNotification(
                        venueId = 1L,
                        bookingId = 7L,
                        event = BookingStaffNotificationEvent.VENUE_CANCELLED,
                        scheduledAtText = "03.04.2026 18:00",
                        partySize = 3,
                        comment = "У окна",
                        displayNumber = 1,
                        cancelReasonText = "Нет мест",
                        actorDisplayName = "@waiter",
                        guestDisplayName = "Мария",
                    ),
                )

            assertEquals(StaffChatNotificationResult.SENT_OR_QUEUED, result)
            coVerify {
                notificationRepository.tryClaimAndEnqueue(
                    -2_000_000_000_000L - (7L * 10L) - BookingStaffNotificationEvent.VENUE_CANCELLED.dedupeCode,
                    777L,
                    "sendMessage",
                    any(),
                )
            }
        }

    @Test
    fun `notifyStaffCall uses readable reason and hides technical context`() =
        runBlocking {
            coEvery { venueSettingsRepository.find(1L) } returns null
            coEvery { venueRepository.findVenueById(1L) } returns
                VenueShort(
                    id = 1L,
                    name = "Mix",
                    staffChatId = 777L,
                )
            val payloadSlot = slot<String>()
            coEvery {
                notificationRepository.tryClaimAndEnqueue(
                    -1_000_000_000_006L,
                    777L,
                    "sendMessage",
                    capture(payloadSlot),
                )
            } returns StaffChatNotificationClaim.CLAIMED

            val result =
                notifier.notifyStaffCallNow(
                    StaffCallNotification(
                        venueId = 1L,
                        staffCallId = 6L,
                        tableLabel = "105",
                        reason = StaffCallReason.COME,
                        comment = "Подойдите, пожалуйста",
                        tableSessionId = 21L,
                        orderId = 19L,
                        guestDisplayName = "Алексей",
                    ),
                )

            assertEquals(StaffChatNotificationResult.SENT_OR_QUEUED, result)
            val payload = payloadSlot.captured
            assertTrue(payload.contains("🛎 Вызов персонала"), payload)
            assertTrue(payload.contains("Mix · Стол 105"), payload)
            assertTrue(payload.contains("Гость: Алексей"), payload)
            assertTrue(payload.contains("Причина: Консультация"), payload)
            assertTrue(payload.contains("Комментарий: Подойдите, пожалуйста"), payload)
            assertTrue(payload.contains("\"inline_keyboard\""), payload)
            assertTrue(payload.contains("✅ Принять вызов"), payload)
            assertTrue(payload.contains("sc_call_ack:1:6"), payload)
            assertFalse(payload.contains("Вызов: #6"), payload)
            assertFalse(payload.contains("Вызов номер"), payload)
            assertFalse(payload.contains("tableSessionId"), payload)
            assertFalse(payload.contains("orderId"), payload)
            assertFalse(payload.contains("COME"), payload)
        }

    @Test
    fun `notifyStaffCall formats relocation without exposing technical context`() =
        runBlocking {
            coEvery { venueSettingsRepository.find(1L) } returns null
            coEvery { venueRepository.findVenueById(1L) } returns
                VenueShort(
                    id = 1L,
                    name = "Mix",
                    staffChatId = 777L,
                )
            val payloadSlot = slot<String>()
            coEvery {
                notificationRepository.tryClaimAndEnqueue(
                    -1_000_000_000_007L,
                    777L,
                    "sendMessage",
                    capture(payloadSlot),
                )
            } returns StaffChatNotificationClaim.CLAIMED

            val result =
                notifier.notifyStaffCallNow(
                    StaffCallNotification(
                        venueId = 1L,
                        staffCallId = 7L,
                        tableLabel = "105",
                        reason = StaffCallReason.OTHER,
                        comment = "Смена стола. Текущий стол: №105. tableSessionId=21. orderId=19.",
                        tableSessionId = 21L,
                        orderId = 19L,
                        type = StaffCallNotificationType.RELOCATION,
                        guestDisplayName = "Алексей",
                    ),
                )

            assertEquals(StaffChatNotificationResult.SENT_OR_QUEUED, result)
            val payload = payloadSlot.captured
            assertTrue(payload.contains("🚪 Запрос смены стола"), payload)
            assertTrue(payload.contains("Mix · Стол 105"), payload)
            assertTrue(payload.contains("Гость: Алексей"), payload)
            assertTrue(payload.contains("Гость хочет сменить стол."), payload)
            assertTrue(payload.contains("✅ Принять вызов"), payload)
            assertTrue(payload.contains("sc_call_ack:1:7"), payload)
            assertFalse(payload.contains("Вызов: #7"), payload)
            assertFalse(payload.contains("Причина:"), payload)
            assertFalse(payload.contains("tableSessionId"), payload)
            assertFalse(payload.contains("orderId"), payload)
            assertFalse(payload.contains("#7"), payload)
        }

    @Test
    fun `notifyNewBatch returns skipped when telegram inactive`() =
        runBlocking {
            val inactiveNotifier =
                StaffChatNotifier(
                    venueRepository = venueRepository,
                    notificationRepository = notificationRepository,
                    isTelegramActive = { false },
                    scope = CoroutineScope(Dispatchers.Unconfined),
                )

            val result =
                inactiveNotifier.notifyNewBatchNow(
                    NewBatchNotification(
                        venueId = 1L,
                        orderId = 2L,
                        batchId = 10L,
                        tableLabel = "1",
                        itemsSummary = "Tea x1",
                        comment = null,
                    ),
                )

            assertEquals(StaffChatNotificationResult.SKIPPED_INACTIVE, result)
            coVerify(exactly = 0) { notificationRepository.tryClaimAndEnqueue(any(), any(), any(), any()) }
        }

    @Test
    fun `notifyNewBatch returns skipped when staff chat is not linked`() =
        runBlocking {
            coEvery { venueSettingsRepository.find(1L) } returns null
            coEvery { venueRepository.findVenueById(1L) } returns
                VenueShort(
                    id = 1L,
                    name = "Venue",
                    staffChatId = null,
                )

            val result =
                notifier.notifyNewBatchNow(
                    NewBatchNotification(
                        venueId = 1L,
                        orderId = 2L,
                        batchId = 10L,
                        tableLabel = "1",
                        itemsSummary = "Tea x1",
                        comment = null,
                    ),
                )

            assertEquals(StaffChatNotificationResult.SKIPPED_NO_STAFF_CHAT, result)
            coVerify(exactly = 0) { notificationRepository.tryClaimAndEnqueue(any(), any(), any(), any()) }
        }

    @Test
    fun `notifyNewBatch is still sent when staff chat is linked and order notifications are disabled`() =
        runBlocking {
            coEvery { venueSettingsRepository.find(1L) } returns
                VenueSettings(
                    venueId = 1L,
                    notifyOrdersEnabled = false,
                    notifyStaffCallsEnabled = true,
                    notifyCancellationsEnabled = true,
                    timezone = null,
                )
            coEvery { venueRepository.findVenueById(1L) } returns
                VenueShort(
                    id = 1L,
                    name = "Venue",
                    staffChatId = 777L,
                )
            coEvery { notificationRepository.tryClaimAndEnqueue(10L, 777L, "sendMessage", any()) } returns
                StaffChatNotificationClaim.CLAIMED

            val result =
                notifier.notifyNewBatchNow(
                    NewBatchNotification(
                        venueId = 1L,
                        orderId = 2L,
                        batchId = 10L,
                        tableLabel = "1",
                        itemsSummary = "Tea x1",
                        comment = null,
                    ),
                )

            assertEquals(StaffChatNotificationResult.SENT_OR_QUEUED, result)
            coVerify { notificationRepository.tryClaimAndEnqueue(10L, 777L, "sendMessage", any()) }
        }

    @Test
    fun `notifyNewBatch returns failed enqueue when atomic claim and enqueue fails`() =
        runBlocking {
            coEvery { venueSettingsRepository.find(1L) } returns null
            coEvery { venueRepository.findVenueById(1L) } returns
                VenueShort(
                    id = 1L,
                    name = "Venue",
                    staffChatId = 777L,
                )
            coEvery { notificationRepository.tryClaimAndEnqueue(10L, 777L, "sendMessage", any()) } returns
                StaffChatNotificationClaim.ERROR

            val result =
                notifier.notifyNewBatchNow(
                    NewBatchNotification(
                        venueId = 1L,
                        orderId = 2L,
                        batchId = 10L,
                        tableLabel = "1",
                        itemsSummary = "Tea x1",
                        comment = null,
                    ),
                )

            assertEquals(StaffChatNotificationResult.FAILED_ENQUEUE, result)
        }
}
