import { REQUEST_ABORTED_CODE } from '../shared/api/abort'
import { clearSession, getAccessToken } from '../shared/api/auth'
import { normalizeErrorCode } from '../shared/api/errorMapping'
import { venueCreateInvite, venueGetStaff, venueRemoveStaff, venueUpdateRole } from '../shared/api/venueApi'
import type { VenueAccessDto, VenueStaffMemberDto, VenueStaffInviteResponse } from '../shared/api/venueDtos'
import { ApiErrorCodes, type ApiErrorInfo } from '../shared/api/types'
import { getTelegramContext } from '../shared/telegram'
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
  inviteRoleLabel: HTMLSpanElement
  inviteVenueName: HTMLSpanElement
  inviteLinkField: HTMLTextAreaElement
  inviteCopyLinkButton: HTMLButtonElement
  inviteShareButton: HTMLButtonElement
  inviteFallbackDetails: HTMLDetailsElement
  inviteCommandField: HTMLTextAreaElement
  inviteCopyCommandButton: HTMLButtonElement
  inviteExpires: HTMLSpanElement
  inviteHelper: HTMLParagraphElement
  inviteCopyStatus: HTMLParagraphElement
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
  const inviteResultTitle = el('h3', { text: 'Приглашение создано' })
  const inviteRoleLabel = el('span')
  const inviteVenueName = el('span')
  const inviteExpires = el('span')
  const inviteMeta = el('div', { className: 'venue-invite-meta' })
  append(
    inviteMeta,
    el('p', { text: 'Роль:' }),
    inviteRoleLabel,
    el('p', { text: 'Заведение:' }),
    inviteVenueName,
    el('p', { text: 'Действует до:' }),
    inviteExpires
  )

  const inviteLinkLabel = el('label', { className: 'field-label', text: 'Ссылка для сотрудника' })
  inviteLinkLabel.htmlFor = 'venue-staff-invite-link'
  const inviteLinkField = document.createElement('textarea')
  inviteLinkField.id = 'venue-staff-invite-link'
  inviteLinkField.className = 'venue-invite-field'
  inviteLinkField.readOnly = true
  inviteLinkField.rows = 2
  const inviteCopyLinkButton = el('button', {
    className: 'button-small button-secondary',
    text: '📋 Скопировать ссылку'
  }) as HTMLButtonElement
  const inviteShareButton = el('button', {
    className: 'button-small button-secondary',
    text: '📤 Поделиться в Telegram'
  }) as HTMLButtonElement
  const inviteLinkActions = el('div', { className: 'venue-invite-actions' })
  append(inviteLinkActions, inviteCopyLinkButton, inviteShareButton)

  const inviteHelper = el('p', { className: 'venue-invite-help', text: '' })

  const inviteFallbackDetails = document.createElement('details')
  inviteFallbackDetails.className = 'venue-invite-fallback'
  const inviteFallbackSummary = document.createElement('summary')
  inviteFallbackSummary.textContent = 'Если ссылка не открылась'
  const inviteFallbackHelp = el('p', {
    className: 'venue-invite-help',
    text: 'Скопируйте команду и отправьте её сотруднику вручную.'
  })

  const inviteCommandField = document.createElement('textarea')
  inviteCommandField.id = 'venue-staff-invite-command'
  inviteCommandField.className = 'venue-invite-field'
  inviteCommandField.readOnly = true
  inviteCommandField.rows = 2
  inviteCommandField.setAttribute('aria-label', 'Команда, если ссылка не открылась')
  const inviteCopyCommandButton = el('button', {
    className: 'button-small button-secondary',
    text: '📋 Скопировать команду'
  }) as HTMLButtonElement
  append(inviteFallbackDetails, inviteFallbackSummary, inviteFallbackHelp, inviteCommandField, inviteCopyCommandButton)
  const inviteCopyStatus = el('p', { className: 'venue-invite-copy-status', text: '' })
  inviteCopyStatus.setAttribute('aria-live', 'polite')
  append(
    inviteResult,
    inviteResultTitle,
    inviteMeta,
    inviteLinkLabel,
    inviteLinkField,
    inviteLinkActions,
    inviteHelper,
    inviteFallbackDetails,
    inviteCopyStatus
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
    inviteRoleLabel,
    inviteVenueName,
    inviteLinkField,
    inviteCopyLinkButton,
    inviteShareButton,
    inviteFallbackDetails,
    inviteCommandField,
    inviteCopyCommandButton,
    inviteExpires,
    inviteHelper,
    inviteCopyStatus,
    list
  }
}

function formatInviteExpires(value: string): string {
  const date = new Date(value)
  if (Number.isNaN(date.getTime())) {
    return value
  }
  return date.toLocaleString([], {
    day: '2-digit',
    month: '2-digit',
    year: 'numeric',
    hour: '2-digit',
    minute: '2-digit'
  })
}

