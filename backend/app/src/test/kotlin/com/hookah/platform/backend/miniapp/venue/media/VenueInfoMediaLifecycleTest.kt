package com.hookah.platform.backend.miniapp.venue.media

import com.hookah.platform.backend.api.ForbiddenException
import com.hookah.platform.backend.api.VenueInfoMediaConflictException
import com.hookah.platform.backend.api.VenueInfoMediaInvalidException
import com.hookah.platform.backend.api.VenueInfoMediaUnavailableException
import com.hookah.platform.backend.miniapp.venue.AuditLogRepository
import com.hookah.platform.backend.miniapp.venue.staff.PhotoFixture
import com.hookah.platform.backend.miniapp.venue.staff.photoTestDataSource
import com.hookah.platform.backend.miniapp.venue.staff.syntheticPhoto
import com.hookah.platform.backend.telegram.db.VenueInfoSectionMediaRepository
import com.hookah.platform.backend.telegram.db.VenueInfoSectionsRepository
import com.lowagie.text.Document
import com.lowagie.text.Paragraph
import com.lowagie.text.pdf.PdfWriter
import com.zaxxer.hikari.HikariDataSource
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

internal class FakeInfoStorage : VenueInfoMediaStorage {
    override var available = true
    val objects = ConcurrentHashMap<String, ByteArray>()
    var puts = 0
    var failPut = false
    var failDelete = false
    var pretendDelete = false
    var afterPut: suspend () -> Unit = {}
    var beforeGet: suspend () -> Unit = {}
    var entered: CompletableDeferred<Unit>? = null
    var release: CompletableDeferred<Unit>? = null

    override suspend fun put(
        key: String,
        file: ValidatedVenueInfoMedia,
    ) {
        puts++
        entered?.complete(Unit)
        release?.await()
        if (failPut) throw VenueInfoMediaUnavailableException()
        objects[key] = file.bytes
        afterPut()
    }

    override suspend fun get(key: String): ByteArray? {
        val result = objects[key]
        beforeGet()
        return result
    }

    override suspend fun delete(key: String) {
        if (failDelete) throw VenueInfoMediaUnavailableException()
        if (!pretendDelete) objects.remove(key)
    }

    override suspend fun exists(key: String) = objects.containsKey(key)
}

// Only a persistence/route transport fixture. Actual OpenPDF admission is tested in Linux Docker.
internal fun fixturePdfAdmission(bytes: ByteArray) {
    VenueInfoPdfPreflight.check(bytes)
    if (String(bytes, Charsets.ISO_8859_1).contains("/JavaScript")) throw VenueInfoMediaInvalidException()
}

internal fun syntheticPdf(active: Boolean = false): ByteArray {
    val out = ByteArrayOutputStream()
    val doc = Document()
    val writer = PdfWriter.getInstance(doc, out)
    doc.open()
    doc.add(Paragraph("Synthetic venue information"))
    if (active) writer.addJavaScript("app.alert('test')")
    doc.close()
    return out.toByteArray()
}

internal class MediaFixture(
    val ds: HikariDataSource,
    val storage: FakeInfoStorage = FakeInfoStorage(),
) : AutoCloseable {
    val seed = PhotoFixture(ds)
    val clock = seed.clock
    val sections = VenueInfoSectionsRepository(ds)
    val legacy = VenueInfoSectionMediaRepository(ds)
    val repository = VenueInfoMediaRepository(ds, AuditLogRepository(ds), clock)
    val service = VenueInfoMediaService(repository, sections, storage, ::fixturePdfAdmission)
    val venue get() = seed.venue
    var section = 0L

    suspend fun init(): MediaFixture {
        sections.ensureDefaultSections(venue)
        section = sections.listSections(venue).first().id
        return this
    }

    suspend fun state(actor: Long = 101) = service.state(venue, actor)

    suspend fun current() = state().sections.single { it.id == section }

    suspend fun upload(
        bytes: ByteArray = syntheticPhoto(),
        mime: String = "image/png",
        actor: Long = 101,
        revision: Long? = null,
        request: String = UUID.randomUUID().toString(),
        replace: String? = null,
    ) = service.upload(
        venue,
        section,
        actor,
        revision ?: current().revision,
        request,
        mime,
        replace,
    ) {
        bytes
    }

    suspend fun action(
        id: String?,
        action: String,
        revision: Long? = null,
    ) = service.mutate(
        venue,
        section,
        101,
        revision ?: current().revision,
        id,
        action,
    )

    fun sql(query: String) = seed.sql(query)

    fun count(query: String) = seed.count(query)

    override fun close() = ds.close()
}

