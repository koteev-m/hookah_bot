package com.hookah.platform.backend.miniapp.guest

import com.hookah.platform.backend.miniapp.guest.api.GuestTodayStaffDto
import com.hookah.platform.backend.miniapp.venue.staff.PublicVenueStaffToday
import com.hookah.platform.backend.miniapp.venue.staff.TodayStaffSource
import com.hookah.platform.backend.miniapp.venue.staff.VenueStaffModuleSettings
import com.hookah.platform.backend.miniapp.venue.staff.VenueStaffProfile
import com.hookah.platform.backend.miniapp.venue.staff.VenueStaffProfileRepository
import com.hookah.platform.backend.miniapp.venue.staff.VenueStaffScheduledShift
import com.hookah.platform.backend.miniapp.venue.staff.VenueStaffShift
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class VenueStaffModuleGuestResolverTest {
    private val now = Instant.parse("2026-08-04T00:30:00Z")
    private val zoneId = ZoneId.of("UTC")

    @Test
    fun `MANUAL preserves exact current public projection and DTO privacy`() =
        runBlocking {
            val repository = mockk<VenueStaffProfileRepository>()
            val resolver = GuestTodayStaffResolver(repository)
            val today = LocalDate.of(2026, 8, 4)
            coEvery { repository.listPublicTodayStaff(VENUE_ID, today) } returns
                listOf(
                    PublicVenueStaffToday(
                        id = 11L,
                        displayName = "Иван",
                        roleLabel = "Мастер",
                        subtype = "hookah_master",
                        photoRef = "private-photo-ref",
                        bio = "Публичное био",
                        tags = listOf("крепко"),
                        shiftId = 901L,
                        shiftDate = today,
                        startsAt = LocalTime.of(9, 0),
                        endsAt = LocalTime.of(18, 0),
                        shiftStatus = "scheduled",
                        manuallyMarkedActive = false,
                    ),
                )

            val result = resolver.resolve(VENUE_ID, now, zoneId, settings(TodayStaffSource.MANUAL))

            assertEquals(1, result.size)
            assertEquals("Иван", result.single().displayName)
            assertEquals("2026-08-04", result.single().shiftDate)
            assertEquals("09:00", result.single().startsAt)
            assertEquals("18:00", result.single().endsAt)
            assertEquals("scheduled", result.single().shiftStatus)
            assertNull(result.single().photoRef)
            assertPublicDtoOnly(result.single())
            coVerify(exactly = 1) { repository.listPublicTodayStaff(VENUE_ID, today) }
            coVerify(exactly = 0) { repository.listScheduledShifts(any(), any(), any()) }
        }

    @Test
    fun `master and guest visibility return empty before staff data reads`() =
        runBlocking {
            val repository = mockk<VenueStaffProfileRepository>()
            val resolver = GuestTodayStaffResolver(repository)

            val masterDisabled =
                resolver.resolve(
                    VENUE_ID,
                    now,
                    zoneId,
                    settings(TodayStaffSource.SCHEDULE, moduleEnabled = false),
                )
            val guestHidden =
                resolver.resolve(
                    VENUE_ID,
                    now,
                    zoneId,
                    settings(TodayStaffSource.MANUAL, guestVisible = false),
                )

            assertTrue(masterDisabled.isEmpty())
            assertTrue(guestHidden.isEmpty())
            coVerify(exactly = 0) { repository.listPublicTodayStaff(any(), any()) }
            coVerify(exactly = 0) { repository.listScheduledShifts(any(), any(), any()) }
        }

    @Test
    fun `SCHEDULE returns only active public presence with overnight dedupe and ignored manual flags`() =
        runBlocking {
            val repository = mockk<VenueStaffProfileRepository>()
            val resolver = GuestTodayStaffResolver(repository)
            val yesterday = LocalDate.of(2026, 8, 3)
            val today = yesterday.plusDays(1)
            val duplicate = profile(21L, "Две активные смены")
            val overnight = profile(22L, "Ночная смена")
            val active = profile(23L, "Текущая смена")
            val hidden = profile(24L, "Скрытый профиль", guestVisible = false)
            val unpublished = profile(25L, "Черновик", published = false)
            val disabled = profile(26L, "Отключённый профиль", disabled = true)
            coEvery { repository.listScheduledShifts(VENUE_ID, yesterday, today) } returns
                listOf(
                    scheduled(101L, duplicate, yesterday, "23:00", "02:00"),
                    scheduled(102L, duplicate, today, "00:00", "01:00", guestVisible = true, manual = true),
                    scheduled(103L, overnight, yesterday, "23:30", "02:30"),
                    scheduled(104L, active, today, "00:00", "01:00"),
                    scheduled(105L, profile(27L, "Будущая"), today, "01:00", "02:00"),
                    scheduled(106L, profile(28L, "На границе конца"), yesterday, "22:00", "00:30"),
                    scheduled(107L, profile(29L, "Отменённая"), today, "00:00", "01:00", status = "canceled"),
                    scheduled(108L, profile(30L, "Неполная"), today, null, "01:00"),
                    scheduled(109L, hidden, today, "00:00", "01:00"),
                    scheduled(110L, unpublished, today, "00:00", "01:00"),
                    scheduled(111L, disabled, today, "00:00", "01:00"),
                    scheduled(112L, profile(31L, "На границе начала"), today, "00:30", "01:30"),
                    scheduled(113L, profile(32L, "Завершённая"), today, "00:00", "01:00", status = "completed"),
                )

            val result = resolver.resolve(VENUE_ID, now, zoneId, settings(TodayStaffSource.SCHEDULE))

            assertEquals(
                listOf("Две активные смены", "Ночная смена", "Текущая смена", "На границе начала"),
                result.map { it.displayName },
            )
            assertEquals(1, result.count { it.id == duplicate.id })
            result.forEach { staff ->
                assertEquals("active", staff.shiftStatus)
                assertEquals(today.toString(), staff.shiftDate)
                assertNull(staff.startsAt)
                assertNull(staff.endsAt)
                assertNull(staff.photoRef)
                assertPublicDtoOnly(staff)
            }
            coVerify(exactly = 0) { repository.listPublicTodayStaff(any(), any()) }
        }

    @Test
    fun `SCHEDULE never falls back to MANUAL when no shift is active`() =
        runBlocking {
            val repository = mockk<VenueStaffProfileRepository>()
            val resolver = GuestTodayStaffResolver(repository)
            val yesterday = LocalDate.of(2026, 8, 3)
            val today = yesterday.plusDays(1)
            coEvery { repository.listScheduledShifts(VENUE_ID, yesterday, today) } returns
                listOf(scheduled(201L, profile(31L, "Будущая"), today, "01:00", "02:00"))
            coEvery { repository.listPublicTodayStaff(VENUE_ID, today) } returns
                listOf(
                    PublicVenueStaffToday(
                        id = 32L,
                        displayName = "Ручная публикация",
                        roleLabel = null,
                        subtype = "other",
                        photoRef = null,
                        bio = null,
                        tags = emptyList(),
                        shiftId = 202L,
                        shiftDate = today,
                        startsAt = null,
                        endsAt = null,
                        shiftStatus = "active",
                        manuallyMarkedActive = true,
                    ),
                )

            val result = resolver.resolve(VENUE_ID, now, zoneId, settings(TodayStaffSource.SCHEDULE))

            assertTrue(result.isEmpty())
            coVerify(exactly = 0) { repository.listPublicTodayStaff(any(), any()) }
        }

    private fun profile(
        id: Long,
        displayName: String,
        guestVisible: Boolean = true,
        published: Boolean = true,
        disabled: Boolean = false,
    ): VenueStaffProfile =
        VenueStaffProfile(
            id = id,
            venueId = VENUE_ID,
            linkedUserId = 70_000L + id,
            displayName = displayName,
            roleLabel = "Мастер",
            subtype = "hookah_master",
            photoRef = "private-photo-$id",
            bio = "Публичное био $id",
            tags = listOf("tag-$id"),
            isGuestVisible = guestVisible,
            createdByUserId = 80_000L + id,
            updatedByUserId = 80_100L + id,
            publishedAt = Instant.EPOCH.takeIf { published },
            disabledAt = Instant.EPOCH.takeIf { disabled },
            createdAt = Instant.EPOCH,
            updatedAt = Instant.EPOCH,
        )

    private fun scheduled(
        id: Long,
        profile: VenueStaffProfile,
        date: LocalDate,
        startsAt: String?,
        endsAt: String?,
        status: String = "scheduled",
        guestVisible: Boolean = false,
        manual: Boolean = false,
    ): VenueStaffScheduledShift =
        VenueStaffScheduledShift(
            profile = profile,
            shift =
                VenueStaffShift(
                    id = id,
                    venueId = VENUE_ID,
                    staffProfileId = profile.id,
                    shiftDate = date,
                    startsAt = startsAt?.let(LocalTime::parse),
                    endsAt = endsAt?.let(LocalTime::parse),
                    status = status,
                    isGuestVisible = guestVisible,
                    manuallyMarkedActive = manual,
                    createdByUserId = 90_000L + id,
                    updatedByUserId = 90_100L + id,
                    createdAt = Instant.EPOCH,
                    updatedAt = Instant.EPOCH,
                ),
        )

    private fun settings(
        source: TodayStaffSource,
        moduleEnabled: Boolean = true,
        guestVisible: Boolean = true,
    ): VenueStaffModuleSettings =
        VenueStaffModuleSettings(
            teamScheduleModuleEnabled = moduleEnabled,
            guestTeamVisible = guestVisible,
            todayStaffSource = source,
            updatedAt = Instant.EPOCH,
        )

    private fun assertPublicDtoOnly(dto: GuestTodayStaffDto) {
        val encoded = Json.encodeToString(dto)
        assertFalse(encoded.contains("shiftId"))
        assertFalse(encoded.contains("linkedUserId"))
        assertFalse(encoded.contains("telegram", ignoreCase = true))
        assertFalse(encoded.contains("username", ignoreCase = true))
        assertFalse(encoded.contains("membership", ignoreCase = true))
        assertFalse(encoded.contains("updatedAt"))
        assertFalse(encoded.contains("audit", ignoreCase = true))
    }

    private companion object {
        const val VENUE_ID = 10L
    }
}
