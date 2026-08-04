package com.hookah.platform.backend.miniapp.venue.staff

import com.hookah.platform.backend.api.DatabaseUnavailableException
import com.hookah.platform.backend.api.InvalidInputException
import com.hookah.platform.backend.api.NotFoundException
import com.hookah.platform.backend.api.StaffShiftCanceledConflictException
import com.hookah.platform.backend.api.StaffShiftConfirmationStaleException
import com.hookah.platform.backend.api.StaffShiftDateConflictException
import com.hookah.platform.backend.api.StaffShiftImmutableException
import com.hookah.platform.backend.api.StaffShiftInvalidIntervalException
import com.hookah.platform.backend.api.StaffShiftStaleException
import com.hookah.platform.backend.api.StaffShiftTodayOverrideException
import com.hookah.platform.backend.miniapp.venue.AuditLogRepository
import com.hookah.platform.backend.miniapp.venue.VenueRole
import com.hookah.platform.backend.miniapp.venue.VenueRoleMapping
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.sql.Connection
import java.sql.ResultSet
import java.sql.SQLException
import java.sql.Statement
import java.sql.Timestamp
import java.sql.Types
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import javax.sql.DataSource

class VenueStaffProfileRepository(
    private val dataSource: DataSource?,
    private val json: Json = Json,
) {
    suspend fun listProfiles(
        venueId: Long,
        today: LocalDate,
        linkedUserId: Long? = null,
        requesterUserId: Long? = null,
        requesterRole: VenueRole? = null,
    ): List<VenueStaffProfileWithTodayShift> {
        require((requesterUserId == null) == (requesterRole == null))
        val ds = dataSource ?: throw DatabaseUnavailableException()
        return withContext(Dispatchers.IO) {
            try {
                ds.connection.use { connection ->
                    val linkedFilter = if (linkedUserId == null) "" else "AND sp.linked_user_id = ?"
                    connection.prepareStatement(
                        """
                        SELECT
                            sp.id AS profile_id,
                            sp.venue_id AS profile_venue_id,
                            sp.linked_user_id,
                            sp.display_name,
                            sp.role_label,
                            sp.subtype,
                            sp.photo_ref,
                            sp.bio,
                            sp.tags,
                            sp.is_guest_visible AS profile_is_guest_visible,
                            sp.created_by_user_id AS profile_created_by_user_id,
                            sp.updated_by_user_id AS profile_updated_by_user_id,
                            sp.published_at,
                            sp.disabled_at,
                            sp.created_at AS profile_created_at,
                            sp.updated_at AS profile_updated_at,
                            linked_member.role AS linked_member_role,
                            COALESCE(active_links.active_profile_count, 0) AS active_profile_count,
                            ss.id AS shift_id,
                            ss.venue_id AS shift_venue_id,
                            ss.staff_profile_id,
                            ss.shift_date,
                            ss.starts_at,
                            ss.ends_at,
                            ss.status AS shift_status,
                            ss.is_guest_visible AS shift_is_guest_visible,
                            ss.manually_marked_active,
                            ss.created_by_user_id AS shift_created_by_user_id,
                            ss.updated_by_user_id AS shift_updated_by_user_id,
                            ss.created_at AS shift_created_at,
                            ss.updated_at AS shift_updated_at
                        FROM staff_profiles sp
                        LEFT JOIN venue_members linked_member
                          ON linked_member.venue_id = sp.venue_id
                         AND linked_member.user_id = sp.linked_user_id
                        LEFT JOIN (
                            SELECT venue_id, linked_user_id, COUNT(*) AS active_profile_count
                            FROM staff_profiles
                            WHERE linked_user_id IS NOT NULL
                              AND disabled_at IS NULL
                              AND venue_id = ?
                            GROUP BY venue_id, linked_user_id
                        ) active_links
                          ON active_links.venue_id = sp.venue_id
                         AND active_links.linked_user_id = sp.linked_user_id
                        LEFT JOIN staff_shifts ss
                          ON ss.staff_profile_id = sp.id
                         AND ss.venue_id = sp.venue_id
                         AND ss.shift_date = ?
                        WHERE sp.venue_id = ?
                        $linkedFilter
                        ORDER BY sp.created_at, sp.id
                        """.trimIndent(),
                    ).use { statement ->
                        statement.setLong(1, venueId)
                        statement.setObject(2, today)
                        statement.setLong(3, venueId)
                        if (linkedUserId != null) {
                            statement.setLong(4, linkedUserId)
                        }
                        statement.executeQuery().use { rs ->
                            val result = mutableListOf<VenueStaffProfileWithTodayShift>()
                            while (rs.next()) {
                                val profile = rs.toStaffProfile()
                                result.add(
                                    VenueStaffProfileWithTodayShift(
                                        profile = profile,
                                        todayShift = rs.toStaffShiftOrNull(),
                                        access =
                                            requesterUserId?.let { actorUserId ->
                                                projectProfileAccess(
                                                    profile = profile,
                                                    actorUserId = actorUserId,
                                                    actorRole = checkNotNull(requesterRole),
                                                    linkedRole =
                                                        VenueRoleMapping.fromDb(
                                                            rs.getString("linked_member_role"),
                                                        ),
                                                    activeLinkCount = rs.getInt("active_profile_count"),
                                                )
                                            },
                                    ),
                                )
                            }
                            result
                        }
                    }
                }
            } catch (e: SQLException) {
                throw DatabaseUnavailableException()
            }
        }
    }

    suspend fun findProfile(
        venueId: Long,
        profileId: Long,
    ): VenueStaffProfile? {
        val ds = dataSource ?: throw DatabaseUnavailableException()
        return withContext(Dispatchers.IO) {
            try {
                ds.connection.use { connection ->
                    connection.prepareStatement(
                        """
                        SELECT
                            id AS profile_id,
                            venue_id AS profile_venue_id,
                            linked_user_id,
                            display_name,
                            role_label,
                            subtype,
                            photo_ref,
                            bio,
                            tags,
                            is_guest_visible AS profile_is_guest_visible,
                            created_by_user_id AS profile_created_by_user_id,
                            updated_by_user_id AS profile_updated_by_user_id,
                            published_at,
                            disabled_at,
                            created_at AS profile_created_at,
                            updated_at AS profile_updated_at
                        FROM staff_profiles
                        WHERE venue_id = ? AND id = ?
                        """.trimIndent(),
                    ).use { statement ->
                        statement.setLong(1, venueId)
                        statement.setLong(2, profileId)
                        statement.executeQuery().use { rs ->
                            if (rs.next()) {
                                rs.toStaffProfile()
                            } else {
                                null
                            }
                        }
                    }
                }
            } catch (e: SQLException) {
                throw DatabaseUnavailableException()
            }
        }
    }

    suspend fun createProfile(
        venueId: Long,
        actorUserId: Long,
        actorRole: VenueRole,
        input: StaffProfileWrite,
        auditLogRepository: AuditLogRepository,
    ): StaffProfileMutationResult =
        inTransaction { connection ->
            if (!actorRoleStillApplies(connection, venueId, actorUserId, actorRole)) {
                return@inTransaction StaffProfileMutationResult.Forbidden
            }
            if (input.linkedUserId != null) {
                return@inTransaction StaffProfileMutationResult.InvalidLink
            }
            val profile = insertProfileInConnection(connection, venueId, actorUserId, input)
            appendStaffProfileAudit(
                connection = connection,
                auditLogRepository = auditLogRepository,
                actorUserId = actorUserId,
                action = STAFF_PROFILE_CREATED_AUDIT_ACTION,
                old = null,
                new = profile,
                changedFields = profileCreationFieldNames(input),
            )
            StaffProfileMutationResult.Success(
                profile = profile,
                changed = true,
                access = profileAccessInConnection(connection, profile, actorUserId, actorRole),
            )
        }

    suspend fun createProfileFromMember(
        venueId: Long,
        actorUserId: Long,
        actorRole: VenueRole,
        targetUserId: Long,
        subtype: String,
        roleLabel: String?,
        auditLogRepository: AuditLogRepository,
    ): StaffProfileMutationResult =
        inTransaction { connection ->
            if (!actorRoleStillApplies(connection, venueId, actorUserId, actorRole)) {
                return@inTransaction StaffProfileMutationResult.Forbidden
            }
            guardActiveLink(
                connection = connection,
                venueId = venueId,
                linkedUserId = targetUserId,
                actorRole = actorRole,
            )?.let { result -> return@inTransaction result }
            val identity =
                findMemberIdentityInConnection(connection, targetUserId)
                    ?: return@inTransaction StaffProfileMutationResult.InvalidLink
            val input =
                StaffProfileWrite(
                    linkedUserId = targetUserId,
                    displayName =
                        buildSafeMemberDisplayName(
                            firstName = identity.firstName,
                            lastName = identity.lastName,
                            username = identity.username,
                        ),
                    roleLabel = roleLabel,
                    subtype = subtype,
                    photoRef = null,
                    bio = null,
                    tags = emptyList(),
                    isGuestVisible = false,
                )
            val profile = insertProfileInConnection(connection, venueId, actorUserId, input)
            appendStaffProfileAudit(
                connection = connection,
                auditLogRepository = auditLogRepository,
                actorUserId = actorUserId,
                action = STAFF_PROFILE_CREATED_AUDIT_ACTION,
                old = null,
                new = profile,
                changedFields = profileCreationFieldNames(input),
            )
            StaffProfileMutationResult.Success(
                profile = profile,
                changed = true,
                access = profileAccessInConnection(connection, profile, actorUserId, actorRole),
            )
        }

    suspend fun updateProfile(
        venueId: Long,
        profileId: Long,
        actorUserId: Long,
        actorRole: VenueRole,
        selfEditOnlyRequest: Boolean,
        auditLogRepository: AuditLogRepository,
        buildInput: (VenueStaffProfile) -> StaffProfileWrite,
    ): StaffProfileMutationResult =
        inTransaction { connection ->
            if (!actorRoleStillApplies(connection, venueId, actorUserId, actorRole)) {
                return@inTransaction StaffProfileMutationResult.Forbidden
            }
            val current =
                findProfileInConnection(connection, venueId, profileId, forUpdate = true)
                    ?: return@inTransaction StaffProfileMutationResult.NotFound
            val input = buildInput(current)
            val changedFields = changedProfileFields(current, input)
            when (
                authorizeProfileMutation(
                    connection = connection,
                    venueId = venueId,
                    actorUserId = actorUserId,
                    actorRole = actorRole,
                    current = current,
                    requestedLinkedUserId = input.linkedUserId,
                    changedFields = changedFields,
                    selfEditOnlyRequest = selfEditOnlyRequest,
                    publishing = false,
                )
            ) {
                StaffProfileLinkValidation.ALLOWED -> Unit
                StaffProfileLinkValidation.INVALID -> return@inTransaction StaffProfileMutationResult.InvalidLink
                StaffProfileLinkValidation.PROTECTED -> return@inTransaction StaffProfileMutationResult.Forbidden
            }
            if (changedFields.isEmpty()) {
                return@inTransaction StaffProfileMutationResult.Success(
                    profile = current,
                    changed = false,
                    access = profileAccessInConnection(connection, current, actorUserId, actorRole),
                )
            }
            val resultingProfileIsActive =
                input.isGuestVisible || (!current.isGuestVisible && current.disabledAt == null)
            val changesActiveLink =
                resultingProfileIsActive &&
                    input.linkedUserId != null &&
                    (current.linkedUserId != input.linkedUserId || current.disabledAt != null)
            if (changesActiveLink) {
                guardActiveLink(
                    connection = connection,
                    venueId = venueId,
                    linkedUserId = checkNotNull(input.linkedUserId),
                    actorRole = actorRole,
                    excludedProfileId = current.id,
                )?.let { result -> return@inTransaction result }
            }
            val now = Instant.now()
            val publishedAt =
                if (input.isGuestVisible) current.publishedAt ?: now else current.publishedAt
            val disabledAt =
                when {
                    input.isGuestVisible -> null
                    current.isGuestVisible -> now
                    else -> current.disabledAt
                }
            connection.prepareStatement(
                """
                UPDATE staff_profiles
                SET linked_user_id = ?,
                    display_name = ?,
                    role_label = ?,
                    subtype = ?,
                    photo_ref = ?,
                    bio = ?,
                    tags = ?,
                    is_guest_visible = ?,
                    updated_by_user_id = ?,
                    published_at = ?,
                    disabled_at = ?,
                    updated_at = CURRENT_TIMESTAMP
                WHERE venue_id = ? AND id = ?
                """.trimIndent(),
            ).use { statement ->
                statement.setNullableLong(1, input.linkedUserId)
                statement.setString(2, input.displayName)
                statement.setNullableString(3, input.roleLabel)
                statement.setString(4, input.subtype)
                statement.setNullableString(5, input.photoRef)
                statement.setNullableString(6, input.bio)
                statement.setNullableString(7, encodeTags(input.tags))
                statement.setBoolean(8, input.isGuestVisible)
                statement.setLong(9, actorUserId)
                statement.setNullableInstant(10, publishedAt)
                statement.setNullableInstant(11, disabledAt)
                statement.setLong(12, venueId)
                statement.setLong(13, profileId)
                if (statement.executeUpdate() != 1) {
                    throw DatabaseUnavailableException()
                }
            }
            val updated =
                findProfileInConnection(connection, venueId, profileId)
                    ?: throw DatabaseUnavailableException()
            appendStaffProfileAudit(
                connection = connection,
                auditLogRepository = auditLogRepository,
                actorUserId = actorUserId,
                action = STAFF_PROFILE_UPDATED_AUDIT_ACTION,
                old = current,
                new = updated,
                changedFields = changedFields,
            )
            StaffProfileMutationResult.Success(
                profile = updated,
                changed = true,
                access = profileAccessInConnection(connection, updated, actorUserId, actorRole),
            )
        }

    suspend fun publishProfile(
        venueId: Long,
        profileId: Long,
        actorUserId: Long,
        actorRole: VenueRole,
        auditLogRepository: AuditLogRepository,
    ): StaffProfileMutationResult =
        setProfileVisibility(
            venueId = venueId,
            profileId = profileId,
            actorUserId = actorUserId,
            actorRole = actorRole,
            visible = true,
            auditLogRepository = auditLogRepository,
        )

    suspend fun hideProfile(
        venueId: Long,
        profileId: Long,
        actorUserId: Long,
        actorRole: VenueRole,
        auditLogRepository: AuditLogRepository,
    ): StaffProfileMutationResult =
        setProfileVisibility(
            venueId = venueId,
            profileId = profileId,
            actorUserId = actorUserId,
            actorRole = actorRole,
            visible = false,
            auditLogRepository = auditLogRepository,
        )

    private suspend fun setProfileVisibility(
        venueId: Long,
        profileId: Long,
        actorUserId: Long,
        actorRole: VenueRole,
        visible: Boolean,
        auditLogRepository: AuditLogRepository,
    ): StaffProfileMutationResult =
        inTransaction { connection ->
            if (!actorRoleStillApplies(connection, venueId, actorUserId, actorRole)) {
                return@inTransaction StaffProfileMutationResult.Forbidden
            }
            val current =
                findProfileInConnection(connection, venueId, profileId, forUpdate = true)
                    ?: return@inTransaction StaffProfileMutationResult.NotFound
            when (
                authorizeProfileMutation(
                    connection = connection,
                    venueId = venueId,
                    actorUserId = actorUserId,
                    actorRole = actorRole,
                    current = current,
                    requestedLinkedUserId = current.linkedUserId,
                    changedFields = setOf(STAFF_PROFILE_VISIBILITY_FIELD),
                    selfEditOnlyRequest = false,
                    publishing = true,
                )
            ) {
                StaffProfileLinkValidation.ALLOWED -> Unit
                StaffProfileLinkValidation.INVALID -> return@inTransaction StaffProfileMutationResult.InvalidLink
                StaffProfileLinkValidation.PROTECTED -> return@inTransaction StaffProfileMutationResult.Forbidden
            }
            if (current.isGuestVisible == visible) {
                return@inTransaction StaffProfileMutationResult.Success(
                    profile = current,
                    changed = false,
                    access = profileAccessInConnection(connection, current, actorUserId, actorRole),
                )
            }
            if (visible && current.disabledAt != null && current.linkedUserId != null) {
                guardActiveLink(
                    connection = connection,
                    venueId = venueId,
                    linkedUserId = current.linkedUserId,
                    actorRole = actorRole,
                    excludedProfileId = current.id,
                )?.let { result -> return@inTransaction result }
            }
            val now = Instant.now()
            connection.prepareStatement(
                """
                UPDATE staff_profiles
                SET is_guest_visible = ?,
                    published_at = ?,
                    disabled_at = ?,
                    updated_by_user_id = ?,
                    updated_at = CURRENT_TIMESTAMP
                WHERE venue_id = ? AND id = ?
                """.trimIndent(),
            ).use { statement ->
                statement.setBoolean(1, visible)
                statement.setNullableInstant(2, if (visible) current.publishedAt ?: now else current.publishedAt)
                statement.setNullableInstant(3, if (visible) null else now)
                statement.setLong(4, actorUserId)
                statement.setLong(5, venueId)
                statement.setLong(6, profileId)
                if (statement.executeUpdate() != 1) {
                    throw DatabaseUnavailableException()
                }
            }
            val updated =
                findProfileInConnection(connection, venueId, profileId)
                    ?: throw DatabaseUnavailableException()
            appendStaffProfileAudit(
                connection = connection,
                auditLogRepository = auditLogRepository,
                actorUserId = actorUserId,
                action =
                    if (visible) {
                        STAFF_PROFILE_PUBLISHED_AUDIT_ACTION
                    } else {
                        STAFF_PROFILE_HIDDEN_AUDIT_ACTION
                    },
                old = current,
                new = updated,
                changedFields = setOf(STAFF_PROFILE_VISIBILITY_FIELD),
            )
            StaffProfileMutationResult.Success(
                profile = updated,
                changed = true,
                access = profileAccessInConnection(connection, updated, actorUserId, actorRole),
            )
        }

    private fun insertProfileInConnection(
        connection: Connection,
        venueId: Long,
        actorUserId: Long,
        input: StaffProfileWrite,
    ): VenueStaffProfile {
        val publishedAt = Instant.now().takeIf { input.isGuestVisible }
        val profileId =
            connection.prepareStatement(
                """
                INSERT INTO staff_profiles (
                    venue_id,
                    linked_user_id,
                    display_name,
                    role_label,
                    subtype,
                    photo_ref,
                    bio,
                    tags,
                    is_guest_visible,
                    created_by_user_id,
                    updated_by_user_id,
                    published_at,
                    disabled_at
                )
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """.trimIndent(),
                Statement.RETURN_GENERATED_KEYS,
            ).use { statement ->
                statement.setLong(1, venueId)
                statement.setNullableLong(2, input.linkedUserId)
                statement.setString(3, input.displayName)
                statement.setNullableString(4, input.roleLabel)
                statement.setString(5, input.subtype)
                statement.setNullableString(6, input.photoRef)
                statement.setNullableString(7, input.bio)
                statement.setNullableString(8, encodeTags(input.tags))
                statement.setBoolean(9, input.isGuestVisible)
                statement.setLong(10, actorUserId)
                statement.setLong(11, actorUserId)
                statement.setNullableInstant(12, publishedAt)
                statement.setNull(13, Types.TIMESTAMP)
                statement.executeUpdate()
                statement.generatedKeys.use { rs ->
                    if (rs.next()) rs.getLong(1) else throw DatabaseUnavailableException()
                }
            }
        return findProfileInConnection(connection, venueId, profileId)
            ?: throw DatabaseUnavailableException()
    }

    private fun findMemberIdentityInConnection(
        connection: Connection,
        userId: Long,
    ): StaffMemberIdentity? =
        connection.prepareStatement(
            """
            SELECT username, first_name, last_name
            FROM users
            WHERE telegram_user_id = ?
            """.trimIndent(),
        ).use { statement ->
            statement.setLong(1, userId)
            statement.executeQuery().use { rs ->
                if (rs.next()) {
                    StaffMemberIdentity(
                        username = normalizeTelegramUsername(rs.getString("username")),
                        firstName = rs.getString("first_name"),
                        lastName = rs.getString("last_name"),
                    )
                } else {
                    null
                }
            }
        }

    private fun actorRoleStillApplies(
        connection: Connection,
        venueId: Long,
        actorUserId: Long,
        expectedRole: VenueRole,
    ): Boolean = lockMembershipRole(connection, venueId, actorUserId) == expectedRole

    private fun profileAccessInConnection(
        connection: Connection,
        profile: VenueStaffProfile,
        actorUserId: Long,
        actorRole: VenueRole,
    ): VenueStaffProfileAccess {
        val linkedRole =
            profile.linkedUserId?.let { linkedUserId ->
                lockMembershipRole(connection, profile.venueId, linkedUserId)
            }
        val activeLinkCount =
            profile.linkedUserId?.let { linkedUserId ->
                countActiveLinks(connection, profile.venueId, linkedUserId)
            } ?: 0
        return projectProfileAccess(
            profile = profile,
            actorUserId = actorUserId,
            actorRole = actorRole,
            linkedRole = linkedRole,
            activeLinkCount = activeLinkCount,
        )
    }

    private fun projectProfileAccess(
        profile: VenueStaffProfile,
        actorUserId: Long,
        actorRole: VenueRole,
        linkedRole: VenueRole?,
        activeLinkCount: Int,
    ): VenueStaffProfileAccess {
        val baseLinkageClass =
            when {
                profile.linkedUserId == null -> VenueStaffProfileLinkageClass.DISPLAY_ONLY
                linkedRole == null -> VenueStaffProfileLinkageClass.PROTECTED
                actorRole == VenueRole.MANAGER && linkedRole != VenueRole.STAFF ->
                    VenueStaffProfileLinkageClass.PROTECTED
                actorRole == VenueRole.STAFF && profile.linkedUserId != actorUserId ->
                    VenueStaffProfileLinkageClass.PROTECTED
                else -> VenueStaffProfileLinkageClass.STAFF_LINKED
            }
        val linkageClass =
            if (
                baseLinkageClass == VenueStaffProfileLinkageClass.STAFF_LINKED &&
                profile.disabledAt == null &&
                activeLinkCount > 1
            ) {
                VenueStaffProfileLinkageClass.DUPLICATE_LINK_DETECTED
            } else {
                baseLinkageClass
            }
        val canManage =
            when (actorRole) {
                VenueRole.OWNER -> true
                VenueRole.MANAGER ->
                    linkageClass == VenueStaffProfileLinkageClass.DISPLAY_ONLY ||
                        linkageClass == VenueStaffProfileLinkageClass.STAFF_LINKED
                VenueRole.STAFF -> false
            }
        val exposedLinkedUserId =
            when {
                actorRole == VenueRole.OWNER -> profile.linkedUserId
                actorRole == VenueRole.MANAGER && linkageClass == VenueStaffProfileLinkageClass.STAFF_LINKED ->
                    profile.linkedUserId
                else -> null
            }
        return VenueStaffProfileAccess(
            linkageClass = linkageClass,
            canManage = canManage,
            isSelf = profile.linkedUserId == actorUserId,
            linkedUserId = exposedLinkedUserId,
        )
    }

    private fun validateRequestedLink(
        connection: Connection,
        venueId: Long,
        linkedUserId: Long?,
        actorRole: VenueRole,
    ): StaffProfileLinkValidation {
        if (linkedUserId == null) {
            return StaffProfileLinkValidation.ALLOWED
        }
        val linkedRole =
            lockMembershipRole(connection, venueId, linkedUserId)
                ?: return StaffProfileLinkValidation.INVALID
        return when (actorRole) {
            VenueRole.OWNER -> StaffProfileLinkValidation.ALLOWED
            VenueRole.MANAGER ->
                if (linkedRole == VenueRole.STAFF) {
                    StaffProfileLinkValidation.ALLOWED
                } else {
                    StaffProfileLinkValidation.PROTECTED
                }
            VenueRole.STAFF -> StaffProfileLinkValidation.PROTECTED
        }
    }

    private fun guardActiveLink(
        connection: Connection,
        venueId: Long,
        linkedUserId: Long,
        actorRole: VenueRole,
        excludedProfileId: Long? = null,
    ): StaffProfileMutationResult? {
        when (validateRequestedLink(connection, venueId, linkedUserId, actorRole)) {
            StaffProfileLinkValidation.ALLOWED -> Unit
            StaffProfileLinkValidation.INVALID -> return StaffProfileMutationResult.InvalidLink
            StaffProfileLinkValidation.PROTECTED -> return StaffProfileMutationResult.Forbidden
        }
        return findActiveLinkConflict(
            connection = connection,
            venueId = venueId,
            linkedUserId = linkedUserId,
            excludedProfileId = excludedProfileId,
        )
    }

    private fun authorizeProfileMutation(
        connection: Connection,
        venueId: Long,
        actorUserId: Long,
        actorRole: VenueRole,
        current: VenueStaffProfile,
        requestedLinkedUserId: Long?,
        changedFields: Set<String>,
        selfEditOnlyRequest: Boolean,
        publishing: Boolean,
    ): StaffProfileLinkValidation {
        if (actorRole == VenueRole.OWNER) {
            return validateRequestedLink(connection, venueId, requestedLinkedUserId, actorRole)
        }
        val currentLinkedRole =
            current.linkedUserId?.let { lockMembershipRole(connection, venueId, it) }
        if (
            actorRole == VenueRole.MANAGER &&
            currentLinkedRole == VenueRole.STAFF &&
            current.disabledAt == null &&
            countActiveLinks(connection, venueId, checkNotNull(current.linkedUserId)) > 1
        ) {
            return StaffProfileLinkValidation.PROTECTED
        }
        if (actorRole == VenueRole.STAFF) {
            val safeSelfEdit =
                current.linkedUserId == actorUserId &&
                    currentLinkedRole == VenueRole.STAFF &&
                    requestedLinkedUserId == current.linkedUserId &&
                    selfEditOnlyRequest &&
                    !publishing &&
                    changedFields.all { it in STAFF_PROFILE_SELF_EDIT_FIELDS }
            return if (safeSelfEdit) {
                StaffProfileLinkValidation.ALLOWED
            } else {
                StaffProfileLinkValidation.PROTECTED
            }
        }
        val safeManagerSelfEdit =
            current.linkedUserId == actorUserId &&
                currentLinkedRole == VenueRole.MANAGER &&
                requestedLinkedUserId == current.linkedUserId &&
                selfEditOnlyRequest &&
                !publishing &&
                changedFields.all { it in STAFF_PROFILE_SELF_EDIT_FIELDS }
        if (safeManagerSelfEdit) {
            return StaffProfileLinkValidation.ALLOWED
        }
        val currentManageable =
            current.linkedUserId == null || currentLinkedRole == VenueRole.STAFF
        if (!currentManageable) {
            return StaffProfileLinkValidation.PROTECTED
        }
        return validateRequestedLink(connection, venueId, requestedLinkedUserId, VenueRole.MANAGER)
    }

    private fun lockMembershipRole(
        connection: Connection,
        venueId: Long,
        userId: Long,
    ): VenueRole? =
        connection.prepareStatement(
            """
            SELECT role
            FROM venue_members
            WHERE venue_id = ? AND user_id = ?
            FOR UPDATE
            """.trimIndent(),
        ).use { statement ->
            statement.setLong(1, venueId)
            statement.setLong(2, userId)
            statement.executeQuery().use { rs ->
                if (rs.next()) VenueRoleMapping.fromDb(rs.getString("role")) else null
            }
        }

    private fun findActiveLinkConflict(
        connection: Connection,
        venueId: Long,
        linkedUserId: Long,
        excludedProfileId: Long? = null,
    ): StaffProfileMutationResult.LinkConflict? {
        val excludedProfileFilter = if (excludedProfileId == null) "" else "AND id <> ?"
        val profiles =
            connection.prepareStatement(
                """
                SELECT id
                FROM staff_profiles
                WHERE venue_id = ?
                  AND linked_user_id = ?
                  AND disabled_at IS NULL
                  $excludedProfileFilter
                ORDER BY id
                LIMIT 2
                """.trimIndent(),
            ).use { statement ->
                statement.setLong(1, venueId)
                statement.setLong(2, linkedUserId)
                if (excludedProfileId != null) statement.setLong(3, excludedProfileId)
                statement.executeQuery().use { rs ->
                    buildList {
                        while (rs.next()) {
                            add(rs.getLong("id"))
                        }
                    }
                }
            }
        return when {
            profiles.isEmpty() -> null
            profiles.size == 1 ->
                StaffProfileMutationResult.LinkConflict(
                    profileLinkState = VenueStaffProfileLinkState.LINKED,
                    linkedStaffProfileId = profiles.single(),
                )
            else ->
                StaffProfileMutationResult.LinkConflict(
                    profileLinkState = VenueStaffProfileLinkState.DUPLICATE_LINK_DETECTED,
                    linkedStaffProfileId = null,
                )
        }
    }

    private fun countActiveLinks(
        connection: Connection,
        venueId: Long,
        linkedUserId: Long,
    ): Int =
        connection.prepareStatement(
            """
            SELECT COUNT(*)
            FROM staff_profiles
            WHERE venue_id = ?
              AND linked_user_id = ?
              AND disabled_at IS NULL
            """.trimIndent(),
        ).use { statement ->
            statement.setLong(1, venueId)
            statement.setLong(2, linkedUserId)
            statement.executeQuery().use { rs ->
                check(rs.next())
                rs.getInt(1)
            }
        }

    private fun changedProfileFields(
        current: VenueStaffProfile,
        input: StaffProfileWrite,
    ): Set<String> =
        buildSet {
            if (current.linkedUserId != input.linkedUserId) add(STAFF_PROFILE_LINKAGE_FIELD)
            if (current.displayName != input.displayName) add("displayName")
            if (current.roleLabel != input.roleLabel) add("roleLabel")
            if (current.subtype != input.subtype) add("subtype")
            if (current.photoRef != input.photoRef) add("photoRef")
            if (current.bio != input.bio) add("bio")
            if (current.tags != input.tags) add("tags")
            if (current.isGuestVisible != input.isGuestVisible) add(STAFF_PROFILE_VISIBILITY_FIELD)
        }

    private fun profileCreationFieldNames(input: StaffProfileWrite): Set<String> =
        buildSet {
            add("displayName")
            add("subtype")
            if (input.linkedUserId != null) add(STAFF_PROFILE_LINKAGE_FIELD)
            if (input.roleLabel != null) add("roleLabel")
            if (input.photoRef != null) add("photoRef")
            if (input.bio != null) add("bio")
            if (input.tags.isNotEmpty()) add("tags")
            if (input.isGuestVisible) add(STAFF_PROFILE_VISIBILITY_FIELD)
        }

    private fun appendStaffProfileAudit(
        connection: Connection,
        auditLogRepository: AuditLogRepository,
        actorUserId: Long,
        action: String,
        old: VenueStaffProfile?,
        new: VenueStaffProfile,
        changedFields: Set<String>,
    ) {
        val oldLinkage = old?.let { profileLinkageClass(connection, it.venueId, it.linkedUserId) }
        val newLinkage = profileLinkageClass(connection, new.venueId, new.linkedUserId)
        val newTargetRole = new.linkedUserId?.let { lockMembershipRole(connection, new.venueId, it) }
        auditLogRepository.appendJson(
            connection = connection,
            actorUserId = actorUserId,
            action = action,
            entityType = STAFF_PROFILE_AUDIT_ENTITY_TYPE,
            entityId = new.id,
            payload =
                buildJsonObject {
                    put("venueId", new.venueId)
                    put("staffProfileId", new.id)
                    put(
                        "changedFields",
                        JsonArray(changedFields.sorted().map { JsonPrimitive(it) }),
                    )
                    putNullableString("oldLinkageClass", oldLinkage?.name)
                    put("newLinkageClass", newLinkage.name)
                    if (newTargetRole == VenueRole.STAFF || newTargetRole == VenueRole.MANAGER) {
                        put("targetRole", newTargetRole.name)
                    }
                    putNullableBoolean("oldPublished", old?.isPublished())
                    put("newPublished", new.isPublished())
                    putNullableBoolean("oldHidden", old?.let { !it.isPublished() })
                    put("newHidden", !new.isPublished())
                },
        )
    }

    private fun profileLinkageClass(
        connection: Connection,
        venueId: Long,
        linkedUserId: Long?,
    ): StaffProfileLinkageClass {
        if (linkedUserId == null) return StaffProfileLinkageClass.DISPLAY_ONLY
        return if (lockMembershipRole(connection, venueId, linkedUserId) == VenueRole.STAFF) {
            StaffProfileLinkageClass.STAFF_LINKED
        } else {
            StaffProfileLinkageClass.PROTECTED
        }
    }

    private fun authorizeTodayShiftMutation(
        connection: Connection,
        actorRole: VenueRole,
        profile: VenueStaffProfile,
    ): Boolean =
        when (actorRole) {
            VenueRole.OWNER -> true
            VenueRole.MANAGER ->
                profileLinkageClass(connection, profile.venueId, profile.linkedUserId) in
                    MANAGER_TODAY_SHIFT_ALLOWED_LINKAGES
            VenueRole.STAFF -> false
        }

    private fun VenueStaffProfile.isPublished(): Boolean = isGuestVisible && publishedAt != null && disabledAt == null

    suspend fun upsertTodayShift(
        venueId: Long,
        staffProfileId: Long,
        shiftDate: LocalDate,
        actorUserId: Long,
        actorRole: VenueRole,
        input: StaffShiftWrite,
        auditLogRepository: AuditLogRepository,
    ): StaffTodayShiftMutationResult =
        inTransaction { connection ->
            if (!actorRoleStillApplies(connection, venueId, actorUserId, actorRole)) {
                return@inTransaction StaffTodayShiftMutationResult.Forbidden
            }
            val profile =
                findProfileInConnection(connection, venueId, staffProfileId, forUpdate = true)
                    ?: return@inTransaction StaffTodayShiftMutationResult.NotFound
            if (!authorizeTodayShiftMutation(connection, actorRole, profile)) {
                return@inTransaction StaffTodayShiftMutationResult.Forbidden
            }
            val existing =
                findShiftForUpdateInConnection(
                    connection = connection,
                    venueId = venueId,
                    staffProfileId = staffProfileId,
                    shiftDate = shiftDate,
                )
            val shift =
                if (existing == null) {
                    insertShiftInConnection(
                        connection = connection,
                        venueId = venueId,
                        staffProfileId = staffProfileId,
                        shiftDate = shiftDate,
                        actorUserId = actorUserId,
                        input = input,
                    )
                } else {
                    updateTodayShiftInConnection(
                        connection = connection,
                        existing = existing,
                        actorUserId = actorUserId,
                        input = input,
                    )
                }
            appendStaffTodayShiftAudit(
                connection = connection,
                auditLogRepository = auditLogRepository,
                actorUserId = actorUserId,
                shift = shift,
            )
            StaffTodayShiftMutationResult.Success(shift)
        }

    suspend fun listScheduledShifts(
        venueId: Long,
        from: LocalDate,
        to: LocalDate,
    ): List<VenueStaffScheduledShift> {
        val ds = dataSource ?: throw DatabaseUnavailableException()
        return withContext(Dispatchers.IO) {
            try {
                ds.connection.use { connection ->
                    connection.prepareStatement(
                        """
                        SELECT
                            $scheduleShiftSelectColumns
                        FROM staff_shifts ss
                        JOIN staff_profiles sp
                          ON sp.id = ss.staff_profile_id
                         AND sp.venue_id = ss.venue_id
                        WHERE ss.venue_id = ?
                          AND ss.shift_date BETWEEN ? AND ?
                        ORDER BY ss.shift_date, ss.starts_at NULLS LAST, sp.display_name, ss.id
                        """.trimIndent(),
                    ).use { statement ->
                        statement.setLong(1, venueId)
                        statement.setObject(2, from)
                        statement.setObject(3, to)
                        statement.executeQuery().use { rs ->
                            buildList {
                                while (rs.next()) {
                                    add(rs.toScheduledShift())
                                }
                            }
                        }
                    }
                }
            } catch (e: SQLException) {
                throw DatabaseUnavailableException()
            }
        }
    }

    suspend fun createScheduledShift(
        venueId: Long,
        staffProfileId: Long,
        shiftDate: LocalDate,
        startsAt: LocalTime,
        endsAt: LocalTime,
        actorUserId: Long,
        now: Instant,
        zoneId: ZoneId,
        auditLogRepository: AuditLogRepository,
    ): VenueStaffScheduledShift? =
        inTransaction { connection ->
            val profile =
                findProfileInConnection(connection, venueId, staffProfileId, forUpdate = true)
                    ?: return@inTransaction null
            findShiftForUpdateInConnection(connection, venueId, staffProfileId, shiftDate)?.let { existing ->
                throwScheduleCreateConflict(
                    current = VenueStaffScheduledShift(profile, existing),
                    now = now,
                    venueToday = LocalDate.ofInstant(now, zoneId),
                    zoneId = zoneId,
                )
            }
            val created =
                insertScheduledShiftInConnection(
                    connection = connection,
                    venueId = venueId,
                    staffProfileId = staffProfileId,
                    shiftDate = shiftDate,
                    startsAt = startsAt,
                    endsAt = endsAt,
                    actorUserId = actorUserId,
                    updatedAt = now,
                )
            val interval =
                resolveStaffScheduleInterval(shiftDate, startsAt, endsAt, zoneId).interval
                    ?: throw StaffShiftInvalidIntervalException()
            val lifecycle = computeStaffScheduleLifecycle(created.shift.status, interval, now)
            appendStaffScheduleAudit(
                connection = connection,
                auditLogRepository = auditLogRepository,
                actorUserId = actorUserId,
                action = STAFF_SHIFT_CREATED_AUDIT_ACTION,
                old = null,
                new = created,
                oldLifecycle = null,
                newLifecycle = lifecycle,
                zoneId = zoneId,
            )
            created
        }

    suspend fun restoreScheduledShift(
        venueId: Long,
        shiftId: Long,
        startsAt: LocalTime?,
        endsAt: LocalTime?,
        expectedUpdatedAt: Instant,
        actorUserId: Long,
        now: Instant,
        venueToday: LocalDate,
        zoneId: ZoneId,
        auditLogRepository: AuditLogRepository,
    ): VenueStaffScheduledShift? =
        inTransaction { connection ->
            if ((startsAt == null) != (endsAt == null)) {
                throw InvalidInputException("startsAt и endsAt нужно передать вместе.")
            }
            val current =
                findScheduledShiftInConnection(connection, venueId, shiftId, forUpdate = true)
                    ?: return@inTransaction null
            validateRestorableShift(
                current = current,
                expectedUpdatedAt = expectedUpdatedAt,
                now = now,
                venueToday = venueToday,
                zoneId = zoneId,
            )
            val restoredStartsAt = startsAt ?: current.shift.startsAt ?: throw StaffShiftInvalidIntervalException()
            val restoredEndsAt = endsAt ?: current.shift.endsAt ?: throw StaffShiftInvalidIntervalException()
            validateRestoredInterval(
                shiftDate = current.shift.shiftDate,
                startsAt = restoredStartsAt,
                endsAt = restoredEndsAt,
                now = now,
                venueToday = venueToday,
                zoneId = zoneId,
            )
            val restored =
                restoreScheduledShiftInConnection(
                    connection = connection,
                    current = current,
                    startsAt = restoredStartsAt,
                    endsAt = restoredEndsAt,
                    expectedUpdatedAt = expectedUpdatedAt,
                    actorUserId = actorUserId,
                    now = now,
                )
            appendStaffScheduleAudit(
                connection = connection,
                auditLogRepository = auditLogRepository,
                actorUserId = actorUserId,
                action = STAFF_SHIFT_RESTORED_AUDIT_ACTION,
                old = current,
                new = restored,
                oldLifecycle = StaffScheduleLifecycle.CANCELED,
                newLifecycle = StaffScheduleLifecycle.SCHEDULED,
                zoneId = zoneId,
            )
            restored
        }

    suspend fun mutateScheduledShiftsBatch(
        venueId: Long,
        assignments: List<StaffScheduleBatchAssignment>,
        actorUserId: Long,
        now: Instant,
        venueToday: LocalDate,
        zoneId: ZoneId,
        auditLogRepository: AuditLogRepository,
    ): List<VenueStaffScheduledShift> =
        inTransaction { connection ->
            val orderedAssignments = assignments.sortedWith(staffScheduleBatchAssignmentOrder)
            if (orderedAssignments.map { it.slot }.distinct().size != orderedAssignments.size) {
                throw InvalidInputException("В batch есть повторяющиеся сотрудник и дата.")
            }

            val profilesById =
                orderedAssignments
                    .map { it.staffProfileId }
                    .distinct()
                    .associateWith { staffProfileId ->
                        findProfileInConnection(connection, venueId, staffProfileId, forUpdate = true)
                            ?: throw NotFoundException()
                    }

            val currentBySlot =
                orderedAssignments.associate { assignment ->
                    assignment.slot to
                        findShiftForUpdateInConnection(
                            connection = connection,
                            venueId = venueId,
                            staffProfileId = assignment.staffProfileId,
                            shiftDate = assignment.shiftDate,
                        )?.let { VenueStaffScheduledShift(checkNotNull(profilesById[assignment.staffProfileId]), it) }
                }

            orderedAssignments.forEach { assignment ->
                val current = currentBySlot[assignment.slot]
                when (assignment.operation) {
                    StaffScheduleBatchOperation.CREATE -> {
                        if (current != null) {
                            throwScheduleCreateConflict(current, now, venueToday, zoneId)
                        }
                    }
                    StaffScheduleBatchOperation.RESTORE -> {
                        val existing = current ?: throw NotFoundException()
                        validateRestorableShift(
                            current = existing,
                            expectedUpdatedAt = assignment.expectedUpdatedAt ?: throw StaffShiftStaleException(),
                            now = now,
                            venueToday = venueToday,
                            zoneId = zoneId,
                        )
                    }
                }
                validateRestoredInterval(
                    shiftDate = assignment.shiftDate,
                    startsAt = assignment.startsAt,
                    endsAt = assignment.endsAt,
                    now = now,
                    venueToday = venueToday,
                    zoneId = zoneId,
                )
            }

            val mutations =
                orderedAssignments.map { assignment ->
                    when (assignment.operation) {
                        StaffScheduleBatchOperation.CREATE -> {
                            val created =
                                insertScheduledShiftInConnection(
                                    connection = connection,
                                    venueId = venueId,
                                    staffProfileId = assignment.staffProfileId,
                                    shiftDate = assignment.shiftDate,
                                    startsAt = assignment.startsAt,
                                    endsAt = assignment.endsAt,
                                    actorUserId = actorUserId,
                                    updatedAt = now,
                                )
                            StaffSchedulePendingAudit(
                                action = STAFF_SHIFT_CREATED_AUDIT_ACTION,
                                old = null,
                                new = created,
                                oldLifecycle = null,
                                newLifecycle = StaffScheduleLifecycle.SCHEDULED,
                            )
                        }
                        StaffScheduleBatchOperation.RESTORE -> {
                            val current = checkNotNull(currentBySlot[assignment.slot])
                            val restored =
                                restoreScheduledShiftInConnection(
                                    connection = connection,
                                    current = current,
                                    startsAt = assignment.startsAt,
                                    endsAt = assignment.endsAt,
                                    expectedUpdatedAt = checkNotNull(assignment.expectedUpdatedAt),
                                    actorUserId = actorUserId,
                                    now = now,
                                )
                            StaffSchedulePendingAudit(
                                action = STAFF_SHIFT_RESTORED_AUDIT_ACTION,
                                old = current,
                                new = restored,
                                oldLifecycle = StaffScheduleLifecycle.CANCELED,
                                newLifecycle = StaffScheduleLifecycle.SCHEDULED,
                            )
                        }
                    }
                }

            mutations.forEach { mutation ->
                appendStaffScheduleAudit(
                    connection = connection,
                    auditLogRepository = auditLogRepository,
                    actorUserId = actorUserId,
                    action = mutation.action,
                    old = mutation.old,
                    new = mutation.new,
                    oldLifecycle = mutation.oldLifecycle,
                    newLifecycle = mutation.newLifecycle,
                    zoneId = zoneId,
                )
            }
            val shiftsBySlot = mutations.associate { it.new.shift.slot to it.new }
            assignments.map { assignment -> checkNotNull(shiftsBySlot[assignment.slot]) }
        }

    suspend fun updateScheduledShift(
        venueId: Long,
        shiftId: Long,
        shiftDate: LocalDate,
        startsAt: LocalTime,
        endsAt: LocalTime,
        expectedUpdatedAt: Instant,
        actorUserId: Long,
        now: Instant,
        venueToday: LocalDate,
        zoneId: ZoneId,
        auditLogRepository: AuditLogRepository,
    ): VenueStaffScheduledShift? =
        inTransaction { connection ->
            val current =
                findScheduledShiftInConnection(connection, venueId, shiftId, forUpdate = true)
                    ?: return@inTransaction null
            if (current.shift.updatedAt != expectedUpdatedAt) {
                throw StaffShiftStaleException()
            }
            val oldResolution =
                resolveStaffScheduleInterval(
                    shiftDate = current.shift.shiftDate,
                    startsAt = current.shift.startsAt,
                    endsAt = current.shift.endsAt,
                    zoneId = zoneId,
                )
            val oldLifecycle =
                when (oldResolution.state) {
                    StaffScheduleIntervalState.INCOMPLETE -> return@inTransaction null
                    StaffScheduleIntervalState.INVALID -> {
                        if (current.shift.status.equals(STAFF_SHIFT_CANCELED_STATUS, ignoreCase = true) ||
                            !current.shift.shiftDate.isAfter(venueToday)
                        ) {
                            throw StaffShiftImmutableException()
                        }
                        if (!current.shift.hasScheduleDefaults()) {
                            throw StaffShiftTodayOverrideException()
                        }
                        null
                    }
                    StaffScheduleIntervalState.VALID -> {
                        val lifecycle =
                            computeStaffScheduleLifecycle(
                                storedStatus = current.shift.status,
                                interval = checkNotNull(oldResolution.interval),
                                now = now,
                            )
                        if (lifecycle != StaffScheduleLifecycle.SCHEDULED) {
                            throw StaffShiftImmutableException()
                        }
                        if (!current.shift.hasScheduleDefaults()) {
                            throw StaffShiftTodayOverrideException()
                        }
                        lifecycle
                    }
                }
            if (shiftDate.isAfter(venueToday.plusDays(STAFF_SCHEDULE_FUTURE_DAYS))) {
                throw InvalidInputException("Смену можно запланировать не более чем на 90 дней вперёд.")
            }
            val newResolution = resolveStaffScheduleInterval(shiftDate, startsAt, endsAt, zoneId)
            val newInterval =
                newResolution.interval
                    ?.takeIf { newResolution.state == StaffScheduleIntervalState.VALID }
                    ?: throw StaffShiftInvalidIntervalException()
            if (!newInterval.startsAt.isAfter(now)) {
                throw InvalidInputException("Начало смены должно быть в будущем.")
            }
            if (current.shift.shiftDate == shiftDate &&
                current.shift.startsAt == startsAt &&
                current.shift.endsAt == endsAt
            ) {
                return@inTransaction current
            }
            val nextUpdatedAt = nextStaffShiftUpdatedAt(now, current.shift.updatedAt)
            val updatedCount =
                try {
                    connection.prepareStatement(
                        """
                        UPDATE staff_shifts
                        SET shift_date = ?,
                            starts_at = ?,
                            ends_at = ?,
                            updated_by_user_id = ?,
                            updated_at = ?
                        WHERE venue_id = ?
                          AND id = ?
                          AND updated_at = ?
                        """.trimIndent(),
                    ).use { statement ->
                        statement.setObject(1, shiftDate)
                        statement.setObject(2, startsAt)
                        statement.setObject(3, endsAt)
                        statement.setLong(4, actorUserId)
                        statement.setTimestamp(5, Timestamp.from(nextUpdatedAt))
                        statement.setLong(6, venueId)
                        statement.setLong(7, shiftId)
                        statement.setTimestamp(8, Timestamp.from(expectedUpdatedAt))
                        statement.executeUpdate()
                    }
                } catch (e: SQLException) {
                    if (e.isUniqueViolation()) {
                        throw StaffShiftDateConflictException()
                    }
                    throw e
                }
            if (updatedCount != 1) {
                throw StaffShiftStaleException()
            }
            val updated =
                findScheduledShiftInConnection(connection, venueId, shiftId, forUpdate = false)
                    ?: throw DatabaseUnavailableException()
            val newLifecycle = computeStaffScheduleLifecycle(updated.shift.status, newInterval, now)
            appendStaffScheduleAudit(
                connection = connection,
                auditLogRepository = auditLogRepository,
                actorUserId = actorUserId,
                action = STAFF_SHIFT_UPDATED_AUDIT_ACTION,
                old = current,
                new = updated,
                oldLifecycle = oldLifecycle,
                newLifecycle = newLifecycle,
                zoneId = zoneId,
                oldValidationState =
                    STAFF_SHIFT_INVALID_INTERVAL_VALIDATION_STATE.takeIf {
                        oldResolution.state == StaffScheduleIntervalState.INVALID
                    },
            )
            updated
        }

    suspend fun cancelScheduledShift(
        venueId: Long,
        shiftId: Long,
        expectedUpdatedAt: Instant,
        expectedConfirmationState: StaffScheduleConfirmationState,
        actorUserId: Long,
        now: Instant,
        venueToday: LocalDate,
        zoneId: ZoneId,
        auditLogRepository: AuditLogRepository,
    ): VenueStaffScheduledShift? =
        inTransaction { connection ->
            val current =
                findScheduledShiftInConnection(connection, venueId, shiftId, forUpdate = true)
                    ?: return@inTransaction null
            if (current.shift.updatedAt != expectedUpdatedAt) {
                throw StaffShiftStaleException()
            }
            if (current.shift.status.equals(STAFF_SHIFT_CANCELED_STATUS, ignoreCase = true)) {
                throw StaffShiftImmutableException()
            }
            val oldResolution =
                resolveStaffScheduleInterval(
                    shiftDate = current.shift.shiftDate,
                    startsAt = current.shift.startsAt,
                    endsAt = current.shift.endsAt,
                    zoneId = zoneId,
                )
            val oldLifecycle: StaffScheduleLifecycle?
            val actualConfirmationState: StaffScheduleConfirmationState
            when (oldResolution.state) {
                StaffScheduleIntervalState.INCOMPLETE -> return@inTransaction null
                StaffScheduleIntervalState.INVALID -> {
                    if (StaffScheduleAllowedAction.CANCEL !in
                        invalidStaffScheduleAllowedActions(current.shift, venueToday)
                    ) {
                        throw StaffShiftImmutableException()
                    }
                    oldLifecycle = null
                    actualConfirmationState = StaffScheduleConfirmationState.INVALID_INTERVAL
                }
                StaffScheduleIntervalState.VALID -> {
                    oldLifecycle =
                        computeStaffScheduleLifecycle(
                            storedStatus = current.shift.status,
                            interval = checkNotNull(oldResolution.interval),
                            now = now,
                        )
                    if (oldLifecycle == StaffScheduleLifecycle.COMPLETED ||
                        oldLifecycle == StaffScheduleLifecycle.CANCELED
                    ) {
                        throw StaffShiftImmutableException()
                    }
                    actualConfirmationState =
                        staffScheduleConfirmationState(oldLifecycle)
                            ?: throw StaffShiftImmutableException()
                }
            }
            if (expectedConfirmationState != actualConfirmationState) {
                throw StaffShiftConfirmationStaleException()
            }
            val nextUpdatedAt = nextStaffShiftUpdatedAt(now, current.shift.updatedAt)
            val updatedCount =
                connection.prepareStatement(
                    """
                    UPDATE staff_shifts
                    SET status = ?,
                        updated_by_user_id = ?,
                        updated_at = ?
                    WHERE venue_id = ?
                      AND id = ?
                      AND updated_at = ?
                    """.trimIndent(),
                ).use { statement ->
                    statement.setString(1, STAFF_SHIFT_CANCELED_STATUS)
                    statement.setLong(2, actorUserId)
                    statement.setTimestamp(3, Timestamp.from(nextUpdatedAt))
                    statement.setLong(4, venueId)
                    statement.setLong(5, shiftId)
                    statement.setTimestamp(6, Timestamp.from(expectedUpdatedAt))
                    statement.executeUpdate()
                }
            if (updatedCount != 1) {
                throw StaffShiftStaleException()
            }
            val updated =
                findScheduledShiftInConnection(connection, venueId, shiftId, forUpdate = false)
                    ?: throw DatabaseUnavailableException()
            appendStaffScheduleAudit(
                connection = connection,
                auditLogRepository = auditLogRepository,
                actorUserId = actorUserId,
                action = STAFF_SHIFT_CANCELED_AUDIT_ACTION,
                old = current,
                new = updated,
                oldLifecycle = oldLifecycle,
                newLifecycle = StaffScheduleLifecycle.CANCELED,
                zoneId = zoneId,
                oldValidationState =
                    STAFF_SHIFT_INVALID_INTERVAL_VALIDATION_STATE.takeIf {
                        oldResolution.state == StaffScheduleIntervalState.INVALID
                    },
            )
            updated
        }

    suspend fun listPublicTodayStaff(
        venueId: Long,
        shiftDate: LocalDate,
    ): List<PublicVenueStaffToday> {
        val ds = dataSource ?: throw DatabaseUnavailableException()
        return withContext(Dispatchers.IO) {
            try {
                ds.connection.use { connection ->
                    connection.prepareStatement(
                        """
                        SELECT
                            sp.id AS profile_id,
                            sp.display_name,
                            sp.role_label,
                            sp.subtype,
                            sp.photo_ref,
                            sp.bio,
                            sp.tags,
                            ss.id AS shift_id,
                            ss.shift_date,
                            ss.starts_at,
                            ss.ends_at,
                            ss.status AS shift_status,
                            ss.manually_marked_active
                        FROM staff_shifts ss
                        JOIN staff_profiles sp
                          ON sp.id = ss.staff_profile_id
                         AND sp.venue_id = ss.venue_id
                        WHERE ss.venue_id = ?
                          AND ss.shift_date = ?
                          AND ss.is_guest_visible = TRUE
                          AND ss.status IN ('scheduled', 'active')
                          AND sp.is_guest_visible = TRUE
                          AND sp.published_at IS NOT NULL
                          AND sp.disabled_at IS NULL
                        ORDER BY
                            CASE WHEN ss.status = 'active' THEN 0 ELSE 1 END,
                            ss.starts_at NULLS LAST,
                            sp.display_name,
                            sp.id
                        """.trimIndent(),
                    ).use { statement ->
                        statement.setLong(1, venueId)
                        statement.setObject(2, shiftDate)
                        statement.executeQuery().use { rs ->
                            val result = mutableListOf<PublicVenueStaffToday>()
                            while (rs.next()) {
                                result.add(
                                    PublicVenueStaffToday(
                                        id = rs.getLong("profile_id"),
                                        displayName = rs.getString("display_name"),
                                        roleLabel = rs.getString("role_label"),
                                        subtype = rs.getString("subtype"),
                                        photoRef = rs.getString("photo_ref"),
                                        bio = rs.getString("bio"),
                                        tags = decodeTags(rs.getString("tags")),
                                        shiftId = rs.getLong("shift_id"),
                                        shiftDate = rs.getObject("shift_date", LocalDate::class.java),
                                        startsAt = rs.getNullableLocalTime("starts_at"),
                                        endsAt = rs.getNullableLocalTime("ends_at"),
                                        shiftStatus = rs.getString("shift_status"),
                                        manuallyMarkedActive = rs.getBoolean("manually_marked_active"),
                                    ),
                                )
                            }
                            result
                        }
                    }
                }
            } catch (e: SQLException) {
                throw DatabaseUnavailableException()
            }
        }
    }

    private fun findProfileInConnection(
        connection: Connection,
        venueId: Long,
        profileId: Long,
        forUpdate: Boolean = false,
    ): VenueStaffProfile? =
        connection.prepareStatement(
            """
            SELECT
                id AS profile_id,
                venue_id AS profile_venue_id,
                linked_user_id,
                display_name,
                role_label,
                subtype,
                photo_ref,
                bio,
                tags,
                is_guest_visible AS profile_is_guest_visible,
                created_by_user_id AS profile_created_by_user_id,
                updated_by_user_id AS profile_updated_by_user_id,
                published_at,
                disabled_at,
                created_at AS profile_created_at,
                updated_at AS profile_updated_at
            FROM staff_profiles
            WHERE venue_id = ? AND id = ?
            ${if (forUpdate) "FOR UPDATE" else ""}
            """.trimIndent(),
        ).use { statement ->
            statement.setLong(1, venueId)
            statement.setLong(2, profileId)
            statement.executeQuery().use { rs ->
                if (rs.next()) {
                    rs.toStaffProfile()
                } else {
                    null
                }
            }
        }

    private fun findShiftForUpdateInConnection(
        connection: Connection,
        venueId: Long,
        staffProfileId: Long,
        shiftDate: LocalDate,
    ): VenueStaffShift? =
        connection.prepareStatement(
            """
            SELECT
                id AS shift_id,
                venue_id AS shift_venue_id,
                staff_profile_id,
                shift_date,
                starts_at,
                ends_at,
                status AS shift_status,
                is_guest_visible AS shift_is_guest_visible,
                manually_marked_active,
                created_by_user_id AS shift_created_by_user_id,
                updated_by_user_id AS shift_updated_by_user_id,
                created_at AS shift_created_at,
                updated_at AS shift_updated_at
            FROM staff_shifts
            WHERE venue_id = ? AND staff_profile_id = ? AND shift_date = ?
            FOR UPDATE
            """.trimIndent(),
        ).use { statement ->
            statement.setLong(1, venueId)
            statement.setLong(2, staffProfileId)
            statement.setObject(3, shiftDate)
            statement.executeQuery().use { rs ->
                if (rs.next()) {
                    rs.toStaffShift()
                } else {
                    null
                }
            }
        }

    private fun insertShiftInConnection(
        connection: Connection,
        venueId: Long,
        staffProfileId: Long,
        shiftDate: LocalDate,
        actorUserId: Long,
        input: StaffShiftWrite,
    ): VenueStaffShift {
        val shiftId =
            connection.prepareStatement(
                """
                INSERT INTO staff_shifts (
                    venue_id,
                    staff_profile_id,
                    shift_date,
                    starts_at,
                    ends_at,
                    status,
                    is_guest_visible,
                    manually_marked_active,
                    created_by_user_id,
                    updated_by_user_id
                )
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """.trimIndent(),
                Statement.RETURN_GENERATED_KEYS,
            ).use { statement ->
                statement.setLong(1, venueId)
                statement.setLong(2, staffProfileId)
                statement.setObject(3, shiftDate)
                statement.setNullableLocalTime(4, input.startsAt)
                statement.setNullableLocalTime(5, input.endsAt)
                statement.setString(6, input.status)
                statement.setBoolean(7, input.isGuestVisible)
                statement.setBoolean(8, input.manuallyMarkedActive)
                statement.setLong(9, actorUserId)
                statement.setLong(10, actorUserId)
                statement.executeUpdate()
                statement.generatedKeys.use { rs ->
                    if (rs.next()) {
                        rs.getLong(1)
                    } else {
                        throw DatabaseUnavailableException()
                    }
                }
            }
        return findShiftByIdInConnection(connection, shiftId) ?: throw DatabaseUnavailableException()
    }

    private fun updateTodayShiftInConnection(
        connection: Connection,
        existing: VenueStaffShift,
        actorUserId: Long,
        input: StaffShiftWrite,
    ): VenueStaffShift {
        val updatedAt = nextStaffShiftUpdatedAt(Instant.now(), existing.updatedAt)
        connection.prepareStatement(
            """
            UPDATE staff_shifts
            SET starts_at = ?,
                ends_at = ?,
                status = ?,
                is_guest_visible = ?,
                manually_marked_active = ?,
                updated_by_user_id = ?,
                updated_at = ?
            WHERE venue_id = ? AND id = ?
            """.trimIndent(),
        ).use { statement ->
            statement.setNullableLocalTime(1, input.startsAt ?: existing.startsAt)
            statement.setNullableLocalTime(2, input.endsAt ?: existing.endsAt)
            statement.setString(3, input.status)
            statement.setBoolean(4, input.isGuestVisible)
            statement.setBoolean(5, input.manuallyMarkedActive)
            statement.setLong(6, actorUserId)
            statement.setTimestamp(7, Timestamp.from(updatedAt))
            statement.setLong(8, existing.venueId)
            statement.setLong(9, existing.id)
            statement.executeUpdate()
        }
        return findShiftByIdInConnection(connection, existing.id) ?: throw DatabaseUnavailableException()
    }

    private fun insertScheduledShiftInConnection(
        connection: Connection,
        venueId: Long,
        staffProfileId: Long,
        shiftDate: LocalDate,
        startsAt: LocalTime,
        endsAt: LocalTime,
        actorUserId: Long,
        updatedAt: Instant,
    ): VenueStaffScheduledShift {
        val normalizedUpdatedAt = updatedAt.truncatedTo(java.time.temporal.ChronoUnit.MILLIS)
        val shiftId =
            try {
                connection.prepareStatement(
                    """
                    INSERT INTO staff_shifts (
                        venue_id,
                        staff_profile_id,
                        shift_date,
                        starts_at,
                        ends_at,
                        status,
                        is_guest_visible,
                        manually_marked_active,
                        created_by_user_id,
                        updated_by_user_id,
                        updated_at
                    )
                    VALUES (?, ?, ?, ?, ?, ?, FALSE, FALSE, ?, ?, ?)
                    """.trimIndent(),
                    Statement.RETURN_GENERATED_KEYS,
                ).use { statement ->
                    statement.setLong(1, venueId)
                    statement.setLong(2, staffProfileId)
                    statement.setObject(3, shiftDate)
                    statement.setObject(4, startsAt)
                    statement.setObject(5, endsAt)
                    statement.setString(6, STAFF_SHIFT_SCHEDULED_STATUS)
                    statement.setLong(7, actorUserId)
                    statement.setLong(8, actorUserId)
                    statement.setTimestamp(9, Timestamp.from(normalizedUpdatedAt))
                    statement.executeUpdate()
                    statement.generatedKeys.use { rs ->
                        if (rs.next()) rs.getLong(1) else throw DatabaseUnavailableException()
                    }
                }
            } catch (e: SQLException) {
                if (e.isUniqueViolation()) {
                    throw StaffShiftDateConflictException()
                }
                throw e
            }
        return findScheduledShiftInConnection(connection, venueId, shiftId, forUpdate = false)
            ?: throw DatabaseUnavailableException()
    }

    private fun restoreScheduledShiftInConnection(
        connection: Connection,
        current: VenueStaffScheduledShift,
        startsAt: LocalTime,
        endsAt: LocalTime,
        expectedUpdatedAt: Instant,
        actorUserId: Long,
        now: Instant,
    ): VenueStaffScheduledShift {
        val nextUpdatedAt = nextStaffShiftUpdatedAt(now, current.shift.updatedAt)
        val updatedCount =
            connection.prepareStatement(
                """
                UPDATE staff_shifts
                SET starts_at = ?,
                    ends_at = ?,
                    status = ?,
                    updated_by_user_id = ?,
                    updated_at = ?
                WHERE venue_id = ?
                  AND id = ?
                  AND status = ?
                  AND updated_at = ?
                """.trimIndent(),
            ).use { statement ->
                statement.setObject(1, startsAt)
                statement.setObject(2, endsAt)
                statement.setString(3, STAFF_SHIFT_SCHEDULED_STATUS)
                statement.setLong(4, actorUserId)
                statement.setTimestamp(5, Timestamp.from(nextUpdatedAt))
                statement.setLong(6, current.shift.venueId)
                statement.setLong(7, current.shift.id)
                statement.setString(8, STAFF_SHIFT_CANCELED_STATUS)
                statement.setTimestamp(9, Timestamp.from(expectedUpdatedAt))
                statement.executeUpdate()
            }
        if (updatedCount != 1) {
            throw StaffShiftStaleException()
        }
        return findScheduledShiftInConnection(
            connection = connection,
            venueId = current.shift.venueId,
            shiftId = current.shift.id,
            forUpdate = false,
        ) ?: throw DatabaseUnavailableException()
    }

    private fun validateRestorableShift(
        current: VenueStaffScheduledShift,
        expectedUpdatedAt: Instant,
        now: Instant,
        venueToday: LocalDate,
        zoneId: ZoneId,
    ) {
        if (current.shift.updatedAt != expectedUpdatedAt) {
            throw StaffShiftStaleException()
        }
        val details = staffShiftConflictDetails(current, now, venueToday, zoneId)
        if (!current.shift.status.equals(STAFF_SHIFT_CANCELED_STATUS, ignoreCase = true)) {
            throw StaffShiftImmutableException(details)
        }
        if (!current.shift.hasScheduleVisibilityDefaults()) {
            throw StaffShiftTodayOverrideException(details)
        }
        if (!current.shift.isRestorableCanceledShift(now, venueToday, zoneId)) {
            throw StaffShiftImmutableException(details)
        }
    }

    private fun validateRestoredInterval(
        shiftDate: LocalDate,
        startsAt: LocalTime,
        endsAt: LocalTime,
        now: Instant,
        venueToday: LocalDate,
        zoneId: ZoneId,
    ) {
        if (shiftDate.isAfter(venueToday.plusDays(STAFF_SCHEDULE_FUTURE_DAYS))) {
            throw InvalidInputException("Смену можно запланировать не более чем на 90 дней вперёд.")
        }
        val interval =
            resolveStaffScheduleInterval(shiftDate, startsAt, endsAt, zoneId).interval
                ?: throw StaffShiftInvalidIntervalException()
        if (!interval.startsAt.isAfter(now)) {
            throw InvalidInputException("Начало смены должно быть в будущем.")
        }
    }

    private fun throwScheduleCreateConflict(
        current: VenueStaffScheduledShift,
        now: Instant,
        venueToday: LocalDate,
        zoneId: ZoneId,
    ): Nothing {
        val details = staffShiftConflictDetails(current, now, venueToday, zoneId)
        if (current.shift.status.equals(STAFF_SHIFT_CANCELED_STATUS, ignoreCase = true)) {
            throw StaffShiftCanceledConflictException(details)
        }
        val lifecycle = resolveStoredShiftLifecycle(current.shift, now, zoneId)
        when (lifecycle) {
            StaffScheduleLifecycle.SCHEDULED ->
                throw StaffShiftDateConflictException(
                    message = "Смена уже запланирована на эту дату.",
                    details = details,
                )
            StaffScheduleLifecycle.ACTIVE,
            StaffScheduleLifecycle.COMPLETED,
            StaffScheduleLifecycle.CANCELED,
            null,
            -> throw StaffShiftImmutableException(details)
        }
    }

    private fun staffShiftConflictDetails(
        current: VenueStaffScheduledShift,
        now: Instant,
        venueToday: LocalDate,
        zoneId: ZoneId,
    ) = buildJsonObject {
        val resolution =
            resolveStaffScheduleInterval(
                current.shift.shiftDate,
                current.shift.startsAt,
                current.shift.endsAt,
                zoneId,
            )
        val lifecycle = resolveStoredShiftLifecycle(current.shift, now, zoneId)
        put("existingShiftId", current.shift.id)
        put("staffProfileId", current.shift.staffProfileId)
        put("status", lifecycle?.name ?: current.shift.status.uppercase(java.util.Locale.ROOT))
        put("expectedUpdatedAt", current.shift.updatedAt.toString())
        put("shiftDate", current.shift.shiftDate.toString())
        putNullableString("startsAt", current.shift.startsAt?.toString())
        putNullableString("endsAt", current.shift.endsAt?.toString())
        put("endsNextDay", resolution.interval?.endsNextDay ?: false)
        put("canRestore", current.shift.isRestorableCanceledShift(now, venueToday, zoneId))
    }

    private fun resolveStoredShiftLifecycle(
        shift: VenueStaffShift,
        now: Instant,
        zoneId: ZoneId,
    ): StaffScheduleLifecycle? {
        if (shift.status.equals(STAFF_SHIFT_CANCELED_STATUS, ignoreCase = true)) {
            return StaffScheduleLifecycle.CANCELED
        }
        val interval = resolveStaffScheduleInterval(shift.shiftDate, shift.startsAt, shift.endsAt, zoneId).interval
        return interval?.let { computeStaffScheduleLifecycle(shift.status, it, now) }
    }

    private fun appendStaffTodayShiftAudit(
        connection: Connection,
        auditLogRepository: AuditLogRepository,
        actorUserId: Long,
        shift: VenueStaffShift,
    ) {
        auditLogRepository.appendJson(
            connection = connection,
            actorUserId = actorUserId,
            action = "$STAFF_TODAY_SHIFT_AUDIT_ACTION_PREFIX${shift.status}",
            entityType = STAFF_SHIFT_AUDIT_ENTITY_TYPE,
            entityId = shift.id,
            payload =
                buildJsonObject {
                    put("venueId", shift.venueId)
                    put("profileId", shift.staffProfileId)
                    put("shiftId", shift.id)
                    put("status", shift.status)
                },
        )
    }

    private fun findScheduledShiftInConnection(
        connection: Connection,
        venueId: Long,
        shiftId: Long,
        forUpdate: Boolean,
    ): VenueStaffScheduledShift? {
        if (forUpdate && !lockScheduledShiftInConnection(connection, venueId, shiftId)) {
            return null
        }
        return connection.prepareStatement(
            """
            SELECT
                $scheduleShiftSelectColumns
            FROM staff_shifts ss
            JOIN staff_profiles sp
              ON sp.id = ss.staff_profile_id
             AND sp.venue_id = ss.venue_id
            WHERE ss.venue_id = ? AND ss.id = ?
            """.trimIndent(),
        ).use { statement ->
            statement.setLong(1, venueId)
            statement.setLong(2, shiftId)
            statement.executeQuery().use { rs ->
                if (rs.next()) rs.toScheduledShift() else null
            }
        }
    }

    private fun lockScheduledShiftInConnection(
        connection: Connection,
        venueId: Long,
        shiftId: Long,
    ): Boolean =
        connection.prepareStatement(
            """
            SELECT id
            FROM staff_shifts
            WHERE venue_id = ? AND id = ?
            FOR UPDATE
            """.trimIndent(),
        ).use { statement ->
            statement.setLong(1, venueId)
            statement.setLong(2, shiftId)
            statement.executeQuery().use { it.next() }
        }

    private fun appendStaffScheduleAudit(
        connection: Connection,
        auditLogRepository: AuditLogRepository,
        actorUserId: Long,
        action: String,
        old: VenueStaffScheduledShift?,
        new: VenueStaffScheduledShift,
        oldLifecycle: StaffScheduleLifecycle?,
        newLifecycle: StaffScheduleLifecycle,
        zoneId: ZoneId,
        oldValidationState: String? = null,
    ) {
        auditLogRepository.appendJson(
            connection = connection,
            actorUserId = actorUserId,
            action = action,
            entityType = STAFF_SHIFT_AUDIT_ENTITY_TYPE,
            entityId = new.shift.id,
            payload =
                buildJsonObject {
                    put("venueId", new.shift.venueId)
                    put("staffProfileId", new.shift.staffProfileId)
                    put("shiftId", new.shift.id)
                    putNullableString("oldShiftDate", old?.shift?.shiftDate?.toString())
                    put("newShiftDate", new.shift.shiftDate.toString())
                    putNullableString("oldStartsAt", old?.shift?.startsAt?.toString())
                    putNullableString("newStartsAt", new.shift.startsAt?.toString())
                    putNullableString("oldEndsAt", old?.shift?.endsAt?.toString())
                    putNullableString("newEndsAt", new.shift.endsAt?.toString())
                    putNullableString("oldLifecycle", oldLifecycle?.name)
                    put("newLifecycle", newLifecycle.name)
                    putNullableBoolean(
                        "oldCanceled",
                        old?.shift?.status?.equals(STAFF_SHIFT_CANCELED_STATUS, ignoreCase = true),
                    )
                    put("newCanceled", new.shift.status.equals(STAFF_SHIFT_CANCELED_STATUS, ignoreCase = true))
                    put("venueTimezone", zoneId.id)
                    if (oldValidationState != null) {
                        put("oldValidationState", oldValidationState)
                    }
                },
        )
    }

    private fun ResultSet.toScheduledShift(): VenueStaffScheduledShift =
        VenueStaffScheduledShift(
            profile = toStaffProfile(),
            shift = toStaffShift(),
        )

    private suspend fun <T> inTransaction(block: (Connection) -> T): T {
        val ds = dataSource ?: throw DatabaseUnavailableException()
        return withContext(Dispatchers.IO) {
            try {
                ds.connection.use { connection ->
                    val initialAutoCommit = connection.autoCommit
                    connection.autoCommit = false
                    try {
                        val result = block(connection)
                        connection.commit()
                        result
                    } catch (e: Exception) {
                        runCatching { connection.rollback() }
                        throw e
                    } finally {
                        connection.autoCommit = initialAutoCommit
                    }
                }
            } catch (e: SQLException) {
                throw DatabaseUnavailableException()
            }
        }
    }

    private fun findShiftByIdInConnection(
        connection: Connection,
        shiftId: Long,
    ): VenueStaffShift? =
        connection.prepareStatement(
            """
            SELECT
                id AS shift_id,
                venue_id AS shift_venue_id,
                staff_profile_id,
                shift_date,
                starts_at,
                ends_at,
                status AS shift_status,
                is_guest_visible AS shift_is_guest_visible,
                manually_marked_active,
                created_by_user_id AS shift_created_by_user_id,
                updated_by_user_id AS shift_updated_by_user_id,
                created_at AS shift_created_at,
                updated_at AS shift_updated_at
            FROM staff_shifts
            WHERE id = ?
            """.trimIndent(),
        ).use { statement ->
            statement.setLong(1, shiftId)
            statement.executeQuery().use { rs ->
                if (rs.next()) {
                    rs.toStaffShift()
                } else {
                    null
                }
            }
        }

    private fun ResultSet.toStaffProfile(): VenueStaffProfile =
        VenueStaffProfile(
            id = getLong("profile_id"),
            venueId = getLong("profile_venue_id"),
            linkedUserId = getNullableLong("linked_user_id"),
            displayName = getString("display_name"),
            roleLabel = getString("role_label"),
            subtype = getString("subtype"),
            photoRef = getString("photo_ref"),
            bio = getString("bio"),
            tags = decodeTags(getString("tags")),
            isGuestVisible = getBoolean("profile_is_guest_visible"),
            createdByUserId = getLong("profile_created_by_user_id"),
            updatedByUserId = getNullableLong("profile_updated_by_user_id"),
            publishedAt = getNullableInstant("published_at"),
            disabledAt = getNullableInstant("disabled_at"),
            createdAt = getNullableInstant("profile_created_at") ?: Instant.EPOCH,
            updatedAt = getNullableInstant("profile_updated_at") ?: Instant.EPOCH,
        )

    private fun ResultSet.toStaffShiftOrNull(): VenueStaffShift? {
        val id = getLong("shift_id")
        if (wasNull()) {
            return null
        }
        return toStaffShift(id)
    }

    private fun ResultSet.toStaffShift(id: Long = getLong("shift_id")): VenueStaffShift =
        VenueStaffShift(
            id = id,
            venueId = getLong("shift_venue_id"),
            staffProfileId = getLong("staff_profile_id"),
            shiftDate = getObject("shift_date", LocalDate::class.java),
            startsAt = getNullableLocalTime("starts_at"),
            endsAt = getNullableLocalTime("ends_at"),
            status = getString("shift_status"),
            isGuestVisible = getBoolean("shift_is_guest_visible"),
            manuallyMarkedActive = getBoolean("manually_marked_active"),
            createdByUserId = getLong("shift_created_by_user_id"),
            updatedByUserId = getNullableLong("shift_updated_by_user_id"),
            createdAt = getNullableInstant("shift_created_at") ?: Instant.EPOCH,
            updatedAt = getNullableInstant("shift_updated_at") ?: Instant.EPOCH,
        )

    private fun encodeTags(tags: List<String>): String? =
        tags.takeIf { it.isNotEmpty() }?.let { json.encodeToString(it) }

    private fun decodeTags(raw: String?): List<String> =
        raw
            ?.takeIf { it.isNotBlank() }
            ?.let { runCatching { json.decodeFromString<List<String>>(it) }.getOrNull() }
            ?: emptyList()
}

