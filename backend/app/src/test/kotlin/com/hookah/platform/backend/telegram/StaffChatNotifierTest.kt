package com.hookah.platform.backend.telegram

import com.hookah.platform.backend.miniapp.venue.orders.OrderBillActiveItemSnapshot
import com.hookah.platform.backend.miniapp.venue.orders.OrderBillExcludedItemSnapshot
import com.hookah.platform.backend.miniapp.venue.orders.OrderBillSnapshot
import com.hookah.platform.backend.miniapp.venue.orders.OrderPendingShiftExtension
import com.hookah.platform.backend.miniapp.venue.orders.OrderWorkflowStatus
import com.hookah.platform.backend.telegram.db.StaffChatNotificationClaim
import com.hookah.platform.backend.telegram.db.StaffChatNotificationRepository
import com.hookah.platform.backend.telegram.db.StaffChatOrderMessage
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
import java.time.Instant
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
            coEvery { venueSettingsRepository.find(1L) } returns
                VenueSettings(
                    venueId = 1L,
                    notifyOrdersEnabled = true,
                    notifyStaffCallsEnabled = true,
                    notifyCancellationsEnabled = true,
                    timezone = "Europe/Moscow",
                )
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
    fun `bill update notification edits live staff chat message with current discounted total`() =
        runBlocking {
            coEvery { venueSettingsRepository.find(1L) } returns null
            coEvery { venueRepository.findVenueById(1L) } returns
                VenueShort(
                    id = 1L,
                    name = "Venue",
                    staffChatId = 777L,
                )
            val payloadSlot = slot<String>()
            coEvery { notificationRepository.findOrderMessage(2L) } returns
                StaffChatOrderMessage(
                    orderId = 2L,
                    venueId = 1L,
                    chatId = 777L,
                    messageId = 55L,
                )
            coEvery {
                notificationRepository.enqueueOrderMessage(
                    orderId = 2L,
                    venueId = 1L,
                    chatId = 777L,
                    method = "editMessageText",
                    payloadJson = capture(payloadSlot),
                )
            } returns true

            val result =
                notifier.notifyBillUpdatedNow(
                    StaffBillUpdatedNotification(
                        venueId = 1L,
                        orderId = 2L,
                        displayNumber = 12,
                        tableLabel = "7",
                        change = StaffBillUpdateChange.MANUAL_DISCOUNT,
                        status = OrderWorkflowStatus.ACCEPTED,
                        actionBatchId = 10L,
                        updatedAt = Instant.parse("2026-06-05T12:34:00Z"),
                        bill =
                            billSnapshot(
                                grossTotalMinor = 20_000,
                                manualDiscountTotalMinor = 2_000,
                                finalPayableTotalMinor = 18_000,
                                activeItems =
                                    listOf(
                                        activeItemSnapshot(
                                            name = "Авторский кальян",
                                            lineGrossMinor = 20_000,
                                            manualDiscountMinor = 2_000,
                                            linePayableMinor = 18_000,
                                            discountPercent = 10,
                                        ),
                                    ),
                            ),
                    ),
                )

            assertEquals(StaffChatNotificationResult.SENT_OR_QUEUED, result)
            val payload = payloadSlot.captured
            assertTrue(payload.contains("Заказ №12"), payload)
            assertTrue(payload.contains("\"message_id\":55"), payload)
            assertTrue(payload.contains("Стол: 7"), payload)
            assertTrue(payload.contains("Статус: принят"), payload)
            assertTrue(payload.contains("Изменение: ручная скидка"), payload)
            assertTrue(payload.contains("Авторский кальян ×1 — 180 ₽"), payload)
            assertTrue(payload.contains("Сумма до скидок: 200 ₽"), payload)
            assertTrue(payload.contains("Ручные скидки: −20 ₽"), payload)
            assertTrue(payload.contains("К оплате: 180 ₽"), payload)
            assertTrue(payload.contains("Обновлено: 05.06.2026 15:34"), payload)
            assertFalse(payload.contains("UTC"), payload)
            coVerify { notificationRepository.enqueueOrderMessage(2L, 1L, 777L, "editMessageText", any()) }
            coVerify(exactly = 0) { notificationRepository.enqueue(777L, "sendMessage", any()) }
            coVerify(exactly = 0) { notificationRepository.tryClaimAndEnqueue(any(), any(), any(), any()) }
        }

    @Test
    fun `bill update notification renders pending shift extension block and staff chat actions`() =
        runBlocking {
            coEvery { venueSettingsRepository.find(1L) } returns null
            coEvery { venueRepository.findVenueById(1L) } returns
                VenueShort(
                    id = 1L,
                    name = "Venue",
                    staffChatId = 777L,
                )
            val payloadSlot = slot<String>()
            coEvery { notificationRepository.findOrderMessage(2L) } returns
                StaffChatOrderMessage(
                    orderId = 2L,
                    venueId = 1L,
                    chatId = 777L,
                    messageId = 55L,
                )
            coEvery {
                notificationRepository.enqueueOrderMessage(
                    orderId = 2L,
                    venueId = 1L,
                    chatId = 777L,
                    method = "editMessageText",
                    payloadJson = capture(payloadSlot),
                )
            } returns true

            val result =
                notifier.notifyBillUpdatedNow(
                    StaffBillUpdatedNotification(
                        venueId = 1L,
                        orderId = 2L,
                        displayNumber = 12,
                        tableLabel = "7",
                        change = StaffBillUpdateChange.SHIFT_EXTENSION_REQUESTED,
                        status = OrderWorkflowStatus.ACCEPTED,
                        actionBatchId = 10L,
                        pendingShiftExtension =
                            pendingShiftExtension(
                                requestId = 501L,
                                orderId = 2L,
                                tableSessionId = 90L,
                                tabId = 91L,
                            ),
                        updatedAt = Instant.parse("2026-06-05T12:34:00Z"),
                        bill =
                            billSnapshot(
                                grossTotalMinor = 20_000,
                                finalPayableTotalMinor = 20_000,
                                activeItems =
                                    listOf(
                                        activeItemSnapshot(
                                            name = "Авторский кальян",
                                            lineGrossMinor = 20_000,
                                            linePayableMinor = 20_000,
                                        ),
                                    ),
                            ),
                    ),
                )

            assertEquals(StaffChatNotificationResult.SENT_OR_QUEUED, result)
            val payload = payloadSlot.captured
            assertTrue(payload.contains("⏳ Запрос на продление работы заведения"), payload)
            assertTrue(payload.contains("На 1 час — 3 000 ₽"), payload)
            assertTrue(payload.contains("Гость ожидает подтверждения"), payload)
            assertTrue(payload.contains("✅ Подтвердить продление"), payload)
            assertTrue(payload.contains("❌ Отказать"), payload)
            assertTrue(payload.contains("sc_se_a:1:501"), payload)
            assertTrue(payload.contains("sc_se_r:1:501"), payload)
            assertTrue(payload.contains("Изменение: запрос на продление работы"), payload)
        }

    @Test
    fun `live order message separates main order and doporder blocks`() {
        val text =
            buildStaffOrderLiveMessageText(
                venueName = "Venue",
                tableLabel = "7",
                displayNumber = 12,
                status = OrderWorkflowStatus.NEW,
                bill =
                    billSnapshot(
                        grossTotalMinor = 30_000,
                        finalPayableTotalMinor = 30_000,
                        activeItems =
                            listOf(
                                activeItemSnapshot(
                                    batchId = 10L,
                                    batchLabel = "Основной заказ",
                                    name = "Авторский кальян",
                                    lineGrossMinor = 20_000,
                                    linePayableMinor = 20_000,
                                ),
                                activeItemSnapshot(
                                    batchId = 11L,
                                    batchLabel = "Дозаказ №1",
                                    name = "Чай",
                                    lineGrossMinor = 10_000,
                                    linePayableMinor = 10_000,
                                ),
                            ),
                    ),
                batches =
                    listOf(
                        StaffOrderBatchLiveBlock(
                            batchId = 10L,
                            label = "Основной заказ",
                            status = OrderWorkflowStatus.DELIVERED,
                            comment = "Без мяты",
                        ),
                        StaffOrderBatchLiveBlock(
                            batchId = 11L,
                            label = "Дозаказ №1",
                            status = OrderWorkflowStatus.NEW,
                            comment = null,
                        ),
                    ),
                updatedAt = Instant.parse("2026-06-05T12:34:00Z"),
            )

        assertTrue(text.contains("Основной заказ — доставлен"), text)
        assertTrue(text.contains("Комментарий: Без мяты"), text)
        assertTrue(text.contains("Авторский кальян ×1 — 200 ₽"), text)
        assertTrue(text.contains("Дозаказ №1 — новый"), text)
        assertTrue(text.contains("Чай ×1 — 100 ₽"), text)
        assertTrue(text.contains("К оплате: 300 ₽"), text)
    }

    @Test
    fun `live order notification targets new doporder action when main order is delivered`() =
        runBlocking {
            coEvery { venueSettingsRepository.find(1L) } returns null
            coEvery { venueRepository.findVenueById(1L) } returns
                VenueShort(
                    id = 1L,
                    name = "Venue",
                    staffChatId = 777L,
                )
            val payloadSlot = slot<String>()
            coEvery { notificationRepository.findOrderMessage(2L) } returns
                StaffChatOrderMessage(
                    orderId = 2L,
                    venueId = 1L,
                    chatId = 777L,
                    messageId = 55L,
                )
            coEvery {
                notificationRepository.enqueueOrderMessage(
                    orderId = 2L,
                    venueId = 1L,
                    chatId = 777L,
                    method = "editMessageText",
                    payloadJson = capture(payloadSlot),
                )
            } returns true

            val result =
                notifier.notifyBillUpdatedNow(
                    StaffBillUpdatedNotification(
                        venueId = 1L,
                        orderId = 2L,
                        displayNumber = 12,
                        tableLabel = "7",
                        change = StaffBillUpdateChange.STATUS_UPDATED,
                        status = OrderWorkflowStatus.NEW,
                        actionBatchId = 11L,
                        updatedAt = Instant.parse("2026-06-05T12:34:00Z"),
                        bill =
                            billSnapshot(
                                grossTotalMinor = 30_000,
                                finalPayableTotalMinor = 30_000,
                                activeItems =
                                    listOf(
                                        activeItemSnapshot(
                                            batchId = 10L,
                                            batchLabel = "Основной заказ",
                                            name = "Авторский кальян",
                                            lineGrossMinor = 20_000,
                                            linePayableMinor = 20_000,
                                        ),
                                        activeItemSnapshot(
                                            batchId = 11L,
                                            batchLabel = "Дозаказ №1",
                                            name = "Чай",
                                            lineGrossMinor = 10_000,
                                            linePayableMinor = 10_000,
                                        ),
                                    ),
                            ),
                        batches =
                            listOf(
                                StaffOrderBatchLiveBlock(
                                    batchId = 10L,
                                    label = "Основной заказ",
                                    status = OrderWorkflowStatus.DELIVERED,
                                    comment = null,
                                ),
                                StaffOrderBatchLiveBlock(
                                    batchId = 11L,
                                    label = "Дозаказ №1",
                                    status = OrderWorkflowStatus.NEW,
                                    comment = null,
                                ),
                            ),
                    ),
                )

            assertEquals(StaffChatNotificationResult.SENT_OR_QUEUED, result)
            val payload = payloadSlot.captured
            assertTrue(payload.contains("Основной заказ — доставлен"), payload)
            assertTrue(payload.contains("Дозаказ №1 — новый"), payload)
            assertTrue(payload.contains("✅ Принять дозаказ №1"), payload)
            assertTrue(payload.contains("sc_ob_a:1:11"), payload)
            assertFalse(payload.contains("✅ Принять основной заказ"), payload)
        }

    @Test
    fun `bill update notification describes exclusion and restore with current totals`() {
        val excludedText =
            buildStaffBillUpdatedNotificationText(
                venueName = "Venue",
                tableLabel = "3",
                displayNumber = 15,
                change = StaffBillUpdateChange.ITEM_EXCLUDED,
                bill =
                    billSnapshot(
                        grossTotalMinor = 0,
                        excludedTotalMinor = 5_000,
                        finalPayableTotalMinor = 0,
                        activeItems = emptyList(),
                        excludedItems =
                            listOf(
                                excludedItemSnapshot(
                                    name = "Чай",
                                    lineGrossMinor = 5_000,
                                    reason = "Комплимент",
                                ),
                            ),
                    ),
            )
        val restoredText =
            buildStaffBillUpdatedNotificationText(
                venueName = "Venue",
                tableLabel = "3",
                displayNumber = 15,
                change = StaffBillUpdateChange.ITEM_RESTORED,
                bill =
                    billSnapshot(
                        grossTotalMinor = 5_000,
                        finalPayableTotalMinor = 5_000,
                        activeItems =
                            listOf(
                                activeItemSnapshot(
                                    name = "Чай",
                                    lineGrossMinor = 5_000,
                                    linePayableMinor = 5_000,
                                ),
                            ),
                    ),
            )

        assertTrue(excludedText.contains("Изменение: позиция исключена"), excludedText)
        assertTrue(excludedText.contains("• нет активных позиций"), excludedText)
        assertTrue(excludedText.contains("Чай ×1 — 50 ₽; причина: Комплимент"), excludedText)
        assertTrue(excludedText.contains("Исключено: −50 ₽"), excludedText)
        assertTrue(excludedText.contains("К оплате: 0 ₽"), excludedText)
        assertTrue(restoredText.contains("Изменение: позиция восстановлена"), restoredText)
        assertTrue(restoredText.contains("Чай ×1 — 50 ₽"), restoredText)
        assertTrue(restoredText.contains("К оплате: 50 ₽"), restoredText)
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

    private fun billSnapshot(
        grossTotalMinor: Long,
        manualDiscountTotalMinor: Long = 0,
        excludedTotalMinor: Long = 0,
        finalPayableTotalMinor: Long,
        activeItems: List<OrderBillActiveItemSnapshot>,
        excludedItems: List<OrderBillExcludedItemSnapshot> = emptyList(),
    ): OrderBillSnapshot =
        OrderBillSnapshot(
            grossTotalMinor = grossTotalMinor,
            manualDiscountTotalMinor = manualDiscountTotalMinor,
            promoDiscountTotalMinor = 0,
            loyaltyDiscountTotalMinor = 0,
            excludedTotalMinor = excludedTotalMinor,
            canceledTotalMinor = 0,
            rejectedTotalMinor = 0,
            finalPayableTotalMinor = finalPayableTotalMinor,
            currency = "RUB",
            activeItems = activeItems,
            promoDiscounts = emptyList(),
            loyaltyDiscounts = emptyList(),
            excludedItems = excludedItems,
        )

    private fun activeItemSnapshot(
        batchId: Long = 1L,
        batchLabel: String = "Основной заказ",
        name: String,
        lineGrossMinor: Long,
        manualDiscountMinor: Long = 0,
        linePayableMinor: Long,
        discountPercent: Int? = null,
    ): OrderBillActiveItemSnapshot =
        OrderBillActiveItemSnapshot(
            batchId = batchId,
            batchLabel = batchLabel,
            batchItemId = 10L,
            itemId = 20L,
            name = name,
            qty = 1,
            lineGrossMinor = lineGrossMinor,
            manualDiscountMinor = manualDiscountMinor,
            promoDiscountMinor = 0,
            linePayableMinor = linePayableMinor,
            currency = "RUB",
            discountPercent = discountPercent,
        )

    private fun pendingShiftExtension(
        requestId: Long,
        orderId: Long,
        tableSessionId: Long,
        tabId: Long,
    ): OrderPendingShiftExtension =
        OrderPendingShiftExtension(
            requestId = requestId,
            orderId = orderId,
            tableSessionId = tableSessionId,
            tabId = tabId,
            tableId = 11L,
            tableNumber = 7,
            durationMinutes = 60,
            priceMinor = 300_000,
            currency = "RUB",
            requestedAt = Instant.parse("2026-06-05T12:30:00Z"),
            status = "pending",
        )

    private fun excludedItemSnapshot(
        name: String,
        lineGrossMinor: Long,
        reason: String,
    ): OrderBillExcludedItemSnapshot =
        OrderBillExcludedItemSnapshot(
            batchId = 1L,
            batchLabel = "Основной заказ",
            batchItemId = 10L,
            itemId = 20L,
            name = name,
            qty = 1,
            lineGrossMinor = lineGrossMinor,
            currency = "RUB",
            status = "excluded",
            reason = reason,
        )
}
