import { apiWrite, CSRF_HEADER } from './api.js'

describe('apiWrite', () => {
  it('soma o header anti-CSRF sem perder os do chamador nem o resto do init', async () => {
    const fetchImpl = vi.fn().mockResolvedValue({ ok: true })
    await apiWrite('/api/x', { method: 'POST', headers: { Accept: 'application/json' }, body: '{}' }, fetchImpl)
    const [url, init] = fetchImpl.mock.calls[0]
    expect(url).toBe('/api/x')
    expect(init.method).toBe('POST')
    expect(init.body).toBe('{}')
    expect(init.headers).toEqual({ Accept: 'application/json', [CSRF_HEADER]: '1' })
  })
})
