package com.hookah.platform.backend.miniapp.venue.staff

import com.hookah.platform.backend.api.DatabaseUnavailableException
import com.hookah.platform.backend.api.ForbiddenException
import com.hookah.platform.backend.api.NotFoundException
import com.hookah.platform.backend.api.StaffPhotoConflictException
import com.hookah.platform.backend.api.StaffPhotoUnavailableException
import com.hookah.platform.backend.miniapp.venue.AuditLogRepository
import com.hookah.platform.backend.miniapp.venue.VenueRole
import com.zaxxer.hikari.HikariDataSource
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

abstract class StaffPhotoLifecycleContract(private val postgres: Boolean) {
    private lateinit var ds: HikariDataSource
    internal lateinit var f: PhotoFixture

    @BeforeEach fun setup() {
        ds = photoTestDataSource(postgres)
        f = PhotoFixture(ds)
    }

    @AfterEach fun close() {
        if (::ds.isInitialized) ds.close()
    }

    @Test fun `upload is private until separate consent and publication and safe DTO`() =
        runBlocking<Unit> {
            val pending = f.upload()
            assertNull(pending.current)
            assertFalse(pending.guestVisible)
            val id = pending.candidate!!.id
            assertFailsWith<NotFoundException> { f.service.guestBytes(f.venue, f.display, id) }
            assertFailsWith<ForbiddenException> { f.action("publish") }
            assertTrue(f.service.privateBytes(f.venue, f.display, 102, id).isNotEmpty())
            f.action("consent")
            assertNull(f.repo.publicPhoto(f.venue, f.display))
            val published = f.action("publish")
            assertTrue(published.guestVisible)
            assertContentEquals(f.storage.objects.values.single(), f.service.guestBytes(f.venue, f.display, id))
            val dto = Json.encodeToString(published)
            assertFalse(dto.contains("staff-photos/"))
            assertFalse(dto.contains("object_key"))
            assertFalse(dto.contains("cloudflare"))
            assertEquals(4, f.count("SELECT COUNT(*) FROM audit_log WHERE entity_type='staff_profile'"))
        }

    @Test fun `manager limited to manageable profiles and no consent substitution`() =
        runBlocking<Unit> {
            f.upload(actor = 102)
            assertFailsWith<ForbiddenException> { f.action("consent", actor = 102) }
            f.upload(f.linked, 102)
            assertFailsWith<ForbiddenException> { f.action("consent", f.linked, 101) }
            assertFailsWith<ForbiddenException> { f.upload(f.protected, 102) }
            assertFailsWith<ForbiddenException> { f.state(f.protected, 102) }
            assertFailsWith<ForbiddenException> { f.upload(f.linked, 103) }
            assertFailsWith<ForbiddenException> { f.state(f.display, 103) }
            assertFailsWith<ForbiddenException> { f.state(f.display, 104) }
            assertFailsWith<NotFoundException> { f.service.state(f.foreignVenue, f.display, 104) }
            f.action("consent", f.linked, 103)
            assertFailsWith<ForbiddenException> { f.action("publish", f.linked, 103) }
            assertTrue(f.action("publish", f.linked, 102).guestVisible)
        }

    @Test fun `self revoke remains available when module disabled and immediately denies both previews`() =
        runBlocking<Unit> {
            val id = f.publish(f.linked).current!!.id
            f.sql(
                """
                UPDATE venue_settings SET team_schedule_module_enabled=FALSE WHERE venue_id=${f.venue}
                """.trimIndent(),
            )
            assertEquals(f.linked, f.service.own(f.venue, 103).single().profileId)
            val revoked = f.action("revoke", f.linked, 103)
            assertNull(revoked.current)
            assertTrue(revoked.cleanupPending)
            assertFailsWith<NotFoundException> { f.service.guestBytes(f.venue, f.linked, id) }
            assertFailsWith<NotFoundException> { f.service.privateBytes(f.venue, f.linked, 101, id) }
            assertEquals(1, f.storage.objects.size)
            f.service.cleanup()
            assertTrue(f.storage.objects.isEmpty())
        }

    @Test fun `replacement keeps current until consent and publish then deletes old`() =
        runBlocking<Unit> {
            val old = f.publish().current!!.id
            val replacement = f.upload().candidate!!.id
            assertEquals(old, f.state().current!!.id)
            assertFailsWith<NotFoundException> { f.service.guestBytes(f.venue, f.display, replacement) }
            f.action("consent")
            val state = f.action("publish")
            assertEquals(replacement, state.current!!.id)
            assertNull(state.candidate)
            assertFailsWith<NotFoundException> { f.service.guestBytes(f.venue, f.display, old) }
            f.service.cleanup()
            assertEquals(setOf("staff-photos/$replacement.jpg"), f.storage.objects.keys)
        }

