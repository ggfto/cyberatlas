// Busca do mapa (issue #23): pura, sem React Flow. Casa por nome (data.label) e por node_key (id), sem diferenciar
// maiusculas nem acentos. Ordem: match exato, depois prefixo, depois substring; empate vai pelo nome.

/** Minusculas e sem acento ("Serviço" -> "servico"), para comparar o que a pessoa digita com nome e chave. */
export function normalize(text) {
  return String(text ?? '')
    .normalize('NFD')
    .replace(/\p{Diacritic}/gu, '')
    .toLowerCase()
    .trim()
}

const EXACT = 0
const PREFIX = 1
const SUBSTRING = 2

function rank(field, q) {
  const f = normalize(field)
  if (!f) return null
  if (f === q) return EXACT
  if (f.startsWith(q)) return PREFIX
  if (f.includes(q)) return SUBSTRING
  return null
}

/**
 * Nos que casam com `query`, do melhor para o pior. Cada resultado e `{ id, label, kind }`; consulta vazia (ou so
 * espacos) devolve lista vazia. `limit` corta a lista (padrao 8).
 */
export function searchNodes(nodes, query, limit = 8) {
  const q = normalize(query)
  if (!q) return []
  const hits = []
  for (const n of nodes || []) {
    const label = n.data?.label ?? n.id
    const ranks = [rank(label, q), rank(n.id, q)].filter((r) => r !== null)
    if (ranks.length === 0) continue
    hits.push({ id: n.id, label, kind: n.data?.kind, score: Math.min(...ranks) })
  }
  hits.sort((a, b) => a.score - b.score || normalize(a.label).localeCompare(normalize(b.label)) || a.id.localeCompare(b.id))
  return hits.slice(0, limit).map(({ id, label, kind }) => ({ id, label, kind }))
}