open class VenueInfoMediaLifecycleH2Test {
    protected open fun source() = photoTestDataSource(false)

    private fun check(block: suspend (MediaFixture) -> Unit) =
        runBlocking {
            MediaFixture(source()).use { block(it.init()) }
        }

    @Test fun `jpeg png pdf append under one ledger and preserve Telegram identity and order`() =
        check { f ->
            f.legacy.addMediaAttachment(f.section, "image", "old-telegram")
            val legacy = f.legacy.listBySectionId(f.section).single()
            f.upload(actor = 102)
            f.upload(syntheticPhoto("jpeg"), "image/jpeg")
            f.upload(syntheticPdf(), "application/pdf")
            val rows = f.legacy.listBySectionId(f.section)
            assertEquals(4, rows.size)
            assertEquals(legacy, rows.first())
            assertEquals(listOf(0, 1, 2, 3), rows.map { it.sortOrder })
            assertTrue(
                rows.drop(1).all {
                    UUID.fromString(it.publicId).toString() == it.publicId &&
                        it.telegramFileId.isEmpty() && it.sourceType == "OBJECT_STORAGE"
                },
            )
            assertEquals(3, f.storage.puts)
            assertEquals("application/pdf", f.service.bytes(rows.last(), null)?.contentType.toString())
        }

    @Test fun `staff foreign and foreign section cannot reserve or reach storage`() =
        check { f ->
            for (actor in listOf(103L, 104L)) {
                assertFailsWith<ForbiddenException> { f.state(actor) }
                assertFailsWith<ForbiddenException> { f.sections.ensureDefaultSections(f.venue, actor) }
                assertFailsWith<ForbiddenException> { f.upload(actor = actor) }
            }
            f.sections.ensureDefaultSections(f.seed.foreignVenue)
            val section = f.sections.listSections(f.seed.foreignVenue).first().id
            assertFails {
                f.service.upload(
                    f.venue,
                    section,
                    101,
                    0,
                    UUID.randomUUID().toString(),
                    "image/png",
                    null,
                ) { syntheticPhoto() }
            }
            assertEquals(0, f.storage.puts)
        }

    @Test fun `lost response retry is idempotent and changed inputs cannot reuse key`() =
        check { f ->
            val request = UUID.randomUUID().toString()
            val revision = f.current().revision
            val bytes = syntheticPhoto()
            f.upload(bytes, revision = revision, request = request)
            f.upload(bytes, revision = revision, request = request)
            assertEquals(1, f.storage.puts)
            assertEquals(1, f.current().media.size)
            assertFailsWith<VenueInfoMediaConflictException> {
                f.upload(syntheticPhoto(width = 81), revision = revision, request = request)
            }
            assertEquals(1, f.storage.puts)
        }

    @Test fun `replace is atomic keeps position and visibility and failure preserves old attachment`() =
        check { f ->
            f.legacy.addMediaAttachment(f.section, "pdf", "legacy-pdf")
            val id = f.current().media.single().id
            f.action(id, "hide")
            f.storage.failPut = true
            assertFailsWith<VenueInfoMediaUnavailableException> { f.upload(replace = id) }
            assertEquals(id, f.current().media.single().id)
            f.storage.failPut = false
            f.upload(syntheticPdf(), "application/pdf", replace = id)
            val row = f.current().media.single()
            assertNotEquals(id, row.id)
            assertEquals(0, row.sortOrder)
            assertFalse(row.visible)
            assertEquals(
                1L,
                f.count("SELECT COUNT(*) FROM venue_info_section_media WHERE telegram_file_id='legacy-pdf'"),
            )
            f.action(row.id, "show")
            assertEquals(1, f.legacy.listBySectionId(f.section).size)
        }

