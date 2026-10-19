/**
 * Aplica um patch do stream (event: patch de /api/topology/stream) sobre o snapshot atual. Pura e sem mutar o
 * anterior: nos por chave, arestas por id, ordenados como o hub manda.
 */
export function applyPatch(snapshot, patch) {
  if (!patch) return snapshot
  const nodes = new Map((snapshot?.nodes || []).map((n) => [n.node.key, n]))
  for (const k of patch.removeNodes || []) nodes.delete(k)
  for (const n of patch.upsertNodes || []) nodes.set(n.node.key, n)
  const edges = new Map((snapshot?.edges || []).map((e) => [e.id, e]))
  for (const id of patch.removeEdges || []) edges.delete(id)
  for (const e of patch.upsertEdges || []) edges.set(e.id, e)
  const byKey = (a, b) => (a < b ? -1 : a > b ? 1 : 0)
  return {
    generatedAt: patch.generatedAt ?? snapshot?.generatedAt,
    nodes: [...nodes.values()].sort((a, b) => byKey(a.node.key, b.node.key)),
    edges: [...edges.values()].sort((a, b) => byKey(a.id, b.id)),
  }
}
