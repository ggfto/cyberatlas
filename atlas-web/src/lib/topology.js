/**
 * Monta o grafo do mapa a partir do snapshot do hub (GET /api/topology). Pura, no molde do topology.js do
 * HiveKeeper: devolve `nodes` + `edges` do React Flow com posicao calculada, testavel sem canvas.
 *
 * Colunas (dentro de cada maquina): entrada (tunel/LAN) -> Traefik -> rota -> servico. O Authentik fica fora
 * das maquinas, na coluna dele: a porta de login e uma so, mesmo para o Traefik do host, que chega nela pela
 * internet. Cada maquina e uma caixa (`machineGroup`) com os filhos por `parentId`; a caixa vem antes dos
 * filhos, como o React Flow exige.
 */

export const COL = { entry: 0, traefik: 300, authgate: 600, route: 900, service: 1200 }
const KIND_COL = { ENTRY: 'entry', TRAEFIK: 'traefik', ROUTE: 'route', SERVICE: 'service' }
const NODE_W = 220
const ROW_H = 90
const PAD = 16
const LABEL_H = 28
const GROUP_GAP = 48
const TOP = ROW_H * 1.5 // espaco do Authentik acima das caixas

const EDGE_STYLE = {
  AUTH_VIA: { strokeDasharray: '2 4' }, // forwardAuth: pontilhado
  WIREGUARD: { strokeDasharray: '6 4' }, // WG: tracejado (F3)
}

export function stateOf(status) {
  return (status?.state || 'UNKNOWN').toLowerCase()
}

function card(tn, position, extra = {}) {
  const { node, status } = tn
  return {
    id: node.key,
    type: 'infra',
    position,
    ...extra,
    data: {
      label: node.name || node.key,
      kind: node.kind,
      machine: node.machine,
      attributes: node.attributes || {},
      state: stateOf(status),
      reason: status?.reason || '',
      drift: tn.drift || [],
    },
  }
}

export function buildInfraTopology(snapshot) {
  const items = snapshot?.nodes || []
  const rawEdges = snapshot?.edges || []
  const groups = []
  const children = []
  const free = []

  const machines = [...new Set(items.filter((t) => t.node.kind === 'MACHINE').map((t) => t.node.machine))].sort()
  const firstRouteOf = new Map()
  for (const e of rawEdges) {
    if (e.kind === 'ROUTES_TO' && e.to.startsWith('service:') && !firstRouteOf.has(e.to)) firstRouteOf.set(e.to, e.from)
  }

  let y = TOP
  for (const m of machines) {
    const own = items.filter((t) => t.node.machine === m && KIND_COL[t.node.kind])
    const of = (kind) => own.filter((t) => t.node.kind === kind).sort((a, b) => a.node.key.localeCompare(b.node.key))
    const routes = of('ROUTE')
    const rowOfRoute = new Map(routes.map((t, i) => [t.node.key, i]))
    const rows = Math.max(1, routes.length, of('ENTRY').length)
    const groupId = `group:${m}`
    const at = (col, row) => ({ x: COL[col] + PAD, y: LABEL_H + PAD + row * ROW_H })

    groups.push({
      id: groupId,
      type: 'machineGroup',
      position: { x: 0, y },
      selectable: false,
      draggable: false,
      style: { width: COL.service + NODE_W + PAD * 2, height: LABEL_H + PAD * 2 + rows * ROW_H },
      data: { label: m },
    })
    const inGroup = { parentId: groupId, extent: 'parent' }
    of('ENTRY').forEach((t, i) => children.push(card(t, at('entry', i), inGroup)))
    of('TRAEFIK').forEach((t) => children.push(card(t, at('traefik', (rows - 1) / 2), inGroup)))
    routes.forEach((t, i) => children.push(card(t, at('route', i), inGroup)))
    of('SERVICE').forEach((t, i) => {
      const row = rowOfRoute.get(firstRouteOf.get(t.node.key))
      children.push(card(t, at('service', row ?? i), inGroup))
    })
    y += LABEL_H + PAD * 2 + rows * ROW_H + GROUP_GAP
  }

  items
    .filter((t) => t.node.kind === 'AUTHGATE')
    .forEach((t, i) => free.push(card(t, { x: COL.authgate, y: i * ROW_H })))

  const placed = new Set([...children, ...free].map((x) => x.id))
  const edges = rawEdges
    .filter((e) => placed.has(e.from) && placed.has(e.to))
    .map((e) => ({ id: e.id, source: e.from, target: e.to, data: { kind: e.kind }, ...(EDGE_STYLE[e.kind] ? { style: EDGE_STYLE[e.kind] } : {}) }))

  return { nodes: [...groups, ...children, ...free], edges }
}
