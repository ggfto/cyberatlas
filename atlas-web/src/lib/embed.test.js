import { isEmbedPath, parseEmbedParams, shouldRenderEmbed } from './embed.js'

describe('isEmbedPath', () => {
  it('so /embed e /embed/ (a mesma regra do SecurityHeaders do hub)', () => {
    expect(isEmbedPath('/embed')).toBe(true)
    expect(isEmbedPath('/embed/')).toBe(true)
    for (const p of ['/', '/embedx', '/embed/x', '//embed', '/api/embed', '/EMBED']) expect(isEmbedPath(p)).toBe(false)
  })
})

describe('parseEmbedParams', () => {
  it('le focus e theme', () => {
    expect(parseEmbedParams('?focus=service%3Avps%2Fx&theme=light')).toEqual({ focus: 'service:vps/x', theme: 'light' })
  })
  it('sem parametros: sem foco, dark; tema desconhecido vira dark', () => {
    expect(parseEmbedParams('')).toEqual({ focus: null, theme: 'dark' })
    expect(parseEmbedParams('?theme=<script>')).toEqual({ focus: null, theme: 'dark' })
  })
})

describe('shouldRenderEmbed', () => {
  const win = (pathname, top) => {
    const w = { location: { pathname } }
    w.self = w
    w.top = top === undefined ? w : top
    return w
  }
  it('fora de moldura: so no /embed', () => {
    expect(shouldRenderEmbed(win('/embed'))).toBe(true)
    expect(shouldRenderEmbed(win('/'))).toBe(false)
  })
  it('enquadrado: sempre o Embed, mesmo fora do /embed (ex.: //embed normalizado pelo Traefik)', () => {
    expect(shouldRenderEmbed(win('//embed', {}))).toBe(true)
    expect(shouldRenderEmbed(win('/', {}))).toBe(true)
  })
  it('top que nem deixa comparar conta como enquadrado', () => {
    const w = { location: { pathname: '/' } }
    Object.defineProperty(w, 'self', { get: () => w })
    Object.defineProperty(w, 'top', { get: () => { throw new Error('SecurityError') } })
    expect(shouldRenderEmbed(w)).toBe(true)
  })
})
