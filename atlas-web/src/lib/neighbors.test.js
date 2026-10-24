import { focusNodeIds, neighborIds } from './neighbors.js'

const e = (source, target) => ({ id: `${source}->${target}`, source, target })
const edges = [
  e('entry:cf', 'route:ha.gf2.in'),
  e('route:ha.gf2.in', 'service:vps/ha'),
  e('route:ha.gf2.in', 'service:vps/ha'), // aresta repetida (outro tipo) nao duplica o vizinho
  e('route:logs.gf2.in', 'service:vps/dozzle'),
  e('route:ha.gf2.in', 'route:ha.gf2.in'), // laco nao conta como vizinho
]
const nodes = ['entry:cf', 'route:ha.gf2.in', 'service:vps/ha', 'route:logs.gf2.in', 'service:vps/dozzle'].map((id) => ({ id }))

describe('neighborIds', () => {
  it('pega os dois sentidos, sem repetir e sem o proprio no', () => {
    expect(neighborIds(edges, 'route:ha.gf2.in').sort()).toEqual(['entry:cf', 'service:vps/ha'])
    expect(neighborIds(edges, 'service:vps/ha')).toEqual(['route:ha.gf2.in'])
  })
  it('no sem aresta ou lista vazia nao tem vizinho', () => {
    expect(neighborIds(edges, 'machine:vps')).toEqual([])
    expect(neighborIds([], 'route:ha.gf2.in')).toEqual([])
    expect(neighborIds(undefined, 'x')).toEqual([])
  })
})

describe('focusNodeIds', () => {
  it('o no primeiro, depois os vizinhos que estao no mapa', () => {
    const ids = focusNodeIds(nodes, edges, 'route:ha.gf2.in')
    expect(ids[0]).toBe('route:ha.gf2.in')
    expect(ids.slice(1).sort()).toEqual(['entry:cf', 'service:vps/ha'])
  })
  it('vizinho que nao esta no mapa fica de fora', () => {
    expect(focusNodeIds(nodes.filter((n) => n.id !== 'entry:cf'), edges, 'route:ha.gf2.in')).toEqual(['route:ha.gf2.in', 'service:vps/ha'])
  })
  it('sem foco, ou foco fora do mapa, nao enquadra nada especifico', () => {
    expect(focusNodeIds(nodes, edges, null)).toEqual([])
    expect(focusNodeIds(nodes, edges, 'route:nao.existe')).toEqual([])
  })
})