    @Test fun `attachment and section visibility are independent and delete retry does not duplicate writes`() =
        check { f ->
            f.upload()
            val id = f.current().media.single().id
            val hideRevision = f.current().revision
            f.action(id, "hide", hideRevision)
            f.action(id, "hide", hideRevision)
            assertEquals(hideRevision + 1, f.current().revision)
            assertTrue(f.legacy.listBySectionId(f.section).isEmpty())
            f.action(id, "show")
            val sectionRevision = f.current().revision
            f.action(null, "hide", sectionRevision)
            f.action(null, "hide", sectionRevision)
            assertEquals(sectionRevision + 1, f.current().revision)
            assertFalse(f.sections.findSectionById(f.venue, f.section)!!.isVisible)
            f.action(null, "show")
            val revision = f.current().revision
            f.action(id, "delete", revision)
            f.action(id, "delete", revision)
            assertTrue(f.current().media.isEmpty())
            assertEquals(1, f.storage.objects.size)
            f.service.cleanup()
            assertEquals(1, f.storage.objects.size)
            f.clock.now = f.clock.now.plusSeconds(7 * 86400 + 1)
            f.storage.failDelete = true
            f.service.cleanup()
            assertEquals(0L, f.count("SELECT COUNT(*) FROM venue_info_media_cleanup WHERE deleted_at IS NOT NULL"))
            f.storage.failDelete = false
            f.clock.now = f.clock.now.plusSeconds(61)
            f.service.cleanup()
            assertTrue(f.storage.objects.isEmpty())
            assertEquals(1L, f.count("SELECT COUNT(*) FROM venue_info_media_cleanup WHERE deleted_at IS NOT NULL"))
        }

    @Test fun `head must confirm absence and daily sweep catches exceptionally late put`() =
        check { f ->
            f.upload()
            val key = f.storage.objects.keys.single()
            val bytes = f.storage.objects[key]!!
            f.action(f.current().media.single().id, "delete")
            f.clock.now = f.clock.now.plusSeconds(7 * 86400 + 1)
            f.storage.pretendDelete = true
            f.service.cleanup()
            assertEquals(0L, f.count("SELECT COUNT(*) FROM venue_info_media_cleanup WHERE deleted_at IS NOT NULL"))
            f.storage.pretendDelete = false
            f.clock.now = f.clock.now.plusSeconds(61)
            f.service.cleanup()
            f.storage.objects[key] = bytes
            f.clock.now = f.clock.now.plusSeconds(86401)
            f.service.cleanup()
            assertTrue(f.storage.objects.isEmpty())
        }

    @Test fun `cleanup journal survives a cascading section deletion`() =
        check { f ->
            f.upload()
            f.sql("DELETE FROM venue_info_sections WHERE id=${f.section}")
            assertEquals(1, f.storage.objects.size)
            f.service.cleanup()
            assertTrue(f.storage.objects.isEmpty())
        }

    @Test fun `membership revocation during put prevents activation and leaves durable cleanup`() =
        check { f ->
            // H2 rounds persisted timestamps to microseconds; an immediate cleanup must not be scheduled in the future.
            f.clock.now = java.time.Instant.parse("2030-01-01T00:00:00.123456789Z")
            f.storage.afterPut = { f.sql("DELETE FROM venue_members WHERE venue_id=${f.venue} AND user_id=101") }
            assertFailsWith<ForbiddenException> { f.upload() }
            assertEquals(0L, f.count("SELECT COUNT(*) FROM venue_info_section_media WHERE status='READY'"))
            assertEquals(1L, f.count("SELECT COUNT(*) FROM venue_info_section_media WHERE status='FAILED'"))
            assertEquals(1L, f.count("SELECT COUNT(*) FROM venue_info_media_cleanup WHERE due_at IS NOT NULL"))
            assertEquals(1, f.storage.objects.size)
            f.service.cleanup()
            assertTrue(f.storage.objects.isEmpty())
        }

