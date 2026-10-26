import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { InfraMap } from './components/InfraMap.jsx'
import { buildInfraTopology } from './lib/topology.js'
import { applyLayout } from './lib/layout.js'
import { fetchManualLayout, useTopology } from './lib/useTopology.js'
import { isFramed, openLoginPopup } from './lib/embed.js'

/**
 * Mapa so leitura para o Home Assistant enquadrar (issue #19). Nada de admin: sem logs, sem edges/enrollment, sem
 * arrastar, sem botao (nem os de zoom: o minimapa e a roda do mouse bastam). So GET (stream da topologia e posicoes
 * manuais ja gravadas). `focus` enquadra o no e os vizinhos e destaca o no; Ctrl/Cmd+K busca (so mexe no viewport);
 * `theme` troca dark/light no <html>.
 *
 * Sem sessao (401 da API ou `end {reason:"auth"}` no stream) e dentro de moldura, o unico botao possivel: "Entrar
 * no atlas" (issue #20). O login nao cabe no iframe (o Authentik manda X-Frame-Options: DENY, de proposito); o botao
 * abre um popup top-level e o iframe recarrega quando ele fecha. Fora de moldura, nada muda.
 */
export default function Embed({ focus = null, theme = 'dark', framed = isFramed(window), reload = () => window.location.reload() }) {
  const [noSession, setNoSession] = useState(false)
  const lost = useCallback(() => setNoSession(true), [])
  const recheck = useCallback(() => {
    fetchManualLayout(lost)
  }, [lost])
  const { snapshot, error } = useTopology(recheck, lost)
  const [manual, setManual] = useState({})

  useEffect(() => {
    fetchManualLayout(lost).then(setManual)
  }, [lost])

  useEffect(() => {
    document.documentElement.classList.toggle('dark', theme === 'dark')
  }, [theme])

  const graph = useMemo(() => {
    const g = buildInfraTopology(snapshot)
    return { ...g, nodes: applyLayout(g.nodes, manual) }
  }, [snapshot, manual])

  return (
    <div className="flex h-full flex-col gap-2 p-2" data-testid="embed">
      {noSession && framed ? (
        <LoginGate reload={reload} />
      ) : (
        error && <div className="text-sm text-red-500">Nao foi possivel carregar o mapa ({error}).</div>
      )}
      <InfraMap nodes={graph.nodes} edges={graph.edges} editable={false} exportable={false} controls={false} focus={focus} height="h-full" />
    </div>
  )
}

function LoginGate({ reload }) {
  const [blocked, setBlocked] = useState(false)
  const stop = useRef(null)
  useEffect(() => () => stop.current?.(), [])

  const login = () => {
    stop.current?.()
    stop.current = openLoginPopup(window, reload)
    setBlocked(!stop.current)
  }

  return (
    <div className="flex flex-col items-center gap-2 py-4 text-center text-sm" data-testid="embed-login">
      <p>Sessao do atlas expirada ou ausente.</p>
      <button type="button" className="rounded border px-3 py-1" onClick={login}>
        Entrar no atlas
      </button>
      {blocked && <p className="text-red-500">O browser bloqueou o popup de login. Libere popups para o HA e tente de novo.</p>}
    </div>
  )
}
