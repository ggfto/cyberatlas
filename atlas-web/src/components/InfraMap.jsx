import { ReactFlow, Background, Handle, Position } from '@xyflow/react'
import '@xyflow/react/dist/style.css'

// verde online, ambar degradado, vermelho offline, cinza desconhecido (sem fonte fresca)
const DOT = { online: 'bg-green-500', degraded: 'bg-amber-500', offline: 'bg-red-500', unknown: 'bg-muted-foreground' }
const KIND_LABEL = { ENTRY: 'entrada', TRAEFIK: 'traefik', AUTHGATE: 'login', ROUTE: 'rota', SERVICE: 'servico' }

/** Um no do mapa: nome, tipo, ponto de estado e o motivo (o "porque" do estado, sempre visivel no hover). */
export function InfraNode({ data }) {
  return (
    <div className="min-w-[200px] rounded-lg border border-border bg-card px-3 py-2 shadow-sm" title={data.reason}>
      <Handle type="target" position={Position.Left} />
      <div className="flex items-center gap-2">
        <span className="truncate text-sm font-medium">{data.label}</span>
        <span className={`ml-auto h-2 w-2 shrink-0 rounded-full ${DOT[data.state] || DOT.unknown}`} aria-label={data.state} />
      </div>
      <div className="text-xs text-muted-foreground">{KIND_LABEL[data.kind] || data.kind}</div>
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

/** Canvas do mapa (molde do InfraMap do HiveKeeper). Nos e arestas vem prontos de buildInfraTopology. */
export function InfraMap({ nodes, edges }) {
  return (
    <div className="h-[72vh] w-full rounded-lg border border-border" data-testid="infra-map">
      <ReactFlow nodes={nodes} edges={edges} nodeTypes={NODE_TYPES} fitView nodesConnectable={false}>
        <Background />
      </ReactFlow>
    </div>
  )
}
