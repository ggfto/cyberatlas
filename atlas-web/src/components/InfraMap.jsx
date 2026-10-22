import { useEffect, useState } from 'react'
import { ReactFlow, Background, Handle, Position, applyNodeChanges } from '@xyflow/react'
import { Pin } from 'lucide-react'
import { ServiceIcon } from './ServiceIcon.jsx'
import '@xyflow/react/dist/style.css'

// verde online, ambar degradado, vermelho offline, cinza desconhecido (sem fonte fresca)
const DOT = { online: 'bg-green-500', degraded: 'bg-amber-500', offline: 'bg-red-500', unknown: 'bg-muted-foreground' }
const KIND_LABEL = {
  ENTRY: 'entrada', TRAEFIK: 'traefik', AUTHGATE: 'login', ROUTE: 'rota', SERVICE: 'servico',
  CONTAINER: 'container', POD: 'pod', WORKLOAD: 'workload', ARGO: 'app do argo', DNS: 'dns', TUNNEL: 'tunel',
}

/** Um no do mapa: nome, tipo, ponto de estado e o motivo (o "porque" do estado, sempre visivel no hover). */
export function InfraNode({ data }) {
  return (
    <div className="min-w-[200px] rounded-lg border border-border bg-card px-3 py-2 shadow-sm" title={data.reason}>
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

/**
 * Canvas do mapa (molde do InfraMap do HiveKeeper). Nos e arestas vem prontos de buildInfraTopology (+ layout
 * manual). Com `editable`, arrastar move o no na tela e, ao soltar, chama `onMove(no)` para gravar a posicao.
 */
export function InfraMap({ nodes, edges, onNodeClick, onMove, editable = false, height = 'h-[72vh]' }) {
  const [local, setLocal] = useState(nodes)
  useEffect(() => setLocal(nodes), [nodes])
  return (
    <div className={`${height} w-full rounded-lg border border-border`} data-testid="infra-map">
      <ReactFlow
        nodes={local}
        edges={edges}
        nodeTypes={NODE_TYPES}
        fitView
        nodesConnectable={false}
        nodesDraggable={editable}
        onNodesChange={(changes) => setLocal((cur) => applyNodeChanges(changes, cur))}
        onNodeDragStop={(_, node) => onMove?.(node)}
        onNodeClick={onNodeClick}
      >
        <Background />
      </ReactFlow>
    </div>
  )
}