    @Test fun `stale revision and ten attachment limit prevent storage writes`() =
        check { f ->
            repeat(10) { f.legacy.addMediaAttachment(f.section, "image", "legacy-$it") }
            assertFailsWith<VenueInfoMediaInvalidException> { f.upload() }
            assertEquals(0, f.storage.puts)
            f.action(f.current().media.first().id, "delete")
            assertFailsWith<VenueInfoMediaConflictException> { f.upload(revision = 0) }
            f.upload()
            assertEquals(10, f.current().media.size)
        }

    @Test fun `in flight duplicate and expired lease race cannot publish swept bytes`() =
        check { f ->
            coroutineScope {
                f.storage.entered = CompletableDeferred()
                f.storage.release = CompletableDeferred()
                val revision = f.current().revision
                val request = UUID.randomUUID().toString()
                val work = async { runCatching { f.upload(revision = revision, request = request) } }
                f.storage.entered!!.await()
                assertFailsWith<VenueInfoMediaConflictException> { f.upload(revision = revision, request = request) }
                f.clock.now = f.clock.now.plusSeconds(601)
                f.service.cleanup()
                f.storage.release!!.complete(Unit)
                assertTrue(work.await().exceptionOrNull() is VenueInfoMediaConflictException)
                f.service.cleanup()
                assertTrue(f.storage.objects.isEmpty())
                assertTrue(f.current().media.isEmpty())
            }
        }

    @Test fun `terminal failure is scoped and explicit new operation preserves old media`() =
        check { f ->
            f.legacy.addMediaAttachment(f.section, "image", "keep-legacy")
            val old = f.current().media.single().id
            val request = UUID.randomUUID().toString()
            assertEquals("UNKNOWN", f.repository.operation(f.venue, f.section, 101, request).status)
            val revision = f.current().revision
            f.storage.failPut = true
            assertFailsWith<VenueInfoMediaUnavailableException> {
                f.upload(
                    request = request,
                    revision = revision,
                    replace = old,
                )
            }
            assertEquals("FAILED", f.repository.operation(f.venue, f.section, 101, request).status)
            assertEquals("UNKNOWN", f.repository.operation(f.venue, f.section, 102, request).status)
            assertFailsWith<ForbiddenException> { f.repository.operation(f.venue, f.section, 103, request) }
            f.storage.failPut = false
            assertFailsWith<VenueInfoMediaConflictException> {
                f.upload(
                    request = request,
                    revision = revision,
                    replace = old,
                )
            }
            assertEquals(old, f.current().media.single().id)
            val next = UUID.randomUUID().toString()
            f.upload(request = next, replace = old)
            assertEquals("SUCCESS", f.repository.operation(f.venue, f.section, 101, next).status)
            assertNotEquals(old, f.current().media.single().id)
            assertEquals(
                1L,
                f.count("SELECT COUNT(*) FROM venue_info_section_media WHERE telegram_file_id='keep-legacy'"),
            )
        }

    @Test fun `disabled storage denies uploads while legacy list and deletion work`() =
        check { f ->
            f.storage.available = false
            f.legacy.addMediaAttachment(f.section, "pdf", "old")
            assertFalse(f.state().enabled)
            assertFailsWith<VenueInfoMediaUnavailableException> { f.upload() }
            f.action(f.current().media.single().id, "delete")
            assertTrue(f.current().media.isEmpty())
        }
}

class VenueInfoMediaLifecyclePostgresTest : VenueInfoMediaLifecycleH2Test() {
    override fun source() = photoTestDataSource(true)

    private fun MediaFixture.backupInventory(): String {
        val relative = "deploy/production/media-inventory.sql"
        val file = listOf(Path.of(relative), Path.of("../..", relative)).first(Files::isRegularFile)
        return ds.connection.use { c ->
            c.createStatement().use { s ->
                s.executeQuery(Files.readString(file)).use { rows ->
                    check(rows.next())
                    rows.getString(1)
                }
            }
        }
    }

