package com.hookah.platform.backend.onboarding

import com.hookah.platform.backend.telegram.db.VenueConnectionRequestApplicantRecord
import com.hookah.platform.backend.telegram.db.VenueConnectionRequestCreateLinkResult
import com.hookah.platform.backend.telegram.db.VenueConnectionRequestMutationResult
import com.hookah.platform.backend.telegram.db.VenueConnectionRequestRecord
import com.hookah.platform.backend.telegram.db.VenueConnectionRequestRepository
import com.hookah.platform.backend.telegram.db.VenueConnectionRequestSubmitResult
import com.hookah.platform.backend.telegram.db.VenueOnboardingSource
import java.time.LocalDate

class VenueOnboardingService(
    private val requestRepository: VenueConnectionRequestRepository,
) {
    suspend fun submitApplication(
        applicantUserId: Long,
        venueName: String,
        city: String,
        contact: String,
        comment: String?,
        source: VenueOnboardingSource,
    ): VenueConnectionRequestSubmitResult =
        requestRepository.createOrReturnActive(
            telegramUserId = applicantUserId,
            venueName = venueName,
            city = city,
            contact = contact,
            comment = comment,
            source = source,
        )

    suspend fun listOwnApplications(applicantUserId: Long): List<VenueConnectionRequestRecord> =
        requestRepository.listByApplicant(applicantUserId)

    suspend fun findOwnApplication(
        requestId: Long,
        applicantUserId: Long,
    ): VenueConnectionRequestRecord? = requestRepository.findByIdForApplicant(requestId, applicantUserId)

    suspend fun updateOwnPendingApplication(
        requestId: Long,
        applicantUserId: Long,
        venueName: String,
        city: String,
        contact: String,
        comment: String?,
        source: VenueOnboardingSource,
    ): VenueConnectionRequestMutationResult =
        requestRepository.updatePending(
            requestId = requestId,
            telegramUserId = applicantUserId,
            venueName = venueName,
            city = city,
            contact = contact,
            comment = comment,
            source = source,
        )

    suspend fun cancelOwnPendingApplication(
        requestId: Long,
        applicantUserId: Long,
        source: VenueOnboardingSource,
    ): VenueConnectionRequestMutationResult = requestRepository.cancel(requestId, applicantUserId, source)

    suspend fun listPlatformApplications(
        statuses: Set<String>?,
        query: String?,
        limit: Int,
        offset: Int,
    ): List<VenueConnectionRequestApplicantRecord> = requestRepository.listForPlatform(statuses, query, limit, offset)

    suspend fun findPlatformApplication(requestId: Long): VenueConnectionRequestApplicantRecord? =
        requestRepository.findForPlatform(requestId)

    suspend fun findApplication(requestId: Long): VenueConnectionRequestRecord? = requestRepository.findById(requestId)

    suspend fun listActionableApplications(limit: Int): List<VenueConnectionRequestRecord> =
        requestRepository.listActionableRequests(limit)

    suspend fun findApplicationByLinkedVenue(venueId: Long): VenueConnectionRequestRecord? =
        requestRepository.findApprovedByLinkedVenue(venueId)

    suspend fun decideApplication(
        requestId: Long,
        actorUserId: Long,
        approved: Boolean,
        source: VenueOnboardingSource,
    ): VenueConnectionRequestMutationResult =
        requestRepository.decide(
            requestId = requestId,
            actorUserId = actorUserId,
            targetStatus =
                if (approved) {
                    VenueConnectionRequestRepository.STATUS_APPROVED
                } else {
                    VenueConnectionRequestRepository.STATUS_REJECTED
                },
            source = source,
        )

    suspend fun closeApprovedApplication(
        requestId: Long,
        actorUserId: Long,
        source: VenueOnboardingSource,
    ): VenueConnectionRequestMutationResult = requestRepository.closeApprovedUnlinked(requestId, actorUserId, source)

    suspend fun updateCommercialTerms(
        requestId: Long,
        actorUserId: Long,
        trialConfigured: Boolean,
        trialEndsOn: LocalDate?,
        currentPriceRub: Long,
        futurePriceRub: Long?,
        futurePriceEffectiveOn: LocalDate?,
        commercialNote: String?,
        source: VenueOnboardingSource,
    ): VenueConnectionRequestMutationResult =
        requestRepository.updateTerms(
            requestId = requestId,
            actorUserId = actorUserId,
            trialConfigured = trialConfigured,
            trialEndsOn = trialEndsOn,
            currentPriceRub = currentPriceRub,
            futurePriceRub = futurePriceRub,
            futurePriceEffectiveOn = futurePriceEffectiveOn,
            commercialNote = commercialNote,
            source = source,
        )

    suspend fun createDraftAndLink(
        requestId: Long,
        actorUserId: Long,
        source: VenueOnboardingSource,
    ): VenueConnectionRequestCreateLinkResult = requestRepository.createDraftAndLink(requestId, actorUserId, source)
}
