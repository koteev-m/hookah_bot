package com.hookah.platform.backend.billing.subscription

import java.time.LocalDate
import java.time.ZoneId

object SubscriptionBillingPeriodResolver {
    @Suppress("UNUSED_PARAMETER")
    fun resolvePaidAnchor(
        trialEndDate: LocalDate?,
        paidStartDate: LocalDate?,
    ): LocalDate? = paidStartDate

    fun resolvePeriodStart(
        anchor: LocalDate,
        today: LocalDate,
    ): LocalDate {
        var start = anchor
        while (!start.plusMonths(1).isAfter(today)) {
            start = start.plusMonths(1)
        }
        return start
    }

    fun invoicePeriodStart(
        anchor: LocalDate,
        paidThrough: LocalDate?,
        today: LocalDate,
        priceAt: (LocalDate) -> Int?,
    ): LocalDate {
        if (paidThrough == null) return resolvePeriodStart(anchor, today)
        var start = resolveNextPeriodStart(paidThrough)
        // A free month has no paid invoice to advance paidThrough. Preserve the first positive debt.
        while (!start.plusMonths(1).isAfter(today) && priceAt(start) == 0) {
            start = start.plusMonths(1)
        }
        return start
    }

    fun resolveNextPeriodStart(paidThrough: LocalDate): LocalDate = paidThrough.plusDays(1)

    fun resolvePeriodEnd(periodStart: LocalDate): LocalDate = periodStart.plusMonths(1).minusDays(1)

    fun buildInvoiceDescription(
        periodStart: LocalDate,
        periodEnd: LocalDate,
        zone: ZoneId,
    ): String = "Подписка ($periodStart - $periodEnd, ${zone.id})"
}
