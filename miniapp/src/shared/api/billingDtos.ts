export type OwnerBillingInvoiceDto = {
  id: number
  periodStart: string
  periodEnd: string
  dueAt: string
  amountMinor: number
  currency: string
  status: string
  checkoutUrl?: string | null
  paidAt?: string | null
}

export type OwnerBillingOverviewResponse = {
  venueId: number
  subscriptionStatus: string
  trialEndAt?: string | null
  paidStartAt?: string | null
  lifecycleUpdatedAt?: string | null
  settingsTrialEndDate?: string | null
  settingsPaidStartDate?: string | null
  priceMinor?: number | null
  currency?: string | null
  basePaidThrough?: string | null
  paidThrough?: string | null
  nextPaymentDate?: string | null
  nextInvoicePeriodStart?: string | null
  nextInvoicePeriodEnd?: string | null
  courtesyDays?: number | null
  lastCourtesyDays?: number | null
  lastCourtesyReason?: string | null
  lastCourtesyCreatedAt?: string | null
  paymentAvailable: boolean
  platformCheckoutEnsureAvailable?: boolean
  checkoutEnsureAvailable: boolean
  unavailableReason?: string | null
  checkoutUrl?: string | null
  payableInvoice?: OwnerBillingInvoiceDto | null
  invoices: OwnerBillingInvoiceDto[]
}
