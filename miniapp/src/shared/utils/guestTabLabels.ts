import type { GuestTabDto } from '../api/guestDtos'

export function formatGuestTabLabel(tab: GuestTabDto, tabs: GuestTabDto[] = []): string {
  if (tab.type === 'PERSONAL') {
    return 'Личный счёт'
  }
  const sharedTabs = tabs.filter((candidate) => candidate.type === 'SHARED' && candidate.status === 'ACTIVE')
  if (sharedTabs.length <= 1) {
    return 'Общий счёт'
  }
  const index = sharedTabs.findIndex((candidate) => candidate.id === tab.id)
  return index >= 0 ? `Общий счёт ${index + 1}` : 'Общий счёт'
}