data class VenueStaffProfile(
    val id: Long,
    val venueId: Long,
    val linkedUserId: Long?,
    val displayName: String,
    val roleLabel: String?,
    val subtype: String,
    val photoRef: String?,
    val bio: String?,
    val tags: List<String>,
    val isGuestVisible: Boolean,
    val createdByUserId: Long,
    val updatedByUserId: Long?,
    val publishedAt: Instant?,
    val disabledAt: Instant?,
    val createdAt: Instant,
    val updatedAt: Instant,
)

data class VenueStaffShift(
    val id: Long,
    val venueId: Long,
    val staffProfileId: Long,
    val shiftDate: LocalDate,
    val startsAt: LocalTime?,
    val endsAt: LocalTime?,
    val status: String,
    val isGuestVisible: Boolean,
    val manuallyMarkedActive: Boolean,
    val createdByUserId: Long,
    val updatedByUserId: Long?,
    val createdAt: Instant,
    val updatedAt: Instant,
)

data class VenueStaffProfileWithTodayShift(
    val profile: VenueStaffProfile,
    val todayShift: VenueStaffShift?,
    val access: VenueStaffProfileAccess? = null,
)

data class VenueStaffProfileAccess(
    val linkageClass: VenueStaffProfileLinkageClass,
    val canManage: Boolean,
    val isSelf: Boolean,
    val linkedUserId: Long?,
)

