import { act, render, screen, waitFor } from '@testing-library/react'
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
    expect(globalThis.fetch).not.toHaveBeenCalled()
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
})
