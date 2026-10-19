import { applyLayout, saveMove } from './layout.js'

const auto = [
  { id: 'group:vps', type: 'machineGroup', position: { x: 0, y: 0 } },
  { id: 'service:vps/a', type: 'infra', parentId: 'group:vps', position: { x: 10, y: 20 }, data: {} },
  { id: 'service:vps/b', type: 'infra', parentId: 'group:vps', position: { x: 30, y: 40 }, data: {} },
]

describe('layout manual', () => {
  it('posicao manual vence a automatica so nos nos que tem linha', () => {
    const out = applyLayout(auto, { 'service:vps/a': { x: 99, y: 88, rev: 3 } })
    expect(out[1].position).toEqual({ x: 99, y: 88 })
    expect(out[1].data.rev).toBe(3)
    expect(out[2].position).toEqual({ x: 30, y: 40 })
    expect(out[2].data.rev).toBe(0)
    expect(auto[1].position).toEqual({ x: 10, y: 20 })
  })

  it('caixa de maquina nao recebe layout manual', () => {
    const out = applyLayout(auto, { 'group:vps': { x: 5, y: 5, rev: 1 } })
    expect(out[0].position).toEqual({ x: 0, y: 0 })
  })

  const reply = (status, body) => ({ ok: status < 300, status, json: async () => body })

  it('PATCH com If-Match da rev e a chave crua no caminho', async () => {
    const fetchImpl = vi.fn().mockResolvedValue(reply(200, { nodeKey: 'service:vps/a', x: 1, y: 2, rev: 4 }))
    const saved = await saveMove('service:vps/a', { x: 1, y: 2 }, 3, fetchImpl)
    expect(saved.rev).toBe(4)
    const [url, init] = fetchImpl.mock.calls[0]
    expect(url).toBe('/api/layout/nodes/service:vps/a')
    expect(init.method).toBe('PATCH')
    expect(init.headers['If-Match']).toBe('"3"')
    expect(init.headers['X-Atlas-Csrf']).toBe('1')
    expect(JSON.parse(init.body)).toEqual({ x: 1, y: 2 })
  })

  it('409: posicao vence o ultimo, entao reenvia uma vez com a rev atual', async () => {
    const fetchImpl = vi.fn()
      .mockResolvedValueOnce(reply(409, { current: { rev: 7 } }))
      .mockResolvedValueOnce(reply(200, { rev: 8, x: 1, y: 2 }))
    expect((await saveMove('k', { x: 1, y: 2 }, 3, fetchImpl)).rev).toBe(8)
    expect(fetchImpl.mock.calls[1][1].headers['If-Match']).toBe('"7"')
  })

  it('409 sem valor atual (alguem voltou ao automatico) reenvia criando', async () => {
    const fetchImpl = vi.fn()
      .mockResolvedValueOnce(reply(409, { current: null }))
      .mockResolvedValueOnce(reply(200, { rev: 1 }))
    await saveMove('k', { x: 1, y: 2 }, 3, fetchImpl)
    expect(fetchImpl.mock.calls[1][1].headers['If-Match']).toBe('"0"')
  })

  it('conflito de novo ou erro: falha sem laco infinito', async () => {
    const fetchImpl = vi.fn().mockResolvedValue(reply(409, { current: { rev: 9 } }))
    await expect(saveMove('k', { x: 1, y: 2 }, 3, fetchImpl)).rejects.toThrow('conflito')
    expect(fetchImpl).toHaveBeenCalledTimes(2)
    await expect(saveMove('k', { x: 1, y: 2 }, 3, vi.fn().mockResolvedValue(reply(403, {})))).rejects.toThrow('HTTP 403')
  })
})