enum class VenueStaffProfileLinkageClass {
    DISPLAY_ONLY,
    STAFF_LINKED,
    PROTECTED,
    DUPLICATE_LINK_DETECTED,
}

data class StaffProfileWrite(
    val linkedUserId: Long?,
    val displayName: String,
    val roleLabel: String?,
    val subtype: String,
    val photoRef: String?,
    val bio: String?,
    val tags: List<String>,
    val isGuestVisible: Boolean,
)

sealed interface StaffProfileMutationResult {
    data class Success(
        val profile: VenueStaffProfile,
        val changed: Boolean,
        val access: VenueStaffProfileAccess,
    ) : StaffProfileMutationResult

    data object NotFound : StaffProfileMutationResult

    data object InvalidLink : StaffProfileMutationResult

    data object Forbidden : StaffProfileMutationResult

    data class LinkConflict(
        val profileLinkState: VenueStaffProfileLinkState,
        val linkedStaffProfileId: Long?,
    ) : StaffProfileMutationResult
}

sealed interface StaffTodayShiftMutationResult {
    data class Success(
        val shift: VenueStaffShift,
    ) : StaffTodayShiftMutationResult

    data object NotFound : StaffTodayShiftMutationResult

    data object Forbidden : StaffTodayShiftMutationResult
}

