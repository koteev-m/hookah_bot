import { REQUEST_ABORTED_CODE } from '../api/abort'
import type { ApiErrorInfo } from '../api/types'

export type ErrorDetailsOptions = {
  isDebug: boolean
  extraNotes?: string[]
}

const copyResetTimers = new WeakMap<HTMLElement, number>()

export function renderErrorDetails(
  container: HTMLElement,
  error: ApiErrorInfo,
  options: ErrorDetailsOptions
) {
  const existingTimer = copyResetTimers.get(container)
  if (existingTimer) {
    window.clearTimeout(existingTimer)
    copyResetTimers.delete(container)
  }
  container.textContent = ''
  if (!options.isDebug) {
    return
  }
  if (error.code === REQUEST_ABORTED_CODE) {
    return
  }
  if (!error.requestId && !error.code && !error.message && !(options.extraNotes?.length ?? 0)) {
    return
  }
  const details = document.createElement('details')
  details.className = 'error-details'
  details.open = false
  const summary = document.createElement('summary')
  summary.textContent = 'Подробнее'
  details.appendChild(summary)
  const list = document.createElement('ul')
  if (error.requestId) {
    const item = document.createElement('li')
    item.textContent = `Request ID: ${error.requestId}`
    list.appendChild(item)
  }
  if (error.code) {
    const item = document.createElement('li')
    item.textContent = `Код: ${error.code}`
    list.appendChild(item)
  }
  if (error.message) {
    const item = document.createElement('li')
    item.textContent = `Сообщение: ${error.message}`
    list.appendChild(item)
  }
  if (options.extraNotes?.length) {
    options.extraNotes.forEach((note) => {
      const item = document.createElement('li')
      item.textContent = note
      list.appendChild(item)
    })
  }
  const clipboardAvailable = Boolean(navigator?.clipboard?.writeText)
  if (error.requestId && !clipboardAvailable) {
    const item = document.createElement('li')
    item.textContent = 'Clipboard недоступен'
    list.appendChild(item)
  }
  details.appendChild(list)
  if (error.requestId && clipboardAvailable) {
    const actions = document.createElement('div')
    actions.className = 'error-details-actions'
    const copyButton = document.createElement('button')
    copyButton.className = 'button-small'
    const copyLabelDefault = 'Скопировать requestId'
    const copyLabelSuccess = 'Скопировано'
    const copyResetDelayMs = 1500
    copyButton.textContent = copyLabelDefault
    copyButton.addEventListener('click', async () => {
      if (!navigator?.clipboard?.writeText) {
        return
      }
      try {
        await navigator.clipboard.writeText(error.requestId ?? '')
        copyButton.textContent = copyLabelSuccess
        const pendingTimer = copyResetTimers.get(container)
        if (pendingTimer) {
          window.clearTimeout(pendingTimer)
        }
        const resetTimer = window.setTimeout(() => {
          copyButton.textContent = copyLabelDefault
          copyResetTimers.delete(container)
        }, copyResetDelayMs)
        copyResetTimers.set(container, resetTimer)
      } catch (copyError) {
        console.warn('Failed to copy requestId', copyError)
      }
    })
    actions.appendChild(copyButton)
    details.appendChild(actions)
  }
  container.appendChild(details)
}
