import { useEffect, useMemo, useRef, useState } from 'react'
import { ReactFlow, Background, Controls, MiniMap, Handle, Position, applyNodeChanges, useReactFlow } from '@xyflow/react'
import { Pin } from 'lucide-react'
import { ServiceIcon } from './ServiceIcon.jsx'
import '@xyflow/react/dist/style.css'
import { ExportMapButton } from './ExportMapButton.jsx'
import { FOCUS_FIT, NodeSearch } from './NodeSearch.jsx'
import { focusNodeIds } from '../lib/neighbors.js'

// verde online, ambar degradado, vermelho offline, cinza desconhecido (sem fonte fresca)
const DOT = { online: 'bg-green-500', degraded: 'bg-amber-500', offline: 'bg-red-500', unknown: 'bg-muted-foreground' }
const KIND_LABEL = {
  ENTRY: 'entrada', TRAEFIK: 'traefik', AUTHGATE: 'login', ROUTE: 'rota', SERVICE: 'servico',
  CONTAINER: 'container', POD: 'pod', WORKLOAD: 'workload', ARGO: 'app do argo', DNS: 'dns', TUNNEL: 'tunel',
}

/** Um no do mapa: nome, tipo, ponto de estado e o motivo (o "porque" do estado, sempre visivel no hover). */
export function InfraNode({ data }) {
  return (
    <div
      className={`min-w-[200px] rounded-lg border bg-card px-3 py-2 shadow-sm ${data.focused ? 'border-primary ring-2 ring-primary' : 'border-border'}`}
      title={data.reason}
      data-focused={data.focused ? 'true' : undefined}
    >
      <Handle type="target" position={Position.Left} />
      <div className="flex items-center gap-2">
        <ServiceIcon name={data.label} kind={data.kind} image={data.attributes?.image} icon={data.attributes?.icon} />
        <span className="truncate text-sm font-medium">{data.label}</span>
        {data.rev > 0 && <Pin className="h-3 w-3 shrink-0 text-muted-foreground" aria-label="posição manual" />}
        <span className={`ml-auto h-2 w-2 shrink-0 rounded-full ${DOT[data.state] || DOT.unknown}`} aria-label={data.state} />
      </div>
      <div className="text-xs text-muted-foreground">
        {KIND_LABEL[data.kind] || data.kind}
        {data.hostProcess && ' · processo no host'}
      </div>
      {data.drift?.length > 0 && (
        <div className="mt-1 flex flex-wrap gap-1">
          {data.drift.map((d) => (
            <span key={d} className="rounded border border-amber-500/50 px-1 text-[10px] text-amber-500">
              {d}
            </span>
          ))}
        </div>
      )}
      <Handle type="source" position={Position.Right} />
    </div>
  )
}

/** Caixa translucida de uma maquina; cliques atravessam para os nos dentro. */
function MachineGroup({ data }) {
  return (
    <div className="pointer-events-none h-full w-full rounded-xl border border-dashed border-primary/40 bg-primary/5">
      <div className="px-2 py-1 text-xs font-medium text-primary/80">{data.label}</div>
    </div>
  )
}

const NODE_TYPES = { infra: InfraNode, machineGroup: MachineGroup }

// Rotulos de acessibilidade do canvas em PT-BR (o padrao do React Flow e ingles).
const ARIA_LABELS = {
  'controls.ariaLabel': 'controles do mapa',
  'controls.zoomIn.ariaLabel': 'aproximar',
  'controls.zoomOut.ariaLabel': 'afastar',
  'controls.fitView.ariaLabel': 'enquadrar o mapa',
  'controls.interactive.ariaLabel': 'travar interacao',
  'minimap.ariaLabel': 'minimapa',
}

/**
 * Enquadra o `?focus=` e os vizinhos (issue #23) uma vez por foco, assim que o no aparece no mapa (o snapshot chega
 * depois da montagem). O fitView do React Flow fica na fila ate os nos serem medidos, entao vale no primeiro render.
 */
function FocusFit({ ids }) {
  const { fitView } = useReactFlow()
  const done = useRef(null)
  const focus = ids[0]
  useEffect(() => {
    if (!focus || done.current === focus) return
    done.current = focus
    fitView({ nodes: ids.map((id) => ({ id })), maxZoom: FOCUS_FIT.maxZoom, padding: FOCUS_FIT.padding })
  }, [focus, ids, fitView])
  return null
}

/**
 * Canvas do mapa (molde do InfraMap do HiveKeeper). Nos e arestas vem prontos de buildInfraTopology (+ layout
 * manual). Com `editable`, arrastar move o no na tela e, ao soltar, chama `onMove(no)` para gravar a posicao.
 * Com `focus` (chave de um no), o enquadramento inicial e nesse no e nos vizinhos, e o no ganha destaque.
 * `controls` mostra os botoes de zoom (sem o de travar/destravar, que ligaria o arrastar por fora do `editable`);
 * o /embed desliga para continuar sem nenhum botao. Minimapa e busca (Ctrl/Cmd+K) so mexem no viewport.
 */
export function InfraMap({ nodes, edges, onNodeClick, onMove, editable = false, exportable = true, controls = true, focus, height = 'h-[72vh]' }) {
  const [local, setLocal] = useState(nodes)
  useEffect(() => setLocal(nodes), [nodes])
  const focusIds = useMemo(() => focusNodeIds(nodes, edges, focus), [nodes, edges, focus])
  const shown = useMemo(
    () => (focus ? local.map((n) => (n.id === focus ? { ...n, data: { ...n.data, focused: true } } : n)) : local),
    [local, focus],
  )
  return (
    <div className={`${height} w-full rounded-lg border border-border`} data-testid="infra-map">
      <ReactFlow
        nodes={shown}
        edges={edges}
        nodeTypes={NODE_TYPES}
        fitView
        nodesConnectable={false}
        nodesDraggable={editable}
        onNodesChange={(changes) => setLocal((cur) => applyNodeChanges(changes, cur))}
        onNodeDragStop={(_, node) => onMove?.(node)}
        onNodeClick={onNodeClick}
        ariaLabelConfig={ARIA_LABELS}
      >
        <Background />
        {controls && <Controls showInteractive={false} />}
        <MiniMap pannable zoomable />
        <FocusFit ids={focusIds} />
        <NodeSearch nodes={local} />
        {exportable && <ExportMapButton />}
      </ReactFlow>
    </div>
  )
}
