import { normalize, searchNodes } from './searchNodes.js'

const n = (id, label, kind = 'SERVICE') => ({ id, data: { label, kind } })
const nodes = [
  n('service:vps/serviço-de-mídia', 'Serviço de Mídia'),
  n('route:ha.gf2.in', 'ha.gf2.in', 'ROUTE'),
  n('route:logs.gf2.in', 'logs.gf2.in', 'ROUTE'),
  n('docker:vps/alpha-home', 'alpha-home', 'CONTAINER'),
  n('service:vps/ha', 'ha'),
]

describe('normalize', () => {
  it('tira acento e maiuscula', () => {
    expect(normalize('  ServIÇO de MÍDIA ')).toBe('servico de midia')
  })
})

describe('searchNodes', () => {
  it('ignora acento: "midia" acha "Mídia" e "mídia" acha "Mídia"', () => {
    expect(searchNodes(nodes, 'midia').map((r) => r.id)).toEqual(['service:vps/serviço-de-mídia'])
    expect(searchNodes(nodes, 'servico').map((r) => r.label)).toEqual(['Serviço de Mídia'])
  })

  it('ignora maiusculas na consulta e no nome', () => {
    expect(searchNodes(nodes, 'ALPHA-HOME').map((r) => r.id)).toEqual(['docker:vps/alpha-home'])
    expect(searchNodes(nodes, 'sErViÇo DE').map((r) => r.id)).toEqual(['service:vps/serviço-de-mídia'])
  })

  it('casa pelo node_key, nao so pelo nome', () => {
    expect(searchNodes(nodes, 'route:ha').map((r) => r.id)).toEqual(['route:ha.gf2.in'])
    expect(searchNodes(nodes, 'docker:vps')[0]).toEqual({ id: 'docker:vps/alpha-home', label: 'alpha-home', kind: 'CONTAINER' })
  })

  it('ordena exato, depois prefixo, depois substring', () => {
    // "ha": exato no servico "ha", prefixo em "ha.gf2.in", substring em "alpha-home" (alpHA)
    expect(searchNodes(nodes, 'ha').map((r) => r.id)).toEqual(['service:vps/ha', 'route:ha.gf2.in', 'docker:vps/alpha-home'])
    // "gf2.in": substring nas duas rotas, empate pelo nome
    expect(searchNodes(nodes, 'gf2.in').map((r) => r.label)).toEqual(['ha.gf2.in', 'logs.gf2.in'])
  })

  it('consulta vazia ou so espacos, ou mapa vazio, devolve lista vazia', () => {
    expect(searchNodes(nodes, '')).toEqual([])
    expect(searchNodes(nodes, '   ')).toEqual([])
    expect(searchNodes(nodes, null)).toEqual([])
    expect(searchNodes([], 'ha')).toEqual([])
    expect(searchNodes(undefined, 'ha')).toEqual([])
  })

  it('sem casamento devolve lista vazia e o limite corta', () => {
    expect(searchNodes(nodes, 'zzz')).toEqual([])
    expect(searchNodes(nodes, 'a', 2)).toHaveLength(2)
  })
})
