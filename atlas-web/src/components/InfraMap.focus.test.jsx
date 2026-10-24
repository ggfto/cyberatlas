import { act, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { InfraMap } from './InfraMap.jsx'
import App from '../App.jsx'
import Embed from '../Embed.jsx'

// O jsdom nao mede os nos, entao o fitView de verdade fica na fila para sempre: aqui ele vira um espiao para ver O
// QUE seria enquadrado. O resto do React Flow e o de verdade.
const { fitView } = vi.hoisted(() => ({ fitView: vi.fn() }))
vi.mock('@xyflow/react', async (importOriginal) => {
  const rf = await importOriginal()
  return { ...rf, useReactFlow: () => ({ ...rf.useReactFlow(), fitView }) }
})

const node = (id, label, kind = 'SERVICE') => ({ id, type: 'infra', position: { x: 0, y: 0 }, data: { label, kind, state: 'online' } })
const nodes = [
  node('entry:cf', 'cloudflare', 'ENTRY'),
  node('route:ha.gf2.in', 'ha.gf2.in', 'ROUTE'),
  node('service:vps/ha', 'home-assistant'),
  node('route:logs.gf2.in', 'logs.gf2.in', 'ROUTE'),
]
const edges = [
  { id: 'e1', source: 'entry:cf', target: 'route:ha.gf2.in' },
  { id: 'e2', source: 'route:ha.gf2.in', target: 'service:vps/ha' },
  { id: 'e3', source: 'entry:cf', target: 'route:logs.gf2.in' },
]
const fitted = (call) => call[0].nodes.map((n) => n.id)

// folga como no Embed.test: com a maquina carregada o primeiro render do React Flow passa dos 5 s padrao
const SLOW = { timeout: 30000 }

beforeEach(() => fitView.mockClear())
afterEach(() => {
  vi.restoreAllMocks()
  delete globalThis.EventSource
})

describe('InfraMap com ?focus=', SLOW, () => {
  it('?focus=route:ha.gf2.in enquadra o no e os vizinhos, e so eles', () => {
    const { container } = render(<InfraMap nodes={nodes} edges={edges} exportable={false} focus="route:ha.gf2.in" />)
    expect(fitView).toHaveBeenCalledTimes(1)
    const ids = fitted(fitView.mock.calls[0])
    expect(ids[0]).toBe('route:ha.gf2.in')
    expect(ids.slice(1).sort()).toEqual(['entry:cf', 'service:vps/ha'])
    expect(fitView.mock.calls[0][0].maxZoom).toBe(1.5)
    const focused = container.querySelectorAll('[data-focused="true"]')
    expect(focused).toHaveLength(1)
    expect(focused[0]).toHaveTextContent('ha.gf2.in')
  })

  it('sem focus nao enquadra no especifico (fica o fitView do mapa inteiro)', () => {
    render(<InfraMap nodes={nodes} edges={edges} exportable={false} />)
    expect(fitView).not.toHaveBeenCalled()
  })

  it('o no chega depois (snapshot do stream): enquadra quando aparece, uma vez so', () => {
    const { rerender } = render(<InfraMap nodes={[]} edges={[]} exportable={false} focus="route:ha.gf2.in" />)
    expect(fitView).not.toHaveBeenCalled()
    rerender(<InfraMap nodes={nodes} edges={edges} exportable={false} focus="route:ha.gf2.in" />)
    expect(fitView).toHaveBeenCalledTimes(1)
    // patch seguinte (lista nova) nao arranca o viewport de quem ja esta navegando
    rerender(<InfraMap nodes={[...nodes]} edges={[...edges]} exportable={false} focus="route:ha.gf2.in" />)
    expect(fitView).toHaveBeenCalledTimes(1)
  })
})

describe('busca (Ctrl/Cmd+K)', SLOW, () => {
  it('fechada nao desenha campo; Ctrl+K abre, Enter centraliza no primeiro resultado e fecha', () => {
    const { container } = render(<InfraMap nodes={nodes} edges={edges} exportable={false} />)
    expect(container.querySelector('input')).toBeNull()
    fireEvent.keyDown(window, { key: 'k', ctrlKey: true })
    const box = screen.getByRole('searchbox', { name: 'buscar nó' })
    expect(box).toHaveFocus()
    fireEvent.change(box, { target: { value: 'HA.GF2' } })
    expect(screen.getAllByRole('option').map((o) => o.textContent)).toEqual(['ha.gf2.inroute:ha.gf2.in'])
    fireEvent.keyDown(box, { key: 'Enter' })
    expect(fitView).toHaveBeenCalledTimes(1)
    expect(fitted(fitView.mock.calls[0])).toEqual(['route:ha.gf2.in'])
    expect(screen.queryByTestId('node-search')).not.toBeInTheDocument()
  })

  it('Cmd+K tambem abre; setas escolhem; clique centraliza', () => {
    render(<InfraMap nodes={nodes} edges={edges} exportable={false} />)
    fireEvent.keyDown(window, { key: 'K', metaKey: true })
    const box = screen.getByRole('searchbox')
    fireEvent.change(box, { target: { value: 'gf2.in' } })
    const opts = screen.getAllByRole('option')
    expect(opts.map((o) => o.querySelector('div').textContent)).toEqual(['ha.gf2.in', 'logs.gf2.in'])
    fireEvent.keyDown(box, { key: 'ArrowDown' })
    expect(screen.getAllByRole('option')[1]).toHaveAttribute('aria-selected', 'true')
    fireEvent.mouseDown(screen.getAllByRole('option')[1])
    expect(fitted(fitView.mock.calls[0])).toEqual(['route:logs.gf2.in'])
  })

  it('Esc fecha sem enquadrar nada; sem resultado avisa', () => {
    render(<InfraMap nodes={nodes} edges={edges} exportable={false} />)
    fireEvent.keyDown(window, { key: 'k', ctrlKey: true })
    const box = screen.getByRole('searchbox')
    fireEvent.change(box, { target: { value: 'zzz' } })
    expect(screen.getByText('Nenhum nó encontrado')).toBeInTheDocument()
    fireEvent.keyDown(box, { key: 'Enter' })
    fireEvent.keyDown(box, { key: 'Escape' })
    expect(screen.queryByRole('searchbox')).not.toBeInTheDocument()
    expect(fitView).not.toHaveBeenCalled()
  })
})

// snapshot do hub: arestas com from/to; os nos ficam dentro da caixa da maquina
const tn = (key, kind, name) => ({ node: { key, kind, name, machine: 'vps', attributes: {} }, status: { state: 'ONLINE' }, drift: [] })
const snap = {
  nodes: [tn('machine:vps', 'MACHINE', 'vps'), tn('entry:cf', 'ENTRY', 'cloudflare'), tn('route:ha.gf2.in', 'ROUTE', 'ha.gf2.in'), tn('service:vps/ha', 'SERVICE', 'home-assistant'), tn('route:logs.gf2.in', 'ROUTE', 'logs.gf2.in')],
  edges: [
    { id: 'e1', from: 'entry:cf', to: 'route:ha.gf2.in', kind: 'ROUTES_TO' },
    { id: 'e2', from: 'route:ha.gf2.in', to: 'service:vps/ha', kind: 'ROUTES_TO' },
    { id: 'e3', from: 'entry:cf', to: 'route:logs.gf2.in', kind: 'ROUTES_TO' },
  ],
}
function mockHub() {
  const table = { '/api/topology': snap, '/api/layout': [], '/api/me': { name: 'bia', roles: [] } }
  return vi.spyOn(globalThis, 'fetch').mockImplementation((url) => {
    const body = table[url]
    return Promise.resolve({ ok: !!body, status: body ? 200 : 404, json: async () => body ?? {} })
  })
}

describe('?focus= nas duas paginas', SLOW, () => {
  it('/?focus=route:ha.gf2.in (pagina normal) enquadra o no e os vizinhos', async () => {
    mockHub()
    render(<App focus="route:ha.gf2.in" />)
    await waitFor(() => expect(fitView).toHaveBeenCalled())
    const ids = fitted(fitView.mock.calls[0])
    expect(ids[0]).toBe('route:ha.gf2.in')
    expect(ids.slice(1).sort()).toEqual(['entry:cf', 'service:vps/ha'])
  })

  it('/embed?focus=route:ha.gf2.in enquadra igual, e a busca funciona sem botao nenhum', async () => {
    mockHub()
    const { container } = render(<Embed focus="route:ha.gf2.in" />)
    await waitFor(() => expect(fitView).toHaveBeenCalled())
    const ids = fitted(fitView.mock.calls[0])
    expect(ids[0]).toBe('route:ha.gf2.in')
    expect(ids.slice(1).sort()).toEqual(['entry:cf', 'service:vps/ha'])
    expect(container.querySelector('.react-flow__minimap')).not.toBeNull()
    expect(container.querySelector('.react-flow__controls')).toBeNull()

    fitView.mockClear()
    act(() => fireEvent.keyDown(window, { key: 'k', ctrlKey: true }))
    fireEvent.change(screen.getByRole('searchbox'), { target: { value: 'home' } })
    expect(screen.queryAllByRole('button')).toHaveLength(0)
    fireEvent.keyDown(screen.getByRole('searchbox'), { key: 'Enter' })
    expect(fitted(fitView.mock.calls[0])).toEqual(['service:vps/ha'])
  })
})
