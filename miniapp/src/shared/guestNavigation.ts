import { getTelegramContext } from './telegram'

type NavigationOptions = {
  replace?: boolean
  root?: boolean
  focusVenue?: number
  catalogChosen?: boolean
  parent?: string
}

type GuestBranch = { id: string; home: string; catalogChosen?: boolean }
type GuestEntry = {
  scope: string
  visit: string
  visitHome: string
  visitEnded: boolean
  branch: GuestBranch
  trail: string[]
  id: string
}
const key = 'hookahGuestNavigation'

const routeParams: Record<string, readonly string[]> = {
  venue: ['id', 'staff', 'cartLineRef', 'browseMenu', 'media'],
  cart: ['focusLineRef'],
  bookings: ['venueId', 'venue_id'],
  messages: ['venueId', 'venue_id', 'threadId', 'thread_id'],
  support: ['venueId', 'venue_id', 'threadId', 'thread_id'],
  'account/history': ['kind', 'visit']
}

// The launch URL remains available to Telegram auth. Navigation keeps only Guest route inputs.
export function guestRouteHash(hash = window.location.hash): string {
  const match = /^#\/?(catalog(?:\/promotions)?|venue(?:\/\d+)?|cart|order|bookings|account(?:\/(?:history|favorites|loyalty|profile))?|messages|support)\/?(?:\?([^#]*))?$/.exec(hash)
  if (!match) return '#/catalog'
  const [, path, query] = match
  const allowed = routeParams[path.startsWith('venue/') ? 'venue' : path] ?? []
  const clean = new URLSearchParams()
  new URLSearchParams(query).forEach((value, name) => {
    if (allowed.includes(name) && !clean.has(name)) clean.set(name, value)
  })
  return `#/${path}${clean.size ? `?${clean}` : ''}`
}

