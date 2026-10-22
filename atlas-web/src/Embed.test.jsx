import { act, fireEvent, render, screen, waitFor } from '@testing-library/react'
import Embed from './Embed.jsx'

class FakeEventSource {
  static all = []
  constructor(url) {
    this.url = url
    this.listeners = {}
    FakeEventSource.all.push(this)
  }
  addEventListener(name, fn) {
    this.listeners[name] = fn
  }
  emit(name, data) {
    this.listeners[name]?.({ data: JSON.stringify(data) })
  }
  close() {}
}

const tn = (key, kind, name) => ({ node: { key, kind, name, machine: 'vps', attributes: {} }, status: { state: 'ONLINE' }, drift: [] })
const snap = {
  nodes: [tn('machine:vps', 'MACHINE', 'vps'), tn('service:vps/x', 'SERVICE', 'x'), tn('docker:vps/9router', 'CONTAINER', '9router')],
  edges: [],
}

// O hub responde como se quem abriu fosse admin com logs ligados: o embed tem que ignorar tudo isso.
function mockHubAsAdmin() {
  const table = {
    '/api/me': [200, { name: 'bia', roles: ['atlas-admins'] }],
    '/api/layout': [200, [{ nodeKey: 'service:vps/x', x: 77, y: 66, rev: 2 }]],
    '/api/logs/targets': [200, ['docker:vps/9router']],
  }
  return vi.spyOn(globalThis, 'fetch').mockImplementation((url) => {
    const hit = Object.entries(table).find(([k]) => url.startsWith(k))
    const [status, body] = hit ? hit[1] : [404, {}]
    return Promise.resolve({ ok: status < 300, status, json: async () => body })
  })
}

beforeEach(() => {
  FakeEventSource.all = []
  globalThis.EventSource = FakeEventSource
})
afterEach(() => {
  vi.restoreAllMocks()
  delete globalThis.EventSource
  document.documentElement.classList.add('dark')
})

describe('Embed (/embed, so leitura)', () => {
  it('nao renderiza nenhum controle de escrita, mesmo para admin com logs ligados', async () => {
    const fetchMock = mockHubAsAdmin()
    const { container } = render(<Embed />)
    await waitFor(() => expect(fetchMock).toHaveBeenCalledWith('/api/layout', expect.anything()))
    act(() => FakeEventSource.all[0].emit('snapshot', snap))
    await act(async () => {})

    expect(await screen.findByText('9router')).toBeInTheDocument()
    // nenhum botao, campo ou formulario
    expect(screen.queryAllByRole('button')).toHaveLength(0)
    expect(container.querySelectorAll('input, textarea, select, form')).toHaveLength(0)
    // nenhum no arrastavel (arrastar grava o layout)
    expect(container.querySelectorAll('.react-flow__node.draggable')).toHaveLength(0)
    // clique em container nao abre log
    fireEvent.click(screen.getByText('9router'))
    expect(screen.queryByTestId('log-panel')).not.toBeInTheDocument()
    await act(async () => {})

    // so leitura: so GET do layout e o stream da topologia; nada de /api/me, logs, edges ou enrollment
    const urls = fetchMock.mock.calls.map(([url]) => url)
    expect(new Set(urls)).toEqual(new Set(['/api/layout']))
    for (const [, init] of fetchMock.mock.calls) expect(init?.method ?? 'GET').toBe('GET')
    expect(FakeEventSource.all.map((e) => e.url)).toEqual(['/api/topology/stream'])
    // folga: com a maquina carregada (build do hub em paralelo) o primeiro render do React Flow passou dos 5 s
  }, 30000)

  it('?focus destaca o no pedido', async () => {
    mockHubAsAdmin()
    const { container } = render(<Embed focus="service:vps/x" />)
    act(() => FakeEventSource.all[0].emit('snapshot', snap))
    await act(async () => {})
    const focused = container.querySelectorAll('[data-focused="true"]')
    expect(focused).toHaveLength(1)
    expect(focused[0]).toHaveTextContent('x')
  })

  it('?theme=light tira o dark do <html>; dark poe de volta', async () => {
    mockHubAsAdmin()
    const { rerender } = render(<Embed theme="light" />)
    expect(document.documentElement.classList.contains('dark')).toBe(false)
    rerender(<Embed theme="dark" />)
    expect(document.documentElement.classList.contains('dark')).toBe(true)
  })
})