    @Test fun `storage write failure and timeout keep old photo and track cleanup`() =
        runBlocking<Unit> {
            val old = f.publish().current!!.id
            f.storage.failPut = true
            assertFailsWith<StaffPhotoUnavailableException> { f.upload() }
            assertEquals(old, f.state().current!!.id)
            assertTrue(f.state().cleanupPending)
            assertTrue(f.service.guestBytes(f.venue, f.display, old).isNotEmpty())
            f.service.cleanup()
            assertEquals(1, f.storage.objects.size)
        }

    @Test fun `delete failure does not claim physical success and can retry`() =
        runBlocking<Unit> {
            val id = f.publish().current!!.id
            f.action("delete")
            f.storage.failDelete = true
            f.service.cleanup()
            assertTrue(f.state().cleanupPending)
            assertEquals(1, f.storage.objects.size)
            assertEquals(0, f.count("SELECT COUNT(*) FROM staff_profile_photos WHERE state='DELETED'"))
            assertFailsWith<NotFoundException> { f.service.guestBytes(f.venue, f.display, id) }
            f.clock.now = f.clock.now.plusSeconds(61)
            f.storage.failDelete = false
            f.service.cleanup()
            assertTrue(f.storage.objects.isEmpty())
            assertFalse(f.state().cleanupPending)
        }

    @Test fun `lost response replay is idempotent and mismatched body fails`() =
        runBlocking<Unit> {
            val request = UUID.randomUUID().toString()
            val first = f.upload(request = request, revision = 0)
            val again = f.upload(request = request, revision = 0)
            assertEquals(first, again)
            assertEquals(1, f.storage.puts)
            assertFailsWith<StaffPhotoConflictException> {
                f.upload(request = request, revision = 0, bytes = syntheticPhoto(width = 30))
            }
            assertEquals(1, f.storage.puts)
        }

    @Test fun `concurrent duplicate and delete during upload cannot resurrect photo`() =
        runBlocking<Unit> {
            withTimeout(15_000) {
                coroutineScope {
                    f.storage.entered = CompletableDeferred()
                    f.storage.release = CompletableDeferred()
                    val request = UUID.randomUUID().toString()
                    val upload = async { runCatching { f.upload(request = request, revision = 0) } }
                    f.storage.entered!!.await()
                    assertFailsWith<StaffPhotoConflictException> { f.upload(request = request, revision = 0) }
                    f.action("delete")
                    f.service.cleanup() // even cleanup before the late PUT must retain the durable tombstone
                    f.storage.release!!.complete(Unit)
                    assertTrue(upload.await().exceptionOrNull() is StaffPhotoConflictException)
                    assertNull(f.state().candidate)
                    assertNull(f.state().current)
                    f.service.cleanup()
                    assertTrue(f.storage.objects.isEmpty())
                }
            }
        }

    @Test fun `simultaneous replacement has one winner and no untracked objects`() =
        runBlocking<Unit> {
            withTimeout(15_000) {
                coroutineScope {
                    val old = f.publish().current!!.id
                    val revision = f.state().revision
                    f.storage.entered = CompletableDeferred()
                    f.storage.release = CompletableDeferred()
                    val first = async { f.upload(revision = revision) }
                    f.storage.entered!!.await()
                    assertFailsWith<StaffPhotoConflictException> { f.upload(revision = revision) }
                    f.storage.release!!.complete(Unit)
                    assertNotNull(first.await().candidate)
                    assertEquals(old, f.state().current!!.id)
                    assertEquals(2, f.count("SELECT COUNT(*) FROM staff_profile_photos"))
                }
            }
        }

    @Test fun `database finalization rollback tracks stored orphan and preserves current`() =
        runBlocking<Unit> {
            val old = f.publish().current!!.id
            val lastAudit = f.count("SELECT MAX(id) FROM audit_log")
            f.storage.afterPut = {
                f.sql(
                    """
                    ALTER TABLE audit_log ADD CONSTRAINT test_photo_failure CHECK (action <>
                    'STAFF_PHOTO_UPLOADED' OR id <= $lastAudit)
                    """.trimIndent(),
                )
            }
            assertFailsWith<DatabaseUnavailableException> { f.upload() }
            assertEquals(old, f.state().current!!.id)
            assertNull(f.state().candidate)
            assertEquals(2, f.storage.objects.size)
            f.service.cleanup()
            assertEquals(1, f.storage.objects.size)
            assertEquals(1, f.count("SELECT COUNT(*) FROM audit_log WHERE action='STAFF_PHOTO_UPLOADED'"))
        }

