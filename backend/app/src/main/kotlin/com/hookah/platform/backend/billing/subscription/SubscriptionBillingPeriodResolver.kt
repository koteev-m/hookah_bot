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

    fun buildInvoiceDescription(
        periodStart: LocalDate,
        periodEnd: LocalDate,
        zone: ZoneId,
    ): String = "Подписка ($periodStart - $periodEnd, ${zone.id})"
}
