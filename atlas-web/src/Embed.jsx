import { useEffect, useMemo, useState } from 'react'
import { InfraMap } from './components/InfraMap.jsx'
import { buildInfraTopology } from './lib/topology.js'
import { applyLayout } from './lib/layout.js'
import { fetchManualLayout, useTopology } from './lib/useTopology.js'

/**
 * Mapa so leitura para o Home Assistant enquadrar (issue #19). Nada de admin: sem logs, sem edges/enrollment, sem
 * arrastar, sem botao (nem os de zoom: o minimapa e a roda do mouse bastam). So GET (stream da topologia e posicoes
 * manuais ja gravadas). `focus` enquadra o no e os vizinhos e destaca o no; Ctrl/Cmd+K busca (so mexe no viewport);
 * `theme` troca dark/light no <html>.
 */
export default function Embed({ focus = null, theme = 'dark' }) {
  const { snapshot, error } = useTopology()
  const [manual, setManual] = useState({})

  useEffect(() => {
    fetchManualLayout().then(setManual)
  }, [])

  useEffect(() => {
    document.documentElement.classList.toggle('dark', theme === 'dark')
  }, [theme])

  const graph = useMemo(() => {
    const g = buildInfraTopology(snapshot)
    return { ...g, nodes: applyLayout(g.nodes, manual) }
  }, [snapshot, manual])

  return (
    <div className="flex h-full flex-col gap-2 p-2" data-testid="embed">
      {error && <div className="text-sm text-red-500">Nao foi possivel carregar o mapa ({error}).</div>}
      <InfraMap nodes={graph.nodes} edges={graph.edges} editable={false} exportable={false} controls={false} focus={focus} height="h-full" />
    </div>
  )
}
