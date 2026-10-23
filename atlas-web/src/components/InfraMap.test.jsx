import { render, screen } from '@testing-library/react'
import { ReactFlowProvider } from '@xyflow/react'
import { InfraMap, InfraNode } from './InfraMap.jsx'

describe('InfraMap', () => {
  it('pagina normal: os dois botoes de exportacao e os tres de zoom, sem o de travar interacao', () => {
    const { container } = render(<InfraMap nodes={[]} edges={[]} />)
    expect(screen.getByRole('button', { name: 'exportar como PNG' })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'exportar como SVG' })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'aproximar' })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'afastar' })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'enquadrar o mapa' })).toBeInTheDocument()
    // o de travar/destravar ligaria o arrastar (que grava o layout) por fora do `editable`
    expect(screen.queryByRole('button', { name: 'travar interacao' })).not.toBeInTheDocument()
    expect(screen.getAllByRole('button')).toHaveLength(5)
    expect(container.querySelector('.react-flow__minimap')).not.toBeNull()
  })

  it('controls e exportable desligados (o /embed): nenhum botao, so o minimapa', () => {
    const { container } = render(<InfraMap nodes={[]} edges={[]} controls={false} exportable={false} />)
    expect(screen.queryAllByRole('button')).toHaveLength(0)
    expect(container.querySelector('.react-flow__controls')).toBeNull()
    expect(container.querySelector('.react-flow__minimap')).not.toBeNull()
  })
})

describe('InfraNode', () => {
  it('mostra nome, tipo e o motivo do estado no hover', () => {
    render(
      <ReactFlowProvider>
        <InfraNode data={{ label: 'logs.gf2.in', kind: 'ROUTE', state: 'degraded', reason: 'rota protegida respondeu sem login (200)' }} />
      </ReactFlowProvider>,
    )
    expect(screen.getByText('logs.gf2.in')).toBeInTheDocument()
    expect(screen.getByText('rota')).toBeInTheDocument()
    expect(screen.getByLabelText('degraded')).toHaveClass('bg-amber-500')
    expect(screen.getByTitle('rota protegida respondeu sem login (200)')).toBeInTheDocument()
  })
  it('mostra os selos de drift', () => {
    render(
      <ReactFlowProvider>
        <InfraNode data={{ label: 'argo:vps-dozzle', kind: 'ARGO', state: 'degraded', reason: 'OutOfSync/Healthy', drift: ['outofsync'] }} />
      </ReactFlowProvider>,
    )
    expect(screen.getByText('outofsync')).toBeInTheDocument()
  })
  it('servico que e processo do host diz isso no tipo', () => {
    render(
      <ReactFlowProvider>
        <InfraNode data={{ label: 'clink', kind: 'SERVICE', state: 'online', reason: '', hostProcess: true }} />
      </ReactFlowProvider>,
    )
    expect(screen.getByText('servico · processo no host')).toBeInTheDocument()
  })
})
