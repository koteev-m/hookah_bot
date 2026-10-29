package com.hookah.platform.backend.miniapp.venue.staff

import com.hookah.platform.backend.api.DatabaseUnavailableException
import com.hookah.platform.backend.api.ForbiddenException
import com.hookah.platform.backend.api.NotFoundException
import com.hookah.platform.backend.api.StaffPhotoActionRetiredException
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

    @Test fun `upload becomes current without consent or publication and safe DTO`() =
        runBlocking<Unit> {
            val uploaded = f.upload()
            assertNull(uploaded.candidate)
            assertTrue(uploaded.guestVisible)
            assertFalse(uploaded.canConsent)
            val id = uploaded.current!!.id
            assertEquals("NONE", uploaded.current.consent)
            assertNull(uploaded.current.consentAt)
            assertTrue(f.service.privateBytes(f.venue, f.display, 102, id).isNotEmpty())
            assertContentEquals(f.storage.objects.values.single(), f.service.guestBytes(f.venue, f.display, id))
            assertEquals(
                0,
                f.count(
                    """
                    SELECT COUNT(*) FROM staff_profile_photos WHERE consent_kind IS NOT NULL OR consent_at IS NOT
                    NULL OR consent_actor_id IS NOT NULL OR consent_policy IS NOT NULL
                    """.trimIndent(),
                ),
            )
            val dto = Json.encodeToString(uploaded)
            assertFalse(dto.contains("staff-photos/"))
            assertFalse(dto.contains("object_key"))
            assertFalse(dto.contains("cloudflare"))
            assertEquals(2, f.count("SELECT COUNT(*) FROM audit_log WHERE entity_type='staff_profile'"))
        }

    @Test fun `owner and manager manage photos on display staff manager and owner cards only in own venue`() =
        runBlocking<Unit> {
            val ownerProfile = f.profile(101)
            for (actor in listOf(101L, 102L)) {
                for (profile in listOf(f.display, f.linked, f.protected, ownerProfile)) {
                    assertTrue(f.state(profile, actor).canManage)
                    val first = f.upload(profile, actor).current!!.id
                    val next = f.upload(profile, actor).current!!.id
                    assertTrue(first != next)
                    assertNull(f.action("delete", profile, actor).current)
                }
            }
            assertFailsWith<ForbiddenException> { f.upload(f.linked, 103) }
            assertFalse(f.state(f.linked, 103).canManage)
            assertFalse(f.state(f.linked, 103).canConsent)
            assertFailsWith<ForbiddenException> { f.state(f.display, 103) }
            assertFailsWith<ForbiddenException> { f.state(f.display, 104) }
            assertFailsWith<NotFoundException> { f.service.state(f.foreignVenue, f.display, 104) }
            for (actor in listOf(103L, 104L)) {
                for (action in listOf("consent", "publish", "revoke", "hide", "delete")) {
                    assertFailsWith<ForbiddenException> {
                        f.service.mutate(f.venue, f.linked, actor, null, 0, action)
                    }
                }
                assertFailsWith<ForbiddenException> {
                    f.service.upload(f.venue, f.linked, actor, 0, UUID.randomUUID().toString(), "image/png") {
                        error("Unauthorized body must not be read")
                    }
                }
            }
        }

    @Test fun `manager photo rights do not grant protected profile edits or linkage changes`() =
        runBlocking<Unit> {
            f.upload(f.protected, 102)
            val result =
                f.profiles.updateProfile(
                    f.venue,
                    f.protected,
                    102,
                    VenueRole.MANAGER,
                    false,
                    AuditLogRepository(ds),
                ) { p ->
                    StaffProfileWrite(
                        null,
                        "Changed",
                        p.roleLabel,
                        p.subtype,
                        p.photoRef,
                        p.bio,
                        p.tags,
                        p.isGuestVisible,
                    )
                }
            assertIs<StaffProfileMutationResult.Forbidden>(result)
            assertEquals(
                1,
                f.count(
                    """
                    SELECT COUNT(*) FROM staff_profiles WHERE id=${f.protected} AND linked_user_id=105 AND
                    display_name='Synthetic'
                    """.trimIndent(),
                ),
            )
            assertEquals(
                1,
                f.count(
                    "SELECT COUNT(*) FROM venue_members WHERE venue_id=${f.venue} AND user_id=105 AND role='MANAGER'",
                ),
            )
        }

    @Test fun `legacy actions fail without writes and old candidates stay private while active photos survive`() =
        runBlocking<Unit> {
            val active = f.upload().current!!.id
            f.legacyCandidate()
            f.sql(
                """
                UPDATE staff_profile_photos SET consent_kind='OWNER_RECORDED', consent_actor_id=101,
                consent_at=CURRENT_TIMESTAMP, consent_policy='staff-photo-v1' WHERE id='$active'
                """.trimIndent(),
            )
            val legacy = f.state()
            val candidate = legacy.candidate!!.id
            val auditCount = f.count("SELECT COUNT(*) FROM audit_log")
            for (actor in listOf(101L, 102L)) {
                for (action in listOf("consent", "publish", "revoke")) {
                    assertFailsWith<StaffPhotoActionRetiredException> { f.action(action, actor = actor) }
                }
            }
            assertEquals(legacy, f.state())
            assertEquals(auditCount, f.count("SELECT COUNT(*) FROM audit_log"))
            assertTrue(f.service.guestBytes(f.venue, f.display, active).isNotEmpty())
            assertFailsWith<NotFoundException> { f.service.guestBytes(f.venue, f.display, candidate) }
            assertTrue(f.service.privateBytes(f.venue, f.display, 102, candidate).isNotEmpty())
            f.storage.failPut = true
            assertFailsWith<StaffPhotoUnavailableException> { f.upload(actor = 102) }
            assertEquals(active, f.state().current!!.id)
            assertEquals(candidate, f.state().candidate!!.id)
            f.storage.failPut = false
            val replacement = f.upload(actor = 102).current!!.id
            assertNull(f.state().candidate)
            f.service.cleanup()
            assertEquals(setOf("staff-photos/$replacement.jpg"), f.storage.objects.keys)
        }

    @Test fun `old candidate without current and hidden current never auto publish`() =
        runBlocking<Unit> {
            val request = UUID.randomUUID().toString()
            val candidate = f.legacyCandidate(request = request).candidate!!.id
            assertEquals(candidate, f.upload(request = request, revision = 0).candidate!!.id)
            assertEquals(1, f.storage.puts)
            f.sql("UPDATE staff_profile_photos SET state='HIDDEN' WHERE id='$candidate'")
            assertEquals(candidate, f.upload(request = request, revision = 0).candidate!!.id)
            assertEquals(1, f.storage.puts)
            assertNull(f.state().current)
            assertNull(f.repo.publicPhoto(f.venue, f.display))
            assertFailsWith<NotFoundException> { f.service.guestBytes(f.venue, f.display, candidate) }
            val active = f.upload().current!!.id
            f.action("hide")
            assertEquals(active, f.state().current!!.id)
            assertNull(f.repo.publicPhoto(f.venue, f.display))
            assertFailsWith<StaffPhotoActionRetiredException> { f.action("publish") }
        }

    @Test fun `manager delete works with module disabled and immediately denies both previews`() =
        runBlocking<Unit> {
            val id = f.upload(f.linked).current!!.id
            f.sql("UPDATE venue_settings SET team_schedule_module_enabled=FALSE WHERE venue_id=${f.venue}")
            assertEquals(f.linked, f.service.own(f.venue, 103).single().profileId)
            assertFailsWith<ForbiddenException> { f.action("revoke", f.linked, 103) }
            val deleted = f.action("delete", f.linked, 102)
            assertNull(deleted.current)
            assertTrue(deleted.cleanupPending)
            assertFailsWith<NotFoundException> { f.service.guestBytes(f.venue, f.linked, id) }
            assertFailsWith<NotFoundException> { f.service.privateBytes(f.venue, f.linked, 101, id) }
            assertEquals(1, f.storage.objects.size)
            f.service.cleanup()
            assertTrue(f.storage.objects.isEmpty())
        }

    @Test fun `replacement atomically becomes current then cleans up old bytes`() =
        runBlocking<Unit> {
            val old = f.upload().current!!.id
            val replacement = f.upload().current!!.id
            assertEquals(replacement, f.state().current!!.id)
            assertNull(f.state().candidate)
            assertTrue(f.service.guestBytes(f.venue, f.display, replacement).isNotEmpty())
            assertFailsWith<NotFoundException> { f.service.guestBytes(f.venue, f.display, old) }
            assertFailsWith<NotFoundException> { f.service.privateBytes(f.venue, f.display, 101, old) }
            f.service.cleanup()
            assertEquals(setOf("staff-photos/$replacement.jpg"), f.storage.objects.keys)
        }

    @Test fun `hidden unpublished and disabled cards stay private after direct upload`() =
        runBlocking<Unit> {
            for (restriction in listOf(
                "is_guest_visible=FALSE",
                "published_at=NULL",
                "disabled_at=CURRENT_TIMESTAMP",
            )) {
                f.sql("UPDATE staff_profiles SET $restriction WHERE id=${f.display}")
                val id = f.upload().current!!.id
                assertFalse(f.state().guestVisible)
                assertFailsWith<NotFoundException> { f.service.guestBytes(f.venue, f.display, id) }
                f.sql(
                    """
                    UPDATE staff_profiles SET is_guest_visible=TRUE, published_at=CURRENT_TIMESTAMP,
                    disabled_at=NULL WHERE id=${f.display}
                    """.trimIndent(),
                )
            }
        }

    @Test fun `storage write failure and timeout keep old photo and track cleanup`() =
        runBlocking<Unit> {
            val old = f.upload().current!!.id
            f.storage.failPut = true
            assertFailsWith<StaffPhotoUnavailableException> { f.upload() }
            assertEquals(old, f.state().current!!.id)
            assertTrue(f.state().cleanupPending)
            assertTrue(f.service.guestBytes(f.venue, f.display, old).isNotEmpty())
            f.service.cleanup()
            assertEquals(1, f.storage.objects.size)
        }

    @Test fun `stored PUT timeout preserves old photo and cleanup owns the ambiguous object`() =
        runBlocking<Unit> {
            val old = f.upload().current!!.id
            f.storage.afterPut = { throw StaffPhotoUnavailableException() }
            assertFailsWith<StaffPhotoUnavailableException> { f.upload(actor = 102) }
            assertEquals(old, f.state().current!!.id)
            assertEquals(2, f.storage.objects.size)
            assertTrue(f.service.guestBytes(f.venue, f.display, old).isNotEmpty())
            f.service.cleanup()
            assertEquals(setOf("staff-photos/$old.jpg"), f.storage.objects.keys)
        }

    @Test fun `actor demotion during PUT prevents activation and retains old photo`() =
        runBlocking<Unit> {
            val old = f.upload().current!!.id
            f.storage.afterPut = {
                f.sql("UPDATE venue_members SET role='STAFF' WHERE venue_id=${f.venue} AND user_id=102")
            }
            assertFailsWith<ForbiddenException> { f.upload(actor = 102) }
            assertEquals(old, f.state().current!!.id)
            f.service.cleanup()
            assertEquals(setOf("staff-photos/$old.jpg"), f.storage.objects.keys)
        }

    @Test fun `replaying a superseded or deleted successful upload cannot resurrect it`() =
        runBlocking<Unit> {
            val request = UUID.randomUUID().toString()
            val old = f.upload(request = request, revision = 0).current!!.id
            val replacement = f.upload(actor = 102).current!!.id
            assertFailsWith<StaffPhotoConflictException> { f.upload(request = request, revision = 0) }
            assertEquals(replacement, f.state().current!!.id)
            f.action("delete")
            assertFailsWith<StaffPhotoConflictException> { f.upload(request = request, revision = 0) }
            assertNull(f.state().current)
            assertFailsWith<NotFoundException> { f.service.guestBytes(f.venue, f.display, old) }
            assertEquals(2, f.storage.puts)
        }

    @Test fun `delete failure does not claim physical success and can retry`() =
        runBlocking<Unit> {
            val id = f.upload().current!!.id
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
                    val old = f.upload().current!!.id
                    val revision = f.state().revision
                    f.storage.entered = CompletableDeferred()
                    f.storage.release = CompletableDeferred()
                    val first = async { f.upload(revision = revision) }
                    f.storage.entered!!.await()
                    assertFailsWith<StaffPhotoConflictException> { f.upload(revision = revision) }
                    assertEquals(old, f.state().current!!.id)
                    assertTrue(f.service.guestBytes(f.venue, f.display, old).isNotEmpty())
                    f.storage.release!!.complete(Unit)
                    val replacement = first.await().current!!.id
                    assertTrue(old != replacement)
                    assertEquals(replacement, f.state().current!!.id)
                    assertFailsWith<NotFoundException> { f.service.guestBytes(f.venue, f.display, old) }
                    f.service.cleanup()
                    assertEquals(1, f.storage.objects.size)
                    assertEquals(2, f.count("SELECT COUNT(*) FROM staff_profile_photos"))
                }
            }
        }

    @Test fun `database finalization rollback tracks stored orphan and preserves current`() =
        runBlocking<Unit> {
            val old = f.upload().current!!.id
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

    @Test fun `visibility and deletion rechecked after slow storage read`() =
        runBlocking<Unit> {
            val id = f.upload().current!!.id
            f.storage.beforeGet = { f.visible = false }
            assertFailsWith<NotFoundException> { f.service.guestBytes(f.venue, f.display, id) }
            f.visible = true
            f.storage.beforeGet = { f.action("hide") }
            assertFailsWith<NotFoundException> { f.service.guestBytes(f.venue, f.display, id) }
            f.storage.beforeGet = {}
            val replacement = f.upload().current!!.id
            f.storage.beforeGet = { f.action("delete") }
            assertFailsWith<NotFoundException> { f.service.privateBytes(f.venue, f.display, 101, replacement) }
        }

    @Test fun `identity relink clears all photos and cannot reuse previous consent`() =
        runBlocking<Unit> {
            f.upload(f.linked)
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
                }.current,
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
            val id = f.upload().current!!.id
            f.sql("UPDATE venues SET status='DELETED' WHERE id=${f.venue}")
            assertFailsWith<NotFoundException> { f.state() }
            assertFailsWith<NotFoundException> { f.upload() }
            assertFailsWith<NotFoundException> { f.service.privateBytes(f.venue, f.display, 101, id) }
        }

    @Test
    fun `membership promotion removal and invite upgrade invalidate photos and consent`() =
        runBlocking<Unit> {
            val members = VenueStaffRepository(ds)
            f.upload(f.linked)
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
            f.upload(f.linked)
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
            f.upload(f.linked)
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
            assertIs<com.hookah.platform.backend.platform.PlatformOwnerAssignmentResult.Success>(
                platform.assignOwner(f.venue, 102, "OWNER", 101),
            )
            assertNull(f.state(protected).current)
            f.upload(protected)
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
