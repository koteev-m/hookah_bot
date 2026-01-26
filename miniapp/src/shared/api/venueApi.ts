import { requestApi, type RequestDependencies } from './request'
import type { StaffChatLinkCodeResponse, VenueMeResponse } from './venueDtos'

export async function venueGetMe(
  backendUrl: string,
  deps: RequestDependencies,
  signal?: AbortSignal
) {
  return requestApi<VenueMeResponse>(backendUrl, '/api/venue/me', { signal }, deps)
}

export async function venueCreateStaffChatLinkCode(
  backendUrl: string,
  venueId: number,
  deps: RequestDependencies,
  signal?: AbortSignal
) {
  return requestApi<StaffChatLinkCodeResponse>(
    backendUrl,
    `/api/venue/${venueId}/staff-chat/link-code`,
    { method: 'POST', signal },
    deps
  )
}