function buildTelegramShareUrl(inviteLink: string, venueName: string, role: string): string {
  const text = `Приглашение в ${venueName}. Роль: ${role}. Откройте ссылку, чтобы принять доступ.`
  return `https://t.me/share/url?url=${encodeURIComponent(inviteLink)}&text=${encodeURIComponent(text)}`
}

function buildInviteHelperText(role: string, venueName: string, expiresAt: string): string {
  return `Отправьте эту ссылку сотруднику. Он откроет её в Telegram и получит роль ${role} в заведении «${venueName}». Ссылка одноразовая и действует до ${expiresAt}.`
}

function selectVisibleField(field: HTMLTextAreaElement) {
  field.focus()
  field.select()
  field.setSelectionRange(0, field.value.length)
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
    const startPayload = currentInvite.startPayload ?? `staff_invite_${currentInvite.inviteCode}`
    const fallbackCommand = currentInvite.fallbackCommand ?? `/start ${startPayload}`
    const deepLink = currentInvite.deepLink?.trim() || null
    const role = currentInvite.role ?? refs.inviteRole.value
    const venueName = currentInvite.venueName ?? access.venueName ?? `Venue ${venueId}`
    const expiresAt = formatInviteExpires(currentInvite.expiresAt)
    refs.inviteRoleLabel.textContent = role
    refs.inviteVenueName.textContent = venueName
    refs.inviteCopyStatus.textContent = ''
    refs.inviteCopyLinkButton.disabled = !deepLink
    refs.inviteShareButton.disabled = !deepLink
    refs.inviteFallbackDetails.open = false
    if (deepLink) {
      refs.inviteLinkField.value = deepLink
      const shareUrl = buildTelegramShareUrl(deepLink, venueName, role)
      refs.inviteShareButton.dataset.shareUrl = shareUrl
    } else {
      refs.inviteLinkField.value = 'Ссылка недоступна. Используйте запасную команду ниже.'
      delete refs.inviteShareButton.dataset.shareUrl
    }
    refs.inviteCommandField.value = fallbackCommand
    refs.inviteExpires.textContent = expiresAt
    refs.inviteHelper.textContent = deepLink
      ? buildInviteHelperText(role, venueName, expiresAt)
      : 'Ссылка недоступна. Скопируйте команду ниже и отправьте её сотруднику вручную.'
    refs.inviteResult.hidden = false
  }

  const copyInviteText = async (
    text: string,
    field: HTMLTextAreaElement,
    successMessage: string,
    fallbackMessage: string
  ) => {
    refs.inviteCopyStatus.textContent = ''
    if (navigator.clipboard?.writeText) {
      try {
        await navigator.clipboard.writeText(text)
        refs.inviteCopyStatus.textContent = successMessage
        showToast(successMessage)
        return
      } catch {
        // Fall through to visible manual selection below.
      }
    }
    selectVisibleField(field)
    refs.inviteCopyStatus.textContent = fallbackMessage
    showToast(fallbackMessage)
  }

  const openUrl = (url: string) => {
    const telegram = getTelegramContext()
    if (telegram.webApp?.openTelegramLink) {
      try {
        telegram.webApp.openTelegramLink(url)
        return
      } catch {
        // Fall through to the browser fallback below.
      }
    }
    const opened = window.open(url, '_blank', 'noopener,noreferrer')
    if (!opened) {
      window.location.href = url
    }
  }

  const currentInviteDeepLink = () => currentInvite?.deepLink?.trim() || null

  const currentInviteShareUrl = () => {
    const link = currentInviteDeepLink()
    if (!link) return null
    const role = currentInvite?.role ?? refs.inviteRole.value
    const venueName = currentInvite?.venueName ?? access.venueName ?? `Venue ${venueId}`
    return buildTelegramShareUrl(link, venueName, role)
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
  disposables.push(
    on(refs.inviteCopyLinkButton, 'click', () => {
      const link = currentInviteDeepLink()
      if (!link) {
        showToast('Ссылка недоступна')
        return
      }
      void copyInviteText(
        link,
        refs.inviteLinkField,
        'Ссылка скопирована',
        'Не удалось скопировать автоматически. Ссылка выделена ниже.'
      )
    })
  )
  disposables.push(
    on(refs.inviteShareButton, 'click', () => {
      const shareUrl = currentInviteShareUrl()
      if (!shareUrl) {
        showToast('Ссылка недоступна')
        return
      }
      openUrl(shareUrl)
    })
  )
  disposables.push(
    on(refs.inviteCopyCommandButton, 'click', () => {
      if (!currentInvite) return
      const startPayload = currentInvite.startPayload ?? `staff_invite_${currentInvite.inviteCode}`
      void copyInviteText(
        currentInvite.fallbackCommand ?? `/start ${startPayload}`,
        refs.inviteCommandField,
        'Команда скопирована',
        'Не удалось скопировать автоматически. Команда выделена ниже.'
      )
    })
  )

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