    @Test fun `production recovery filters hall section and venue access changes independently`() =
        runBlocking {
            MediaFixture(source()).use { f ->
                f.init()
                f.upload()
                val plan = UUID.randomUUID().toString()
                val hall =
                    f.seed.insert("INSERT INTO venue_halls (venue_id,name,plan_id) VALUES (${f.venue},'Hall','$plan')")
                f.sql(
                    """
                    INSERT INTO venue_subscriptions (venue_id,status) VALUES (${f.venue},'ACTIVE')
                    ON CONFLICT (venue_id) DO UPDATE SET status='ACTIVE';
                    INSERT INTO venue_seating_rules (venue_id,use_plan,guest_table_choice) VALUES (${f.venue},TRUE,TRUE)
                    ON CONFLICT (venue_id) DO UPDATE SET use_plan=TRUE,guest_table_choice=TRUE;
                    INSERT INTO hall_plan_media (id,venue_id,hall_id,actor_user_id,request_id,object_key,checksum_sha256,
                    input_sha256,mime_type,size_bytes,width,height,status) VALUES ('$plan',${f.venue},$hall,101,'$plan',
                    'venue-info/$plan.jpg',repeat('a',64),repeat('b',64),'image/jpeg',12,1,1,'READY')
                    """.trimIndent(),
                )

                fun candidates() =
                    Json.parseToJsonElement(f.backupInventory()).jsonArray.map { it.jsonArray }
                        .associate { it[0].jsonPrimitive.content to it.last().toString() }
                val all = candidates()
                val planKey = "venue-info/$plan.jpg"
                assertEquals(listOf("true", "true"), all.values.sorted())
                f.sql("UPDATE venue_seating_rules SET guest_table_choice=FALSE WHERE venue_id=${f.venue}")
                assertEquals(all + (planKey to "false"), candidates())
                f.sql("UPDATE venue_seating_rules SET guest_table_choice=TRUE WHERE venue_id=${f.venue}")
                f.sql("UPDATE venue_info_sections SET is_visible=FALSE WHERE id=${f.section}")
                assertEquals("true", candidates()[planKey])
                assertEquals("false", candidates().filterKeys { it != planKey }.values.single())
                f.sql("UPDATE venue_subscriptions SET status='SUSPENDED_BY_PLATFORM' WHERE venue_id=${f.venue}")
                assertEquals(listOf("false", "false"), candidates().values.sorted())
            }
        }

    @Test fun `production backup inventory observes visibility and keeps deleted bytes out of recovery`() =
        runBlocking {
            MediaFixture(source()).use { f ->
                f.init()

                fun inventory() = f.backupInventory()
                assertEquals("[]", inventory())
                f.upload()
                val id = f.current().media.single().id
                val shown = inventory()
                assertTrue(shown.contains("venue-info/"))
                f.action(id, "hide")
                assertNotEquals(shown, inventory())
                f.sql(
                    "INSERT INTO venue_subscriptions (venue_id,status) VALUES (${f.venue},'ACTIVE') " +
                        "ON CONFLICT (venue_id) DO UPDATE SET status='ACTIVE'",
                )
                val profile = f.seed.display
                f.sql(
                    "INSERT INTO staff_shifts (venue_id,staff_profile_id,shift_date,status,created_by_user_id) " +
                        "VALUES (${f.venue},$profile,CURRENT_DATE,'active',101)",
                )
                f.seed.upload()

                fun photo() =
                    Json.parseToJsonElement(inventory()).jsonArray.map { it.jsonArray }
                        .single { it[0].jsonPrimitive.content.startsWith("staff-photos/") }
                val initial = photo()
                assertEquals("true", initial.last().toString())
                val restrictions =
                    listOf(
                        "venue_settings SET guest_team_visible" to "venue_id=${f.venue}",
                        "venue_settings SET team_schedule_module_enabled" to "venue_id=${f.venue}",
                        "staff_profiles SET is_guest_visible" to "id=$profile",
                        "staff_shifts SET is_guest_visible" to "staff_profile_id=$profile",
                    )
                for ((setting, where) in restrictions) {
                    f.sql("UPDATE $setting=FALSE WHERE $where")
                    assertNotEquals(initial[3], photo()[3])
                    assertEquals("false", photo().last().toString())
                    f.sql("UPDATE $setting=TRUE WHERE $where")
                    assertEquals(initial, photo())
                }
                f.seed.upload()
                assertNotEquals(initial[0], photo()[0])
                f.seed.action("delete")
                f.action(id, "delete")
                assertEquals("[]", inventory())
                assertTrue(f.count("SELECT count(*) FROM venue_info_media_cleanup WHERE due_at IS NOT NULL") > 0)
            }
        }
}