private enum class StaffProfileLinkValidation {
    ALLOWED,
    INVALID,
    PROTECTED,
}

private data class StaffMemberIdentity(
    val username: String?,
    val firstName: String?,
    val lastName: String?,
)

private enum class StaffProfileLinkageClass {
    DISPLAY_ONLY,
    STAFF_LINKED,
    PROTECTED,
}

data class StaffShiftWrite(
    val startsAt: LocalTime?,
    val endsAt: LocalTime?,
    val status: String,
    val isGuestVisible: Boolean,
    val manuallyMarkedActive: Boolean,
)

private data class StaffShiftSlot(
    val staffProfileId: Long,
    val shiftDate: LocalDate,
)

private data class StaffSchedulePendingAudit(
    val action: String,
    val old: VenueStaffScheduledShift?,
    val new: VenueStaffScheduledShift,
    val oldLifecycle: StaffScheduleLifecycle?,
    val newLifecycle: StaffScheduleLifecycle,
)

private val StaffScheduleBatchAssignment.slot: StaffShiftSlot
    get() = StaffShiftSlot(staffProfileId, shiftDate)

private val VenueStaffShift.slot: StaffShiftSlot
    get() = StaffShiftSlot(staffProfileId, shiftDate)

private val staffScheduleBatchAssignmentOrder =
    compareBy<StaffScheduleBatchAssignment>({ it.staffProfileId }, { it.shiftDate })

