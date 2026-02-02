export type DepositCategory = 'BAR' | 'HOOKAH' | 'VIP' | 'OTHER'

export type NightTableAllocationDto = {
  category: DepositCategory
  amount: number
}

export type NightTableDepositDto = {
  depositId?: number
  id?: number
  amount: number
  allocations: NightTableAllocationDto[]
  createdAt?: string | null
  updatedAt?: string | null
}

export type NightTableDto = {
  tableId: number
  tableNumber?: number
  tableLabel?: string
  status?: 'FREE' | 'OCCUPIED'
  isOccupied?: boolean
  isSeated?: boolean
  isActive?: boolean
  deposit?: NightTableDepositDto | null
  depositId?: number
  seatedAt?: string | null
  guestName?: string | null
}

export type NightTablesResponse = {
  clubId: number
  nightKey: string
  tables: NightTableDto[]
}

export type SeatTableRequest = {
  mode: 'WITH_QR' | 'NO_QR'
  qr?: string
  deposit: NightTableDepositInput
}

export type NightTableDepositInput = {
  amount: number
  allocations: NightTableAllocationDto[]
}

export type UpdateDepositRequest = {
  amount: number
  allocations: NightTableAllocationDto[]
  reason: string
}