class VenueInfoMediaValidationTest {
    @Test fun `reject disguised executable corrupt images oversized and active pdf`() {
        for ((bytes, mime) in listOf(
            "MZbinary".toByteArray() to "application/pdf",
            "<script/>".toByteArray() to "image/png",
            syntheticPhoto() to "application/pdf",
            syntheticPdf(true) to "application/pdf",
            syntheticPdf().copyOf(100) to "application/pdf",
            ByteArray(VENUE_INFO_MEDIA_MAX_BYTES + 1) to "application/pdf",
            syntheticPhoto().also {
                it[25] = 0
            } to "image/png",
        )) {
            assertFailsWith<VenueInfoMediaInvalidException> {
                VenueInfoMediaValidation.validate(bytes, mime, ::fixturePdfAdmission)
            }
        }
    }

    @Test fun `JPEG and PNG normalize EXIF orientation without retaining private metadata`() {
        for (orientation in 1..8) for (format in listOf("jpeg", "png")) {
            val tiff =
                java.nio.ByteBuffer.allocate(26).order(java.nio.ByteOrder.LITTLE_ENDIAN)
                    .put('I'.code.toByte()).put('I'.code.toByte()).putShort(42).putInt(8).putShort(1)
                    .putShort(0x112).putShort(3).putInt(1).putShort(orientation.toShort()).putShort(0).putInt(0).array()
            val original = syntheticPhoto(format, 80, 40)
            val input =
                if (format == "jpeg") {
                    val payload = "Exif\u0000\u0000".toByteArray() + tiff
                    original.copyOfRange(0, 2) +
                        byteArrayOf(-1, -31, 0, (payload.size + 2).toByte()) +
                        payload + original.copyOfRange(2, original.size)
                } else {
                    val type = "eXIf".toByteArray()
                    val crc = java.util.zip.CRC32().also { it.update(type + tiff) }.value
                    val chunk =
                        java.nio.ByteBuffer.allocate(
                            tiff.size + 12,
                        ).putInt(tiff.size).put(type).put(tiff).putInt(crc.toInt()).array()
                    original.copyOfRange(0, 33) + chunk + original.copyOfRange(33, original.size)
                }
            val result = VenueInfoMediaValidation.validate(input, "image/$format")
            val image = javax.imageio.ImageIO.read(java.io.ByteArrayInputStream(result.bytes))
            assertEquals(if (orientation >= 5) 40 else 80, image.width)
            assertEquals(if (orientation >= 5) 80 else 40, image.height)
            val metadata = com.drew.imaging.ImageMetadataReader.readMetadata(java.io.ByteArrayInputStream(result.bytes))
            assertFalse(metadata.directories.any { it.name.contains("Exif") || it.name.contains("GPS") })
        }
    }

    @Test fun `safe pdf and jpeg png remain distinct formats`() {
        for ((bytes, mime) in listOf(
            syntheticPdf() to "application/pdf",
            syntheticPhoto() to "image/png",
            syntheticPhoto("jpeg") to "image/jpeg",
        )) {
            assertEquals(mime, VenueInfoMediaValidation.validate(bytes, mime, ::fixturePdfAdmission).mime)
        }
    }

    @Test fun `reject dimension limit and animated png and xref allocation bomb`() {
        assertFailsWith<VenueInfoMediaInvalidException> {
            VenueInfoMediaValidation.validate(syntheticPhoto(width = 8193, height = 1), "image/png")
        }
        assertFailsWith<VenueInfoMediaInvalidException> {
            VenueInfoMediaValidation.validate(
                "%PDF-1.4\n1 0 obj <</Size 999999999>> endobj\n%%EOF".toByteArray(),
                "application/pdf",
            )
        }
    }
}
