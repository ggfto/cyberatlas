import { useCallback, useEffect, useMemo, useState } from 'react'
import { MriPageHeader } from '@mriqbox/ui-kit'
import { Network } from 'lucide-react'
import { InfraMap } from './components/InfraMap.jsx'
import { buildInfraTopology } from './lib/topology.js'
import { applyPatch } from './lib/patch.js'
import { LogPanel } from './components/LogPanel.jsx'
import { MAX_TABS } from './lib/logTabs.js'
import { applyLayout, saveMove } from './lib/layout.js'

const WITH_LOGS = new Set(['CONTAINER', 'POD'])

export default function App() {
  const [snapshot, setSnapshot] = useState({ nodes: [], edges: [] })
  const [error, setError] = useState(null)
  const [logsOn, setLogsOn] = useState(false)
  const [logTargets, setLogTargets] = useState([])
  const [me, setMe] = useState(null)
  const [manual, setManual] = useState({})
  const [sessionLost, setSessionLost] = useState(false)

  const checkSession = useCallback(() => {
    return fetch('/api/me', { headers: { Accept: 'application/json' } })
      .then(async (r) => {
        if (r.status === 401) {
          setSessionLost(true)
          return
        }
        if (r.ok) setMe(await r.json())
      })
      .catch(() => {})
  }, [])

  useEffect(() => {
    checkSession()
    fetch('/api/layout', { headers: { Accept: 'application/json' } })
      .then(async (r) => {
        const rows = r.ok ? await r.json() : []
        if (Array.isArray(rows)) setManual(Object.fromEntries(rows.map((p) => [p.nodeKey, p])))
      })
      .catch(() => {})
  }, [checkSession])

  useEffect(() => {
    // logs so existem com atlas.logs.enabled no hub (desligado ate a F5): 404 aqui e o mapa fica sem painel
    const ctrl = new AbortController()
    fetch('/api/logs/targets', { signal: ctrl.signal, headers: { Accept: 'application/json' } })
      .then((r) => setLogsOn(r.ok))
      .catch(() => {})
    return () => ctrl.abort()
  }, [])

  const openLogs = useCallback(
    (_, node) => {
      if (!logsOn || !WITH_LOGS.has(node.data?.kind)) return
      setLogTargets((cur) =>
        cur.some((t) => t.key === node.id) ? cur : [...cur, { key: node.id, label: node.data.label }].slice(-MAX_TABS))
    },
    [logsOn],
  )

  useEffect(() => {
    // Stream (F3): snapshot ao conectar e patch a cada mudanca; o EventSource reconecta sozinho mandando o
    // Last-Event-ID. Sem EventSource (ambiente antigo/teste), uma leitura simples.
    if (typeof EventSource !== 'undefined') {
      const es = new EventSource('/api/topology/stream')
      es.addEventListener('snapshot', (ev) => {
        setError(null)
        setSnapshot(JSON.parse(ev.data))
      })
      es.addEventListener('patch', (ev) => setSnapshot((cur) => applyPatch(cur, JSON.parse(ev.data))))
      es.addEventListener('error', () => {
        setError('stream interrompido, reconectando')
        // o EventSource nao diz o status: se foi a sessao que expirou, a reconexao nunca vai passar
        checkSession()
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
  }, [checkSession])

  const graph = useMemo(() => {
    const g = buildInfraTopology(snapshot)
    return { ...g, nodes: applyLayout(g.nodes, manual) }
  }, [snapshot, manual])
  const admin = me?.roles?.includes('atlas-admins') ?? false

  const move = useCallback((node) => {
    saveMove(node.id, node.position, node.data?.rev ?? 0)
      .then((p) => setManual((cur) => ({ ...cur, [p.nodeKey ?? node.id]: p })))
      .catch((e) => setError(`posicao nao salva (${e.message})`))
  }, [])

  return (
    <div className="flex h-full flex-col gap-4 p-4">
      <MriPageHeader title="Cyber Atlas" icon={Network} count={snapshot.nodes?.length ?? 0} countLabel="nós" />
      {sessionLost && (
        <a className="text-sm text-amber-500 underline" href="/">
          Sessão expirada: entrar de novo
        </a>
      )}
      {error && <div className="text-sm text-red-500">Nao foi possivel carregar o mapa ({error}).</div>}
      <InfraMap nodes={graph.nodes} edges={graph.edges} onNodeClick={openLogs} onMove={move} editable={admin} height={logTargets.length ? 'h-[40vh]' : undefined} />
      {logTargets.length > 0 && (
        <LogPanel targets={logTargets} onClose={(key) => setLogTargets((cur) => cur.filter((t) => t.key !== key))} />
      )}
    </div>
  )
}
