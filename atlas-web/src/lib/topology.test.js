import { buildInfraTopology, COL, stateOf } from './topology.js'

const n = (key, kind, machine, name = key, state = 'UNKNOWN', reason = 'sem fonte') => ({
  node: { key, kind, name, machine, attributes: {} },
  status: { state, reason, sources: [] },
})
const e = (from, to, kind = 'ROUTES_TO') => ({ id: `${kind.toLowerCase()}:${from}->${to}`, from, to, kind })

const SNAPSHOT = {
  generatedAt: '2026-10-19T12:00:00Z',
  nodes: [
    n('machine:vps', 'MACHINE', 'vps', 'vps'),
    n('entry:vps/tunel', 'ENTRY', 'vps', 'Tunel Cloudflare'),
    n('traefik:vps', 'TRAEFIK', 'vps', 'Traefik'),
    n('authgate:authentik', 'AUTHGATE', null, 'Authentik'),
    n('route:ha.gf2.in', 'ROUTE', 'vps', 'ha.gf2.in', 'ONLINE', '200'),
    n('route:logs.gf2.in', 'ROUTE', 'vps', 'logs.gf2.in', 'DEGRADED', 'rota protegida respondeu sem login (200)'),
    n('service:vps/homeassistant', 'SERVICE', 'vps', 'homeassistant'),
    n('service:vps/dozzle', 'SERVICE', 'vps', 'dozzle'),
    n('machine:host', 'MACHINE', 'host', 'host'),
    n('traefik:host', 'TRAEFIK', 'host', 'Traefik'),
  ],
  edges: [
    e('entry:vps/tunel', 'traefik:vps'),
    e('traefik:vps', 'route:ha.gf2.in'),
    e('traefik:vps', 'route:logs.gf2.in'),
    e('route:logs.gf2.in', 'authgate:authentik', 'AUTH_VIA'),
    e('route:ha.gf2.in', 'service:vps/homeassistant'),
    e('route:logs.gf2.in', 'service:vps/dozzle'),
  ],
}

const byId = (graph) => Object.fromEntries(graph.nodes.map((x) => [x.id, x]))

describe('buildInfraTopology', () => {
  it('uma caixa por maquina e cada no dentro da sua, com extent parent', () => {
    const g = byId(buildInfraTopology(SNAPSHOT))
    expect(g['group:vps'].type).toBe('machineGroup')
    expect(g['group:host'].type).toBe('machineGroup')
    expect(g['route:ha.gf2.in'].parentId).toBe('group:vps')
    expect(g['route:ha.gf2.in'].extent).toBe('parent')
    expect(g['traefik:host'].parentId).toBe('group:host')
    // o no MACHINE vira a propria caixa, nao um card solto
    expect(g['machine:vps']).toBeUndefined()
  })

  it('caixa antes dos filhos (exigencia do React Flow para parentId)', () => {
    const ids = buildInfraTopology(SNAPSHOT).nodes.map((x) => x.id)
    expect(ids.indexOf('group:vps')).toBeLessThan(ids.indexOf('route:ha.gf2.in'))
    expect(ids.indexOf('group:host')).toBeLessThan(ids.indexOf('traefik:host'))
  })

  it('colunas por tipo: entrada, Traefik, rota e servico', () => {
    const g = byId(buildInfraTopology(SNAPSHOT))
    const x = (id) => g[id].position.x - g[id].position.x % 1
    expect(x('entry:vps/tunel')).toBe(COL.entry + 16)
    expect(x('traefik:vps')).toBe(COL.traefik + 16)
    expect(x('route:ha.gf2.in')).toBe(COL.route + 16)
    expect(x('service:vps/homeassistant')).toBe(COL.service + 16)
  })

  it('Authentik fica fora das maquinas, na coluna dele', () => {
    const a = byId(buildInfraTopology(SNAPSHOT))['authgate:authentik']
    expect(a.parentId).toBeUndefined()
    expect(a.position.x).toBe(COL.authgate)
  })

  it('servico na linha da sua rota; rotas em ordem alfabetica', () => {
    const g = byId(buildInfraTopology(SNAPSHOT))
    expect(g['route:ha.gf2.in'].position.y).toBeLessThan(g['route:logs.gf2.in'].position.y)
    expect(g['service:vps/homeassistant'].position.y).toBe(g['route:ha.gf2.in'].position.y)
    expect(g['service:vps/dozzle'].position.y).toBe(g['route:logs.gf2.in'].position.y)
  })

  it('estado e motivo chegam ao card; sem status = unknown', () => {
    const g = byId(buildInfraTopology(SNAPSHOT))
    expect(g['route:logs.gf2.in'].data).toMatchObject({ state: 'degraded', reason: 'rota protegida respondeu sem login (200)', kind: 'ROUTE' })
    expect(stateOf(undefined)).toBe('unknown')
  })

  it('forwardAuth pontilhado; caminho da requisicao solido', () => {
    const edges = Object.fromEntries(buildInfraTopology(SNAPSHOT).edges.map((x) => [x.id, x]))
    expect(edges['auth_via:route:logs.gf2.in->authgate:authentik'].style.strokeDasharray).toBe('2 4')
    expect(edges['routes_to:traefik:vps->route:ha.gf2.in'].style).toBeUndefined()
  })

  it('caixa cresce com o numero de rotas', () => {
    const g = byId(buildInfraTopology(SNAPSHOT))
    expect(g['group:vps'].style.height).toBeGreaterThan(g['group:host'].style.height)
  })

  it('snapshot vazio ou ausente nao quebra', () => {
    expect(buildInfraTopology({ nodes: [], edges: [] })).toEqual({ nodes: [], edges: [] })
    expect(buildInfraTopology(undefined)).toEqual({ nodes: [], edges: [] })
  })
  it('drift chega ao card', () => {
    const snap = { nodes: [{ ...n('machine:vps', 'MACHINE', 'vps', 'vps') }, { ...n('route:x.gf2.in', 'ROUTE', 'vps', 'x.gf2.in'), drift: ['servico-sem-dns'] }], edges: [] }
    const g = byId(buildInfraTopology(snap))
    expect(g['route:x.gf2.in'].data.drift).toEqual(['servico-sem-dns'])
    expect(byId(buildInfraTopology(SNAPSHOT))['route:ha.gf2.in'].data.drift).toEqual([])
  })
})
