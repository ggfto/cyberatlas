import { act, fireEvent, render, screen } from '@testing-library/react'
import { LogPanel } from './LogPanel.jsx'

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
  close() {
    this.closed = true
  }
}

beforeEach(() => {
  FakeEventSource.all = []
  globalThis.EventSource = FakeEventSource
})
afterEach(() => delete globalThis.EventSource)

const line = (text) => ({ ts: '2026-10-19T12:00:00Z', stream: 'stdout', text, truncated: false })

describe('LogPanel', () => {
  it('abre um stream por aba e mostra as linhas, o dropped e o selo de redacao', () => {
    render(<LogPanel targets={[{ key: 'docker:vps/9router', label: '9router' }]} onClose={() => {}} />)
    expect(screen.getByText('redação ativa')).toBeInTheDocument()
    const es = FakeEventSource.all[0]
    expect(es.url).toBe('/api/logs/stream?node=docker%3Avps%2F9router&tail=200')
    act(() => {
      es.emit('line', line('subiu na porta 20128'))
      es.emit('dropped', { n: 12 })
    })
    expect(screen.getByText('subiu na porta 20128')).toBeInTheDocument()
    expect(screen.getByText('12 linhas descartadas')).toBeInTheDocument()
    act(() => es.emit('end', { reason: 'edge desconectado' }))
    expect(screen.getByText('fim: edge desconectado')).toBeInTheDocument()
    expect(es.closed).toBe(true)
  })

  it('aplicar filtro reabre o stream com o filtro; fechar aba fecha o stream', () => {
    const onClose = vi.fn()
    const { rerender } = render(<LogPanel targets={[{ key: 'a', label: 'A' }]} onClose={onClose} />)
    rerender(<LogPanel targets={[{ key: 'a', label: 'A' }, { key: 'b', label: 'B' }]} onClose={onClose} />)
    expect(FakeEventSource.all.map((e) => e.url)).toEqual(['/api/logs/stream?node=a&tail=200', '/api/logs/stream?node=b&tail=200'])
    fireEvent.change(screen.getByLabelText('filtro'), { target: { value: 'ERRO' } })
    fireEvent.click(screen.getByLabelText('regex'))
    fireEvent.submit(screen.getByLabelText('filtro').closest('form'))
    const last = FakeEventSource.all[FakeEventSource.all.length - 1]
    expect(last.url).toBe('/api/logs/stream?node=b&tail=200&filter=ERRO&regex=true')
    expect(FakeEventSource.all[1].closed).toBe(true)
    fireEvent.click(screen.getByLabelText('fechar B'))
    expect(last.closed).toBe(true)
    expect(onClose).toHaveBeenCalledWith('b')
  })

  it('pausar segura as linhas na tela e retomar mostra', () => {
    render(<LogPanel targets={[{ key: 'a', label: 'A' }]} onClose={() => {}} />)
    fireEvent.click(screen.getByText('pausar'))
    act(() => FakeEventSource.all[0].emit('line', line('durante a pausa')))
    expect(screen.queryByText('durante a pausa')).not.toBeInTheDocument()
    fireEvent.click(screen.getByText('retomar'))
    expect(screen.getByText('durante a pausa')).toBeInTheDocument()
  })
})
