import { ReactFlow, Background } from '@xyflow/react'
import '@xyflow/react/dist/style.css'

/** Canvas do mapa (molde do InfraMap do HiveKeeper). Nos e arestas vem prontos de buildInfraTopology (F1). */
export function InfraMap({ nodes, edges }) {
  return (
    <div className="h-[72vh] w-full rounded-lg border border-border" data-testid="infra-map">
      <ReactFlow nodes={nodes} edges={edges} fitView nodesConnectable={false}>
        <Background />
      </ReactFlow>
    </div>
  )
}