data class PublicVenueStaffToday(
    val id: Long,
    val displayName: String,
    val roleLabel: String?,
    val subtype: String,
    val photoRef: String?,
    val bio: String?,
    val tags: List<String>,
    val shiftId: Long,
    val shiftDate: LocalDate,
    val startsAt: LocalTime?,
    val endsAt: LocalTime?,
    val shiftStatus: String,
    val manuallyMarkedActive: Boolean,
)

private fun java.sql.PreparedStatement.setNullableLong(
    index: Int,
    value: Long?,
) {
    if (value == null) {
        setNull(index, Types.BIGINT)
    } else {
        setLong(index, value)
    }
}

private fun java.sql.PreparedStatement.setNullableString(
    index: Int,
    value: String?,
) {
    if (value == null) {
        setNull(index, Types.VARCHAR)
    } else {
        setString(index, value)
    }
}

private fun java.sql.PreparedStatement.setNullableInstant(
    index: Int,
    value: Instant?,
) {
    if (value == null) {
        setNull(index, Types.TIMESTAMP)
    } else {
        setTimestamp(index, Timestamp.from(value))
    }
}

private fun java.sql.PreparedStatement.setNullableLocalTime(
    index: Int,
    value: LocalTime?,
) {
    if (value == null) {
        setNull(index, Types.TIME)
    } else {
        setObject(index, value)
    }
}

