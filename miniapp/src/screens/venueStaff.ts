import { REQUEST_ABORTED_CODE } from '../shared/api/abort'
import { clearSession, getAccessToken } from '../shared/api/auth'
import { normalizeErrorCode } from '../shared/api/errorMapping'
import { venueCreateInvite, venueGetStaff, venueRemoveStaff, venueUpdateRole } from '../shared/api/venueApi'
import type { VenueAccessDto, VenueStaffMemberDto, VenueStaffInviteResponse } from '../shared/api/venueDtos'
import { ApiErrorCodes, type ApiErrorInfo } from '../shared/api/types'
import { append, el, on } from '../shared/ui/dom'
import { presentApiError, type ApiErrorAction } from '../shared/ui/apiErrorPresenter'
import { renderErrorDetails } from '../shared/ui/errorDetails'
import { showToast } from '../shared/ui/toast'

export type VenueStaffOptions = {
  root: HTMLDivElement | null
  backendUrl: string
  isDebug: boolean
  venueId: number
  access: VenueAccessDto
  currentUserId: number
}

type StaffRefs = {
  status: HTMLParagraphElement
  error: HTMLDivElement
  errorTitle: HTMLHeadingElement
  errorMessage: HTMLParagraphElement
  errorActions: HTMLDivElement
  errorDetails: HTMLDivElement
  inviteRole: HTMLSelectElement
  inviteButton: HTMLButtonElement
  inviteResult: HTMLDivElement
  inviteCode: HTMLSpanElement
  inviteExpires: HTMLSpanElement
  inviteInstructions: HTMLParagraphElement
  list: HTMLDivElement
}

function buildApiDeps(isDebug: boolean) {
  return { isDebug, getAccessToken, clearSession }
}

function renderErrorActions(container: HTMLElement, actions: ApiErrorAction[]) {
  container.replaceChildren()
  actions.forEach((action) => {
    const button = document.createElement('button')
    button.textContent = action.label
    if (action.kind === 'secondary') {
      button.classList.add('button-secondary')
    }
    button.addEventListener('click', action.onClick)
    container.appendChild(button)
  })
}

function buildStaffDom(root: HTMLDivElement): StaffRefs {
  const wrapper = el('div', { className: 'venue-staff' })
  const header = el('div', { className: 'card' })
  const title = el('h2', { text: 'Персонал' })
  const inviteRow = el('div', { className: 'venue-form-row' })
  const inviteRole = document.createElement('select')
  inviteRole.className = 'venue-select'
  inviteRole.appendChild(new Option('STAFF', 'STAFF'))
  inviteRole.appendChild(new Option('MANAGER', 'MANAGER'))
  inviteRole.appendChild(new Option('OWNER', 'OWNER'))
  const inviteButton = el('button', { text: 'Создать инвайт' }) as HTMLButtonElement
  append(inviteRow, inviteRole, inviteButton)

  const inviteResult = el('div', { className: 'venue-invite-result' })
  inviteResult.hidden = true
  const inviteCode = el('span')
  const inviteExpires = el('span')
  const inviteInstructions = el('p', { text: '' })
  append(
    inviteResult,
    el('p', { text: 'Код:' }),
    inviteCode,
    el('p', { text: 'Истекает:' }),
    inviteExpires,
    inviteInstructions
  )

  append(header, title, inviteRow, inviteResult)

  const status = el('p', { className: 'status', text: '' })

  const error = el('div', { className: 'error-card' })
  error.hidden = true
  const errorTitle = el('h3')
  const errorMessage = el('p')
  const errorActions = el('div', { className: 'error-actions' })
  const errorDetails = el('div')
  append(error, errorTitle, errorMessage, errorActions, errorDetails)

  const list = el('div', { className: 'venue-staff-list' })

  append(wrapper, header, status, error, list)
  root.replaceChildren(wrapper)

  return {
    status,
    error,
    errorTitle,
    errorMessage,
    errorActions,
    errorDetails,
    inviteRole,
    inviteButton,
    inviteResult,
    inviteCode,
    inviteExpires,
    inviteInstructions,
    list
  }
}

function renderMemberRow(
  member: VenueStaffMemberDto,
  currentUserId: number,
  canManageRoles: boolean,
  onUpdateRole: (member: VenueStaffMemberDto, role: VenueStaffMemberDto['role']) => void,
  onRemove: (member: VenueStaffMemberDto) => void
) {
  const row = el('div', { className: 'venue-staff-row' })
  const info = el('div', { className: 'venue-staff-info' })
  append(
    info,
    el('strong', { text: `User ${member.userId}` }),
    el('p', { className: 'venue-order-sub', text: `Роль: ${member.role}` })
  )

  const actions = el('div', { className: 'venue-staff-actions' })
  const roleSelect = document.createElement('select')
  roleSelect.className = 'venue-select'
  roleSelect.appendChild(new Option('OWNER', 'OWNER'))
  roleSelect.appendChild(new Option('MANAGER', 'MANAGER'))
  roleSelect.appendChild(new Option('STAFF', 'STAFF'))
  roleSelect.value = member.role
  const updateButton = el('button', { className: 'button-small', text: 'Обновить' }) as HTMLButtonElement
  const removeButton = el('button', { className: 'button-small button-secondary', text: 'Удалить' }) as HTMLButtonElement

  const disableManage = !canManageRoles || member.userId === currentUserId
  roleSelect.disabled = disableManage
  updateButton.disabled = disableManage
  removeButton.disabled = !canManageRoles || member.userId === currentUserId

  if (member.userId === currentUserId) {
    updateButton.title = 'Нельзя менять свою роль'
    removeButton.title = 'Нельзя удалить себя'
  } else if (!canManageRoles) {
    updateButton.title = 'Недостаточно прав'
    removeButton.title = 'Недостаточно прав'
  }

  updateButton.addEventListener('click', () => onUpdateRole(member, roleSelect.value as VenueStaffMemberDto['role']))
  removeButton.addEventListener('click', () => onRemove(member))

  append(actions, roleSelect, updateButton, removeButton)
  append(row, info, actions)
  return row
}

