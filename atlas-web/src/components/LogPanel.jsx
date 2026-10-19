import { useEffect, useReducer, useState } from 'react'
import { MriButton } from '@mriqbox/ui-kit'
import { X } from 'lucide-react'
import { initialTabs, logUrl, tabsReducer } from '../lib/logTabs.js'

/** Um EventSource por aba (vive enquanto a aba existe, ativa ou nao); reabre quando o filtro muda. */
function TabStream({ tab, dispatch }) {
  const { key, filter, regex, gen } = tab
  useEffect(() => {
    if (typeof EventSource === 'undefined') return undefined
    const es = new EventSource(logUrl(key, filter, regex))
    es.addEventListener('line', (ev) => dispatch({ type: 'lines', key, lines: [JSON.parse(ev.data)] }))
    es.addEventListener('dropped', (ev) => dispatch({ type: 'dropped', key, n: JSON.parse(ev.data).n }))
    es.addEventListener('end', (ev) => {
      // fim de verdade (fim do log, edge caiu, duracao maxima): sem reconexao automatica
      es.close()
      dispatch({ type: 'end', key, reason: JSON.parse(ev.data).reason })
    })
    return () => es.close()
  }, [key, filter, regex, gen, dispatch])
  return null
}

function FilterForm({ tab, dispatch }) {
  const [text, setText] = useState(tab.filter)
  const [regex, setRegex] = useState(tab.regex)
  return (
    <form
      className="flex items-center gap-2"
      onSubmit={(e) => {
        e.preventDefault()
        dispatch({ type: 'filter', key: tab.key, filter: text, regex })
      }}
    >
      <input
        aria-label="filtro"
        className="h-8 w-64 rounded border border-border bg-background px-2 text-sm"
        placeholder={regex ? 'regex (RE2)' : 'texto'}
        value={text}
        onChange={(e) => setText(e.target.value)}
      />
      <label className="flex items-center gap-1 text-xs">
        <input aria-label="regex" type="checkbox" checked={regex} onChange={(e) => setRegex(e.target.checked)} />
        regex
      </label>
    </form>
  )
}

/**
 * Painel de log ao vivo (F4): abas (max 4, sem repetir), um stream SSE por aba, pausa sem perder linha, filtro
 * no hub (texto ou RE2), aviso de linhas descartadas. As linhas ja chegam redigidas no edge e de novo no hub.
 */
export function LogPanel({ targets, onClose }) {
  const [state, dispatch] = useReducer(tabsReducer, initialTabs)

  useEffect(() => {
    for (const t of targets) dispatch({ type: 'open', key: t.key, label: t.label })
  }, [targets])

  const tab = state.tabs.find((t) => t.key === state.active)
  const close = (key) => {
    dispatch({ type: 'close', key })
    onClose(key)
  }

  return (
    <section className="flex h-[40vh] flex-col rounded-lg border border-border bg-card" data-testid="log-panel">
      {state.tabs.map((t) => (
        <TabStream key={t.key} tab={t} dispatch={dispatch} />
      ))}
      <div className="flex items-center gap-1 border-b border-border px-2">
        {state.tabs.map((t) => (
          <div key={t.key} className={`flex items-center gap-1 px-2 py-1 text-sm ${t.key === state.active ? 'border-b-2 border-primary' : 'text-muted-foreground'}`}>
            <button type="button" onClick={() => dispatch({ type: 'activate', key: t.key })}>
              {t.label}
            </button>
            <button type="button" aria-label={`fechar ${t.label}`} onClick={() => close(t.key)}>
              <X className="h-3 w-3" />
            </button>
          </div>
        ))}
        <span className="ml-auto rounded border border-green-500/50 px-1 text-[10px] text-green-500" title="segredos conhecidos sao trocados por [redacted:...] no edge e no hub">
          redação ativa
        </span>
      </div>
      {tab && (
        <>
          <div className="flex items-center gap-2 px-2 py-1">
            <FilterForm key={`${tab.key}:${tab.gen}`} tab={tab} dispatch={dispatch} />
            <MriButton size="sm" variant="outline" onClick={() => dispatch({ type: 'pause', key: tab.key, paused: !tab.paused })}>
              {tab.paused ? 'retomar' : 'pausar'}
            </MriButton>
            {tab.dropped > 0 && <span className="text-xs text-amber-500">{tab.dropped} linhas descartadas</span>}
            {tab.ended && <span className="text-xs text-muted-foreground">fim: {tab.ended}</span>}
          </div>
          <pre className="flex-1 overflow-auto px-2 font-mono text-xs leading-5">
            {tab.lines.map((l, i) => (
              <div key={i} className={l.stream === 'stderr' ? 'text-red-400' : undefined}>
                {l.text}
                {l.truncated && <span className="text-muted-foreground"> [cortada]</span>}
              </div>
            ))}
          </pre>
        </>
      )}
    </section>
  )
}
