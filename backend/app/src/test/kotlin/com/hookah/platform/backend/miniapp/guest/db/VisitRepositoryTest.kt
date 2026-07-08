package com.hookah.platform.backend.miniapp.guest.db

import com.hookah.platform.backend.miniapp.venue.VenueRole
import com.hookah.platform.backend.miniapp.venue.orders.OrderActionActor
import com.hookah.platform.backend.miniapp.venue.orders.OrderWorkflowStatus
import com.hookah.platform.backend.miniapp.venue.orders.VenueOrdersRepository
import kotlinx.coroutines.runBlocking
import org.flywaydb.core.Flyway
import org.h2.jdbcx.JdbcDataSource
import java.sql.DriverManager
import java.sql.Statement
import java.sql.Timestamp
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class VisitRepositoryTest {
    @Test
    fun `seated booking creates one idempotent visit`() =
        runBlocking {
            val jdbcUrl = migratedJdbcUrl("visit-booking-seated")
            val fixture = seedBase(jdbcUrl)
            val visitRepository = VisitRepository(dataSource(jdbcUrl))
            val bookingRepository = GuestBookingRepository(dataSource(jdbcUrl), visitRepository)
            val serviceDate = LocalDate.of(2030, 5, 10)
            val scheduledAt = LocalDateTime.of(serviceDate, LocalTime.of(20, 0)).atZone(ZONE_ID).toInstant()
            val booking =
                bookingRepository.create(
                    venueId = fixture.venueId,
                    userId = GUEST_ONE,
                    scheduledAt = scheduledAt,
                    partySize = 2,
                    comment = null,
                    venueZoneId = ZONE_ID,
                    serviceDate = serviceDate,
                )
            assertNotNull(
                bookingRepository.updateByVenue(
                    bookingId = booking.id,
                    venueId = fixture.venueId,
                    nextStatus = BookingStatus.CONFIRMED,
                ),
            )

            assertNotNull(bookingRepository.markSeated(fixture.venueId, booking.id, actorUserId = STAFF_USER))
            assertEquals(1L, visitRepository.getVisitCount(GUEST_ONE, fixture.venueId))
            assertNull(bookingRepository.markSeated(fixture.venueId, booking.id, actorUserId = STAFF_USER))
            assertEquals(1L, visitRepository.getVisitCount(GUEST_ONE, fixture.venueId))

            val visit = visitRepository.findRecentVisits(GUEST_ONE).single()
            assertEquals(booking.id, visit.bookingId)
            assertEquals(VisitSource.BOOKING_SEATED, visit.source)
            assertEquals(serviceDate, visit.serviceDate)
        }

    @Test
    fun `non seated terminal booking does not create visit`() =
        runBlocking {
            val jdbcUrl = migratedJdbcUrl("visit-booking-terminal-not-seated")
            val fixture = seedBase(jdbcUrl)
            val visitRepository = VisitRepository(dataSource(jdbcUrl))
            val bookingRepository = GuestBookingRepository(dataSource(jdbcUrl), visitRepository)

            suspend fun confirmedBooking(
                scheduledAt: Instant,
                serviceDate: LocalDate,
            ): BookingRecord {
                val booking =
                    bookingRepository.create(
                        venueId = fixture.venueId,
                        userId = GUEST_ONE,
                        scheduledAt = scheduledAt,
                        partySize = 2,
                        comment = null,
                        venueZoneId = ZONE_ID,
                        serviceDate = serviceDate,
                    )
                assertNotNull(
                    bookingRepository.updateByVenue(
                        bookingId = booking.id,
                        venueId = fixture.venueId,
                        nextStatus = BookingStatus.CONFIRMED,
                    ),
                )
                return booking
            }

            val noShow = confirmedBooking(Instant.parse("2030-05-10T17:00:00Z"), LocalDate.of(2030, 5, 10))
            assertNotNull(bookingRepository.markNoShow(fixture.venueId, noShow.id, actorUserId = STAFF_USER))

            val canceled = confirmedBooking(Instant.parse("2030-05-10T18:00:00Z"), LocalDate.of(2030, 5, 10))
            assertNotNull(bookingRepository.cancelByGuest(canceled.id, fixture.venueId, GUEST_ONE))

            val expired = confirmedBooking(Instant.parse("2030-05-10T19:00:00Z"), LocalDate.of(2030, 5, 10))
            assertEquals(1, bookingRepository.expireOverdue(Instant.parse("2030-05-11T00:00:00Z")))

            assertEquals(0L, visitRepository.getVisitCount(GUEST_ONE, fixture.venueId))
            assertNull(visitRepository.recordBookingSeatedVisit(noShow.id))
            assertNull(visitRepository.recordBookingSeatedVisit(canceled.id))
            assertNull(visitRepository.recordBookingSeatedVisit(expired.id))
        }

    @Test
    fun `legacy non seated booking visits are excluded from guest history`() =
        runBlocking {
            val jdbcUrl = migratedJdbcUrl("visit-history-filters-legacy-bookings")
            val fixture = seedBase(jdbcUrl)
            val visitRepository = VisitRepository(dataSource(jdbcUrl))
            val filteredVisitIds = mutableListOf<Long>()
            DriverManager.getConnection(jdbcUrl, "sa", "").use { connection ->
                listOf("CANCELED", "NO_SHOW", "EXPIRED", "PENDING", "CHANGED").forEachIndexed { index, status ->
                    val bookingId =
                        insertBooking(
                            connection = connection,
                            venueId = fixture.venueId,
                            userId = GUEST_ONE,
                            status = status,
                            displayNumber = index + 1,
                        )
                    filteredVisitIds.add(
                        insertBookingVisit(
                            connection = connection,
                            venueId = fixture.venueId,
                            userId = GUEST_ONE,
                            bookingId = bookingId,
                        ),
                    )
                }
                val seatedBookingId =
                    insertBooking(
                        connection = connection,
                        venueId = fixture.venueId,
                        userId = GUEST_ONE,
                        status = "SEATED",
                        displayNumber = 10,
                    )
                insertBookingVisit(
                    connection = connection,
                    venueId = fixture.venueId,
                    userId = GUEST_ONE,
                    bookingId = seatedBookingId,
                )
            }

            val history = visitRepository.listGuestVisitHistory(GUEST_ONE)

            assertEquals(1, history.size)
            assertEquals(VisitSource.BOOKING_SEATED, history.single().source)
            filteredVisitIds.forEach { visitId ->
                assertNull(visitRepository.getGuestVisitDetail(GUEST_ONE, visitId))
            }
        }

    @Test
    fun `arrival terminal actions require confirmed booking`() =
        runBlocking {
            val jdbcUrl = migratedJdbcUrl("visit-booking-arrival-confirmed-only")
            val fixture = seedBase(jdbcUrl)
            val visitRepository = VisitRepository(dataSource(jdbcUrl))
            val bookingRepository = GuestBookingRepository(dataSource(jdbcUrl), visitRepository)

            suspend fun createBooking(scheduledAt: Instant) =
                bookingRepository.create(
                    venueId = fixture.venueId,
                    userId = GUEST_ONE,
                    scheduledAt = scheduledAt,
                    partySize = 2,
                    comment = null,
                    venueZoneId = ZONE_ID,
                    serviceDate = LocalDate.of(2030, 5, 10),
                )

            val pendingSeat = createBooking(Instant.parse("2030-05-10T17:00:00Z"))
            assertNull(bookingRepository.markSeated(fixture.venueId, pendingSeat.id, actorUserId = STAFF_USER))

            val pendingNoShow = createBooking(Instant.parse("2030-05-10T17:15:00Z"))
            assertNull(bookingRepository.markNoShow(fixture.venueId, pendingNoShow.id, actorUserId = STAFF_USER))

            val changedSeat = createBooking(Instant.parse("2030-05-10T17:30:00Z"))
            assertNotNull(
                bookingRepository.updateByVenue(
                    bookingId = changedSeat.id,
                    venueId = fixture.venueId,
                    nextStatus = BookingStatus.CHANGED,
                    scheduledAt = Instant.parse("2030-05-10T18:00:00Z"),
                    venueZoneId = ZONE_ID,
                    serviceDate = LocalDate.of(2030, 5, 10),
                ),
            )
            assertNull(bookingRepository.markSeated(fixture.venueId, changedSeat.id, actorUserId = STAFF_USER))

            val changedNoShow = createBooking(Instant.parse("2030-05-10T18:15:00Z"))
            assertNotNull(
                bookingRepository.updateByVenue(
                    bookingId = changedNoShow.id,
                    venueId = fixture.venueId,
                    nextStatus = BookingStatus.CHANGED,
                    scheduledAt = Instant.parse("2030-05-10T18:30:00Z"),
                    venueZoneId = ZONE_ID,
                    serviceDate = LocalDate.of(2030, 5, 10),
                ),
            )
            assertNull(bookingRepository.markNoShow(fixture.venueId, changedNoShow.id, actorUserId = STAFF_USER))

            assertEquals(0L, visitRepository.getVisitCount(GUEST_ONE, fixture.venueId))
        }

    @Test
    fun `closed order creates one visit per real guest participant`() =
        runBlocking {
            val jdbcUrl = migratedJdbcUrl("visit-order-closed")
            val fixture = seedBase(jdbcUrl)
            val visitRepository = VisitRepository(dataSource(jdbcUrl))
            val orderFixture = seedActiveOrderWithParticipants(jdbcUrl, fixture)
            val orderRepository = VenueOrdersRepository(dataSource(jdbcUrl), visitRepository = visitRepository)

            val result =
                orderRepository.updateOrderStatus(
                    venueId = fixture.venueId,
                    orderId = orderFixture.orderId,
                    nextStatus = OrderWorkflowStatus.CLOSED,
                    actor = OrderActionActor(userId = STAFF_USER, role = VenueRole.STAFF),
                )

            assertNotNull(result)
            assertEquals(true, result.applied)
            assertEquals(1L, visitRepository.getVisitCount(GUEST_ONE, fixture.venueId))
            assertEquals(1L, visitRepository.getVisitCount(GUEST_TWO, fixture.venueId))
            assertEquals(0L, visitRepository.getVisitCount(SHARED_ONLY_GUEST, fixture.venueId))
            assertEquals(0L, visitRepository.getVisitCount(STAFF_USER, fixture.venueId))

            val secondClose =
                orderRepository.updateOrderStatus(
                    venueId = fixture.venueId,
                    orderId = orderFixture.orderId,
                    nextStatus = OrderWorkflowStatus.CLOSED,
                    actor = OrderActionActor(userId = STAFF_USER, role = VenueRole.STAFF),
                )
            assertNotNull(secondClose)
            assertEquals(false, secondClose.applied)
            assertEquals(2L, visitRepository.getVisitCount(GUEST_ONE) + visitRepository.getVisitCount(GUEST_TWO))
        }

    @Test
    fun `shared common bill schedules feedback per real guest visit only`() =
        runBlocking {
            val jdbcUrl = migratedJdbcUrl("visit-feedback-shared-bill")
            val fixture = seedBase(jdbcUrl)
            val visitRepository = VisitRepository(dataSource(jdbcUrl))
            val feedbackRepository = VisitFeedbackRepository(dataSource(jdbcUrl))
            val orderFixture = seedActiveOrderWithParticipants(jdbcUrl, fixture)
            DriverManager.getConnection(jdbcUrl, "sa", "").use { connection ->
                val guestThreeTab =
                    insertTab(connection, fixture.venueId, orderFixture.tableSessionId, GUEST_THREE, "PERSONAL")
                val guestThreeBatch = insertBatch(connection, orderFixture.orderId, guestThreeTab, null, "MINIAPP")
                insertBatchItem(connection, guestThreeBatch, fixture.itemId)
                insertGuestBatchIdempotency(
                    connection,
                    fixture.venueId,
                    orderFixture.tableSessionId,
                    GUEST_THREE,
                    orderFixture.orderId,
                    guestThreeBatch,
                    "guest-three-1",
                )
            }
            val orderRepository =
                VenueOrdersRepository(
                    dataSource = dataSource(jdbcUrl),
                    visitRepository = visitRepository,
                    visitFeedbackRepository = feedbackRepository,
                )

            val result =
                orderRepository.updateOrderStatus(
                    venueId = fixture.venueId,
                    orderId = orderFixture.orderId,
                    nextStatus = OrderWorkflowStatus.CLOSED,
                    actor = OrderActionActor(userId = STAFF_USER, role = VenueRole.STAFF),
                )

            assertNotNull(result)
            assertEquals(true, result.applied)
            assertEquals(1L, visitRepository.getVisitCount(GUEST_ONE, fixture.venueId))
            assertEquals(1L, visitRepository.getVisitCount(GUEST_TWO, fixture.venueId))
            assertEquals(1L, visitRepository.getVisitCount(GUEST_THREE, fixture.venueId))
            assertEquals(0L, visitRepository.getVisitCount(SHARED_ONLY_GUEST, fixture.venueId))
            assertEquals(0L, visitRepository.getVisitCount(STAFF_USER, fixture.venueId))
            assertEquals(listOf(GUEST_ONE, GUEST_TWO, GUEST_THREE), fetchFeedbackRequestUserIds(jdbcUrl))

            val staleClose =
                orderRepository.updateOrderStatus(
                    venueId = fixture.venueId,
                    orderId = orderFixture.orderId,
                    nextStatus = OrderWorkflowStatus.CLOSED,
                    actor = OrderActionActor(userId = STAFF_USER, role = VenueRole.STAFF),
                )

            assertNotNull(staleClose)
            assertEquals(false, staleClose.applied)
            assertEquals(listOf(GUEST_ONE, GUEST_TWO, GUEST_THREE), fetchFeedbackRequestUserIds(jdbcUrl))
            assertEquals(3, countRows(jdbcUrl, "visits"))
            assertEquals(3, countRows(jdbcUrl, "visit_feedback_requests"))
        }

    @Test
    fun `guest visit detail loads owned billable items from all closed orders in table session`() =
        runBlocking {
            val jdbcUrl = migratedJdbcUrl("visit-detail-orders")
            val fixture = seedBase(jdbcUrl)
            val visitRepository = VisitRepository(dataSource(jdbcUrl))
            val orderFixture = seedActiveOrderWithParticipants(jdbcUrl, fixture)
            val orderRepository = VenueOrdersRepository(dataSource(jdbcUrl), visitRepository = visitRepository)

            orderRepository.updateOrderStatus(
                venueId = fixture.venueId,
                orderId = orderFixture.orderId,
                nextStatus = OrderWorkflowStatus.CLOSED,
                actor = OrderActionActor(userId = STAFF_USER, role = VenueRole.STAFF),
            )
            insertClosedOrderForGuest(
                jdbcUrl = jdbcUrl,
                fixture = fixture,
                tableSessionId = orderFixture.tableSessionId,
                userId = GUEST_ONE,
                displayNumber = 2,
            )

            val visit = visitRepository.findRecentVisits(GUEST_ONE).single()
            val detail = visitRepository.getGuestVisitDetail(GUEST_ONE, visit.id)

            assertNotNull(detail)
            assertEquals(2, detail.orders.size)
            assertEquals(listOf(1, 2), detail.orders.map { it.displayNumber })
            assertEquals(3, detail.orders.flatMap { it.items }.sumOf { it.qty })
            assertEquals(3000L, detail.totalMinor)
            assertEquals("RUB", detail.currency)
            assertNull(visitRepository.getGuestVisitDetail(SHARED_ONLY_GUEST, visit.id))
        }

    @Test
    fun `guest visit detail subtracts persisted promotion adjustment`() =
        runBlocking {
            val jdbcUrl = migratedJdbcUrl("visit-detail-promo")
            val fixture = seedBase(jdbcUrl)
            val visitRepository = VisitRepository(dataSource(jdbcUrl))
            val orderFixture = seedActiveOrderWithParticipants(jdbcUrl, fixture)
            val orderRepository = VenueOrdersRepository(dataSource(jdbcUrl), visitRepository = visitRepository)

            orderRepository.updateOrderStatus(
                venueId = fixture.venueId,
                orderId = orderFixture.orderId,
                nextStatus = OrderWorkflowStatus.CLOSED,
                actor = OrderActionActor(userId = STAFF_USER, role = VenueRole.STAFF),
            )
            insertPromotionAdjustmentForGuestOrder(
                jdbcUrl = jdbcUrl,
                fixture = fixture,
                orderId = orderFixture.orderId,
                userId = GUEST_ONE,
                discountMinor = 200L,
            )

            val visit = visitRepository.findRecentVisits(GUEST_ONE).single()
            val detail = visitRepository.getGuestVisitDetail(GUEST_ONE, visit.id)

            assertNotNull(detail)
            assertEquals(1800L, detail.totalMinor)
            assertEquals(200L, detail.orders.single().items.single().promoDiscountMinor)
            assertEquals(
                listOf("Счастливые часы" to 200L),
                detail.orders.single().promotionDiscounts.map {
                    it.label to it.discountMinor
                },
            )
        }

    @Test
    fun `guest visit detail preserves selected option and preference note snapshots`() =
        runBlocking {
            val jdbcUrl = migratedJdbcUrl("visit-detail-option-note")
            val fixture = seedBase(jdbcUrl)
            val visitRepository = VisitRepository(dataSource(jdbcUrl))
            val orderFixture = seedOrderShell(jdbcUrl, fixture)
            DriverManager.getConnection(jdbcUrl, "sa", "").use { connection ->
                val guestTab =
                    insertTab(connection, fixture.venueId, orderFixture.tableSessionId, GUEST_ONE, "PERSONAL")
                val batch = insertBatch(connection, orderFixture.orderId, guestTab, null, "MINIAPP")
                val batchItem =
                    insertBatchItem(
                        connection = connection,
                        batchId = batch,
                        itemId = fixture.itemId,
                        preferenceNote = "покрепче",
                    )
                insertBatchItemOption(connection, batchItem, optionName = "Ягодный микс", priceDeltaMinor = 250L)
                insertGuestBatchIdempotency(
                    connection,
                    fixture.venueId,
                    orderFixture.tableSessionId,
                    GUEST_ONE,
                    orderFixture.orderId,
                    batch,
                    "option-note",
                )
                markOrderClosed(connection, orderFixture.orderId)
            }

            val result = visitRepository.recordOrderClosedVisits(orderFixture.orderId)
            val visit = visitRepository.findRecentVisits(GUEST_ONE).single()
            val detail = visitRepository.getGuestVisitDetail(GUEST_ONE, visit.id)

            assertEquals(1, result.participantsCount)
            assertNotNull(detail)
            val item = detail.orders.single().items.single()
            assertEquals("Ягодный микс", item.selectedOption?.name)
            assertEquals(250L, item.selectedOption?.priceDeltaMinor)
            assertEquals("покрепче", item.preferenceNote)
            assertEquals(1250L, item.priceMinor)
            assertEquals(1250L, item.totalMinor)
            assertEquals(1250L, detail.totalMinor)
        }

    @Test
    fun `rejected and excluded only guest batches do not create visits`() =
        runBlocking {
            val jdbcUrl = migratedJdbcUrl("visit-invalid-batches")
            val fixture = seedBase(jdbcUrl)
            val visitRepository = VisitRepository(dataSource(jdbcUrl))
            val orderFixture = seedOrderShell(jdbcUrl, fixture)
            DriverManager.getConnection(jdbcUrl, "sa", "").use { connection ->
                val guestOneTab =
                    insertTab(connection, fixture.venueId, orderFixture.tableSessionId, GUEST_ONE, "PERSONAL")
                val rejectedBatch =
                    insertBatch(connection, orderFixture.orderId, guestOneTab, null, "MINIAPP", status = "REJECTED")
                insertBatchItem(connection, rejectedBatch, fixture.itemId)
                insertGuestBatchIdempotency(
                    connection,
                    fixture.venueId,
                    orderFixture.tableSessionId,
                    GUEST_ONE,
                    orderFixture.orderId,
                    rejectedBatch,
                    "rejected",
                )

                val guestTwoTab =
                    insertTab(connection, fixture.venueId, orderFixture.tableSessionId, GUEST_TWO, "PERSONAL")
                val excludedBatch = insertBatch(connection, orderFixture.orderId, guestTwoTab, null, "MINIAPP")
                insertBatchItem(connection, excludedBatch, fixture.itemId, isExcluded = true)
                insertGuestBatchIdempotency(
                    connection,
                    fixture.venueId,
                    orderFixture.tableSessionId,
                    GUEST_TWO,
                    orderFixture.orderId,
                    excludedBatch,
                    "excluded",
                )
                markOrderClosed(connection, orderFixture.orderId)
            }

            val result = visitRepository.recordOrderClosedVisits(orderFixture.orderId)

            assertEquals(0, result.participantsCount)
            assertEquals(0L, visitRepository.getVisitCount(GUEST_ONE, fixture.venueId))
            assertEquals(0L, visitRepository.getVisitCount(GUEST_TWO, fixture.venueId))
        }

    @Test
    fun `canceled only guest batch does not create visit`() =
        runBlocking {
            val jdbcUrl = migratedJdbcUrl("visit-canceled-item-batch")
            val fixture = seedBase(jdbcUrl)
            val visitRepository = VisitRepository(dataSource(jdbcUrl))
            val orderFixture = seedOrderShell(jdbcUrl, fixture)
            DriverManager.getConnection(jdbcUrl, "sa", "").use { connection ->
                val guestTab =
                    insertTab(connection, fixture.venueId, orderFixture.tableSessionId, GUEST_ONE, "PERSONAL")
                val batch = insertBatch(connection, orderFixture.orderId, guestTab, null, "MINIAPP")
                insertBatchItem(connection, batch, fixture.itemId, itemStatus = "CANCELED")
                insertGuestBatchIdempotency(
                    connection,
                    fixture.venueId,
                    orderFixture.tableSessionId,
                    GUEST_ONE,
                    orderFixture.orderId,
                    batch,
                    "canceled",
                )
                markOrderClosed(connection, orderFixture.orderId)
            }

            val result = visitRepository.recordOrderClosedVisits(orderFixture.orderId)

            assertEquals(0, result.participantsCount)
            assertEquals(0L, visitRepository.getVisitCount(GUEST_ONE, fixture.venueId))
        }

    @Test
    fun `guest batch with at least one non excluded item creates visit`() =
        runBlocking {
            val jdbcUrl = migratedJdbcUrl("visit-valid-batch")
            val fixture = seedBase(jdbcUrl)
            val visitRepository = VisitRepository(dataSource(jdbcUrl))
            val orderFixture = seedOrderShell(jdbcUrl, fixture)
            DriverManager.getConnection(jdbcUrl, "sa", "").use { connection ->
                val guestTab =
                    insertTab(connection, fixture.venueId, orderFixture.tableSessionId, GUEST_ONE, "PERSONAL")
                val batch = insertBatch(connection, orderFixture.orderId, guestTab, null, "MINIAPP")
                insertBatchItem(connection, batch, fixture.itemId, isExcluded = true)
                insertBatchItem(connection, batch, fixture.itemId, isExcluded = false)
                insertGuestBatchIdempotency(
                    connection,
                    fixture.venueId,
                    orderFixture.tableSessionId,
                    GUEST_ONE,
                    orderFixture.orderId,
                    batch,
                    "valid",
                )
                markOrderClosed(connection, orderFixture.orderId)
            }

            val result = visitRepository.recordOrderClosedVisits(orderFixture.orderId)

            assertEquals(1, result.participantsCount)
            assertEquals(1L, visitRepository.getVisitCount(GUEST_ONE, fixture.venueId))
        }

    @Test
    fun `shared tab batch author with billable item creates visit`() =
        runBlocking {
            val jdbcUrl = migratedJdbcUrl("visit-shared-author")
            val fixture = seedBase(jdbcUrl)
            val visitRepository = VisitRepository(dataSource(jdbcUrl))
            val orderFixture = seedOrderShell(jdbcUrl, fixture)
            DriverManager.getConnection(jdbcUrl, "sa", "").use { connection ->
                val sharedTab = insertTab(connection, fixture.venueId, orderFixture.tableSessionId, null, "SHARED")
                insertTabMember(connection, sharedTab, SHARED_ONLY_GUEST)
                insertTabMember(connection, sharedTab, GUEST_ONE)
                val batch = insertBatch(connection, orderFixture.orderId, sharedTab, GUEST_ONE, "MINIAPP")
                insertBatchItem(connection, batch, fixture.itemId)
                markOrderClosed(connection, orderFixture.orderId)
            }

            val result = visitRepository.recordOrderClosedVisits(orderFixture.orderId)

            assertEquals(1, result.participantsCount)
            assertEquals(1L, visitRepository.getVisitCount(GUEST_ONE, fixture.venueId))
            assertEquals(0L, visitRepository.getVisitCount(SHARED_ONLY_GUEST, fixture.venueId))
            val visit = visitRepository.findRecentVisits(GUEST_ONE).single()
            assertNotNull(visitRepository.getGuestVisitDetail(GUEST_ONE, visit.id))
            assertNull(visitRepository.getGuestVisitDetail(SHARED_ONLY_GUEST, visit.id))
        }

    @Test
    fun `same booking and later closed order merge into one visit`() =
        runBlocking {
            val jdbcUrl = migratedJdbcUrl("visit-merge")
            val fixture = seedBase(jdbcUrl)
            val visitRepository = VisitRepository(dataSource(jdbcUrl))
            val bookingRepository = GuestBookingRepository(dataSource(jdbcUrl), visitRepository)
            val serviceDate = LocalDate.of(2030, 5, 10)
            val booking =
                bookingRepository.create(
                    venueId = fixture.venueId,
                    userId = GUEST_ONE,
                    scheduledAt = LocalDateTime.of(serviceDate, LocalTime.of(20, 0)).atZone(ZONE_ID).toInstant(),
                    partySize = 2,
                    comment = null,
                    venueZoneId = ZONE_ID,
                    serviceDate = serviceDate,
                )
            assertNotNull(
                bookingRepository.updateByVenue(
                    bookingId = booking.id,
                    venueId = fixture.venueId,
                    nextStatus = BookingStatus.CONFIRMED,
                ),
            )
            assertNotNull(bookingRepository.markSeated(fixture.venueId, booking.id, actorUserId = STAFF_USER))
            val orderFixture = seedActiveOrderWithParticipants(jdbcUrl, fixture)
            val orderRepository = VenueOrdersRepository(dataSource(jdbcUrl), visitRepository = visitRepository)

            orderRepository.updateOrderStatus(
                venueId = fixture.venueId,
                orderId = orderFixture.orderId,
                nextStatus = OrderWorkflowStatus.CLOSED,
                actor = OrderActionActor(userId = STAFF_USER, role = VenueRole.STAFF),
            )

            assertEquals(1L, visitRepository.getVisitCount(GUEST_ONE, fixture.venueId))
            val visit = visitRepository.findRecentVisits(GUEST_ONE).single()
            assertEquals(booking.id, visit.bookingId)
            assertEquals(orderFixture.orderId, visit.orderId)
            assertEquals(orderFixture.tableSessionId, visit.tableSessionId)
            assertEquals(VisitSource.MERGED, visit.source)
        }

    @Test
    fun `table session cleanup alone does not create visit`() =
        runBlocking {
            val jdbcUrl = migratedJdbcUrl("visit-table-session-cleanup")
            val fixture = seedBase(jdbcUrl)
            val visitRepository = VisitRepository(dataSource(jdbcUrl))
            val tableSessionRepository = TableSessionRepository(dataSource(jdbcUrl))
            seedBareExpiredTableSession(jdbcUrl, fixture)

            tableSessionRepository.closeExpiredSessions(Instant.parse("2030-05-10T12:00:00Z"))

            assertEquals(0L, visitRepository.getVisitCount(GUEST_ONE, fixture.venueId))
        }

    private fun migratedJdbcUrl(prefix: String): String {
        val jdbcUrl =
            "jdbc:h2:mem:$prefix-${UUID.randomUUID()};MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;" +
                "DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1"
        Flyway.configure()
            .dataSource(jdbcUrl, "sa", "")
            .locations("classpath:db/migration/h2")
            .load()
            .migrate()
        return jdbcUrl
    }

    private fun dataSource(jdbcUrl: String): JdbcDataSource =
        JdbcDataSource().apply {
            setURL(jdbcUrl)
            user = "sa"
            password = ""
        }

    private fun seedBase(jdbcUrl: String): BaseFixture =
        DriverManager.getConnection(jdbcUrl, "sa", "").use { connection ->
            listOf(GUEST_ONE, GUEST_TWO, GUEST_THREE, SHARED_ONLY_GUEST, STAFF_USER).forEach { userId ->
                connection.prepareStatement(
                    """
                    INSERT INTO users (telegram_user_id, username, first_name)
                    VALUES (?, ?, ?)
                    """.trimIndent(),
                ).use { statement ->
                    statement.setLong(1, userId)
                    statement.setString(2, "user$userId")
                    statement.setString(3, "User $userId")
                    statement.executeUpdate()
                }
            }
            val venueId =
                connection.prepareStatement(
                    """
                    INSERT INTO venues (name, city, address, status)
                    VALUES ('Venue', 'City', 'Address', 'PUBLISHED')
                    """.trimIndent(),
                    Statement.RETURN_GENERATED_KEYS,
                ).use { statement ->
                    statement.executeUpdate()
                    statement.generatedKeys.use { keys ->
                        keys.next()
                        keys.getLong(1)
                    }
                }
            connection.prepareStatement(
                """
                INSERT INTO venue_members (venue_id, user_id, role)
                VALUES (?, ?, 'STAFF')
                """.trimIndent(),
            ).use { statement ->
                statement.setLong(1, venueId)
                statement.setLong(2, STAFF_USER)
                statement.executeUpdate()
            }
            val tableId =
                connection.prepareStatement(
                    """
                    INSERT INTO venue_tables (venue_id, table_number, is_active)
                    VALUES (?, 10, true)
                    """.trimIndent(),
                    Statement.RETURN_GENERATED_KEYS,
                ).use { statement ->
                    statement.setLong(1, venueId)
                    statement.executeUpdate()
                    statement.generatedKeys.use { keys ->
                        keys.next()
                        keys.getLong(1)
                    }
                }
            val categoryId =
                connection.prepareStatement(
                    """
                    INSERT INTO menu_categories (venue_id, name, sort_order, is_active)
                    VALUES (?, 'Hookah', 0, true)
                    """.trimIndent(),
                    Statement.RETURN_GENERATED_KEYS,
                ).use { statement ->
                    statement.setLong(1, venueId)
                    statement.executeUpdate()
                    statement.generatedKeys.use { keys ->
                        keys.next()
                        keys.getLong(1)
                    }
                }
            val itemId =
                connection.prepareStatement(
                    """
                    INSERT INTO menu_items (venue_id, category_id, name, price_minor, currency, is_available)
                    VALUES (?, ?, 'Hookah', 1000, 'RUB', true)
                    """.trimIndent(),
                    Statement.RETURN_GENERATED_KEYS,
                ).use { statement ->
                    statement.setLong(1, venueId)
                    statement.setLong(2, categoryId)
                    statement.executeUpdate()
                    statement.generatedKeys.use { keys ->
                        keys.next()
                        keys.getLong(1)
                    }
                }
            BaseFixture(venueId = venueId, tableId = tableId, itemId = itemId)
        }

    private fun seedOrderShell(
        jdbcUrl: String,
        fixture: BaseFixture,
    ): OrderFixture =
        DriverManager.getConnection(jdbcUrl, "sa", "").use { connection ->
            val now = Instant.parse("2030-05-10T10:00:00Z")
            val tableSessionId =
                connection.prepareStatement(
                    """
                    INSERT INTO table_sessions (venue_id, table_id, started_at, last_activity_at, expires_at, status)
                    VALUES (?, ?, ?, ?, ?, 'ACTIVE')
                    """.trimIndent(),
                    Statement.RETURN_GENERATED_KEYS,
                ).use { statement ->
                    statement.setLong(1, fixture.venueId)
                    statement.setLong(2, fixture.tableId)
                    statement.setTimestamp(3, Timestamp.from(now))
                    statement.setTimestamp(4, Timestamp.from(now))
                    statement.setTimestamp(5, Timestamp.from(now.plusSeconds(7200)))
                    statement.executeUpdate()
                    statement.generatedKeys.use { keys ->
                        keys.next()
                        keys.getLong(1)
                    }
                }
            val orderId =
                connection.prepareStatement(
                    """
                    INSERT INTO orders (venue_id, table_id, table_session_id, status, display_number, display_date)
                    VALUES (?, ?, ?, 'ACTIVE', 1, ?)
                    """.trimIndent(),
                    Statement.RETURN_GENERATED_KEYS,
                ).use { statement ->
                    statement.setLong(1, fixture.venueId)
                    statement.setLong(2, fixture.tableId)
                    statement.setLong(3, tableSessionId)
                    statement.setDate(4, java.sql.Date.valueOf(LocalDate.of(2030, 5, 10)))
                    statement.executeUpdate()
                    statement.generatedKeys.use { keys ->
                        keys.next()
                        keys.getLong(1)
                    }
                }
            OrderFixture(orderId = orderId, tableSessionId = tableSessionId)
        }

    private fun seedActiveOrderWithParticipants(
        jdbcUrl: String,
        fixture: BaseFixture,
    ): OrderFixture =
        DriverManager.getConnection(jdbcUrl, "sa", "").use { connection ->
            val now = Instant.parse("2030-05-10T10:00:00Z")
            val tableSessionId =
                connection.prepareStatement(
                    """
                    INSERT INTO table_sessions (venue_id, table_id, started_at, last_activity_at, expires_at, status)
                    VALUES (?, ?, ?, ?, ?, 'ACTIVE')
                    """.trimIndent(),
                    Statement.RETURN_GENERATED_KEYS,
                ).use { statement ->
                    statement.setLong(1, fixture.venueId)
                    statement.setLong(2, fixture.tableId)
                    statement.setTimestamp(3, Timestamp.from(now))
                    statement.setTimestamp(4, Timestamp.from(now))
                    statement.setTimestamp(5, Timestamp.from(now.plusSeconds(7200)))
                    statement.executeUpdate()
                    statement.generatedKeys.use { keys ->
                        keys.next()
                        keys.getLong(1)
                    }
                }
            val orderId =
                connection.prepareStatement(
                    """
                    INSERT INTO orders (venue_id, table_id, table_session_id, status, display_number, display_date)
                    VALUES (?, ?, ?, 'ACTIVE', 1, ?)
                    """.trimIndent(),
                    Statement.RETURN_GENERATED_KEYS,
                ).use { statement ->
                    statement.setLong(1, fixture.venueId)
                    statement.setLong(2, fixture.tableId)
                    statement.setLong(3, tableSessionId)
                    statement.setDate(4, java.sql.Date.valueOf(LocalDate.of(2030, 5, 10)))
                    statement.executeUpdate()
                    statement.generatedKeys.use { keys ->
                        keys.next()
                        keys.getLong(1)
                    }
                }
            val guestOneTab = insertTab(connection, fixture.venueId, tableSessionId, GUEST_ONE, "PERSONAL")
            val guestTwoTab = insertTab(connection, fixture.venueId, tableSessionId, GUEST_TWO, "PERSONAL")
            val sharedTab = insertTab(connection, fixture.venueId, tableSessionId, null, "SHARED")
            insertTabMember(connection, sharedTab, SHARED_ONLY_GUEST)

            val guestOneFirstBatch = insertBatch(connection, orderId, guestOneTab, null, "MINIAPP")
            insertBatchItem(connection, guestOneFirstBatch, fixture.itemId)
            insertGuestBatchIdempotency(
                connection,
                fixture.venueId,
                tableSessionId,
                GUEST_ONE,
                orderId,
                guestOneFirstBatch,
                "guest-one-1",
            )

            val guestOneSecondBatch = insertBatch(connection, orderId, guestOneTab, null, "MINIAPP")
            insertBatchItem(connection, guestOneSecondBatch, fixture.itemId)
            insertGuestBatchIdempotency(
                connection,
                fixture.venueId,
                tableSessionId,
                GUEST_ONE,
                orderId,
                guestOneSecondBatch,
                "guest-one-2",
            )

            val guestTwoBatch = insertBatch(connection, orderId, guestTwoTab, null, "MINIAPP")
            insertBatchItem(connection, guestTwoBatch, fixture.itemId)

            val sharedOnlyBatch = insertBatch(connection, orderId, sharedTab, null, "MINIAPP")
            insertBatchItem(connection, sharedOnlyBatch, fixture.itemId)

            val staffBatch = insertBatch(connection, orderId, null, STAFF_USER, "STAFF")
            insertBatchItem(connection, staffBatch, fixture.itemId)

            OrderFixture(orderId = orderId, tableSessionId = tableSessionId)
        }

    private fun insertClosedOrderForGuest(
        jdbcUrl: String,
        fixture: BaseFixture,
        tableSessionId: Long,
        userId: Long,
        displayNumber: Int,
    ): Long =
        DriverManager.getConnection(jdbcUrl, "sa", "").use { connection ->
            val orderId =
                connection.prepareStatement(
                    """
                    INSERT INTO orders (venue_id, table_id, table_session_id, status, display_number, display_date)
                    VALUES (?, ?, ?, 'CLOSED', ?, ?)
                    """.trimIndent(),
                    Statement.RETURN_GENERATED_KEYS,
                ).use { statement ->
                    statement.setLong(1, fixture.venueId)
                    statement.setLong(2, fixture.tableId)
                    statement.setLong(3, tableSessionId)
                    statement.setInt(4, displayNumber)
                    statement.setDate(5, java.sql.Date.valueOf(LocalDate.of(2030, 5, 10)))
                    statement.executeUpdate()
                    statement.generatedKeys.use { keys ->
                        keys.next()
                        keys.getLong(1)
                    }
                }
            val tab =
                findActivePersonalTab(connection, tableSessionId, userId)
                    ?: insertTab(connection, fixture.venueId, tableSessionId, userId, "PERSONAL")
            val batch = insertBatch(connection, orderId, tab, null, "MINIAPP")
            insertBatchItem(connection, batch, fixture.itemId)
            insertGuestBatchIdempotency(
                connection = connection,
                venueId = fixture.venueId,
                tableSessionId = tableSessionId,
                userId = userId,
                orderId = orderId,
                batchId = batch,
                idempotencyKey = "detail-$orderId",
            )
            orderId
        }

    private fun findActivePersonalTab(
        connection: java.sql.Connection,
        tableSessionId: Long,
        userId: Long,
    ): Long? =
        connection.prepareStatement(
            """
            SELECT id
            FROM tab
            WHERE table_session_id = ?
              AND owner_user_id = ?
              AND type = 'PERSONAL'
              AND status = 'ACTIVE'
            ORDER BY id
            LIMIT 1
            """.trimIndent(),
        ).use { statement ->
            statement.setLong(1, tableSessionId)
            statement.setLong(2, userId)
            statement.executeQuery().use { rs ->
                if (rs.next()) rs.getLong("id") else null
            }
        }

    private fun seedBareExpiredTableSession(
        jdbcUrl: String,
        fixture: BaseFixture,
    ) {
        DriverManager.getConnection(jdbcUrl, "sa", "").use { connection ->
            val now = Instant.parse("2030-05-10T09:00:00Z")
            connection.prepareStatement(
                """
                INSERT INTO table_sessions (venue_id, table_id, started_at, last_activity_at, expires_at, status)
                VALUES (?, ?, ?, ?, ?, 'ACTIVE')
                """.trimIndent(),
            ).use { statement ->
                statement.setLong(1, fixture.venueId)
                statement.setLong(2, fixture.tableId)
                statement.setTimestamp(3, Timestamp.from(now.minusSeconds(7200)))
                statement.setTimestamp(4, Timestamp.from(now.minusSeconds(3600)))
                statement.setTimestamp(5, Timestamp.from(now.minusSeconds(60)))
                statement.executeUpdate()
            }
        }
    }

    private fun insertBooking(
        connection: java.sql.Connection,
        venueId: Long,
        userId: Long,
        status: String,
        displayNumber: Int,
    ): Long =
        connection.prepareStatement(
            """
            INSERT INTO bookings (
                venue_id,
                user_id,
                scheduled_at,
                party_size,
                status,
                display_date,
                display_number
            )
            VALUES (?, ?, ?, 2, ?, ?, ?)
            """.trimIndent(),
            Statement.RETURN_GENERATED_KEYS,
        ).use { statement ->
            statement.setLong(1, venueId)
            statement.setLong(2, userId)
            statement.setTimestamp(3, Timestamp.from(Instant.parse("2030-05-10T17:00:00Z")))
            statement.setString(4, status)
            statement.setDate(5, java.sql.Date.valueOf(LocalDate.of(2030, 5, 10)))
            statement.setInt(6, displayNumber)
            statement.executeUpdate()
            statement.generatedKeys.use { keys ->
                keys.next()
                keys.getLong(1)
            }
        }

    private fun insertBookingVisit(
        connection: java.sql.Connection,
        venueId: Long,
        userId: Long,
        bookingId: Long,
    ): Long =
        connection.prepareStatement(
            """
            INSERT INTO visits (venue_id, user_id, booking_id, source, occurred_at, service_date)
            VALUES (?, ?, ?, 'BOOKING_SEATED', ?, ?)
            """.trimIndent(),
            Statement.RETURN_GENERATED_KEYS,
        ).use { statement ->
            statement.setLong(1, venueId)
            statement.setLong(2, userId)
            statement.setLong(3, bookingId)
            statement.setTimestamp(4, Timestamp.from(Instant.parse("2030-05-10T18:00:00Z")))
            statement.setDate(5, java.sql.Date.valueOf(LocalDate.of(2030, 5, 10)))
            statement.executeUpdate()
            statement.generatedKeys.use { keys ->
                keys.next()
                keys.getLong(1)
            }
        }

    private fun insertTab(
        connection: java.sql.Connection,
        venueId: Long,
        tableSessionId: Long,
        ownerUserId: Long?,
        type: String,
    ): Long =
        connection.prepareStatement(
            """
            INSERT INTO tab (venue_id, table_session_id, type, owner_user_id, status)
            VALUES (?, ?, ?, ?, 'ACTIVE')
            """.trimIndent(),
            Statement.RETURN_GENERATED_KEYS,
        ).use { statement ->
            statement.setLong(1, venueId)
            statement.setLong(2, tableSessionId)
            statement.setString(3, type)
            if (ownerUserId == null) {
                statement.setNull(4, java.sql.Types.BIGINT)
            } else {
                statement.setLong(4, ownerUserId)
            }
            statement.executeUpdate()
            statement.generatedKeys.use { keys ->
                keys.next()
                keys.getLong(1)
            }
        }

    private fun insertTabMember(
        connection: java.sql.Connection,
        tabId: Long,
        userId: Long,
    ) {
        connection.prepareStatement(
            """
            INSERT INTO tab_member (tab_id, user_id, role)
            VALUES (?, ?, 'MEMBER')
            """.trimIndent(),
        ).use { statement ->
            statement.setLong(1, tabId)
            statement.setLong(2, userId)
            statement.executeUpdate()
        }
    }

    private fun insertBatch(
        connection: java.sql.Connection,
        orderId: Long,
        tabId: Long?,
        authorUserId: Long?,
        source: String,
        status: String = "DELIVERED",
    ): Long =
        connection.prepareStatement(
            """
            INSERT INTO order_batches (order_id, tab_id, author_user_id, source, status, guest_comment)
            VALUES (?, ?, ?, ?, ?, NULL)
            """.trimIndent(),
            Statement.RETURN_GENERATED_KEYS,
        ).use { statement ->
            statement.setLong(1, orderId)
            if (tabId == null) {
                statement.setNull(2, java.sql.Types.BIGINT)
            } else {
                statement.setLong(2, tabId)
            }
            if (authorUserId == null) {
                statement.setNull(3, java.sql.Types.BIGINT)
            } else {
                statement.setLong(3, authorUserId)
            }
            statement.setString(4, source)
            statement.setString(5, status)
            statement.executeUpdate()
            statement.generatedKeys.use { keys ->
                keys.next()
                keys.getLong(1)
            }
        }

    private fun insertBatchItem(
        connection: java.sql.Connection,
        batchId: Long,
        itemId: Long,
        isExcluded: Boolean = false,
        itemStatus: String = "ACTIVE",
        preferenceNote: String? = null,
    ): Long =
        connection.prepareStatement(
            """
            INSERT INTO order_batch_items (order_batch_id, menu_item_id, qty, is_excluded, item_status, preference_note)
            VALUES (?, ?, 1, ?, ?, ?)
            """.trimIndent(),
            Statement.RETURN_GENERATED_KEYS,
        ).use { statement ->
            statement.setLong(1, batchId)
            statement.setLong(2, itemId)
            statement.setBoolean(3, isExcluded)
            statement.setString(4, itemStatus)
            statement.setString(5, preferenceNote)
            statement.executeUpdate()
            statement.generatedKeys.use { keys ->
                keys.next()
                keys.getLong(1)
            }
        }

    private fun insertBatchItemOption(
        connection: java.sql.Connection,
        batchItemId: Long,
        optionName: String,
        priceDeltaMinor: Long,
    ) {
        connection.prepareStatement(
            """
            INSERT INTO order_batch_item_options (
                order_batch_item_id,
                menu_item_option_id,
                option_name_snapshot,
                price_delta_minor_snapshot
            )
            VALUES (?, NULL, ?, ?)
            """.trimIndent(),
        ).use { statement ->
            statement.setLong(1, batchItemId)
            statement.setString(2, optionName)
            statement.setLong(3, priceDeltaMinor)
            statement.executeUpdate()
        }
    }

    private fun markOrderClosed(
        connection: java.sql.Connection,
        orderId: Long,
    ) {
        connection.prepareStatement("UPDATE orders SET status = 'CLOSED' WHERE id = ?").use { statement ->
            statement.setLong(1, orderId)
            statement.executeUpdate()
        }
    }

    private fun insertGuestBatchIdempotency(
        connection: java.sql.Connection,
        venueId: Long,
        tableSessionId: Long,
        userId: Long,
        orderId: Long,
        batchId: Long,
        idempotencyKey: String,
    ) {
        connection.prepareStatement(
            """
            INSERT INTO guest_batch_idempotency (venue_id, table_session_id, user_id, idempotency_key, order_id, batch_id)
            VALUES (?, ?, ?, ?, ?, ?)
            """.trimIndent(),
        ).use { statement ->
            statement.setLong(1, venueId)
            statement.setLong(2, tableSessionId)
            statement.setLong(3, userId)
            statement.setString(4, idempotencyKey)
            statement.setLong(5, orderId)
            statement.setLong(6, batchId)
            statement.executeUpdate()
        }
    }

    private fun fetchFeedbackRequestUserIds(jdbcUrl: String): List<Long> =
        DriverManager.getConnection(jdbcUrl, "sa", "").use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery("SELECT user_id FROM visit_feedback_requests ORDER BY user_id").use { rs ->
                    buildList {
                        while (rs.next()) add(rs.getLong("user_id"))
                    }
                }
            }
        }

    private fun insertPromotionAdjustmentForGuestOrder(
        jdbcUrl: String,
        fixture: BaseFixture,
        orderId: Long,
        userId: Long,
        discountMinor: Long,
    ) {
        DriverManager.getConnection(jdbcUrl, "sa", "").use { connection ->
            val batchItemId =
                connection.prepareStatement(
                    """
                    SELECT obi.id
                    FROM order_batch_items obi
                    JOIN order_batches ob ON ob.id = obi.order_batch_id
                    JOIN guest_batch_idempotency gbi ON gbi.batch_id = ob.id
                    WHERE ob.order_id = ?
                      AND gbi.user_id = ?
                    ORDER BY obi.id
                    LIMIT 1
                    """.trimIndent(),
                ).use { statement ->
                    statement.setLong(1, orderId)
                    statement.setLong(2, userId)
                    statement.executeQuery().use { rs ->
                        rs.next()
                        rs.getLong("id")
                    }
                }
            val ruleId = insertPromotionRule(connection, fixture.venueId)
            val applicationId =
                connection.prepareStatement(
                    """
                    INSERT INTO order_promotion_applications (
                        order_id,
                        batch_id,
                        venue_id,
                        user_id,
                        promotion_id,
                        rule_id,
                        title_snapshot,
                        rule_type,
                        target_type,
                        target_value,
                        discount_percent,
                        discount_total_minor,
                        currency,
                        dedupe_key
                    )
                    SELECT ?, obi.order_batch_id, ?, ?, NULL, ?, 'Счастливые часы',
                           'HAPPY_HOURS_PERCENT', 'CATEGORY_TYPE', 'HOOKAH', 20, ?, 'RUB', ?
                    FROM order_batch_items obi
                    WHERE obi.id = ?
                    """.trimIndent(),
                    Statement.RETURN_GENERATED_KEYS,
                ).use { statement ->
                    statement.setLong(1, orderId)
                    statement.setLong(2, fixture.venueId)
                    statement.setLong(3, userId)
                    statement.setLong(4, ruleId)
                    statement.setLong(5, discountMinor)
                    statement.setString(6, "visit-test:$orderId:$batchItemId")
                    statement.setLong(7, batchItemId)
                    statement.executeUpdate()
                    statement.generatedKeys.use { keys ->
                        keys.next()
                        keys.getLong(1)
                    }
                }
            connection.prepareStatement(
                """
                INSERT INTO order_batch_item_promotion_adjustments (
                    application_id,
                    order_batch_item_id,
                    menu_item_id,
                    discount_minor,
                    discount_percent,
                    original_price_minor,
                    quantity,
                    currency
                )
                VALUES (?, ?, ?, ?, 20, 1000, 1, 'RUB')
                """.trimIndent(),
            ).use { statement ->
                statement.setLong(1, applicationId)
                statement.setLong(2, batchItemId)
                statement.setLong(3, fixture.itemId)
                statement.setLong(4, discountMinor)
                statement.executeUpdate()
            }
        }
    }

    private fun insertPromotionRule(
        connection: java.sql.Connection,
        venueId: Long,
    ): Long =
        connection.prepareStatement(
            """
            INSERT INTO promotion_rules (
                promotion_id,
                venue_id,
                rule_type,
                target_type,
                target_value,
                discount_percent,
                status,
                priority,
                created_by_user_id
            )
            VALUES (NULL, ?, 'HAPPY_HOURS_PERCENT', 'CATEGORY_TYPE', 'HOOKAH', 20, 'ACTIVE', 100, ?)
            """.trimIndent(),
            Statement.RETURN_GENERATED_KEYS,
        ).use { statement ->
            statement.setLong(1, venueId)
            statement.setLong(2, STAFF_USER)
            statement.executeUpdate()
            statement.generatedKeys.use { keys ->
                keys.next()
                keys.getLong(1)
            }
        }

    private fun countRows(
        jdbcUrl: String,
        tableName: String,
    ): Int =
        DriverManager.getConnection(jdbcUrl, "sa", "").use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery("SELECT COUNT(*) FROM $tableName").use { rs ->
                    rs.next()
                    rs.getInt(1)
                }
            }
        }

    private data class BaseFixture(
        val venueId: Long,
        val tableId: Long,
        val itemId: Long,
    )

    private data class OrderFixture(
        val orderId: Long,
        val tableSessionId: Long,
    )

    private companion object {
        val ZONE_ID: ZoneId = ZoneId.of("Europe/Moscow")
        const val GUEST_ONE: Long = 1001L
        const val GUEST_TWO: Long = 1002L
        const val GUEST_THREE: Long = 1004L
        const val SHARED_ONLY_GUEST: Long = 1003L
        const val STAFF_USER: Long = 2001L
    }
}