// A reload can continue the same Guest trail. Never rewind an unknown browser/mode entry.
export function createGuestNavigation(
  onChange: () => void,
  backTarget: () => string | null,
  contentRoot: () => string
) {
  const mount = `${Date.now()}-${Math.random()}`
  let serial = 0
  const nextId = () => `${mount}/${++serial}`
  const hash = guestRouteHash()
  const stored = window.history.state?.[key] as GuestEntry | undefined
  const initial = stored?.scope && stored.visit && stored.branch?.id && Array.isArray(stored.trail) &&
    stored.trail.length <= window.history.length && stored.trail.at(-1) === hash &&
    stored.trail.every(route => typeof route === 'string' && route === guestRouteHash(route)) ? stored : null
  const scope = initial?.scope ?? mount
  let visit = initial?.visit ?? scope
  // Records whether this history boundary was reset after a server-confirmed end.
  let visitEnded = initial?.visitEnded === true
  let boundary = new URL(window.location.href)
  boundary.hash = guestRouteHash(initial?.visitHome ?? initial?.branch?.home ?? hash)
  // Forward entries survive replaceState and reload. Carry their branch identity with the entry.
  let branch: GuestBranch = {
    id: initial?.branch?.id ?? visit,
    home: guestRouteHash(initial?.branch?.home ?? boundary.hash),
    catalogChosen: initial?.branch?.catalogChosen === true
  }
  let entry: GuestEntry
  let seen = ''
  let traversing = false
  let pendingOptions: NavigationOptions | null = null
  let pendingHash: string | null = null
  let pendingRoot: { url: URL; home: string; options: NavigationOptions } | null = null

  const write = (url: URL, trail: string[], options: NavigationOptions, replace: boolean) => {
    // With SDK credentials, a mixed deep link can display its clean URL immediately.
    // A hash-only launch keeps its bootstrap credentials until the first transition.
    const hash = trail[trail.length - 1]
    if (url.hash.startsWith('#/') && url.hash !== hash && getTelegramContext().webApp?.initData) url.hash = hash
    entry = { scope, visit, visitHome: boundary.hash, visitEnded, branch, trail, id: nextId() }
    const state = {
      ...window.history.state,
      [key]: entry,
      hookahGuestDepth: trail.length - 1,
      hookahGuestPrevious: trail.at(-2) ?? null,
      hookahFocusVenue: options.focusVenue ?? null,
      hookahGuestCatalog: url.hash.startsWith('#/catalog') && branch.catalogChosen === true
    }
    window.history[replace ? 'replaceState' : 'pushState'](state, '', url)
    return entry
  }
  const notify = () => {
    const marker = `${entry.id}:${window.location.href}`
    if (seen === marker) return
    seen = marker
    onChange()
  }
  const read = (): GuestEntry | null => {
    const value = window.history.state?.[key] as GuestEntry | undefined
    return value?.scope === scope && Array.isArray(value.trail) && value.trail.at(-1) === guestRouteHash()
      ? value : null
  }
  const applyRoot = (url: URL, home: string, options: NavigationOptions) => {
    const anchor = new URL(url)
    anchor.hash = home
    boundary.search = anchor.search
    branch = { id: nextId(), home, catalogChosen: options.catalogChosen ?? branch.catalogChosen }
    write(anchor, [home], options, true)
    if (url.hash !== home) write(url, [home, url.hash], options, false)
    notify()
  }
  const sync = () => {
    const stored = window.history.state?.[key] as GuestEntry | undefined
    traversing = false
    // A native Back/Forward can land on an obsolete physical entry, even after reload.
    // Replace it before rendering a route or resolving any table from its URL.
    const oldVisit = stored ? stored.visit !== visit : window.location.search !== boundary.search
    const oldBranch = stored && (stored.branch?.id ?? stored.visit) !== branch.id
    if (oldVisit || oldBranch) {
      pendingRoot = null
      pendingHash = null
      pendingOptions = null
      const url = new URL(boundary)
      if (!oldVisit) url.hash = branch.home
      write(url, [url.hash], {}, true)
      notify()
      return
    }
    const owned = read()
    if (pendingRoot) {
      const pending = pendingRoot
      pendingRoot = null
      if (owned?.trail.length === 1) {
        applyRoot(pending.url, pending.home, pending.options)
        return
      }
    }
    if (owned) {
      entry = owned
      if (pendingOptions) {
        const url = new URL(window.location.href)
        if (pendingHash) url.hash = pendingHash
        write(url, [...owned.trail.slice(0, -1), url.hash], pendingOptions, true)
      }
    }
    else write(new URL(window.location.href), [guestRouteHash()], {}, true)
    pendingOptions = null
    pendingHash = null
    notify()
  }
  const first = write(new URL(window.location.href), initial?.trail ?? [hash], {}, true)
  seen = `${first.id}:${window.location.href}`
  window.addEventListener('popstate', sync)
  window.addEventListener('hashchange', sync)

  return {
    resetVisit: (url: URL, { ended = false }: { ended?: boolean } = {}) => {
      visit = nextId()
      visitEnded = ended
      boundary = new URL(url)
      branch = { id: visit, home: url.hash, catalogChosen: url.hash === '#/catalog' }
      pendingRoot = null
      pendingOptions = null
      pendingHash = null
      traversing = false
      write(url, [url.hash], {}, true)
      notify()
    },
    hasEndedVisitRoot: (url: URL) => visitEnded && boundary.href === url.href,
    isCatalogChosen: () => branch.catalogChosen === true,
    previousHash: () => entry.trail.at(-2) ?? null,
    navigate: (destination: string, options: NavigationOptions = {}) => {
      const hash = guestRouteHash(destination)
      const url = new URL(window.location.href)
      url.hash = hash
      if (pendingRoot) {
        if (options.root) pendingRoot = { url, home: /^(#\/account|#\/messages)$/.test(hash) ? contentRoot() : hash, options }
        return
      }
      const catalogChoiceChanged = options.catalogChosen !== undefined && options.catalogChosen !== branch.catalogChosen
      if (traversing || (url.href === window.location.href && !catalogChoiceChanged)) return
      if (options.root) {
        const home = /^(#\/account|#\/messages)$/.test(hash) ? contentRoot() : hash
        if (entry.trail.length === 1) applyRoot(url, home, options)
        else {
          pendingRoot = { url, home, options }
          traversing = true
          window.history.go(1 - entry.trail.length)
        }
        return
      }
      const ancestor = entry.trail.slice(0, -1).lastIndexOf(options.parent ?? hash)
      if (ancestor >= 0 && (!options.replace || ancestor === entry.trail.length - 2)) {
        traversing = true
        pendingOptions = options
        pendingHash = options.parent ? hash : null
        window.history.go(ancestor - entry.trail.length + 1)
        return
      }
      const replace = options.replace === true || Boolean(options.parent)
      // Keep the launch URL available to authentication on entry. Once leaving it,
      // the native browser parent can use the same route as the logical parent.
      if (!replace && window.location.hash !== guestRouteHash()) {
        window.history.replaceState(window.history.state, '', guestRouteHash())
      }
      const trail = replace ? [...entry.trail.slice(0, -1), hash] : [...entry.trail, hash]
      write(url, trail, options, replace)
      notify()
    },
    back: () => {
      if (traversing || pendingRoot) return
      const target = backTarget()
      if (!target || target === window.location.hash) return
      const parent = entry.trail.slice(0, -1).lastIndexOf(target)
      if (parent >= 0) {
        traversing = true
        window.history.go(parent - entry.trail.length + 1)
      } else {
        const url = new URL(window.location.href)
        url.hash = target
        // A synthetic parent starts its own logical trail. Other physical entries
        // remain accessible through native Back/Forward, including legacy hash links.
        write(url, [target], {}, true)
        notify()
      }
    },
    canGoBack: () => {
      const target = backTarget()
      return Boolean(target && target !== window.location.hash)
    },
    dispose: () => {
      pendingRoot = null
      pendingOptions = null
      pendingHash = null
      window.removeEventListener('popstate', sync)
      window.removeEventListener('hashchange', sync)
    }
  }
}
