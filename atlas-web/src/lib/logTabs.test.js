import { MAX_TABS, MAX_LINES, initialTabs, tabsReducer, logUrl } from './logTabs.js'

const open = (key, label = key) => ({ type: 'open', key, label })

describe('abas de log', () => {
  it('abrir o mesmo alvo duas vezes so ativa a aba que ja existe', () => {
    let s = tabsReducer(initialTabs, open('docker:vps/a'))
    s = tabsReducer(s, open('docker:vps/b'))
    s = tabsReducer(s, open('docker:vps/a'))
    expect(s.tabs.map((t) => t.key)).toEqual(['docker:vps/a', 'docker:vps/b'])
    expect(s.active).toBe('docker:vps/a')
  })

  it('no maximo 4 abas: a quinta substitui a mais antiga', () => {
    let s = initialTabs
    for (const k of ['a', 'b', 'c', 'd', 'e']) s = tabsReducer(s, open(k))
    expect(MAX_TABS).toBe(4)
    expect(s.tabs.map((t) => t.key)).toEqual(['b', 'c', 'd', 'e'])
    expect(s.active).toBe('e')
  })

  it('fechar a aba ativa ativa a vizinha; fechar a ultima esvazia', () => {
    let s = initialTabs
    for (const k of ['a', 'b', 'c']) s = tabsReducer(s, open(k))
    s = tabsReducer(s, { type: 'close', key: 'c' })
    expect(s.active).toBe('b')
    s = tabsReducer(s, { type: 'activate', key: 'a' })
    s = tabsReducer(s, { type: 'close', key: 'a' })
    expect(s.active).toBe('b')
    s = tabsReducer(s, { type: 'close', key: 'b' })
    expect(s).toEqual(initialTabs)
  })

  it('linhas acumulam com teto e dropped soma', () => {
    let s = tabsReducer(initialTabs, open('a'))
    s = tabsReducer(s, { type: 'lines', key: 'a', lines: Array.from({ length: MAX_LINES + 5 }, (_, i) => ({ text: `l${i}` })) })
    expect(s.tabs[0].lines).toHaveLength(MAX_LINES)
    expect(s.tabs[0].lines[0].text).toBe('l5')
    s = tabsReducer(s, { type: 'dropped', key: 'a', n: 3 })
    s = tabsReducer(s, { type: 'dropped', key: 'a', n: 4 })
    expect(s.tabs[0].dropped).toBe(7)
  })

  it('pausa nao perde linha: guarda e solta tudo ao retomar', () => {
    let s = tabsReducer(initialTabs, open('a'))
    s = tabsReducer(s, { type: 'pause', key: 'a', paused: true })
    s = tabsReducer(s, { type: 'lines', key: 'a', lines: [{ text: 'x' }] })
    expect(s.tabs[0].lines).toEqual([])
    s = tabsReducer(s, { type: 'pause', key: 'a', paused: false })
    expect(s.tabs[0].lines.map((l) => l.text)).toEqual(['x'])
  })

  it('end marca a aba e acoes de aba inexistente nao mudam nada', () => {
    let s = tabsReducer(initialTabs, open('a'))
    s = tabsReducer(s, { type: 'end', key: 'a', reason: 'fim do log' })
    expect(s.tabs[0].ended).toBe('fim do log')
    expect(tabsReducer(s, { type: 'lines', key: 'zzz', lines: [{ text: 'x' }] })).toBe(s)
  })

  it('url do stream leva filtro e o modo regex', () => {
    expect(logUrl('docker:vps/a b', '', false)).toBe('/api/logs/stream?node=docker%3Avps%2Fa+b&tail=200')
    expect(logUrl('k8s:vps/lab/pod/x', 'ERRO|panic', true)).toBe('/api/logs/stream?node=k8s%3Avps%2Flab%2Fpod%2Fx&tail=200&filter=ERRO%7Cpanic&regex=true')
  })
})
