import { clearSession, getAccessToken } from '../shared/api/auth'
import { requestApi } from '../shared/api/request'
import { append, el } from '../shared/ui/dom'

export type GuestProfileDraft = Partial<Record<'name' | 'birthday', string>>

type Profile = { displayName: string | null; birthday: string | null }

export function renderGuestProfile(
  root: HTMLElement,
  backendUrl: string,
  isDebug: boolean,
  draft: GuestProfileDraft,
  onBack: () => void
) {
  const controller = new AbortController()
  const deps = { isDebug, getAccessToken, clearSession }
  const section = el('section', { className: 'card guest-profile-form' })
  const back = el('button', { text: 'К профилю', className: 'button-secondary button-small' })
  const status = el('p', { className: 'status' })
  status.setAttribute('role', 'status')
  const retry = el('button', { text: 'Повторить', className: 'button-secondary button-small' })
  const fields = el('div', { className: 'guest-profile-fields' })
  append(section, back, el('h1', { text: 'Личные данные' }), status, retry, fields)
  root.replaceChildren(section)
  back.addEventListener('click', onBack)
  let saving = false

  const renderFields = (profile: Profile) => {
    const makeField = (field: 'name' | 'birthday', label: string, saved: string | null, hint: string) => {
      const form = el('form', { className: 'guest-profile-field' })
      const input = el('input')
      input.type = 'text'
      input.name = field
      input.maxLength = field === 'name' ? 40 : 5
      input.autocomplete = field === 'name' ? 'given-name' : 'off'
      input.placeholder = field === 'name' ? 'Как к вам обращаться' : 'ДД.ММ'
      const immutable = field === 'birthday' && saved !== null
      const fieldLabel = el('label', { text: label })
      fieldLabel.appendChild(input)
      input.value = immutable ? saved : (draft[field] ?? saved ?? '')
      input.readOnly = immutable
      const feedback = el('p', { className: 'status' })
      feedback.setAttribute('role', 'status')
      const actions = el('div', { className: 'venue-inline-actions' })
      const save = el('button', { text: field === 'name' ? 'Сохранить имя' : 'Сохранить дату' })
      save.type = 'submit'
      const cancel = el('button', { text: 'Отменить изменения', className: 'button-secondary' })
      cancel.type = 'button'
      const update = () => {
        const dirty = input.value !== (saved ?? '')
        if (dirty) draft[field] = input.value
        else delete draft[field]
        save.disabled = !dirty
        cancel.hidden = !dirty
        feedback.textContent = dirty ? 'Есть несохранённые изменения.' : ''
      }
      append(form, fieldLabel, el('p', { className: 'status', text: hint }), feedback)
      if (immutable) return form
      append(actions, save, cancel)
      form.appendChild(actions)
      update()
      input.addEventListener('input', update)
      cancel.addEventListener('click', () => { input.value = saved ?? ''; update() })
      form.addEventListener('submit', async (event) => {
        event.preventDefault()
        if (saving || save.disabled) return
        const value = input.value.trim()
        if (field === 'name' ? !value : !/^\d{2}\.\d{2}$/.test(value)) {
          feedback.textContent = field === 'name' ? 'Введите имя.' : 'Введите дату в формате ДД.ММ.'
          input.focus()
          return
        }
        saving = true
        save.disabled = cancel.disabled = input.disabled = true
        feedback.textContent = 'Сохраняем…'
        const payload = field === 'name' ? { displayName: value } : { birthday: value }
        const result = await requestApi<Profile>(backendUrl, `/api/guest/profile/${field}`, {
          method: 'PUT', headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify(payload), signal: controller.signal
        }, deps)
        if (controller.signal.aborted) return
        saving = false
        save.disabled = cancel.disabled = input.disabled = false
        if (!result.ok) {
          feedback.textContent = result.error.status === 400
            ? result.error.message || 'Проверьте введённое значение.'
            : 'Не удалось сохранить. Проверьте соединение и повторите.'
          return
        }
        delete draft[field]
        renderFields(result.data)
        status.textContent = field === 'name' ? 'Имя сохранено.' : 'День рождения сохранён.'
      })
      return form
    }
    if (profile.birthday) delete draft.birthday
    fields.replaceChildren(
      makeField('name', 'Имя', profile.displayName, 'Это имя увидит персонал в заказах, вызовах и бронях.'),
      makeField('birthday', 'День рождения', profile.birthday, profile.birthday
        ? 'Дата сохранена. Изменить её самостоятельно нельзя.'
        : 'Укажите день и месяц для предложений ко дню рождения. После сохранения дату нельзя изменить.')
    )
  }

  const load = async () => {
    retry.hidden = true
    status.textContent = 'Загружаем личные данные…'
    const result = await requestApi<Profile>(backendUrl, '/api/guest/profile', { signal: controller.signal }, deps)
    if (controller.signal.aborted) return
    if (!result.ok) {
      status.textContent = 'Не удалось загрузить личные данные. Попробуйте ещё раз.'
      retry.hidden = false
      return
    }
    status.textContent = ''
    renderFields(result.data)
  }
  retry.addEventListener('click', () => void load())
  void load()
  return () => controller.abort()
}
