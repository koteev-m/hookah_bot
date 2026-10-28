package com.hookah.platform.backend.miniapp.venue.staff

import com.hookah.platform.backend.api.StaffPhotoUnavailableException
import com.hookah.platform.backend.miniapp.venue.AuditLogRepository
import com.hookah.platform.backend.test.PostgresTestEnv
import com.hookah.platform.backend.test.migrateH2OnboardingFixture
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import kotlinx.coroutines.CompletableDeferred
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.sql.Statement
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import javax.imageio.ImageIO
import javax.sql.DataSource

internal class PhotoClock : Clock() {
    var now: Instant = Instant.now().plusSeconds(1)

    override fun instant() = now

    override fun getZone(): ZoneId = ZoneId.of("UTC")

    override fun withZone(zone: ZoneId): Clock = this
}

internal class FakeStaffPhotoStorage : StaffPhotoStorage {
    override var available = true
    val objects = ConcurrentHashMap<String, ByteArray>()
    var puts = 0
    var failPut = false
    var failDelete = false
    var afterPut: suspend () -> Unit = {}
    var beforeGet: suspend () -> Unit = {}
    var entered: CompletableDeferred<Unit>? = null
    var release: CompletableDeferred<Unit>? = null

    override suspend fun put(
        key: String,
        bytes: ByteArray,
    ) {
        puts++
        entered?.complete(Unit)
        release?.await()
        if (failPut) throw StaffPhotoUnavailableException()
        objects[key] = bytes.copyOf()
        afterPut()
    }

    override suspend fun get(key: String): ByteArray? {
        val bytes = objects[key]?.copyOf()
        beforeGet()
        return bytes
    }

    override suspend fun exists(key: String) = objects.containsKey(key)

    override suspend fun delete(key: String) {
        if (failDelete) throw StaffPhotoUnavailableException()
        objects.remove(key)
    }
}

internal fun syntheticPhoto(
    format: String = "png",
    width: Int = 80,
    height: Int = 40,
): ByteArray {
    val image = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
    image.createGraphics().let { g ->
        g.color = Color(20, 130, 90)
        g.fillRect(0, 0, width, height)
        g.dispose()
    }
    return ByteArrayOutputStream().use {
        ImageIO.write(image, format, it)
        it.toByteArray()
    }
}

internal fun photoTestDataSource(postgres: Boolean): HikariDataSource {
    if (postgres) return PostgresTestEnv.createDataSource(PostgresTestEnv.createDatabase())
    return HikariDataSource(
        HikariConfig().apply {
            jdbcUrl = "jdbc:h2:mem:photos_${UUID.randomUUID()};MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1"
            username = "sa"
            password = ""
            maximumPoolSize = 4
        },
    ).also { migrateH2OnboardingFixture(it) }
}

internal class PhotoFixture(val ds: DataSource) {
    val clock = PhotoClock()
    val storage = FakeStaffPhotoStorage()
    val profiles = VenueStaffProfileRepository(ds)
    val repo = StaffPhotoRepository(ds, profiles, AuditLogRepository(ds), clock)
    var visible = true
    val service = StaffPhotoService(repo, storage, { if (visible) setOf(display, linked, protected) else emptySet() })
    val venue: Long
    val foreignVenue: Long
    val display: Long
    val linked: Long
    val protected: Long

    init {
        sql(
            """
            INSERT INTO users (telegram_user_id,  first_name) VALUES (101, 'Owner'), (102, 'Manager'),
            (103, 'Staff'), (104, 'Foreign'), (105, 'Protected')
            """.trimIndent(),
        )
        venue =
            insert(
                """
                INSERT INTO venues (name, city, address, status) VALUES ('Photo test', 'Test', 'Test',
                'PUBLISHED')
                """.trimIndent(),
            )
        foreignVenue =
            insert(
                """
                INSERT INTO venues (name, city, address, status) VALUES ('Other', 'Test', 'Test',
                'PUBLISHED')
                """.trimIndent(),
            )
        sql(
            """
            INSERT INTO venue_members (venue_id, user_id, role) VALUES ($venue, 101, 'OWNER'),
            ($venue, 102, 'MANAGER'), ($venue, 103, 'STAFF'), ($venue, 105, 'MANAGER'),
            ($foreignVenue, 104, 'OWNER')
            """.trimIndent(),
        )
        sql(
            """
            INSERT INTO venue_settings (venue_id, team_schedule_module_enabled, guest_team_visible,
            today_staff_source) VALUES ($venue, TRUE, TRUE, 'MANUAL')
            """.trimIndent(),
        )
        display = profile(null)
        linked = profile(103)
        protected = profile(105)
    }

    fun profile(link: Long?): Long =
        insert(
            """
            INSERT INTO staff_profiles (venue_id, linked_user_id, display_name, subtype,
            is_guest_visible, published_at, created_by_user_id, updated_by_user_id)
            VALUES ($venue, ${link ?: "NULL"}, 'Synthetic', 'waiter', TRUE, CURRENT_TIMESTAMP, 101, 101)
            """.trimIndent(),
        )

    fun sql(query: String) =
        ds.connection.use { c ->
            c.createStatement().use { it.execute(query) }
            Unit
        }

    fun count(query: String): Long =
        ds.connection.use { c ->
            c.createStatement().use { s ->
                s.executeQuery(query).use {
                    it.next()
                    it.getLong(1)
                }
            }
        }

    fun insert(query: String): Long =
        ds.connection.use { c ->
            c.prepareStatement(query, Statement.RETURN_GENERATED_KEYS).use { s ->
                s.executeUpdate()
                s.generatedKeys.use {
                    it.next()
                    it.getLong(1)
                }
            }
        }

    suspend fun state(
        profile: Long = display,
        actor: Long = 101,
    ) = service.state(venue, profile, actor)

    suspend fun upload(
        profile: Long = display,
        actor: Long = 101,
        revision: Long? = null,
        request: String = UUID.randomUUID().toString(),
        bytes: ByteArray = syntheticPhoto(),
    ): StaffPhotoStateDto =
        service.upload(
            venue,
            profile,
            actor,
            revision ?: state(profile, actor).revision,
            request,
            "image/png",
        ) { bytes }

    suspend fun action(
        action: String,
        profile: Long = display,
        actor: Long = 101,
        photo: String? = null,
        revision: Long? = null,
    ): StaffPhotoStateDto {
        val s = state(profile, actor)
        return service.mutate(
            venue,
            profile,
            actor,
            photo ?: s.candidate?.id ?: s.current?.id,
            revision ?: s.revision,
            action,
            true,
        )
    }

    suspend fun publish(profile: Long = display): StaffPhotoStateDto {
        upload(profile)
        action("consent", profile, if (profile == linked) 103 else 101)
        return action("publish", profile)
    }
}
