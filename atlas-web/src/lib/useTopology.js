import { useEffect, useState } from 'react'
import { applyPatch } from './patch.js'

/**
 * Teto do atraso aleatorio antes de conferir a sessao quando o hub fecha o stream por expiracao (`end` com
 * `reason: "auth"`). Todas as abas e iframes do mesmo login recebem o end no mesmo segundo; o hub so renova o token
 * (refresh) na proxima requisicao, e o Authentik invalida o refresh token usado. Espalhando as conferencias, a
 * primeira renova e as outras ja chegam com o cookie novo.
 */
export const AUTH_RECHECK_JITTER_MS = 3000

/**
 * Snapshot do mapa ao vivo (F3), comum ao App e ao /embed: snapshot ao conectar e patch a cada mudanca; o
 * EventSource reconecta sozinho mandando o Last-Event-ID. Sem EventSource (ambiente antigo/teste), uma leitura
 * simples. So leitura: GET no stream ou no /api/topology.
 *
 * O hub fecha o stream quando o token da sessao vence (`end {reason:"auth"}`, issue #20). Ai uma unica leitura
 * confere a sessao: viva (o hub renovou), o stream reabre; 401, `onSessionLost` (sem ele, `onStreamError`).
 */
export function useTopology(onStreamError, onSessionLost) {
  const [snapshot, setSnapshot] = useState({ nodes: [], edges: [] })
  const [error, setError] = useState(null)
  const [generation, setGeneration] = useState(0)

  useEffect(() => {
    if (typeof EventSource !== 'undefined') {
      const es = new EventSource('/api/topology/stream')
      let recheck = null
      let gone = false
      es.addEventListener('snapshot', (ev) => {
        setError(null)
        setSnapshot(JSON.parse(ev.data))
      })
      es.addEventListener('patch', (ev) => setSnapshot((cur) => applyPatch(cur, JSON.parse(ev.data))))
      es.addEventListener('error', () => {
        setError('stream interrompido, reconectando')
        onStreamError?.()
      })
      es.addEventListener('end', (ev) => {
        if (endReason(ev) !== 'auth') return
        es.close() // sem isto o EventSource reconectaria sozinho, junto com a conferencia
        setError('sessao expirou, renovando')
        recheck = setTimeout(() => {
          sessionAlive().then((alive) => {
            if (gone) return
            if (alive) setGeneration((g) => g + 1)
            else (onSessionLost ?? onStreamError)?.()
          })
        }, Math.random() * AUTH_RECHECK_JITTER_MS)
      })
      return () => {
        gone = true
        clearTimeout(recheck)
        es.close()
      }
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
  }, [onStreamError, onSessionLost, generation])

  return { snapshot, setSnapshot, error, setError }
}

function endReason(ev) {
  try {
    return JSON.parse(ev.data)?.reason
  } catch {
    return null
  }
}

/** So 401 e sessao perdida; outro erro reabre o stream, que tem o proprio aviso de erro. */
function sessionAlive() {
  return fetch('/api/layout', { headers: { Accept: 'application/json' } })
    .then((r) => r.status !== 401)
    .catch(() => true)
}

/**
 * Posicoes manuais gravadas ({ key: {x, y, rev} }), so leitura. `onUnauthorized` e chamado no 401 (sem sessao),
 * que o /embed usa para oferecer o login.
 */
export function fetchManualLayout(onUnauthorized) {
  return fetch('/api/layout', { headers: { Accept: 'application/json' } })
    .then(async (r) => {
      if (r.status === 401) onUnauthorized?.()
      const rows = r.ok ? await r.json() : []
      return Array.isArray(rows) ? Object.fromEntries(rows.map((p) => [p.nodeKey, p])) : {}
    })
    .catch(() => ({}))
}
