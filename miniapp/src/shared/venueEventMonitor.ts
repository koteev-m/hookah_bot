import { clearSession, getAccessToken } from './api/auth'
import { venueGetDashboard, type AttentionEvent, type DashboardSnapshot } from './api/venueDashboardApi'
import type { VenueAccessDto } from './api/venueDtos'
import { getTelegramContext } from './telegram'
import { append, el } from './ui/dom'
import { EVENT_CAPACITY, VenueEventLedger, VenueLedgerBusyError, VenueLedgerCapacityError, type DeliveryLease } from './venueEventLedger'

const POLL_MS = 15000
export type VenueMonitor = {
  subscribe: (listener: (snapshot: DashboardSnapshot | null) => void) => () => void
  refresh: () => void
  dispose: () => void
}

// One owner in a document, even if the shell is mounted twice. Cross-window sound reservations
// additionally use Web Locks and the shared, account/venue-scoped device ledger.
let activeDispose: (() => void) | null = null

export function venueAttentionHref(event: AttentionEvent): string | null {
  if (event.kind === 'BOOKING_MESSAGE') {
    return Number.isSafeInteger(event.threadId) && (event.threadId ?? 0) > 0 ? `#/messages?threadId=${event.threadId}` : null
  }
  if (event.kind === 'BOOKING_PENDING') return event.bookingId ? `#/bookings?bookingId=${event.bookingId}` : null
  return event.orderId ? `#/orders/${event.orderId}${event.batchId ? `?batchId=${event.batchId}` : ''}` : event.callId ? `#/calls?callId=${event.callId}` : null
}

