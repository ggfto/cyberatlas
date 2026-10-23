import { useEffect, useState } from 'react'
import { applyPatch } from './patch.js'

/**
 * Snapshot do mapa ao vivo (F3), comum ao App e ao /embed: snapshot ao conectar e patch a cada mudanca; o
 * EventSource reconecta sozinho mandando o Last-Event-ID. Sem EventSource (ambiente antigo/teste), uma leitura
 * simples. So leitura: GET no stream ou no /api/topology.
 */
export function useTopology(onStreamError) {
  const [snapshot, setSnapshot] = useState({ nodes: [], edges: [] })
  const [error, setError] = useState(null)

  useEffect(() => {
    if (typeof EventSource !== 'undefined') {
      const es = new EventSource('/api/topology/stream')
      es.addEventListener('snapshot', (ev) => {
        setError(null)
        setSnapshot(JSON.parse(ev.data))
      })
      es.addEventListener('patch', (ev) => setSnapshot((cur) => applyPatch(cur, JSON.parse(ev.data))))
      es.addEventListener('error', () => {
        setError('stream interrompido, reconectando')
        onStreamError?.()
      })
      return () => es.close()
    }
    const ctrl = new AbortController()
    fetch('/api/topology', { signal: ctrl.signal, headers: { Accept: 'application/json' } })
      .then(async (r) => {
        if (!r.ok) throw new Error(`HTTP ${r.status}`)
        setSnapshot(await r.json())
      })
      .catch((e) => {
        if (e.name !== 'AbortError') setError(e.message)
      })
    return () => ctrl.abort()
  }, [onStreamError])

  return { snapshot, setSnapshot, error, setError }
}

/** Posicoes manuais gravadas ({ key: {x, y, rev} }), so leitura. */
export function fetchManualLayout() {
  return fetch('/api/layout', { headers: { Accept: 'application/json' } })
    .then(async (r) => {
      const rows = r.ok ? await r.json() : []
      return Array.isArray(rows) ? Object.fromEntries(rows.map((p) => [p.nodeKey, p])) : {}
    })
    .catch(() => ({}))
}
