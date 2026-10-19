import { useEffect, useMemo, useState } from 'react'
import { MriPageHeader } from '@mriqbox/ui-kit'
import { Network } from 'lucide-react'
import { InfraMap } from './components/InfraMap.jsx'
import { buildInfraTopology } from './lib/topology.js'
import { applyPatch } from './lib/patch.js'

export default function App() {
  const [snapshot, setSnapshot] = useState({ nodes: [], edges: [] })
  const [error, setError] = useState(null)

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
      es.addEventListener('error', () => setError('stream interrompido, reconectando'))
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
  }, [])

  const graph = useMemo(() => buildInfraTopology(snapshot), [snapshot])

  return (
    <div className="flex h-full flex-col gap-4 p-4">
      <MriPageHeader title="Cyber Atlas" icon={Network} />
      {error && <div className="text-sm text-red-500">Nao foi possivel carregar o mapa ({error}).</div>}
      <InfraMap nodes={graph.nodes} edges={graph.edges} />
    </div>
  )
}