private fun ResultSet.getNullableLong(column: String): Long? {
    val value = getLong(column)
    return value.takeIf { !wasNull() }
}

private fun ResultSet.getNullableInstant(column: String): Instant? = getTimestamp(column)?.toInstant()

private fun ResultSet.getNullableLocalTime(column: String): LocalTime? = getObject(column, LocalTime::class.java)

private fun SQLException.isUniqueViolation(): Boolean =
    generateSequence(this) { it.nextException }
        .any { it.sqlState == "23505" || it.errorCode == 23505 }

private fun JsonObjectBuilder.putNullableString(
    key: String,
    value: String?,
) {
    if (value == null) {
        put(key, JsonNull)
    } else {
        put(key, value)
    }
}

private fun JsonObjectBuilder.putNullableBoolean(
    key: String,
    value: Boolean?,
) {
    if (value == null) {
        put(key, JsonNull)
    } else {
        put(key, value)
    }
}

private const val STAFF_SHIFT_AUDIT_ENTITY_TYPE = "staff_shift"
private const val STAFF_TODAY_SHIFT_AUDIT_ACTION_PREFIX = "staff_shift_marked_"
private const val STAFF_PROFILE_AUDIT_ENTITY_TYPE = "staff_profile"
private const val STAFF_PROFILE_CREATED_AUDIT_ACTION = "STAFF_PROFILE_CREATED"
private const val STAFF_PROFILE_UPDATED_AUDIT_ACTION = "STAFF_PROFILE_UPDATED"
private const val STAFF_PROFILE_PUBLISHED_AUDIT_ACTION = "STAFF_PROFILE_PUBLISHED"
private const val STAFF_PROFILE_HIDDEN_AUDIT_ACTION = "STAFF_PROFILE_HIDDEN"
private const val STAFF_PROFILE_LINKAGE_FIELD = "linkage"
private const val STAFF_PROFILE_VISIBILITY_FIELD = "isGuestVisible"
private const val STAFF_SHIFT_CREATED_AUDIT_ACTION = "STAFF_SHIFT_CREATED"
private const val STAFF_SHIFT_UPDATED_AUDIT_ACTION = "STAFF_SHIFT_UPDATED"
private const val STAFF_SHIFT_CANCELED_AUDIT_ACTION = "STAFF_SHIFT_CANCELED"
private const val STAFF_SHIFT_RESTORED_AUDIT_ACTION = "STAFF_SHIFT_RESTORED"
private const val STAFF_SHIFT_INVALID_INTERVAL_VALIDATION_STATE = "INVALID_INTERVAL"
private const val STAFF_SCHEDULE_FUTURE_DAYS = 90L

