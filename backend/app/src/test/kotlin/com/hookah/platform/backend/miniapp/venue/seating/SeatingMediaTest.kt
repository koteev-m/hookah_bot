package com.hookah.platform.backend.miniapp.venue.seating

import com.hookah.platform.backend.api.ForbiddenException
import com.hookah.platform.backend.api.NotFoundException
import com.hookah.platform.backend.api.SeatingConflictException
import com.hookah.platform.backend.api.VenueInfoMediaInvalidException
import com.hookah.platform.backend.api.VenueInfoMediaUnavailableException
import com.hookah.platform.backend.miniapp.venue.media.FakeInfoStorage
import com.hookah.platform.backend.miniapp.venue.staff.syntheticPhoto
import kotlinx.coroutines.runBlocking
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class SeatingMediaTest {
    @Test
    fun `failed replacement corrupt input and foreign plan cannot change committed layout`() =
        runBlocking<Unit> {
            h2Seating().use { ds ->
                val (venue, _) = seedSeating(ds)
                val (other, _) = seedSeating(ds)
                val repo = SeatingRepository(ds, clock = seatingClock)
                val storage = FakeInfoStorage()
                val service = HallPlanService(repo, storage)
                val hall = repo.command(venue, OWNER, cmd("HALL").copy(name = "Main")).id
                val request = UUID.randomUUID().toString()
                val uploaded = service.upload(venue, hall, OWNER, request, "image/png") { syntheticPhoto() }
                assertEquals(uploaded, service.upload(venue, hall, OWNER, request, "image/png") { syntheticPhoto() })
                repo.command(
                    venue,
                    OWNER,
                    cmd("PLAN").copy(id = hall, revision = 0, planId = uploaded.id, confirmPlanReplacement = true),
                )
                val before = repo.state(venue, OWNER)
                assertFailsWith<VenueInfoMediaInvalidException> {
                    service.upload(
                        venue,
                        hall,
                        OWNER,
                        UUID.randomUUID().toString(),
                        "image/png",
                    ) { "MZsynthetic".toByteArray() }
                }
                assertFailsWith<VenueInfoMediaInvalidException> {
                    service.upload(
                        venue,
                        hall,
                        OWNER,
                        UUID.randomUUID().toString(),
                        "application/pdf",
                    ) { "%PDF-synthetic".toByteArray() }
                }
                storage.failPut = true
                val retry = UUID.randomUUID().toString()
                assertFailsWith<VenueInfoMediaUnavailableException> {
                    service.upload(venue, hall, OWNER, retry, "image/png") { syntheticPhoto() }
                }
                assertEquals(before, repo.state(venue, OWNER))
                storage.failPut = false
                val replacement = service.upload(venue, hall, OWNER, retry, "image/png") { syntheticPhoto() }
                assertFailsWith<SeatingConflictException> {
                    service.upload(venue, hall, OWNER, retry, "image/png") { syntheticPhoto(width = 81) }
                }
                assertFailsWith<NotFoundException> { service.bytes(other, hall, uploaded.id, OWNER) }
                assertFailsWith<NotFoundException> { service.bytes(venue, hall, replacement.id, null) }
                assertEquals(uploaded.id, repo.state(venue, OWNER).halls.single().planId)
            }
        }

    @Test
    fun `access revoked while storage writes prevents ready state and applying replacement`() =
        runBlocking<Unit> {
            h2Seating().use { ds ->
                val (venue, _) = seedSeating(ds)
                val repo = SeatingRepository(ds, clock = seatingClock)
                val storage = FakeInfoStorage()
                val service = HallPlanService(repo, storage)
                val hall = repo.command(venue, OWNER, cmd("HALL").copy(name = "Main")).id
                storage.afterPut = {
                    ds.connection.use {
                            c ->
                        c.write("DELETE FROM venue_members WHERE venue_id = ? AND user_id = ?", venue, OWNER)
                    }
                }
                assertFailsWith<ForbiddenException> {
                    service.upload(venue, hall, OWNER, UUID.randomUUID().toString(), "image/png") { syntheticPhoto() }
                }
                ds.connection.use { c ->
                    assertEquals(
                        0,
                        c.rows("SELECT COUNT(*) FROM hall_plan_media WHERE status = 'READY'") { it.getInt(1) }.single(),
                    )
                    assertEquals(
                        0,
                        c.rows("SELECT COUNT(*) FROM venue_halls WHERE plan_id IS NOT NULL") { it.getInt(1) }.single(),
                    )
                }
                assertFailsWith<ForbiddenException> {
                    service.bytes(
                        venue,
                        hall,
                        storage.objects.keys.single().substringAfter('/').substringBeforeLast('.'),
                        OWNER,
                    )
                }
            }
        }
}
