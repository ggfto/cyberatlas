import { act, fireEvent, render, screen, waitFor } from '@testing-library/react'
import Embed from './Embed.jsx'
import { LOGIN_POPUP_PATH, POPUP_POLL_MS } from './lib/embed.js'
import { AUTH_RECHECK_JITTER_MS } from './lib/useTopology.js'

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

// --- issue #20: login top-level a partir do iframe ---

function mockHub(table) {
  return vi.spyOn(globalThis, 'fetch').mockImplementation((url) => {
    const hit = Object.entries(table).find(([k]) => url.startsWith(k))
    const [status, body] = hit ? hit[1] : [404, {}]
    return Promise.resolve({ ok: status < 300, status, json: async () => body })
  })
}

describe('Embed sem sessao (issue #20)', () => {
  afterEach(() => vi.useRealTimers())

  it('em moldura e sem sessao (401 da API): mostra so o botao "Entrar no atlas"', async () => {
    mockHub({ '/api/layout': [401, {}] })
    render(<Embed framed />)
    const btn = await screen.findByRole('button', { name: 'Entrar no atlas' })
    expect(screen.getAllByRole('button')).toEqual([btn])
  })

  it('em moldura e com sessao: nenhum botao', async () => {
    const fetchMock = mockHubAsAdmin()
    render(<Embed framed />)
    await waitFor(() => expect(fetchMock).toHaveBeenCalledWith('/api/layout', expect.anything()))
    act(() => FakeEventSource.all[0].emit('snapshot', snap))
    expect(await screen.findByText('9router')).toBeInTheDocument()
    expect(screen.queryAllByRole('button')).toHaveLength(0)
  }, 30000)

  it('fora de moldura e sem sessao: comportamento de antes, sem botao', async () => {
    const fetchMock = mockHub({ '/api/layout': [401, {}] })
    render(<Embed framed={false} />)
    await waitFor(() => expect(fetchMock).toHaveBeenCalled())
    await act(async () => {})
    expect(screen.queryAllByRole('button')).toHaveLength(0)
  })

  it('end {reason:"auth"} no stream e a sessao nao volta: fecha o stream e mostra o botao', async () => {
    vi.useFakeTimers()
    const fetchMock = mockHubAsAdmin()
    render(<Embed framed />)
    await act(async () => {})
    act(() => FakeEventSource.all[0].emit('snapshot', snap))
    expect(screen.queryAllByRole('button')).toHaveLength(0)

    fetchMock.mockImplementation(() => Promise.resolve({ ok: false, status: 401, json: async () => ({}) }))
    const closed = vi.spyOn(FakeEventSource.all[0], 'close')
    act(() => FakeEventSource.all[0].emit('end', { reason: 'auth' }))
    expect(closed).toHaveBeenCalled()
    await act(async () => vi.advanceTimersByTime(AUTH_RECHECK_JITTER_MS))
    await act(async () => {})
    expect(screen.getByRole('button', { name: 'Entrar no atlas' })).toBeInTheDocument()
    expect(FakeEventSource.all).toHaveLength(1)
  })

  it('end {reason:"auth"} e o hub renovou a sessao: reabre o stream, sem botao', async () => {
    vi.useFakeTimers()
    mockHubAsAdmin()
    render(<Embed framed />)
    await act(async () => {})
    act(() => FakeEventSource.all[0].emit('end', { reason: 'auth' }))
    await act(async () => vi.advanceTimersByTime(AUTH_RECHECK_JITTER_MS))
    await act(async () => {})
    expect(FakeEventSource.all).toHaveLength(2)
    expect(screen.queryAllByRole('button')).toHaveLength(0)
  })

  it('o botao abre o login top-level e o iframe recarrega quando o popup fecha', async () => {
    mockHub({ '/api/layout': [401, {}] })
    const popup = { closed: false }
    const open = vi.spyOn(window, 'open').mockReturnValue(popup)
    const reload = vi.fn()
    render(<Embed framed reload={reload} />)
    const btn = await screen.findByRole('button', { name: 'Entrar no atlas' })

    vi.useFakeTimers()
    fireEvent.click(btn)
    expect(open).toHaveBeenCalledWith(LOGIN_POPUP_PATH, expect.any(String), expect.stringContaining('popup'))
    act(() => vi.advanceTimersByTime(POPUP_POLL_MS * 4))
    expect(reload).not.toHaveBeenCalled()

    popup.closed = true
    act(() => vi.advanceTimersByTime(POPUP_POLL_MS))
    expect(reload).toHaveBeenCalledTimes(1)
    act(() => vi.advanceTimersByTime(POPUP_POLL_MS * 4))
    expect(reload).toHaveBeenCalledTimes(1)
  })

  it('popup bloqueado: avisa, sem recarregar', async () => {
    mockHub({ '/api/layout': [401, {}] })
    vi.spyOn(window, 'open').mockReturnValue(null)
    const reload = vi.fn()
    render(<Embed framed reload={reload} />)
    fireEvent.click(await screen.findByRole('button', { name: 'Entrar no atlas' }))
    expect(screen.getByText(/bloqueou o popup/)).toBeInTheDocument()
    expect(reload).not.toHaveBeenCalled()
  })
})