    @Test fun `expired reservation and profile deletion retain cleanup ownership`() =
        runBlocking<Unit> {
            val image = StaffPhotoImage.normalize(syntheticPhoto(), "image/png")
            val reserved = f.repo.reserve(f.venue, f.display, 101, 0, UUID.randomUUID().toString(), "test", image)
            f.storage.put(reserved.photo.key, image.bytes)
            f.clock.now = f.clock.now.plusSeconds(601)
            f.service.cleanup()
            assertTrue(f.storage.objects.isEmpty())
            assertFalse(f.state().uploading)
            f.upload(f.linked)
            f.sql("DELETE FROM staff_profiles WHERE id=${f.linked}")
            f.service.cleanup()
            assertTrue(f.storage.objects.isEmpty())
            assertEquals(2, f.count("SELECT COUNT(*) FROM staff_profile_photos WHERE state='DELETED'"))
        }

    @Test fun `visibility and consent rechecked after slow storage read`() =
        runBlocking<Unit> {
            val id = f.publish().current!!.id
            f.storage.beforeGet = { f.visible = false }
            assertFailsWith<NotFoundException> { f.service.guestBytes(f.venue, f.display, id) }
            f.visible = true
            f.storage.beforeGet = { f.action("hide") }
            assertFailsWith<NotFoundException> { f.service.guestBytes(f.venue, f.display, id) }
            f.storage.beforeGet = {}
            f.action("publish")
            f.storage.beforeGet = { f.action("delete") }
            assertFailsWith<NotFoundException> { f.service.privateBytes(f.venue, f.display, 101, id) }
        }

    @Test fun `identity relink clears all photos and cannot reuse previous consent`() =
        runBlocking<Unit> {
            f.publish(f.linked)
            val result =
                f.profiles.updateProfile(f.venue, f.linked, 101, VenueRole.OWNER, false, AuditLogRepository(ds)) { p ->
                    StaffProfileWrite(
                        null,
                        p.displayName,
                        p.roleLabel,
                        p.subtype,
                        p.photoRef,
                        p.bio,
                        p.tags,
                        p.isGuestVisible,
                    )
                }
            assertTrue(result is StaffProfileMutationResult.Success)
            assertNull(f.state(f.linked).current)
            assertNull(f.repo.publicPhoto(f.venue, f.linked))
            f.service.cleanup()
            assertTrue(f.storage.objects.isEmpty())
        }

    @Test fun `slow body deadline releases upload slot without reservation`() =
        runBlocking<Unit> {
            val service = StaffPhotoService(f.repo, f.storage, { emptySet() }, uploadReadTimeoutMillis = 30)
            repeat(3) {
                assertFailsWith<StaffPhotoUnavailableException> {
                    service.upload(f.venue, f.display, 101, 0, UUID.randomUUID().toString(), "image/png") {
                        kotlinx.coroutines.delay(500)
                        syntheticPhoto()
                    }
                }
            }
            assertEquals(0, f.count("SELECT COUNT(*) FROM staff_profile_photos"))
            assertNotNull(
                service.upload(f.venue, f.display, 101, 0, UUID.randomUUID().toString(), "image/png") {
                    syntheticPhoto()
                }.candidate,
            )
        }

    @Test fun `claimed expired upload cannot be finalized or resurrected`() =
        runBlocking<Unit> {
            val image = StaffPhotoImage.normalize(syntheticPhoto(), "image/png")
            val reservation = f.repo.reserve(f.venue, f.display, 101, 0, UUID.randomUUID().toString(), "input", image)
            f.storage.put(reservation.photo.key, image.bytes)
            f.clock.now = f.clock.now.plusSeconds(601)
            val claimed = f.repo.claimCleanup().single()
            assertFailsWith<StaffPhotoConflictException> { f.repo.finalizeUpload(reservation.photo) }
            f.storage.delete(claimed.key)
            f.repo.completeCleanup(claimed.id)
            assertNull(f.state().candidate)
            assertNull(f.state().current)
            assertTrue(f.storage.objects.isEmpty())
        }

