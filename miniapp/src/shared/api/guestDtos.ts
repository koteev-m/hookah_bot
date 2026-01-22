// Nullable fields are omitted only when backend Json settings disable explicitNulls or encodeDefaults for default values.
export type CatalogResponse = {
  venues: CatalogVenueDto[]
}

export type CatalogVenueDto = {
  id: number
  name: string
  city?: string | null
  address?: string | null
}

export type VenueResponse = {
  venue: VenueDto
}

export type VenueDto = {
  id: number
  name: string
  city?: string | null
  address?: string | null
  status: string
}

export type MenuResponse = {
  venueId: number
  categories: MenuCategoryDto[]
}

export type MenuCategoryDto = {
  id: number
  name: string
  items: MenuItemDto[]
}

export type MenuItemDto = {
  id: number
  name: string
  priceMinor: number
  currency: string
  isAvailable: boolean
}

export type TableResolveResponse = {
  venueId: number
  tableId: number
  tableNumber: string
  venueStatus: string
  subscriptionStatus: string
  available: boolean
  unavailableReason: string | null
}

export type ActiveOrderResponse = {
  order: ActiveOrderDto | null
}

export type ActiveOrderDto = {
  orderId: number
  venueId: number
  tableId: number
  tableNumber: string
  status: string
  batches: OrderBatchDto[]
}

export type OrderBatchDto = {
  batchId: number
  comment: string | null
  items: OrderBatchItemDto[]
}

export type OrderBatchItemDto = {
  itemId: number
  qty: number
}

export type AddBatchRequest = {
  tableToken: string
  items: AddBatchItemDto[]
  comment?: string | null
}

export type AddBatchItemDto = {
  itemId: number
  qty: number
}

export type AddBatchResponse = {
  orderId: number
  batchId: number
}

export type StaffCallRequest = {
  tableToken: string
  reason: string
  comment?: string | null
}

export type StaffCallResponse = {
  staffCallId: number
  createdAtEpochSeconds: number
}
