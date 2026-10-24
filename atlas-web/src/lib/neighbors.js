// Foco por no (issue #23): `?focus=<node_key>` enquadra o no e os vizinhos, isto e, os nos ligados a ele por uma
// aresta, em qualquer sentido. Puro, testavel sem canvas.

/** Chaves dos nos ligados a `id` por uma aresta (entrando ou saindo), sem repetir e sem o proprio no. */
export function neighborIds(edges, id) {
  const out = new Set()
  for (const e of edges || []) {
    if (e.source === id && e.target !== id) out.add(e.target)
    else if (e.target === id && e.source !== id) out.add(e.source)
  }
  return [...out]
}

/**
 * O que enquadrar para `focus`: o proprio no primeiro, depois os vizinhos que existem no mapa. Foco ausente ou que
 * nao esta no mapa devolve lista vazia (o mapa enquadra tudo, como sem `focus`).
 */
export function focusNodeIds(nodes, edges, focus) {
  if (!focus) return []
  const present = new Set((nodes || []).map((n) => n.id))
  if (!present.has(focus)) return []
  return [focus, ...neighborIds(edges, focus).filter((k) => present.has(k))]
}
