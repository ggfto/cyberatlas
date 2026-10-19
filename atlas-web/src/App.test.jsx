import { act, fireEvent, render, screen, waitFor } from '@testing-library/react'
import App from './App.jsx'

class FakeEventSource {
  static last
  constructor(url) {
    this.url = url
    this.listeners = {}
    FakeEventSource.last = this
  }
  addEventListener(name, fn) {
    this.listeners[name] = fn
  }
  emit(name, data) {
    this.listeners[name]?.({ data: JSON.stringify(data) })
  }
  close() {
    this.closed = true
  }
}

afterEach(() => {
  vi.restoreAllMocks()
  delete globalThis.EventSource
})

describe('App', () => {
  it('busca /api/topology e mostra o cabecalho do ui-kit e o canvas', async () => {
    const fetchMock = vi.spyOn(globalThis, 'fetch').mockResolvedValue({ ok: true, json: async () => ({ nodes: [], edges: [] }) })
    render(<App />)
    expect(screen.getByText('Cyber Atlas')).toBeInTheDocument()
    expect(screen.getByTestId('infra-map')).toBeInTheDocument()
    await waitFor(() => expect(fetchMock).toHaveBeenCalledWith('/api/topology', expect.anything()))
  })

  it('erro da API aparece em vez de um mapa vazio silencioso', async () => {
    vi.spyOn(globalThis, 'fetch').mockResolvedValue({ ok: false, status: 503, json: async () => ({}) })
    render(<App />)
    expect(await screen.findByText('Nao foi possivel carregar o mapa (HTTP 503).')).toBeInTheDocument()
  })
  it('usa o stream quando ha EventSource: snapshot e depois patch', async () => {
    globalThis.EventSource = FakeEventSource
    vi.spyOn(globalThis, 'fetch').mockResolvedValue({ ok: true, json: async () => ({ nodes: [], edges: [] }) })
    const { container } = render(<App />)
    expect(FakeEventSource.last.url).toBe('/api/topology/stream')
    expect(globalThis.fetch).not.toHaveBeenCalledWith('/api/topology', expect.anything())
    const tn = (key) => ({ node: { key, kind: 'SERVICE', name: key, machine: 'vps', attributes: {} }, status: { state: 'ONLINE' }, drift: [] })
    const count = (n) => screen.getByText((_, el) => el?.textContent === `${n} NÓS`)
    act(() => FakeEventSource.last.emit('snapshot', { nodes: [tn('service:vps/a')], edges: [] }))
    expect(count(1)).toBeInTheDocument()
    act(() => FakeEventSource.last.emit('patch', { upsertNodes: [tn('service:vps/b')], removeNodes: [], upsertEdges: [], removeEdges: [] }))
    expect(count(2)).toBeInTheDocument()
    act(() => FakeEventSource.last.emit('patch', { upsertNodes: [], removeNodes: ['service:vps/a'], upsertEdges: [], removeEdges: [] }))
    expect(count(1)).toBeInTheDocument()
    expect(container).toBeTruthy()
  })

  it('clique num container abre o painel de log quando os logs estao ligados; em servico nao', async () => {
    globalThis.EventSource = FakeEventSource
    vi.spyOn(globalThis, 'fetch').mockResolvedValue({ ok: true, json: async () => ['docker:vps/9router'] })
    render(<App />)
    await waitFor(() => expect(globalThis.fetch).toHaveBeenCalledWith('/api/logs/targets', expect.anything()))
    const tn = (key, kind, name) => ({ node: { key, kind, name, machine: 'vps', attributes: {} }, status: { state: 'ONLINE' }, drift: [] })
    act(() => FakeEventSource.last.emit('snapshot', {
      nodes: [tn('machine:vps', 'MACHINE', 'vps'), tn('docker:vps/9router', 'CONTAINER', '9router'), tn('service:vps/x', 'SERVICE', 'x')], edges: [],
    }))
    await act(async () => {})
    fireEvent.click(await screen.findByText('x'))
    expect(screen.queryByTestId('log-panel')).not.toBeInTheDocument()
    fireEvent.click(screen.getByText('9router'))
    expect(screen.getByTestId('log-panel')).toBeInTheDocument()
    expect(FakeEventSource.last.url).toBe('/api/logs/stream?node=docker%3Avps%2F9router&tail=200')
    fireEvent.click(screen.getByLabelText('fechar 9router'))
    expect(screen.queryByTestId('log-panel')).not.toBeInTheDocument()
  })

  it('logs desligados no hub (404): clique em container nao abre nada', async () => {
    globalThis.EventSource = FakeEventSource
    vi.spyOn(globalThis, 'fetch').mockResolvedValue({ ok: false, status: 404, json: async () => ({}) })
    render(<App />)
    const tn = (key, kind, name) => ({ node: { key, kind, name, machine: 'vps', attributes: {} }, status: { state: 'ONLINE' }, drift: [] })
    act(() => FakeEventSource.last.emit('snapshot', { nodes: [tn('machine:vps', 'MACHINE', 'vps'), tn('docker:vps/9router', 'CONTAINER', '9router')], edges: [] }))
    await waitFor(() => expect(globalThis.fetch).toHaveBeenCalledWith('/api/logs/targets', expect.anything()))
    fireEvent.click(await screen.findByText('9router'))
    expect(screen.queryByTestId('log-panel')).not.toBeInTheDocument()
  })

  const route = (table) => (url) => {
    const hit = Object.entries(table).find(([k]) => url.startsWith(k))
    const [status, body] = hit ? hit[1] : [404, {}]
    return Promise.resolve({ ok: status < 300, status, json: async () => body })
  }
  const tn = (key, kind, name) => ({ node: { key, kind, name, machine: 'vps', attributes: {} }, status: { state: 'ONLINE' }, drift: [] })
  const snap = { nodes: [tn('machine:vps', 'MACHINE', 'vps'), tn('service:vps/x', 'SERVICE', 'x')], edges: [] }

  it('posicao manual do /api/layout vence a automatica e so admin arrasta', async () => {
    globalThis.EventSource = FakeEventSource
    vi.spyOn(globalThis, 'fetch').mockImplementation(route({
      '/api/me': [200, { name: 'bia', roles: ['atlas-admins'] }],
      '/api/layout': [200, [{ nodeKey: 'service:vps/x', x: 77, y: 66, rev: 2 }]],
      '/api/logs/targets': [200, []],
    }))
    const { container } = render(<App />)
    await waitFor(() => expect(globalThis.fetch).toHaveBeenCalledWith('/api/layout', expect.anything()))
    act(() => FakeEventSource.last.emit('snapshot', snap))
    await act(async () => {})
    // posicao no DOM nao serve no jsdom (o React Flow prende o filho na caixa do pai, que mede 0): o no mostra o pino
    expect(await screen.findByLabelText('posição manual')).toBeInTheDocument()
    expect(container.querySelector('[data-id="service:vps/x"]').classList.contains('draggable')).toBe(true)
  })

  it('viewer nao arrasta', async () => {
    globalThis.EventSource = FakeEventSource
    vi.spyOn(globalThis, 'fetch').mockImplementation(route({
      '/api/me': [200, { name: 'ana', roles: ['atlas-viewers'] }],
      '/api/layout': [200, []],
      '/api/logs/targets': [403, {}],
    }))
    const { container } = render(<App />)
    await waitFor(() => expect(globalThis.fetch).toHaveBeenCalledWith('/api/me', expect.anything()))
    act(() => FakeEventSource.last.emit('snapshot', snap))
    await act(async () => {})
    expect(container.querySelector('[data-id="service:vps/x"]').classList.contains('draggable')).toBe(false)
    expect(screen.queryByLabelText('posição manual')).not.toBeInTheDocument()
  })

  it('sessao expirada: o stream cai, /api/me da 401 e aparece o link para entrar de novo', async () => {
    globalThis.EventSource = FakeEventSource
    let expired = false
    vi.spyOn(globalThis, 'fetch').mockImplementation((url) =>
      route({ '/api/me': expired ? [401, {}] : [200, { name: 'ana', roles: ['atlas-viewers'] }], '/api/layout': [200, []] })(url))
    render(<App />)
    await waitFor(() => expect(globalThis.fetch).toHaveBeenCalledWith('/api/me', expect.anything()))
    expired = true
    act(() => FakeEventSource.last.listeners.error?.({}))
    const link = await screen.findByText('Sessão expirada: entrar de novo')
    expect(link.getAttribute('href')).toBe('/')
  })
})