export function renderVenueStaffScreen(options: VenueStaffOptions) {
  const { root, backendUrl, isDebug, venueId, access, currentUserId } = options
  if (!root) return () => undefined
  const refs = buildStaffDom(root)
  const deps = buildApiDeps(isDebug)

  let disposed = false
  let loadAbort: AbortController | null = null
  let loadSeq = 0
  let currentInvite: VenueStaffInviteResponse | null = null

  const canInvite = access.role !== 'STAFF'
  const canManageRoles = access.role === 'OWNER'

  const setStatus = (text: string) => {
    refs.status.textContent = text
  }

  const hideError = () => {
    refs.error.hidden = true
  }

  const showError = (error: ApiErrorInfo) => {
    const normalized = normalizeErrorCode(error)
    if (normalized === ApiErrorCodes.UNAUTHORIZED || normalized === ApiErrorCodes.INITDATA_INVALID) {
      clearSession()
    }
    const presentation = presentApiError(error, { isDebug, scope: 'venue' })
    refs.error.dataset.severity = presentation.severity
    refs.errorTitle.textContent = presentation.title
    refs.errorMessage.textContent = presentation.message
    const actions: ApiErrorAction[] = presentation.actions.length
      ? presentation.actions.map((action) =>
          action.label === 'Повторить' ? { ...action, onClick: () => void loadStaff() } : action
        )
      : [{ label: 'Повторить', kind: 'primary' as const, onClick: () => void loadStaff() }]
    renderErrorActions(refs.errorActions, actions)
    renderErrorDetails(refs.errorDetails, error, { isDebug })
    refs.error.hidden = false
  }

  const renderInvite = () => {
    if (!currentInvite) {
      refs.inviteResult.hidden = true
      return
    }
    refs.inviteCode.textContent = currentInvite.inviteCode
    refs.inviteExpires.textContent = new Date(currentInvite.expiresAt).toLocaleString()
    refs.inviteInstructions.textContent = currentInvite.instructions
    refs.inviteResult.hidden = false
  }

  const renderStaff = (members: VenueStaffMemberDto[], currentUserId: number) => {
    refs.list.replaceChildren()
    if (!members.length) {
      refs.list.appendChild(el('p', { className: 'venue-empty', text: 'Сотрудники не найдены.' }))
      return
    }
    members.forEach((member) => {
      refs.list.appendChild(
        renderMemberRow(member, currentUserId, canManageRoles, (target, role) => void updateRole(target, role), (target) => void removeMember(target))
      )
    })
  }

  const loadStaff = async () => {
    hideError()
    setStatus('Загрузка...')
    if (loadAbort) {
      loadAbort.abort()
    }
    const controller = new AbortController()
    loadAbort = controller
    const seq = ++loadSeq
    const result = await venueGetStaff(backendUrl, venueId, deps, controller.signal)
    if (disposed || loadSeq !== seq) return
    loadAbort = null
    if (!result.ok && result.error.code === REQUEST_ABORTED_CODE) return
    if (!result.ok) {
      showError(result.error)
      setStatus('')
      return
    }
    renderStaff(result.data.members, currentUserId)
    setStatus(`Обновлено: ${new Date().toLocaleTimeString()}`)
  }

  const createInvite = async () => {
    if (!canInvite) {
      showToast('Недостаточно прав')
      return
    }
    const role = refs.inviteRole.value as VenueStaffMemberDto['role']
    const result = await venueCreateInvite(backendUrl, { venueId, body: { role } }, deps)
    if (disposed) return
    if (!result.ok) {
      showError(result.error)
      return
    }
    currentInvite = result.data
    renderInvite()
    showToast('Инвайт создан')
  }

  const updateRole = async (member: VenueStaffMemberDto, role: VenueStaffMemberDto['role']) => {
    if (!canManageRoles) return
    if (member.userId === currentUserId) {
      showToast('Нельзя менять свою роль')
      return
    }
    const result = await venueUpdateRole(backendUrl, { venueId, userId: member.userId, body: { role } }, deps)
    if (disposed) return
    if (!result.ok) {
      showError(result.error)
      return
    }
    showToast('Роль обновлена')
    void loadStaff()
  }

  const removeMember = async (member: VenueStaffMemberDto) => {
    if (!canManageRoles) return
    if (member.userId === currentUserId) {
      showToast('Нельзя удалить себя')
      return
    }
    if (!window.confirm('Удалить участника?')) return
    const result = await venueRemoveStaff(backendUrl, { venueId, userId: member.userId }, deps)
    if (disposed) return
    if (!result.ok) {
      showError(result.error)
      return
    }
    showToast('Участник удалён')
    void loadStaff()
  }

  const disposables: Array<() => void> = []
  disposables.push(on(refs.inviteButton, 'click', () => void createInvite()))

  refs.inviteButton.disabled = !canInvite
  refs.inviteButton.title = canInvite ? '' : 'Недостаточно прав'
  if (access.role === 'MANAGER') {
    refs.inviteRole.value = 'STAFF'
  }
  if (access.role === 'MANAGER') {
    const managerOptions = Array.from(refs.inviteRole.options)
    managerOptions.forEach((option) => {
      if (option.value !== 'STAFF') {
        option.disabled = true
      }
    })
  }

  void loadStaff()

  return () => {
    disposed = true
    loadAbort?.abort()
    disposables.forEach((dispose) => dispose())
  }
}