    @Test fun `deleted venue denies management and private preview`() =
        runBlocking<Unit> {
            val id = f.publish().current!!.id
            f.sql("UPDATE venues SET status='DELETED' WHERE id=${f.venue}")
            assertFailsWith<NotFoundException> { f.state() }
            assertFailsWith<NotFoundException> { f.upload() }
            assertFailsWith<NotFoundException> { f.service.privateBytes(f.venue, f.display, 101, id) }
        }

    @Test
    fun `membership promotion removal and invite upgrade invalidate photos and consent`() =
        runBlocking<Unit> {
            val members = VenueStaffRepository(ds)
            f.publish(f.linked)
            assertIs<VenueStaffUpdateResult.Success>(
                members.updateRoleWithOwnerGuard(
                    f.venue,
                    101,
                    103,
                    VenueRole.MANAGER,
                    VenueStaffMutationSource.VENUE_MINI_APP,
                ),
            )
            assertNull(f.state(f.linked).current)
            members.updateRoleWithOwnerGuard(
                f.venue,
                101,
                103,
                VenueRole.STAFF,
                VenueStaffMutationSource.VENUE_MINI_APP,
            )
            f.publish(f.linked)
            assertIs<VenueStaffRemoveResult.Success>(
                members.removeMemberWithOwnerGuard(
                    f.venue,
                    101,
                    103,
                    VenueStaffMutationSource.VENUE_MINI_APP,
                ),
            )
            assertNull(f.repo.publicPhoto(f.venue, f.linked))
            f.sql("INSERT INTO venue_members (venue_id,user_id,role) VALUES (${f.venue},103,'STAFF')")
            f.publish(f.linked)
            val invites = StaffInviteRepository(ds, "synthetic-test-pepper")
            val invite = checkNotNull(invites.createInvite(f.venue, 101, "MANAGER", 3600, AuditLogRepository(ds)))
            assertIs<StaffInviteAcceptResult.Success>(
                invites.acceptInvite(invite.code, 103, AuditLogRepository(ds)) { _, _, _, _ ->
                    error("Existing membership must be promoted rather than inserted")
                },
            )
            assertNull(f.state(f.linked).current)
            f.service.cleanup()
            assertTrue(f.storage.objects.isEmpty())
        }

    @Test
    fun `empty state binds selected revision before relink and platform role changes invalidate`() =
        runBlocking<Unit> {
            val before = f.state(f.linked)
            assertEquals(0, before.revision)
            assertIs<StaffProfileMutationResult.Success>(
                f.profiles.updateProfile(
                    f.venue,
                    f.linked,
                    101,
                    VenueRole.OWNER,
                    false,
                    AuditLogRepository(ds),
                ) { p ->
                    StaffProfileWrite(
                        null,
                        p.displayName,
                        p.roleLabel,
                        p.subtype,
                        p.photoRef,
                        p.bio,
                        p.tags,
                        p.isGuestVisible,
                    )
                },
            )
            assertFailsWith<StaffPhotoConflictException> { f.upload(f.linked, revision = before.revision) }
            val platform = com.hookah.platform.backend.platform.PlatformVenueMemberRepository(ds)
            val protected = f.profile(102)
            f.upload(protected)
            f.action("consent", protected, 102)
            f.action("publish", protected)
            assertIs<com.hookah.platform.backend.platform.PlatformOwnerAssignmentResult.Success>(
                platform.assignOwner(f.venue, 102, "OWNER", 101),
            )
            assertNull(f.state(protected).current)
            f.upload(protected)
            f.action("consent", protected, 102)
            f.action("publish", protected)
            assertIs<com.hookah.platform.backend.platform.PlatformOwnerRevokeResult.Success>(
                platform.revokeOwner(f.venue, 102),
            )
            assertNull(f.repo.publicPhoto(f.venue, protected))
        }

    @Test fun `disabled storage leaves other state usable and cannot fake upload success`() =
        runBlocking<Unit> {
            f.storage.available = false
            assertFalse(f.state().enabled)
            assertFailsWith<StaffPhotoUnavailableException> { f.upload() }
            assertEquals(0, f.count("SELECT COUNT(*) FROM staff_profile_photos"))
        }
}

class StaffPhotoLifecycleH2Test : StaffPhotoLifecycleContract(false)

class StaffPhotoLifecyclePostgresTest : StaffPhotoLifecycleContract(true)
