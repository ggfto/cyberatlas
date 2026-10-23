import { useEffect, useMemo, useRef, useState } from 'react'
import { Panel, useReactFlow } from '@xyflow/react'
import { searchNodes } from '../lib/searchNodes.js'

/** Ctrl+K (Cmd+K no Mac) abre a busca. */
export function isSearchShortcut(e) {
  return (e.ctrlKey || e.metaKey) && !e.altKey && !e.shiftKey && e.key?.toLowerCase() === 'k'
}

/** Enquadra um no: fitView so nele, com o mesmo teto de zoom do `?focus=`. */
export const FOCUS_FIT = { maxZoom: 1.5, padding: 0.2, duration: 300 }

/**
 * Busca do mapa (issue #23). Fechada nao desenha nada (o /embed segue sem campo nem botao); Ctrl/Cmd+K abre, Esc
 * fecha, setas escolhem e Enter (ou clique) centraliza no no. So mexe no viewport: nada aqui grava, entao vale
 * igual na pagina normal e no /embed. Fica dentro do <ReactFlow> para usar o fitView da instancia.
 */
export function NodeSearch({ nodes }) {
  const { fitView } = useReactFlow()
  const [open, setOpen] = useState(false)
  const [query, setQuery] = useState('')
  const [active, setActive] = useState(0)
  const input = useRef(null)
  const results = useMemo(() => searchNodes(nodes, query), [nodes, query])

  useEffect(() => {
    const onKey = (e) => {
      if (!isSearchShortcut(e)) return
      e.preventDefault()
      setOpen(true)
      input.current?.focus()
    }
    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
  }, [])

  useEffect(() => {
    if (open) input.current?.focus()
  }, [open])
  useEffect(() => setActive(0), [query])

  const close = () => {
    setOpen(false)
    setQuery('')
  }
  const pick = (hit) => {
    if (!hit) return
    fitView({ nodes: [{ id: hit.id }], ...FOCUS_FIT })
    close()
  }
  const onKeyDown = (e) => {
    if (e.key === 'Escape') {
      e.preventDefault()
      close()
    } else if (e.key === 'ArrowDown') {
      e.preventDefault()
      setActive((i) => Math.min(i + 1, results.length - 1))
    } else if (e.key === 'ArrowUp') {
      e.preventDefault()
      setActive((i) => Math.max(i - 1, 0))
    } else if (e.key === 'Enter') {
      e.preventDefault()
      pick(results[active])
    }
  }

  if (!open) return null
  return (
    <Panel position="top-center" className="nodrag nopan nowheel">
      <div className="w-80 rounded-lg border border-border bg-card p-2 shadow-lg" data-testid="node-search">
        <input
          ref={input}
          type="search"
          aria-label="buscar nó"
          placeholder="Buscar por nome ou chave (Esc fecha)"
          className="w-full rounded border border-border bg-background px-2 py-1 text-sm outline-none"
          value={query}
          onChange={(e) => setQuery(e.target.value)}
          onKeyDown={onKeyDown}
          onBlur={close}
          aria-controls="node-search-results"
          aria-activedescendant={results[active] ? `node-search-${active}` : undefined}
        />
        {query.trim() && (
          <ul id="node-search-results" role="listbox" aria-label="resultados da busca" className="mt-1 max-h-64 overflow-auto">
            {results.length === 0 && <li className="px-2 py-1 text-xs text-muted-foreground">Nenhum nó encontrado</li>}
            {results.map((hit, i) => (
              <li
                key={hit.id}
                id={`node-search-${i}`}
                role="option"
                aria-selected={i === active}
                className={`cursor-pointer rounded px-2 py-1 text-sm ${i === active ? 'bg-primary/15' : ''}`}
                // mousedown antes do blur do campo, senao a lista some antes do clique
                onMouseDown={(e) => {
                  e.preventDefault()
                  pick(hit)
                }}
                onMouseEnter={() => setActive(i)}
              >
                <div className="truncate font-medium">{hit.label}</div>
                <div className="truncate text-xs text-muted-foreground">{hit.id}</div>
              </li>
            ))}
          </ul>
        )}
      </div>
    </Panel>
  )
}
