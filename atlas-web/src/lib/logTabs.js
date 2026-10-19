// Abas do painel de log (design §3.4): no maximo 4, sem repetir alvo; cada aba guarda as ultimas MAX_LINES linhas,
// o total descartado (dropped do hub) e, pausada, segura as linhas novas para soltar ao retomar.
export const MAX_TABS = 4
export const MAX_LINES = 1000
export const TAIL = 200

export const initialTabs = { tabs: [], active: null }

const newTab = (key, label) => ({ key, label, lines: [], held: [], dropped: 0, paused: false, ended: null, filter: '', regex: false, gen: 0 })
const cap = (lines) => (lines.length > MAX_LINES ? lines.slice(lines.length - MAX_LINES) : lines)

function update(state, key, fn) {
  if (!state.tabs.some((t) => t.key === key)) return state
  return { ...state, tabs: state.tabs.map((t) => (t.key === key ? fn(t) : t)) }
}

export function tabsReducer(state, action) {
  switch (action.type) {
    case 'open': {
      if (state.tabs.some((t) => t.key === action.key)) return { ...state, active: action.key }
      const tabs = [...state.tabs, newTab(action.key, action.label)]
      return { tabs: tabs.slice(Math.max(0, tabs.length - MAX_TABS)), active: action.key }
    }
    case 'close': {
      const i = state.tabs.findIndex((t) => t.key === action.key)
      if (i < 0) return state
      const tabs = state.tabs.filter((t) => t.key !== action.key)
      if (tabs.length === 0) return initialTabs
      const active = state.active === action.key ? tabs[Math.max(0, i - 1)].key : state.active
      return { tabs, active }
    }
    case 'activate':
      return state.tabs.some((t) => t.key === action.key) ? { ...state, active: action.key } : state
    case 'lines':
      return update(state, action.key, (t) =>
        t.paused ? { ...t, held: cap([...t.held, ...action.lines]) } : { ...t, lines: cap([...t.lines, ...action.lines]) })
    case 'dropped':
      return update(state, action.key, (t) => ({ ...t, dropped: t.dropped + action.n }))
    case 'end':
      return update(state, action.key, (t) => ({ ...t, ended: action.reason }))
    case 'pause':
      return update(state, action.key, (t) =>
        action.paused ? { ...t, paused: true } : { ...t, paused: false, lines: cap([...t.lines, ...t.held]), held: [] })
    case 'filter':
      // filtro novo e outro stream no hub: recomeca a aba (gen muda e o EventSource reabre)
      return update(state, action.key, (t) => ({ ...newTab(t.key, t.label), filter: action.filter, regex: action.regex, gen: t.gen + 1 }))
    default:
      return state
  }
}

export function logUrl(key, filter, regex) {
  const q = new URLSearchParams({ node: key, tail: String(TAIL) })
  if (filter) {
    q.set('filter', filter)
    if (regex) q.set('regex', 'true')
  }
  return `/api/logs/stream?${q}`
}