export function mountVenueEventMonitor(options: {
  root: HTMLElement; backendUrl: string; isDebug: boolean; access: VenueAccessDto; userId: number
  onAccessChanged: (reason: 'denied' | 'changed') => void
}): VenueMonitor {
  activeDispose?.()
  const { root, backendUrl, isDebug, access, userId } = options
  const ledger = new VenueEventLedger(JSON.stringify([backendUrl, userId, access.venueId]))
  const pendingLeases = new Set<DeliveryLease>()
  const cancelTones = new Set<() => void>()
  let monitoringProblem = ''
  let uncertain = false
  const audioKey = `hookah:venue-audio:v1:${JSON.stringify([backendUrl, userId])}`
  const telegram = getTelegramContext()
  const listeners = new Set<(snapshot: DashboardSnapshot | null) => void>()
  let snapshot: DashboardSnapshot | null = null
  let disposed = false
  let denied = false
  let active = true
  let timer: number | undefined
  let controller: AbortController | null = null
  let lastSuccess: number | null = null
  let audio: AudioContext | null = null
  let sound = false
  let audioProblem = ''
  let coordinationProblem = ''
  let volume = 0.5
  let desktop = false
  let muted: string[] = []
  let audioMessage = 'Звук выключен. Включите его на этом устройстве.'
  let hasLocks = Boolean(navigator.locks?.request)
  try {
    const preferences = JSON.parse(localStorage.getItem(audioKey) ?? '{}')
    if (preferences.enabled === true) audioProblem = 'нужно разрешить звук после открытия Mini App'
    if (Number.isFinite(preferences.volume)) volume = Math.max(0, Math.min(1, preferences.volume))
  } catch { /* explicit device controls remain usable */ }

  const panel = el('section', { className: 'venue-alerts' })
  panel.setAttribute('aria-label', 'Оперативные события')
  const heading = el('button', { className: 'button-secondary', text: 'Требуют внимания: —' })
  heading.setAttribute('aria-haspopup', 'dialog')
  const connection = el('p', { className: 'venue-alert-status', text: 'Обновление событий…' })
  connection.setAttribute('role', 'status')
  const soundState = el('p')
  const soundButton = el('button', { text: 'Включить звук' })
  const testButton = el('button', { className: 'button-secondary', text: 'Проверить звук' })
  const volumeInput = el('input')
  volumeInput.type = 'range'; volumeInput.min = '0'; volumeInput.max = '100'; volumeInput.value = String(volume * 100)
  const volumeLabel = el('label', { text: 'Громкость сигнала ' }); volumeLabel.append(volumeInput)
  const refresh = el('button', { className: 'button-secondary', text: 'Обновить события' })
  const notifications = el('button', { className: 'button-secondary', text: 'Включить системные уведомления' })
  const supportsNotifications = typeof window.Notification === 'function' && typeof Notification.requestPermission === 'function'
  notifications.hidden = !supportsNotifications
  const controls = el('div', { className: 'button-row' })
  append(controls, soundButton, refresh)
  const settings = el('details')
  const settingsControls = el('div', { className: 'button-row' })
  append(settingsControls, testButton, volumeLabel, notifications)
  append(settings, el('summary', { text: 'Настройки оповещений' }), settingsControls,
    el('p', { className: 'venue-alert-help', text: 'Сигнал состоит из трёх тонов и длится около секунды; не более двух повторов с интервалом от минуты. Приглушение действует только на этом устройстве и не выполняет заказ или вызов. Системные уведомления доступны только в активном Mini App и не содержат данных гостей.' }))
  const details = el('details')
  details.open = true
  const summary = el('summary', { text: 'Список ожидающих событий' })
  const list = el('div', { className: 'venue-alert-list' }); append(details, summary, list)
  const recent = el('p')
  const dialog = document.createElement('dialog')
  dialog.className = 'venue-alert-dialog'
  dialog.setAttribute('aria-label', 'Задачи заведения')
  const close = el('button', { className: 'button-secondary', text: 'Закрыть задачи' })
  close.onclick = () => dialog.close()
  const dialogBody = el('div', { className: 'venue-alert-dialog-body' })
  append(dialogBody, el('h2', { text: 'Задачи заведения' }), close, controls, details, recent, settings,
    el('p', { className: 'venue-alert-help', text: 'Счётчик отражает задачи: дозаказ и вызов одного стола — две задачи. Mini App и чат персонала показывают одни задачи. Открытие и приглушение не принимают заказ. После действия состояние обновляется при следующей синхронизации, обычно до 15 секунд. Фоновый звук закрытого Mini App не гарантируется.' }))
  dialog.append(dialogBody)
  heading.onclick = () => { if (!dialog.open) dialog.showModal() }
  list.addEventListener('click', event => { if ((event.target as Element).closest('a')) dialog.close() })
  append(panel, heading, soundState, connection, dialog)
  root.replaceChildren(panel)

  const persistPreferences = () => {
    try { localStorage.setItem(audioKey, JSON.stringify({ volume, enabled: sound })) } catch { /* no automatic audio without ledger */ }
  }
  const paintAudio = () => {
    soundButton.textContent = sound ? 'Выключить звук' : 'Включить звук'
    soundButton.setAttribute('aria-pressed', String(sound))
    const state = !hasLocks ? 'недоступен — только визуальные оповещения'
      : coordinationProblem ? coordinationProblem
      : audioProblem ? audioProblem
      : !sound ? 'выключен' : volume === 0 ? 'выключен — громкость сигнала 0'
        : audio?.state === 'running' ? 'включён' : 'приостановлен — нужно разрешить звук'
    soundState.textContent = `Звук на этом устройстве: ${state}.`
    soundState.title = audioMessage
  }
  const foreground = () => active && !document.hidden && navigator.onLine
  const currentIdentity = () => getTelegramContext().telegramUserId === telegram.telegramUserId
  let playing = false
  const tone = async (gesture = false) => {
    if (playing || volume === 0) return false
    playing = true
    try {
      if (gesture) {
        const Audio = window.AudioContext ?? (window as Window & { webkitAudioContext?: typeof AudioContext }).webkitAudioContext
        if (!Audio) { audioProblem = 'недоступен в этом браузере'; throw new Error('unsupported') }
        audioProblem = ''
        if (!audio) { audio = new Audio(); audio.onstatechange = () => { if (!disposed) paintAudio() } }
        await audio.resume()
      }
      if (disposed || !foreground() || !currentIdentity() || !audio || audio.state !== 'running') throw new Error('blocked')
      const oscillator = audio.createOscillator()
      const gain = audio.createGain()
      oscillator.frequency.setValueAtTime(660, audio.currentTime)
      oscillator.frequency.setValueAtTime(880, audio.currentTime + 0.3)
      oscillator.frequency.setValueAtTime(990, audio.currentTime + 0.6)
      for (const offset of [0, 0.3, 0.6]) {
        gain.gain.setValueAtTime(0, audio.currentTime + offset)
        gain.gain.linearRampToValueAtTime(volume * 0.12, audio.currentTime + offset + 0.035)
        gain.gain.linearRampToValueAtTime(0, audio.currentTime + offset + 0.26)
      }
      oscillator.connect(gain); gain.connect(audio.destination)
      return await new Promise<boolean>(resolve => {
        let settled = false
        let timeout: number | undefined
        const finish = (success: boolean) => {
          if (settled) return
          settled = true; window.clearTimeout(timeout); cancelTones.delete(cancel)
          oscillator.onended = null
          if (!success) { try { oscillator.stop() } catch { /* already stopped */ } }
          oscillator.disconnect(); gain.disconnect(); resolve(success)
        }
        const cancel = () => finish(false)
        cancelTones.add(cancel)
        oscillator.onended = () => finish(foreground() && currentIdentity() && audio?.state === 'running')
        timeout = window.setTimeout(cancel, 2000)
        try { oscillator.start(); oscillator.stop(audio!.currentTime + 0.9) }
        catch { finish(false) }
      })
    } catch {
      sound = false
      audioProblem ||= 'нужно разрешить звук — нажмите «Включить звук»'
      audioMessage = 'Браузер не разрешил звук. Нажмите «Включить звук» после возвращения в Mini App.'
      paintAudio()
      return false
    } finally { playing = false }
  }
  soundButton.onclick = async () => {
    if (sound) { sound = false; cancelTones.forEach(cancel => cancel()); audioMessage = 'Звук выключен на этом устройстве.'; await audio?.suspend() }
    else if (await tone(true)) { sound = hasLocks; audioMessage = hasLocks ? 'Звук включён на этом устройстве.' : 'Проверка звука выполнена.' }
    persistPreferences(); paintAudio()
    if (sound && snapshot?.attentionComplete) await consume(snapshot)
  }
  testButton.onclick = async () => {
    if (await tone(true)) { audioMessage = sound ? 'Проверка звука выполнена. Звук включён.' : 'Проверка звука выполнена. Автоматический звук выключен.'; paintAudio() }
  }
  volumeInput.oninput = () => { volume = Number(volumeInput.value) / 100; if (volume === 0) cancelTones.forEach(cancel => cancel()); persistPreferences(); paintAudio() }
  notifications.onclick = async () => {
    if (desktop) { desktop = false; notifications.textContent = 'Включить системные уведомления'; return }
    try {
      desktop = await Notification.requestPermission() === 'granted'
      notifications.textContent = desktop ? 'Выключить системные уведомления' : 'Системные уведомления запрещены'
    } catch { notifications.textContent = 'Системные уведомления недоступны' }
  }
  const notify = () => listeners.forEach(listener => listener(snapshot))
  const status = (message: string) => {
    connection.textContent = `${message} Последнее успешное обновление: ${lastSuccess ? new Date(lastSuccess).toLocaleTimeString('ru-RU') : 'ещё не было'}.`
  }
  let lastListSignature = ''
  const paintList = () => {
    heading.textContent = `Требуют внимания: ${uncertain ? 'неизвестно' : snapshot && !snapshot.attentionComplete ? `≥${snapshot.attentionTotalAtLeast}` : snapshot?.attention.length ?? '—'}`
    const signature = JSON.stringify([uncertain, snapshot?.attention, muted])
    if (signature === lastListSignature) {
      list.querySelectorAll<HTMLAnchorElement>('a[data-wait-since]').forEach(link => {
        const minutes = Math.max(0, Math.floor((Date.now() - Date.parse(link.dataset.waitSince!)) / 60000))
        link.textContent = (link.textContent ?? '').replace(/ожидание \d+ мин$/, `ожидание ${minutes} мин`)
      })
      return
    }
    lastListSignature = signature
    const focused = document.activeElement as HTMLElement | null
    const focusedEvent = focused?.closest<HTMLElement>('.venue-alert-row')?.dataset.eventId
    const focusedTag = focused?.tagName.toLowerCase()
    list.replaceChildren()
    for (const event of snapshot?.attention ?? []) {
      const row = el('div', { className: 'venue-alert-row' }); row.dataset.eventId = event.id
      const labels = { ORDER_CREATED: 'Первый заказ', BATCH_ADDED: 'Дозаказ · новые позиции', STAFF_CALL_CREATED: 'Вызов персонала', BOOKING_PENDING: 'Новая бронь', BOOKING_MESSAGE: 'Сообщение по брони', ORDER_SEND_UNKNOWN: 'Новые позиции · счёт не определён' }
      const age = Math.max(0, Math.floor((Date.now() - Date.parse(event.createdAt)) / 60000))
      const link = el('a', { text: `${event.billRequest ? 'Запрос счёта' : labels[event.kind] ?? 'Операционная задача'} · ${event.scheduledAt ? `${new Date(event.scheduledAt).toLocaleString('ru-RU')} (время устройства)` : event.tableNumber ? `стол ${event.tableNumber}` : 'стол не назначен'} · ожидание ${age} мин` })
      link.dataset.waitSince = event.createdAt
      const href = venueAttentionHref(event)
      if (href) link.href = href; else link.setAttribute('aria-disabled', 'true')
      const mute = el('button', { className: 'button-secondary button-small', text: muted.includes(event.id) ? 'Приглушено' : 'Приглушить' })
      mute.setAttribute('aria-label', muted.includes(event.id) ? 'Приглушено на устройстве' : 'Приглушить на устройстве')
      mute.disabled = muted.includes(event.id)
      mute.onclick = () => {
        mute.disabled = true
        void ledger.mute(event.id).then(() => { if (!disposed) { muted = [...new Set([...muted, event.id])]; paintList() } }).catch(error => {
          if (disposed) return
          if (error instanceof VenueLedgerBusyError) audioMessage = 'Приглушение не сохранено: координация вкладок занята. Повторите действие.'
          else { audioMessage = 'Хранилище недоступно. Автоматические сигналы отключены.'; sound = false }
          paintAudio(); paintList()
        })
      }
      append(row, link, mute); list.append(row)
    }
    if (uncertain) list.prepend(el('p', { text: 'Полнота списка не подтверждена. Откройте очередь заказов и вызовов.' }))
    else if (snapshot && !snapshot.attention.length) list.append(el('p', { text: 'Нет ожидающих событий.' }))
    if (focusedEvent && dialog.open) {
      const replacement = list.querySelector<HTMLElement>(`[data-event-id="${CSS.escape(focusedEvent)}"] ${focusedTag === 'button' ? 'button' : 'a'}`)
      ;(replacement ?? close).focus({ preventScroll: true })
    }
  }
  const consume = async (next: DashboardSnapshot) => {
    monitoringProblem = ''
    recent.textContent = ''
    if (!next.attentionComplete) {
      monitoringProblem = `Больше ${EVENT_CAPACITY} необработанных событий. Показана часть списка; автоматические сигналы приостановлены. Откройте очередь заказов и вызовов.`
      return
    }
    let lease: DeliveryLease | null = null
    try {
      const result = await ledger.observe(next.attention.map(event => event.id), sound && !playing && volume > 0 && audio?.state === 'running' && hasLocks && foreground() && currentIdentity(), desktop && hasLocks && foreground() && currentIdentity())
      lease = result.lease
      if (disposed || !foreground() || !currentIdentity()) { if (lease) await ledger.release(lease); return }
      coordinationProblem = ''; paintAudio()
      muted = result.muted
      recent.textContent = result.created ? `Новых ожидающих событий после предыдущего обновления: ${result.created}.` : ''
      if (!lease) return
      pendingLeases.add(lease)
      const audioSucceeded = lease.audio.length > 0 && sound && Date.now() < lease.expires ? await tone() : false
      let desktopSucceeded = false
      if (lease.desktop.length && desktop && supportsNotifications && Notification.permission === 'granted' && !disposed && foreground() && currentIdentity() && Date.now() < lease.expires) {
        try {
          const notice = new Notification('Событие заведения', { body: 'Проверьте оперативный экран Mini App.', tag: 'hookah-venue-attention', silent: true })
          window.setTimeout(() => notice.close(), 5000)
          desktopSucceeded = true
        } catch { /* unsupported WebViews keep the visual list and pending desktop receipt */ }
      }
      await ledger.finish(lease, audioSucceeded, desktopSucceeded)
      if (lease.audio.length && !audioSucceeded && !disposed && sound && volume > 0 && foreground() && currentIdentity()) {
        sound = false; audioProblem = 'приостановлен — нужно разрешить звук'; audioMessage = 'Браузер не разрешил или прервал звук. Нажмите «Включить звук» для повторной попытки.'; paintAudio()
      }
    } catch (error) {
      if (!(error instanceof VenueLedgerCapacityError)) { sound = false; desktop = false }
      monitoringProblem = error instanceof VenueLedgerCapacityError ? error.message : 'Хранилище недоступно или повреждено, либо синхронизация окон недоступна. Автоматический звук недоступен; список продолжает обновляться. Проверьте разрешения браузера.'
      coordinationProblem = 'недоступна координация — только визуальные оповещения'
      audioMessage = monitoringProblem; paintAudio()
    } finally { if (lease) pendingLeases.delete(lease) }
  }
  const clearSnapshot = () => { snapshot = null; muted = []; recent.textContent = ''; paintList(); notify() }
  const poll = async () => {
    if (disposed || denied || controller || !foreground()) return
    if (!currentIdentity()) { denied = true; clearSnapshot(); options.onAccessChanged('changed'); return }
    await (async () => {
      if (disposed || denied || controller || !foreground()) return
      const request = new AbortController(); controller = request
      refresh.disabled = true
      let timeout: number | undefined
      try {
        const response = await Promise.race([
          venueGetDashboard(backendUrl, access.venueId, { isDebug, getAccessToken, clearSession }, request.signal),
          new Promise<null>(resolve => { timeout = window.setTimeout(() => { request.abort(); resolve(null) }, 10000) })
        ])
        if (disposed || controller !== request || !currentIdentity()) return
        if (!response) { uncertain = true; paintList(); status('Нет связи: превышено время ожидания. Повторим автоматически.'); return }
        if (!response.ok) {
          if (response.error.status === 401 || response.error.status === 403) {
            denied = true; sound = false; desktop = false; clearSnapshot(); paintAudio()
            status('Доступ потерян. Откройте выбор заведения или войдите заново.'); options.onAccessChanged('denied')
          } else if (response.error.code === 'MONITORING_CAPACITY') { uncertain = true; paintList(); status('Мониторинг ограничен: ответ превышает 128 КиБ. Данные устарели; откройте очередь или обновите Mini App.') }
          else { uncertain = true; paintList(); status('Нет связи: данные могут быть устаревшими. Повторим обновление автоматически.') }
          return
        }
        const next = response.data
        if (![2, 3].includes(next.monitoringVersion) || next.eventCapacity !== EVENT_CAPACITY || !Array.isArray(next.attention) || next.attention.length > EVENT_CAPACITY || typeof next.attentionComplete !== 'boolean' || !Number.isInteger(next.attentionTotalAtLeast) || (next.attentionComplete ? next.attentionTotalAtLeast !== next.attention.length : next.attentionTotalAtLeast !== EVENT_CAPACITY + 1 || next.attention.length !== EVENT_CAPACITY) || !Number.isSafeInteger(next.venueId) || !Number.isSafeInteger(next.userId) || !['OWNER', 'MANAGER', 'STAFF'].includes(next.role)) throw new VenueLedgerCapacityError('Несовместимый или слишком большой ответ мониторинга. Обновите Mini App.')
        if (new Set(next.attention.map(event => event.id)).size !== next.attention.length || next.attention.some(event =>
          !['ORDER_CREATED', 'BATCH_ADDED', 'STAFF_CALL_CREATED', 'BOOKING_PENDING', 'BOOKING_MESSAGE', 'ORDER_SEND_UNKNOWN'].includes(event.kind) ||
          (event.kind === 'BOOKING_MESSAGE' && (!Number.isSafeInteger(event.threadId) || (event.threadId ?? 0) <= 0)) ||
          !Number.isFinite(Date.parse(event.createdAt)))) throw new VenueLedgerCapacityError('Неподдерживаемые задачи. Полнота списка не подтверждена.')
        if (next.userId !== userId || next.venueId !== access.venueId || next.role !== access.role) {
          denied = true; clearSnapshot(); status('Доступ изменился. Обновляем разрешения.'); options.onAccessChanged('denied'); return
        }
        if (!foreground()) return
        await consume(next)
        if (disposed) return
        snapshot = next; uncertain = false; lastSuccess = Date.now(); paintList(); notify(); status(monitoringProblem ? `Связь есть. Мониторинг ограничен: ${monitoringProblem}` : 'Связь есть.')
      } catch (error) {
        if (!disposed) {
          if (error instanceof VenueLedgerCapacityError) { uncertain = true; paintList() }
          status(error instanceof VenueLedgerCapacityError ? `Мониторинг ограничен: ${error.message}` : 'Нет связи: обновление не завершено. Повторим автоматически.')
        }
      }
      finally { window.clearTimeout(timeout); if (controller === request) { controller = null; refresh.disabled = false } }
    })()
  }
  const schedule = () => {
    window.clearTimeout(timer)
    if (disposed || denied || !foreground()) return
    timer = window.setTimeout(async () => { await poll(); schedule() }, POLL_MS)
  }
  const refreshNow = () => { void poll().finally(schedule) }
  const lifecycle = () => {
    if (!currentIdentity()) { denied = true; clearSnapshot(); options.onAccessChanged('changed'); return }
    if (!foreground()) {
      cancelTones.forEach(cancel => cancel())
      pendingLeases.forEach(lease => { void ledger.release(lease).catch(() => undefined) })
      window.clearTimeout(timer); controller?.abort(); controller = null; refresh.disabled = false
      status(navigator.onLine ? 'Mini App неактивен: обновление и звук приостановлены.' : 'Нет связи: устройство offline. Данные могут быть устаревшими.')
    } else refreshNow()
  }
  const activated = () => { active = true; lifecycle() }
  const deactivated = () => { active = false; lifecycle() }
  refresh.onclick = refreshNow
  document.addEventListener('visibilitychange', lifecycle)
  window.addEventListener('online', lifecycle); window.addEventListener('offline', lifecycle)
  telegram.webApp?.onEvent?.('activated', activated); telegram.webApp?.onEvent?.('deactivated', deactivated)
  const audioPreferences = (event: StorageEvent) => {
    if (event.key !== audioKey) return
    try {
      const preferences = JSON.parse(event.newValue ?? '{}')
      if (preferences.enabled === false) { sound = false; cancelTones.forEach(cancel => cancel()) }
      if (Number.isFinite(preferences.volume)) { volume = Math.max(0, Math.min(1, preferences.volume)); volumeInput.value = String(volume * 100); if (volume === 0) cancelTones.forEach(cancel => cancel()) }
    } catch { sound = false }
    paintAudio()
  }
  window.addEventListener('storage', audioPreferences)
  paintAudio(); refreshNow()
  const dispose = () => {
    if (disposed) return
    disposed = true; window.clearTimeout(timer); controller?.abort(); cancelTones.forEach(cancel => cancel()); void audio?.close()
    pendingLeases.forEach(lease => { void ledger.release(lease).catch(() => undefined) })
    snapshot = null; notify()
    document.removeEventListener('visibilitychange', lifecycle)
    window.removeEventListener('online', lifecycle); window.removeEventListener('offline', lifecycle)
    telegram.webApp?.offEvent?.('activated', activated); telegram.webApp?.offEvent?.('deactivated', deactivated)
    window.removeEventListener('storage', audioPreferences)
    if (dialog.open) dialog.close()
    listeners.clear(); panel.remove()
    if (activeDispose === dispose) activeDispose = null
  }
  activeDispose = dispose
  return {
    subscribe(listener) { listeners.add(listener); listener(snapshot); return () => { listeners.delete(listener) } },
    refresh: refreshNow, dispose
  }
}