private val STAFF_PROFILE_SELF_EDIT_FIELDS = setOf("photoRef", "bio", "tags")
private val MANAGER_TODAY_SHIFT_ALLOWED_LINKAGES =
    setOf(
        StaffProfileLinkageClass.DISPLAY_ONLY,
        StaffProfileLinkageClass.STAFF_LINKED,
    )

private val scheduleShiftSelectColumns =
    """
    sp.id AS profile_id,
    sp.venue_id AS profile_venue_id,
    sp.linked_user_id,
    sp.display_name,
    sp.role_label,
    sp.subtype,
    sp.photo_ref,
    sp.bio,
    sp.tags,
    sp.is_guest_visible AS profile_is_guest_visible,
    sp.created_by_user_id AS profile_created_by_user_id,
    sp.updated_by_user_id AS profile_updated_by_user_id,
    sp.published_at,
    sp.disabled_at,
    sp.created_at AS profile_created_at,
    sp.updated_at AS profile_updated_at,
    ss.id AS shift_id,
    ss.venue_id AS shift_venue_id,
    ss.staff_profile_id,
    ss.shift_date,
    ss.starts_at,
    ss.ends_at,
    ss.status AS shift_status,
    ss.is_guest_visible AS shift_is_guest_visible,
    ss.manually_marked_active,
    ss.created_by_user_id AS shift_created_by_user_id,
    ss.updated_by_user_id AS shift_updated_by_user_id,
    ss.created_at AS shift_created_at,
    ss.updated_at AS shift_updated_at
    """.trimIndent()
