import { applyPatch } from './patch.js'

const n = (key, state = 'ONLINE') => ({ node: { key, kind: 'SERVICE', name: key, machine: 'vps', attributes: {} }, status: { state, reason: '' }, drift: [] })
const e = (id) => ({ id, from: 'a', to: 'b', kind: 'ROUTES_TO' })

describe('applyPatch', () => {
  it('upsert, remocao e ordem por chave/id, sem mutar o snapshot anterior', () => {
    const before = { generatedAt: 't0', nodes: [n('a'), n('b'), n('velho')], edges: [e('e1'), e('e2')] }
    const after = applyPatch(before, {
      version: 8, base: 7, generatedAt: 't1',
      upsertNodes: [n('b', 'OFFLINE'), n('c')], removeNodes: ['velho'],
      upsertEdges: [e('e3')], removeEdges: ['e1'],
    })
    expect(after.nodes.map((x) => x.node.key)).toEqual(['a', 'b', 'c'])
    expect(after.nodes.find((x) => x.node.key === 'b').status.state).toBe('OFFLINE')
    expect(after.edges.map((x) => x.id)).toEqual(['e2', 'e3'])
    expect(after.generatedAt).toBe('t1')
    expect(before.nodes.map((x) => x.node.key)).toEqual(['a', 'b', 'velho'])
  })

  it('patch vazio ou ausente devolve o mesmo mapa', () => {
    const before = { generatedAt: 't0', nodes: [n('a')], edges: [] }
    expect(applyPatch(before, { upsertNodes: [], removeNodes: [], upsertEdges: [], removeEdges: [] }).nodes).toEqual(before.nodes)
    expect(applyPatch(before, undefined)).toBe(before)
  })
})
